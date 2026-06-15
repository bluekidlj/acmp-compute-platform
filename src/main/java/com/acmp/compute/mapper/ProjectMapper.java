package com.acmp.compute.mapper;

import com.acmp.compute.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ProjectMapper {
    int insert(Project project);
    int update(Project project);
    Optional<Project> findById(@Param("id") String id);
    List<Project> findByWorkspaceId(@Param("workspaceId") String workspaceId);
    List<Project> findAll();
    int deleteById(@Param("id") String id);

    // 成员管理
    void insertMember(@Param("projectId") String projectId, @Param("userId") String userId);
    void deleteMember(@Param("projectId") String projectId, @Param("userId") String userId);
    void deleteAllMembers(@Param("projectId") String projectId);
    List<String> findMemberIds(@Param("projectId") String projectId);
}
