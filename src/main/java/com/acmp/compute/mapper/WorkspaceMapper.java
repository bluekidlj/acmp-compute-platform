package com.acmp.compute.mapper;

import com.acmp.compute.entity.Workspace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface WorkspaceMapper {
    int insert(Workspace workspace);
    int update(Workspace workspace);
    Optional<Workspace> findById(@Param("id") String id);
    List<Workspace> findAll();
    int deleteById(@Param("id") String id);

    // 成员管理
    void insertMember(@Param("workspaceId") String workspaceId, @Param("userId") String userId);
    void deleteMember(@Param("workspaceId") String workspaceId, @Param("userId") String userId);
    void deleteAllMembers(@Param("workspaceId") String workspaceId);
    List<String> findMemberIds(@Param("workspaceId") String workspaceId);
}
