package com.acmp.compute.k8s;

import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.GpuBrand;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.models.V1Affinity;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1HTTPGetAction;
import io.kubernetes.client.openapi.models.V1HostPathVolumeSource;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1NodeAffinity;
import io.kubernetes.client.openapi.models.V1NodeSelector;
import io.kubernetes.client.openapi.models.V1NodeSelectorRequirement;
import io.kubernetes.client.openapi.models.V1NodeSelectorTerm;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1Probe;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.openapi.models.V1Toleration;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kubernetes 资源构建器：把"算力规格 + 副本数"翻译为 K8s 资源（V1* POJO）。
 *
 * <p>底层 K8s 客户端：io.kubernetes:client-java（官方 Java client）。
 * POJO 类型在 io.kubernetes.client.openapi.models.V1*。
 *
 * <p>资源量映射：
 * <pre>
 * ComputeSpec
 * ├─ gpuBrand NVIDIA/HYGON/HUAWEI_ASCEND  → limits[gpuResourceKey] = gpuPerReplica
 * ├─ defaultGpuCount, defaultCpuCores, defaultMemoryGib
 * ├─ defaultGpumemMb, defaultGpucores     → 仅 NVIDIA，HAMi vGPU 切分
 * ├─ nodeSelector JSON                     → Pod.nodeSelector
 * ├─ tolerations JSON                      → Pod.tolerations
 * └─ resourceQuotaKey (platform.io/{spec}) → limits[platform.io/{spec}] = 1/replica
 * </pre>
 */
