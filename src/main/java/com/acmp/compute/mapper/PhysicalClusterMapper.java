package com.acmp.compute.mapper;

import com.acmp.compute.entity.PhysicalCluster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface PhysicalClusterMapper {
    int insert(PhysicalCluster entity);
    int update(PhysicalCluster entity);
    int updateScanSummary(PhysicalCluster entity);
    Optional<PhysicalCluster> findById(@Param("id") String id);
    List<PhysicalCluster> findAll();
    int deleteById(@Param("id") String id);
}
