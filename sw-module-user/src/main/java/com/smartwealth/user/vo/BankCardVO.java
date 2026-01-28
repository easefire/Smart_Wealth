package com.smartwealth.user.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Schema(description = "银行卡视图对象")
public class BankCardVO {

    @Schema(description = "银行卡记录ID (用于删除/修改)")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "银行名称")
    private String bankName;

    @Schema(description = "脱敏后的银行卡号 (如: 6222 **** **** 8888)")
    private String cardNo;

    @Schema(description = "卡片类型 (1:储蓄卡, 2:信用卡)")
    private Integer cardType;

    @Schema(description = "是否为默认卡 (0:否, 1:是)")
    private Integer isDefault;

    @Schema(description = "单日交易限额")
    private BigDecimal limitPerDay;
}