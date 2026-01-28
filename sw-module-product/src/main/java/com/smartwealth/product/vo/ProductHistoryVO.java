package com.smartwealth.product.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ProductHistoryVO {
    private LocalDate date;
    private BigDecimal rate; // 年化收益率
    private BigDecimal nav;  // 单位净值
}
