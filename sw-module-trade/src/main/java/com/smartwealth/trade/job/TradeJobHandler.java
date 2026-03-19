package com.smartwealth.trade.job;


import com.smartwealth.trade.dto.TradeCheckDTO;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.smartwealth.trade.service.ITradeOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@Slf4j
public class TradeJobHandler {

    @Autowired
    private ITradeOrderService tradeOrderService;

    /**
     * 每日收益结算任务
     * 逻辑：每天凌晨 02:00 执行，结算“昨天”的收益
     */
    @XxlJob("dailySettlementJobHandler")
    public void dailySettlementJobHandler() {
        LocalDate bizDate = LocalDate.now().minusDays(1);

        // 1. 抓取分片参数（如果单机直接运行，默认 0 和 1）
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        if (shardTotal == 0) {
            shardIndex = 0;
            shardTotal = 1;
        }

        XxlJobHelper.log("========== 收益结算开始 | 分片: {}/{} | 业务日期: {} ==========", shardIndex, shardTotal, bizDate);
        long startTime = System.currentTimeMillis();

        try {
            // 2. 调用核心业务逻辑
            tradeOrderService.executeDailySettlementWithSharding(bizDate, shardIndex, shardTotal);

            long costTime = System.currentTimeMillis() - startTime;
            XxlJobHelper.handleSuccess(String.format("分片[%d/%d] 结算成功，耗时: %d ms", shardIndex, shardTotal, costTime));

        } catch (Exception e) {
            log.error("收益结算严重异常", e);
            XxlJobHelper.log(e);
            XxlJobHelper.handleFail("结算异常: " + e.getMessage());
        } finally {
            XxlJobHelper.log("========== 收益结算结束 ==========");
        }
    }

    /**
     * 每日交易收益审计任务
     * 建议时间：每天凌晨 04:30 (资产对账之后)
     */
    @XxlJob("dailyTradeCheckJob")
    public void dailyTradeCheckJob() {
        log.info("========== 开始执行交易模块(收益)体检 ==========");

        // 1. 执行 SQL，拉取异常订单
        List<TradeCheckDTO> errorList = tradeOrderService.checkIncomeConsistency();

        // 2. 没问题直接收工
        if (errorList == null || errorList.isEmpty()) {
            log.info("✅ 交易收益核对完美通过。");
            return;
        }

        // 3. 有问题，疯狂报警
        for (TradeCheckDTO error : errorList) {
            log.warn("🚨【收益不平警告】订单ID: [{}], 账面累计收益: [{}], 实际流水总和: [{}]",
                    error.getOrderId(),
                    error.getOrderIncome(),
                    error.getRealSumIncome());


        }

        log.info("========== 交易体检结束，发现 {} 个异常订单 ==========", errorList.size());
    }
}