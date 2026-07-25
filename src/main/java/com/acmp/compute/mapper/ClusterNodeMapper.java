package com.acmp.compute.mapper;

import com.acmp.compute.entity.ClusterNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ClusterNodeMapper {
    int insert(ClusterNode node);
    int updateDiscovered(ClusterNode node);
    int markOfflineByCluster(@Param("clusterId") String clusterId);
    Optional<ClusterNode> findByClusterAndName(@Param("clusterId") String clusterId,
                                               @Param("name") String name);
    Optional<ClusterNode> findById(@Param("id") String id);
    List<ClusterNode> findByClusterId(@Param("clusterId") String clusterId);
    int deleteByClusterId(@Param("clusterId") String clusterId);
}
