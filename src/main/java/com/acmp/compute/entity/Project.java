package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 项目（Workspace 内的子租户）。
 *
 * 1.0 角色：配额真正拥有者。
 * 项目从 Workspace 拥有的三类池中获得按规格分配的节点数。
 * 部署推理服务时，平台按 spec 路由到项目拥有的对应类型池。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {
    private String id;
    private String workspaceId;
    private String name;
    private String description;
    private String createdBy;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
