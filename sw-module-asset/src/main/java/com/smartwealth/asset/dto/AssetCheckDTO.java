package com.smartwealth.asset.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 资产对账结果传输对象
 * 用于接收 Mapper 查询出的异常账户信息
 */
@Data
public class AssetCheckDTO {

    /**
     * 异常用户ID
     */
    private Long userId;

    /**
     * 钱包里的实际余额 (t_asset_wallet.balance)
     */
    private BigDecimal walletBalance;

    /**
     * 流水里记录的快照余额 (t_asset_flow.current_balance)
     * 理论上这两个数应该完全相等
     */
    private BigDecimal snapshotBalance;
}
