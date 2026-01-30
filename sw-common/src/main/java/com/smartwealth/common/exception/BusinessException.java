package com.smartwealth.common.exception;

import com.smartwealth.common.result.ResultCode;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    // 异常编号
    private final Integer code;
    /**
     * 直接传枚举
     * throw new BusinessException(ResultCode.USER_NOT_EXIST);
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }
    /**
     * 只有消息，默认用 500 (偶尔用)
     * throw new BusinessException("系统有点不对劲");
     */
    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.FAILURE.getCode();
    }
}