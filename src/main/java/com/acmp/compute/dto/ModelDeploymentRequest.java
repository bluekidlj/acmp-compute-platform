package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.util.Map;

/**
 * 1.0 部署推理服务请求。
 *
 * 关键变化：
 *   - 入口路径：POST /api/v1/projects/{projectId}/deployments
 *   - 必填 specName（全局 ComputeSpec.name），平台按 spec.poolType 路由到对应项目池
 *   - replicas 1.0 限定为 1
 *   - gpuCount/cpuCores/memoryGib 等从规格默认读取（不需用户填）
 */
@Data
public class ModelDeploymentRequest {
    @NotBlank
    private String name;
    private String description;

    @NotBlank
    private String specName;

    /** 1.0 严格限制 1 */
    private Integer replicas = 1;

    @NotBlank
    private String image;

    /** 容器监听端口和 Service 端口，未填写时使用 vLLM 默认端口 8000。 */
    @Min(1)
    @Max(65535)
    private Integer port = 8000;

    private Map<String, String> envVars;
    private String command;
    private String args;

    /** 模型广场 ID（可选；填了则从 modelSource 解析） */
    private String modelId;
    /** with_weights / without_weights */
    private String modelSource;
    /** 容器内模型路径 */
    private String modelIdOrPath;
    private String modelName;
}
