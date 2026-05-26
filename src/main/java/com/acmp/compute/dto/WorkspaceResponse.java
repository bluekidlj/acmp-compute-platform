package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 工作空间响应（含所属逻辑池信息 + 配额 + 用量）。
 */
@Data
@Builder
public class WorkspaceResponse {
    private String id;
    private String name;
    private String description;
    private String createdBy;
    private String status;
    /** 所属逻辑资源池 ID */
    private String resourcePoolId;
    /** 所属逻辑资源池名称 */
    private String resourcePoolName;
    /** 配额信息（含已用量） */
    private WorkspaceQuotaResponse quota;
    private Instant createdAt;
    private Instant updatedAt;
}
