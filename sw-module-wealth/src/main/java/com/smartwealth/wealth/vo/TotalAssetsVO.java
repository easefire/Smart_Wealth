package com.smartwealth.wealth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "总资产概览对象")
public class TotalAssetsVO {
    private BigDecimal totalAmount;      // 总资产 (余额 + 市值)
    private BigDecimal walletBalance;    // 钱包可用余额
    private BigDecimal totalMarketValue; // 持仓总市值 (份额 * 最新净值)
    private BigDecimal totalProfit;      // 累计浮动盈亏 (总市值 - 总本金)
}