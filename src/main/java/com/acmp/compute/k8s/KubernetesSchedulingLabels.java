package com.acmp.compute.k8s;

import java.util.Locale;

/**
 * ACMP 写入 Kubernetes Node 和 Pod 的调度标签。
 */
public final class KubernetesSchedulingLabels {
    public static final String POOL_TYPE = "acmp.ai/pool-type";
    public static final String COMPUTE_SPEC = "acmp.ai/compute-spec";
    public static final String GPU_BRAND = "acmp.ai/gpu-brand";
    public static final String GPU_MODEL = "acmp.ai/gpu-model";

    private KubernetesSchedulingLabels() {
    }

    /**
     * 将平台名称转换为 Kubernetes 合法且可读的标签值。
     */
    public static String value(String source) {
        if (source == null || source.isBlank()) {
            return "unknown";
        }
        String normalized = source.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]+", "-")
                .replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
        if (normalized.isBlank()) {
            return "unknown";
        }
        return normalized.length() <= 63 ? normalized : normalized.substring(0, 63);
    }
}
