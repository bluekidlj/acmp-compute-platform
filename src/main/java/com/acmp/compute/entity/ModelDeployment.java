package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * vLLM 模型服务部署记录。
 *
 * 三个层级 ID：
 *  - workspaceId    : K8s 边界（Namespace 所在）
 *  - resourcePoolId : 配额归属（双层配额 L1）
 *  - specId         : 算力规格（驱动资源键、调度约束）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelDeployment {
    private String id;
    private String workspaceId;
    private String resourcePoolId;
    private String specId;
    private String name;
    private String modelName;
    /** with_weights / without_weights */
    private String modelSource;
    private String modelIdOrPath;
    private String vllmImage;
    private Integer gpuPerReplica;
    private Integer gpumemMb;
    private Integer gpucores;
    private Integer replicas;
    private String k8sDeploymentName;
    private String k8sServiceName;
    /** pending / running / failed / stopped */
    private String status;
    private String serviceUrl;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
