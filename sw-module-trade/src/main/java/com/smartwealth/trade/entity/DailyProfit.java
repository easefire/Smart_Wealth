package com.smartwealth.trade.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_trade_daily_profit")
public class DailyProfit {
    @TableId()
    private Long id;

    private Long userId;     // 用户ID
    private Long prodId;     // 产品ID
    private Long orderId;    // 关联订单ID

    private LocalDate profitDate;   // 收益日期 (yyyy-MM-dd)
    private BigDecimal dailyProfit; // 当日增量收益

    /**
     * 1: 持仓浮盈 (由净值更新触发)
     * 2: 赎回收益 (由赎回动作触发)
     */
    private Integer type;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
