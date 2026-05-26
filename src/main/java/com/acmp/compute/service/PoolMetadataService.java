package com.acmp.compute.service;

import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 池元数据服务：按规格在逻辑池关联的物理集群中选定一个调度目标。
 *
 * 设计：一个 Pod 只能落到一个节点，跨集群合并 nodeSelector 会产生"哪个节点都不满足"
 * 的非法约束。正确做法：把 spec.nodeSelector 看作"目标节点组的标签子集"，
 * 在池关联的物理集群中找到 nodeLabels ⊇ spec.nodeSelector 的那个集群，
 * 然后用该集群的 nodeLabels + taints 作为最终调度约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoolMetadataService {

    private final ResourcePoolMapper resourcePoolMapper;
    private final PhysicalClusterMapper physicalClusterMapper;

    /**
     * 加载逻辑池关联的所有物理集群。
     *
     * 【异构算力说明】优先使用 workspaceId 从 workspace_pool_cluster 查询工作空间实际涉及的集群；
     * 若未传 workspaceId，则从 resource_pool_physical_cluster 查询（兼容旧行为）。
     * 这样确保部署时只在工作空间关联的物理集群范围内选择。
     *
     * @param resourcePoolId 逻辑池 ID
     * @param workspaceId 可选，工作空间 ID（用于限定集群范围）
     */
    public List<PhysicalCluster> loadPhysicalClustersByPool(String resourcePoolId, String workspaceId) {
        List<String> ids;

        if (workspaceId != null && !workspaceId.isEmpty()) {
            // 【异构算力】优先从 workspace_pool_cluster 查（限定该工作空间涉及的物理集群）
            ids = resourcePoolMapper.findPhysicalClusterIdsByWorkspaceId(workspaceId);
            if (ids.isEmpty()) {
                // 兜底：从逻辑池关联的物理集群查
                ids = resourcePoolMapper.findPhysicalClusterIds(resourcePoolId);
            }
        } else {
            // 兼容旧行为：直接查逻辑池关联的物理集群
            ids = resourcePoolMapper.findPhysicalClusterIds(resourcePoolId);
        }

        if (ids.isEmpty()) {
            throw new BadRequestException("逻辑池 " + resourcePoolId + " 未关联任何物理集群");
        }
        return ids.stream()
                .map(id -> physicalClusterMapper.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("物理集群不存在: " + id)))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 按规格在逻辑池关联的物理集群中选定唯一目标集群。
     *
     * 匹配规则：spec.nodeSelector 中每个 (key,value) 必须出现在 cluster.nodeLabels 中。
     * 若 spec 未声明 nodeSelector，则取池关联的第一个集群（兼容旧行为）。
     * 命中多个时取第一个匹配的集群（命中即返回）。
     *
     * <h2>异构算力调度说明</h2>
     * 在异构场景下（同一逻辑池关联多个物理集群，或同一物理集群含多种节点类型），
     * spec.nodeSelector 如 {"pool":"nvidia-gpu"} 或 {"pool":"hygon-dcu"} 用于将
     * 该规格的 Pod 精确路由到对应节点，不跨集群调度（跨集群需 Volcano/自研调度器）。
     * 本方法不做多集群混合调度，只做"规格 → 集群"的最优匹配。
     *
     * @param resourcePoolId 逻辑池 ID
     * @param spec           算力规格
     * @return 匹配的物理集群信息（含 nodeLabelsJson/taintsJson 供 Pod 调度使用）
     * @throws BadRequestException 没有任何集群满足规格的节点标签要求
     */
    public TargetCluster pickClusterForSpec(String resourcePoolId, ComputeSpec spec) {
        return pickClusterForSpec(resourcePoolId, spec, null);
    }

    /**
     * 按规格在逻辑池关联的物理集群中选定唯一目标集群（可限定工作空间范围）。
     *
     * 匹配规则：spec.nodeSelector 中每个 (key,value) 必须出现在 cluster.nodeLabels 中。
     * 若 spec 未声明 nodeSelector，则取池关联的第一个集群（兼容旧行为）。
     * 命中多个时取第一个匹配的集群（命中即返回）。
     *
     * <h2>异构算力调度说明</h2>
     * 在异构场景下（同一逻辑池关联多个物理集群，或同一物理集群含多种节点类型），
     * spec.nodeSelector 如 {"pool":"nvidia-gpu"} 或 {"pool":"hygon-dcu"} 用于将
     * 该规格的 Pod 精确路由到对应节点，不跨集群调度（跨集群需 Volcano/自研调度器）。
     * 本方法不做多集群混合调度，只做"规格 → 集群"的最优匹配。
     *
     * @param resourcePoolId 逻辑池 ID
     * @param spec          算力规格
     * @return 匹配的物理集群信息（含 nodeLabelsJson/taintsJson 供 Pod 调度使用）
     * @throws BadRequestException 没有任何集群满足规格的节点标签要求
     */
    public TargetCluster pickClusterForSpec(String resourcePoolId, ComputeSpec spec) {
        return pickClusterForSpec(resourcePoolId, spec, null);
    }

    /**
     * 按规格在逻辑池关联的物理集群中选定唯一目标集群（可限定工作空间范围）。
     *
     * 匹配规则：spec.nodeSelector 中每个 (key,value) 必须出现在 cluster.nodeLabels 中。
     * 若 spec 未声明 nodeSelector，则取池关联的第一个集群（兼容旧行为）。
     * 命中多个时取第一个匹配的集群（命中即返回）。
     *
     * <h2>异构算力调度说明</h2>
     * 在异构场景下（同一逻辑池关联多个物理集群，或同一物理集群含多种节点类型），
     * spec.nodeSelector 如 {"pool":"nvidia-gpu"} 或 {"pool":"hygon-dcu"} 用于将
     * 该规格的 Pod 精确路由到对应节点，不跨集群调度（跨集群需 Volcano/自研调度器）。
     * 本方法不做多集群混合调度，只做"规格 → 集群"的最优匹配。
     *
     * @param resourcePoolId 逻辑池 ID
     * @param workspaceId    可选，工作空间 ID（用于限定集群范围）
     * @param spec           算力规格
     * @return 匹配的物理集群信息（含 nodeLabelsJson/taintsJson 供 Pod 调度使用）
     * @throws BadRequestException 没有任何集群满足规格的节点标签要求
     */
    public TargetCluster pickClusterForSpec(String resourcePoolId, ComputeSpec spec, String workspaceId) {
        List<PhysicalCluster> clusters = loadPhysicalClustersByPool(resourcePoolId, workspaceId);

        Map<String, String> specLabels = parseJsonMap(spec.getNodeSelector());

        if (specLabels.isEmpty()) {
            PhysicalCluster c = clusters.get(0);
            log.info("spec {} 未声明 nodeSelector，使用池中第一个物理集群 {}",
                    spec.getName(), c.getId());
            return toTarget(c);
        }

        for (PhysicalCluster c : clusters) {
            Map<String, String> labels = parseJsonMap(c.getNodeLabels());
            if (containsAll(labels, specLabels)) {
                log.info("spec {} 命中物理集群 {} (labels={})", spec.getName(), c.getId(), labels);
                return toTarget(c);
            }
        }

        throw new BadRequestException(
                "逻辑池 " + resourcePoolId + " 关联的物理集群没有满足规格 "
                        + spec.getName() + " 节点标签 " + specLabels + " 的目标");
    }

    private TargetCluster toTarget(PhysicalCluster c) {
        return TargetCluster.builder()
                .clusterId(c.getId())
                .clusterName(c.getName())
                .nodeLabelsJson(c.getNodeLabels())
                .taintsJson(c.getTaints())
                .build();
    }

    private boolean containsAll(Map<String, String> superSet, Map<String, String> subSet) {
        for (Map.Entry<String, String> e : subSet.entrySet()) {
            String v = superSet.get(e.getKey());
            if (v == null || !v.equals(e.getValue())) return false;
        }
        return true;
    }

    private Map<String, String> parseJsonMap(String json) {
        if (json == null || json.isEmpty()) return new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = Serialization.jsonMapper().readValue(json, Map.class);
            return m;
        } catch (Exception e) {
            log.warn("解析 JSON Map 失败: {}", json, e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * 选定的目标物理集群信息：用于工作空间 Namespace 创建及 Pod 调度。
     *
     * nodeLabelsJson 直接用作 Pod.nodeSelector，
     * taintsJson 直接用作 Pod.tolerations。
     */
    @Data
    @Builder
    public static class TargetCluster {
        private String clusterId;
        private String clusterName;
        /** JSON: {"pool":"nvidia-gpu"} */
        private String nodeLabelsJson;
        /** JSON: [{"key":"nvidia.com/gpu",...}] */
        private String taintsJson;
    }
}
