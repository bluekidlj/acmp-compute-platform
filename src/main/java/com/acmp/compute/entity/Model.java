package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 模型广场：管理模型文件的元信息。
 * storageBackend 支持多存储后端，当前实现 NFS。
 * 部署推理服务时可选模型，自动填充 modelSource、modelIdOrPath、hostModelPath。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Model {

    private String id;
    private String name;
    private String displayName;
    private String description;
    /** DEEPSEEK / QWEN / GLM / MINIMAX_M */
    private String modelFamily;
    /** with_weights / without_weights */
    private String modelSource;
    /** 存储后端类型，如 nfs / ceph / oss，当前固定 nfs */
    private String storageBackend;
    /** GPU 主机上的模型完整绝对目录，如 /data/acmp/models/Qwen2.5-3B-Instruct */
    private String storagePath;
    /** 文件大小 MB */
    private Long fileSizeMb;
    private Instant createdAt;
    private Instant updatedAt;
}
