package com.smartwealth.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.user.entity.AdminUser;
import jakarta.validation.constraints.NotBlank;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 管理员用户 Mapper 接口
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {

    AdminUser selectByUsername(@NotBlank(message = "用户名不能为空") String username);
}
