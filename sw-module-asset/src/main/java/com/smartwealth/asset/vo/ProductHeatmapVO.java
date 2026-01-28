package com.smartwealth.asset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "产品资金热力统计对象")
public class ProductHeatmapVO {
    private Long productId;
    private String productName;

    private BigDecimal totalInflow;   // 累计买入金额
    private BigDecimal totalOutflow;  // 累计赎回金额
    private BigDecimal netInflow;    // 净流入额（流入 - 流出）

    private Integer purchaseCount;    // 买入笔数
    private Integer redeemCount;      // 赎回笔数
}