package com.acmp.compute.service;

import com.acmp.compute.dto.ModelDeploymentRequest;
import com.acmp.compute.dto.ModelDeploymentResponse;
import com.acmp.compute.dto.DeploymentMetricsResponse;
import com.acmp.compute.dto.ModelResponse;
import com.acmp.compute.dto.ChatCompletionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.GpuDevice;
import com.acmp.compute.entity.ModelDeployment;
import com.acmp.compute.entity.Project;
import com.acmp.compute.entity.TenantSpecQuota;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.K8sResourceBuilder;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.GpuDeviceMapper;
import com.acmp.compute.mapper.ModelDeploymentMapper;
import com.acmp.compute.mapper.ProjectMapper;
import com.acmp.compute.security.UserPrincipal;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDeploymentService {
    private static final Pattern PROMETHEUS_SAMPLE = Pattern.compile(
            "^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{[^}]*})?\\s+([^\\s]+)(?:\\s+\\d+)?$");
    private final ModelDeploymentMapper mapper;
    private final ProjectMapper projectMapper;
    private final ComputeSpecMapper specMapper;
    private final GpuDeviceMapper gpuMapper;
    private final TenantSpecQuotaService quotaService;
    private final KubernetesClientManager clientManager;
    private final ModelService modelService;
    private final ObjectMapper objectMapper;

    /**
     * 单体同步流程：校验 → 扣租户配额 → 创建 DB 记录 → 调 Kubernetes。
     * Kubernetes 调用失败时在当前请求中恢复配额并保留 FAILED 记录，便于内网调试。
     */
    @Transactional
    public ModelDeploymentResponse deploy(String projectId, ModelDeploymentRequest request) {
        Project project = project(projectId);
        ensureAccess(project);
        ComputeSpec spec = specMapper.findByName(request.getSpecName()).orElse(null);
        if (spec == null) {
            throw new ResourceNotFoundException("规格不存在: " + request.getSpecName());
        }
        if (spec.getResourcePoolId() == null) {
            throw new BadRequestException("规格未绑定固定资源池");
        }

        int replicas = request.getReplicas() == null ? 1 : request.getReplicas();
        if (replicas <= 0) {
            throw new BadRequestException("replicas 必须大于 0");
        }
        int gpuCountPerReplica = spec.getGpuCount() == null ? 1 : spec.getGpuCount();
        if (gpuCountPerReplica <= 0) {
            throw new BadRequestException("算力规格 GPU 数量必须大于 0");
        }
        int tensorParallelSize = request.getTensorParallelSize() == null
                ? gpuCountPerReplica
                : request.getTensorParallelSize();
        if (tensorParallelSize <= 0) {
            throw new BadRequestException("tensorParallelSize 必须大于 0");
        }
        if (tensorParallelSize > gpuCountPerReplica) {
            throw new BadRequestException("tensorParallelSize 不能大于 gpuCountPerReplica");
        }
        double gpuMemoryUtilization = request.getGpuMemoryUtilization() == null
                ? 0.8D
                : request.getGpuMemoryUtilization();
        if (gpuMemoryUtilization <= 0.0D || gpuMemoryUtilization > 1.0D) {
            throw new BadRequestException("gpuMemoryUtilization 必须在 0 到 1 之间");
        }
        int maxModelLength = request.getMaxModelLength() == null ? 8192 : request.getMaxModelLength();
        if (maxModelLength <= 0) {
            throw new BadRequestException("maxModelLength 必须大于 0");
        }
        int port = request.getPort() == null ? 8000 : request.getPort();
        if (port < 1 || port > 65535) {
            throw new BadRequestException("port 必须在 1 到 65535 之间");
        }
        String clusterId = candidateCluster(spec, replicas, gpuCountPerReplica);

        TenantSpecQuota quota = quotaService.requireAvailable(project.getTenantId(), spec.getId(), replicas);
        quotaService.changeUsed(quota.getId(), replicas);

        String id = UUID.randomUUID().toString();
        String safeName = request.getName().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        String deploymentName = trim("vllm-" + safeName + "-" + id.substring(0, 6), 63);
        String serviceName = trim(deploymentName + "-svc", 63);
        String namespace = "tenant-" + project.getTenantId().substring(0, Math.min(8, project.getTenantId().length()));
        String image = request.getImage();
        String modelPath = request.getModelIdOrPath() == null ? "/models" : request.getModelIdOrPath();
        String hostModelPath = null;
        String modelSource = request.getModelSource();
        if (request.getModelId() != null && !request.getModelId().isBlank()) {
            ModelResponse model = modelService.getById(request.getModelId());
            // 模型登记保存的是 GPU 主机完整绝对目录，直接写入 hostPath.path。
            hostModelPath = model.getStoragePath();
            modelSource = model.getModelSource();
        }
        boolean vllmMode = image != null && image.contains("vllm");
        String command = request.getCommand();
        String args = request.getArgs();
        java.util.Map<String, String> envVars = request.getEnvVars() == null
                ? new java.util.HashMap<>()
                : new java.util.HashMap<>(request.getEnvVars());
        envVars.remove("CUDA_DISABLE_CONTROL");
        if ("EXCLUSIVE".equals(spec.getSpecType())) {
            envVars.put("CUDA_DISABLE_CONTROL", "true");
        }
        if (vllmMode) {
            if (request.getModelName() == null || request.getModelName().isBlank()) {
                throw new BadRequestException("vLLM 部署必须提供模型名称");
            }
            command = (command == null || command.isBlank()) ? "vllm" : command;
            args = buildVllmArgs(modelPath, port, request.getModelName(), tensorParallelSize,
                    gpuMemoryUtilization, maxModelLength);
        }
        List<String> assignedGpuIds = new ArrayList<>();
        if ("EXCLUSIVE".equals(spec.getSpecType())) {
            int requiredGpu = replicas * gpuCountPerReplica;
            assignedGpuIds = reserveGpuDevices(clusterId, spec.getGpuModel(), requiredGpu);
        }

        log.info("推理部署准备: projectId={}, tenantId={}, clusterId={}, namespace={}, specId={}, "
                        + "specName={}, specType={}, replicas={}, gpuCountPerReplica={}, tensorParallelSize={}, "
                        + "gpuMemoryUtilization={}, maxModelLength={}, image={}, modelId={}, hostModelPath={}, "
                        + "containerModelPath={}, port={}",
                projectId, project.getTenantId(), clusterId, namespace, spec.getId(), spec.getName(),
                spec.getSpecType(), replicas, gpuCountPerReplica, tensorParallelSize, gpuMemoryUtilization,
                maxModelLength, image, request.getModelId(), hostModelPath, modelPath, port);

        ModelDeployment record = ModelDeployment.builder().id(id).projectId(projectId)
                .tenantId(project.getTenantId())
                .modelId(request.getModelId()).resourcePoolId(spec.getResourcePoolId())
                .specId(spec.getId()).name(request.getName())
                .modelName(request.getModelName()).modelSource(modelSource).modelIdOrPath(modelPath)
                .vllmImage(image).port(port)
                .gpuCountPerReplica(gpuCountPerReplica)
                .tensorParallelSize(tensorParallelSize)
                .gpuMemoryUtilization(gpuMemoryUtilization)
                .maxModelLength(maxModelLength)
                .assignedGpuIdsJson(jsonArray(assignedGpuIds))
                .replicas(replicas).k8sDeploymentName(deploymentName).k8sServiceName(serviceName)
                .status("PENDING").actualClusterId(clusterId).createdBy(currentUser().getId()).build();
        try {
            mapper.insert(record);
            // Tenant 不再永久绑定 Namespace；部署时按 Tenant ID 创建稳定 Namespace。
            log.info("推理部署阶段开始: deploymentId={}, stage=create-namespace", id);
            clientManager.createNamespace(clusterId, namespace);
            log.info("推理部署阶段开始: deploymentId={}, stage=build-kubernetes-resources", id);
            V1Deployment deployment = K8sResourceBuilder.buildVllmDeployment(
                    deploymentName, namespace, image, modelPath, port, spec, replicas, gpuCountPerReplica,
                    hostModelPath, envVars, command, args);
            V1Service service = K8sResourceBuilder.buildVllmService(
                    serviceName,
                    namespace,
                    deploymentName,
                    port);
            log.info("推理部署阶段开始: deploymentId={}, stage=submit-deployment-service", id);
            clientManager.createVllmDeploymentAndService(clusterId, namespace, deployment, service);
            String url = "http://" + serviceName + "." + namespace + ".svc.cluster.local:" + port;
            mapper.updateStatus(id, "SUBMITTED", url);
            record.setStatus("SUBMITTED");
            record.setServiceUrl(url);
            log.info("推理部署提交成功: deploymentId={}, deploymentName={}, serviceName={}, serviceUrl={}",
                    id, deploymentName, serviceName, url);
        } catch (Exception e) {
            releaseGpuDevices(assignedGpuIds);
            quotaService.changeUsed(quota.getId(), -replicas);
            if (mapper.findById(id).isPresent()) {
                mapper.updateStatus(id, "FAILED", null);
                mapper.updateFailure(id, shortMessage(e.getMessage()));
            }
            record.setStatus("FAILED");
            record.setFailureMessage(shortMessage(e.getMessage()));
            log.error("推理服务提交 Kubernetes 失败: deploymentId={}, clusterId={}, namespace={}, "
                            + "deploymentName={}, serviceName={}, error={}",
                    id, clusterId, namespace, deploymentName, serviceName, e.getMessage(), e);
        }
        ModelDeployment saved = mapper.findById(id).orElse(record);
        return response(saved, null);
    }

    /**
     * 查询当前用户有权访问的全部推理服务。
     */
    public List<ModelDeploymentResponse> listAccessible() {
        List<ModelDeploymentResponse> result = new ArrayList<>();
        for (ModelDeployment record : mapper.findAll()) {
            Project project = projectMapper.findById(record.getProjectId()).orElse(null);
            if (project != null && canAccess(project)) {
                Integer ready = null;
                try {
                    ready = clientManager.getDeploymentReadyReplicas(
                            record.getActualClusterId(),
                            namespace(record.getTenantId()),
                            record.getK8sDeploymentName()).orElse(0);
                } catch (Exception exception) {
                    log.warn("全局列表查询就绪副本失败: deployment={}, error={}",
                            record.getId(), exception.getMessage());
                }
                result.add(response(record, ready));
            }
        }
        return result;
    }

    public List<ModelDeploymentResponse> listByProject(String projectId) {
        Project project = project(projectId);
        ensureAccess(project);
        List<ModelDeploymentResponse> result = new ArrayList<>();
        for (ModelDeployment record : mapper.findByProjectId(projectId)) {
            result.add(response(record, null));
        }
        return result;
    }

    public ModelDeploymentResponse getStatus(String projectId, String deploymentId) {
        Project project = project(projectId);
        ensureAccess(project);
        ModelDeployment record = record(projectId, deploymentId);
        Integer ready = null;
        try {
            String namespace = namespace(record.getTenantId());
            ready = clientManager.getDeploymentReadyReplicas(record.getActualClusterId(), namespace,
                    record.getK8sDeploymentName()).orElse(0);
            if (ready >= record.getReplicas() && !"RUNNING".equals(record.getStatus())) {
                mapper.updateStatus(record.getId(), "RUNNING", record.getServiceUrl());
                record.setStatus("RUNNING");
            }
        } catch (Exception e) {
            log.warn("查询 Kubernetes Deployment 状态失败，保留数据库状态: {}", e.getMessage());
        }
        return response(record, ready);
    }

    public DeploymentMetricsResponse metrics(String projectId, String deploymentId) {
        Project project = project(projectId);
        ensureAccess(project);
        ModelDeployment record = record(projectId, deploymentId);
        Instant collectedAt = Instant.now();
        try {
            String metricsText = clientManager.getServiceProxy(
                    record.getActualClusterId(),
                    namespace(record.getTenantId()),
                    record.getK8sServiceName(),
                    record.getPort(),
                    "/metrics");
            Map<String, List<Double>> samples = parsePrometheusSamples(metricsText);
            Double running = metricSum(samples, "vllm:num_requests_running");
            Double waiting = metricSum(samples, "vllm:num_requests_waiting");
            Double promptTokens = metricSum(samples, "vllm:prompt_tokens_total");
            Double generationTokens = metricSum(samples, "vllm:generation_tokens_total");
            Double successfulRequests = metricSum(samples, "vllm:request_success_total");
            Double gpuCacheRatio = metricAverage(samples, "vllm:gpu_cache_usage_perc");
            Double e2eLatency = histogramAverageMs(samples, "vllm:e2e_request_latency_seconds");
            Double ttft = histogramAverageMs(samples, "vllm:time_to_first_token_seconds");
            boolean available = running != null || waiting != null || promptTokens != null
                    || generationTokens != null || successfulRequests != null || gpuCacheRatio != null;
            return DeploymentMetricsResponse.builder()
                    .deploymentId(record.getId())
                    .available(available)
                    .message(available ? null : "vLLM /metrics 未返回可识别的指标")
                    .collectedAt(collectedAt)
                    .runningRequests(running)
                    .waitingRequests(waiting)
                    .promptTokensTotal(promptTokens)
                    .generationTokensTotal(generationTokens)
                    .successfulRequestsTotal(successfulRequests)
                    .gpuCacheUsagePercent(gpuCacheRatio == null ? null : gpuCacheRatio * 100.0D)
                    .averageE2eLatencyMs(e2eLatency)
                    .averageTimeToFirstTokenMs(ttft)
                    .build();
        } catch (Exception exception) {
            String message = shortMessage(exception.getMessage());
            log.warn("读取 vLLM 指标失败: deploymentId={}, error={}", deploymentId, message);
            return DeploymentMetricsResponse.builder()
                    .deploymentId(record.getId())
                    .available(false)
                    .message(message)
                    .collectedAt(collectedAt)
                    .build();
        }
    }

    private Map<String, List<Double>> parsePrometheusSamples(String metricsText) {
        Map<String, List<Double>> result = new HashMap<>();
        if (metricsText == null || metricsText.isBlank()) {
            return result;
        }
        for (String line : metricsText.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = PROMETHEUS_SAMPLE.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            try {
                double value = Double.parseDouble(matcher.group(2));
                if (Double.isFinite(value)) {
                    result.computeIfAbsent(matcher.group(1), ignored -> new ArrayList<>()).add(value);
                }
            } catch (NumberFormatException ignored) {
                // NaN and infinity do not represent usable monitoring values.
            }
        }
        return result;
    }

    private Double metricSum(Map<String, List<Double>> samples, String metric) {
        List<Double> values = samples.get(metric);
        if (values == null || values.isEmpty()) {
            return null;
        }
        double total = 0.0D;
        for (Double value : values) {
            total += value;
        }
        return total;
    }

    private Double metricAverage(Map<String, List<Double>> samples, String metric) {
        List<Double> values = samples.get(metric);
        if (values == null || values.isEmpty()) {
            return null;
        }
        double total = 0.0D;
        for (Double value : values) {
            total += value;
        }
        return total / values.size();
    }

    private Double histogramAverageMs(Map<String, List<Double>> samples, String metricPrefix) {
        Double sum = metricSum(samples, metricPrefix + "_sum");
        Double count = metricSum(samples, metricPrefix + "_count");
        return sum == null || count == null || count <= 0.0D ? null : sum / count * 1000.0D;
    }

    /**
     * 将 OpenAI 兼容请求代理到部署对应的 Kubernetes ClusterIP Service。
     *
     * 客户端连接最容易在内网调试时出错，因此这里不接受外部 URL：
     * 目标地址和模型名必须来自已经持久化且通过权限校验的部署记录。
     */
    public JsonNode chat(
            String projectId,
            String deploymentId,
            ChatCompletionRequest request) {
        Project project = project(projectId);
        ensureAccess(project);
        ModelDeployment record = record(projectId, deploymentId);

        if (!"RUNNING".equals(record.getStatus())) {
            throw new BadRequestException("推理服务尚未运行，当前状态: " + record.getStatus());
        }
        if (record.getServiceUrl() == null || record.getServiceUrl().isBlank()) {
            throw new BadRequestException("推理服务没有可用的内部地址");
        }
        if (record.getModelName() == null || record.getModelName().isBlank()) {
            throw new BadRequestException("推理服务没有配置模型名称");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", record.getModelName());
        body.set("messages", objectMapper.valueToTree(request.getMessages()));
        body.put("temperature", request.getTemperature());
        body.put("top_p", request.getTopP());
        body.put("repetition_penalty", request.getRepetitionPenalty());
        body.put("max_tokens", request.getMaxTokens());
        body.put("stream", false);

        try {
            String result = clientManager.postServiceProxy(
                    record.getActualClusterId(),
                    namespace(record.getTenantId()),
                    record.getK8sServiceName(),
                    record.getPort(),
                    "/v1/chat/completions",
                    objectMapper.writeValueAsString(body));
            return parseChatResponse(record, result);
        } catch (Exception exception) {
            log.warn("调用推理服务失败: deployment={}, service={}, error={}",
                    deploymentId, record.getK8sServiceName(), exception.getMessage());
            throw new BadRequestException("调用推理服务失败: " + shortMessage(exception.getMessage()));
        }
    }

    /**
     * 解析真实 vLLM 的 OpenAI JSON 响应。
     *
     * <p>Docker Desktop 没有真实 GPU，本地验证部署使用 hashicorp/http-echo
     * 证明 Service Proxy 链路可达。只有这个明确的演示镜像允许把纯文本包装为
     * OpenAI Chat Completions 响应；其他镜像返回非法 JSON 时仍直接报错。
     */
    private JsonNode parseChatResponse(ModelDeployment record, String responseBody)
            throws Exception {
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception parseException) {
            if (record.getVllmImage() == null
                    || !record.getVllmImage().startsWith("hashicorp/http-echo:")) {
                throw parseException;
            }

            String content = responseBody == null ? "" : responseBody.trim();
            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "assistant");
            message.put("content", content);

            ObjectNode choice = objectMapper.createObjectNode();
            choice.put("index", 0);
            choice.set("message", message);
            choice.put("finish_reason", "stop");

            ArrayNode choices = objectMapper.createArrayNode();
            choices.add(choice);

            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", 0);
            usage.put("total_tokens", 0);

            ObjectNode response = objectMapper.createObjectNode();
            response.put("id", "local-protocol-demo-" + record.getId());
            response.put("object", "chat.completion");
            response.put("model", record.getModelName());
            response.set("choices", choices);
            response.set("usage", usage);
            return response;
        }
    }

    @Transactional
    public void delete(String projectId, String deploymentId) {
        Project project = project(projectId);
        ensureAccess(project);
        ModelDeployment record = record(projectId, deploymentId);
        String namespace = namespace(record.getTenantId());

        // Demo 集群可能已经不可达。K8s 清理失败时保留完整日志，但不能让失效记录永久阻塞调试重置。
        try {
            clientManager.deleteDeployment(
                    record.getActualClusterId(), namespace, record.getK8sDeploymentName());
        } catch (RuntimeException exception) {
            log.warn("删除推理 Deployment 失败，继续删除平台记录: deploymentId={}, clusterId={}, error={}",
                    deploymentId, record.getActualClusterId(), exception.getMessage());
        }
        try {
            clientManager.deleteService(
                    record.getActualClusterId(), namespace, record.getK8sServiceName());
        } catch (RuntimeException exception) {
            log.warn("删除推理 Service 失败，继续删除平台记录: deploymentId={}, clusterId={}, error={}",
                    deploymentId, record.getActualClusterId(), exception.getMessage());
        }

        if (!"FAILED".equals(record.getStatus())) {
            TenantSpecQuota quota = quotaService.requireAvailable(record.getTenantId(), record.getSpecId(), 0);
            quotaService.changeUsed(quota.getId(), -record.getReplicas());
        }
        releaseGpuDevices(parseAssignedGpuIds(record.getAssignedGpuIdsJson()));
        mapper.deleteById(deploymentId);
    }

    private Project project(String id) {
        Project project = projectMapper.findById(id).orElse(null);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在: " + id);
        }
        return project;
    }

    /**
     * 入池时创建的规格优先使用来源 Gpu 所在集群。
     *
     * <p>历史预置规格没有来源 Gpu 时保留按资源池和型号查找候选集群的兼容逻辑。
     * 共享规格的多个副本由 HAMi 在同一张物理 Gpu 上切分，不按副本数要求物理卡数量。
     */
    private String candidateCluster(ComputeSpec spec, int replicas, int gpuCountPerReplica) {
        GpuDevice sourceGpu = gpuMapper.findByComputeSpecId(spec.getId()).orElse(null);
        int requiredPhysicalGpu = "EXCLUSIVE".equals(spec.getSpecType())
                ? replicas * gpuCountPerReplica
                : 1;

        List<String> clusters = gpuMapper.findCandidateClusterIds(
                spec.getResourcePoolId(),
                spec.getGpuModel(),
                requiredPhysicalGpu);

        if (clusters.isEmpty()) {
            throw new BadRequestException("资源池中没有满足规格的在线 Gpu");
        }

        if (sourceGpu != null) {
            if (!"READY".equals(sourceGpu.getStatus())) {
                throw new BadRequestException("规格来源 Gpu 当前不可用");
            }
            if (clusters.contains(sourceGpu.getClusterId())) {
                return sourceGpu.getClusterId();
            }
        }

        return clusters.get(0);
    }
    private ModelDeployment record(String projectId, String id) {
        ModelDeployment value = mapper.findById(id).orElse(null);
        if (value == null) {
            throw new ResourceNotFoundException("部署不存在");
        }
        if (!projectId.equals(value.getProjectId())) {
            throw new ForbiddenException("部署不属于该项目");
        }
        return value;
    }
    private void ensureAccess(Project project) {
        if (!canAccess(project)) {
            throw new ForbiddenException("无权限访问该项目");
        }
    }

    private boolean canAccess(Project project) {
        UserPrincipal user = currentUser();
        if (user.getId().equals(project.getCreatedBy())) {
            return true;
        }
        for (String member : projectMapper.findMemberIds(project.getId())) {
            if (user.getId().equals(member)) {
                return true;
            }
        }
        for (org.springframework.security.core.GrantedAuthority authority : user.getAuthorities()) {
            if ("ROLE_PLATFORM_ADMIN".equals(authority.getAuthority())
                    || "ROLE_ORG_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
    private UserPrincipal currentUser() {
        Object value = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(value instanceof UserPrincipal)) {
            throw new ForbiddenException("未登录");
        }
        return (UserPrincipal) value;
    }
    private String namespace(String tenantId) {
        return "tenant-" + tenantId.substring(0, Math.min(8, tenantId.length()));
    }
    private String trim(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private String buildVllmArgs(
            String modelPath,
            Integer port,
            String modelName,
            int tensorParallelSize,
            double gpuMemoryUtilization,
            int maxModelLength) {
        List<String> args = new ArrayList<>();
        args.add("--model");
        args.add(modelPath);
        args.add("--served-model-name");
        args.add(modelName);
        args.add("--host");
        args.add("0.0.0.0");
        args.add("--port");
        args.add(String.valueOf(port));
        args.add("--gpu-memory-utilization");
        args.add(String.valueOf(gpuMemoryUtilization));
        args.add("--max-model-len");
        args.add(String.valueOf(maxModelLength));
        args.add("--tensor-parallel-size");
        args.add(String.valueOf(tensorParallelSize));
        return String.join(" ", args);
    }

    private String shortMessage(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

    private List<String> reserveGpuDevices(String clusterId, String gpuModel, int required) {
        if (required <= 0) {
            return List.of();
        }
        List<GpuDevice> devices = gpuMapper.findIdleByCluster(clusterId, gpuModel, required);
        if (devices.size() < required) {
            throw new BadRequestException("资源池中没有足够的空闲 GPU");
        }
        List<String> ids = new ArrayList<>();
        for (GpuDevice device : devices) {
            ids.add(device.getId());
        }
        int updated = gpuMapper.updateUsageStatusByIds(ids, "BUSY");
        if (updated != ids.size()) {
            releaseGpuDevices(ids);
            throw new BadRequestException("GPU 预留失败，请稍后重试");
        }
        return ids;
    }

    private void releaseGpuDevices(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        gpuMapper.updateUsageStatusByIds(ids, "IDLE");
    }

    private List<String> parseAssignedGpuIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("解析部署 GPU 预留列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String jsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private ModelDeploymentResponse response(ModelDeployment m, Integer ready) {
        return ModelDeploymentResponse.builder().id(m.getId()).projectId(m.getProjectId())
                .tenantId(m.getTenantId()).resourcePoolId(m.getResourcePoolId()).specId(m.getSpecId())
                .name(m.getName()).modelName(m.getModelName())
                .modelSource(m.getModelSource()).modelIdOrPath(m.getModelIdOrPath()).vllmImage(m.getVllmImage())
                .port(m.getPort()).replicas(m.getReplicas())
                .gpuCountPerReplica(m.getGpuCountPerReplica())
                .tensorParallelSize(m.getTensorParallelSize())
                .gpuMemoryUtilization(m.getGpuMemoryUtilization())
                .maxModelLength(m.getMaxModelLength())
                .k8sDeploymentName(m.getK8sDeploymentName()).k8sServiceName(m.getK8sServiceName())
                .status(m.getStatus()).serviceUrl(m.getServiceUrl()).readyReplicas(ready)
                .actualClusterId(m.getActualClusterId()).createdBy(m.getCreatedBy())
                .failureMessage(m.getFailureMessage())
                .createdAt(m.getCreatedAt()).updatedAt(m.getUpdatedAt()).build();
    }
}
