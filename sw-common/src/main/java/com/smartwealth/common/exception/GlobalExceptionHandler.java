package com.smartwealth.common.exception;

import com.smartwealth.common.result.Result;
import com.smartwealth.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;

/**
 * 全局异常处理器
 * 作用：拦截 Controller 层抛出的各种异常，统一包装成 Result 对象返回给前端
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    /**
     * 1. 拦截业务异常 (最常用)
     * 场景：代码中手动 throw new BusinessException(ResultCode.USER_FROZEN);
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        // 业务异常通常是预期内的，记录 warn 日志即可，不用打印堆栈吓唬自己
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 2. 拦截参数校验异常 (Validation)
     * 场景：DTO 里的 @NotNull, @Size 校验失败时
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidationException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        // 获取第一条错误信息 (比如 "手机号不能为空")
        String msg = Objects.requireNonNull(bindingResult.getFieldError()).getDefaultMessage();

        log.warn("参数校验失败: {}", msg);
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    /**
     * 2.1 拦截另一种参数异常 (通常是 Get 请求参数绑定失败)
     */
    @ExceptionHandler(BindException.class)
    public Result<?> handleBindException(BindException e) {
        String msg = Objects.requireNonNull(e.getBindingResult().getFieldError()).getDefaultMessage();
        log.warn("参数绑定失败: {}", msg);
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    /**
     * 3. 拦截所有未知的系统异常 (兜底)
     * 场景：NullPointerException, IndexOutOfBoundsException, SQL报错等
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        // 🚨 系统异常必须打印完整堆栈 (error)，方便排查 BUG
        log.error("系统发生未知错误", e);

        // 返回模糊的错误提示，不要把 "NullPoint..." 直接展示给用户看，不安全也不友好
        return Result.fail(ResultCode.FAILURE);
    }
}