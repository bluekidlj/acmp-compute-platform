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
    private String resourcePoolId;
    private String resourcePoolName;
    private String namespace;
    private String volcanoQueueName;
    private String primaryClusterId;
    private Integer maxPods;
    private String createdBy;
    private String status;

    /** 按规格的配额清单（max/used/available） */
    private List<SpecQuotaView> specQuotas;

    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    public static class SpecQuotaView {
        private String specId;
        private String specName;
        private Integer maxQuota;
        private Integer usedQuota;
        private Integer availableQuota;
    }
}
