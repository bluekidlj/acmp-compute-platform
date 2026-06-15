package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ScanResult {
    private Instant scannedAt;
    private Integer nodeCount;
    private Integer gpuModelCount;
    private Integer splitCount;
    private Integer maxCpuCores;
    private Integer maxMemoryGib;
    private List<String> gpuTypes;
    private List<GpuSplitView> splits;
}
