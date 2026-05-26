package com.acmp.compute.k8s;

import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.GpuBrand;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kubernetes 资源构建器：把"算力规格 + 副本数"翻译为 K8s 资源。
 *
 * 资源量流转的关键映射：
 *   规格 (ComputeSpec)                                  → Pod
 *   ├─ gpuBrand        NVIDIA / HYGON / HUAWEI_ASCEND   → limits[gpuResourceKey] = gpuPerReplica
 *   ├─ default*Counts  cpu/mem/gpumem/gpucores          → limits/requests
 *   ├─ nodeSelector    {"pool":"nvidia-gpu"}            → Pod.nodeSelector
 *   ├─ tolerations     [{key,operator,effect}]          → Pod.tolerations
 *   └─ resourceQuotaKey  platform.io/{spec}             → limits[platform.io/{spec}] = replicas
 *
 * 最后一项让 Namespace 的 ResourceQuota（按 platform.io/{spec} 上限）真实生效。
 */
@Slf4j
public class K8sResourceBuilder {

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

    /**
     * 根据 GPU 品牌返回额外的细粒度资源键（HAMi vGPU 等），可能不存在。
     */
    private static String gpuMemKey(GpuBrand brand) {
        if (brand == GpuBrand.NVIDIA) return "nvidia.com/gpumem";
        return null;
    }

    private static String gpuCoresKey(GpuBrand brand) {
        if (brand == GpuBrand.NVIDIA) return "nvidia.com/gpucores";
        return null;
    }

    // ─────────────────────────── vLLM Deployment + Service ───────────────────────────

    /**
     * 构建 vLLM Deployment + Service YAML（规范版本）。
     *
     * @param deploymentName Deployment 名
     * @param serviceName    Service 名
     * @param namespace      目标 namespace
     * @param image          vLLM 镜像
     * @param modelIdOrPath  容器内模型路径
     * @param spec           算力规格（决定资源键、cpu/mem 默认值）
     * @param replicas       副本数
     * @param hostModelPath  宿主机模型目录（hostPath 挂载，可选）
     * @param nodeSelector   nodeSelector JSON 字符串（可选，通常来自 spec）
     * @param tolerations    tolerations JSON 字符串（可选，通常来自 spec）
     */
    public static String buildVllmDeploymentAndService(
            String deploymentName,
            String serviceName,
            String namespace,
            String image,
            String modelIdOrPath,
            ComputeSpec spec,
            Integer replicas,
            String hostModelPath,
            String nodeSelector,
            String tolerations) {

        int gpuPerReplica = spec.getDefaultGpuCount() != null ? spec.getDefaultGpuCount() : 1;
        int cpuCores = spec.getDefaultCpuCores() != null ? spec.getDefaultCpuCores() : 4;
        int memoryGib = spec.getDefaultMemoryGib() != null ? spec.getDefaultMemoryGib() : 16;

        // 容器
        ContainerBuilder containerBuilder = new ContainerBuilder()
                .withName("vllm")
                .withImage(image)
                .withPorts(new ContainerPortBuilder().withContainerPort(8000).withName("http").build())
                .withEnv(
                    new EnvVarBuilder().withName("VLLM_MODEL")
                            .withValue(modelIdOrPath != null ? modelIdOrPath : "/models").build()
                )
                .withReadinessProbe(
                    new ProbeBuilder()
                        .withHttpGet(new HTTPGetActionBuilder()
                                .withPath("/health").withPort(new IntOrString(8000)).build())
                        .withInitialDelaySeconds(60)
                        .withPeriodSeconds(10)
                        .build()
                );

        Map<String, Quantity> limits = buildResourceMap(spec, replicas, gpuPerReplica, cpuCores, memoryGib);
        Map<String, Quantity> requests = new HashMap<>(limits);

        containerBuilder.withResources(new ResourceRequirementsBuilder()
                .withLimits(limits)
                .withRequests(requests)
                .build());

        if (hostModelPath != null && !hostModelPath.isEmpty()) {
            containerBuilder.withVolumeMounts(
                new VolumeMountBuilder().withName("model-data").withMountPath("/models").build()
            );
        }

        // Pod
        PodSpecBuilder podSpecBuilder = new PodSpecBuilder()
                .withContainers(containerBuilder.build());

        Map<String, String> finalNodeSelector = parseNodeSelector(nodeSelector);
        if (!finalNodeSelector.isEmpty()) {
            podSpecBuilder.withNodeSelector(finalNodeSelector);
        }

        List<Toleration> tols = parseTolerations(tolerations);
        for (Toleration t : tols) podSpecBuilder.addToTolerations(t);

        if (hostModelPath != null && !hostModelPath.isEmpty()) {
            podSpecBuilder.withVolumes(
                new VolumeBuilder()
                    .withName("model-data")
                    .withHostPath(new HostPathVolumeSourceBuilder()
                            .withPath(hostModelPath).withType("Directory").build())
                    .build()
            );
        }

        // Deployment
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(deploymentName)
                    .withNamespace(namespace)
                    .addToLabels("app", "vllm")
                    .addToLabels("spec", spec.getName())
                .endMetadata()
                .withNewSpec()
                    .withReplicas(replicas != null ? replicas : 1)
                    .withNewSelector()
                        .addToMatchLabels("app", "vllm")
                        .addToMatchLabels("deployment", deploymentName)
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels("app", "vllm")
                            .addToLabels("deployment", deploymentName)
                            .addToLabels("spec", spec.getName())
                        .endMetadata()
                        .withSpec(podSpecBuilder.build())
                    .endTemplate()
                .endSpec()
                .build();

        // Service
        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(serviceName)
                    .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                    .addToSelector("app", "vllm")
                    .addToSelector("deployment", deploymentName)
                    .withPorts(new ServicePortBuilder()
                            .withPort(8000).withTargetPort(new IntOrString(8000)).withName("http").build())
                    .withType("ClusterIP")
                .endSpec()
                .build();

        return "---\n" + Serialization.asYaml(deployment) + "\n---\n" + Serialization.asYaml(service);
    }

