package com.smartwealth.trade.dto;


import lombok.Data;
import java.math.BigDecimal;

/**
 * 资金池对账聚合对象
 * 用于承载 "用户+产品" 维度的份额汇总
 */
@Data
public class PoolCheckDTO {

    /** 用户ID */
    private Long userId;

    /** 产品ID */
    private Long prodId;

    /** * 份额总数
     * 对于 Trade 端： = 当前持有份额 + 历史已赎回份额
     * 对于 Asset 端： = 解析 remark 算出来的历史总买入份额
     */
    private BigDecimal amount;

    /**
     * 辅助方法：生成 Map 的唯一 Key
     * 格式：userId_prodId
     */
    public String getPoolKey() {
        return userId + "_" + prodId;
    }
}