package com.smartwealth.product.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@TableName("t_prod_rate_history")
public class ProductRateHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 产品ID
     */
    private Long prodId;

    /**
     * 当日执行年化利率 (如 0.0450)
     */
    private BigDecimal rate;

    /**
     * 当日单位净值 (如 1.0002345678)
     */
    private BigDecimal nav;

    /**
     * 记录日期 (yyyy-MM-dd)
     */
    private LocalDate recordDate;


}