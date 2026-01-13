package com.smartwealth.common.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Optional;

/**
 * 用户上下文 - 升级版
 * 核心改变：使用一个 ThreadLocal 存储对象，而不是分散存储变量
 */
public class UserContext {

    // 1. 定义一个内部类，囊括所有你想存的信息
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UserInfo {
        private Long id;       // 用户ID 或 管理员ID
        private String role;   // 角色: "USER" / "ADMIN"
        // 你以后可以在这加：private String username;
    }

    // 2. 只用一个 ThreadLocal
    private static final ThreadLocal<UserInfo> threadLocal = new ThreadLocal<>();

    // ============================
    // 🟢 存取方法
    // ============================

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

    // ============================
    // 🟡 快捷获取方法 (语法糖)
    // ============================

    /**
     * 获取用户ID (做了判空处理)
     */
    public static Long getUserId() {
        return Optional.ofNullable(threadLocal.get())
                .map(UserInfo::getId)
                .orElse(null);
    }

    /**
     * 获取角色
     */
    public static String getRole() {
        return Optional.ofNullable(threadLocal.get())
                .map(UserInfo::getRole)
                .orElse(null);
    }

    /**
     * 判断当前是否是管理员 (给 Service 层做双重保险用)
     */
    public static boolean isAdmin() {
        String role = getRole();
        return "ADMIN".equals(role);
    }

    // ============================
    // 🔴 清理方法
    // ============================
    public static void clear() {
        threadLocal.remove();
    }
}