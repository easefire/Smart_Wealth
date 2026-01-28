package com.smartwealth.trade.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.smartwealth.common.configuration.RabbitConfig;
import com.smartwealth.trade.service.ITradeOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TradeResultConsumer {

    @Autowired
    private ITradeOrderService orderService;

    /**
     * 监听资产模块发回的扣款结果 [cite: 2026-01-24]
     */
    @RabbitListener(queues = RabbitConfig.RESULT_QUEUE)
    public void onResult(String message) {
        log.info("【交易模块】接收到资产扣款回执: {}", message);
        JSONObject result = JSON.parseObject(message);

        Long orderId = result.getLong("orderId");
        String type = result.getString("type");
        boolean success = result.getBoolean("success");
        String reason = result.getString("reason");

        // 调用 Service 处理最终状态 [cite: 2026-01-24]
        switch(type) {
            case "PURCHASE":
                log.info("处理申购订单 ID: {} 的扣款结果", orderId);
                orderService.handlePurchaseResult(orderId, success, reason);
                break;
            case "REDEEM":
                log.info("处理赎回订单 ID: {} 的入账结果", orderId);
                orderService.handleRedemptionResult(orderId, success, reason);
                break;
            default:
                log.warn("未知的订单类型: {}，订单 ID: {}", type, orderId);
                return;
        }

    }
}