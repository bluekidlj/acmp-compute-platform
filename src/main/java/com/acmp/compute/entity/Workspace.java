package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 工作空间：资源的二次分配（按项目/团队/用户）。
 * 每个工作空间属于一个逻辑资源池（N:1），配额从该池分配。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {
    private String id;
    /** 所属逻辑资源池 ID（N:1） */
    private String resourcePoolId;
    private String name;
    private String description;
    private String createdBy;
    /** active / archived */
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
