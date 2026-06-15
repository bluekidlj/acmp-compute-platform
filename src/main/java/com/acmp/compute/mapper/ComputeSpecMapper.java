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
    List<ComputeSpec> findByPoolType(@Param("poolType") String poolType);
    int deleteById(@Param("id") String id);

    // 池-规格关联
    void insertResourcePoolSpec(@Param("resourcePoolId") String poolId, @Param("specId") String specId);
    void deleteResourcePoolSpec(@Param("resourcePoolId") String poolId, @Param("specId") String specId);
    void deleteResourcePoolSpecsByPool(@Param("resourcePoolId") String poolId);
    List<ComputeSpec> findByResourcePoolId(@Param("resourcePoolId") String poolId);
    List<String> findSpecIdsByResourcePoolId(@Param("resourcePoolId") String poolId);
}
