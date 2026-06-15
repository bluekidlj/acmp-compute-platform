package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 注册物理集群请求。
 */
@Data
public class PhysicalClusterRegisterRequest {
    @NotBlank
    private String name;
    private String description;
    @NotBlank
    private String kubeconfigBase64;
    /** CSV 形式：NVIDIA,HYGON,HUAWEI_ASCEND */
    private String gpuTypes = "NVIDIA";
    private String location = "default";
    /** JSON：节点标签 */
    private String nodeLabels;
    /** JSON：污点 */
    private List<String> taints;
}
