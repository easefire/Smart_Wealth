package com.smartwealth.trade.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.smartwealth.trade.enums.TradeStatusEnum;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * <p>
 * 理财交易订单表
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@TableName("t_trade_order")
@Data
public class TradeOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单ID - 建议使用 Snowflake 算法生成
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /**
     * 产品ID
     */
    private Long prodId;

    /**
     * 本金（申购金额）
     */
    private BigDecimal amount;

    private BigDecimal quantity;

    private BigDecimal frozenQuantity;

    /**
     * 累计收益（确权后每天或每期更新）
     */
    private BigDecimal accumulatedIncome;

    /**
     * 名称快照：防止产品改名导致历史记录混乱
     */
    private String prodNameSnap;

    /**
     * 利率快照：锁定购买时的预期收益率
     */
    private BigDecimal rateSnap;

    /**
     * 状态：0-待支付, 1-持有中, 2-赎回中, 3-已赎回, 4-已关单
     */
    private TradeStatusEnum status;

    private LocalDateTime createTime;

    /**
     * 到期时间（如果是定期产品则必须有值）
     */
    private LocalDateTime expireTime;

    /**
     * 建议增加更新时间，方便追溯
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
