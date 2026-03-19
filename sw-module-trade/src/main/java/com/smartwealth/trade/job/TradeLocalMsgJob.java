package com.smartwealth.trade.job;

import cn.hutool.core.collection.CollUtil;
import com.smartwealth.common.configuration.RabbitConfig;
import com.smartwealth.trade.entity.TradeLocalMsg;
import com.smartwealth.trade.mapper.TradeLocalMsgMapper;
import com.smartwealth.trade.mq.producer.TradeMessageProducer;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class TradeLocalMsgJob {

    @Autowired
    private TradeLocalMsgMapper msgMapper;
    @Autowired
    private TradeMessageProducer tradeProducer;

    @XxlJob("tradeMsgRetryJob")
    public void execute() {
        // 1. 防爆仓：每次最多只查 500 条，并且必须满足重试时间
        // 对应的 SQL: WHERE status = 0 AND (next_retry IS NULL OR next_retry <= NOW()) LIMIT 500
        List<TradeLocalMsg> list = msgMapper.selectPendingMsgs();

        if (CollUtil.isEmpty(list)) {
            return;
        }

        log.info("【MQ补偿任务】本次扫描到 {} 条待发送消息", list.size());

        for (TradeLocalMsg msg : list) {
            try {
                // 2. 路由发送
                if ("ex.trade.purchase".equals(msg.getTopic())) {
                    tradeProducer.sendPurchaseMessage(msg);
                } else if (RabbitConfig.REDEMPTION_EXCHANGE.equals(msg.getTopic())) {
                    tradeProducer.sendRedemptionMessage(msg);
                } else {
                    log.warn("未知 Topic，直接标记为失败: {}", msg.getTopic());
                    markAsDeadLetter(msg, "未知的 Topic 类型");
                }

                // 注意：发送成功绝不在这里改 status，全权交由 ConfirmCallback 处理

            } catch (Exception e) {
                log.error("Job 补发消息失败，msgId: {}", msg.getMsgId(), e);

                // 3. 物理熔断机制 (防无限死循环)
                int currentRetry = msg.getRetryCount() == null ? 0 : msg.getRetryCount();

                if (currentRetry >= 5) { // 阈值：5次
                    // 彻底熔断，状态置为 2，等待人工后台处理
                    markAsDeadLetter(msg, "重试超限: " + e.getMessage());
                } else {
                    // 阶梯式延迟计算 (1分钟, 2分钟, 4分钟...)
                    int delayMinutes = (int) Math.pow(2, currentRetry);
                    msg.setRetryCount(currentRetry + 1);
                    msg.setNextRetry(LocalDateTime.now().plusMinutes(delayMinutes));
                    // 状态依然是 0，只是更新了时间和次数
                    msgMapper.updateById(msg);
                }
            }
        }
    }

    // 提取的私有方法：标记为死信 (状态 2)
    private void markAsDeadLetter(TradeLocalMsg msg, String reason) {
        msg.setStatus(2); // 2: 发送失败/死信
        msgMapper.updateById(msg);

        // 【客观必须】这里应该接入企业微信/钉钉告警机制
        // alertService.sendUrgentMsg("本地消息重试超限，请立刻人工介入！MsgId: " + msg.getMsgId());
    }
}
