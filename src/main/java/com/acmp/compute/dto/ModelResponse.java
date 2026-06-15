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
    private String modelSource;
    /** 存储后端类型，如 nfs */
    private String storageBackend;
    /** 存储路径前缀（不含 name），如 /mnt/nfs/models */
    private String storagePath;
    /** 文件大小 MB */
    private Long fileSizeMb;
    private Instant createdAt;
    private Instant updatedAt;
}