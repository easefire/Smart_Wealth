package com.smartwealth.asset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "资金流向分类统计对象")
public class FlowStatisticsVO {

    @Schema(description = "分类统计项列表")
    private List<StatItem> items;

    @Schema(description = "统计周期总流水金额")
    private BigDecimal totalPeriodAmount;

    @Data
    @AllArgsConstructor
    public static class StatItem {
        private String typeDesc;     // 交易类型描述 (如：理财买入)
        private String typeCode;     // 交易类型编码 (如：PURCHASE)
        private BigDecimal amount;   // 该类型总金额
        private Double percentage;   // 百分比占比
        private Long count;          // 发生笔数
    }
}