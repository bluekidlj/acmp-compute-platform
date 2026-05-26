package com.acmp.compute.service;

import com.acmp.compute.dto.TrainingJobRequest;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.TrainingJobRecord;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.K8sResourceBuilder;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.TrainingJobRecordMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 训练任务服务：提交 VolcanoJob，走完整的"规格 + 双层配额 + 池调度约束"路径。
 *
 * 【异构算力说明】同 ModelDeploymentService，部署时由 PoolMetadataService.pickClusterForSpec
 * 根据请求的 spec 动态选定目标物理集群，而非使用 ws.primaryClusterId。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingJobService {

    private final WorkspaceMapper workspaceMapper;
    private final ComputeSpecMapper computeSpecMapper;
    private final TrainingJobRecordMapper trainingJobRecordMapper;
    private final KubernetesClientManager clientManager;
    private final QuotaService quotaService;
    /** 【异构算力】用于根据 spec 动态选定目标物理集群 */
    private final PoolMetadataService poolMetadataService;

    private UserPrincipal currentUser() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof UserPrincipal)) throw new ForbiddenException("未登录");
        return (UserPrincipal) p;
    }

    @Transactional(rollbackFor = Exception.class)
    public String submit(String workspaceId, TrainingJobRequest request) {
        UserPrincipal user = currentUser();
        Workspace ws = workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + workspaceId));

        List<String> members = workspaceMapper.findMemberIds(workspaceId);
        if (!members.contains(user.getId()))
            throw new ForbiddenException("无权限在该工作空间提交训练任务");

        if (request.getSpecName() == null || request.getSpecName().isBlank()) {
            throw new BadRequestException("必须指定 specName");
        }
        ComputeSpec spec = computeSpecMapper.findByName(request.getSpecName())
                .orElseThrow(() -> new ResourceNotFoundException("规格不存在: " + request.getSpecName()));

        int replicas = request.getReplicas();
        String poolId = ws.getResourcePoolId();

        // 双层配额校验 + 预扣
        quotaService.validateBothLevelQuotas(poolId, workspaceId, spec.getId(), replicas);
        quotaService.deductBothLevelQuotas(poolId, workspaceId, spec.getId(), replicas);

        // 【异构算力】根据 spec 动态选定目标物理集群
        PoolMetadataService.TargetCluster target = poolMetadataService.pickClusterForSpec(poolId, spec);
        String clusterId = target.getClusterId();

        String recordId = UUID.randomUUID().toString();
        TrainingJobRecord record = TrainingJobRecord.builder()
                .id(recordId)
                .workspaceId(workspaceId)
                .resourcePoolId(poolId)
                .specId(spec.getId())
                .replicas(replicas)
                .k8sJobName(request.getJobName())
                .jobName(request.getJobName())
                .status("submitted")
                .createdBy(user.getId())
                .build();
        trainingJobRecordMapper.insert(record);

        try {
            String yaml = K8sResourceBuilder.buildVolcanoJob(
                    request.getJobName(),
                    ws.getNamespace(),
                    ws.getVolcanoQueueName(),
                    replicas,
                    request.getImage(),
                    spec,
                    request.getCommand(),
                    spec.getNodeSelector(),
                    spec.getTolerations());

            // 【异构算力】使用动态选定的 clusterId，而非 ws.primaryClusterId
            clientManager.applyYamlInNamespace(clusterId, ws.getNamespace(), yaml);

            log.info("✅ VolcanoJob {} 已提交 (ws={}, pool={}, spec={}, replicas={})",
                    request.getJobName(), workspaceId, poolId, spec.getName(), replicas);
            return request.getJobName();

        } catch (Exception err) {
            log.warn("⚠️ VolcanoJob 提交失败，回滚配额: {}", err.getMessage(), err);
            try {
                quotaService.rollbackBothLevelQuotas(poolId, workspaceId, spec.getId(), replicas);
            } catch (Exception rb) {
                log.error("❌ 训练任务配额回滚失败: {}", rb.getMessage(), rb);
            }
            record.setStatus("failed");
            trainingJobRecordMapper.update(record);
            throw new RuntimeException("训练任务提交失败: " + err.getMessage(), err);
        }
    }
}
