package com.acmp.compute.dto;

import com.acmp.compute.entity.GpuBrand;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ResourcePoolResponse {
    private String id;
    private String poolType;
    private String name;
    private String description;
    private Integer gpuCount;
    private String status;

    private List<SpecBrief> specs;

    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    public static class SpecBrief {
        private String id;
        private String name;
        private String displayName;
        private String specType;
        private GpuBrand gpuBrand;
        private String gpuShare;
        /** 当前规格由全部关联 GPU 提供的规格节点总数。 */
        private Integer totalNodes;
        /** 规格总容量扣除已分配租户配额后的可用节点数。 */
        private Integer availableNodes;
    }
}
