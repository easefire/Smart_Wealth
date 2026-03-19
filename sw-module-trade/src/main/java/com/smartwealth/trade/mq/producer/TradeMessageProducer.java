package com.smartwealth.trade.mq.producer;

import com.smartwealth.common.configuration.RabbitConfig;
import com.smartwealth.trade.entity.TradeLocalMsg;
import com.smartwealth.trade.mapper.TradeLocalMsgMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
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
        // 1. 设置消息属性 (包括幂等需要的 msgId)
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(msg.getMsgId());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(msg.getContent().getBytes(StandardCharsets.UTF_8), properties);

        // 2. 【核心修复】：构建信物 CorrelationData
        CorrelationData correlationData = new CorrelationData(msg.getMsgId());

        // 3. 【核心修复】：带上信物发送
        rabbitTemplate.convertAndSend(
                msg.getTopic(),
                PURCHASE_ROUTING_KEY,
                message,
                correlationData // 👈 必须传这个，ConfirmCallback 才能生效
        );

        log.info("【申购链路】MQ 消息已推送至网络层等待回执，msgId: {}", msg.getMsgId());
    }

    public void sendRedemptionMessage(TradeLocalMsg msg) {
        // 1. 设置消息属性
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(msg.getMsgId());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);

        Message message = new Message(msg.getContent().getBytes(StandardCharsets.UTF_8), properties);

        // 2. 【核心新增】构建信物 CorrelationData，把 msgId 塞进去
        // 这个对象会跟着消息一起去 MQ，然后在 ConfirmCallback 里原封不动地还给你
        CorrelationData correlationData = new CorrelationData(msg.getMsgId());

        // 3. 发送至赎回交换机
        rabbitTemplate.convertAndSend(
                msg.getTopic(),
                RabbitConfig.REDEMPTION_ROUTING_KEY,
                message,
                correlationData
        );

        // 4. 【核心删除】绝对不要在这里写 tradeLocalMsgMapper.updateById(msg);
        // 发送动作结束。把改变状态的权力，彻底移交给上面的 RabbitmqConfirmConfig。

        log.info("【赎回链路】消息已推送至网络层等待 MQ 回执，msgId: {}", msg.getMsgId());
    }


}
