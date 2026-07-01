package com.acmp.compute.dto;

import com.acmp.compute.entity.PoolCard;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
public class PoolCardResponse {
    private String id;
    private String poolId;
    private String gpuBrand;
    private String gpuModel;
    private String nodeName;
    private String serialNo;
    private String specId;
    private Integer slots;
    private String status;
    private Instant createdAt;

    public static PoolCardResponse from(PoolCard c) {
        return PoolCardResponse.builder()
                .id(c.getId())
                .poolId(c.getPoolId())
                .gpuBrand(c.getGpuBrand())
                .gpuModel(c.getGpuModel())
                .nodeName(c.getNodeName())
                .serialNo(c.getSerialNo())
                .specId(c.getSpecId())
                .slots(c.getSlots())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .build();
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class ListResponse {
        private String poolId;
        private Integer totalNodes;
        private List<PoolCardResponse> cards;
        private Map<String, SpecSummary> bySpec;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class SpecSummary {
        private Integer cards;
        private Integer slots;
    }
}
