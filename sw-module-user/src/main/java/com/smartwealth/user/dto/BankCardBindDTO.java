package com.smartwealth.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "银行卡绑定参数")
public class BankCardBindDTO {

    @NotBlank(message = "银行名称不能为空")
    @Schema(description = "银行名称")
    private String bankName;

    @NotBlank(message = "银行卡号不能为空")
    @Pattern(regexp = "^\\d{16,19}$", message = "银行卡号格式不正确")
    @Schema(description = "银行卡号")
    private String cardNo;

    @NotNull(message = "卡片类型不能为空")
    @Schema(description = "卡片类型 (1:储蓄卡, 2:信用卡)")
    private Integer cardType;

    @Schema(description = "是否设为默认卡 (0:否, 1:是)")
    private Integer isDefault = 0;

    @Schema(description = "单日交易限额")
    private BigDecimal limitPerDay;
}
