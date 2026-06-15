package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 分配项目配额请求：管理员把某池某规格分给项目。
 */
@Data
public class ProjectQuotaRequest {
    @NotBlank
    private String poolId;
    @NotBlank
    private String specId;
    @NotNull
    @Min(1)
    private Integer totalNodes;
}
