package com.smartwealth.trade.config;


import com.smartwealth.asset.mapper.AssetLocalMsgMapper;
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
    private TradeLocalMsgMapper tradeLocalMsgMapper;

    /**
     * 【BUGFIX-#13】之前 ConfirmCallback 只更新 trade_local_msg，
     *              资产端发的回执（MSG_PURC_RES_*, MSG_REED_RES_*, MSG_RECH_*, MSG_WITH_*）
     *              即使 broker 真正落盘了，asset_local_msg 也永远停在 status=0，
     *              定时补偿任务会无限重发。这里把 asset 端的 mapper 也注入进来，
     *              按 msgId 前缀分发，两侧各更各的。
     */
    @Autowired
    private AssetLocalMsgMapper assetLocalMsgMapper;

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
            // 仅当 broker 真正落盘后，才把本地消息置为成功。
            // 命名规范：MSG_XXX_ 前缀属于资产端；其它（如 PURCHASE / REDEEM 业务命令）属于交易端。
            if (isAssetMsg(msgId)) {
                int rows = assetLocalMsgMapper.updateStatusSuccessByMsgId(msgId);
                log.info("【可靠投递】asset_local_msg 状态更新为成功。msgId: {}, rows: {}", msgId, rows);
            } else {
                tradeLocalMsgMapper.updateStatusSuccess(msgId);
                log.info("【可靠投递】trade_local_msg 状态更新为成功。msgId: {}", msgId);
            }
        } else {
            log.error("【致命异常】MQ 拒收消息，msgId: {}, 原因: {}", msgId, cause);
        }
    }

    private boolean isAssetMsg(String msgId) {
        return msgId != null && (
                msgId.startsWith("MSG_PURC")
                        || msgId.startsWith("MSG_REED")
                        || msgId.startsWith("MSG_RECH")
                        || msgId.startsWith("MSG_WITH")
        );
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
