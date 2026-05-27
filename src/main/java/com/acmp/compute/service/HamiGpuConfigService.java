package com.acmp.compute.service;

import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.HamiGpuConfig;
import com.acmp.compute.entity.HamiVgpuUnit;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.HamiGpuConfigMapper;
import com.acmp.compute.mapper.HamiVgpuUnitMapper;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 【HAMi vGPU】GPU 切分配置服务。
 *
 * 核心职责：
 *  1. CRUD HAMi GPU 切分主配置（hami_gpu_config）
 *  2. CRUD vGPU 单元明细（hami_vgpu_unit）
 *  3. 将 ComputeSpec 绑定到 vGPU 单元（specType=VIRTUAL）
 *  4. 手动同步 vGPU 可用数量（从 K8s 节点 allocatable 查询）
 *
 * 设计原则：
 *  - HAMi 切分配置由管理员手动管理（不由平台自动发现）
 *  - available_count 由管理员手动触发同步（不对 K8s 做实时 Watch）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HamiGpuConfigService {

    private final HamiGpuConfigMapper hamiGpuConfigMapper;
    private final HamiVgpuUnitMapper hamiVgpuUnitMapper;
    private final PhysicalClusterMapper physicalClusterMapper;
    private final ComputeSpecMapper computeSpecMapper;
    private final KubernetesClientManager clientManager;

    // ─────────────────────────── GPU 切分配置 CRUD ───────────────────────────

    /**
     * 创建 GPU 切分配置，同时批量创建 vGPU 单元。
     *
     * @param physicalClusterId 物理集群 ID
     * @param gpuType GPU 型号，如 "A100-80GB-SXM"
     * @param gpuMemMb 整卡显存 MB
     * @param gpuCores 整卡算力占比 0-100
     * @param totalVgpuCount 切出的 vGPU 总数
     * @param nodeSelectorKey 节点标签 key，如 "pool"
     * @param nodeSelectorPrefix 节点标签前缀，如 "v100-"
     * @param vgpuUnits vGPU 单元列表（必须与 totalVgpuCount 一致）
     */
    @Transactional(rollbackFor = Exception.class)
    public HamiGpuConfig createGpuConfig(String physicalClusterId, String gpuType,
            int gpuMemMb, int gpuCores, int totalVgpuCount,
            String nodeSelectorKey, String nodeSelectorPrefix,
            List<HamiVgpuUnit> vgpuUnits) {

        physicalClusterMapper.findById(physicalClusterId)
                .orElseThrow(() -> new ResourceNotFoundException("物理集群不存在: " + physicalClusterId));

        if (vgpuUnits == null || vgpuUnits.size() != totalVgpuCount) {
            throw new BadRequestException("vGPU 单元数量必须等于 totalVgpuCount");
        }

        // 1. 创建 hami_gpu_config
        String configId = UUID.randomUUID().toString();
        HamiGpuConfig config = HamiGpuConfig.builder()
                .id(configId)
                .physicalClusterId(physicalClusterId)
                .gpuType(gpuType)
                .gpuMemMb(gpuMemMb)
                .gpuCores(gpuCores)
                .totalVgpuCount(totalVgpuCount)
                .nodeSelectorKey(nodeSelectorKey)
                .nodeSelectorPrefix(nodeSelectorPrefix)
                .status("active")
                .build();
        hamiGpuConfigMapper.insert(config);

        // 2. 批量创建 hami_vgpu_unit
        for (HamiVgpuUnit unit : vgpuUnits) {
            unit.setId(UUID.randomUUID().toString());
            unit.setHamiGpuConfigId(configId);
            unit.setAvailableCount(totalVgpuCount); // 初始值
            hamiVgpuUnitMapper.insert(unit);
        }

        log.info("✓ HAMi GPU 配置已创建: id={}, cluster={}, gpu={}, totalVgpu={}",
                configId, physicalClusterId, gpuType, totalVgpuCount);
        return config;
    }

    /**
     * 更新 GPU 切分配置（仅更新主配置，vGPU 单元需单独更新）。
     */
    @Transactional(rollbackFor = Exception.class)
    public HamiGpuConfig updateGpuConfig(String id, String gpuType,
            int gpuMemMb, int gpuCores, int totalVgpuCount,
            String nodeSelectorKey, String nodeSelectorPrefix) {

        HamiGpuConfig config = hamiGpuConfigMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("GPU 配置不存在: " + id));
        config.setGpuType(gpuType);
        config.setGpuMemMb(gpuMemMb);
        config.setGpuCores(gpuCores);
        config.setTotalVgpuCount(totalVgpuCount);
        config.setNodeSelectorKey(nodeSelectorKey);
        config.setNodeSelectorPrefix(nodeSelectorPrefix);
        hamiGpuConfigMapper.update(config);
        return config;
    }

    /**
     * 删除 GPU 切分配置（级联删除所有 vGPU 单元）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteGpuConfig(String id) {
        hamiVgpuUnitMapper.deleteByGpuConfigId(id);
        hamiGpuConfigMapper.deleteById(id);
        log.info("✓ HAMi GPU 配置已删除: id={}", id);
    }

    /**
     * 获取集群所有 GPU 切分配置。
     */
    public List<HamiGpuConfig> listByCluster(String clusterId) {
        return hamiGpuConfigMapper.findByPhysicalClusterId(clusterId);
    }

    /**
     * 获取 GPU 配置详情。
     */
    public HamiGpuConfig getById(String id) {
        return hamiGpuConfigMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("GPU 配置不存在: " + id));
    }

    // ─────────────────────────── vGPU 单元 CRUD ───────────────────────────

    /**
     * 添加 vGPU 单元到已有 GPU 配置。
     */
    @Transactional(rollbackFor = Exception.class)
    public HamiVgpuUnit addVgpuUnit(String gpuConfigId, HamiVgpuUnit unit) {
        hamiGpuConfigMapper.findById(gpuConfigId)
                .orElseThrow(() -> new ResourceNotFoundException("GPU 配置不存在: " + gpuConfigId));

        unit.setId(UUID.randomUUID().toString());
        unit.setHamiGpuConfigId(gpuConfigId);
        hamiVgpuUnitMapper.insert(unit);
        log.info("✓ vGPU 单元已添加: name={}, gpuConfig={}", unit.getVgpuName(), gpuConfigId);
        return unit;
    }

    /**
     * 获取 GPU 配置的所有 vGPU 单元。
     */
    public List<HamiVgpuUnit> listVgpuUnits(String gpuConfigId) {
        return hamiVgpuUnitMapper.findByGpuConfigId(gpuConfigId);
    }

    /**
     * 删除 vGPU 单元。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteVgpuUnit(String id) {
        hamiVgpuUnitMapper.deleteById(id);
        log.info("✓ vGPU 单元已删除: id={}", id);
    }

    // ─────────────────────────── vGPU 单元同步 ───────────────────────────

    /**
     * 获取 vGPU 单元的可用水量（用于配额校验）。
     */
    public int getAvailableCount(String vgpuUnitId) {
        return hamiVgpuUnitMapper.findById(vgpuUnitId)
                .map(HamiVgpuUnit::getAvailableCount)
                .orElse(0);
    }

    /**
     * 手动同步 vGPU 可用数量。
     *
     * 同步逻辑：
     * 1. 根据 vGPU 单元的 nodeSelectorValue 匹配节点标签（config.nodeSelectorKey = nodeSelectorValue）
     * 2. 统计满足匹配条件的节点数
     * 3. available_count = 节点数 × total_vgpu_count（一块卡的 vGPU 单元数）
     *
     * 注意：这里假设同规格节点每节点 1 张 GPU。实际多卡服务器需要根据节点 allocatable
     * 中的 nvidia.com/gpu 数量做乘法。
     *
     * @param clusterId 物理集群 ID
     * @param vgpuUnitId vGPU 单元 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncAvailableCount(String clusterId, String vgpuUnitId) {
        HamiVgpuUnit unit = hamiVgpuUnitMapper.findById(vgpuUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("vGPU 单元不存在: " + vgpuUnitId));
        HamiGpuConfig config = hamiGpuConfigMapper.findById(unit.getHamiGpuConfigId())
                .orElseThrow(() -> new ResourceNotFoundException("GPU 配置不存在"));

        KubernetesClient client = clientManager.getClient(clusterId);

        // 拼接标签选择器：pool=v100-7b
        String labelKey = config.getNodeSelectorKey();
        String labelValue = unit.getNodeSelectorValue();
        String labelSelector = labelKey + "=" + labelValue;

        // 统计满足节点标签的节点数
        int nodeCount = client.nodes().withLabelSelector(labelSelector).list().getItems().size();

        // available_count = 节点数 × 每卡 vGPU 单元数
        // 假设同规格节点每节点 1 张 GPU；实际多卡需查每节点 nvidia.com/gpu allocatable
        int availableCount = nodeCount * config.getTotalVgpuCount();

        hamiVgpuUnitMapper.updateAvailableCount(vgpuUnitId, availableCount);
        log.info("✓ vGPU 可用数量已同步: unit={}, label={}, nodes={}, totalVgpu={}, availableCount={}",
                vgpuUnitId, labelSelector, nodeCount, config.getTotalVgpuCount(), availableCount);
    }

    // ─────────────────────────── ComputeSpec 绑定 ───────────────────────────

    /**
     * 将 ComputeSpec 绑定到 vGPU 单元。
     * 设置 specType=VIRTUAL，关联 hamiVgpuUnitId。
     * 自动从 vGPU 单元填充 nodeSelector、defaultGpumemMb、defaultGpucores。
     *
     * @param clusterId 物理集群 ID（用于获取 nodeSelectorKey 拼接 nodeSelector）
     * @param specId ComputeSpec ID
     * @param vgpuUnitId vGPU 单元 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public ComputeSpec bindSpecToVgpuUnit(String clusterId, String specId, String vgpuUnitId) {
        ComputeSpec spec = computeSpecMapper.findById(specId)
                .orElseThrow(() -> new ResourceNotFoundException("规格不存在: " + specId));
        HamiVgpuUnit unit = hamiVgpuUnitMapper.findById(vgpuUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("vGPU 单元不存在: " + vgpuUnitId));
        HamiGpuConfig config = hamiGpuConfigMapper.findById(unit.getHamiGpuConfigId())
                .orElseThrow(() -> new ResourceNotFoundException("GPU 配置不存在"));

        // 校验 clusterId 一致
        if (!clusterId.equals(config.getPhysicalClusterId())) {
            throw new BadRequestException("vGPU 单元不属于该物理集群");
        }

        // 设置 VIRTUAL 类型 + 关联
        spec.setSpecType(ComputeSpec.SpecType.VIRTUAL);
        spec.setHamiVgpuUnitId(vgpuUnitId);

        // 自动从 vGPU 单元填充字段
        String nodeSelectorKey = config.getNodeSelectorKey();
        spec.setNodeSelector("{\"" + nodeSelectorKey + "\":\"" + unit.getNodeSelectorValue() + "\"}");
        spec.setDefaultGpumemMb(unit.getVgpuMemMb());
        spec.setDefaultGpucores(unit.getVgpuCores());

        // 如果规格尚未设置 resourceQuotaKey，自动生成
        if (spec.getResourceQuotaKey() == null || spec.getResourceQuotaKey().isEmpty()) {
            spec.setResourceQuotaKey("platform.io/" + unit.getVgpuName());
        }

        computeSpecMapper.update(spec);
        log.info("✓ 规格 {} 已绑定到 vGPU 单元 {}（VIRTUAL）", spec.getName(), vgpuUnitId);
        return spec;
    }
}