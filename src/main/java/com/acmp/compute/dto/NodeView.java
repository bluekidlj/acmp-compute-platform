package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 节点视图（含标签/污点/GPU 资源/分配）。
 */
@Data
@Builder
public class NodeView {
    private String name;
    private String status;
    /** NVIDIA-A100-SXM4-80GB 等 */
    private String gpuModel;
    /** 节点 allocatable 卡数 */
    private Integer gpuCount;
    private Integer cpuCores;
    private Integer memoryGiB;
    /** 节点 labels JSON */
    private String labelsJson;
    /** 节点 taints JSON */
    private String taintsJson;
    /** 该节点上的 HAMi 切分规格 */
    private List<GpuSplitView> splits;
}
