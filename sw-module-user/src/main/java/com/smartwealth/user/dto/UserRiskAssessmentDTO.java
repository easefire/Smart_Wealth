package com.smartwealth.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserRiskAssessmentDTO {
    @Schema(description = "测评总分")
    @NotNull(message = "测评结果不能为空") // 核心：Integer 用 NotNull
    @Min(value = 0, message = "分值不能为负数")
    @Max(value = 100, message = "超过最高分限制")
    private Integer scores; // 如果这里是 Integer，就不能用 @NotEmpty
}