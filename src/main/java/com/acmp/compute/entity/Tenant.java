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
public class Tenant {
    private String id;
    private String name;
    private String description;
    private String createdBy;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
