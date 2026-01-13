package com.smartwealth.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * <p>
 * 理财交易订单表
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@TableName("t_trade_order")
public class TradeOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单ID
     */
    private Long id;

    private Long userId;

    private Long prodId;

    /**
     * 本金
     */
    private BigDecimal amount;

    /**
     * 累计收益
     */
    private BigDecimal accumulatedIncome;

    /**
     * 名称快照
     */
    private String prodNameSnap;

    /**
     * 利率快照
     */
    private BigDecimal rateSnap;

    /**
     * 1-持有中, 3-已赎回, 4-已关单
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime expireTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProdId() {
        return prodId;
    }

    public void setProdId(Long prodId) {
        this.prodId = prodId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getAccumulatedIncome() {
        return accumulatedIncome;
    }

    public void setAccumulatedIncome(BigDecimal accumulatedIncome) {
        this.accumulatedIncome = accumulatedIncome;
    }

    public String getProdNameSnap() {
        return prodNameSnap;
    }

    public void setProdNameSnap(String prodNameSnap) {
        this.prodNameSnap = prodNameSnap;
    }

    public BigDecimal getRateSnap() {
        return rateSnap;
    }

    public void setRateSnap(BigDecimal rateSnap) {
        this.rateSnap = rateSnap;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    @Override
    public String toString() {
        return "TradeOrder{" +
            "id = " + id +
            ", userId = " + userId +
            ", prodId = " + prodId +
            ", amount = " + amount +
            ", accumulatedIncome = " + accumulatedIncome +
            ", prodNameSnap = " + prodNameSnap +
            ", rateSnap = " + rateSnap +
            ", status = " + status +
            ", createTime = " + createTime +
            ", expireTime = " + expireTime +
        "}";
    }
}
