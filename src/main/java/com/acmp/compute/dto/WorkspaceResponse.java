package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class WorkspaceResponse {
    private String id;
    private String name;
    private String description;
    private String primaryClusterId;
    private String primaryClusterName;
    private String namespace;
    private String volcanoQueueName;
    private String serviceAccountName;
    private Integer maxPods;
    private String createdBy;
    private String status;

    /** 三类池摘要（创建/详情接口返回） */
    private List<WorkspacePoolSummary> pools;

    /** 工作空间成员 */
    private List<String> memberIds;

    private Instant createdAt;
    private Instant updatedAt;
}
