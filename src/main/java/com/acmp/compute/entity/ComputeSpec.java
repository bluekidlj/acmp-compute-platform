package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 1.0 算力规格（全局规格库）。
 *
 * <p>三个 specType 决定对应的池类型（poolType）：
 * <ul>
 *   <li>PHYSICAL  → EXCLUSIVE   （整卡独占）</li>
 *   <li>VIRTUAL   → SHARED      （HAMi vGPU 切分）</li>
 *   <li>OVERSELL  → OVERSELL    （超分占位，1.0 暂未实现真实 K8s 提交）</li>
 * </ul>
 *
 * <p>由 platform 自动预置 7 条标准规格（见 schema-h2.sql 末尾）。
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
    /** PHYSICAL / VIRTUAL / OVERSELL */
    private String specType;
    /** EXCLUSIVE / SHARED / OVERSELL（冗余字段，从 specType 派生） */
    private String poolType;
    private Integer defaultGpuCount;
    private Integer defaultGpumemMb;
    private Integer defaultGpucores;
    private Integer defaultCpuCores;
    private Integer defaultMemoryGib;
    /** JSON: {"pool":"..."} */
    private String nodeSelector;
    /** JSON: [{key,operator,effect}] */
    private String tolerations;
    /** ResourceQuota 中使用的资源键，默认 platform.io/{name} */
    private String resourceQuotaKey;
    private Integer memoryGb;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;

    /** 派生：若 resourceQuotaKey 未设置，则默认为 platform.io/{name} */
    public String getResourceQuotaKey() {
        if (resourceQuotaKey != null && !resourceQuotaKey.isEmpty()) return resourceQuotaKey;
        return "platform.io/" + name;
    }
}
