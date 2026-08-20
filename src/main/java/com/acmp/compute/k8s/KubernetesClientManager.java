package com.acmp.compute.k8s;

import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.security.EncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.util.Yaml;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Kubernetes 客户端和核心资源操作入口。
 *
 * <p>每个集群只缓存一个官方 ApiClient。连接超时必须有限，避免内网地址或证书错误
 * 长时间占用接口线程。所有 404、409 只在对应操作可幂等时忽略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KubernetesClientManager {
    private static final String HAMI_NAMESPACE = "hami-system";
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");
    private static final MediaType MERGE_PATCH_MEDIA_TYPE =
            MediaType.get("application/merge-patch+json");

    private final PhysicalClusterMapper clusterMapper;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final Map<String, ApiClient> clients = new ConcurrentHashMap<>();

    @Value("${acmp.kubernetes.connect-timeout-seconds:5}")
    private int connectTimeoutSeconds;

    @Value("${acmp.kubernetes.read-timeout-seconds:15}")
    private int readTimeoutSeconds;

    @Value("${acmp.kubernetes.call-timeout-seconds:30}")
    private int callTimeoutSeconds;

    @Value("${acmp.hami.config-map:hami-device-plugin}")
    private String hamiConfigMapName;

    @Value("${acmp.hami.exclusive-node-config-enabled:false}")
    private boolean hamiExclusiveNodeConfigEnabled;

    /**
     * 获取集群客户端。缓存未命中时才解密并解析 kubeconfig。
     */
    public ApiClient getClient(String clusterId) {
        ApiClient cached = clients.get(clusterId);
        if (cached != null) {
            return cached;
        }

        synchronized (clients) {
            cached = clients.get(clusterId);
            if (cached == null) {
                cached = createClient(clusterId);
                clients.put(clusterId, cached);
            }
        }
        return cached;
    }

    /**
     * 关闭并移除指定集群客户端。
     */
    public void closeClient(String clusterId) {
        close(clients.remove(clusterId));
    }

    /**
     * 校验 kubeconfig 可解析、可连接，并且具备 Node 查询权限。
     */
    public boolean validateKubeconfig(String kubeconfig) {
        ApiClient client = null;
        try {
            client = buildClient(kubeconfig);
            new CoreV1Api(client).listNode().limit(1).execute();
            return true;
        } catch (Exception e) {
            log.warn("kubeconfig 校验失败: {}", e.getMessage());
            return false;
        } finally {
            close(client);
        }
    }

    /**
     * 创建 Namespace。已存在表示目标状态已经满足，不视为失败。
     */
    public void createNamespace(String clusterId, String namespace) {
        V1Namespace body = new V1Namespace().metadata(new V1ObjectMeta().name(namespace));
        log.info("K8S YAML 提交: clusterId={}, kind=Namespace, name={}\n{}",
                clusterId, namespace, yaml(body));
        try {
            new CoreV1Api(getClient(clusterId))
                    .createNamespace(body)
                    .execute();
            log.info("K8S API 成功: clusterId={}, kind=Namespace, name={}",
                    clusterId, namespace);
        } catch (ApiException e) {
            if (e.getCode() == 409) {
                log.info("K8S API 跳过: clusterId={}, kind=Namespace, name={}, reason=already-exists",
                        clusterId, namespace);
                return;
            }
            log.error("K8S API 失败: clusterId={}, kind=Namespace, name={}, code={}, body={}",
                    clusterId, namespace, e.getCode(), e.getResponseBody());
            throw operationError("创建 Namespace", e);
        }
    }

    /**
     * 给真实 Kubernetes Node 写入 ACMP 调度标签。
     */
    public void labelNode(String clusterId, String nodeName, Map<String, String> labels) {
        try {
            String patchJson = objectMapper.writeValueAsString(
                    Map.of("metadata", Map.of("labels", labels)));
            log.info("K8S Node 标签提交: clusterId={}, nodeName={}, patch={}",
                    clusterId, nodeName, patchJson);
            patchNodeMerge(clusterId, nodeName, patchJson, "更新 Node 调度标签");
            log.info("K8S Node 标签成功: clusterId={}, nodeName={}, labels={}",
                    clusterId, nodeName, labels);
        } catch (Exception exception) {
            log.error("K8S Node 标签失败: clusterId={}, nodeName={}, error={}",
                    clusterId, nodeName, exception.getMessage());
            throw new IllegalStateException("更新 Node 调度标签失败: " + exception.getMessage(),
                    exception);
        }
    }

    /**
     * 通过包含 nodeconfig 的 ConfigMap 判断 HAMi 是否已安装。
     */
    public boolean isHamiInstalled(String clusterId) {
        try {
            findHamiConfigMap(new CoreV1Api(getClient(clusterId)));
            return true;
        } catch (Exception e) {
            if (e instanceof ApiException && ((ApiException) e).getCode() == 404) {
                return false;
            }
            if (e instanceof ApiException) {
                throw operationError("检测 HAMi 安装状态", (ApiException) e);
            }
            throw new IllegalStateException("检测 HAMi 安装状态失败: " + e.getMessage(), e);
        }
    }

    /**
     * 修改单个节点的 HAMi 节点级配置，平台只替换目标节点并保留其他节点配置。
     */
    public void applyHamiNodeSharing(String clusterId, String nodeName, String gpuShare) {
        applyHamiNodeConfig(clusterId, nodeName, hamiSplitCount(gpuShare));
    }

    /** 独享池也显式覆盖节点配置，切分数 1 表示整卡。 */
    public void applyHamiNodeExclusive(String clusterId, String nodeName) {
        if (!hamiExclusiveNodeConfigEnabled) {
            log.info("跳过独享池 HAMi NodeConfig 写入: clusterId={}, nodeName={}, reason=config-disabled",
                    clusterId, nodeName);
            return;
        }
        applyHamiNodeConfig(clusterId, nodeName, 1);
    }

    /** 删除独享规格时使用同一个开关，避免无 HAMi 测试集群访问 ConfigMap。 */
    public void removeHamiNodeExclusive(String clusterId, String nodeName) {
        if (!hamiExclusiveNodeConfigEnabled) {
            log.info("跳过独享池 HAMi NodeConfig 清理: clusterId={}, nodeName={}, reason=config-disabled",
                    clusterId, nodeName);
            return;
        }
        removeHamiNodeSharing(clusterId, nodeName);
    }

    private void applyHamiNodeConfig(String clusterId, String nodeName, int splitCount) {
        double ratio = 1.0d / splitCount;
        try {
            CoreV1Api api = new CoreV1Api(getClient(clusterId));
            V1ConfigMap configMap = findHamiConfigMap(api);
            String configMapName = configMap.getMetadata().getName();
            Map<String, String> data = configMap.getData() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(configMap.getData());
            String key = hamiConfigKey(data);
            ObjectNode root = data.containsKey(key) && data.get(key) != null
                    ? (ObjectNode) objectMapper.readTree(data.get(key)) : objectMapper.createObjectNode();
            ArrayNode nodes = root.withArray("nodeconfig");
            removeHamiNodeConfig(nodes, nodeName);
            ObjectNode nodeConfig = nodes.addObject();
            nodeConfig.put("name", nodeName);
            nodeConfig.put("devicesplitcount", splitCount);
            nodeConfig.put("devicememoryscaling", ratio);
            nodeConfig.put("devicecorescaling", ratio);
            data.put(key, objectMapper.writeValueAsString(root));
            configMap.setData(data);
            log.info("HAMi 节点配置提交: clusterId={}, configMap={}, nodeName={}, splitCount={}, configKey={}\n{}",
                    clusterId, configMapName, nodeName, splitCount, key, data.get(key));
            api.replaceNamespacedConfigMap(configMapName, HAMI_NAMESPACE, configMap).execute();
            restartHamiDevicePlugin(api, nodeName);
        } catch (Exception e) {
            log.error("HAMi 节点配置失败: clusterId={}, nodeName={}, splitCount={}, error={}",
                    clusterId, nodeName, splitCount, e.getMessage(), e);
            throw new IllegalStateException("更新 HAMi 节点切分配置失败: " + e.getMessage(), e);
        }
    }

    /** 清理节点级 HAMi 配置，恢复由全局配置决定的整卡上报。 */
    public void removeHamiNodeSharing(String clusterId, String nodeName) {
        try {
            CoreV1Api api = new CoreV1Api(getClient(clusterId));
            V1ConfigMap configMap = findHamiConfigMap(api);
            String configMapName = configMap.getMetadata().getName();
            Map<String, String> data = configMap.getData() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(configMap.getData());
            String key = hamiConfigKey(data);
            if (data.containsKey(key)) {
                ObjectNode root = (ObjectNode) objectMapper.readTree(data.get(key));
                removeHamiNodeConfig(root.withArray("nodeconfig"), nodeName);
                data.put(key, objectMapper.writeValueAsString(root));
                configMap.setData(data);
                log.info("HAMi 节点配置清理提交: clusterId={}, nodeName={}\n{}", clusterId, nodeName, data.get(key));
                api.replaceNamespacedConfigMap(configMapName, HAMI_NAMESPACE, configMap).execute();
                restartHamiDevicePlugin(api, nodeName);
            }
        } catch (Exception e) {
            log.error("HAMi 节点配置清理失败: clusterId={}, nodeName={}, error={}",
                    clusterId, nodeName, e.getMessage(), e);
            throw new IllegalStateException("清理 HAMi 节点切分配置失败: " + e.getMessage(), e);
        }
    }

    private void removeHamiNodeConfig(ArrayNode nodes, String nodeName) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            if (nodeName.equals(nodes.get(i).path("name").asText())) {
                nodes.remove(i);
            }
        }
    }

    private V1ConfigMap findHamiConfigMap(CoreV1Api api) throws ApiException {
        try {
            V1ConfigMap configured = api.readNamespacedConfigMap(
                    hamiConfigMapName, HAMI_NAMESPACE).execute();
            if (hasHamiNodeConfig(configured)) {
                return configured;
            }
        } catch (ApiException exception) {
            if (exception.getCode() != 404) {
                throw exception;
            }
            log.info("HAMi 默认 ConfigMap 不存在，开始按 nodeconfig 自动识别: namespace={}, name={}",
                    HAMI_NAMESPACE, hamiConfigMapName);
        }

        V1ConfigMapList configMaps = api.listNamespacedConfigMap(HAMI_NAMESPACE).execute();
        for (V1ConfigMap configMap : configMaps.getItems()) {
            if (hasHamiNodeConfig(configMap)) {
                log.info("HAMi ConfigMap 自动识别成功: namespace={}, name={}",
                        HAMI_NAMESPACE, configMap.getMetadata().getName());
                return configMap;
            }
        }

        String availableNames = configMaps.getItems().stream()
                .filter(item -> item.getMetadata() != null)
                .map(item -> item.getMetadata().getName())
                .collect(Collectors.joining(", "));
        throw new IllegalStateException("命名空间 " + HAMI_NAMESPACE
                + " 中未找到包含 nodeconfig 的 HAMi ConfigMap，可用 ConfigMap: " + availableNames);
    }

    private boolean hasHamiNodeConfig(V1ConfigMap configMap) {
        if (configMap == null || configMap.getData() == null) {
            return false;
        }
        for (String value : configMap.getData().values()) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(value);
                if (root != null && root.isObject() && root.has("nodeconfig")) {
                    return true;
                }
            } catch (Exception ignored) {
                // ConfigMap 中可能同时包含非 JSON 数据，只识别 HAMi 配置项。
            }
        }
        return false;
    }

    private String hamiConfigKey(Map<String, String> data) {
        for (Map.Entry<String, String> entry : data.entrySet()) {
            try {
                JsonNode root = objectMapper.readTree(entry.getValue());
                if (root != null && root.isObject() && root.has("nodeconfig")) {
                    return entry.getKey();
                }
            } catch (Exception ignored) {
                // 继续检查下一个数据项。
            }
        }
        throw new IllegalStateException("HAMi ConfigMap 中缺少包含 nodeconfig 的 JSON 配置项");
    }

    private void restartHamiDevicePlugin(CoreV1Api api, String nodeName) throws ApiException {
        V1PodList pods = api.listNamespacedPod(HAMI_NAMESPACE)
                .fieldSelector("spec.nodeName=" + nodeName).execute();
        int deleted = 0;
        if (pods.getItems() != null) {
            for (var pod : pods.getItems()) {
                String podName = pod.getMetadata() == null ? null : pod.getMetadata().getName();
                String app = pod.getMetadata() == null || pod.getMetadata().getLabels() == null
                        ? null : pod.getMetadata().getLabels().get("app");
                if (podName != null && (podName.contains("hami-device-plugin")
                        || "hami-device-plugin".equals(app))) {
                    api.deleteNamespacedPod(podName, HAMI_NAMESPACE).execute();
                    deleted++;
                }
            }
        }
        log.info("HAMi device-plugin 刷新请求已提交: nodeName={}, deletedPods={}", nodeName, deleted);
    }

    private int hamiSplitCount(String gpuShare) {
        if (gpuShare == null || !gpuShare.startsWith("1/")) {
            throw new IllegalArgumentException("HAMi 切分比例必须为 1/2、1/4、1/8 或 1/10");
        }
        int count = Integer.parseInt(gpuShare.substring(2));
        if (count != 2 && count != 4 && count != 8 && count != 10) {
            throw new IllegalArgumentException("HAMi 切分比例必须为 1/2、1/4、1/8 或 1/10");
        }
        return count;
    }

    /**
     * 删除一个集群所有真实 Node 上由 ACMP 管理的调度标签。
     */
    public int removeAcmpNodeLabels(String clusterId) {
        try {
            V1NodeList nodeList = new CoreV1Api(getClient(clusterId)).listNode().execute();
            int updated = 0;
            for (V1Node node : nodeList.getItems()) {
                if (node.getMetadata() == null || node.getMetadata().getName() == null) {
                    continue;
                }
                if (removeAcmpNodeLabels(clusterId, node.getMetadata().getName())) {
                    updated++;
                }
            }
            return updated;
        } catch (Exception exception) {
            if (exception instanceof ApiException) {
                ApiException apiException = (ApiException) exception;
                log.error("K8S Node 标签清理失败: clusterId={}, code={}, body={}",
                        clusterId, apiException.getCode(), apiException.getResponseBody());
                throw operationError("清理 ACMP Node 标签", apiException);
            }
            throw new IllegalStateException("清理 ACMP Node 标签失败: " + exception.getMessage(),
                    exception);
        }
    }

    /**
     * 删除一个真实 Node 上由 ACMP 管理的调度标签。
     */
    public boolean removeAcmpNodeLabels(String clusterId, String nodeName) {
        try {
            Map<String, Object> removedLabels = new LinkedHashMap<>();
            removedLabels.put(KubernetesSchedulingLabels.POOL_TYPE, null);
            removedLabels.put(KubernetesSchedulingLabels.COMPUTE_SPEC, null);
            removedLabels.put(KubernetesSchedulingLabels.GPU_BRAND, null);
            removedLabels.put(KubernetesSchedulingLabels.GPU_MODEL, null);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("labels", removedLabels);
            Map<String, Object> patchBody = new LinkedHashMap<>();
            patchBody.put("metadata", metadata);
            String patchJson = objectMapper.writeValueAsString(patchBody);

            log.info("K8S Node 标签清理提交: clusterId={}, nodeName={}, patch={}",
                    clusterId, nodeName, patchJson);
            patchNodeMerge(clusterId, nodeName, patchJson, "清理 ACMP Node 标签");
            log.info("K8S Node 标签清理成功: clusterId={}, nodeName={}", clusterId, nodeName);
            return true;
        } catch (Exception exception) {
            throw new IllegalStateException("清理 ACMP Node 标签失败: " + exception.getMessage(),
                    exception);
        }
    }

    /**
     * 按顺序创建 Deployment 和 Service。
     *
     * <p>Deployment 成功但 Service 失败时保留 Deployment，便于在内网直接排查 Pod；
     * 上层会记录 FAILED，后续删除接口可清理两个资源。
     */
    public void createVllmDeploymentAndService(
            String clusterId,
            String namespace,
            V1Deployment deployment,
            V1Service service) {
        String deploymentName = deployment.getMetadata() == null
                ? "unknown" : deployment.getMetadata().getName();
        String serviceName = service.getMetadata() == null
                ? "unknown" : service.getMetadata().getName();

        log.info("K8S YAML 提交: clusterId={}, namespace={}, kind=Deployment, name={}\n{}",
                clusterId, namespace, deploymentName, yaml(deployment));
        try {
            new AppsV1Api(getClient(clusterId))
                    .createNamespacedDeployment(namespace, deployment)
                    .execute();
            log.info("K8S API 成功: clusterId={}, namespace={}, kind=Deployment, name={}",
                    clusterId, namespace, deploymentName);
        } catch (ApiException e) {
            log.error("K8S API 失败: clusterId={}, namespace={}, kind=Deployment, name={}, code={}, body={}",
                    clusterId, namespace, deploymentName, e.getCode(), e.getResponseBody());
            throw operationError("创建 vLLM Deployment", e);
        }

        log.info("K8S YAML 提交: clusterId={}, namespace={}, kind=Service, name={}\n{}",
                clusterId, namespace, serviceName, yaml(service));
        try {
            new CoreV1Api(getClient(clusterId))
                    .createNamespacedService(namespace, service)
                    .execute();
            log.info("K8S API 成功: clusterId={}, namespace={}, kind=Service, name={}",
                    clusterId, namespace, serviceName);
        } catch (ApiException e) {
            log.error("K8S API 失败: clusterId={}, namespace={}, kind=Service, name={}, code={}, body={}",
                    clusterId, namespace, serviceName, e.getCode(), e.getResponseBody());
            throw operationError("创建 vLLM Service", e);
        }
    }

    /**
     * 查询 Deployment 就绪副本数。
     */
    public Optional<Integer> getDeploymentReadyReplicas(
            String clusterId,
            String namespace,
            String deploymentName) {
        try {
            V1Deployment deployment = new AppsV1Api(getClient(clusterId))
                    .readNamespacedDeployment(deploymentName, namespace)
                    .execute();
            if (deployment.getStatus() == null) {
                return Optional.of(0);
            }
            return Optional.ofNullable(deployment.getStatus().getReadyReplicas());
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return Optional.empty();
            }
            throw operationError("查询 Deployment", e);
        }
    }

    /**
     * 删除 Deployment。资源不存在时视为删除成功。
     */
    public void deleteDeployment(String clusterId, String namespace, String deploymentName) {
        try {
            new AppsV1Api(getClient(clusterId))
                    .deleteNamespacedDeployment(deploymentName, namespace)
                    .execute();
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw operationError("删除 Deployment", e);
            }
        }
    }

    /**
     * 删除 Service。资源不存在时视为删除成功。
     */
    public void deleteService(String clusterId, String namespace, String serviceName) {
        try {
            new CoreV1Api(getClient(clusterId))
                    .deleteNamespacedService(serviceName, namespace)
                    .execute();
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw operationError("删除 Service", e);
            }
        }
    }

    /**
     * 读取当前 Kubernetes Service 暴露的真实 HTTP 端口。
     *
     * <p>不能使用数据库中的部署端口作为代理目标，因为 Service 可能已经调整过端口，
     * 历史部署记录也可能仍保留默认值 8000。
     */
    public int getServiceHttpPort(String clusterId, String namespace, String serviceName) {
        requireDnsLabel(namespace, "Namespace");
        requireDnsLabel(serviceName, "Service");
        try {
            V1Service service = new CoreV1Api(getClient(clusterId))
                    .readNamespacedService(serviceName, namespace)
                    .execute();
            if (service.getSpec() == null || service.getSpec().getPorts() == null
                    || service.getSpec().getPorts().isEmpty()) {
                throw new IllegalStateException("Service 未配置访问端口: " + serviceName);
            }
            for (V1ServicePort servicePort : service.getSpec().getPorts()) {
                if ("http".equals(servicePort.getName()) && servicePort.getPort() != null) {
                    return servicePort.getPort();
                }
            }
            V1ServicePort firstPort = service.getSpec().getPorts().get(0);
            if (firstPort.getPort() == null) {
                throw new IllegalStateException("Service 端口值为空: " + serviceName);
            }
            return firstPort.getPort();
        } catch (ApiException exception) {
            throw operationError("查询推理 Service 端口", exception);
        }
    }

    /**
     * 通过 Kubernetes API Server 的 Service Proxy 调用集群内服务。
     *
     * <p>ACMP 通常运行在集群外，Windows 或内网主机无法解析 svc.cluster.local。
     * 这里复用该集群 kubeconfig 的认证和 TLS 配置，经 API Server 转发请求，
     * 避免依赖主机 DNS、NodePort、Ingress 或临时 port-forward。
     */
    public String postServiceProxy(
            String clusterId,
            String namespace,
            String serviceName,
            int port,
            String path,
            String jsonBody) {
        requireDnsLabel(namespace, "Namespace");
        requireDnsLabel(serviceName, "Service");
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        String proxyUrl = getClient(clusterId).getBasePath()
                + "/api/v1/namespaces/" + namespace
                + "/services/http:" + serviceName + ":" + port
                + "/proxy/" + normalizedPath;

        okhttp3.OkHttpClient proxyClient = getClient(clusterId).getHttpClient()
                .newBuilder()
                .readTimeout(2, TimeUnit.MINUTES)
                .callTimeout(125, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(proxyUrl)
                .post(RequestBody.create(
                        jsonBody,
                        JSON_MEDIA_TYPE))
                .build();

        try (Response response = proxyClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException(
                        "推理服务返回 HTTP " + response.code() + ": " + body);
            }
            return body;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "通过 Kubernetes Service Proxy 调用失败: " + exception.getMessage(),
                    exception);
        }
    }

    /** 通过 Kubernetes API Server 的 Service Proxy 读取集群内 HTTP 服务。 */
    public String getServiceProxy(
            String clusterId,
            String namespace,
            String serviceName,
            int port,
            String path) {
        requireDnsLabel(namespace, "Namespace");
        requireDnsLabel(serviceName, "Service");
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        String proxyUrl = getClient(clusterId).getBasePath()
                + "/api/v1/namespaces/" + namespace
                + "/services/http:" + serviceName + ":" + port
                + "/proxy/" + normalizedPath;

        okhttp3.OkHttpClient proxyClient = getClient(clusterId).getHttpClient()
                .newBuilder()
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(proxyUrl)
                .get()
                .header("Accept", "text/plain")
                .build();

        try (Response response = proxyClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException(
                        "服务返回 HTTP " + response.code() + ": " + body);
            }
            return body;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "通过 Kubernetes Service Proxy 读取失败: " + exception.getMessage(),
                    exception);
        }
    }

    private ApiClient createClient(String clusterId) {
        PhysicalCluster cluster = clusterMapper.findById(clusterId).orElse(null);
        if (cluster == null) {
            throw new IllegalArgumentException("集群不存在: " + clusterId);
        }
        String kubeconfig = encryptionService.decrypt(cluster.getKubeconfigBase64Encrypted());
        return buildClient(kubeconfig);
    }

    private void requireDnsLabel(String value, String fieldName) {
        if (value == null || !value.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException(fieldName + " 名称不合法: " + value);
        }
    }

    private void patchNodeMerge(
            String clusterId,
            String nodeName,
            String patchJson,
            String operation) {
        ApiClient client = getClient(clusterId);
        String encodedNodeName = URLEncoder.encode(nodeName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = client.getBasePath() + "/api/v1/nodes/" + encodedNodeName;
        Request request = new Request.Builder()
                .url(url)
                .patch(RequestBody.create(patchJson, MERGE_PATCH_MEDIA_TYPE))
                .build();
        try (Response response = client.getHttpClient().newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException(operation + "失败("
                        + response.code() + "): " + body);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(operation + "失败: " + exception.getMessage(),
                    exception);
        }
    }

    private ApiClient buildClient(String kubeconfig) {
        try {
            KubeConfig config = KubeConfig.loadKubeConfig(new StringReader(kubeconfig));
            ApiClient client = ClientBuilder.kubeconfig(config).build();

            /*
             * 集群版本可能高于当前 Java Client，Node 等对象会出现客户端尚未定义的新字段。
             * 这些非核心字段不能阻塞 Node/GPU 查询，因此启用宽松 JSON 反序列化。
             */
            client.setLenientOnJson(true);

            okhttp3.OkHttpClient httpClient = client.getHttpClient()
                    .newBuilder()
                    .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                    .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                    .build();
            client.setHttpClient(httpClient);
            return client;
        } catch (Exception e) {
            throw new IllegalArgumentException("kubeconfig 解析失败: " + e.getMessage(), e);
        }
    }

    private void close(ApiClient client) {
        if (client == null || client.getHttpClient() == null) {
            return;
        }
        try {
            client.getHttpClient().dispatcher().cancelAll();
            client.getHttpClient().dispatcher().executorService().shutdown();
            client.getHttpClient().connectionPool().evictAll();
        } catch (Exception e) {
            log.warn("关闭 Kubernetes 客户端失败: {}", e.getMessage());
        }
    }

    private RuntimeException operationError(String operation, ApiException e) {
        String detail = e.getResponseBody();
        if (detail == null || detail.isBlank()) {
            detail = e.getMessage();
        }
        return new IllegalStateException(operation + "失败(" + e.getCode() + "): " + detail, e);
    }

    private String yaml(Object resource) {
        try {
            return Yaml.dump(resource);
        } catch (Exception exception) {
            // 日志序列化失败不能阻塞 Kubernetes 主流程。
            return "# YAML 日志序列化失败: " + exception.getMessage();
        }
    }
}
