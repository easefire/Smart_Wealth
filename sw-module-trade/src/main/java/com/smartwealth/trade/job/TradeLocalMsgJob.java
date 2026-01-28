package com.smartwealth.trade.job;

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
        // 1. 查待发送
        List<TradeLocalMsg> list = msgMapper.selectPendingMsgs();

        for (TradeLocalMsg msg : list) {
            try {
                // 2. 区分类型并补发
                if ("ex.trade.purchase".equals(msg.getTopic())) {
                    log.info("Job 补发申购请求: {}", msg.getMsgId());
                    tradeProducer.sendPurchaseMessage(msg);
                }
                else if (RabbitConfig.REDEMPTION_EXCHANGE.equals(msg.getTopic())) {
                    log.info("Job 补发赎回请求: {}", msg.getMsgId());
                    tradeProducer.sendRedemptionMessage(msg);
                }
                else {
                    log.warn("未知 Topic，跳过: {}", msg.getTopic());
                }

            } catch (Exception e) {
                // 3. 失败处理
                msg.setRetryCount(msg.getRetryCount() + 1);
                msg.setNextRetry(LocalDateTime.now().plusMinutes(1));
                msgMapper.updateById(msg);
            }
        }
    }
}
