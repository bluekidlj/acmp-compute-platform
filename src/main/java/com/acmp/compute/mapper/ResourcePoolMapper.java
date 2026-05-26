package com.acmp.compute.mapper;

import com.acmp.compute.entity.ResourcePool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ResourcePoolMapper {

    int insert(ResourcePool entity);
    int update(ResourcePool entity);

    Optional<ResourcePool> findById(@Param("id") String id);
    List<ResourcePool> findAll();
    List<ResourcePool> findByPhysicalClusterId(@Param("physicalClusterId") String physicalClusterId);

    int insertPhysicalCluster(@Param("resourcePoolId") String resourcePoolId,
                              @Param("physicalClusterId") String physicalClusterId);
    List<String> findPhysicalClusterIds(@Param("resourcePoolId") String resourcePoolId);

    /** 【异构算力】从 workspace_pool_cluster 查工作空间关联的物理集群 */
    List<String> findPhysicalClusterIdsByWorkspaceId(@Param("workspaceId") String workspaceId);

    int deleteById(@Param("id") String id);
}
