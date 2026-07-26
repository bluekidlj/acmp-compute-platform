package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * 调试重置是破坏性操作，调用方必须明确提交 RESET。
 */
@Data
public class ClusterResetRequest {

    @NotBlank(message = "确认文本不能为空")
    @Pattern(regexp = "RESET", message = "确认文本必须为 RESET")
    private String confirmation;
}
