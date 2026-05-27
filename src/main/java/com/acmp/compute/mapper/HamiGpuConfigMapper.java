package com.acmp.compute.mapper;

import com.acmp.compute.entity.HamiGpuConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface HamiGpuConfigMapper {
    int insert(HamiGpuConfig entity);
    int update(HamiGpuConfig entity);
    Optional<HamiGpuConfig> findById(@Param("id") String id);
    List<HamiGpuConfig> findAll();
    List<HamiGpuConfig> findByPhysicalClusterId(@Param("physicalClusterId") String physicalClusterId);
    int deleteById(@Param("id") String id);
}