    // ─────────────────────────── VolcanoJob ───────────────────────────

    /**
     * 构建 VolcanoJob，注入规格驱动的资源键、nodeSelector、tolerations、platform.io 计量。
     */
    public static String buildVolcanoJob(
            String jobName,
            String namespace,
            String queueName,
            Integer replicas,
            String image,
            ComputeSpec spec,
            List<String> command,
            String nodeSelector,
            String tolerations) {

        int gpuPerPod = spec.getDefaultGpuCount() != null ? spec.getDefaultGpuCount() : 1;
        int cpuCores = spec.getDefaultCpuCores() != null ? spec.getDefaultCpuCores() : 4;
        int memoryGib = spec.getDefaultMemoryGib() != null ? spec.getDefaultMemoryGib() : 16;

        // 容器资源
        Map<String, Quantity> limits = buildResourceMap(spec, 1, gpuPerPod, cpuCores, memoryGib);
        // VolcanoJob 走 Unstructured；把 Quantity 转成字符串
        Map<String, String> limitMap = new HashMap<>();
        for (Map.Entry<String, Quantity> e : limits.entrySet()) {
            limitMap.put(e.getKey(), e.getValue().toString());
        }

        Map<String, Object> resources = new HashMap<>();
        resources.put("limits", limitMap);
        resources.put("requests", new HashMap<>(limitMap));

        Map<String, Object> container = new HashMap<>();
        container.put("name", "worker");
        container.put("image", image);
        container.put("resources", resources);
        if (command != null && !command.isEmpty()) container.put("command", command);

        Map<String, Object> podSpec = new HashMap<>();
        podSpec.put("restartPolicy", "Never");
        podSpec.put("containers", List.of(container));

        Map<String, String> ns = parseNodeSelector(nodeSelector);
        if (!ns.isEmpty()) podSpec.put("nodeSelector", ns);

        List<Toleration> tols = parseTolerations(tolerations);
        if (!tols.isEmpty()) {
            List<Map<String, Object>> tolList = new java.util.ArrayList<>();
            for (Toleration t : tols) {
                Map<String, Object> m = new HashMap<>();
                if (t.getKey() != null) m.put("key", t.getKey());
                if (t.getOperator() != null) m.put("operator", t.getOperator());
                if (t.getValue() != null) m.put("value", t.getValue());
                if (t.getEffect() != null) m.put("effect", t.getEffect());
                tolList.add(m);
            }
            podSpec.put("tolerations", tolList);
        }

        Map<String, Object> template = new HashMap<>();
        template.put("spec", podSpec);

        Map<String, Object> task = new HashMap<>();
        task.put("name", "worker");
        task.put("replicas", replicas);
        task.put("minAvailable", replicas);
        task.put("template", template);

        Map<String, Object> spec_ = new HashMap<>();
        spec_.put("minAvailable", replicas);
        spec_.put("schedulerName", "volcano");
        if (queueName != null) spec_.put("queue", queueName);
        spec_.put("tasks", List.of(task));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", jobName);
        metadata.put("namespace", namespace);

        Map<String, Object> jobMap = new HashMap<>();
        jobMap.put("apiVersion", "batch.volcano.sh/v1alpha1");
        jobMap.put("kind", "Job");
        jobMap.put("metadata", metadata);
        jobMap.put("spec", spec_);

        return Serialization.asYaml(jobMap);
    }

