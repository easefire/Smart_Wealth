package com.smartwealth.trade.dto;


import lombok.Data;
import java.math.BigDecimal;

/**
 * 交易对账异常对象
 */
@Data
public class TradeCheckDTO {
    /**
     * 异常订单ID
     */
    private Long orderId;

    /**
     * 订单表记录的累计收益 (t_trade_order.accumulated_income)
     */
    private BigDecimal orderIncome;

    /**
     * 流水表累加出来的真实收益 (SUM(t_trade_daily_profit.daily_profit))
     */
    private BigDecimal realSumIncome;
}
