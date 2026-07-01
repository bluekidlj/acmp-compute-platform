package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class PoolCardRequest {
    @NotBlank private String gpuBrand;
    @NotBlank private String gpuModel;
    @NotBlank private String nodeName;
    private String serialNo;
    @NotBlank private String specId;
}
