package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 创建/更新工作空间请求。
 * 工作空间的资源量按"规格 + 上限"申请，不再用 gpu/cpu/mem 聚合维度。
 */
@Data
public class WorkspaceRequest {
    @NotBlank
    private String name;

    private String description;

    /** 所属逻辑资源池 */
    @NotBlank
    private String resourcePoolId;

    /** 工作空间申请的规格配额清单（更新时可为空 = 不动配额） */
    private List<SpecQuotaItem> specQuotas;

    /** Pod 数量上限（与规格无关），默认 50 */
    @Min(1)
    private Integer maxPods = 50;

    @Data
    public static class SpecQuotaItem {
        @NotBlank
        private String specName;
        @NotNull
        @Min(0)
        private Integer maxQuota;
    }

    /** 创建时强制要求 specQuotas 非空 */
    public void requireSpecQuotasForCreate() {
        if (specQuotas == null || specQuotas.isEmpty()) {
            throw new IllegalArgumentException("创建工作空间必须指定 specQuotas");
        }
    }
}
