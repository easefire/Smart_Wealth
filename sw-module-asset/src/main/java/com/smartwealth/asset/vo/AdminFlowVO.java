package com.smartwealth.asset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "管理员流水审计详情对象")
public class AdminFlowVO {
    private Long flowId;
    private Long userId;
    private String userName;      // 关联查询：用户姓名/昵称
    private String userPhone;     // 关联查询：用户手机号（脱敏）
    private String prodName;      // 关联查询：产品名称
    private BigDecimal amount;    // 变动金额
    private BigDecimal balance;   // 变动后余额
    private String typeDesc;      // 交易类型描述
    private String remark;        // 备注（含收益率等）
    private LocalDateTime time;   // 发生时间
}