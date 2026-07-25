package com.acmp.compute.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class TenantRequest {

    @NotBlank
    private String name;

    private String description;
}
