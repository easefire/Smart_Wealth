package com.smartwealth.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "修改用户信息参数")
public class UserUpdateDTO {
    @Schema(description = "新用户名")
    private String username;

    @Schema(description = "新手机号")
    private String phone;

    @Schema(description = "旧密码（修改密码时必填）")
    private String oldPassword;

    @Schema(description = "新密码")
    private String newPassword;
}