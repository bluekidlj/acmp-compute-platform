package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 项目从池获得的配额（按 pool × spec 维度）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectResourceQuota {
    private String id;
    private String projectId;
    private String resourcePoolId;
    private String specId;
    /** 管理员分配给该项目的该规格节点数 */
    private Integer totalNodes;
    /** 已用节点数（部署时扣减） */
    private Integer usedNodes;
    private Instant createdAt;
    private Instant updatedAt;
}
