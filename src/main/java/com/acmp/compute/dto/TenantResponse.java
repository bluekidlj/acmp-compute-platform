package com.acmp.compute.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class TenantResponse {
    private String id;
    private String name;
    private String description;
    private String createdBy;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
