package com.smartwealth.trade.vo;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.smartwealth.trade.enums.TradeStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "管理员订单详情视图对象")
public class AdminOrderVO {
    private Long orderId;
    private Long userId;
    private String userName;     // 需跨模块获取
    private Long productId;
    private String productName;  // 需跨模块获取
    private BigDecimal amount;   // 剩余本金
    private BigDecimal quantity; // 持有份额
    private BigDecimal accumulatedIncome; // 累计已实现收益
    private TradeStatusEnum status; // 订单状态
    private LocalDateTime createTime;
}