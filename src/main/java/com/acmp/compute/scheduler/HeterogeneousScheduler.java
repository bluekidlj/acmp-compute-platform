package com.acmp.compute.scheduler;

import com.acmp.compute.dto.ModelDeploymentRequest;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异构资源池调度器：一个逻辑池可绑定多个物理集群，支持按规格路由。
 * pickCluster 根据 spec.nodeSelector 匹配最佳物理集群。
 * validateDeployment 检查是否有任意集群支持该 gpuType。
 */
@Slf4j
@RequiredArgsConstructor
public class HeterogeneousScheduler implements PoolScheduler {

    private final ResourcePoolMapper resourcePoolMapper;
    private final PhysicalClusterMapper physicalClusterMapper;

    @Override
    public PhysicalCluster pickCluster(String poolId, ComputeSpec spec) {
        List<String> clusterIds = resourcePoolMapper.findPhysicalClusterIds(poolId);
        if (clusterIds.isEmpty()) {
            throw new BadRequestException("逻辑池 " + poolId + " 未关联任何物理集群");
        }

        Map<String, String> specLabels = parseJsonMap(spec.getNodeSelector());

        if (specLabels.isEmpty()) {
            PhysicalCluster c = physicalClusterMapper.findById(clusterIds.get(0))
                    .orElseThrow(() -> new ResourceNotFoundException("物理集群不存在: " + clusterIds.get(0)));
            log.info("spec {} 未声明 nodeSelector，使用池中第一个物理集群 {}", spec.getName(), c.getId());
            return c;
        }

        for (String id : clusterIds) {
            PhysicalCluster c = physicalClusterMapper.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("物理集群不存在: " + id));
            Map<String, String> labels = parseJsonMap(c.getNodeLabels());
            if (containsAll(labels, specLabels)) {
                log.info("spec {} 命中物理集群 {} (labels={})", spec.getName(), c.getId(), labels);
                return c;
            }
        }

        throw new BadRequestException(
                "逻辑池 " + poolId + " 关联的物理集群没有满足规格 "
                        + spec.getName() + " 节点标签 " + specLabels + " 的目标");
    }

    @Override
    public void validateDeployment(String poolId, ModelDeploymentRequest request) {
        List<String> clusterIds = resourcePoolMapper.findPhysicalClusterIds(poolId);
        if (clusterIds.isEmpty()) {
            throw new BadRequestException("逻辑池 " + poolId + " 未关联任何物理集群");
        }

        String gpuType = request.getGpuType();
        PhysicalCluster targetCluster = null;
        for (String id : clusterIds) {
            PhysicalCluster c = physicalClusterMapper.findById(id).orElse(null);
            if (c == null) continue;
            Map<String, String> labels = parseJsonMap(c.getNodeLabels());
            if (gpuType.equals(labels.get("pool"))) {
                targetCluster = c;
                break; // 找到第一个就返回
            }
        }
        if (targetCluster == null) {
            throw new BadRequestException("逻辑池 " + poolId + " 中没有支持 GPU 类型 " + gpuType + " 的物理集群");
        }

        // 单副本资源上限校验（用支持该 gpuType 的集群来校验）
        if (request.getCpuCores() != null && targetCluster.getMaxCpuCores() != null
                && request.getCpuCores() > targetCluster.getMaxCpuCores()) {
            throw new BadRequestException(
                    "单副本 CPU " + request.getCpuCores() + " 核超出节点上限 " + targetCluster.getMaxCpuCores() + " 核");
        }
        if (request.getMemoryGib() != null && targetCluster.getMaxMemoryGib() != null
                && request.getMemoryGib() > targetCluster.getMaxMemoryGib()) {
            throw new BadRequestException(
                    "单副本内存 " + request.getMemoryGib() + "Gi 超出节点上限 " + targetCluster.getMaxMemoryGib() + "Gi");
        }
    }

    @Override
    public String getMode() {
        return "HETEROGENEOUS";
    }

    private boolean containsAll(Map<String, String> superSet, Map<String, String> subSet) {
        for (Map.Entry<String, String> e : subSet.entrySet()) {
            String v = superSet.get(e.getKey());
            if (v == null || !v.equals(e.getValue())) return false;
        }
        return true;
    }

    private Map<String, String> parseJsonMap(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = Serialization.jsonMapper().readValue(json, Map.class);
            result.putAll(m);
        } catch (Exception e) {
            log.warn("解析 JSON Map 失败: {}", json, e);
        }
        return result;
    }
}