package com.smartwealth.wealth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "全平台资产看板视图对象")
public class PlatformDashboardVO {

    @Schema(description = "平台总 AUM (Total Assets Under Management)")
    private BigDecimal totalAum;

    @Schema(description = "平台用户总余额")
    private BigDecimal totalWalletBalance;

    @Schema(description = "平台总持仓市值")
    private BigDecimal totalHoldingValue;

    @Schema(description = "全平台待清算浮盈总额")
    private BigDecimal totalFloatingProfit;

    @Schema(description = "统计快照时间")
    private LocalDateTime snapshotTime;
}