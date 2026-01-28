package com.smartwealth.asset.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RechargeDTO {
    @NotNull(message = "请选择银行卡")
    private Long bankCardId; //

    @NotNull(message = "充值金额不能为空")
    @DecimalMin(value = "0.01", message = "最低充值金额为0.01元")
    private BigDecimal amount;
}