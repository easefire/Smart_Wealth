package com.smartwealth.asset.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.smartwealth.asset.enums.TransactionTypeEnum;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 资金交易流水表 - 记录所有余额变动凭证
 * </p>
 *
 * @author Gemini
 * @since 2026-01-18
 */
@Data
@ToString
@TableName("t_asset_flow") // 建议与修改后的 SQL 表名保持一致
public class AssetFlow implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 唯一业务流水号 (用于对账)
     */
    private String flowNo;

    /**
     * 关联用户 ID
     */
    private Long userId;

    /**
     * 业务关联 ID (原 orderId)
     * 充值存银行卡 ID, 申购/收益存订单 ID
     */
    @TableField("biz_id")
    private Long bizId;

    /**
     * 变动金额 (+/-)
     */
    private BigDecimal amount;

    /**
     * 交易类型
     * RECHARGE-充值, WITHDRAW-提现, PURCHASE-申购, REDEEM-赎回, INCOME-收益
     */
    private TransactionTypeEnum type;

    /**
     * 变动后余额快照 (核心审计字段)
     */
    private BigDecimal balanceSnapshot;

    /**
     * 业务备注 (如: 银行卡充值-尾号3456)
     */
    private String remark;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT) // 配合 MyMetaObjectHandler 实现自动填充
    private LocalDateTime createTime;

}