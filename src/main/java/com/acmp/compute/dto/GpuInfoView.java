package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class GpuInfoView {
    private String model;
    private Long memoryMb;
    private Integer nodeCount;
    private Integer totalCards;
    private List<String> nodeNames;
}
