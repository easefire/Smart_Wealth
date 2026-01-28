package com.smartwealth.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartwealth.common.annotations.LogAudit;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.product.entity.MarketSentimentLog;
import com.smartwealth.product.enums.MarketSentiment;
import com.smartwealth.product.mapper.MarketLogMapper;
import com.smartwealth.product.service.IMarketService;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Random;

/**
 * <p>
 * 市场情绪日志表 服务实现类
 * </p>
 *
 * @author Fire
 * @since 2026-01-12
 */
@Service
public class MarketServiceImpl extends ServiceImpl<MarketLogMapper, MarketSentimentLog> implements IMarketService {
    private final Random random = new Random();
    @Autowired
    private MarketLogMapper marketLogMapper;
    @Autowired
    private RedisService redisService;


    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "市场情绪", operation = "修改情绪设定")
    public void saveSentiment(MarketSentiment sentiment, String operator) {
        // 【核心修改】因为任务是凌晨（如 01:00）执行，所以我们要记录的是"昨天"的情绪
        LocalDate recordDate = LocalDate.now().minusDays(1);

        // 1. 查询数据库：我们要找的是"昨天"是否已经生成过（支持重跑任务）
        MarketSentimentLog log = this.getOne(new LambdaQueryWrapper<MarketSentimentLog>()
                .eq(MarketSentimentLog::getRecordDate, recordDate)); // <--- 改这里

        if (log == null) {
            log = new MarketSentimentLog();
            log.setRecordDate(recordDate); // <--- 改这里，落库的时间必须是昨天
        }

        log.setScenarioCode(sentiment.getCode());
        log.setOperator(operator);
        log.setDescription(sentiment.getDescription());

        this.saveOrUpdate(log);

        // 2. 更新 Redis
        // 此时 Redis 里的 LATEST 代表的是"最近一个已完结交易日(昨天)"的情绪
        redisService.set(RedisKeyConstants.KEY_MARKET_SENTIMENT_LATEST, sentiment.getCode());
    }

    @Override
    public MarketSentimentLog selectLatestOne() {
        return marketLogMapper.selectOne(new LambdaQueryWrapper<MarketSentimentLog>()
                .orderByDesc(MarketSentimentLog::getRecordDate)
                .last("LIMIT 1"));
    }

    /**
     * 计算明天的市场情绪
     *
     * @param todaySentiment 今天的（或最近一次的）市场情绪
     * @return 明天的情绪
     */
    @Override
    public MarketSentiment simulateNextSentiment(MarketSentiment todaySentiment) {
        // 如果是第一次运行（没有昨天的数据），默认从震荡开始
        if (todaySentiment == null) {
            return MarketSentiment.SIDEWAYS;
        }

        // 1. 黑天鹅事件判定 (Black Swan Event)
        // 设定 2% 的概率发生黑天鹅，完全无视规律，随机突变
        if (random.nextDouble() < 0.02) {
            return randomBlackSwan(todaySentiment);
        }

        // 2. 正常市场演化 (基于权重的随机游走)
        return evolveNormally(todaySentiment);
    }

    /**
     * 正常演化逻辑：基于当前状态，高概率维持或小幅变动，低概率大幅跳跃
     */

    public MarketSentiment evolveNormally(MarketSentiment current) {
        int currentOrder = current.getOrder();

        // 权重累加器
        int totalWeight = 0;
        int[] weights = new int[5]; // 对应 0~4 五个状态

        for (MarketSentiment target : MarketSentiment.values()) {
            int targetOrder = target.getOrder();
            int distance = Math.abs(currentOrder - targetOrder);

            // === 核心权重算法 ===
            // 距离 0 (维持现状): 权重 50
            // 距离 1 (相邻状态): 权重 20
            // 距离 2 (跳一级):   权重 5
            // 距离 3 (跳两级):   权重 1
            // 距离 4 (天地板):   权重 0 (正常情况下不直接天地板，除非黑天鹅)
            int weight;
            switch (distance) {
                case 0:
                    weight = 50;
                    break; // 最大的概率是维持现状
                case 1:
                    weight = 20;
                    break; // 其次是变成相邻状态
                case 2:
                    weight = 5;
                    break;  // 小概率跳跃
                case 3:
                    weight = 1;
                    break;  // 极小概率
                default:
                    weight = 0;
                    break; // 不允许直接反转
            }

            weights[targetOrder] = weight;
            totalWeight += weight;
        }

        // 轮盘赌算法选择下一个状态
        int randomVal = random.nextInt(totalWeight);
        int currentSum = 0;
        for (int i = 0; i < weights.length; i++) {
            currentSum += weights[i];
            if (randomVal < currentSum) {
                return MarketSentiment.getByOrder(i);
            }
        }

        return current; // 兜底
    }

    /**
     * 黑天鹅逻辑：倾向于跳向离当前状态比较远的地方
     */
    private MarketSentiment randomBlackSwan(MarketSentiment current) {
        // 简单粗暴：完全随机，或者倾向于反转
        // 这里我们做一个简单的完全随机，模拟市场的不可预测性
        int randomOrder = random.nextInt(5);
        return MarketSentiment.getByOrder(randomOrder);
    }
}

