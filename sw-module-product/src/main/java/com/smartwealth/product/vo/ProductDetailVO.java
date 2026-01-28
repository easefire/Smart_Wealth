package com.smartwealth.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "产品画像视图对象")
public class ProductDetailVO {

    @Schema(description = "产品基础信息")
    private ProductBaseInfoVO baseInfo;

    @Schema(description = "可用库存")
    private BigDecimal availableStock;

    @Schema(description = "收益历史列表")
    private List<ProductHistoryVO> historyList;

    @Data
    public static class ProductBaseInfoVO {
        private Long id;
        private String name;
        private String code;
        private Integer cycle;       // 锁定周期
        private Integer riskLevel;    // 对应表中 tinyint
        private BigDecimal currentNav; // 当前净值
        private BigDecimal latestRate; // 最新年化收益率
        private BigDecimal baseRate;   // 基准利率
        private Integer status;        // 产品状态
    }
}