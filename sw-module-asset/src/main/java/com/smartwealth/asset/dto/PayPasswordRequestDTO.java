package com.smartwealth.asset.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "设置支付密码请求对象")
public class PayPasswordRequestDTO {

    @Schema(description = "支付密码", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "支付密码不能为空")
    @Size(min = 6, max = 6, message = "支付密码必须为6位数字")
    private String password;
}
