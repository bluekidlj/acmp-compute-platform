package com.acmp.compute.mapper;

import com.acmp.compute.entity.GpuDevice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface GpuDeviceMapper {
    int insert(GpuDevice device);
    int updateDiscovered(GpuDevice device);
    int markOfflineByCluster(@Param("clusterId") String clusterId);
    Optional<GpuDevice> findByIdentity(@Param("clusterId") String clusterId,
                                       @Param("nodeName") String nodeName,
                                       @Param("gpuIndex") int gpuIndex);
    Optional<GpuDevice> findById(@Param("id") String id);
    List<GpuDevice> findByClusterId(@Param("clusterId") String clusterId);
    List<GpuDevice> findByNodeId(@Param("nodeId") String nodeId);
    List<GpuDevice> findByPoolId(@Param("poolId") String poolId);
    Optional<GpuDevice> findByComputeSpecId(@Param("computeSpecId") String computeSpecId);
    int assignPool(@Param("id") String id, @Param("poolId") String poolId);
    int assignPoolAndSpec(@Param("id") String id,
                          @Param("poolId") String poolId,
                          @Param("computeSpecId") String computeSpecId);
    int countByPool(@Param("poolId") String poolId);
    int countAvailableByPoolAndModel(@Param("poolId") String poolId,
                                     @Param("gpuModel") String gpuModel);
    List<String> findCandidateClusterIds(@Param("poolId") String poolId,
                                         @Param("gpuModel") String gpuModel,
                                         @Param("required") int required);
    int deleteByClusterId(@Param("clusterId") String clusterId);
}
