package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 修改资源池请求：
 *   - totalNodes  池总容量
 *   - specs       关联规格 ID 列表（覆盖式；不传 = 不变）
 */
@Data
public class ResourcePoolUpdateRequest {
    @NotNull
    @Min(0)
    private Integer totalNodes;
    private List<String> specs;
}
