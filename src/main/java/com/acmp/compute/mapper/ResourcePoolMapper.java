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
    int updateCapacity(@Param("id") String id, @Param("totalNodes") int totalNodes);
    int updateAllocated(@Param("id") String id, @Param("allocatedNodes") int allocatedNodes);

    Optional<ResourcePool> findById(@Param("id") String id);
    List<ResourcePool> findAll();
    List<ResourcePool> findByWorkspaceId(@Param("workspaceId") String workspaceId);
    Optional<ResourcePool> findByWorkspaceAndType(@Param("workspaceId") String workspaceId,
                                                  @Param("poolType") String poolType);
    int deleteById(@Param("id") String id);
}
