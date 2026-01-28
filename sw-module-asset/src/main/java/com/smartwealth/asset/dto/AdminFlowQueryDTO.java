package com.smartwealth.asset.dto;

import com.smartwealth.asset.enums.TransactionTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

@Data
@Schema(description = "管理员流水审计查询对象")
public class AdminFlowQueryDTO {
    private Integer current = 1;
    private Integer size = 10;

    @Schema(description = "目标用户ID（可选）")
    private Long targetUserId;

    @Schema(description = "产品ID（可选，对应 biz_id）")
    private Long productId;

    @Schema(description = "流水类型集合（可选）")
    private List<TransactionTypeEnum> types;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
}

