package com.smartwealth.asset.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "提现请求对象")
public class WithdrawDTO {
    @Schema(description = "银行卡ID", required = true)
    private Long bankCardId;

    @Schema(description = "提现金额", required = true)
    private BigDecimal amount;

    @Schema(description = "支付密码", required = true)
    private String payPassword;
}