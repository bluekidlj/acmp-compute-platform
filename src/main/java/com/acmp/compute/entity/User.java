package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 1.0 用户实体。表名 app_user（避免与 SQL 关键字 users 冲突）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String email;
    /** PLATFORM_ADMIN / ORG_ADMIN / INFERENCE_USER */
    private String role;
    private String organizationId;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
