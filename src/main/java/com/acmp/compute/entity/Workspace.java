package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 1.0 工作空间 = 租户
 *
 * <ul>
 *   <li>每个工作空间 = 1 个 K8s Namespace（一个主 NS）</li>
 *   <li>1.0 模式下 primaryClusterId 必有值（单集群单租户）</li>
 *   <li>拥有 3 个 ResourcePool：EXCLUSIVE / SHARED / OVERSELL</li>
 *   <li>可包含 N 个 Project</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {
    private String id;
    private String name;
    private String description;
    /** 1.0 单集群下唯一物理集群 ID */
    private String primaryClusterId;
    private String namespace;
    private String serviceAccountName;
    private String volcanoQueueName;
    private Integer maxPods;
    private String createdBy;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
