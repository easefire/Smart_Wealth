package com.smartwealth.asset.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 用户钱包总账表
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@TableName("t_asset_wallet")
@Data
public class AssetWallet implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_id", type = IdType.INPUT) // 明确标识主键由外部传入
    private Long userId;

    private BigDecimal balance;

    private BigDecimal frozenAmount;

    private Integer version;

    private String payPassword;

    @TableField(fill = FieldFill.INSERT_UPDATE) // 建议使用自动填充更新时间
    private LocalDateTime updateTime;
}
