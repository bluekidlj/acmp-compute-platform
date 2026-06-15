package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 工作空间下某类资源池的摘要。
 */
@Data
@Builder
public class WorkspacePoolSummary {
    private String id;
    /** EXCLUSIVE / SHARED / OVERSELL */
    private String poolType;
    private String name;
    private String description;
    private Integer totalNodes;
    private Integer allocatedNodes;
    private Integer availableNodes;
    private Integer specCount;
}
