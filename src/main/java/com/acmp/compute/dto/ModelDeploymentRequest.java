package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import java.util.Map;

/**
 * 1.0 部署推理服务请求。
 *
 * 关键变化：
 *   - 入口路径：POST /api/v1/projects/{projectId}/deployments
 *   - 必填 specName（全局 ComputeSpec.name），平台按 spec.poolType 路由到对应项目池
 *   - replicas 表示副本数，每个副本占用一个租户规格节点
 *   - gpuCount/cpuCores/memoryGib 等从规格默认读取（不需用户填）
 */
@Data
public class ModelDeploymentRequest {
    @NotBlank
    private String name;
    private String description;

    @NotBlank
    private String specName;

    /** 副本数，默认 1；实际可用上限由租户剩余规格配额校验。 */
    @Min(1)
    private Integer replicas = 1;

    /** 单实例张量并行度，默认等于算力规格的 GPU 数量。 */
    @Min(1)
    private Integer tensorParallelSize;

    /** vLLM 安全默认值。 */
    @DecimalMin(value = "0.0", inclusive = false)
    @DecimalMax("1.0")
    private Double gpuMemoryUtilization = 0.8D;

    /** vLLM 安全默认值。 */
    @Min(1)
    private Integer maxModelLength = 8192;

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
