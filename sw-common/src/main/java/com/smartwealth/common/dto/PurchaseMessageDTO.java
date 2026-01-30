package com.smartwealth.common.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 申购扣款消息载荷 [cite: 2026-01-24]
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseMessageDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单 ID
     */
    private Long orderId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 申购金额
     */
    private BigDecimal amount;

    /**
     * 申购份额
     */
    private BigDecimal share;

    /**
     * 支付密码
     */
    private String payPassword;

    /**
     * 产品 ID
     */
    private Long productId;
}
