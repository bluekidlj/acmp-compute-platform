package com.acmp.compute.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规格配额条目：用于嵌套在 ResourcePool / Workspace 请求/响应中。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecQuotaEntry {
    /** 规格名称，如 nvidia-a100-80g */
    private String specName;
    /** 规格 ID */
    private String specId;
    /** 总配额（逻辑池使用） */
    private Integer totalQuota;
    /** 上限（工作空间使用） */
    private Integer maxQuota;
    /** 已分配（逻辑池使用） */
    private Integer allocatedQuota;
    /** 已使用（工作空间使用） */
    private Integer usedQuota;
    /** 可用 = total/max - allocated/used */
    private Integer available;
}
