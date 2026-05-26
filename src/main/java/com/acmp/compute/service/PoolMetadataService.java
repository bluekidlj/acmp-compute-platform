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
     */
    public List<PhysicalCluster> loadPhysicalClustersByPool(String resourcePoolId) {
        resourcePoolMapper.findById(resourcePoolId)
                .orElseThrow(() -> new ResourceNotFoundException("逻辑资源池不存在: " + resourcePoolId));

        List<String> ids = resourcePoolMapper.findPhysicalClusterIds(resourcePoolId);
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
     */
    public TargetCluster pickClusterForSpec(String resourcePoolId, ComputeSpec spec) {
        List<PhysicalCluster> clusters = loadPhysicalClustersByPool(resourcePoolId);

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
