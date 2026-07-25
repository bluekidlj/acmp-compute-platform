package com.acmp.compute.dto;

import lombok.Data;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class TenantSpecQuotaRequest {

    @NotBlank
    private String specId;

    @NotNull
    @Min(0)
    private Integer total;
}
