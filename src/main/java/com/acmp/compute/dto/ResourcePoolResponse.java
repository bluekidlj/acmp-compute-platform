package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ResourcePoolResponse {
    private String id;
    private String name;
    private String description;
    private String departmentCode;
    private String departmentName;
    private String status;

    /** 关联的物理集群 ID 列表 */
    private List<String> physicalClusterIds;

    /** 按规格的配额（每条含 specName/total/allocated/available） */
    private List<SpecQuotaView> specQuotas;

    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    public static class SpecQuotaView {
        private String specId;
        private String specName;
        private Integer totalQuota;
        private Integer allocatedQuota;
        private Integer availableQuota;
    }
}
