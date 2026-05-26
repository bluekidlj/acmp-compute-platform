package com.acmp.compute.k8s;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kubernetes 资源 Builder 辅助类：使用 fabric8 Builder API 构建 K8s 资源。
 * 避免使用 YAML 模板，获得类型安全、编译时检查的好处。
 */
@Slf4j
public class K8sResourceBuilder {

    /**
     * 构建 vLLM Deployment + Service YAML（规范版本）。
     * 自动注入调度约束、资源限制、容忍度。
     * 
     * @param deploymentName Deployment 名称
     * @param serviceName Service 名称
     * @param namespace 目标 namespace
     * @param image vLLM 镜像地址
     * @param modelIdOrPath 模型路径
     * @param gpuPerReplica 每个副本的 GPU 数量
     * @param gpumemMb GPU 内存（MB）
     * @param gpucores GPU 核心数
     * @param replicas 副本数
     * @param hostModelPath 宿主机模型路径（用于 hostPath 挂载，可选）
     * @param nodeSelector 节点选择器（JSON 字符串或 null）
     * @param tolerations 污点容忍（JSON 字符串或 null）
     * @return 完整的 vLLM Deployment + Service YAML 字符串
     */
    public static String buildVllmDeploymentAndService(
            String deploymentName,
            String serviceName,
            String namespace,
            String image,
            String modelIdOrPath,
            Integer gpuPerReplica,
            Integer gpumemMb,
            Integer gpucores,
            Integer replicas,
            String hostModelPath,
            String nodeSelector,
            String tolerations) {
        
        // 构建 Container
        ContainerBuilder containerBuilder = new ContainerBuilder()
                .withName("vllm")
                .withImage(image)
                .withPorts(new ContainerPortBuilder().withContainerPort(8000).withName("http").build())
                .withEnv(
                    new EnvVarBuilder().withName("VLLM_MODEL").withValue(modelIdOrPath != null ? modelIdOrPath : "/models").build(),
                    new EnvVarBuilder().withName("NVIDIA_VISIBLE_DEVICES").withValue("all").build()
                )
                .withReadinessProbe(
                    new ProbeBuilder()
                        .withHttpGet(new HTTPGetActionBuilder()
                            .withPath("/health")
                            .withPort(new IntOrString(8000))
                            .build())
                        .withInitialDelaySeconds(60)
                        .withPeriodSeconds(10)
                        .build()
                );
        
        // 构建资源限制
        Map<String, Quantity> limits = new HashMap<>();
        limits.put("nvidia.com/gpu", Quantity.parse(String.valueOf(gpuPerReplica != null ? gpuPerReplica : 1)));
        if (gpumemMb != null && gpumemMb > 0) {
            limits.put("nvidia.com/gpumem", Quantity.parse(String.valueOf(gpumemMb)));
        }
        if (gpucores != null && gpucores > 0) {
            limits.put("nvidia.com/gpucores", Quantity.parse(String.valueOf(gpucores)));
        }
        
        Map<String, Quantity> requests = new HashMap<>();
        requests.put("nvidia.com/gpu", Quantity.parse(String.valueOf(gpuPerReplica != null ? gpuPerReplica : 1)));
        
        containerBuilder.withResources(new ResourceRequirementsBuilder()
                .withLimits(limits)
                .withRequests(requests)
                .build());
        
        // 如果指定了 hostPath，添加 volumeMount 和 volume
        if (hostModelPath != null && !hostModelPath.isEmpty()) {
            containerBuilder.withVolumeMounts(
                new VolumeMountBuilder()
                    .withName("model-data")
                    .withMountPath("/models")
                    .build()
            );
        }
        
        // 构建 Pod Template Spec
        PodSpecBuilder podSpecBuilder = new PodSpecBuilder()
                .withContainers(containerBuilder.build());
        
        // 合并 nodeSelector：物理池约束 + 默认 GPU 节点标签
        Map<String, String> finalNodeSelector = new HashMap<>();
        finalNodeSelector.put("gpu-node", "true");
        if (nodeSelector != null && !nodeSelector.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> poolNodeSelector = Serialization.jsonMapper().readValue(nodeSelector, Map.class);
                finalNodeSelector.putAll(poolNodeSelector);
            } catch (Exception e) {
                log.warn("Failed to parse nodeSelector JSON: {}", nodeSelector, e);
            }
        }
        podSpecBuilder.withNodeSelector(finalNodeSelector);
        
