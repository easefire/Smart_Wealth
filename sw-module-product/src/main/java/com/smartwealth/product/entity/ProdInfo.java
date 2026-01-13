package com.smartwealth.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

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
     * 当前利率
     */
    private BigDecimal rate;

    /**
     * 封闭期(天)
     */
    private Integer cycle;

    /**
     * R1-R5
     */
    private Byte riskLevel;

    private BigDecimal totalStock;

    private BigDecimal lockedStock;

    /**
     * 1-在售, 2-售罄
     */
    private Byte status;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public Integer getCycle() {
        return cycle;
    }

    public void setCycle(Integer cycle) {
        this.cycle = cycle;
    }

    public Byte getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(Byte riskLevel) {
        this.riskLevel = riskLevel;
    }

    public BigDecimal getTotalStock() {
        return totalStock;
    }

    public void setTotalStock(BigDecimal totalStock) {
        this.totalStock = totalStock;
    }

    public BigDecimal getLockedStock() {
        return lockedStock;
    }

    public void setLockedStock(BigDecimal lockedStock) {
        this.lockedStock = lockedStock;
    }

    public Byte getStatus() {
        return status;
    }

    public void setStatus(Byte status) {
        this.status = status;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "ProdInfo{" +
            "id = " + id +
            ", name = " + name +
            ", code = " + code +
            ", rate = " + rate +
            ", cycle = " + cycle +
            ", riskLevel = " + riskLevel +
            ", totalStock = " + totalStock +
            ", lockedStock = " + lockedStock +
            ", status = " + status +
            ", updateTime = " + updateTime +
        "}";
    }
}
