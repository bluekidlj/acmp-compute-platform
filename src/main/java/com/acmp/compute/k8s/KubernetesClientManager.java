package com.acmp.compute.k8s;

import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.security.EncryptionService;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Service;
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
import java.util.Map;
import java.util.Optional;
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

    private final PhysicalClusterMapper clusterMapper;
    private final EncryptionService encryptionService;
    private final Map<String, ApiClient> clients = new ConcurrentHashMap<>();

    @Value("${acmp.kubernetes.connect-timeout-seconds:5}")
    private int connectTimeoutSeconds;

    @Value("${acmp.kubernetes.read-timeout-seconds:15}")
    private int readTimeoutSeconds;

    @Value("${acmp.kubernetes.call-timeout-seconds:30}")
    private int callTimeoutSeconds;

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
                        MediaType.get("application/json; charset=utf-8")))
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
