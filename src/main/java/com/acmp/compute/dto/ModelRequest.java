package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 创建/更新模型的请求。
 * name 是模型唯一标识。
 * storageBackend 当前使用 "nfs" 表示 NFS 或本地挂载。
 * storagePath 是模型在 GPU 主机上的完整绝对目录，不再自动拼接模型名。
 */
@Data
public class ModelRequest {

    @NotBlank
    private String name;

    private String displayName;

    private String description;

    /** 固定模型系列：DEEPSEEK / QWEN / GLM / MINIMAX_M */
    @NotBlank
    private String modelFamily;

    /** with_weights / without_weights */
    private String modelSource = "with_weights";

    /** 存储后端类型，当前固定填 "nfs"（未来可扩展其他后端） */
    private String storageBackend = "nfs";

    /** GPU 主机模型绝对目录，如 /data/acmp/models/Qwen2.5-3B-Instruct */
    @NotBlank
    private String storagePath;

    /** 文件大小 MB（可选） */
    private Long fileSizeMb;
}
