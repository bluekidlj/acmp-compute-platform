package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 整台 Kubernetes Node 加入资源池时使用的统一算力规格参数。
 *
 * <p>Node、GPU 品牌、型号、数量和资源池类型均由后端库存确定。
 */
@Data
public class NodeJoinSpecRequest {

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
