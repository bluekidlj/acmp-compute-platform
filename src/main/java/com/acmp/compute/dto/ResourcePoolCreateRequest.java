package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.util.List;

/**
 * 创建逻辑资源池请求。
 *
 * 设计：逻辑池本身不存调度规则/标签/资源数量；
 * 资源按"规格 + 数量"维度划分，提交时给出 {@link SpecQuotaItem} 列表。
 */
@Data
public class ResourcePoolCreateRequest {
    /** 关联的物理集群（至少一个） */
    @NotEmpty
    private List<String> physicalClusterIds;

    @NotBlank
    private String name;

    private String description;

    @NotBlank
    @Pattern(regexp = "^[a-z0-9_-]+$")
    private String departmentCode;

    @NotBlank
    private String departmentName;

    /** 按规格的总配额清单，至少一条（poolLabel 非空时忽略此字段，由平台自动生成切分规格） */
    private List<SpecQuotaItem> specQuotas;

    /** 【HAMi vGPU】节点 poolLabel（切分规格名），单选。如 "nvidia-a100-80g-1/4"。非空时表示启用切分，平台自动生成 ComputeSpec */
    private String poolLabel;

    @Data
    public static class SpecQuotaItem {
        /** 规格名（compute_spec.name），如 nvidia-rtx4090-24g */
        @NotBlank
        private String specName;

        /** 该规格在池内的总配额（即可用副本数） */
        @NotNull
        @Min(0)
        private Integer totalQuota;
    }
}
