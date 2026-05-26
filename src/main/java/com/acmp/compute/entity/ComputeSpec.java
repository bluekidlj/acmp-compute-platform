package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 算力规格 = 预设的 K8s ResourceRequirements 模板 + nodeSelector + tolerations。
 * 用户提交任务时只需引用规格名，平台自动翻译为完整 Pod Spec。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComputeSpec {
    private String id;
    private String name;
    private String displayName;
    private GpuBrand gpuBrand;
    // ── 预设 ResourceRequirements ──
    private Integer defaultGpuCount;
    private Integer defaultGpumemMb;
    private Integer defaultGpucores;
    private Integer defaultCpuCores;
    private Integer defaultMemoryGib;
    // ── 节点调度 ──
    /** JSON: {"pool":"nvidia-gpu"} */
    private String nodeSelector;
    /** JSON: [{"key":"nvidia.com/gpu","operator":"Exists","effect":"NoSchedule"}] */
    private String tolerations;
    /** ResourceQuota 中使用的资源键，默认 platform.io/{name} */
    private String resourceQuotaKey;
    // ── 元信息 ──
    private Integer memoryGb;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;

    /** 获取 ResourceQuota 键，若未设置则生成默认值 */
    public String getResourceQuotaKey() {
        if (resourceQuotaKey != null && !resourceQuotaKey.isEmpty()) return resourceQuotaKey;
        return "platform.io/" + name;
    }
}
