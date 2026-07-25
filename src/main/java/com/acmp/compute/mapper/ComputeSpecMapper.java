package com.acmp.compute.mapper;

import com.acmp.compute.entity.ComputeSpec;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ComputeSpecMapper {
    int insert(ComputeSpec spec);
    int update(ComputeSpec spec);
    Optional<ComputeSpec> findById(@Param("id") String id);
    Optional<ComputeSpec> findByName(@Param("name") String name);
    List<ComputeSpec> findAll();
    List<ComputeSpec> findBySpecType(@Param("specType") String specType);
    int deleteById(@Param("id") String id);
    List<ComputeSpec> findByResourcePoolId(@Param("resourcePoolId") String poolId);
}
