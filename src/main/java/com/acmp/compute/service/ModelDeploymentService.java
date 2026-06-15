package com.acmp.compute.service;

import com.acmp.compute.dto.ModelDeploymentRequest;
import com.acmp.compute.dto.ModelDeploymentResponse;
import com.acmp.compute.dto.ModelResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.ModelDeployment;
import com.acmp.compute.entity.Project;
import com.acmp.compute.entity.ProjectResourceQuota;
import com.acmp.compute.entity.ResourcePool;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.K8sResourceBuilder;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.ModelDeploymentMapper;
import com.acmp.compute.mapper.ProjectMapper;
import com.acmp.compute.mapper.ProjectResourceQuotaMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import com.acmp.compute.security.UserPrincipal;
import com.acmp.compute.util.NfsStoragePathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 1.0 模型部署服务。
 *
 * <h2>部署流程</h2>
 * <ol>
 *   <li>校验项目成员</li>
 *   <li>加载 spec → specType 决定 poolType</li>
 *   <li>在项目拥有的池中找该 poolType 的池，且池已关联该 spec</li>
 *   <li>三层配额校验：project quota / pool allocated / 1.0 replicas=1</li>
 *   <li>预扣 project.used + pool.allocated</li>
 *   <li>调 K8sResourceBuilder 生成 YAML，提交 K8s</li>
 *   <li>失败回滚</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDeploymentService {

    private final ModelDeploymentMapper deploymentMapper;
    private final ProjectMapper projectMapper;
    private final WorkspaceMapper workspaceMapper;
    private final ResourcePoolMapper poolMapper;
    private final ComputeSpecMapper specMapper;
    private final ProjectResourceQuotaMapper projectQuotaMapper;
    private final KubernetesClientManager clientManager;
    private final ModelService modelService;

    private UserPrincipal currentUser() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof UserPrincipal)) throw new ForbiddenException("未登录");
        return (UserPrincipal) p;
    }

    private void ensureCanAccessProject(String projectId) {
        List<String> members = projectMapper.findMemberIds(projectId);
        if (!members.contains(currentUser().getId())) {
            throw new ForbiddenException("无权限访问该项目");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelDeploymentResponse deploy(String projectId, ModelDeploymentRequest req) {
        ensureCanAccessProject(projectId);

        Project project = projectMapper.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在: " + projectId));
        Workspace ws = workspaceMapper.findById(project.getWorkspaceId())
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + project.getWorkspaceId()));

        // 1.0 replicas 必须 = 1
        if (req.getReplicas() != null && req.getReplicas() != 1) {
            throw new BadRequestException("1.0 仅支持 1 节点部署 (replicas 必须为 1)");
        }
        int replicas = 1;

        // 加载 spec
        ComputeSpec spec = specMapper.findByName(req.getSpecName())
                .orElseThrow(() -> new BadRequestException("规格不存在: " + req.getSpecName()));

        // 1.0 超分暂未实现真实 K8s 提交
        if ("OVERSELL".equals(spec.getPoolType())) {
            // 仍记账，但不调 K8s
            log.warn("⚠️ 超分池 {} 1.0 暂未实现真实 K8s 提交，仅记账", spec.getName());
        }

        // 找匹配池：项目所属 WS 拥有该 poolType 池，且池已关联该 spec
        ResourcePool pool = findMatchingPool(project.getWorkspaceId(), spec);

        // 项目配额校验
        ProjectResourceQuota quota = projectQuotaMapper.findByProjectPoolSpec(projectId, pool.getId(), spec.getId())
                .orElseThrow(() -> new BadRequestException(
                        "项目 " + project.getName() + " 在 " + pool.getName() + " 中未配置规格 " + spec.getName() + " 的配额"));

        int used = quota.getUsedNodes() != null ? quota.getUsedNodes() : 0;
        int total = quota.getTotalNodes() != null ? quota.getTotalNodes() : 0;
        if (used + replicas > total) {
            throw new BadRequestException(String.format(
                    "项目配额不足: 规格=%s, total=%d, used=%d, 请求=%d",
                    spec.getName(), total, used, replicas));
        }

        // 预扣
        projectQuotaMapper.updateUsedNodes(quota.getId(), used + replicas);
        // 注：pool.allocated 不在部署时修改。pool.allocated 仅在 ProjectQuotaService.allocate 分配配额时累加。

        // K8s 资源名
        String safeName = req.getName().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        String deploymentName = trim("vllm-" + safeName, 50);
        String serviceName = trim(deploymentName + "-svc", 50);

        String modelPath = req.getModelIdOrPath() != null ? req.getModelIdOrPath() : "/models";
        String image = req.getImage() != null ? req.getImage() : "vllm/vllm-openai:latest";
        String hostModelPath = null;
        String effectiveModelSource = req.getModelSource();
        if (req.getModelId() != null && !req.getModelId().isEmpty()) {
            ModelResponse modelResp = modelService.getById(req.getModelId());
            hostModelPath = NfsStoragePathResolver.resolve(modelResp.getStoragePath(), modelResp.getName());
            effectiveModelSource = modelResp.getModelSource();
        }

        String id = UUID.randomUUID().toString();
        String clusterId = ws.getPrimaryClusterId();
        ModelDeployment record = ModelDeployment.builder()
                .id(id)
                .projectId(projectId)
                .workspaceId(ws.getId())
                .resourcePoolId(pool.getId())
                .specId(spec.getId())
                .poolType(spec.getPoolType())
                .name(req.getName())
                .modelName(req.getModelName())
                .modelSource(effectiveModelSource)
                .modelIdOrPath(modelPath)
                .vllmImage(image)
                .gpuPerReplica(spec.getDefaultGpuCount())
                .gpumemMb(spec.getDefaultGpumemMb())
                .gpucores(spec.getDefaultGpucores())
                .replicas(replicas)
                .k8sDeploymentName(deploymentName)
                .k8sServiceName(serviceName)
                .status("pending")
                .actualClusterId(clusterId)
                .createdBy(currentUser().getId())
                .build();
        deploymentMapper.insert(record);

        // 超分池跳过 K8s 提交
        if ("OVERSELL".equals(spec.getPoolType())) {
            deploymentMapper.updateStatus(id, "running", null);
            ModelDeployment refreshed = deploymentMapper.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("部署不存在: " + id));
            log.info("✅ 超分占位部署完成（未提交 K8s）: id={}, project={}, spec={}", id, projectId, spec.getName());
            return toResponse(refreshed, null);
        }

        try {
            String yaml = K8sResourceBuilder.buildVllmDeploymentAndService(
                    deploymentName, serviceName, ws.getNamespace(),
                    image, modelPath, spec, replicas, hostModelPath,
                    spec.getNodeSelector(), spec.getTolerations(),
                    req.getEnvVars(), req.getCommand(), req.getArgs());
            clientManager.createVllmDeploymentAndService(clusterId, ws.getNamespace(), yaml);
            deploymentMapper.updateActualClusterId(id, clusterId);

            String serviceUrl = String.format("http://%s.%s.svc.cluster.local:8000", serviceName, ws.getNamespace());
            deploymentMapper.updateStatus(id, "running", serviceUrl);
            record.setStatus("running");
            record.setServiceUrl(serviceUrl);

            log.info("✅ vLLM 部署完成: id={}, project={}, spec={}, url={}", id, projectId, spec.getName(), serviceUrl);
        } catch (Exception err) {
            log.warn("⚠️ K8s 提交失败，回滚配额: {}", err.getMessage(), err);
            // 回滚 project.used
            projectQuotaMapper.updateUsedNodes(quota.getId(), used);
            deploymentMapper.updateStatus(id, "failed", null);
            record.setStatus("failed");
            throw new RuntimeException("vLLM 部署失败: " + err.getMessage(), err);
        }

        return toResponse(record, null);
    }

    public List<ModelDeploymentResponse> listByProject(String projectId) {
        ensureCanAccessProject(projectId);
        return deploymentMapper.findByProjectId(projectId).stream()
                .map(m -> toResponse(m, null)).collect(java.util.stream.Collectors.toList());
    }

    public ModelDeploymentResponse getStatus(String projectId, String deploymentId) {
        ensureCanAccessProject(projectId);
        ModelDeployment record = deploymentMapper.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("部署记录不存在"));
        if (!projectId.equals(record.getProjectId())) {
            throw new ForbiddenException("部署不属于该项目");
        }
        Integer ready = null;
        try {
            String clusterId = record.getActualClusterId();
            Workspace ws = workspaceMapper.findById(record.getWorkspaceId()).orElseThrow();
            if (clusterId == null || clusterId.isEmpty()) clusterId = ws.getPrimaryClusterId();
            ready = clientManager.getDeploymentReadyReplicas(
                    clusterId, ws.getNamespace(), record.getK8sDeploymentName()).orElse(0);
        } catch (Exception ignored) {}
        return toResponse(record, ready);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String projectId, String deploymentId) {
        ensureCanAccessProject(projectId);
        ModelDeployment record = deploymentMapper.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("部署记录不存在"));
        if (!projectId.equals(record.getProjectId())) {
            throw new ForbiddenException("部署不属于该项目");
        }
        Workspace ws = workspaceMapper.findById(record.getWorkspaceId()).orElseThrow();
        String clusterId = record.getActualClusterId() != null && !record.getActualClusterId().isEmpty()
                ? record.getActualClusterId() : ws.getPrimaryClusterId();

        // 删 K8s 资源（非超分）
        if (!"OVERSELL".equals(record.getPoolType()) && record.getK8sDeploymentName() != null) {
            try {
                clientManager.deleteDeployment(clusterId, ws.getNamespace(), record.getK8sDeploymentName());
                clientManager.deleteService(clusterId, ws.getNamespace(), record.getK8sServiceName());
            } catch (Exception e) {
                log.warn("删除 K8s 资源失败（继续）: {}", e.getMessage());
            }
        }

        // 回滚配额
        if (record.getSpecId() != null && record.getReplicas() != null
                && !"failed".equals(record.getStatus())) {
            try {
                Optional<ProjectResourceQuota> q = projectQuotaMapper.findByProjectPoolSpec(
                        projectId, record.getResourcePoolId(), record.getSpecId());
                if (q.isPresent()) {
                    int used = q.get().getUsedNodes() != null ? q.get().getUsedNodes() : 0;
                    projectQuotaMapper.updateUsedNodes(q.get().getId(),
                            Math.max(0, used - record.getReplicas()));
                }
            } catch (Exception e) {
                log.error("❌ 配额回滚失败: {}", e.getMessage(), e);
            }
        }

        deploymentMapper.deleteById(deploymentId);
        log.info("✓ 部署 {} 已删除", deploymentId);
    }

    private ResourcePool findMatchingPool(String workspaceId, ComputeSpec spec) {
        ResourcePool pool = poolMapper.findByWorkspaceAndType(workspaceId, spec.getPoolType())
                .orElseThrow(() -> new BadRequestException(
                        "工作空间 " + workspaceId + " 未拥有 " + spec.getPoolType() + " 类型池"));
        if (!specMapper.findSpecIdsByResourcePoolId(pool.getId()).contains(spec.getId())) {
            throw new BadRequestException(
                    "资源池 " + pool.getName() + " 未关联规格 " + spec.getName());
        }
        return pool;
    }

    private String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private ModelDeploymentResponse toResponse(ModelDeployment m, Integer readyReplicas) {
        return ModelDeploymentResponse.builder()
                .id(m.getId())
                .projectId(m.getProjectId())
                .workspaceId(m.getWorkspaceId())
                .resourcePoolId(m.getResourcePoolId())
                .specId(m.getSpecId())
                .poolType(m.getPoolType())
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
                .actualClusterId(m.getActualClusterId())
                .createdBy(m.getCreatedBy())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}
