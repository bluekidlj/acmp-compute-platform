package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 模型部署记录。规格参数通过 specId 获取，不在部署表中重复保存。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelDeployment {
    private String id;
    private String projectId;
    private String tenantId;
    private String modelId;
    private String resourcePoolId;
    private String specId;
    private String name;
    private String modelName;
    private String modelSource;
    private String modelIdOrPath;
    private String vllmImage;
    private Integer port;
    private Integer replicas;
    private Integer gpuCountPerReplica;
    private Integer tensorParallelSize;
    private Double gpuMemoryUtilization;
    private Integer maxModelLength;
    private String assignedGpuIdsJson;
    private String k8sDeploymentName;
    private String k8sServiceName;
    private String status;
    private String serviceUrl;
    private String actualClusterId;
    private String createdBy;
    private String failureMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
