package com.smartwealth.trade.vo;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "用户持仓展示对象")
public class PositionVO {
    private Long id;
    private Long prodId;    // 产品ID
    private String prodName;     // 产品名称（来自快照或实时查询）
    private BigDecimal amount;   // 投入本金
    private BigDecimal quantity; // 持仓份额
    private BigDecimal currentNav; // 最新净值
    private BigDecimal marketValue; // 当前市值 (份额 * 最新净值)
    private BigDecimal profit;   // 浮动盈亏 (市值 - 本金)
    private BigDecimal latestRate; // 最新利率
    private LocalDateTime createTime; // 申购时间
    @Schema(description = "是否可赎回：true-可赎回，false-封闭中")
    private Boolean redeemable;
    @Schema(description = "到期时间描述")
    private LocalDateTime expireTime;
}
