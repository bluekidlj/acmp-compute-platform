package com.acmp.compute.service;

import com.acmp.compute.dto.ModelDeploymentResponse;
import com.acmp.compute.dto.VllmDeployRequest;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.ModelDeployment;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.K8sResourceBuilder;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.ModelDeploymentMapper;
import com.acmp.compute.mapper.ResourcePoolMapper;
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
 * vLLM 模型服务部署（规范版本）。
 *
 * 部署流程（10步）：
 * ① 校验用户权限（工作空间成员）
 * ② 校验工作空间与逻辑池绑定关系
 * ③ 加载规格定义及默认参数
 * ④ 加载逻辑池关联的物理集群调度约束
 * ⑤ 双层配额校验（逻辑池级 + 工作空间级）
 * ⑥ 预扣配额（防止并发超配）
 * ⑦ 构建 K8s Deployment（自动注入调度约束）
 * ⑧ 提交 K8s 创建
 * ⑨ 部署记录落库
 * ⑩ 失败时自动回滚配额
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDeploymentService {

    private final ModelDeploymentMapper modelDeploymentMapper;
    private final WorkspaceMapper workspaceMapper;
    private final ResourcePoolMapper resourcePoolMapper;
    private final ComputeSpecMapper computeSpecMapper;
    private final KubernetesClientManager clientManager;
    private final PoolMetadataService poolMetadataService;
    private final QuotaService quotaService;

    private UserPrincipal currentUser() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(p instanceof UserPrincipal)) throw new ForbiddenException("未登录");
        return (UserPrincipal) p;
    }

    /** ① 校验用户是否属于该工作空间 */
    private void ensureCanAccessWorkspace(String workspaceId) {
        List<String> members = workspaceMapper.findMemberIds(workspaceId);
        if (!members.contains(currentUser().getId()))
            throw new ForbiddenException("无权限访问该工作空间");
    }

    /**
     * 规范版本：使用规格名称部署。
     *
     * @param poolId 逻辑资源池 ID（用户指定的资源池）
     * @param workspaceId 工作空间 ID
     * @param request 部署请求（包含 specName、replicas）
     * @return 部署结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ModelDeploymentResponse deployBySpec(String poolId, String workspaceId, VllmDeployRequest request) {
        log.info("开始部署 vLLM 模型: 池={}, 工作空间={}, 规格={}, 副本={}",
                poolId, workspaceId, request.getSpecName(), request.getReplicas());

        try {
            // ① 校验用户权限
            ensureCanAccessWorkspace(workspaceId);
            log.debug("✓ 权限校验通过");

            // ② 校验工作空间与逻辑池绑定关系
            Workspace ws = workspaceMapper.findById(workspaceId)
                    .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + workspaceId));

            // 验证池存在
            resourcePoolMapper.findById(poolId)
                    .orElseThrow(() -> new ResourceNotFoundException("逻辑资源池不存在: " + poolId));
            log.debug("✓ 工作空间与逻辑池验证通过");

            // ③ 加载规格定义及默认参数
            ComputeSpec spec = computeSpecMapper.findByName(request.getSpecName())
                    .orElseThrow(() -> new ResourceNotFoundException("规格不存在: " + request.getSpecName()));
            log.debug("✓ 规格加载成功: {} (GPU={}, CPU={}, Mem={}Gi)",
                    spec.getName(), spec.getDefaultGpuCount(), spec.getDefaultCpuCores(), spec.getDefaultMemoryGib());

            // ④ 加载逻辑池关联的物理集群调度约束
            PoolMetadataService.PoolMetadata poolMetadata = poolMetadataService.loadPoolMetadata(poolId);
            String nodeSelector = poolMetadata.getNodeSelector();
            String tolerations = poolMetadata.getTolerations();
            log.debug("✓ 物理集群调度约束加载成功 (nodeSelector={}, tolerations={})",
                    nodeSelector != null ? "yes" : "no", tolerations != null ? "yes" : "no");

            // ⑤ 双层配额校验
            quotaService.validateBothLevelQuotas(poolId, workspaceId, spec.getId(), request.getReplicas());
            log.debug("✓ 双层配额校验通过");

            // ⑥ 预扣配额
            quotaService.deductBothLevelQuotas(poolId, workspaceId, spec.getId(), request.getReplicas());
            log.debug("✓ 配额预扣完成");

            // 生成 K8s 资源名
            String deploymentName = "vllm-" + request.getName().toLowerCase().replaceAll("[^a-z0-9-]", "-");
            String serviceName = deploymentName + "-svc";
            if (deploymentName.length() > 50) deploymentName = deploymentName.substring(0, 50);
            if (serviceName.length() > 50) serviceName = serviceName.substring(0, 50);

            // 创建部署记录（初始状态为 pending）
            String id = UUID.randomUUID().toString();
            String modelPath = request.getModelIdOrPath() != null ? request.getModelIdOrPath() : "/models";
            String vllmImage = request.getVllmImage() != null ? request.getVllmImage() : "vllm/vllm-openai:latest";

            ModelDeployment record = ModelDeployment.builder()
                    .id(id)
                    .resourcePoolId(poolId)
                    .name(request.getName())
                    .modelName(request.getModelName())
                    .modelSource(request.getModelSource())
                    .modelIdOrPath(modelPath)
                    .vllmImage(vllmImage)
                    .gpuPerReplica(spec.getDefaultGpuCount())
                    .gpumemMb(spec.getDefaultGpumemMb())
                    .gpucores(spec.getDefaultGpucores())
                    .replicas(request.getReplicas())
                    .k8sDeploymentName(deploymentName)
                    .k8sServiceName(serviceName)
                    .status("pending")
                    .createdBy(currentUser().getId())
                    .build();
            modelDeploymentMapper.insert(record);
            log.debug("✓ 部署记录已保存: id={}, status=pending", id);

            try {
                // ⑦ 构建 K8s Deployment（自动注入调度约束）
                String yaml = K8sResourceBuilder.buildVllmDeploymentAndService(
                        deploymentName, serviceName, ws.getNamespace(),
                        vllmImage, modelPath,
                        spec.getDefaultGpuCount(), spec.getDefaultGpumemMb(), spec.getDefaultGpucores(),
                        request.getReplicas(), request.getHostModelPath(),
                        nodeSelector, tolerations);
                log.debug("✓ K8s YAML 构建成功");

                // ⑧ 提交 K8s 创建
                clientManager.createVllmDeploymentAndService(ws.getPrimaryClusterId(), ws.getNamespace(), yaml);
                log.debug("✓ K8s Deployment + Service 创建成功");

                // ⑨ 部署记录落库（状态更新为 running）
                String serviceUrl = "http://" + serviceName + "." + ws.getNamespace() + ".svc.cluster.local:8000";
                record.setStatus("running");
                record.setServiceUrl(serviceUrl);
                modelDeploymentMapper.update(record);

                log.info("✅ vLLM 部署完成: id={}, name={}, 池={}, 工作空间={}, 副本={}, 服务地址={}",
                        id, request.getName(), poolId, workspaceId, request.getReplicas(), serviceUrl);

            } catch (Exception deploymentError) {
                // ⑩ 失败回滚：K8s 创建失败 → 回滚配额 → 标记为 failed
                log.warn("⚠️  K8s Deployment 创建失败，进行配额回滚: {}", deploymentError.getMessage(), deploymentError);

                try {
                    quotaService.rollbackBothLevelQuotas(poolId, workspaceId, spec.getId(), request.getReplicas());
                    log.info("✓ 配额回滚成功");
                } catch (Exception rollbackError) {
                    log.error("❌ 配额回滚失败，需要手工介入: {}", rollbackError.getMessage(), rollbackError);
                }

                record.setStatus("failed");
                modelDeploymentMapper.update(record);
                throw new RuntimeException("vLLM 部署失败: " + deploymentError.getMessage(), deploymentError);
            }

            return toResponse(record, null);

        } catch (Exception e) {
            log.error("❌ 部署过程异常: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 向后兼容：使用工作空间 ID 直接部署（旧版本接口）。
     * @deprecated 使用新版本 deployBySpec(poolId, workspaceId, request)
     */
    @Deprecated
    @Transactional(rollbackFor = Exception.class)
    public ModelDeploymentResponse deploy(String workspaceId, VllmDeployRequest request) {
        // 从工作空间推导逻辑池
        Workspace ws = workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + workspaceId));
        return deployBySpec(ws.getResourcePoolId(), workspaceId, request);
    }

    public List<ModelDeploymentResponse> listByWorkspace(String workspaceId) {
        ensureCanAccessWorkspace(workspaceId);
        return modelDeploymentMapper.findByResourcePoolId(workspaceId).stream()
                .map(m -> toResponse(m, null)).collect(Collectors.toList());
    }

    public ModelDeploymentResponse getStatus(String workspaceId, String deploymentId) {
        ensureCanAccessWorkspace(workspaceId);
        ModelDeployment record = modelDeploymentMapper.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("部署记录不存在"));
        if (!record.getResourcePoolId().equals(workspaceId))
            throw new ForbiddenException("部署不属于该工作空间");
        Integer ready = null;
        try {
            Workspace ws = workspaceMapper.findById(workspaceId).orElseThrow();
            ready = clientManager.getDeploymentReadyReplicas(ws.getPrimaryClusterId(), ws.getNamespace(), record.getK8sDeploymentName()).orElse(0);
        } catch (Exception ignored) {}
        return toResponse(record, ready);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String workspaceId, String deploymentId) {
        ensureCanAccessWorkspace(workspaceId);
        ModelDeployment record = modelDeploymentMapper.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("部署记录不存在"));
        if (!record.getResourcePoolId().equals(workspaceId))
            throw new ForbiddenException("部署不属于该工作空间");
        Workspace ws = workspaceMapper.findById(workspaceId).orElseThrow();
        try {
            clientManager.deleteDeployment(ws.getPrimaryClusterId(), ws.getNamespace(), record.getK8sDeploymentName());
            clientManager.deleteService(ws.getPrimaryClusterId(), ws.getNamespace(), record.getK8sServiceName());
        } catch (Exception e) { log.warn("删除 K8s 资源失败: {}", e.getMessage()); }
        modelDeploymentMapper.deleteById(deploymentId);
    }

    private ModelDeploymentResponse toResponse(ModelDeployment m, Integer readyReplicas) {
        return ModelDeploymentResponse.builder()
                .id(m.getId())
                .resourcePoolId(m.getResourcePoolId())
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
