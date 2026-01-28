package com.smartwealth.asset.mq.consumer;


import com.alibaba.fastjson.JSON;
import com.rabbitmq.client.Channel;
import com.smartwealth.asset.service.impl.InternalAssetService;
import com.smartwealth.common.configuration.RabbitConfig;
import com.smartwealth.common.dto.PurchaseMessageDTO;
import com.smartwealth.common.dto.RedemptionMessageDTO;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.asset.mq.producer.AssetResultProducer;
import io.jsonwebtoken.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class AssetInfoConsumer {

    @Autowired
    private InternalAssetService assetService;

    @Autowired
    private AssetResultProducer assetResultProducer;

    @RabbitListener(queues = RabbitConfig.PURCHASE_QUEUE)
    public void onPurchaseMessage(Message message, Channel channel) throws IOException, java.io.IOException { // 建议加上Channel做手动ACK
        String msgId = message.getMessageProperties().getMessageId();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("【资产模块】接收到扣款请求，msgId: {}, 内容: {}", msgId, body);

        PurchaseMessageDTO dto = JSON.parseObject(body, PurchaseMessageDTO.class);

        try {
            // 核心业务处理：内含幂等校验、动账、本地消息落库
            // 注意：这里如果抛出 BusinessException，说明是业务失败，Service 内部已经处理了（落库了失败消息），这里 catch 住就行
            assetService.deductForPurchase(dto);

            // 手动 ACK (消费成功)
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);

        } catch (BusinessException e) {
            log.warn("【资产模块】业务扣款失败已处理 (落库失败消息)，订单ID: {}", dto.getOrderId());
            // 业务失败也算消费成功，因为重试没用。Service 里已经保存了 FAIL 消息通知 Trade 端。
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("【资产模块】系统异常，拒绝消息让 MQ 重试: {}", dto.getOrderId(), e);
            // 系统异常 (数据库挂了)，抛出或 NACK，让 MQ 重试
            // requeue = true (重回队列)
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
        }
    }

    @RabbitListener(queues = RabbitConfig.REDEMPTION_QUEUE)
    public void onRedemptionMessage(Message message, Channel channel) throws IOException, java.io.IOException {
        String msgId = message.getMessageProperties().getMessageId();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("【资产模块】接收到赎回入账请求，msgId: {}, 内容: {}", msgId, body);

        RedemptionMessageDTO dto = JSON.parseObject(body, RedemptionMessageDTO.class);

        try {
            // 核心业务处理
            // Service 内部处理了：幂等、动账、记流水、本地消息落库(成功/失败)、事务后速发
            assetService.processRedemption(dto);

            // 消费成功 ACK
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);

        } catch (BusinessException e) {
            log.warn("【资产模块】业务入账失败已处理 (落库失败消息)，订单ID: {}", dto.getRequestId());
            // 业务失败（如账户不存在），Service 已保存失败消息通知 Trade，此处直接 ACK 结束
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("【资产模块】系统异常，拒绝消息让 MQ 重试: {}", dto.getRequestId(), e);
            // 系统异常 (数据库挂了)，抛出让 MQ 重试
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
        }
    }
}