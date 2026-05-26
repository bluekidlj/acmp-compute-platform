package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * vLLM 模型服务部署请求（规范版本）。
 * 
 * 用户仅指定：规格名称、副本数。平台自动推导 K8s 资源、调度约束、配额限制。
 * 模型与权重从本地获取，modelIdOrPath 为容器内路径（如 /models/qwen3）。
 */
@Data
public class VllmDeployRequest {
    /** 部署名称（用于生成 K8s Deployment 名称） */
    @NotBlank
    private String name;
    
    /** 指定的计算规格名称，如 nvidia-4090-24g、huawei-ascend */
    @NotBlank
    private String specName;
    
    /** 副本数 */
    @NotNull @Min(1)
    private Integer replicas;
    
    /** 模型名称（用于显示和记录） */
    private String modelName;
    
    /** with_weights / without_weights */
    @NotBlank
    private String modelSource;
    
    /** 本地路径：容器内模型路径（如 /models/qwen3），若为空则默认 /models */
    private String modelIdOrPath;
    
    /** vLLM 镜像地址，若为空则使用规格中的默认镜像 */
    private String vllmImage;
    
    /** 宿主机权重目录（用于 hostPath 挂载，可选），如 /data/models/Qwen3 */
    private String hostModelPath;
}
