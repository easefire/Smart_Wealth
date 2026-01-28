package com.smartwealth.trade.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum TradeStatusEnum implements IEnum<Integer> {

    PENDING(0, "PENDING"),
    HOLDING(1, "HOLDING"),       // 你定义的 1
    REDEEMING(2, "REDEEMING"),   // 你定义的 2
    REDEEMED(3, "REDEEMED"),      // 你定义的 3
    CLOSED(4, "CLOSED");        // 你定义的 4

    @EnumValue
    private final int value;
    @JsonValue
    private final String desc;

    TradeStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    @Override
    public Integer getValue() {
        return this.value;
    }

    @JsonValue // 确保 Swagger 和前端接口返回的是描述文字或指定格式
    public String getDesc() {
        return this.desc;
    }
}
