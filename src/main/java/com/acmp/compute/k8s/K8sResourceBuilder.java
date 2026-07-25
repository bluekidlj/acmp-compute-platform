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
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
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
            String hostModelPath,
            Map<String, String> envVars,
            String command,
            String args) {
        V1Container container = new V1Container()
                .name("vllm")
                .image(image)
                .addPortsItem(new V1ContainerPort().containerPort(port).name("http"))
                .resources(resources(spec));

        List<String> commandValues = split(command);
        if (!commandValues.isEmpty()) {
            container.setCommand(commandValues);
        }

        List<String> argumentValues = split(args);
        if (!argumentValues.isEmpty()) {
            container.setArgs(argumentValues);
        } else {
            container.setArgs(List.of(
                    "--model",
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
                                .metadata(new V1ObjectMeta().labels(labels))
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

    private static V1ResourceRequirements resources(ComputeSpec spec) {
        Map<String, Quantity> limits = new HashMap<>();
        limits.put(gpuResourceKey(spec.getGpuBrand()), Quantity.fromString(String.valueOf(spec.getGpuCount())));
        limits.put("cpu", Quantity.fromString(String.valueOf(spec.getCpuCores())));
        limits.put("memory", Quantity.fromString(spec.getMemoryGib() + "Gi"));

        if ("SHARED".equals(spec.getSpecType()) && spec.getGpuBrand() == GpuBrand.NVIDIA) {
            int percent = gpuSharePercent(spec.getGpuShare());
            limits.put(
                    "nvidia.com/gpumem-percentage",
                    Quantity.fromString(String.valueOf(percent)));
            limits.put(
                    "nvidia.com/gpucores",
                    Quantity.fromString(String.valueOf(percent)));
        }

        return new V1ResourceRequirements()
                .limits(limits)
                .requests(new HashMap<>(limits));
    }

    private static String gpuResourceKey(GpuBrand brand) {
        if (brand == GpuBrand.HYGON) {
            return "amd.com/dcu";
        }
        if (brand == GpuBrand.HUAWEI_ASCEND) {
            return "huawei.com/ascend910";
        }
        return "nvidia.com/gpu";
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
