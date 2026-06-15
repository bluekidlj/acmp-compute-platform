package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 创建/更新工作空间请求。
 *
 * 1.0 字段：
 *   - name          必填
 *   - description   可选
 *   - clusterId     必填（1.0 单集群）
 *   - memberIds     可选（管理员初始加入的用户）
 *   - maxPods       可选，默认 50
 *
 * 注意：1.0 创建工作空间时不再要求 specQuotas；资源池在创建时自动建三类空池，
 * 容量由后续 PATCH /pools/{id} 调整。
 */
@Data
public class WorkspaceRequest {
    @NotBlank
    private String name;
    private String description;
    @NotBlank
    private String clusterId;
    private List<String> memberIds;
    @Min(1)
    private Integer maxPods = 50;
}
