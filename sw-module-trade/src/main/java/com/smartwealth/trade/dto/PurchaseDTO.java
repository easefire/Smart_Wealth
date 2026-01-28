package com.smartwealth.trade.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "申购理财产品请求对象")
public class PurchaseDTO {

    @Schema(description = "理财产品ID", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "产品ID不能为空")
    private Long productId;

    @Schema(description = "申购金额", example = "10000.00", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "申购金额不能为空")
    @DecimalMin(value = "0.01", message = "申购金额必须大于0")
    private BigDecimal amount;

    @Schema(description = "支付密码", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "支付密码不能为空")
    private String payPassword;
}
