package com.acmp.compute.service;

import com.acmp.compute.dto.ProjectRequest;
import com.acmp.compute.dto.ProjectResponse;
import com.acmp.compute.entity.Project;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ProjectMapper;
import com.acmp.compute.mapper.ProjectResourceQuotaMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final WorkspaceMapper workspaceMapper;
    private final ProjectResourceQuotaMapper projectQuotaMapper;

    private UserPrincipal currentUser() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof UserPrincipal)) {
            throw new com.acmp.compute.exception.ForbiddenException("未登录");
        }
        return (UserPrincipal) p;
    }

    @Transactional
    public ProjectResponse create(String workspaceId, ProjectRequest req) {
        if (workspaceMapper.findById(workspaceId).isEmpty()) {
            throw new ResourceNotFoundException("工作空间不存在: " + workspaceId);
        }
        Project project = Project.builder()
                .id(UUID.randomUUID().toString())
                .workspaceId(workspaceId)
                .name(req.getName())
                .description(req.getDescription())
                .createdBy(currentUser().getId())
                .status("active")
                .build();
        projectMapper.insert(project);
        if (req.getMemberIds() != null) {
            for (String userId : req.getMemberIds()) projectMapper.insertMember(project.getId(), userId);
        }
        log.info("✓ 项目 {} 已创建 (ws={})", project.getName(), workspaceId);
        return toResponse(project, true);
    }

    @Transactional
    public ProjectResponse update(String id, ProjectRequest req) {
        Project p = projectMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在: " + id));
        p.setName(req.getName());
        if (req.getDescription() != null) p.setDescription(req.getDescription());
        projectMapper.update(p);
        return toResponse(p, true);
    }

    @Transactional
    public void delete(String id) {
        if (projectMapper.findById(id).isEmpty()) {
            throw new ResourceNotFoundException("项目不存在: " + id);
        }
        projectQuotaMapper.deleteByProjectId(id);
        projectMapper.deleteAllMembers(id);
        projectMapper.deleteById(id);
        log.info("✓ 项目已删除: {}", id);
    }

    public ProjectResponse getById(String id) {
        Project p = projectMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在: " + id));
        return toResponse(p, true);
    }

    public List<ProjectResponse> listByWorkspace(String workspaceId) {
        return projectMapper.findByWorkspaceId(workspaceId).stream()
                .map(p -> toResponse(p, true)).collect(java.util.stream.Collectors.toList());
    }

    @Transactional
    public void addMember(String projectId, String userId) {
        if (projectMapper.findById(projectId).isEmpty()) {
            throw new ResourceNotFoundException("项目不存在");
        }
        projectMapper.insertMember(projectId, userId);
    }

    @Transactional
    public void removeMember(String projectId, String userId) {
        projectMapper.deleteMember(projectId, userId);
    }

    public List<String> listMembers(String projectId) {
        return projectMapper.findMemberIds(projectId);
    }

    private ProjectResponse toResponse(Project p, boolean withQuotas) {
        ProjectResponse.ProjectResponseBuilder b = ProjectResponse.builder()
                .id(p.getId())
                .workspaceId(p.getWorkspaceId())
                .name(p.getName())
                .description(p.getDescription())
                .createdBy(p.getCreatedBy())
                .status(p.getStatus())
                .memberIds(projectMapper.findMemberIds(p.getId()))
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt());

        if (withQuotas) {
            List<Map<String, Object>> rows = projectQuotaMapper.findByProjectId(p.getId());
            Map<String, List<ProjectResponse.QuotaView>> grouped = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String poolType = (String) row.get("RP_POOL_TYPE");
                if (poolType == null) poolType = (String) row.get("rp_pool_type");
                if (poolType == null) poolType = "UNKNOWN";
                ProjectResponse.QuotaView v = ProjectResponse.QuotaView.builder()
                        .quotaId((String) row.get("ID"))
                        .poolId((String) row.get("RESOURCE_POOL_ID"))
                        .poolName((String) row.get("POOL_NAME"))
                        .specId((String) row.get("SPEC_ID"))
                        .specName((String) row.get("SPEC_NAME"))
                        .specType((String) row.get("SPEC_TYPE"))
                        .totalNodes(toInt(row.get("TOTAL_NODES")))
                        .usedNodes(toInt(row.get("USED_NODES")))
                        .availableNodes(toInt(row.get("AVAILABLE")))
                        .build();
                grouped.computeIfAbsent(poolType, k -> new ArrayList<>()).add(v);
            }
            b.quotaByPoolType(grouped);
        }
        return b.build();
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }
}
