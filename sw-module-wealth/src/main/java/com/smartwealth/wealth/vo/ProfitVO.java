package com.smartwealth.wealth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "持仓收益明细对象")
public class ProfitVO {

    @Schema(description = "理财产品名称")
    private String prodName;

    @Schema(description = "收益金额（持仓浮盈）")
    private BigDecimal profit;

    @Schema(description = "收益计算起始时间", example = "2026-01-01T00:00:00")
    private LocalDateTime startTime;

    @Schema(description = "收益计算结束时间", example = "2026-01-19T23:59:59")
    private LocalDateTime endTime;

    @Schema(description = "查询/估值执行时间", example = "2026-01-19T15:30:00")
    private LocalDateTime queryTime;
}
