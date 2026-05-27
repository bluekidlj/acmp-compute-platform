package com.acmp.compute.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 集群节点扫描响应（含节点列表 + 集群 poolLabel 枚举）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeScanResponse {
    /** 集群节点列表 */
    private List<NodeInfoResponse> nodes;
    /** 集群中所有不重复的 pool 标签值（用于资源池创建时选择切分规格） */
    private Set<String> poolLabels;
}