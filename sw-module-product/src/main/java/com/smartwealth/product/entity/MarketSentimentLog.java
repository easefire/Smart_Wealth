package com.smartwealth.product.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_prod_market_daily_log")
public class MarketSentimentLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 场景代码 (对应 Enum 的 code)
     */
    private String scenarioCode;

    /**
     * 作用日期
     */
    private LocalDate recordDate;

    /**
     * 操作人
     */
    private String operator;

    /**
     * 描述/备注
     */
    private String description;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}