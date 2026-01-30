package com.smartwealth.asset.service.impl;

import com.alibaba.fastjson.JSON;
import com.smartwealth.asset.entity.AssetLocalMsg;
import com.smartwealth.asset.mapper.AssetLocalMsgMapper;
import com.smartwealth.common.configuration.RabbitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class AssetLocalMsgService {
    @Autowired
    private AssetLocalMsgMapper msgMapper;

    /**
     * 【关键】保存失败消息，必须开启新事务 (REQUIRES_NEW)
     * 确保即使主事务回滚，这条失败记录也能存下来，通知 Trade 端。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveFailMsgInNewTx(Long orderId, String type, String reason) {
        AssetLocalMsg message = new AssetLocalMsg();
        message.setMsgId(type + orderId);
        message.setTopic(RabbitConfig.RESULT_EXCHANGE);

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("status", "FAIL"); // 明确标记失败
        payload.put("reason", reason);

        message.setContent(JSON.toJSONString(payload));
        message.setStatus(0); // 待发送

        msgMapper.insert(message);
    }

    /**
     * 更新状态为成功 (可以是普通事务)
     */
    @Transactional
    public void updateStatusSuccess(Long id) {
        AssetLocalMsg update = new AssetLocalMsg();
        update.setId(id);
        update.setStatus(1); // 1 = SUCCESS
        msgMapper.updateById(update);
    }
}