@Slf4j
public class K8sResourceBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─────────────────────── 资源键映射 ───────────────────────

    /**
     * 根据 GPU 品牌返回对应的 K8s 设备资源键。
     */
    public static String gpuResourceKey(GpuBrand brand) {
        if (brand == null) return "nvidia.com/gpu";
        switch (brand) {
            case NVIDIA:         return "nvidia.com/gpu";
            case HYGON:          return "amd.com/dcu";
            case HUAWEI_ASCEND:  return "huawei.com/ascend910";
            default:             return "nvidia.com/gpu";
        }
    }

    private static String gpuMemKey(GpuBrand brand) {
        if (brand == GpuBrand.NVIDIA) return "nvidia.com/gpumem";
        return null;
    }

    private static String gpuCoresKey(GpuBrand brand) {
        if (brand == GpuBrand.NVIDIA) return "nvidia.com/gpucores";
        return null;
    }

    // ─────────────────────── vLLM Deployment + Service ───────────────────────

    /**
     * 构造 vLLM Deployment（V1Deployment POJO）。
     * K8sResourceBuilder 不再返回 String YAML，而是直接返回官方 V1Deployment/V1Service，
     * 由 KubernetesClientManager.createVllmDeploymentAndService 走 client-java submit。
     */
    public static V1Deployment buildVllmDeployment(
            String deploymentName,
            String namespace,
            String image,
            String modelIdOrPath,
            ComputeSpec spec,
            Integer replicas,
            String hostModelPath,
            String nodeSelector,
            String tolerations,
            Map<String, String> envVars,
            String command,
            String args,
            List<String> preferredNodes) {

        int gpuPerReplica = spec.getDefaultGpuCount() != null ? spec.getDefaultGpuCount() : 1;
        int cpuCores = spec.getDefaultCpuCores() != null ? spec.getDefaultCpuCores() : 4;
        int memoryGib = spec.getDefaultMemoryGib() != null ? spec.getDefaultMemoryGib() : 16;

        // Container
        V1Container container = new V1Container()
                .name("vllm")
                .image(image)
                .addPortsItem(new V1ContainerPort().containerPort(8000).name("http"))
                .addEnvItem(new V1EnvVar().name("VLLM_MODEL")
                        .value(modelIdOrPath != null ? modelIdOrPath : "/models"))
                .readinessProbe(new V1Probe()
                        .httpGet(new V1HTTPGetAction().path("/health").port(new io.kubernetes.client.custom.IntOrString(8000)))
                        .initialDelaySeconds(60)
                        .periodSeconds(10));

        if (envVars != null) {
            for (Map.Entry<String, String> e : envVars.entrySet()) {
                container.addEnvItem(new V1EnvVar().name(e.getKey()).value(e.getValue()));
            }
        }
        if (command != null && !command.isEmpty()) {
            List<String> cmd = parseCommand(command);
            if (cmd != null) container.command(cmd);
        }
        if (args != null && !args.isEmpty()) {
            container.args(List.of(args.split("\\s+")));
        }

        Map<String, String> limits = buildResourceMap(spec, replicas, gpuPerReplica, cpuCores, memoryGib);
        Map<String, String> requests = new HashMap<>(limits);
        // V1ResourceRequirements.limits/requests 是 Map<String, Quantity>，K8s API 也接受 String
        container.resources(new V1ResourceRequirements()
                .putLimitsItem("cpu", io.kubernetes.client.custom.Quantity.fromString(limits.get("cpu")))
                .putLimitsItem("memory", io.kubernetes.client.custom.Quantity.fromString(limits.get("memory")))
                .putRequestsItem("cpu", io.kubernetes.client.custom.Quantity.fromString(limits.get("cpu")))
                .putRequestsItem("memory", io.kubernetes.client.custom.Quantity.fromString(limits.get("memory"))));
        for (Map.Entry<String, String> e : limits.entrySet()) {
            String k = e.getKey();
            if (k.equals("cpu") || k.equals("memory")) continue;
            io.kubernetes.client.custom.Quantity q = io.kubernetes.client.custom.Quantity.fromString(e.getValue());
            ((V1ResourceRequirements) container.getResources()).putLimitsItem(k, q);
            ((V1ResourceRequirements) container.getResources()).putRequestsItem(k, q);
        }

        if (hostModelPath != null && !hostModelPath.isEmpty()) {
            container.addVolumeMountsItem(new V1VolumeMount().name("model-data").mountPath("/models"));
        }

        // Pod
        V1PodSpec podSpec = new V1PodSpec().addContainersItem(container);

        Map<String, String> finalNodeSelector = parseNodeSelector(nodeSelector);
        if (!finalNodeSelector.isEmpty()) {
            podSpec.nodeSelector(finalNodeSelector);
        }
        for (V1Toleration t : parseTolerations(tolerations)) {
            podSpec.addTolerationsItem(t);
        }
        if (hostModelPath != null && !hostModelPath.isEmpty()) {
            podSpec.addVolumesItem(new V1Volume()
                    .name("model-data")
                    .hostPath(new V1HostPathVolumeSource().path(hostModelPath).type("Directory")));
        }

        if (preferredNodes != null && !preferredNodes.isEmpty()) {
            V1NodeSelectorRequirement req = new V1NodeSelectorRequirement()
                .key("kubernetes.io/hostname")
                .operator("In")
                .values(preferredNodes);
            V1NodeSelectorTerm term = new V1NodeSelectorTerm().addMatchExpressionsItem(req);
            V1NodeSelector nodeSelectorObj = new V1NodeSelector().addNodeSelectorTermsItem(term);
            V1NodeAffinity nodeAffinity = new V1NodeAffinity()
                .requiredDuringSchedulingIgnoredDuringExecution(nodeSelectorObj);
            podSpec.affinity(new V1Affinity().nodeAffinity(nodeAffinity));
        }

        String safeSpecName = sanitizeLabel(spec.getName());
        V1Deployment deployment = new V1Deployment()
                .metadata(new V1ObjectMeta()
                        .name(deploymentName)
                        .namespace(namespace)
                        .putLabelsItem("app", "vllm")
                        .putLabelsItem("spec", safeSpecName))
                .spec(new V1DeploymentSpec()
                        .replicas(replicas != null ? replicas : 1)
                        .selector(new V1LabelSelector()
                                .putMatchLabelsItem("app", "vllm")
                                .putMatchLabelsItem("deployment", deploymentName))
                        .template(new V1PodTemplateSpec()
                                .metadata(new V1ObjectMeta()
                                        .putLabelsItem("app", "vllm")
                                        .putLabelsItem("deployment", deploymentName)
                                        .putLabelsItem("spec", safeSpecName))
                                .spec(podSpec)));
        return deployment;
    }

    /**
     * 构造 vLLM Service（V1Service POJO）。
     */
    public static V1Service buildVllmService(String serviceName, String namespace, String deploymentName) {
        return new V1Service()
                .metadata(new V1ObjectMeta().name(serviceName).namespace(namespace))
                .spec(new V1ServiceSpec()
                        .putSelectorItem("app", "vllm")
                        .putSelectorItem("deployment", deploymentName)
                        .addPortsItem(new V1ServicePort()
                                .port(8000)
                                .targetPort(new io.kubernetes.client.custom.IntOrString(8000))
                                .name("http"))
                        .type("ClusterIP"));
    }

    // ─────────────────────── VolcanoJob / Queue（兼容 WS 资源，但 1.0 不使用） ───────────────────────

    /**
     * 构造 VolcanoJob YAML（集群级 Volcano 调度，1.0 未使用，保留兼容）。
     * @return YAML 字符串
     */
    public static String buildVolcanoJob(
            String jobName, String namespace, String queueName, Integer replicas, String image,
            ComputeSpec spec, List<String> command, String nodeSelector, String tolerations) {
        // 1.0 部署走 Deployment + vLLM，VolcanoJob 仅作占位。
        // 返回一个最小化 VolcanoJob YAML 字符串。
        StringBuilder yaml = new StringBuilder();
        yaml.append("apiVersion: batch.volcano.sh/v1alpha1\n");
        yaml.append("kind: Job\n");
        yaml.append("metadata:\n");
        yaml.append("  name: ").append(jobName).append("\n");
        yaml.append("  namespace: ").append(namespace).append("\n");
        yaml.append("spec:\n");
        yaml.append("  minAvailable: ").append(replicas).append("\n");
        yaml.append("  schedulerName: volcano\n");
        if (queueName != null) yaml.append("  queue: ").append(queueName).append("\n");
        yaml.append("  tasks:\n");
        yaml.append("  - name: worker\n");
        yaml.append("    replicas: ").append(replicas).append("\n");
        yaml.append("    minAvailable: ").append(replicas).append("\n");
        yaml.append("    template:\n");
        yaml.append("      spec:\n");
        yaml.append("        restartPolicy: Never\n");
        yaml.append("        containers:\n");
        yaml.append("        - name: worker\n");
        yaml.append("          image: ").append(image).append("\n");
        if (command != null && !command.isEmpty()) {
            yaml.append("          command:\n");
            for (String c : command) yaml.append("          - ").append(c).append("\n");
        }
        return yaml.toString();
    }

    /**
     * 构造 Volcano Queue YAML（集群级资源，1.0 不通过此函数提交——见 KubernetesClientManager.applyClusterScopedYaml）。
     * 保留以兼容已有调用点。
     */
    public static String buildVolcanoQueue(String queueName, Map<String, String> capability) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("apiVersion: scheduling.volcano.sh/v1beta1\n");
        yaml.append("kind: Queue\n");
        yaml.append("metadata:\n");
        yaml.append("  name: ").append(queueName).append("\n");
        yaml.append("spec:\n");
        yaml.append("  weight: 1\n");
        yaml.append("  reclaimable: true\n");
        if (capability != null && !capability.isEmpty()) {
            yaml.append("  capability:\n");
            for (Map.Entry<String, String> e : capability.entrySet()) {
                yaml.append("    ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
        }
        return yaml.toString();
    }

    // ─────────────────────── helpers ───────────────────────

    /**
     * 构建资源 map：含 gpu/cpu/mem + （可选）gpumem/gpucores + platform.io/{spec}。
     * values 是 String（K8s API 原生格式）。
     */
    private static Map<String, String> buildResourceMap(
            ComputeSpec spec, Integer units, int gpuPerReplica, int cpuCores, int memoryGib) {
        Map<String, String> map = new HashMap<>();
        String gpuKey = gpuResourceKey(spec.getGpuBrand());
        map.put(gpuKey, String.valueOf(gpuPerReplica));

        if (spec.getDefaultGpumemMb() != null && spec.getDefaultGpumemMb() > 0) {
            String k = gpuMemKey(spec.getGpuBrand());
            if (k != null) map.put(k, String.valueOf(spec.getDefaultGpumemMb()));
        }
        if (spec.getDefaultGpucores() != null && spec.getDefaultGpucores() > 0) {
            String k = gpuCoresKey(spec.getGpuBrand());
            if (k != null) map.put(k, String.valueOf(spec.getDefaultGpucores()));
        }
        map.put("cpu", String.valueOf(cpuCores));
        map.put("memory", memoryGib + "Gi");

        String rqKey = spec.getResourceQuotaKey();
        if (rqKey != null && !rqKey.isEmpty()) {
            map.put(rqKey, String.valueOf(units != null ? units : 1));
        }
        return map;
    }

    private static List<String> parseCommand(String command) {
        if (command == null || command.isEmpty()) return null;
        if (command.startsWith("[")) {
            try {
                return MAPPER.readValue(command, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("解析 command JSON 失败: {}", command, e);
            }
        }
        if (command.contains(",")) return Arrays.asList(command.split(","));
        return Arrays.asList(command.split("\\s+"));
    }

    /** K8s label value 不允许 '/', 把 name 里的 '/' 替换为 '-' */
    public static String sanitizeLabel(String name) {
        if (name == null) return null;
        return name.replace('/', '-');
    }

    private static Map<String, String> parseNodeSelector(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            Map<String, String> m = MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
            result.putAll(m);
        } catch (Exception e) {
            log.warn("解析 nodeSelector JSON 失败: {}", json, e);
        }
        return result;
    }

    private static List<V1Toleration> parseTolerations(String json) {
        List<V1Toleration> result = new ArrayList<>();
        if (json == null || json.isEmpty()) return result;
        try {
            List<Map<String, Object>> list = MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> t : list) {
                V1Toleration tol = new V1Toleration();
                if (t.get("key") != null) tol.key(String.valueOf(t.get("key")));
                if (t.get("operator") != null) tol.operator(String.valueOf(t.get("operator")));
                if (t.get("value") != null) tol.value(String.valueOf(t.get("value")));
                if (t.get("effect") != null) tol.effect(String.valueOf(t.get("effect")));
                result.add(tol);
            }
        } catch (Exception e) {
            log.warn("解析 tolerations JSON 失败: {}", json, e);
        }
        return result;
    }
}
