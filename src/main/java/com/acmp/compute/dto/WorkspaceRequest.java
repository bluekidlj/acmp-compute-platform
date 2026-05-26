package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 工作空间创建/修改请求。
 * 每个工作空间属于一个逻辑资源池，配额从该池分配。
 */
@Data
public class WorkspaceRequest {
    @NotBlank
    private String name;
    private String description;
    /** 所属逻辑资源池 ID（必填，N:1） */
    @NotBlank
    private String resourcePoolId;
    /** 初始配额（可选，创建时可不传，之后通过 quota API 设置） */
    @NotNull @Min(0)
    private Integer initialGpuSlots = 0;
    @NotNull @Min(0)
    private Integer initialCpuCores = 0;
    @NotNull @Min(0)
    private Integer initialMemoryGib = 0;
    private Integer initialMaxPods = 10;
    private Integer initialMaxHours = 100;
}
