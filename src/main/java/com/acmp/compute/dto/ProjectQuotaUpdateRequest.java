package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class ProjectQuotaUpdateRequest {
    @NotNull
    @Min(1)
    private Integer totalNodes;
}
