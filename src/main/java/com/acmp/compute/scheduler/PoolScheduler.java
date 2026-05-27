package com.acmp.compute.scheduler;

import com.acmp.compute.dto.ModelDeploymentRequest;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.PhysicalCluster;

import java.util.List;

/**
 * 资源池调度器接口。
 * 按 poolMode 分发到 HomogeneousScheduler（单集群）或 HeterogeneousScheduler（多集群）。
 */
public interface PoolScheduler {

    /**
     * 根据规格选择目标物理集群。
     */
    PhysicalCluster pickCluster(String poolId, ComputeSpec spec);

    /**
     * 部署预检验：gpuType 是否在该池支持范围内。
     */
    void validateDeployment(String poolId, ModelDeploymentRequest request);

    /**
     * 资源池模式
     */
    String getMode();
}