package com.acmp.compute.mapper;

import com.acmp.compute.entity.WorkspaceQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface WorkspaceQuotaMapper {
    void insert(WorkspaceQuota quota);
    void update(WorkspaceQuota quota);
    Optional<WorkspaceQuota> findByWorkspaceId(@Param("workspaceId") String workspaceId);
    void deleteByWorkspaceId(@Param("workspaceId") String workspaceId);
}
