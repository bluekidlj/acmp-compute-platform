package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class ProjectResponse {
    private String id;
    private String tenantId;
    private String name;
    private String description;
    private String createdBy;
    private String status;
    private List<String> memberIds;
    /** 配额视图：按 pool_type 分组的 spec 配额 */
    private Map<String, List<QuotaView>> quotaByPoolType;
    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    public static class QuotaView {
        private String quotaId;
        private String poolId;
        private String poolName;
        private String specId;
        private String specName;
        private String specType;
        private Integer totalNodes;
        private Integer usedNodes;
        private Integer availableNodes;
    }
}
