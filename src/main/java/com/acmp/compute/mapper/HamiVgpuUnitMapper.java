package com.acmp.compute.mapper;

import com.acmp.compute.entity.HamiVgpuUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface HamiVgpuUnitMapper {
    int insert(HamiVgpuUnit entity);
    int update(HamiVgpuUnit entity);
    int updateAvailableCount(@Param("id") String id, @Param("availableCount") int availableCount);
    Optional<HamiVgpuUnit> findById(@Param("id") String id);
    List<HamiVgpuUnit> findByGpuConfigId(@Param("hamiGpuConfigId") String hamiGpuConfigId);
    int deleteById(@Param("id") String id);
    int deleteByGpuConfigId(@Param("hamiGpuConfigId") String hamiGpuConfigId);
}