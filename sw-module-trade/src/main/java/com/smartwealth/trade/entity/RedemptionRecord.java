package com.smartwealth.trade.entity;


import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 赎回交易流水表 - 流量模型
 * 用于记录每一笔异步赎回指令的生命周期
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_trade_redemption_record")
public class RedemptionRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 产品ID
     */
    private Long productId;

    /**
     * 本次赎回的份额（重要：部分赎回时靠它解冻/核销）
     */
    private BigDecimal amount;

    /**
     * 全局唯一业务编号 - 用于MQ幂等去重
     */
    private long requestId;

    private String freezeDetails;

    /**
     * 状态：0-申请中，1-赎回成功，2-赎回失败
     * 建议在 Service 层使用下面的内部枚举
     */
    private Status status;

    /**
     * 资产模块返回的失败原因
     */
    private String failReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // --- 状态枚举 ---
    @Getter
    @AllArgsConstructor
    public enum Status {
        APPLYING(0, "申请中"),
        SUCCESS(1, "赎回成功"),
        FAIL(2, "赎回失败");

        @EnumValue
        private final int code;
        private final String desc;
    }
}