package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 资源池（1.0：Workspace 私有三类池）。
 *
 * <p>三类池固定语义：
 * <ul>
 *   <li>EXCLUSIVE  - 独占整卡（PHYSICAL 规格）</li>
 *   <li>SHARED     - HAMi vGPU 切分（VIRTUAL 规格）</li>
 *   <li>OVERSELL   - 超分占位（OVERSELL 规格，1.0 不实际提交 K8s）</li>
 * </ul>
 *
 * <p>资源数量管理：
 * <ul>
 *   <li>totalNodes      - 池总容量（卡数 / vGPU 数）</li>
 *   <li>allocatedNodes  - 已分给各 Project 之和（约束：≤ totalNodes）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourcePool {
    private String id;
    private String workspaceId;
    /** EXCLUSIVE / SHARED / OVERSELL */
    private String poolType;
    private String name;
    private String description;
    private String primaryClusterId;
    private Integer totalNodes;
    private Integer allocatedNodes;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
