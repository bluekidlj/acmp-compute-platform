package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Map;

/**
 * 模型部署请求（新版本，完全自定义每副本资源）。
 *
 * 用户直接指定算力资源（gpu/cpu/memory），平台自动匹配或创建 ComputeSpec。
 * 支持服务配置（镜像、环境变量、启动命令）。
 */
@Data
public class ModelDeploymentRequest {

    // ─────────────────────────── 基本信息 ───────────────────────────

    /** 部署名称（用于生成 K8s Deployment 名称） */
    @NotBlank
    private String name;

    /** 描述（可选） */
    private String description;

    // ─────────────────────────── 算力资源 ───────────────────────────

    /** 实例数目 */
    @NotNull @Min(1)
    private Integer replicas;

    /** 每副本 GPU 数（如 1） */
    @NotNull @Min(1)
    private Integer gpuCount;

    /** 每副本 CPU 核数（如 4） */
    @NotNull @Min(1)
    private Integer cpuCores;

    /** 每副本内存 GiB（如 16） */
    @NotNull @Min(1)
    private Integer memoryGib;

    /**
     * GPU 类型（用于匹配资源池规格），如 "nvidia-a100-80g-1/4"、"hygon-dcu-32g-1/8"。
     * 平台根据此字段查找匹配的 ComputeSpec，或自动创建。
     */
    @NotBlank
    private String gpuType;

    // ─────────────────────────── 服务配置 ───────────────────────────

    /** 镜像地址，如 vllm/vllm-openai:latest */
    @NotBlank
    private String image;

    /** 环境变量（可选） */
    private Map<String, String> envVars;

    /** 启动命令（可选），如 ["python", "serve.py"] */
    private String command;

    /** 启动参数（可选） */
    private String args;

    // ─────────────────────────── 模型配置 ───────────────────────────

    /** 模型来源：with_weights / without_weights */
    @NotBlank
    private String modelSource;

    /** 容器内模型路径（如 /models/qwen3），为空则默认 /models */
    private String modelIdOrPath;

    /** 模型名称（用于显示和记录） */
    private String modelName;
}