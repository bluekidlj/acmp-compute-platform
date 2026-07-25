package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 租户下的项目，是模型部署的业务归属边界。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {
    private String id;
    private String tenantId;
    private String name;
    private String description;
    private String createdBy;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
