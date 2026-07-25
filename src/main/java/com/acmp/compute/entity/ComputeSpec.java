package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 算力规格。
 *
 * <p>规格直接绑定一个固定资源池。specType 只使用 EXCLUSIVE 或 SHARED，
 * 避免同时维护 specType、poolType 两套含义相同的字段。
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
    private String specType;
    private String resourcePoolId;
    private String gpuModel;
    private Integer gpuCount;
    private Integer cpuCores;
    private Integer memoryGib;
    /** 共享规格比例，只允许 1/8、1/4、1/2；独占规格为 null。 */
    private String gpuShare;
    private String description;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
