package com.smartwealth.user.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 银行卡实体类
 * 对应数据库表：t_user_bank_card
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user_bank_card")
public class BankCard {

    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID) // 使用雪花算法生成分布式唯一ID
    private Long id;

    /**
     * 用户ID (关联 t_user_base 表)
     */
    private Long userId;

    /**
     * 银行名称 (如：中国银行、招商银行)
     */
    private String bankName;

    /**
     * 银行卡号 (数据库存储加密后的 64 位字符串)
     */
    private String cardNo;

    /**
     * 卡片类型 (1:储蓄卡, 2:信用卡)
     */
    private Integer cardType;

    /**
     * 是否为默认提现卡 (0:否, 1:是)
     */
    private Integer isDefault;

    /**
     * 单日交易限额
     */
    private BigDecimal limitPerDay;
}
