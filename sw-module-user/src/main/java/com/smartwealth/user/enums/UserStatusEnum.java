package com.smartwealth.user.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 用户账户状态枚举
 */
@Getter
public enum UserStatusEnum {

    /**
     * 1. 初始注册状态：已填写用户名、密码、手机号。
     * 权限：仅能登录、浏览。
     */
    REGISTERED(0, "已注册（待实名测评）"),

    /**
     * 2. 身份认证状态：已完成实名认证和风险测评。
     * 权限：可查看产品详情，但不可交易。
     */
    VERIFIED(1, "已实名（待风险测评）"),

    TESTED(2, "已测评（待绑定银行卡）"),
    /**
     * 3. 激活状态：已绑定银行卡。
     * 权限：全功能开放，可进行购买交易。
     */
    ACTIVE(3, "已激活（可购买交易）"),

    /**
     * 4. 冻结状态：账户被限制。
     * 权限：禁止登录或禁止所有交易操作。
     */
    FROZEN(-1, "已冻结（账号异常）"),

    DELETED(-2, "已删除（用户注销）");

    @EnumValue
    @JsonValue
    private final int code;
    private final String description;

    UserStatusEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UserStatusEnum fromCode(int code) {
        for (UserStatusEnum status : UserStatusEnum.values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        // 如果找不到，可以返回 null 或者抛出自定义异常
        return null;
    }

    /**
     * 根据 code 获取对应的枚举
     */
    public static UserStatusEnum getByCode(int code) {
        for (UserStatusEnum status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return null;
    }

    /**
     * 业务逻辑封装：判断当前状态是否允许购买
     * 只有状态为 ACTIVE (2) 时才允许购买
     */
    public boolean canPurchase() {
        return this == ACTIVE;
    }

    /**
     * 业务逻辑封装：判断账户是否正常（未被冻结）
     */
    public boolean isNormal() {
        return this != FROZEN;
    }
}
