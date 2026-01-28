package com.smartwealth.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "产品入库请求")
public class ProductSaveDTO {
    @NotBlank(message = "产品代码不能为空")
    private String code;

    @NotBlank(message = "产品名称不能为空")
    private String name;

    @NotNull(message = "投资周期不能为空")
    private Integer cycle;

    @NotNull(message = "基准利率不能为空")
    @Schema(description = "年化基准利率，如0.0450代表4.5%")
    private BigDecimal baseRate;

    @NotNull(message = "风险等级不能为空")
    private Integer riskLevel;

    @NotNull(message = "总份额不能为空")
    private BigDecimal totalStock;
}