package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ModelDeploymentResponse {
    private String id;
    private String workspaceId;
    private String resourcePoolId;
    private String specId;
    private String name;
    private String modelName;
    private String modelSource;
    private String modelIdOrPath;
    private String vllmImage;
    private Integer gpuPerReplica;
    private Integer replicas;
    private String k8sDeploymentName;
    private String k8sServiceName;
    private String status;
    private String serviceUrl;
    private Integer readyReplicas;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
