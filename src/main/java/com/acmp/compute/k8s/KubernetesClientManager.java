package com.acmp.compute.k8s;

import com.acmp.compute.entity.PhysicalCluster;
import com.acmp.compute.mapper.PhysicalClusterMapper;
import com.acmp.compute.security.EncryptionService;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRule;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Kubernetes 客户端管理器：按物理集群 ID 缓存 KubernetesClient，
 * 使用平台高权限 ServiceAccount 代理操作 K8s，不 per-user 创建 ServiceAccount。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KubernetesClientManager {

    private final PhysicalClusterMapper physicalClusterMapper;
    private final EncryptionService encryptionService;

    /** 集群 ID -> 已创建的 KubernetesClient 缓存，避免重复解析 kubeconfig */
    private final Map<String, KubernetesClient> clientCache = new ConcurrentHashMap<>();

    /**
     * 获取指定物理集群的 Kubernetes 客户端。若缓存不存在则从库中取 kubeconfig 解密后创建并缓存。
     */
    public KubernetesClient getClient(String physicalClusterId) {
        return clientCache.computeIfAbsent(physicalClusterId, this::createAndCacheClient);
    }

    private KubernetesClient createAndCacheClient(String physicalClusterId) {
        PhysicalCluster cluster = physicalClusterMapper.findById(physicalClusterId)
                .orElseThrow(() -> new IllegalArgumentException("集群不存在: " + physicalClusterId));
        String decrypted = encryptionService.decrypt(cluster.getKubeconfigBase64Encrypted());
        Config config = Config.fromKubeconfig(decrypted);
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    /** 移除并关闭指定集群的客户端（例如集群删除或 kubeconfig 更新后调用） */
    public void closeClient(String physicalClusterId) {
        KubernetesClient client = clientCache.remove(physicalClusterId);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 K8s 客户端异常: {}", e.getMessage());
            }
        }
    }

    /** 在指定集群下创建 Namespace */
    public void createNamespace(String physicalClusterId, String namespaceName) {
        KubernetesClient client = getClient(physicalClusterId);
        Namespace ns = new NamespaceBuilder()
                .withNewMetadata().withName(namespaceName).endMetadata()
                .build();
        client.namespaces().resource(ns).serverSideApply();
        log.info("已创建 Namespace: {} @ cluster {}", namespaceName, physicalClusterId);
    }

    /** 删除 Namespace（级联删除内部所有资源） */
    public void deleteNamespace(String physicalClusterId, String namespaceName) {
        KubernetesClient client = getClient(physicalClusterId);
        client.namespaces().withName(namespaceName).delete();
        log.info("已删除 Namespace: {} @ cluster {}", namespaceName, physicalClusterId);
    }

    /**
     * 在指定 namespace 下创建 ResourceQuota（通用 GPU/CPU/Memory）。
     */
    public void createResourceQuota(String physicalClusterId, String namespace, String quotaName,
                                    int gpuSlots, int cpuCores, int memoryGiB, int maxPods) {
        KubernetesClient client = getClient(physicalClusterId);
        ResourceQuota quota = new ResourceQuotaBuilder()
                .withNewMetadata().withName(quotaName).withNamespace(namespace).endMetadata()
                .withNewSpec()
                .addToHard("nvidia.com/gpu", Quantity.parse(String.valueOf(gpuSlots)))
                .addToHard("cpu", Quantity.parse(String.valueOf(cpuCores)))
                .addToHard("memory", Quantity.parse(memoryGiB + "Gi"))
                .addToHard("pods", Quantity.parse(String.valueOf(maxPods)))
                .endSpec()
                .build();
        client.resourceQuotas().inNamespace(namespace).resource(quota).serverSideApply();
        log.info("已创建 ResourceQuota: {} @ namespace {} (gpu={}, cpu={}, mem={}Gi, pods={})", 
                quotaName, namespace, gpuSlots, cpuCores, memoryGiB, maxPods);
    }

    /**
     * 按规格创建 ResourceQuota：键为 platform.io/{specName}。
     * @param specLimits Map<specResourceKey, count> 如 {"platform.io/nvidia-rtx4090-24g": "1"}
     */
    public void createResourceQuotaBySpec(String physicalClusterId, String namespace, String quotaName,
                                           Map<String, String> specLimits, int maxPods) {
        KubernetesClient client = getClient(physicalClusterId);
        java.util.Map<String, Quantity> hard = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : specLimits.entrySet()) {
            hard.put(entry.getKey(), Quantity.parse(entry.getValue()));
        }
        hard.put("pods", Quantity.parse(String.valueOf(maxPods)));
        ResourceQuota quota = new ResourceQuotaBuilder()
                .withNewMetadata().withName(quotaName).withNamespace(namespace).endMetadata()
                .withNewSpec().withHard(hard).endSpec()
                .build();
        client.resourceQuotas().inNamespace(namespace).resource(quota).serverSideApply();
        log.info("已创建按规格 ResourceQuota: {} @ ns={}, specs={}", quotaName, namespace, specLimits);
    }

    /**
     * 使用渲染后的 YAML 在指定 namespace 创建或替换资源（如 Deployment、Service）。
     * 用于 vLLM Deployment、VolcanoJob 等。
     */
    public void applyYamlInNamespace(String physicalClusterId, String namespace, String yaml) {
        KubernetesClient client = getClient(physicalClusterId);
        try {
            client.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                    .inNamespace(namespace)
                    .serverSideApply();
        } catch (Exception e) {
            log.error("应用 YAML 失败: {}", e.getMessage());
            throw new RuntimeException("应用 YAML 失败", e);
        }
    }

    /**
     * 创建 vLLM Deployment 与 Service（通过 YAML 一次性 apply）。
     * 具体结构由 K8sTemplateEngine + vllm-deployment.yaml.ftl 生成。
     */
    public void createVllmDeploymentAndService(String physicalClusterId, String namespace, String yaml) {
        applyYamlInNamespace(physicalClusterId, namespace, yaml);
    }

    /** 删除指定 namespace 下的 Deployment */
    public void deleteDeployment(String physicalClusterId, String namespace, String deploymentName) {
        KubernetesClient client = getClient(physicalClusterId);
        client.apps().deployments().inNamespace(namespace).withName(deploymentName).delete();
        log.info("已删除 Deployment: {} @ {}", deploymentName, namespace);
    }

    /** 删除指定 namespace 下的 Service */
    public void deleteService(String physicalClusterId, String namespace, String serviceName) {
        KubernetesClient client = getClient(physicalClusterId);
        client.services().inNamespace(namespace).withName(serviceName).delete();
        log.info("已删除 Service: {} @ {}", serviceName, namespace);
    }

    /**
     * 获取 Deployment 的 ready 副本数，用于状态同步。
     */
    public Optional<Integer> getDeploymentReadyReplicas(String physicalClusterId, String namespace, String deploymentName) {
        KubernetesClient client = getClient(physicalClusterId);
        Deployment deployment = client.apps().deployments().inNamespace(namespace).withName(deploymentName).get();
        if (deployment == null || deployment.getStatus() == null) return Optional.of(0);
        Integer ready = deployment.getStatus().getReadyReplicas();
        return Optional.ofNullable(ready == null ? 0 : ready);
    }

    /**
     * 应用集群级别资源 YAML（如 Volcano Queue）。Queue 无 metadata.namespace，创建为集群级资源。
     */
    public void applyClusterScopedYaml(String physicalClusterId, String yaml) {
        KubernetesClient client = getClient(physicalClusterId);
        try {
            client.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))).serverSideApply();
        } catch (Exception e) {
            log.error("应用集群级 YAML 失败: {}", e.getMessage());
            throw new RuntimeException("应用集群级 YAML 失败", e);
        }
    }

    /** 验证 kubeconfig 是否可用：尝试创建客户端并执行一次 list namespaces。 */
    public boolean validateKubeconfig(String kubeconfigPlain) {
        try {
            Config config = Config.fromKubeconfig(kubeconfigPlain);
            try (KubernetesClient client = new KubernetesClientBuilder().withConfig(config).build()) {
                client.namespaces().list();
                return true;
            }
        } catch (Exception e) {
            log.warn("kubeconfig 校验失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 创建 ServiceAccount：用于工作空间自身的资源操作。
     */
    public void createServiceAccount(String physicalClusterId, String namespace, String saName) {
        KubernetesClient client = getClient(physicalClusterId);
        ServiceAccount sa = new ServiceAccountBuilder()
                .withNewMetadata().withName(saName).withNamespace(namespace).endMetadata()
                .build();
        client.serviceAccounts().inNamespace(namespace).resource(sa).serverSideApply();
        log.info("已创建 ServiceAccount: {} @ namespace {}", saName, namespace);
    }

    /**
     * 创建 Role：为部门用户定义权限范围，支持 Pod、Deployment、Service、VolcanoJob 等操作。
     * 该 Role 限制在特定 namespace 内。
     */
    public void createRole(String physicalClusterId, String namespace, String roleName) {
        KubernetesClient client = getClient(physicalClusterId);
        
        // Pod 相关权限
        PolicyRule podRule = new PolicyRuleBuilder()
                .withApiGroups("")
                .withResources("pods", "pods/log", "pods/exec")
                .withVerbs("get", "list", "watch", "create", "delete")
                .build();
        
        // Deployment 相关权限
        PolicyRule deploymentRule = new PolicyRuleBuilder()
                .withApiGroups("apps")
                .withResources("deployments", "statefulsets")
                .withVerbs("get", "list", "watch", "create", "update", "patch", "delete")
                .build();
        
        // Job 相关权限
        PolicyRule jobRule = new PolicyRuleBuilder()
                .withApiGroups("batch")
                .withResources("jobs")
                .withVerbs("get", "list", "watch", "create", "update", "patch", "delete")
                .build();
        
        // VolcanoJob 权限
        PolicyRule volcanoJobRule = new PolicyRuleBuilder()
                .withApiGroups("batch.volcano.sh")
                .withResources("volcanojobs")
                .withVerbs("get", "list", "watch", "create", "update", "patch", "delete")
                .build();
        
        // Service、ConfigMap、Secret 权限
        PolicyRule svcRule = new PolicyRuleBuilder()
                .withApiGroups("")
                .withResources("services", "configmaps", "secrets")
                .withVerbs("get", "list", "watch", "create", "update", "patch", "delete")
                .build();
        
        // Event 权限（用于监控）
        PolicyRule eventRule = new PolicyRuleBuilder()
                .withApiGroups("")
                .withResources("events")
                .withVerbs("get", "list", "watch")
                .build();
        
        // PersistentVolumeClaim 权限（用于数据持久化）
        PolicyRule pvcRule = new PolicyRuleBuilder()
                .withApiGroups("")
                .withResources("persistentvolumeclaims")
                .withVerbs("get", "list", "watch", "create", "delete")
                .build();
        
        Role role = new RoleBuilder()
                .withNewMetadata()
                .withName(roleName)
                .withNamespace(namespace)
                .endMetadata()
                .withRules(podRule, deploymentRule, jobRule, volcanoJobRule, svcRule, eventRule, pvcRule)
                .build();
        
        client.rbac().roles().inNamespace(namespace).resource(role).serverSideApply();
        log.info("已创建 Role: {} @ namespace {}", roleName, namespace);
    }

    /**
     * 创建 RoleBinding：将 Role 与 ServiceAccount 绑定，赋予 SA 对应的权限。
     */
    public void createRoleBinding(String physicalClusterId, String namespace, String rbName, 
                                  String roleName, String saName) {
        KubernetesClient client = getClient(physicalClusterId);
        
        // 使用 Builder 模式正确创建 RoleRef
        io.fabric8.kubernetes.api.model.rbac.RoleRef roleRef = new RoleRefBuilder()
                .withApiGroup("rbac.authorization.k8s.io")
                .withKind("Role")
                .withName(roleName)
                .build();
        
        // 使用 Builder 模式正确创建 Subject
        io.fabric8.kubernetes.api.model.rbac.Subject subject = new SubjectBuilder()
                .withKind("ServiceAccount")
                .withName(saName)
                .withNamespace(namespace)
                .build();
        
        RoleBinding rb = new RoleBindingBuilder()
                .withNewMetadata()
                .withName(rbName)
                .withNamespace(namespace)
                .endMetadata()
                .withRoleRef(roleRef)
                .withSubjects(subject)
                .build();
        
        client.rbac().roleBindings().inNamespace(namespace).resource(rb).serverSideApply();
        log.info("已创建 RoleBinding: {} @ namespace {}", rbName, namespace);
    }

    /**
     * 从 ServiceAccount 的 Secret 中提取 token 和 CA 数据，用于生成 kubeconfig。
     * 返回 Map<token, ca-crt>。
     */
    public Map<String, String> extractServiceAccountCredentials(String physicalClusterId, String namespace, String saName) {
        KubernetesClient client = getClient(physicalClusterId);
        ServiceAccount sa = client.serviceAccounts().inNamespace(namespace).withName(saName).get();
        
        if (sa == null || sa.getSecrets() == null || sa.getSecrets().isEmpty()) {
            throw new RuntimeException("ServiceAccount " + saName + " 无关联 Secret");
        }
        
        String secretName = sa.getSecrets().get(0).getName();
        var secret = client.secrets().inNamespace(namespace).withName(secretName).get();
        
        if (secret == null || secret.getData() == null) {
            throw new RuntimeException("无法获取 ServiceAccount Secret 数据");
        }
        
        String token = new String(Base64.getDecoder().decode(secret.getData().get("token")));
        String caCrt = secret.getData().get("ca.crt");
        
        return Map.of("token", token, "ca-crt", caCrt);
    }

    /**
     * 查询 K8s Namespace 下 ResourceQuota 的实际用量。
     * @return Map 包含 hard 上限和 used 已用量，key 为 "hardGpu"/"hardCpu"/"hardMem"/"usedGpu"/"usedCpu"/"usedMem"
     */
    public Map<String, Long> getResourceQuotaStatus(String physicalClusterId, String namespace, String quotaName) {
        KubernetesClient client = getClient(physicalClusterId);
        ResourceQuota rq = client.resourceQuotas().inNamespace(namespace).withName(quotaName).get();
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        if (rq != null && rq.getStatus() != null) {
            Map<String, Quantity> hard = rq.getStatus().getHard();
            Map<String, Quantity> used = rq.getStatus().getUsed();
            result.put("hardGpu", parseQuantity(hard != null ? hard.get("nvidia.com/gpu") : null));
            result.put("hardCpu", parseQuantity(hard != null ? hard.get("cpu") : null));
            result.put("hardMem", parseQuantity(hard != null ? hard.get("memory") : null) / (1024 * 1024 * 1024));
            result.put("usedGpu", parseQuantity(used != null ? used.get("nvidia.com/gpu") : null));
            result.put("usedCpu", parseQuantity(used != null ? used.get("cpu") : null));
            result.put("usedMem", parseQuantity(used != null ? used.get("memory") : null) / (1024 * 1024 * 1024));
        }
        return result;
    }

    private long parseQuantity(Quantity q) {
        if (q == null) return 0L;
        try {
            Object amount = q.getAmount();
            if (amount instanceof Number) return ((Number) amount).longValue();
        } catch (Exception ignored) {}
        try {
            return Quantity.getAmountInBytes(q).longValue();
        } catch (Exception ignored) {}
        return 0L;
    }
}
