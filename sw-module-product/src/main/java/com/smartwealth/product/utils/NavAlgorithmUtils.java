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

        // 1. 如果是 R1 固收类产品，直接脱离大盘逻辑，走独立的低波动正收益模型
        if (riskLevel != null && riskLevel == 1) {
            // R1 的年化收益大概在 2%~3%，折合日均收益约万分之一 (0.0001)
            // 波动极小，用绝对的正态分布即可，确保不亏损
            double r1Yield = 0.0001 + (random.nextGaussian() * 0.00001);
            return BigDecimal.valueOf(Math.max(0, r1Yield)).setScale(8, RoundingMode.HALF_UP);
        }

        double limit = 0.10 * riskAmplifier;
        finalChange = Math.max(-limit, Math.min(limit, finalChange));


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
