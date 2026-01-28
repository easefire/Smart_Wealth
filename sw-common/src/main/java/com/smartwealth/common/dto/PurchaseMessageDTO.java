package com.smartwealth.common.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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

    private static final long serialVersionUID = 1L;

    /**
     * 订单 ID (用于资产模块幂等校验及回执) [cite: 2026-01-24]
     */
    private Long orderId;

    /**
     * 用户 ID (定位钱包记录) [cite: 2026-01-24]
     */
    private Long userId;

    /**
     * 申购金额 (需从钱包扣除的数额) [cite: 2026-01-24]
     */
    private BigDecimal amount;

    /**
     * 申购份额 (用于产品模块回执确认) [cite: 2026-01-24]
     */
    private BigDecimal share;

    /**
     * 支付密码 (资产模块进行最终安全验证) [cite: 2026-01-24]
     */
    private String payPassword;

    /**
     * 产品 ID (可选，用于流水记录或审计)
     */
    private Long productId;
}
