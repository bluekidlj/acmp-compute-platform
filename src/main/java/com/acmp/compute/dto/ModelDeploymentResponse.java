package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ModelDeploymentResponse {
    private String id;
    private String projectId;
    private String tenantId;
    private String resourcePoolId;
    private String specId;
    private String name;
    private String modelName;
    private String modelSource;
    private String modelIdOrPath;
    private String vllmImage;
    private Integer port;
    private Integer replicas;
    private String k8sDeploymentName;
    private String k8sServiceName;
    private String status;
    private String serviceUrl;
    private Integer readyReplicas;
    private String actualClusterId;
    private String createdBy;
    private String failureMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
