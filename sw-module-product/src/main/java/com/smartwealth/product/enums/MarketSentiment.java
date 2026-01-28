package com.smartwealth.product.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MarketSentiment {
    EXTREME_BULL("EXTREME_BULL",4, 1.8, "疯牛 - 收益率大幅飙升"),
    BULL("BULL",3, 1.3, "小牛 - 收益率稳步上涨"),
    SIDEWAYS("SIDEWAYS",2, 1.0, "震荡 - 市场横盘波动"),
    BEAR("BEAR", 1,0.7, "小熊 - 收益率普遍下跌"),
    EXTREME_BEAR("EXTREME_BEAR",0, 0.2, "大熊 - 市场哀鸿遍野");

    private final String code;
    private final  int order;//算法距离
    private final Double coefficient; // 影响因子
    private final String description;

    public static MarketSentiment getByCode(String code) {
        for (MarketSentiment s : values()) {
            if (s.getCode().equalsIgnoreCase(code)) return s;
        }
        return SIDEWAYS; // 默认震荡
    }
    public static MarketSentiment getByOrder(int order) {
        for (MarketSentiment e : values()) {
            if (e.order == order) return e;
        }
        return SIDEWAYS; // 默认
    }
}