package com.acmp.compute.k8s;

import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.GpuBrand;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1HostPathVolumeSource;
import io.kubernetes.client.openapi.models.V1HTTPGetAction;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1Probe;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将平台规格转换为 Kubernetes Deployment 和 Service。
 *
 * <p>这里仅处理核心提交逻辑。EXCLUSIVE 请求整卡资源，SHARED 在整卡资源键之外
 * 增加 HAMi 的显存百分比和算力百分比。HAMi 资源键必须与内网集群安装版本一致。
 */
public final class K8sResourceBuilder {

    private K8sResourceBuilder() {
    }

    /**
     * 构建 vLLM Deployment。
     */
    public static V1Deployment buildVllmDeployment(
            String deploymentName,
            String namespace,
            String image,
            String modelPath,
            Integer port,
            ComputeSpec spec,
            Integer replicas,
            Integer gpuCountPerReplica,
            String hostModelPath,
            Map<String, String> envVars,
            String command,
            String args) {
        V1Container container = new V1Container()
                .name("vllm")
                .image(image)
                .addPortsItem(new V1ContainerPort().containerPort(port).name("http"))
                .readinessProbe(new V1Probe()
                        .httpGet(new V1HTTPGetAction()
                                .path("/health")
                                .port(new IntOrString(port))
                                .scheme("HTTP"))
                        .initialDelaySeconds(5)
                        .periodSeconds(5)
                        .timeoutSeconds(3)
                        .failureThreshold(3))
                .resources(resources(spec, gpuCountPerReplica));

        List<String> commandValues = split(command);
        if (!commandValues.isEmpty()) {
            container.setCommand(commandValues);
        }

        List<String> argumentValues = split(args);
        if (!argumentValues.isEmpty()) {
            container.setArgs(argumentValues);
        } else {
            container.setArgs(List.of(
                    "serve",
                    modelPath,
                    "--host",
                    "0.0.0.0",
                    "--port",
                    String.valueOf(port)));
        }

        if (envVars != null) {
            for (Map.Entry<String, String> entry : envVars.entrySet()) {
                container.addEnvItem(new V1EnvVar()
                        .name(entry.getKey())
                        .value(entry.getValue()));
            }
        }

        V1PodSpec podSpec = new V1PodSpec()
                .restartPolicy("Always")
                .nodeSelector(schedulingNodeSelector(spec))
                .addContainersItem(container);

        if (hostModelPath != null && !hostModelPath.isBlank()) {
            container.addVolumeMountsItem(new V1VolumeMount()
                    .name("model")
                    .mountPath(modelPath)
                    .readOnly(true));
            podSpec.addVolumesItem(new V1Volume()
                    .name("model")
                    .hostPath(new V1HostPathVolumeSource()
                            .path(hostModelPath)
                            .type("Directory")));
        }

        Map<String, String> labels = new HashMap<>();
        labels.put("app", "vllm");
        labels.put("deployment", deploymentName);
        labels.put("spec", sanitizeLabel(spec.getName()));

        return new V1Deployment()
                .metadata(new V1ObjectMeta()
                        .name(deploymentName)
                        .namespace(namespace)
                        .labels(labels))
                .spec(new V1DeploymentSpec()
                        .replicas(replicas)
                        .selector(new V1LabelSelector().matchLabels(labels))
                        .template(new V1PodTemplateSpec()
                                .metadata(new V1ObjectMeta()
                                        .labels(labels)
                                        .annotations(podAnnotations(spec)))
                                .spec(podSpec)));
    }

    /**
     * 构建访问 vLLM 的集群内 Service。
     */
    public static V1Service buildVllmService(
            String serviceName,
            String namespace,
            String deploymentName,
            Integer port) {
        Map<String, String> selector = new HashMap<>();
        selector.put("app", "vllm");
        selector.put("deployment", deploymentName);

        return new V1Service()
                .metadata(new V1ObjectMeta()
                        .name(serviceName)
                        .namespace(namespace))
                .spec(new V1ServiceSpec()
                        .selector(selector)
                        .addPortsItem(new V1ServicePort()
                                .name("http")
                                .port(port)
                                .targetPort(new IntOrString(port)))
                        .type("ClusterIP"));
    }

