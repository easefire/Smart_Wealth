package com.smartwealth.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class PromptRequest {
    @Schema(description = "用户的提问或指令", example = "帮我分析一下当前的 A 股行情")
    private String prompt;
}