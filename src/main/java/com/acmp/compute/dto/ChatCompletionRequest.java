package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 前端测试推理服务时使用的 OpenAI Chat Completions 核心参数。
 *
 * model 和目标 URL 均不允许由前端指定，后端会从部署记录中读取，
 * 防止用户借助代理访问任意内网地址。
 */
@Data
public class ChatCompletionRequest {

    @Valid
    @NotEmpty
    private List<ChatMessage> messages;

    private Double temperature = 0.7D;
    private Double topP = 0.8D;
    private Double repetitionPenalty = 1.05D;
    private Integer maxTokens = 512;

    @Data
    public static class ChatMessage {
        private String role;
        private String content;
    }
}