    // ─────────────────────────── Volcano Queue ───────────────────────────

    /**
     * 构建 Volcano Queue YAML（集群级资源）。
     * capability 中放规格驱动的设备资源键，而不是固定 nvidia.com/gpu。
     */
    public static String buildVolcanoQueue(
            String queueName,
            Map<String, String> capability) {

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", queueName);

        Map<String, Object> spec = new HashMap<>();
        spec.put("capability", capability);
        spec.put("weight", 1);
        spec.put("reclaimable", true);

        Map<String, Object> queueMap = new HashMap<>();
        queueMap.put("apiVersion", "scheduling.volcano.sh/v1beta1");
        queueMap.put("kind", "Queue");
        queueMap.put("metadata", metadata);
        queueMap.put("spec", spec);

        return Serialization.asYaml(queueMap);
    }

    // ─────────────────────────── helpers ───────────────────────────

    /**
     * 构建资源 map：含 gpu/cpu/mem + （可选）gpumem/gpucores + platform.io/{spec}=units。
     * units 用于 ResourceQuota 累计：单 Pod 占 1 单位，使整副本数等于 ResourceQuota.used。
     */
    private static Map<String, Quantity> buildResourceMap(
            ComputeSpec spec, Integer units, int gpuPerReplica, int cpuCores, int memoryGib) {

        Map<String, Quantity> map = new HashMap<>();

        // GPU 设备资源（按品牌）
        String gpuKey = gpuResourceKey(spec.getGpuBrand());
        map.put(gpuKey, Quantity.parse(String.valueOf(gpuPerReplica)));

        // HAMi vGPU 细粒度（仅 NVIDIA）
        if (spec.getDefaultGpumemMb() != null && spec.getDefaultGpumemMb() > 0) {
            String k = gpuMemKey(spec.getGpuBrand());
            if (k != null) map.put(k, Quantity.parse(String.valueOf(spec.getDefaultGpumemMb())));
        }
        if (spec.getDefaultGpucores() != null && spec.getDefaultGpucores() > 0) {
            String k = gpuCoresKey(spec.getGpuBrand());
            if (k != null) map.put(k, Quantity.parse(String.valueOf(spec.getDefaultGpucores())));
        }

        // CPU + Memory
        map.put("cpu", Quantity.parse(String.valueOf(cpuCores)));
        map.put("memory", Quantity.parse(memoryGib + "Gi"));

        // platform.io/{spec} = 1（每副本计 1，K8s ResourceQuota.used 自动累加为副本数）
        String rqKey = spec.getResourceQuotaKey(); // 默认 platform.io/{name}
        if (rqKey != null && !rqKey.isEmpty()) {
            map.put(rqKey, Quantity.parse("1"));
        }
        return map;
    }

    private static Map<String, String> parseNodeSelector(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = Serialization.jsonMapper().readValue(json, Map.class);
            result.putAll(m);
        } catch (Exception e) {
            log.warn("解析 nodeSelector JSON 失败: {}", json, e);
        }
        return result;
    }

    private static List<Toleration> parseTolerations(String json) {
        List<Toleration> result = new java.util.ArrayList<>();
        if (json == null || json.isEmpty()) return result;
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = Serialization.jsonMapper().readValue(json, List.class);
            for (Map<String, Object> t : list) {
                result.add(Serialization.jsonMapper().convertValue(t, Toleration.class));
            }
        } catch (Exception e) {
            log.warn("解析 tolerations JSON 失败: {}", json, e);
        }
        return result;
    }
}
