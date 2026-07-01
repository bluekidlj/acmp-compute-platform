package com.acmp.compute.service;

import com.acmp.compute.dto.PoolCardRequest;
import com.acmp.compute.dto.PoolCardResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.PoolCard;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.PoolCardMapper;
import com.acmp.compute.mapper.ProjectResourceQuotaMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 异构算力：池里加卡/删卡/列卡。
 *
 * <p>核心公式：slots = cardMemMb / spec.defaultGpumemMb（VIRTUAL）；PHYSICAL/OVERSELL = 1
 *
 * <p>一致性保证：
 * <ul>
 *   <li>加卡：写 pool_card + 重算 pool.totalNodes + 同步 K8s ResourceQuota</li>
 *   <li>删卡：校验 prq.used ≤ 剩余 slots（force=true 截断）+ 重算 + 同步 K8s</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoolCardService {

    private final PoolCardMapper poolCardMapper;
    private final ResourcePoolMapper poolMapper;
    private final ComputeSpecMapper specMapper;
    private final ProjectResourceQuotaMapper projectQuotaMapper;
    private final KubernetesClientManager clientManager;
    private final WorkspaceMapper workspaceMapper;

    /**
     * 加卡到池。
     */
    @Transactional
    public PoolCard addCard(String poolId, PoolCardRequest req) {
        ResourcePool pool = poolMapper.findById(poolId)
            .orElseThrow(() -> new ResourceNotFoundException("资源池不存在"));
        ComputeSpec spec = specMapper.findById(req.getSpecId())
            .orElseThrow(() -> new BadRequestException("规格不存在: " + req.getSpecId()));

        if (!pool.getPoolType().equals(spec.getPoolType())) {
            throw new BadRequestException(String.format(
                "池 %s 类型 %s 与规格 %s 类型 %s 不匹配",
                pool.getName(), pool.getPoolType(), spec.getName(), spec.getPoolType()));
        }
        String specBrand = spec.getGpuBrand() != null ? spec.getGpuBrand().name() : "NVIDIA";
        if (!req.getGpuBrand().equalsIgnoreCase(specBrand)) {
            throw new BadRequestException(String.format(
                "卡品牌 %s 与规格品牌 %s 不匹配", req.getGpuBrand(), specBrand));
        }
        if (poolCardMapper.exists(poolId, req.getNodeName(), req.getSerialNo(), req.getSpecId()) > 0) {
            throw new BadRequestException("同一卡 + 同一 spec 已在池中（UNIQUE 约束）");
        }

        int slots = computeSlots(req.getGpuBrand(), req.getGpuModel(), spec);

        // 确保 spec 已关联到池（K8s ResourceQuota 需要）
        if (specMapper.findSpecIdsByResourcePoolId(poolId).stream().noneMatch(id -> id.equals(req.getSpecId()))) {
            specMapper.insertResourcePoolSpec(poolId, req.getSpecId());
        }

        PoolCard card = PoolCard.builder()
            .id(UUID.randomUUID().toString())
            .poolId(poolId)
            .gpuBrand(req.getGpuBrand())
            .gpuModel(req.getGpuModel())
            .nodeName(req.getNodeName())
            .serialNo(req.getSerialNo())
            .specId(req.getSpecId())
            .slots(slots)
            .status("active")
            .build();
        poolCardMapper.insert(card);

        try {
            recomputePoolAndSyncK8s(pool);
        } catch (Exception e) {
            log.warn("⚠️ 池重算/K8s 同步失败（继续）: {}", e.getMessage());
        }

        log.info("✓ 卡 {} 加入池 {} (slots={})", card.getId(), poolId, slots);
        return card;
    }

    /**
     * 删卡。
     */
    @Transactional
    public void removeCard(String poolId, String cardId, boolean force) {
        PoolCard card = poolCardMapper.findById(cardId);
        if (card == null) throw new ResourceNotFoundException("卡不存在");
        if (!card.getPoolId().equals(poolId)) {
            throw new BadRequestException("卡不属于该池");
        }
        ResourcePool pool = poolMapper.findById(poolId).orElseThrow();

        int poolSpecSlotsAfter = poolCardMapper.sumActiveSlotsByPoolAndSpec(poolId, card.getSpecId())
            - card.getSlots();
        int maxUsedForSpec = projectQuotaMapper.sumUsedByPoolAndSpec(poolId, card.getSpecId());
        if (maxUsedForSpec > poolSpecSlotsAfter && !force) {
            throw new BadRequestException(String.format(
                "该 spec 已被项目用 %d 个，删卡后池剩余 %d，不够用。请先清理 prq 或加 ?force=true 强制（强制后超用 prq 会被截断）",
                maxUsedForSpec, poolSpecSlotsAfter));
        }

        poolCardMapper.deleteById(cardId);

        if (force && maxUsedForSpec > poolSpecSlotsAfter) {
            projectQuotaMapper.capUsedByPoolAndSpec(poolId, card.getSpecId(), poolSpecSlotsAfter);
            log.warn("⚠️ 强制删卡：截断 prq.used 到 {}", poolSpecSlotsAfter);
        }

        try {
            recomputePoolAndSyncK8s(pool);
        } catch (Exception e) {
            log.warn("⚠️ 池重算/K8s 同步失败（继续）: {}", e.getMessage());
        }

        log.info("✓ 卡 {} 已从池 {} 移除", cardId, poolId);
    }

    /**
     * 列池里所有卡 + 按 spec 汇总。
     */
    public PoolCardResponse.ListResponse listByPool(String poolId) {
        if (poolMapper.findById(poolId).isEmpty()) {
            throw new ResourceNotFoundException("资源池不存在");
        }
        List<PoolCard> cards = poolCardMapper.findByPoolId(poolId);
        List<PoolCardResponse> cardDtos = new ArrayList<>();
        Map<String, PoolCardResponse.SpecSummary> bySpec = new LinkedHashMap<>();
        int total = 0;
        for (PoolCard c : cards) {
            cardDtos.add(PoolCardResponse.from(c));
            total += c.getSlots() != null ? c.getSlots() : 0;
            bySpec.compute(c.getSpecId(), (k, v) -> {
                int slots = c.getSlots() != null ? c.getSlots() : 0;
                if (v == null) return PoolCardResponse.SpecSummary.builder()
                    .cards(1).slots(slots).build();
                return PoolCardResponse.SpecSummary.builder()
                    .cards(v.getCards() + 1).slots(v.getSlots() + slots).build();
            });
        }
        return PoolCardResponse.ListResponse.builder()
            .poolId(poolId)
            .totalNodes(total)
            .cards(cardDtos)
            .bySpec(bySpec)
            .build();
    }

    /** 重算池 capacity + allocated + 同步 K8s ResourceQuota */
    private void recomputePoolAndSyncK8s(ResourcePool pool) {
        int newTotal = poolCardMapper.sumActiveSlotsByPool(pool.getId());
        poolMapper.updateCapacity(pool.getId(), newTotal);

        Workspace ws = workspaceMapper.findById(pool.getWorkspaceId()).orElseThrow();
        List<ComputeSpec> specs = specMapper.findByResourcePoolId(pool.getId());
        if (specs.isEmpty()) return;

        Map<String, String> hard = new LinkedHashMap<>();
        for (ComputeSpec s : specs) {
            int slots = poolCardMapper.sumActiveSlotsByPoolAndSpec(pool.getId(), s.getId());
            hard.put(s.getResourceQuotaKey(), String.valueOf(slots));
        }
        clientManager.createResourceQuotaBySpec(
            pool.getPrimaryClusterId(), ws.getNamespace(),
            "quota-" + pool.getPoolType().toLowerCase() + "-" + pool.getId().substring(0, 8),
            hard, Math.max(50, newTotal * 10));
    }

    /** 1 张卡 + 1 spec = N 节点 */
    public int computeSlots(String brand, String model, ComputeSpec spec) {
        if ("OVERSELL".equals(spec.getSpecType())) return 1;
        if ("PHYSICAL".equals(spec.getSpecType())) return 1;
        if ("VIRTUAL".equals(spec.getSpecType())) {
            if (spec.getDefaultGpumemMb() == null || spec.getDefaultGpumemMb() == 0) return 1;
            int cardMem = getCardMemMb(brand, model);
            return cardMem / spec.getDefaultGpumemMb();
        }
        return 1;
    }

    private int getCardMemMb(String brand, String model) {
        if ("NVIDIA".equals(brand)) {
            if (model.contains("A100") || model.contains("H100")) return 81920;
            if (model.contains("V100")) return 16384;
        }
        if ("HYGON".equals(brand)) return 16384;
        if ("HUAWEI_ASCEND".equals(brand)) return 65536;
        return 16384;
    }
}
