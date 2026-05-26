package com.acmp.compute.service;

import com.acmp.compute.dto.ModelDeploymentResponse;
import com.acmp.compute.dto.VllmDeployRequest;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.ModelDeployment;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.K8sResourceBuilder;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.ModelDeploymentMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import com.acmp.compute.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * vLLM 模型服务部署。
 *
 * 流程：
 *  ① 校验 workspace 成员
 *  ② 加载 workspace + spec
 *  ③ 双层配额校验（L1: pool, L2: workspace）
 *  ④ 双层配额预扣
 *  ⑤ 构建 K8s Deployment + Service（注入 spec 资源键 + nodeSelector + tolerations + platform.io/{spec}）
 *  ⑥ 提交 K8s；失败回滚配额
 *  ⑦ 删除时同样回滚配额
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDeploymentService {

    private final ModelDeploymentMapper modelDeploymentMapper;
    private final WorkspaceMapper workspaceMapper;
    private final ComputeSpecMapper computeSpecMapper;
    private final KubernetesClientManager clientManager;
    private final QuotaService quotaService;

    private UserPrincipal currentUser() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof UserPrincipal)) throw new ForbiddenException("未登录");
        return (UserPrincipal) p;
    }

    private void ensureCanAccessWorkspace(String workspaceId) {
        List<String> members = workspaceMapper.findMemberIds(workspaceId);
        if (!members.contains(currentUser().getId()))
            throw new ForbiddenException("无权限访问该工作空间");
    }

    /**
     * 部署到指定工作空间。
     * 池 ID 由 workspace.resourcePoolId 推导，规格由 request.specName 指定。
     */
    @Transactional(rollbackFor = Exception.class)
    public ModelDeploymentResponse deploy(String workspaceId, VllmDeployRequest request) {
        ensureCanAccessWorkspace(workspaceId);

        // ② workspace + spec
        Workspace ws = workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + workspaceId));
        String poolId = ws.getResourcePoolId();

        if (request.getSpecName() == null || request.getSpecName().isBlank()) {
            throw new BadRequestException("必须指定 specName");
        }
        ComputeSpec spec = computeSpecMapper.findByName(request.getSpecName())
                .orElseThrow(() -> new ResourceNotFoundException("规格不存在: " + request.getSpecName()));

        int replicas = request.getReplicas() != null ? request.getReplicas() : 1;

        // ③ 双层配额校验
        quotaService.validateBothLevelQuotas(poolId, workspaceId, spec.getId(), replicas);

        // ④ 预扣配额
        quotaService.deductBothLevelQuotas(poolId, workspaceId, spec.getId(), replicas);

        // 生成 K8s 资源名
        String safeName = request.getName().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        String deploymentName = trim("vllm-" + safeName, 50);
        String serviceName = trim(deploymentName + "-svc", 50);

        String modelPath = request.getModelIdOrPath() != null ? request.getModelIdOrPath() : "/models";
        String vllmImage = request.getVllmImage() != null ? request.getVllmImage() : "vllm/vllm-openai:latest";

        String id = UUID.randomUUID().toString();
        ModelDeployment record = ModelDeployment.builder()
                .id(id)
                .workspaceId(workspaceId)
                .resourcePoolId(poolId)
                .specId(spec.getId())
                .name(request.getName())
                .modelName(request.getModelName())
                .modelSource(request.getModelSource())
                .modelIdOrPath(modelPath)
                .vllmImage(vllmImage)
                .gpuPerReplica(spec.getDefaultGpuCount())
                .gpumemMb(spec.getDefaultGpumemMb())
                .gpucores(spec.getDefaultGpucores())
                .replicas(replicas)
                .k8sDeploymentName(deploymentName)
                .k8sServiceName(serviceName)
                .status("pending")
                .createdBy(currentUser().getId())
                .build();
        modelDeploymentMapper.insert(record);

        try {
            // ⑤ 构建 YAML（spec 驱动）
            String yaml = K8sResourceBuilder.buildVllmDeploymentAndService(
                    deploymentName, serviceName, ws.getNamespace(),
                    vllmImage, modelPath,
                    spec, replicas, request.getHostModelPath(),
                    spec.getNodeSelector(), spec.getTolerations());

            // ⑥ K8s 提交
            clientManager.createVllmDeploymentAndService(ws.getPrimaryClusterId(), ws.getNamespace(), yaml);

            String serviceUrl = String.format("http://%s.%s.svc.cluster.local:8000",
                    serviceName, ws.getNamespace());
            record.setStatus("running");
            record.setServiceUrl(serviceUrl);
            modelDeploymentMapper.update(record);

            log.info("✅ vLLM 部署完成: id={}, ws={}, pool={}, spec={}, replicas={}, url={}",
                    id, workspaceId, poolId, spec.getName(), replicas, serviceUrl);

        } catch (Exception err) {
            log.warn("⚠️ K8s 提交失败，回滚配额: {}", err.getMessage(), err);
            try {
                quotaService.rollbackBothLevelQuotas(poolId, workspaceId, spec.getId(), replicas);
            } catch (Exception rb) {
                log.error("❌ 配额回滚失败，需要手工介入: {}", rb.getMessage(), rb);
            }
            record.setStatus("failed");
            modelDeploymentMapper.update(record);
            throw new RuntimeException("vLLM 部署失败: " + err.getMessage(), err);
        }

        return toResponse(record, replicas);
    }

    /**
     * 新版接口：显式指定 pool + workspace。
     * 校验 workspace 确实属于该 pool。
     */
    @Transactional(rollbackFor = Exception.class)
    public ModelDeploymentResponse deployBySpec(String poolId, String workspaceId, VllmDeployRequest request) {
        Workspace ws = workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + workspaceId));
        if (!poolId.equals(ws.getResourcePoolId())) {
            throw new BadRequestException("工作空间 " + workspaceId + " 不属于池 " + poolId);
        }
        return deploy(workspaceId, request);
    }

    public List<ModelDeploymentResponse> listByWorkspace(String workspaceId) {
        ensureCanAccessWorkspace(workspaceId);
        return modelDeploymentMapper.findByWorkspaceId(workspaceId).stream()
                .map(m -> toResponse(m, null))
                .collect(Collectors.toList());
    }

    public ModelDeploymentResponse getStatus(String workspaceId, String deploymentId) {
        ensureCanAccessWorkspace(workspaceId);
        ModelDeployment record = modelDeploymentMapper.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("部署记录不存在"));
        if (!workspaceId.equals(record.getWorkspaceId()))
            throw new ForbiddenException("部署不属于该工作空间");

        Integer ready = null;
        try {
            Workspace ws = workspaceMapper.findById(workspaceId).orElseThrow();
            ready = clientManager.getDeploymentReadyReplicas(
                    ws.getPrimaryClusterId(), ws.getNamespace(), record.getK8sDeploymentName())
                    .orElse(0);
        } catch (Exception ignored) {}
        return toResponse(record, ready);
    }

    /**
     * 删除部署：删 K8s 资源 → 回滚配额 → 删 DB。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String workspaceId, String deploymentId) {
        ensureCanAccessWorkspace(workspaceId);
        ModelDeployment record = modelDeploymentMapper.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("部署记录不存在"));
        if (!workspaceId.equals(record.getWorkspaceId()))
            throw new ForbiddenException("部署不属于该工作空间");

        Workspace ws = workspaceMapper.findById(workspaceId).orElseThrow();

        try {
            clientManager.deleteDeployment(ws.getPrimaryClusterId(), ws.getNamespace(), record.getK8sDeploymentName());
            clientManager.deleteService(ws.getPrimaryClusterId(), ws.getNamespace(), record.getK8sServiceName());
        } catch (Exception e) {
            log.warn("删除 K8s 资源失败（继续回滚配额与删 DB）: {}", e.getMessage());
        }

        // 回滚配额：只对成功扣减过的（status != failed）部署回滚
        if (record.getSpecId() != null && record.getReplicas() != null
                && !"failed".equals(record.getStatus())) {
            try {
                quotaService.rollbackBothLevelQuotas(
                        record.getResourcePoolId(), workspaceId,
                        record.getSpecId(), record.getReplicas());
            } catch (Exception e) {
                log.error("❌ 配额回滚失败: {}", e.getMessage(), e);
            }
        }

        modelDeploymentMapper.deleteById(deploymentId);
        log.info("✓ 部署 {} 已删除 (ws={}, spec={}, replicas={})",
                deploymentId, workspaceId, record.getSpecId(), record.getReplicas());
    }

    private String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private ModelDeploymentResponse toResponse(ModelDeployment m, Integer readyReplicas) {
        return ModelDeploymentResponse.builder()
                .id(m.getId())
                .workspaceId(m.getWorkspaceId())
                .resourcePoolId(m.getResourcePoolId())
                .specId(m.getSpecId())
                .name(m.getName())
                .modelName(m.getModelName())
                .modelSource(m.getModelSource())
                .modelIdOrPath(m.getModelIdOrPath())
                .vllmImage(m.getVllmImage())
                .gpuPerReplica(m.getGpuPerReplica())
                .replicas(m.getReplicas())
                .k8sDeploymentName(m.getK8sDeploymentName())
                .k8sServiceName(m.getK8sServiceName())
                .status(m.getStatus())
                .serviceUrl(m.getServiceUrl())
                .readyReplicas(readyReplicas)
                .createdBy(m.getCreatedBy())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}
