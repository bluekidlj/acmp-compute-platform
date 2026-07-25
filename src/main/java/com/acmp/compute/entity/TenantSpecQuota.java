package com.acmp.compute.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantSpecQuota {
    private String id;
    private String tenantId;
    private String specId;
    private Integer total;
    private Integer used;
    private Instant createdAt;
    private Instant updatedAt;
}
