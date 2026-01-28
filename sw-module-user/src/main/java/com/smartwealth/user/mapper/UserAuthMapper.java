package com.smartwealth.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.user.entity.UserAuth;
import com.smartwealth.user.entity.UserBase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * <p>
 * 用户认证敏感表 Mapper 接口
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface UserAuthMapper extends BaseMapper<UserAuth> {

    boolean existsByUsername(@NotBlank(message = "用户名不能为空") @Size(min = 4, max = 20, message = "用户名长度需在4-20位之间") String username);

    boolean existsByPhone(@NotBlank(message = "手机号不能为空") @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone);

    UserBase selectByUsername(@NotBlank(message = "用户名不能为空") String username);

}

