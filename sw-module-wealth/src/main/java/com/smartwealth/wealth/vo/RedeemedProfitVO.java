package com.smartwealth.wealth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "已赎回收益汇总对象")
public class RedeemedProfitVO {
    private String prodName;        // 产品名称

    private BigDecimal totalProfit; // 累计浮动盈亏（自持有以来）

    private String profitRate; // 收益率
}
