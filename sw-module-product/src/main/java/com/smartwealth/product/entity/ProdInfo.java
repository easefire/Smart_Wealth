package com.smartwealth.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 理财产品信息表
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Data
@TableName("t_prod_info")
public class ProdInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    /**
     * 产品代码
     */
    private String code;

    /**
     * 基准利率
     */
    private BigDecimal baseRate;

    private BigDecimal latestRate;

    private BigDecimal currentNav;
    /**
     * 封闭期(天)
     */
    private Integer cycle;

    /**
     * R1-R5
     */
    private Integer riskLevel;

    private BigDecimal totalStock;

    private BigDecimal lockedStock;

    /**
     * 1-在售, 2-下架
     */
    private Integer status;

    private LocalDateTime updateTime;



}
