package com.smartwealth.asset.mq.producer;

import com.alibaba.fastjson.JSON;
import com.smartwealth.common.configuration.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class AssetResultProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送扣款结果回执给交易模块（带 CorrelationData，便于 ConfirmCallback 更新本地消息）。
     *
     * 【BUGFIX-#13】之前 convertAndSend 完全没传 CorrelationData，Broker 回执无法路由到具体本地消息，
     *              本地消息发送状态永远依赖“发完立刻置 1”的乐观假设，
     *              在 Broker 写入失败/网络抖动场景下会丢消息。
     *
     * @param msgId    与 AssetLocalMsg.msg_id 完全一致的业务消息号（由调用方负责生成 & 入库）
     * @param orderId  业务订单/请求号
     * @param type     业务类型（PURCHASE / REDEEM）
     * @param success  业务结果
     * @param reason   失败原因（成功传 null）
     */
    public void sendResult(String msgId, Long orderId, String type, boolean success, String reason) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("orderId", orderId);
        resultMap.put("type", type);
        resultMap.put("success", success);
        resultMap.put("reason", reason);

        String json = JSON.toJSONString(resultMap);

        MessageProperties properties = new MessageProperties();
        properties.setMessageId(msgId);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), properties);

        CorrelationData correlationData = new CorrelationData(msgId);

        rabbitTemplate.convertAndSend(
                RabbitConfig.RESULT_EXCHANGE,
                RabbitConfig.RESULT_ROUTING_KEY,
                message,
                correlationData
        );

        log.info("【资产模块】结果回执已推送至 MQ，msgId: {}, 订单ID: {}, 结果: {}",
                msgId, orderId, success ? "成功" : "失败");
    }

    /**
     * 兼容老调用：未指定 msgId 时按规则推导，避免一次性改完所有调用点。
     */
    public void sendResult(Long orderId, String type, boolean success, String reason) {
        String msgId;
        if ("PURCHASE".equalsIgnoreCase(type)) {
            msgId = "MSG_PURC_RES_" + orderId;
        } else if ("REDEEM".equalsIgnoreCase(type)) {
            // 【BUGFIX】统一赎回 msgId 前缀为 "MSG_REED_RES_"（与持久化端一致），
            //          原本按 success 三元判断会让失败分支带下划线、成功分支不带，
            //          直接和 saveResultLocalMsg/saveFailMsgInNewTx 写入的 msgId 错配，
            //          导致 ConfirmCallback 的 updateStatusSuccessByMsgId 永远命中 0 行，
            //          本地消息会被定时任务无限重发直至死信。
            msgId = "MSG_REED_RES_" + orderId;
        } else {
            msgId = type + ":" + orderId;
        }
        sendResult(msgId, orderId, type, success, reason);
    }
}