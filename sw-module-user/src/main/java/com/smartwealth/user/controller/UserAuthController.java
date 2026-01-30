package com.smartwealth.user.controller;

import com.smartwealth.common.context.UserContext;
import com.smartwealth.common.result.Result;
import com.smartwealth.user.dto.UserLoginDTO;
import com.smartwealth.user.dto.UserRegisterDTO;
import com.smartwealth.user.dto.UserUpdateDTO;
import com.smartwealth.user.vo.UserVO;
import com.smartwealth.user.service.IUserAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 用户认证敏感表 前端控制器
 * </p>
 *
 * @author Fire
 * @since 2026-01-12
 */
@RestController
@RequestMapping("/sw/user/auth")
@Slf4j
@Tag(name = "用户端-用户认证")
@PreAuthorize("hasRole('USER')")
public class UserAuthController {

    @Autowired
    private IUserAuthService authService;

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    @PreAuthorize("permitAll()")
    public Result<UserVO> register(@Validated @RequestBody UserRegisterDTO registerDto) {
        log.info("用户注册开始，用户名: {}", registerDto.getUsername());
        UserVO userVo = authService.register(registerDto);
        return Result.success(userVo);
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public Result<String> login(@Validated @RequestBody UserLoginDTO dto) {
        String token = authService.login(dto);
        return Result.success(token);
    }

    @Operation(summary = "修改个人信息")
    @PutMapping("/update")
    public Result<Void> updateInfo(@RequestBody UserUpdateDTO dto) {
        authService.updateUserInfo(dto);
        return Result.success();
    }

    @Operation(summary = "账户注销")
    @DeleteMapping("/delete")
    public Result<Void> deleteAccount() {
        Long userId = UserContext.getUserId();
        authService.deleteAccount(userId);
        return Result.success();
    }

    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        Long userId = UserContext.getUserId();
        authService.logout(userId);
        return Result.success();
    }
}


