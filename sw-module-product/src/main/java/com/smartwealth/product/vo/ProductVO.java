package com.smartwealth.product.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductVO {
    private Long id;
    private String code;
    private String name;
    private Integer cycle;      // 锁定周期
    private BigDecimal baseRate; // 基准利率
    private BigDecimal latestRate; // 最新实际利率
    private BigDecimal currentNav; // 当前净值
    private Integer riskLevel;
    private BigDecimal availableStock; // 剩余额度
}