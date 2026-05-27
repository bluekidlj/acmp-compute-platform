package com.acmp.compute.mapper;

import com.acmp.compute.entity.ComputeSpec;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ComputeSpecMapper {
    void insert(ComputeSpec spec);
    Optional<ComputeSpec> findById(@Param("id") String id);
    Optional<ComputeSpec> findByName(@Param("name") String name);
    List<ComputeSpec> findAll();
    void deleteById(@Param("id") String id);

    // ── 物理集群 ↔ 规格 ──
    void insertPhysicalClusterSpec(@Param("physicalClusterId") String cid, @Param("specId") String sid, @Param("totalCount") int count);
    List<ComputeSpec> findByPhysicalClusterId(@Param("physicalClusterId") String cid);

    // ── 逻辑池 ↔ 规格配额 ──
    void insertResourcePoolSpecQuota(@Param("resourcePoolId") String pid, @Param("specId") String sid,
                                      @Param("totalQuota") int total, @Param("allocatedQuota") int allocated);
    void updateResourcePoolSpecAllocated(@Param("resourcePoolId") String pid, @Param("specId") String sid,
                                          @Param("allocatedQuota") int allocated);
    List<java.util.Map<String, Object>> findSpecQuotasByResourcePoolId(@Param("resourcePoolId") String pid);

    // ── 工作空间 × 逻辑池 × 规格配额（双层配额第二层）──
    void insertWorkspaceSpecQuota(@Param("workspaceId") String wid, @Param("resourcePoolId") String pid,
                                   @Param("specId") String sid,
                                   @Param("maxQuota") int max, @Param("usedQuota") int used);
    void updateWorkspaceSpecUsed(@Param("workspaceId") String wid, @Param("resourcePoolId") String pid,
                                  @Param("specId") String sid, @Param("usedQuota") int used);
    void deleteWorkspaceSpecQuotas(@Param("workspaceId") String wid);
    List<java.util.Map<String, Object>> findSpecQuotasByWorkspaceId(@Param("workspaceId") String wid);

    // ── 更新规格（用于 HAMi vGPU 同步）──
    void update(ComputeSpec spec);
}
