package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * Gpu 入池时同步创建算力规格的请求。
 *
 * <p>Gpu 型号、品牌、规格类型、资源池和 Gpu 数量均由后端根据目标 Gpu 和资源池确定，
 * 前端只提交管理员需要填写的规格信息。
 */
@Data
public class GpuJoinSpecRequest {

    @NotBlank
    private String name;

    private String displayName;

    private String gpuShare;

    @NotNull
    @Min(1)
    private Integer cpuCores;

    @NotNull
    @Min(1)
    private Integer memoryGib;

    private String description;
}
