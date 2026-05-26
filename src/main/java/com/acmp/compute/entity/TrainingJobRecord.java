package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 训练任务记录（与 K8s VolcanoJob 一一对应）。
 *
 * 三个层级 ID 同 ModelDeployment：
 *  - workspaceId    : K8s 边界
 *  - resourcePoolId : 配额归属
 *  - specId         : 算力规格
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingJobRecord {
    private String id;
    private String workspaceId;
    private String resourcePoolId;
    private String specId;
    private Integer replicas;
    private String k8sJobName;
    private String jobName;
    private String status;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
