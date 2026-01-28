package com.smartwealth.asset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "资产流水视图对象")
public class AssetFlowVO {
    private Long id;
    private String flowNo;         // 业务流水号
    private BigDecimal amount;     // 变动金额
    private String type;           // 交易类型描述
    private BigDecimal balanceSnapshot; // 变动后余额
    private String remark;         // 备注
    private LocalDateTime createTime; // 交易时间
}