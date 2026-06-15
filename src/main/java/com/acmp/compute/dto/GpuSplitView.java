package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class GpuSplitView {
    /** nvidia-a100-80g-1/4 等 */
    private String poolLabel;
    private Integer memMb;
    private Integer coresPct;
    private Integer nodeCount;
    private List<String> nodeNames;
}
