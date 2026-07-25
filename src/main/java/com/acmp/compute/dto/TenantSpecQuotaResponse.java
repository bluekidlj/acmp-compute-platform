package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TenantSpecQuotaResponse {
    private String id;
    private String tenantId;
    private String specId;
    private String specName;
    private String specDisplayName;
    private String resourcePoolId;
    private String resourcePoolName;
    private String poolType;
    private String gpuModel;
    private String gpuShare;
    private Integer cpuCores;
    private Integer memoryGib;
    private Integer capacityNodes;
    private Integer total;
    private Integer used;
    private Integer remaining;
}
