package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 注册物理集群请求。
 */
@Data
public class PhysicalClusterRegisterRequest {
    @NotBlank
    private String name;
    private String description;
    @NotBlank
    private String kubeconfig;
}
