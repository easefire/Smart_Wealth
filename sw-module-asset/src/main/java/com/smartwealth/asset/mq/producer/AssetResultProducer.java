package com.smartwealth.asset.mq.producer;

import com.alibaba.fastjson.JSON;
import com.smartwealth.common.configuration.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class AssetResultProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送扣款结果回执给交易模块 [cite: 2026-01-24]
     */
    public void sendResult(Long orderId,String type, boolean success, String reason) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("orderId", orderId);
        resultMap.put("type", type);
        resultMap.put("success", success);
        resultMap.put("reason", reason);

        String json = JSON.toJSONString(resultMap);

        // 发回到结果队列 [cite: 2026-01-24]
        rabbitTemplate.convertAndSend(RabbitConfig.RESULT_EXCHANGE, RabbitConfig.RESULT_ROUTING_KEY, json);

        log.info("【资产模块】结果回执已发送，订单ID: {}, 结果: {}", orderId, success ? "成功" : "失败");
    }
}