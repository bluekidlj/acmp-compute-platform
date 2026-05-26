package com.acmp.compute.dto;

import com.acmp.compute.entity.GpuBrand;
import lombok.Data;
import javax.validation.constraints.NotBlank;

/** 
 * 注册物理集群请求：管理员通过此接口注册新的 K8s 集群。
 * name + description + kubeconfig + GPU 硬件类型
 */
@Data
public class PhysicalClusterRegisterRequest {
    @NotBlank
    private String name;
    private String description;
    /** 
     * Base64 编码的完整 kubeconfig 内容。
     * 必须包含完整的 clusters、contexts、users 配置。
     */
    @NotBlank
    private String kubeconfigBase64;
    /** GPU 硬件类型，逗号分隔，如 NVIDIA,HYGON */
    private String gpuTypes = GpuBrand.NVIDIA.name();
    /** 地域/机房，如 beijing, shanghai */
    private String location = "default";
    private String nodeLabels;
    private String taints;
}
