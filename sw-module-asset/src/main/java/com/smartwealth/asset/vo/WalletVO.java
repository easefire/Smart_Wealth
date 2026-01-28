package com.smartwealth.asset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "用户钱包视图对象")
public class WalletVO {

    @Schema(description = "可用余额", example = "10000.0000")
    private BigDecimal balance; // 对应表字段 balance

    @Schema(description = "冻结金额", example = "0.0000")
    private BigDecimal frozenAmount; // 对应表字段 frozen_amount

    @Schema(description = "总金额 (可用 + 冻结)")
    private BigDecimal totalAmount; // 业务计算字段：balance + frozen_amount

    @Schema(description = "是否已设置支付密码")
    private Boolean hasPayPassword; // 根据 pay_password 是否为空判断

    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime; // 对应表字段 update_time
}