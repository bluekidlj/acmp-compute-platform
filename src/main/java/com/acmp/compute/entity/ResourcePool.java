package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 平台固定资源池，只保留 EXCLUSIVE 和 SHARED。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourcePool {
    private String id;
    private String poolType;
    private String name;
    private String description;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
