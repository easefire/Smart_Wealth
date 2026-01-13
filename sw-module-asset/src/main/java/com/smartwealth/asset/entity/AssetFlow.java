package com.smartwealth.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * <p>
 * 资金流水表
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@TableName("t_asset_flow")
public class AssetFlow implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 唯一流水号
     */
    private String flowNo;

    private Long userId;

    /**
     * 关联业务单号
     */
    private Long orderId;

    /**
     * 金额(+/-)
     */
    private BigDecimal amount;

    /**
     * DEPOSIT, WITHDRAW, BUY, INCOME
     */
    private String type;

    /**
     * 变动后余额
     */
    private BigDecimal balanceSnapshot;

    private String remark;

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFlowNo() {
        return flowNo;
    }

    public void setFlowNo(String flowNo) {
        this.flowNo = flowNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BigDecimal getBalanceSnapshot() {
        return balanceSnapshot;
    }

    public void setBalanceSnapshot(BigDecimal balanceSnapshot) {
        this.balanceSnapshot = balanceSnapshot;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    @Override
    public String toString() {
        return "AssetFlow{" +
            "id = " + id +
            ", flowNo = " + flowNo +
            ", userId = " + userId +
            ", orderId = " + orderId +
            ", amount = " + amount +
            ", type = " + type +
            ", balanceSnapshot = " + balanceSnapshot +
            ", remark = " + remark +
            ", createTime = " + createTime +
        "}";
    }
}
