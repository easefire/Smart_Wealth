package com.smartwealth.asset.job;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.smartwealth.asset.entity.AssetLocalMsg;
import com.smartwealth.asset.mapper.AssetLocalMsgMapper;
import com.smartwealth.asset.mq.producer.AssetResultProducer;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资产模块-本地消息表重试任务
 * 作用：兜底补发那些因为网络原因没发出去的 MQ 回执
 */
@Component
@Slf4j
public class AssetLocalMsgJobHandler {

    @Autowired
    private AssetLocalMsgMapper msgMapper;

    @Autowired
    private AssetResultProducer assetResultProducer; // 你的 MQ 发送者

    /**
     * 每分钟执行一次
     */
    @XxlJob("assetMsgRetryJob")
    public void execute() {
        // 【核心修复 1】防爆仓与退避生效：必须调用带 LIMIT 和 时间校验的 SQL
        // 对应的底层 SQL 必须是: WHERE status = 0 AND (next_retry IS NULL OR next_retry <= NOW()) LIMIT 500
        List<AssetLocalMsg> pendingList = msgMapper.selectPendingMsgs();

        if (pendingList == null || pendingList.isEmpty()) {
            return;
        }

        log.info(">>> 开始执行消息补发任务，本次捞取数量: {}", pendingList.size());

        for (AssetLocalMsg msg : pendingList) {
            processSingleMsg(msg);
        }
    }

    private void processSingleMsg(AssetLocalMsg msg) {
        try {
            JSONObject payload = JSON.parseObject(msg.getContent());
            Long orderId = payload.getLong("orderId");
            String statusStr = payload.getString("status");
            String reason = payload.getString("reason");
            boolean isSuccess = "SUCCESS".equals(statusStr);

            String type;
            if (msg.getMsgId().startsWith("MSG_PURC")) {
                type = "PURCHASE";
            } else if (msg.getMsgId().startsWith("MSG_REED")) {
                type = "REDEEM";
            } else {
                log.warn("未知消息类型，跳过: {}", msg.getMsgId());
                markAsDead(msg, "未知的消息前缀: " + msg.getMsgId());
                return;
            }

            log.info("正在补发回执: ID={}, Type={}, OrderId={}", msg.getMsgId(), type, orderId);

            // ⚠️ 物理前提：你的 assetResultProducer.sendResult 底层必须透传了 CorrelationData(msg.getMsgId())
            assetResultProducer.sendResult(orderId, type, isSuccess, reason);

            // 【核心修复 2】绝对删除 msg.setStatus(1) 和 updateById！
            // 权力交接：发送动作结束，状态流转全权移交给资产端的 RabbitmqConfirmConfig

        } catch (Exception e) {
            log.error("补发动作抛出异常，准备下次重试: {}", msg.getMsgId(), e);
            handleFailure(msg);
        }
    }

    private void handleFailure(AssetLocalMsg msg) {
        // 【核心修复 3】防御性编程：防止数据库初始值为 null 导致自动拆箱空指针异常
        int currentRetry = (msg.getRetryCount() == null ? 0 : msg.getRetryCount()) + 1;
        msg.setRetryCount(currentRetry);

        if (currentRetry >= 5) {
            msg.setStatus(2); // 2 = 重试耗尽失败 (需人工介入告警)
            log.error("【高危告警】消息重试次数耗尽，进入死信状态: {}", msg.getMsgId());
        } else {
            // 指数退避：第1次等1分，第2次等2分，第3次等4分...
            int delayMinutes = 1 << (currentRetry - 1);
            msg.setNextRetry(LocalDateTime.now().plusMinutes(delayMinutes));
        }

        msgMapper.updateById(msg);
    }

    private void markAsDead(AssetLocalMsg msg, String reason) {
        msg.setStatus(3); // 3 = 格式错误等直接死信
        // 强烈建议你在资产端本地表加上 error_reason 字段，方便日后排查
        // msg.setErrorReason(reason);
        msgMapper.updateById(msg);
    }
}