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
        // 1. 记录任务开始
        // 注意：这里打印的日期是 "昨天"，明确告诉运维我们在算哪一天的账
        LocalDate bizDate = LocalDate.now().minusDays(1);
        XxlJobHelper.log("========== 开始执行每日收益结算 ==========");
        XxlJobHelper.log("当前物理时间: " + System.currentTimeMillis());
        XxlJobHelper.log("业务结算日期 (T-1): " + bizDate);

        long startTime = System.currentTimeMillis();

        try {
            // 2. 调用核心业务逻辑
            // Service 内部已经处理了 minusDays(1) 的逻辑，这里直接调即可
            tradeOrderService.executeDailySettlement();

            // 3. 记录成功状态
            long costTime = System.currentTimeMillis() - startTime;
            XxlJobHelper.log("结算成功，耗时: " + costTime + "ms");
            XxlJobHelper.handleSuccess("业务日期[" + bizDate + "]结算完成");

        } catch (Exception e) {
            // 4. 异常处理
            log.error("每日收益结算失败", e);

            // 记录详细堆栈到 XXL-JOB 日志控制台，方便排查
            XxlJobHelper.log(e);

            // 标记任务失败，触发 XXL-JOB 的邮件/钉钉报警
            XxlJobHelper.handleFail("结算异常: " + e.getMessage());
        } finally {
            XxlJobHelper.log("========== 结算任务结束 ==========");
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

            // 面试加分项：你可以在这里加一行注释
            // TODO: 发送钉钉/飞书报警给开发团队
        }

        log.info("========== 交易体检结束，发现 {} 个异常订单 ==========", errorList.size());
    }
}