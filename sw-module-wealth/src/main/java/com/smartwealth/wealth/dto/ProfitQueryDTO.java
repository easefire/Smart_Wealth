package com.smartwealth.wealth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
@Schema(description = "收益明细分页查询对象")
public class ProfitQueryDTO {

    @Schema(description = "当前页码", example = "1", defaultValue = "1")
    @Min(value = 1, message = "页码不能小于1")
    private Integer current = 1;

    @Schema(description = "每页条数", example = "10", defaultValue = "10")
    @Min(1) @Max(100)
    private Integer size = 10;

    @Schema(description = "理财产品ID", example = "2")
    private Long productId;

    @Schema(description = "查询类型：1-昨日/持仓收益波动, 2-历史已实现收益", example = "1")
    private Integer queryType; // 对应 t_trade_daily_profit 的 type

    @Schema(description = "起始日期（不传且传了productId则查全量持仓收益）", example = "2026-01-01")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @Schema(description = "结束日期", example = "2026-01-18")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
}