        // 添加污点容忍（来自物理池约束）
        if (tolerations != null && !tolerations.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tolerationList = Serialization.jsonMapper().readValue(tolerations, List.class);
                for (Map<String, Object> t : tolerationList) {
                    Toleration tol = Serialization.jsonMapper().convertValue(t, Toleration.class);
                    podSpecBuilder.addToTolerations(tol);
                }
            } catch (Exception e) {
                log.warn("Failed to parse tolerations JSON: {}", tolerations, e);
            }
        }
        
        // 添加 Volume（如果使用 hostPath）
        if (hostModelPath != null && !hostModelPath.isEmpty()) {
            podSpecBuilder.withVolumes(
                new VolumeBuilder()
                    .withName("model-data")
                    .withHostPath(new HostPathVolumeSourceBuilder()
                        .withPath(hostModelPath)
                        .withType("Directory")
                        .build())
                    .build()
            );
        }
        
        // 构建 Deployment
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(deploymentName)
                    .withNamespace(namespace)
                    .addToLabels("app", "vllm")
                    .addToLabels("model", "vllm")
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
                        .endMetadata()
                        .withSpec(podSpecBuilder.build())
                    .endTemplate()
                .endSpec()
                .build();
        
        // 构建 Service
        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(serviceName)
                    .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                    .addToSelector("app", "vllm")
                    .addToSelector("deployment", deploymentName)
                    .withPorts(new ServicePortBuilder()
                        .withPort(8000)
                        .withTargetPort(new IntOrString(8000))
                        .withName("http")
                        .build())
                    .withType("ClusterIP")
                .endSpec()
                .build();
        
        // 序列化为 YAML
        String deploymentYaml = Serialization.asYaml(deployment);
        String serviceYaml = Serialization.asYaml(service);
        
        log.debug("Generated vLLM Deployment YAML:\n{}", deploymentYaml);
        log.debug("Generated Service YAML:\n{}", serviceYaml);
        
        return "---\n" + deploymentYaml + "\n---\n" + serviceYaml;
    }

    /**
     * 构建 vLLM Deployment + Service YAML（向后兼容版本）。
     * @deprecated 使用新版本 buildVllmDeploymentAndService，支持 nodeSelector 和 tolerations
     */
    @Deprecated
    public static String buildVllmDeploymentAndService(
            String deploymentName,
            String serviceName,
            String namespace,
            String image,
            String modelIdOrPath,
            Integer gpuPerReplica,
            Integer gpumemMb,
            Integer gpucores,
            Integer replicas,
            String hostModelPath) {
        return buildVllmDeploymentAndService(
                deploymentName, serviceName, namespace, image, modelIdOrPath,
                gpuPerReplica, gpumemMb, gpucores, replicas, hostModelPath,
                null, null);
    }

    /**
     * 构建 VolcanoJob（用于分布式训练）。
     * 
     * @param jobName Job 名称
     * @param namespace 目标 namespace
     * @param queueName Volcano Queue 名称
     * @param replicas Pod 副本数
     * @param image 训练镜像
     * @param gpuPerPod 每个 Pod 的 GPU 数量
     * @param gpuMemPerPod GPU 内存（可选）
     * @param gpuCoresPerPod GPU 核心数（可选）
     * @param command 执行命令（可选）
     * @return VolcanoJob YAML 字符串
     */
    public static String buildVolcanoJob(
            String jobName,
            String namespace,
            String queueName,
            Integer replicas,
            String image,
            Integer gpuPerPod,
            Integer gpuMemPerPod,
            Integer gpuCoresPerPod,
            List<String> command) {
        
        // 注意：fabric8 可能未提供原生的 VolcanoJob CRD Builder
        // 在这种情况下，我们需要使用 Unstructured API 或回退到 YAML
        // 下面是使用 Unstructured 的方式
        
        Map<String, Object> jobMap = new HashMap<>();
        jobMap.put("apiVersion", "batch.volcano.sh/v1alpha1");
        jobMap.put("kind", "VolcanoJob");
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", jobName);
        metadata.put("namespace", namespace);
        jobMap.put("metadata", metadata);
        
        Map<String, Object> spec = new HashMap<>();
        spec.put("minAvailable", replicas);
        spec.put("schedulerName", "volcano");
        spec.put("queue", queueName);
        
        // 构建 tasks
        Map<String, Object> task = new HashMap<>();
        task.put("name", "worker");
        task.put("replicas", replicas);
        task.put("minAvailable", replicas);
        
        Map<String, Object> template = new HashMap<>();
        Map<String, Object> podSpec = new HashMap<>();
        podSpec.put("restartPolicy", "Never");
        
        // 强制添加 nodeSelector
        Map<String, String> nodeSelector = new HashMap<>();
        nodeSelector.put("gpu-node", "true");
        podSpec.put("nodeSelector", nodeSelector);
        
        // 构建 Container
        Map<String, Object> container = new HashMap<>();
        container.put("name", "worker");
        container.put("image", image);
        
        if (command != null && !command.isEmpty()) {
            container.put("command", command);
        }
        
        // 资源限制
        Map<String, Object> resources = new HashMap<>();
        Map<String, String> limitMap = new HashMap<>();
        limitMap.put("nvidia.com/gpu", String.valueOf(gpuPerPod != null ? gpuPerPod : 1));
        if (gpuMemPerPod != null && gpuMemPerPod > 0) {
            limitMap.put("nvidia.com/gpumem", String.valueOf(gpuMemPerPod));
        }
        if (gpuCoresPerPod != null && gpuCoresPerPod > 0) {
            limitMap.put("nvidia.com/gpucores", String.valueOf(gpuCoresPerPod));
        }
        resources.put("limits", limitMap);
        
        Map<String, String> requestMap = new HashMap<>();
        requestMap.put("nvidia.com/gpu", String.valueOf(gpuPerPod != null ? gpuPerPod : 1));
        resources.put("requests", requestMap);
        
        container.put("resources", resources);
        
        podSpec.put("containers", List.of(container));
        template.put("spec", podSpec);
        
        task.put("template", template);
        
        spec.put("tasks", List.of(task));
        jobMap.put("spec", spec);
        
        // 序列化为 YAML
        String yaml = Serialization.asYaml(jobMap);
        log.debug("Generated VolcanoJob YAML:\n{}", yaml);
        
        return yaml;
    }

    /**
     * 构建 Volcano Queue YAML（集群级资源，用于资源池创建）。
     * Volcano Queue 是自定义资源 (CRD)，用来配置训练任务的资源配额。
     * 
     * @param queueName Queue 名称（如：queue-dept-finance）
     * @param gpuSlots GPU 配额数量
     * @param cpuCores CPU 核心数
     * @param memoryGiB 内存 GB 数
     * @return Volcano Queue YAML 字符串
     */
    public static String buildVolcanoQueue(
            String queueName,
            String gpuSlots,
            String cpuCores,
            String memoryGiB) {
        
        try {
            // 使用 Unstructured API 构建 Volcano Queue（自定义 CRD）
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("name", queueName);
            
            Map<String, Object> capability = new HashMap<>();
            capability.put("nvidia.com/gpu", gpuSlots);
            capability.put("cpu", cpuCores);
            capability.put("memory", memoryGiB + "Gi");
            
            Map<String, Object> spec = new HashMap<>();
            spec.put("capability", capability);
            spec.put("weight", 1);
            spec.put("reclaimable", true);
            
            Map<String, Object> queueMap = new HashMap<>();
            queueMap.put("apiVersion", "scheduling.volcano.sh/v1beta1");
            queueMap.put("kind", "Queue");
            queueMap.put("metadata", metadata);
            queueMap.put("spec", spec);
            
            // 序列化为 YAML
            String yaml = Serialization.asYaml(queueMap);
            log.debug("✓ 构建 Volcano Queue YAML 成功: {}", queueName);
            log.debug("Generated Queue YAML:\n{}", yaml);
            
            return yaml;
            
        } catch (Exception e) {
            log.error("✗ 构建 Volcano Queue YAML 失败: {}", e.getMessage(), e);
            throw new RuntimeException("构建 Volcano Queue 失败: " + e.getMessage(), e);
        }
    }
}
