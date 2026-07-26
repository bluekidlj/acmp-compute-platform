package com.acmp.compute.mapper;

import com.acmp.compute.entity.ResourcePool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface ResourcePoolMapper {
    Optional<ResourcePool> findById(@Param("id") String id);

    int upsert(ResourcePool pool);
}
