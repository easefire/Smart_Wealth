package com.smartwealth.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 赎回请求消息载荷
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RedemptionMessageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 交易模块的订单ID (核心：用于幂等校验)
     */
    private Long requestId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 产品ID
     */
    private Long productId;

    /**
     * 总赎回金额 (本金 + 收益)
     */
    private BigDecimal amount;

    /**
     * 赎回份额
     */
    private BigDecimal share;


    private BigDecimal profit;


    /**
     * 备注信息
     */
    private String remark;
}