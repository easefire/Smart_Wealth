package com.smartwealth.user.controller.admin;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartwealth.common.context.UserContext;
import com.smartwealth.user.entity.AuditLog;
import com.smartwealth.common.result.Result;
import com.smartwealth.user.dto.UserLoginDTO;
import com.smartwealth.user.vo.AdminUserVO;
import com.smartwealth.user.enums.UserStatusEnum;
import com.smartwealth.user.service.IAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/sw/admin/user")
@Slf4j
@Tag(name = "管理端-用户管理")
public class AdminUserController {
    @Autowired
    private IAdminService adminService;

    @Operation(summary = "管理员注册")
    @PostMapping("/register")
    public Result<String> register(@Validated @RequestBody UserLoginDTO registerDto) {
        log.info("管理员注册开始，用户名: {}", registerDto.getUsername());
        adminService.register(registerDto);
        return Result.success("注册成功");
    }

    @Operation(summary = "管理员登录")
    @PostMapping("/login")
    public Result<String> login(@Validated @RequestBody UserLoginDTO dto) {
        String token = adminService.login(dto);
        return Result.success(token);
    }

    @Operation(summary = "管理员登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        Long userId= UserContext.getUserId();
        adminService.logout(userId);
        return Result.success();
    }

    // 统计注册人数
    @Operation(summary = "统计注册用户", description = "可按日期统计注册用户数量，若不传日期则统计总注册用户数")
    @GetMapping("/count")
    public Result<Long> getRegisterCount(
            @Parameter(description = "查询日期", example = "2026-01-18")
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(adminService.countTotalUsers(date));
    }

    @Operation(summary = "获取用户分页列表")
    @GetMapping("/page")
    public Result<IPage<AdminUserVO>> getUserPage(
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "10") int size) {

        IPage<AdminUserVO> result = adminService.listUsersForAdmin(current, size);
        return Result.success(result);
    }

    @Operation(summary = "冻结/解冻用户状态")
    @PutMapping("/status/{userId}")
    public Result<Void> freezeOrUnfreeze(@PathVariable(value = "userId") Long userId, @RequestParam(value = "status") Integer statusCode) {
        UserStatusEnum status = UserStatusEnum.fromCode(statusCode);
        boolean success = adminService.updateUserStatus(userId, status);
        if (success) {
            return Result.success();
        }
        return Result.fail("操作失败，请检查用户是否存在");
    }

    @Operation(summary = "查看审计日志")
    @GetMapping("/audit-logs")
    public Result<IPage<AuditLog>> getAuditLogs(
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        IPage<AuditLog> result = adminService.getAuditLogs(current, size);
        return Result.success(result);
    }

}

