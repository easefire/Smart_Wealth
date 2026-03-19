package com.smartwealth.trade.config;


import com.smartwealth.trade.mapper.TradeLocalMsgMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Slf4j
@Component
public class RabbitmqConfirmConfig implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TradeLocalMsgMapper tradeLocalMsgMapper; // 注入你的本地消息服务

    // 在 Bean 初始化后，将当前类设置为 RabbitTemplate 的回调监听器
    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnsCallback(this);
    }

    /**
     * 【核心 1】ConfirmCallback: 消息是否成功到达 Exchange (交换机)
     * @param correlationData 包含我们在发送时塞进去的 msgId
     * @param ack true表示 MQ 成功接收并落盘；false表示 MQ 拒收
     * @param cause 拒收的原因
     */
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null || correlationData.getId() == null) {
            return;
        }
        String msgId = correlationData.getId();

        if (ack) {
            // 客观事实：只有走进这里，才代表消息真正安全了！
            // 此时再去数据库把状态改为 1 (已发送)
            tradeLocalMsgMapper.updateStatusSuccess(msgId);
            log.info("【可靠投递】消息已在 MQ 磁盘安全落定，状态更新为成功。msgId: {}", msgId);
        } else {
            // 极端情况：MQ 磁盘满了，或者权限错误，直接拒收
            // 状态依然是 0，可以记录告警日志，等待定时任务重试
            log.error("【致命异常】MQ 拒收消息，msgId: {}, 原因: {}", msgId, cause);
        }
    }

    /**
     * 【核心 2】ReturnsCallback: 消息到了 Exchange，但没有正确路由到 Queue (队列)
     * 客观事实：这是你路由键 (RoutingKey) 写错，或者队列没绑定的代码级 Bug
     */
    @Override
    public void returnedMessage(ReturnedMessage returned) {
        log.error("【路由失败】消息丢失！找不到对应的队列。交换机: {}, 路由键: {}, 回退代码: {}, 明细: {}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyCode(),
                returned.getReplyText());
        // 这种属于代码配置错误，重试也没用。你可以把本地消息状态改为 2 (发送失败/死信)
        // tradeLocalMsgService.updateStatusFail(msgId, "路由失败");
    }
}
