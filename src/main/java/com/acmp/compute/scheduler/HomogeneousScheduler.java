package com.acmp.compute.scheduler;

import com.acmp.compute.dto.ModelDeploymentRequest;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同构资源池调度器：一个逻辑池只绑定一个物理集群。
 * pickCluster 直接返回唯一物理集群。
 * validateDeployment 检查 gpuType 是否在该集群的 nodeLabels 中。
 */
@Slf4j
@RequiredArgsConstructor
public class HomogeneousScheduler implements PoolScheduler {

    private final ResourcePoolMapper resourcePoolMapper;
    private final PhysicalClusterMapper physicalClusterMapper;

    @Override
    public PhysicalCluster pickCluster(String poolId, ComputeSpec spec) {
        List<String> clusterIds = resourcePoolMapper.findPhysicalClusterIds(poolId);
        if (clusterIds.isEmpty()) {
            throw new BadRequestException("资源池 " + poolId + " 未关联任何物理集群");
        }
        if (clusterIds.size() > 1) {
            throw new BadRequestException("资源池 " + poolId + " 关联了多个物理集群，请使用 HETEROGENEOUS 模式");
        }
        return physicalClusterMapper.findById(clusterIds.get(0))
                .orElseThrow(() -> new BadRequestException("物理集群不存在: " + clusterIds.get(0)));
    }

    @Override
    public void validateDeployment(String poolId, ModelDeploymentRequest request) {
        List<String> clusterIds = resourcePoolMapper.findPhysicalClusterIds(poolId);
        if (clusterIds.isEmpty()) {
            throw new BadRequestException("资源池 " + poolId + " 未关联任何物理集群");
        }
        PhysicalCluster cluster = physicalClusterMapper.findById(clusterIds.get(0))
                .orElseThrow(() -> new BadRequestException("物理集群不存在: " + clusterIds.get(0)));

        // 1. gpuType 匹配校验
        String gpuType = request.getGpuType();
        Map<String, String> clusterLabels = parseJsonMap(cluster.getNodeLabels());
        String poolLabel = clusterLabels.get("pool");
        if (poolLabel == null || !poolLabel.equals(gpuType)) {
            throw new BadRequestException(
                    "该资源池不支持此 GPU 类型 " + gpuType + "，该池支持的类型为 " + poolLabel);
        }

        // 2. 单副本资源上限校验
        if (request.getCpuCores() != null && cluster.getMaxCpuCores() != null
                && request.getCpuCores() > cluster.getMaxCpuCores()) {
            throw new BadRequestException(
                    "单副本 CPU " + request.getCpuCores() + " 核超出节点上限 " + cluster.getMaxCpuCores() + " 核");
        }
        if (request.getMemoryGib() != null && cluster.getMaxMemoryGib() != null
                && request.getMemoryGib() > cluster.getMaxMemoryGib()) {
            throw new BadRequestException(
                    "单副本内存 " + request.getMemoryGib() + "Gi 超出节点上限 " + cluster.getMaxMemoryGib() + "Gi");
        }
    }

    @Override
    public String getMode() {
        return "HOMOGENEOUS";
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