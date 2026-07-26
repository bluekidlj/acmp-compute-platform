package com.acmp.compute.k8s;

import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.GpuBrand;
import io.kubernetes.client.openapi.models.V1Deployment;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class K8sResourceBuilderTest {

    @Test
    void deploymentUsesPoolAndComputeSpecNodeSelector() {
        ComputeSpec spec = ComputeSpec.builder()
                .id("spec-v100-exclusive")
                .name("NVIDIA V100 Exclusive")
                .gpuBrand(GpuBrand.NVIDIA)
                .specType("EXCLUSIVE")
                .resourcePoolId("pool-exclusive")
                .gpuModel("Tesla V100")
                .gpuCount(1)
                .cpuCores(4)
                .memoryGib(16)
                .status("active")
                .build();

        V1Deployment deployment = K8sResourceBuilder.buildVllmDeployment(
                "vllm-demo",
                "tenant-demo",
                "hashicorp/http-echo:1.0.0",
                "/models",
                5678,
                spec,
                1,
                null,
                Map.of(),
                "/http-echo",
                "-listen=:5678");

        Map<String, String> selector = deployment.getSpec()
                .getTemplate()
                .getSpec()
                .getNodeSelector();

        assertEquals("exclusive", selector.get(KubernetesSchedulingLabels.POOL_TYPE));
        assertEquals("nvidia-v100-exclusive",
                selector.get(KubernetesSchedulingLabels.COMPUTE_SPEC));
    }
}
