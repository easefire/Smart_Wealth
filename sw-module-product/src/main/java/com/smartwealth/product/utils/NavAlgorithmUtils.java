package com.smartwealth.product.utils;

import com.smartwealth.product.enums.MarketSentiment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

public class NavAlgorithmUtils {

    private static final Random random = new Random();

    /**
     * 计算今日净值涨跌幅
     *
     * @param sentiment 市场情绪
     * @param riskLevel 产品风险等级 (1-5)
     * @return 涨跌幅 (例如 0.0123 代表涨 1.23%)
     */
    public static BigDecimal calculateDailyChange(MarketSentiment sentiment, Integer riskLevel) {
        // 1. 获取市场基准趋势 (Mean) 和 市场波动率 (StdDev)
        double marketMean = 0.0;
        double marketStdDev = 0.01; // 默认大盘标准差 1%

        switch (sentiment) {
            case EXTREME_BULL: // 疯牛：平均涨 2.5%，波动大
                marketMean = 0.025;
                marketStdDev = 0.02;
                break;
            case BULL:         // 小牛：平均涨 0.8%
                marketMean = 0.008;
                marketStdDev = 0.01;
                break;
            case SIDEWAYS:     // 震荡：平均 0
                marketMean = 0.0;
                marketStdDev = 0.008;
                break;
            case BEAR:         // 小熊：平均跌 0.8%
                marketMean = -0.008;
                marketStdDev = 0.012;
                break;
            case EXTREME_BEAR: // 大熊：平均跌 2.5%，恐慌导致波动极大
                marketMean = -0.025;
                marketStdDev = 0.03;
                break;
        }

        // 2. 引入“个股特异性” (高斯分布/正态分布)
        // random.nextGaussian() 返回均值0，标准差1的随机数
        // 这一步实现了“大涨的时候不可能所有都涨”：
        // 即使 marketMean 是 +0.025，如果随机出 -2.0，结果依然可能是负的。
        double randomFactor = random.nextGaussian();

        // 原始波动 = 市场均值 + (随机因子 * 市场波动率)
        double rawChange = marketMean + (randomFactor * marketStdDev);

        // 3. 引入“风险等级放大器” (Risk Amplifier)
        double riskAmplifier = getRiskAmplifier(riskLevel);

        // 4. 计算最终涨跌幅
        double finalChange = rawChange * riskAmplifier;

        // 5. 特殊处理 R1 (R1通常是保本理财，不允许大跌，做个保护)
        if (riskLevel != null && riskLevel == 1) {
            // R1 产品波动极小，且大概率非负
            finalChange = Math.abs(finalChange * 0.1);
        }

        // 6. 兜底限制 (防止一天跌没了，设定涨跌停板 10% 或 20%)
        finalChange = Math.max(-0.10, Math.min(0.10, finalChange));

        return BigDecimal.valueOf(finalChange).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * 根据风险等级获取放大倍数
     */
    private static double getRiskAmplifier(Integer riskLevel) {
        if (riskLevel == null) return 1.0;
        switch (riskLevel) {
            case 1: return 0.2; // R1: 波动只有大盘的 20%
            case 2: return 0.6; // R2: 波动是大盘的 60%
            case 3: return 1.0; // R3: 跟随大盘
            case 4: return 1.5; // R4: 1.5倍杠杆
            case 5: return 2.5; // R5: 2.5倍杠杆 (疯狗模式)
            default: return 1.0;
        }
    }
}
