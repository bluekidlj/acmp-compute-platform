package com.acmp.compute.service;

import com.acmp.compute.dto.TrainingJobRequest;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.K8sResourceBuilder;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.WorkspaceMapper;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 训练任务服务：提交 VolcanoJob 到工作空间（K8s Namespace）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingJobService {

    private final WorkspaceMapper workspaceMapper;
    private final KubernetesClientManager clientManager;

    private UserPrincipal currentUser() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof UserPrincipal)) throw new ForbiddenException("未登录");
        return (UserPrincipal) p;
    }

    public String submit(String workspaceId, TrainingJobRequest request) {
        UserPrincipal user = currentUser();
        Workspace ws = workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在"));
        List<String> members = workspaceMapper.findMemberIds(workspaceId);
        if (!members.contains(user.getId()))
            throw new ForbiddenException("无权限在该工作空间提交训练任务");

        try {
            String yaml = K8sResourceBuilder.buildVolcanoJob(
                    request.getJobName(), ws.getNamespace(), ws.getVolcanoQueueName(),
                    request.getReplicas(), request.getImage(),
                    request.getGpuPerPod(), request.getGpuMemPerPod(), request.getGpuCoresPerPod(),
                    request.getCommand());
            clientManager.applyYamlInNamespace(ws.getPrimaryClusterId(), ws.getNamespace(), yaml);
            log.info("✓ VolcanoJob {} 已提交到工作空间 {} (ns={})", request.getJobName(), workspaceId, ws.getNamespace());
            return request.getJobName();
        } catch (Exception e) {
            throw new RuntimeException("训练任务提交失败: " + e.getMessage());
        }
    }
}