    private static V1ResourceRequirements resources(ComputeSpec spec, Integer gpuCountPerReplica) {
        int gpuCount = gpuCountPerReplica == null ? 1 : gpuCountPerReplica;
        Map<String, Quantity> limits = new HashMap<>();
        limits.put(gpuResourceKey(spec), Quantity.fromString(String.valueOf(gpuCount)));
        limits.put("cpu", Quantity.fromString(String.valueOf(spec.getCpuCores())));
        limits.put("memory", Quantity.fromString(spec.getMemoryGib() + "Gi"));

        if ("SHARED".equals(spec.getSpecType())) {
            int percent = gpuSharePercent(spec.getGpuShare());
            if (spec.getGpuBrand() == GpuBrand.HYGON) {
                limits.put("hygon.com/dcucores", Quantity.fromString(String.valueOf(percent)));
                limits.put("hygon.com/dcumem", Quantity.fromString(String.valueOf(sharedMemoryMb(spec, percent))));
            } else if (spec.getGpuBrand() == GpuBrand.HUAWEI_ASCEND) {
                String resource = ascendResourceKey(spec.getGpuModel());
                limits.put(resource + "-core", Quantity.fromString(String.valueOf(percent)));
                limits.put(resource + "-memory", Quantity.fromString(String.valueOf(sharedMemoryMb(spec, percent))));
            } else {
                limits.put("nvidia.com/gpumem-percentage", Quantity.fromString(String.valueOf(percent)));
                limits.put("nvidia.com/gpucores", Quantity.fromString(String.valueOf(percent)));
            }
        }

        return new V1ResourceRequirements()
                .limits(limits)
                .requests(new HashMap<>(limits));
    }

    private static String gpuResourceKey(GpuBrand brand) {
        if (brand == GpuBrand.HYGON) {
            return "hygon.com/dcunum";
        }
        if (brand == GpuBrand.HUAWEI_ASCEND) {
            throw new IllegalArgumentException("华为昇腾资源键必须根据具体型号生成");
        }
        return "nvidia.com/gpu";
    }

    private static String gpuResourceKey(ComputeSpec spec) {
        if (spec.getGpuBrand() == GpuBrand.HUAWEI_ASCEND) {
            return ascendResourceKey(spec.getGpuModel());
        }
        return gpuResourceKey(spec.getGpuBrand());
    }

    private static int sharedMemoryMb(ComputeSpec spec, int percent) {
        if (spec.getGpuMemoryMb() == null || spec.getGpuMemoryMb() <= 0) {
            throw new IllegalArgumentException("共享规格缺少 Gpu 显存容量");
        }
        return Math.max(1, (int) (spec.getGpuMemoryMb() * percent / 100));
    }

    private static String ascendResourceKey(String model) {
        String value = model == null ? "" : model.toUpperCase().replace("-", "").replace("_", "");
        if (value.contains("910B4")) return "huawei.com/Ascend910B4";
        if (value.contains("910B3")) return "huawei.com/Ascend910B3";
        if (value.contains("910B2")) return "huawei.com/Ascend910B2";
        if (value.contains("910C")) return "huawei.com/Ascend910C";
        if (value.contains("910B")) return "huawei.com/Ascend910B";
        if (value.contains("910A")) return "huawei.com/Ascend910A";
        if (value.contains("310P")) return "huawei.com/Ascend310P";
        throw new IllegalArgumentException("无法根据型号生成华为昇腾资源键: " + model);
    }

    private static Map<String, String> podAnnotations(ComputeSpec spec) {
        Map<String, String> annotations = new HashMap<>();
        if ("SHARED".equals(spec.getSpecType()) && spec.getGpuBrand() == GpuBrand.HUAWEI_ASCEND) {
            annotations.put("huawei.com/vnpu-mode", "hami-core");
        }
        return annotations;
    }

    /**
     * 规格标签保证 Pod 只进入相同资源池和相同算力规格的 Node。
     */
    private static Map<String, String> schedulingNodeSelector(ComputeSpec spec) {
        Map<String, String> selector = new HashMap<>();
        selector.put(KubernetesSchedulingLabels.POOL_TYPE,
                KubernetesSchedulingLabels.value(spec.getSpecType()));
        selector.put(KubernetesSchedulingLabels.COMPUTE_SPEC,
                KubernetesSchedulingLabels.value(spec.getName()));
        return selector;
    }

    /**
     * 将平台固定共享比例转换为 HAMi 使用的整数百分比。
     */
    private static int gpuSharePercent(String gpuShare) {
        if ("1/8".equals(gpuShare)) {
            return 12;
        }
        if ("1/4".equals(gpuShare)) {
            return 25;
        }
        if ("1/2".equals(gpuShare)) {
            return 50;
        }
        throw new IllegalArgumentException("不支持的共享 GPU 比例: " + gpuShare);
    }

    private static List<String> split(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }

        String[] values = value.trim().split("\\s+");
        for (String item : values) {
            if (!item.isBlank()) {
                result.add(item);
            }
        }
        return result;
    }

    private static String sanitizeLabel(String name) {
        if (name == null) {
            return "unknown";
        }
        return name.replace('/', '-');
    }
}
