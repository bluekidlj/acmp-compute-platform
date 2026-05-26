package com.acmp.compute.service;

import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ComputeSpecMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 双层配额服务：
 *  L1 - 逻辑池规格配额 (resource_pool_spec_quota.allocated_quota)
 *  L2 - 工作空间规格配额 (workspace_pool_spec_quota.used_quota)
 *
 * 部署/训练流程：
 *  ① validateBothLevelQuotas
 *  ② deductBothLevelQuotas
 *  ③ K8s 操作（失败时回滚）
 *  ④ 删除时 rollbackBothLevelQuotas
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final ComputeSpecMapper computeSpecMapper;

    // ─────────────────────────── L1: 逻辑池规格配额 ───────────────────────────

    public void validatePoolLevelQuota(String resourcePoolId, String specId, int requestedUnits) {
        Map<String, Object> quota = findPoolSpecQuotaRow(resourcePoolId, specId);
        int total = toInt(quota.get("total_quota"));
        int allocated = toInt(quota.get("allocated_quota"));
        int available = total - allocated;
        if (available < requestedUnits) {
            throw new BadRequestException(String.format(
                    "逻辑池级配额不足: 池=%s, 规格=%s, 需要=%d, 可用=%d (total=%d, allocated=%d)",
                    resourcePoolId, specId, requestedUnits, available, total, allocated));
        }
        log.debug("✓ L1 配额校验通过: 池={}, 规格={}, 请求={}, 可用={}",
                resourcePoolId, specId, requestedUnits, available);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deductPoolLevelQuota(String resourcePoolId, String specId, int units) {
        Map<String, Object> quota = findPoolSpecQuotaRow(resourcePoolId, specId);
        int newAllocated = toInt(quota.get("allocated_quota")) + units;
        computeSpecMapper.updateResourcePoolSpecAllocated(resourcePoolId, specId, newAllocated);
        log.debug("→ L1 配额已扣减: 池={}, 规格={}, units={}, allocated={}",
                resourcePoolId, specId, units, newAllocated);
    }

    @Transactional(rollbackFor = Exception.class)
    public void rollbackPoolLevelQuota(String resourcePoolId, String specId, int units) {
        Map<String, Object> quota = findPoolSpecQuotaRow(resourcePoolId, specId);
        int newAllocated = Math.max(0, toInt(quota.get("allocated_quota")) - units);
        computeSpecMapper.updateResourcePoolSpecAllocated(resourcePoolId, specId, newAllocated);
        log.debug("← L1 配额已回滚: 池={}, 规格={}, units={}, allocated={}",
                resourcePoolId, specId, units, newAllocated);
    }

    // ─────────────────────────── L2: 工作空间规格配额 ───────────────────────────

    public void validateWorkspaceLevelQuota(String workspaceId, String resourcePoolId, String specId, int requestedUnits) {
        Map<String, Object> quota = findWorkspaceSpecQuotaRow(workspaceId, resourcePoolId, specId);
        int max = toInt(quota.get("max_quota"));
        int used = toInt(quota.get("used_quota"));
        int available = max - used;
        if (available < requestedUnits) {
            throw new BadRequestException(String.format(
                    "工作空间级配额不足: ws=%s, 规格=%s, 需要=%d, 可用=%d (max=%d, used=%d)",
                    workspaceId, specId, requestedUnits, available, max, used));
        }
        log.debug("✓ L2 配额校验通过: ws={}, 规格={}, 请求={}, 可用={}",
                workspaceId, specId, requestedUnits, available);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deductWorkspaceLevelQuota(String workspaceId, String resourcePoolId, String specId, int units) {
        Map<String, Object> quota = findWorkspaceSpecQuotaRow(workspaceId, resourcePoolId, specId);
        int newUsed = toInt(quota.get("used_quota")) + units;
        computeSpecMapper.updateWorkspaceSpecUsed(workspaceId, resourcePoolId, specId, newUsed);
        log.debug("→ L2 配额已扣减: ws={}, 规格={}, units={}, used={}",
                workspaceId, specId, units, newUsed);
    }

    @Transactional(rollbackFor = Exception.class)
    public void rollbackWorkspaceLevelQuota(String workspaceId, String resourcePoolId, String specId, int units) {
        Map<String, Object> quota = findWorkspaceSpecQuotaRow(workspaceId, resourcePoolId, specId);
        int newUsed = Math.max(0, toInt(quota.get("used_quota")) - units);
        computeSpecMapper.updateWorkspaceSpecUsed(workspaceId, resourcePoolId, specId, newUsed);
        log.debug("← L2 配额已回滚: ws={}, 规格={}, units={}, used={}",
                workspaceId, specId, units, newUsed);
    }

    // ─────────────────────────── 双层封装 ───────────────────────────

    public void validateBothLevelQuotas(String resourcePoolId, String workspaceId, String specId, int requestedUnits) {
        validatePoolLevelQuota(resourcePoolId, specId, requestedUnits);
        validateWorkspaceLevelQuota(workspaceId, resourcePoolId, specId, requestedUnits);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deductBothLevelQuotas(String resourcePoolId, String workspaceId, String specId, int units) {
        deductPoolLevelQuota(resourcePoolId, specId, units);
        deductWorkspaceLevelQuota(workspaceId, resourcePoolId, specId, units);
        log.info("✓ 双层配额已扣减: 池={}, ws={}, 规格={}, units={}",
                resourcePoolId, workspaceId, specId, units);
    }

    @Transactional(rollbackFor = Exception.class)
    public void rollbackBothLevelQuotas(String resourcePoolId, String workspaceId, String specId, int units) {
        rollbackWorkspaceLevelQuota(workspaceId, resourcePoolId, specId, units);
        rollbackPoolLevelQuota(resourcePoolId, specId, units);
        log.info("✓ 双层配额已回滚: 池={}, ws={}, 规格={}, units={}",
                resourcePoolId, workspaceId, specId, units);
    }

    // ─────────────────────────── helpers ───────────────────────────

    private Map<String, Object> findPoolSpecQuotaRow(String resourcePoolId, String specId) {
        List<Map<String, Object>> all = computeSpecMapper.findSpecQuotasByResourcePoolId(resourcePoolId);
        return all.stream()
                .filter(q -> Objects.equals(q.get("spec_id"), specId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "逻辑池 " + resourcePoolId + " 未配置规格 " + specId + " 的配额"));
    }

    private Map<String, Object> findWorkspaceSpecQuotaRow(String workspaceId, String resourcePoolId, String specId) {
        List<Map<String, Object>> all = computeSpecMapper.findSpecQuotasByWorkspaceId(workspaceId);
        return all.stream()
                .filter(q -> Objects.equals(q.get("spec_id"), specId)
                        && Objects.equals(q.get("resource_pool_id"), resourcePoolId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "工作空间 " + workspaceId + " 在池 " + resourcePoolId + " 中未配置规格 " + specId + " 的配额"));
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }
}
