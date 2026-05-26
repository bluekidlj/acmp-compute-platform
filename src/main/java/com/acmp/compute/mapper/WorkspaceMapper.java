package com.acmp.compute.mapper;

import com.acmp.compute.entity.Workspace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface WorkspaceMapper {
    void insert(Workspace workspace);
    void update(Workspace workspace);
    Optional<Workspace> findById(@Param("id") String id);
    List<Workspace> findAll();
    /** 按逻辑资源池 ID 查其下所有工作空间 */
    List<Workspace> findByResourcePoolId(@Param("resourcePoolId") String resourcePoolId);
    void deleteById(@Param("id") String id);

    // ── 成员管理 ──
    void insertMember(@Param("workspaceId") String workspaceId, @Param("userId") String userId);
    void deleteMember(@Param("workspaceId") String workspaceId, @Param("userId") String userId);
    List<String> findMemberIds(@Param("workspaceId") String workspaceId);

    // ── 【异构算力】工作空间 ↔ 物理集群关联 ──
    /** 插入 workspace_pool_cluster 关联记录 */
    void insertCluster(@Param("workspaceId") String workspaceId, @Param("physicalClusterId") String physicalClusterId);
    /** 查询工作空间关联的所有物理集群 ID */
    List<String> findClusterIds(@Param("workspaceId") String workspaceId);
    /** 删除工作空间关联的所有物理集群 */
    void deleteClusters(@Param("workspaceId") String workspaceId);
}
