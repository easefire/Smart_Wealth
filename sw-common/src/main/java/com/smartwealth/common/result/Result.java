package com.smartwealth.common.result;

import lombok.Data;
import java.io.Serializable;

/**
 * 统一返回结果类
 * 核心改动：增加了对 ResultCode 枚举的支持，增加了无参 success 方法
 */
@Data
public class Result<T> implements Serializable {

    private Integer code; // 状态码
    private String msg;   // 提示信息
    private T data;       // 数据载体

    // 私有构造，强制通过静态方法创建
    private Result() {}

    // ============================
    // 🟢 成功响应的方法
    // ============================

    /**
     * 成功 - 无数据返回 (例如：删除成功，修改密码成功)
     */
    public static <T> Result<T> success() {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /**
     * 成功 - 带数据返回 (例如：查询用户详情)
     */
    public static <T> Result<T> success(T data) {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    // ============================
    // 🔴 失败响应的方法
    // ============================

    /**
     * 失败 - 使用标准枚举 (推荐！)
     * 用法：Result.fail(ResultCode.USER_NOT_EXIST);
     */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return build(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /**
     * 失败 - 自定义 Code 和 Msg (GlobalExceptionHandler 用)
     * 用法：Result.fail(4001, "余额不足");
     */
    public static <T> Result<T> fail(Integer code, String msg) {
        return build(code, msg, null);
    }

    /**
     * 失败 - 只传 Msg (默认用 500 系统错误)
     * 用法：Result.fail("系统爆炸了");
     */
    public static <T> Result<T> fail(String msg) {
        return build(ResultCode.FAILURE.getCode(), msg, null);
    }

    // ============================
    // 🔧 内部构建方法
    // ============================
    private static <T> Result<T> build(Integer code, String msg, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(data);
        return result;
    }
}