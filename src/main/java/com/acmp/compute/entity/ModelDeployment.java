package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 1.0 模型部署。
 *
 * 关键字段：
 * <ul>
 *   <li>projectId          - 部署归属项目（配额真正持有者）</li>
 *   <li>workspaceId        - K8s 边界（Namespace）</li>
 *   <li>resourcePoolId     - 实际落到的资源池</li>
 *   <li>specId             - 使用的算力规格</li>
 *   <li>poolType           - 池类型（EXCLUSIVE/SHARED/OVERSELL）</li>
 *   <li>actualClusterId    - 实际落到的物理集群（1.0 = workspace.primaryClusterId）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelDeployment {
    private String id;
    private String projectId;
    private String workspaceId;
    private String resourcePoolId;
    private String specId;
    private String poolType;
    private String name;
    private String modelName;
    private String modelSource;
    private String modelIdOrPath;
    private String vllmImage;
    private Integer gpuPerReplica;
    private Integer gpumemMb;
    private Integer gpucores;
    private Integer replicas;
    private String k8sDeploymentName;
    private String k8sServiceName;
    private String status;
    private String serviceUrl;
    private String actualClusterId;
    private String poolCardId;
    private String resourceKey;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
