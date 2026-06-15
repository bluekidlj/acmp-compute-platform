package com.acmp.compute.service;

import com.acmp.compute.dto.ModelDeploymentRequest;
import com.acmp.compute.dto.ModelDeploymentResponse;
import com.acmp.compute.dto.ModelResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.GpuSplitSpec;
import com.acmp.compute.entity.GpuBrand;
import com.acmp.compute.entity.ModelDeployment;
import com.acmp.compute.entity.PhysicalCluster;
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
import com.acmp.compute.util.NfsStoragePathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 模型服务部署。
 *
 * 流程：
 *  ① 校验 workspace 成员
 *  ② 部署预检验（PoolScheduler.validateDeployment）：gpuType 是否在池支持范围内
 *  ③ 自动匹配/创建 ComputeSpec（根据 gpuType + 资源参数）
 *  ④ 双层配额校验（L1: pool, L2: workspace）
 *  ⑤ 根据 poolMode 动态选定目标物理集群（PoolMetadataService）
 *  ⑥ 双层配额预扣
 *  ⑦ 如果 modelId 非空，从模型广场获取 nfsPath，自动设置 hostModelPath
 *  ⑧ 构建 K8s Deployment + Service（注入 spec 资源键 + nodeSelector + tolerations + platform.io/{spec}）
 *  ⑨ 提交到⑤中选定的目标物理集群；失败回滚配额
 *  ⑩ 删除时同样回滚配额
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
    /** 【异构算力】用于根据 spec 动态选定目标物理集群 */
    private final PoolMetadataService poolMetadataService;
    /** 【模型广场】用于根据 modelId 自动获取 nfsPath 和 modelSource */
    private final ModelService modelService;

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
     * 部署推理服务（完全自定义每副本资源）。
     * 用户直接指定 gpuCount/cpuCores/memoryGib/gpuType，平台自动匹配或创建 ComputeSpec。
     */
    @Transactional(rollbackFor = Exception.class)
    public ModelDeploymentResponse deploy(String workspaceId, ModelDeploymentRequest request) {
        ensureCanAccessWorkspace(workspaceId);

        Workspace ws = workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + workspaceId));
        String poolId = ws.getResourcePoolId();

        // ② 部署预检验：gpuType 是否在该池支持范围内
        poolMetadataService.validateDeployment(poolId, request);

        // ③ 自动匹配/创建 ComputeSpec
        ComputeSpec spec = ensureComputeSpec(request);

        int replicas = request.getReplicas() != null ? request.getReplicas() : 1;

        // ④ 双层配额校验
        quotaService.validateBothLevelQuotas(poolId, workspaceId, spec.getId(), replicas);

        // ⑤ 根据 poolMode 选定目标物理集群
        PhysicalCluster cluster = poolMetadataService.pickClusterForSpec(poolId, spec);

        // ⑥ 预扣配额
        quotaService.deductBothLevelQuotas(poolId, workspaceId, spec.getId(), replicas);

        // 生成 K8s 资源名
        String safeName = request.getName().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        String deploymentName = trim("vllm-" + safeName, 50);
        String serviceName = trim(deploymentName + "-svc", 50);

        String modelPath = request.getModelIdOrPath() != null ? request.getModelIdOrPath() : "/models";
        String image = request.getImage() != null ? request.getImage() : "vllm/vllm-openai:latest";

        // ⑦ 如果 modelId 非空，从模型广场获取 storagePath 和 modelSource，自动映射
        String hostModelPath = null;
        String effectiveModelSource = request.getModelSource();
        if (request.getModelId() != null && !request.getModelId().isEmpty()) {
            ModelResponse modelResp = modelService.getById(request.getModelId());
            hostModelPath = NfsStoragePathResolver.resolve(modelResp.getStoragePath(), modelResp.getName());
            effectiveModelSource = modelResp.getModelSource();
            log.info("📦 模型广场: modelId={}, storagePath={}, modelSource={}",
                    request.getModelId(), hostModelPath, effectiveModelSource);
        }

        String id = UUID.randomUUID().toString();
        ModelDeployment record = ModelDeployment.builder()
                .id(id)
                .workspaceId(workspaceId)
                .resourcePoolId(poolId)
                .specId(spec.getId())
                .name(request.getName())
                .modelName(request.getModelName())
                .modelSource(effectiveModelSource)
                .modelIdOrPath(modelPath)
                .vllmImage(image)
                .gpuPerReplica(request.getGpuCount())
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
            // 构建 YAML（spec 驱动，支持 envVars/command）
            // hostModelPath 来自模型广场（如果 modelId 非空），否则为 null
            String yaml = K8sResourceBuilder.buildVllmDeploymentAndService(
                    deploymentName, serviceName, ws.getNamespace(),
                    image, modelPath,
                    spec, replicas, hostModelPath,
                    spec.getNodeSelector(), spec.getTolerations(),
                    request.getEnvVars(), request.getCommand(), request.getArgs());

            // 使用选定的 cluster
            clientManager.createVllmDeploymentAndService(cluster.getId(), ws.getNamespace(), yaml);

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
     * 部署推理服务（显式指定 pool + workspace）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ModelDeploymentResponse deployBySpec(String poolId, String workspaceId, ModelDeploymentRequest request) {
        Workspace ws = workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("工作空间不存在: " + workspaceId));
        if (!poolId.equals(ws.getResourcePoolId())) {
            throw new BadRequestException("工作空间 " + workspaceId + " 不属于池 " + poolId);
        }
        return deploy(workspaceId, request);
    }

    /**
     * 根据请求参数自动匹配或创建 ComputeSpec。
     * gpuType 对应 poolLabel（如 nvidia-a100-80g-1/4），用于 nodeSelector 匹配。
     */
    private ComputeSpec ensureComputeSpec(ModelDeploymentRequest request) {
        String gpuType = request.getGpuType();
        int gpuCount = request.getGpuCount() != null ? request.getGpuCount() : 1;
        int cpuCores = request.getCpuCores() != null ? request.getCpuCores() : 4;
        int memoryGib = request.getMemoryGib() != null ? request.getMemoryGib() : 16;

        // 生成 spec 名称
        String specName = "auto-" + gpuType + "-" + gpuCount + "g-" + cpuCores + "c-" + memoryGib + "g";

        // 查找是否已存在
        ComputeSpec existing = computeSpecMapper.findByName(specName).orElse(null);
        if (existing != null) {
            log.info("复用已有 ComputeSpec: {}", specName);
            return existing;
        }

        // 创建新的 ComputeSpec
        GpuSplitSpec splitSpec = GpuSplitSpec.fromSpecName(gpuType);
        int gpumemMb = 16384;  // 默认
        int gpucores = 50;     // 默认
        String gpuBrand = "NVIDIA";
        String displaySuffix = gpuType;

        if (splitSpec != null) {
            gpumemMb = splitSpec.getGpumemMb();
            gpucores = splitSpec.getGpucores();
            gpuBrand = splitSpec.getGpuBrand();
            displaySuffix = GpuSplitSpec.parseSplitType(gpuType);
        }

        String id = UUID.randomUUID().toString();
        ComputeSpec spec = ComputeSpec.builder()
                .id(id)
                .name(specName)
                .displayName(displaySuffix)
                .gpuBrand(GpuBrand.valueOf(gpuBrand))
                .defaultGpuCount(gpuCount)
                .defaultGpumemMb(gpumemMb)
                .defaultGpucores(gpucores)
                .defaultCpuCores(cpuCores)
                .defaultMemoryGib(memoryGib)
                .nodeSelector("{\"pool\":\"" + gpuType + "\"}")
                .tolerations("[{\"key\":\"nvidia.com/gpu\",\"operator\":\"Exists\",\"effect\":\"NoSchedule\"}]")
                .resourceQuotaKey("platform.io/" + specName)
                .specType(ComputeSpec.SpecType.VIRTUAL)
                .memoryGb(gpumemMb / 1024)
                .build();
        computeSpecMapper.insert(spec);

        log.info("✓ 自动创建 ComputeSpec: {}", specName);
        return spec;
    }

    public List<ModelDeploymentResponse> listByWorkspace(String workspaceId) {
        ensureCanAccessWorkspace(workspaceId);
        List<ModelDeploymentResponse> result = new ArrayList<>();
        List<ModelDeployment> records = modelDeploymentMapper.findByWorkspaceId(workspaceId);
        for (ModelDeployment m : records) {
            result.add(toResponse(m, null));
        }
        return result;
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