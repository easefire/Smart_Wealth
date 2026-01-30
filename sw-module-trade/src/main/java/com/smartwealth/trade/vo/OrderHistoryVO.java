package com.smartwealth.trade.vo;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.smartwealth.trade.enums.TradeStatusEnum;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderHistoryVO {
    private Long id;
    private String productName;   // 直接对应 prod_name_snap
    private BigDecimal amount;    // 交易金额
    private BigDecimal rate;      // 对应 rate_snap，展示购买时的利率
    private TradeStatusEnum status;       // 订单状态
    private LocalDateTime createTime; // 交易时间
}
