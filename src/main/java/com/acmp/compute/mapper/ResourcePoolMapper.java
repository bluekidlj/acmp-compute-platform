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

    /** 仅更新 allocated 计数（工作空间配额分配/释放时） */
    int updateAllocated(ResourcePool entity);

    Optional<ResourcePool> findById(@Param("id") String id);

    List<ResourcePool> findAll();

    /** 按物理集群 ID 查关联的逻辑池（通过 M2M 表） */
    List<ResourcePool> findByPhysicalClusterId(@Param("physicalClusterId") String physicalClusterId);

    /** 插入逻辑池↔物理集群关联 */
    int insertPhysicalCluster(@Param("resourcePoolId") String resourcePoolId, @Param("physicalClusterId") String physicalClusterId);

    /** 查询逻辑池关联的物理集群 ID 列表 */
    List<String> findPhysicalClusterIds(@Param("resourcePoolId") String resourcePoolId);

    int deleteById(@Param("id") String id);
}
