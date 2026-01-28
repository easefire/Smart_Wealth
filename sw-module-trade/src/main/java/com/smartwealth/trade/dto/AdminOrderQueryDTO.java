package com.smartwealth.trade.dto;

import com.smartwealth.trade.enums.TradeStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "管理员订单查询参数")
public class AdminOrderQueryDTO {
    private Integer current = 1;
    private Integer size = 10;
    private Long targetUserId;   // 按用户过滤
    private Long productId;      // 按产品过滤
    private TradeStatusEnum status; // 按状态过滤（HOLDING, REDEEMED）
    private LocalDate startDate;
    private LocalDate endDate;
}