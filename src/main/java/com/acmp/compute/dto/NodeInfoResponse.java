package com.acmp.compute.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * K8s 节点信息（用于纳管扫描）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeInfoResponse {
    private String name;
    private String status;
    private String gpuType;           // nvidia.com/gpu-family 或 amd.com/dcu-family
    private int gpuCount;             // nvidia.com/gpu allocatable
    private int gpuMemMb;             // nvidia.com/gpumem allocatable（HAMi切分后）
    private int gpuCores;             // nvidia.com/gpucores allocatable（HAMi切分后）
    private int cpuCores;             // cpu allocatable
    private int memoryGiB;            // memory allocatable (GiB)
    private String poolLabel;         // pool node label value
    private String labelsJson;        // 全部标签 JSON
}