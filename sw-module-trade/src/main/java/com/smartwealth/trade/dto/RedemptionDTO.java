package com.smartwealth.trade.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RedemptionDTO {
    @NotNull(message = "产品ID不能为空")
    private Long productId;

    @NotNull(message = "赎回份额不能为空")
    @DecimalMin(value = "0.01", message = "赎回份额必须大于0")
    private BigDecimal redeemQuantity; // 用户想要赎回的份额

    @NotBlank(message = "支付密码不能为空")
    private String payPassword;
}