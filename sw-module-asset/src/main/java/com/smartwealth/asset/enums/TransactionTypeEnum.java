package com.smartwealth.asset.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 交易类型枚举
 */
@Getter
@AllArgsConstructor
public enum TransactionTypeEnum {

    /**
     * 充值：外部资金进入钱包
     */
    RECHARGE("RECHARGE", "充值"),

    /**
     * 提现：钱包资金流向外部
     */
    WITHDRAW("WITHDRAW", "提现"),

    /**
     * 申购：钱包余额转为理财资产
     */
    PURCHASE("PURCHASE", "申购产品"),

    /**
     * 赎回：理财资产转回钱包余额
     */
    REDEEM("REDEEM", "赎回产品"),

    /**
     * 收益：理财订单产生的每日利息
     */
    INCOME("INCOME", "收益发放");

    /**
     * 存入数据库的值
     */
    @EnumValue
    private final String code;

    /**
     * 前端展示的值
     */
    @JsonValue
    private final String description;
}