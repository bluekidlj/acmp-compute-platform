package com.acmp.compute.service;

import com.acmp.compute.dto.ModelDeploymentRequest;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.scheduler.HeterogeneousScheduler;
import com.acmp.compute.scheduler.HomogeneousScheduler;
import com.acmp.compute.scheduler.PoolScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 池元数据服务：根据 poolMode 分发到对应的调度器。
 * HOMOGENEOUS → HomogeneousScheduler
 * HETEROGENEOUS → HeterogeneousScheduler
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoolMetadataService {

    private final ResourcePoolMapper resourcePoolMapper;
    private final PhysicalClusterMapper physicalClusterMapper;
    private final HomogeneousScheduler homogeneousScheduler;
    private final HeterogeneousScheduler heterogeneousScheduler;

    /**
     * 按规格在逻辑池关联的物理集群中选定目标集群。
     */
    public PhysicalCluster pickClusterForSpec(String poolId, ComputeSpec spec) {
        PoolScheduler scheduler = getScheduler(poolId);
        return scheduler.pickCluster(poolId, spec);
    }

    /**
     * 部署预检验：gpuType 是否在该池支持范围内。
     */
    public void validateDeployment(String poolId, ModelDeploymentRequest request) {
        PoolScheduler scheduler = getScheduler(poolId);
        scheduler.validateDeployment(poolId, request);
    }

    /**
     * 获取资源池关联的所有物理集群。
     */
    public List<PhysicalCluster> loadPhysicalClustersByPool(String poolId) {
        List<String> ids = resourcePoolMapper.findPhysicalClusterIds(poolId);
        if (ids.isEmpty()) {
            throw new BadRequestException("资源池 " + poolId + " 未关联任何物理集群");
        }
        return ids.stream()
                .map(id -> physicalClusterMapper.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("物理集群不存在: " + id)))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 根据 poolMode 获取对应的调度器。
     */
    private PoolScheduler getScheduler(String poolId) {
        ResourcePool pool = resourcePoolMapper.findById(poolId)
                .orElseThrow(() -> new ResourceNotFoundException("资源池不存在: " + poolId));
        String mode = pool.getPoolMode();
        if ("HETEROGENEOUS".equals(mode)) {
            return heterogeneousScheduler;
        }
        return homogeneousScheduler;
    }
}