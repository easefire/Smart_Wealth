package com.smartwealth.product;

import com.smartwealth.product.enums.MarketSentiment;
import com.smartwealth.product.utils.NavAlgorithmUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 净值算法的"硬约束"测试。算法本身有随机性，所以不断言精确值，
 * 而是断言"无论怎么随机都不能违反的金融规则"：
 *   - R1 固收类不能产生负收益；
 *   - 任何风险等级都不能突破日内涨跌停 (10% * 风险倍数)；
 *   - 大熊市的期望值是负的（多次采样的均值应小于 0）。
 */
class NavAlgorithmUtilsTest {

    @RepeatedTest(50)
    @DisplayName("R1 固收：单日永远不亏")
    void r1_never_loses_money() {
        BigDecimal change = NavAlgorithmUtils.calculateDailyChange(MarketSentiment.EXTREME_BEAR, 1);
        assertNotNull(change);
        assertTrue(change.signum() >= 0,
                "R1 固收在大熊市出现了负收益: " + change);
    }

    @RepeatedTest(100)
    @DisplayName("R5 高风险：单日波动不会突破 ±25% (10% × 2.5 倍杠杆)")
    void r5_change_within_band() {
        BigDecimal change = NavAlgorithmUtils.calculateDailyChange(MarketSentiment.EXTREME_BULL, 5);
        BigDecimal abs = change.abs();
        assertTrue(abs.compareTo(new BigDecimal("0.25")) <= 0,
                "R5 突破涨跌停: " + change);
    }

    @Test
    @DisplayName("大熊市样本均值应为负")
    void extreme_bear_mean_is_negative() {
        // 不需要做完整统计，简单求和判断方向即可
        BigDecimal sum = BigDecimal.ZERO;
        int n = 500;
        for (int i = 0; i < n; i++) {
            sum = sum.add(NavAlgorithmUtils.calculateDailyChange(MarketSentiment.EXTREME_BEAR, 3));
        }
        assertTrue(sum.signum() < 0,
                "大熊市跑了 " + n + " 次，均值竟然 >= 0: " + sum);
    }

    @Test
    @DisplayName("riskLevel 为 null 时不抛 NPE，使用默认放大倍数")
    void null_risk_level_does_not_npe() {
        BigDecimal change = NavAlgorithmUtils.calculateDailyChange(MarketSentiment.SIDEWAYS, null);
        assertNotNull(change);
    }
}
