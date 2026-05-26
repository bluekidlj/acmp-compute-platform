package com.acmp.compute.service;

import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 物理池元数据加载服务。
 * 
 * 职责：加载逻辑资源池所关联的所有物理集群的调度约束（nodeSelector、taints）。
 * 用途：在部署模型或训练任务时，自动注入这些约束到 K8s Pod Spec。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoolMetadataService {

    private final ResourcePoolMapper resourcePoolMapper;
    private final PhysicalClusterMapper physicalClusterMapper;

    /**
     * 加载逻辑资源池关联的所有物理集群。
     *
     * @param resourcePoolId 逻辑资源池 ID
     * @return 物理集群列表
     * @throws ResourceNotFoundException 如果资源池不存在
     */
    public List<PhysicalCluster> loadPhysicalClustersByPool(String resourcePoolId) {
        // 验证资源池存在
        resourcePoolMapper.findById(resourcePoolId)
                .orElseThrow(() -> new ResourceNotFoundException("逻辑资源池不存在: " + resourcePoolId));

        // 加载所有关联的物理集群
        List<String> physicalClusterIds = resourcePoolMapper.findPhysicalClusterIds(resourcePoolId);
        List<PhysicalCluster> clusters = physicalClusterIds.stream()
                .map(id -> physicalClusterMapper.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("物理集群不存在: " + id)))
                .collect(Collectors.toList());

        log.info("✓ 加载资源池 {} 的 {} 个物理集群", resourcePoolId, clusters.size());
        return clusters;
    }

    /**
     * 合并所有物理集群的 nodeSelector。
     *
     * 合并策略：
     * - 若多个集群定义了相同的标签键，优先级不确定（可在后续扩展为配置驱动）
     * - 若某集群未定义 nodeSelector，跳过该集群的贡献
     *
     * @param clusters 物理集群列表
     * @return 合并后的 nodeSelector JSON 字符串（若无标签则为 null）
     */
    public String mergeNodeSelectors(List<PhysicalCluster> clusters) {
        if (clusters == null || clusters.isEmpty()) {
            return null;
        }

        StringBuilder merged = new StringBuilder("{");
        boolean hasContent = false;

        for (PhysicalCluster cluster : clusters) {
            if (cluster.getNodeLabels() == null || cluster.getNodeLabels().isEmpty()) {
                continue;
            }
            // 简单方式：直接拼接（假设 nodeLabels 已是有效 JSON）
            // 生产环境应使用 JSON 库进行正式合并
            if (hasContent) {
                merged.append(",");
            }
            merged.append(cluster.getNodeLabels().substring(1, cluster.getNodeLabels().length() - 1));
            hasContent = true;
        }

        merged.append("}");
        return hasContent ? merged.toString() : null;
    }

    /**
     * 收集所有物理集群的污点容忍。
     *
     * @param clusters 物理集群列表
     * @return 污点列表 JSON 字符串（若无污点则为 null）
     */
    public String collectTolerations(List<PhysicalCluster> clusters) {
        if (clusters == null || clusters.isEmpty()) {
            return null;
        }

        StringBuilder collected = new StringBuilder("[");
        boolean hasContent = false;

        for (PhysicalCluster cluster : clusters) {
            if (cluster.getTaints() == null || cluster.getTaints().isEmpty()) {
                continue;
            }
            // 假设 taints 为 JSON 数组字符串，去掉外层 [ ]
            String taintsContent = cluster.getTaints();
            if (taintsContent.startsWith("[") && taintsContent.endsWith("]")) {
                taintsContent = taintsContent.substring(1, taintsContent.length() - 1);
            }

            if (hasContent && !taintsContent.isEmpty()) {
                collected.append(",");
            }
            if (!taintsContent.isEmpty()) {
                collected.append(taintsContent);
                hasContent = true;
            }
        }

        collected.append("]");
        return hasContent ? collected.toString() : null;
    }

    /**
     * 一次性加载并合并 nodeSelector 和 tolerations。
     *
     * @param resourcePoolId 逻辑资源池 ID
     * @return 包含 nodeSelector 和 tolerations 的对象
     */
    public PoolMetadata loadPoolMetadata(String resourcePoolId) {
        List<PhysicalCluster> clusters = loadPhysicalClustersByPool(resourcePoolId);
        String nodeSelector = mergeNodeSelectors(clusters);
        String tolerations = collectTolerations(clusters);

        return PoolMetadata.builder()
                .resourcePoolId(resourcePoolId)
                .physicalClusters(clusters)
                .nodeSelector(nodeSelector)
                .tolerations(tolerations)
                .build();
    }

    /**
     * 池元数据对象（用于返回加载结果）。
     */
    @lombok.Data
    @lombok.Builder
    public static class PoolMetadata {
        private String resourcePoolId;
        private List<PhysicalCluster> physicalClusters;
        private String nodeSelector;
        private String tolerations;
    }
}
