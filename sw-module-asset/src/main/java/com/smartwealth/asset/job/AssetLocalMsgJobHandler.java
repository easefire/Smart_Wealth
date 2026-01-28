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
        // 1. 拉取待处理消息
        List<AssetLocalMsg> pendingList = msgMapper.selectPendingMsgs();
        if (pendingList == null || pendingList.isEmpty()) {
            return;
        }

        log.info(">>> 开始执行消息补发任务，待处理数量: {}", pendingList.size());

        for (AssetLocalMsg msg : pendingList) {
            processSingleMsg(msg);
        }
    }

    private void processSingleMsg(AssetLocalMsg msg) {
        try {
            // 2. 解析内容
            // 之前存的 JSON: {"orderId":123, "status":"SUCCESS", "reason":null}
            JSONObject payload = JSON.parseObject(msg.getContent());

            Long orderId = payload.getLong("orderId");
            String statusStr = payload.getString("status"); // "SUCCESS" or "FAIL"
            String reason = payload.getString("reason");
            boolean isSuccess = "SUCCESS".equals(statusStr);

            // 3. 识别业务类型 (申购还是赎回?)
            // 技巧：根据 msg_id 的前缀来判断
            // 申购前缀: MSG_PURC_RES_
            // 赎回前缀: MSG_REED_RES
            String type;
            if (msg.getMsgId().startsWith("MSG_PURC")) {
                type = "PURCHASE";
            } else if (msg.getMsgId().startsWith("MSG_REED")) {
                type = "REDEEM";
            } else {
                log.warn("未知消息类型，跳过: {}", msg.getMsgId());
                markAsDead(msg); // 标记为死信，不再重试
                return;
            }

            // 4. 执行补发 (调用你现有的 Producer)
            log.info("正在补发回执: ID={}, Type={}, OrderId={}", msg.getMsgId(), type, orderId);
            assetResultProducer.sendResult(orderId, type, isSuccess, reason);

            // 5. 补发成功：更新状态为 1 (SUCCESS)
            msg.setStatus(1);
            msgMapper.updateById(msg);

        } catch (Exception e) {
            log.error("补发失败，准备下次重试: {}", msg.getMsgId(), e);
            handleFailure(msg);
        }
    }

    /**
     * 处理失败情况：增加重试次数，计算下次时间
     */
    private void handleFailure(AssetLocalMsg msg) {
        int currentRetry = msg.getRetryCount() + 1;
        msg.setRetryCount(currentRetry);

        // 如果重试超过 5 次，标记为失败(2)或死信(3)，不再重试
        if (currentRetry >= 5) {
            msg.setStatus(2); // 2 = FAIL/DEAD
            log.error("消息重试次数耗尽，标记为失败: {}", msg.getMsgId());
        } else {
            // 指数退避：第1次等1分，第2次等2分，第3次等4分...
            int delayMinutes = 1 << (currentRetry - 1);
            msg.setNextRetry(LocalDateTime.now().plusMinutes(delayMinutes));
        }

        msgMapper.updateById(msg);
    }

    /**
     * 标记为死信 (针对格式错误等无法修复的问题)
     */
    private void markAsDead(AssetLocalMsg msg) {
        msg.setStatus(3); // 3 = DEAD
        msgMapper.updateById(msg);
    }
}