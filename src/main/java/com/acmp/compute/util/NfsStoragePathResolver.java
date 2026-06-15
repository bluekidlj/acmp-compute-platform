package com.acmp.compute.util;

/**
 * 存储路径解析工具类。
 * 根据存储后端和存储根路径，计算模型的完整访问路径。
 *
 * 当前只实现 NFS 后端，未来可扩展其他后端。
 */
public final class NfsStoragePathResolver {

    private NfsStoragePathResolver() {
    }

    /**
     * 计算模型在 NFS 上的完整路径。
     * 完整路径 = storagePath + "/" + modelName
     *
     * @param storagePath 存储根路径，如 /mnt/nfs/models
     * @param modelName   模型唯一标识，如 qwen3-7b
     * @return 完整路径，如 /mnt/nfs/models/qwen3-7b
     */
    public static String resolve(String storagePath, String modelName) {
        if (storagePath == null || storagePath.isEmpty()) {
            throw new IllegalArgumentException("storagePath 不能为空");
        }
        if (modelName == null || modelName.isEmpty()) {
            throw new IllegalArgumentException("modelName 不能为空");
        }
        String trimmed = storagePath.replaceAll("/+$", "");
        return trimmed + "/" + modelName;
    }

    /**
     * 判断存储后端是否为 NFS。
     */
    public static boolean isNfs(String storageBackend) {
        return "nfs".equalsIgnoreCase(storageBackend);
    }
}