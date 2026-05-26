package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 逻辑资源池：纯聚合容器。
 * 设计原则："物理属性归物理池，标准定义归规格，逻辑池只存关联关系"。
 *
 * 资源数量/调度规则全部下沉到：
 *  - resource_pool_physical_cluster (关联的物理集群)
 *  - resource_pool_spec_quota       (按规格的总配额)
 *  - compute_spec                   (nodeSelector / tolerations / resourceQuotaKey)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourcePool {
    private String id;
    private String name;
    private String description;
    private String departmentCode;
    private String departmentName;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
