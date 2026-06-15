package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 创建/更新模型的请求。
 * name 是模型唯一标识。
 * storageBackend 固定为 "nfs"，storagePath 是存储根路径。
 * 平台自动生成 storagePath/name 作为完整路径。
 */
@Data
public class ModelRequest {

    @NotBlank
    private String name;

    private String displayName;

    private String description;

    /** with_weights / without_weights */
    private String modelSource = "with_weights";

    /** 存储后端类型，当前固定填 "nfs"（未来可扩展其他后端） */
    private String storageBackend = "nfs";

    /** 存储根路径，如 /mnt/nfs/models（平台自动拼接 name） */
    @NotBlank
    private String storagePath;

    /** 文件大小 MB（可选） */
    private Long fileSizeMb;
}