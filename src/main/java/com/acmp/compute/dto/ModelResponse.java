package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ModelResponse {
    private String id;
    private String name;
    private String displayName;
    private String description;
    private String modelFamily;
    private String modelSource;
    /** 存储后端类型，如 nfs */
    private String storageBackend;
    /** GPU 主机上的模型完整绝对目录，如 /data/acmp/models/Qwen2.5-3B-Instruct */
    private String storagePath;
    /** 文件大小 MB */
    private Long fileSizeMb;
    private Instant createdAt;
    private Instant updatedAt;
}
