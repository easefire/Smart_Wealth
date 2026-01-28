package com.smartwealth.trade.mq.producer;

import com.smartwealth.common.configuration.RabbitConfig;
import com.smartwealth.trade.entity.TradeLocalMsg;
import com.smartwealth.trade.mapper.TradeLocalMsgMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

import static com.smartwealth.common.configuration.RabbitConfig.PURCHASE_ROUTING_KEY;

@Component
@Slf4j
public class TradeMessageProducer {
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private TradeLocalMsgMapper tradeLocalMsgMapper;
    /**
     * 发送申购扣款消息到 MQ
     */
    public void sendPurchaseMessage(TradeLocalMsg msg) {
        // 设置消息属性，特别是为了幂等而设置的 msg_id [cite: 2026-01-24]
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(msg.getMsgId());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(msg.getContent().getBytes(StandardCharsets.UTF_8), properties);

        // 这里的 topic 就是配置类里的 rk.trade.purchase
        rabbitTemplate.convertAndSend(msg.getTopic(), PURCHASE_ROUTING_KEY, message);

        msg.setStatus(1);
        tradeLocalMsgMapper.updateById(msg);

        log.info("MQ 消息已投递，msgId: {}", msg.getMsgId());
    }

    public void sendRedemptionMessage(TradeLocalMsg msg) {
        // 1. 设置消息属性，透传本地消息表的 msgId 用于消费端幂等
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(msg.getMsgId());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);

        // 2. 构建消息体
        Message message = new Message(msg.getContent().getBytes(StandardCharsets.UTF_8), properties);

        // 3. 发送至赎回交换机，使用对应的路由键 rk.trade.redemption
        // 注意：这里的第一个参数是 Exchange，第二个是 RoutingKey
        rabbitTemplate.convertAndSend(
                msg.getTopic(),
                RabbitConfig.REDEMPTION_ROUTING_KEY,
                message
        );

        // 4. 更新本地消息表状态为已投递（1-已发送）
        msg.setStatus(1);
        tradeLocalMsgMapper.updateById(msg);

        log.info("【赎回链路】MQ 消息已投递，msgId: {}, 路由键: {}", msg.getMsgId(), RabbitConfig.REDEMPTION_ROUTING_KEY);
    }


}
