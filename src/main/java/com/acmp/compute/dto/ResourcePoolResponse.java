package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ResourcePoolResponse {
    private String id;
    private String workspaceId;
    /** EXCLUSIVE / SHARED / OVERSELL */
    private String poolType;
    private String name;
    private String description;
    private String primaryClusterId;
    private Integer totalNodes;
    private Integer allocatedNodes;
    private Integer availableNodes;
    private String status;

    /** 已关联的规格（简版：id + name） */
    private List<SpecBrief> specs;

    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    public static class SpecBrief {
        private String id;
        private String name;
        private String displayName;
        private String specType;
        private String poolType;
    }
}
