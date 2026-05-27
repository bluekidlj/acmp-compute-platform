package com.acmp.compute.k8s;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * K8s 节点信息（用于纳管扫描）。
 *
 * poolLabels 说明：一个 GPU 节点可同时支持多种切分规格，
 * 例如节点可被标记为 pool=nvidia-a100-80g-1/2 AND pool=nvidia-a100-80g-1/4，
 * 表示该节点同时支持 1/2 卡和 1/4 卡两种 vGPU 规格。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeInfoResponse {
    private String name;
    private String status;
    private String gpuType;           // nvidia.com/gpu-family 或 amd.com/dcu-family
    /** 可用节点数（vGPU 实例数，HAMi 切分后） */
    private int nodeCount;             // nvidia.com/gpu allocatable
    /** 每节点显存（MB，HAMi 切分后） */
    private int nodeMemMb;             // nvidia.com/gpumem allocatable
    /** 每节点算力（百分比，HAMi 切分后） */
    private int nodeCores;             // nvidia.com/gpucores allocatable
    private int cpuCores;             // cpu allocatable
    private int memoryGiB;            // memory allocatable (GiB)
    /** 节点支持的切分规格标签集（一个节点可支持多种切分） */
    private Set<String> poolLabels;
    private String labelsJson;        // 全部标签 JSON
}