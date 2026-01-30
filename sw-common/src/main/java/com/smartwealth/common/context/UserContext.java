package com.smartwealth.common.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Optional;

/**
 * 用户上下文
 * 核心改变：使用ThreadLocal 存储对象
 */
public class UserContext {
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    // 1. 定义用户信息类
    public static class UserInfo {
        private Long id;       // 用户ID 或 管理员ID
        private String role;   // 角色: "USER" / "ADMIN"
    }
    // 2. 只用一个 ThreadLocal
    private static final ThreadLocal<UserInfo> threadLocal = new ThreadLocal<>();

    /**
     * 设置当前用户信息
     */
    public static void set(Long id, String role) {
        threadLocal.set(new UserInfo(id, role));
    }
    public static void set(UserInfo userInfo) {
        threadLocal.set(userInfo);
    }
    /**
     * 获取当前用户信息
     */
    public static UserInfo get() {
        return threadLocal.get();
    }

    /**
     * 获取用户ID
     */
    public static Long getUserId() {
        return Optional.ofNullable(threadLocal.get())
                .map(UserInfo::getId)
                .orElse(null);
    }
    /**
     * 清除当前线程的用户信息
     */
    public static void clear() {
        threadLocal.remove();
    }
}