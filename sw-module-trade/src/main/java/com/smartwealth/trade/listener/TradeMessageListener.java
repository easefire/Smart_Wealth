package com.smartwealth.trade.listener;

import com.smartwealth.trade.entity.TradeLocalMsg;
import com.smartwealth.trade.event.ProdPurchaseEvent;
import com.smartwealth.trade.event.ProdRedeemEvent;
import com.smartwealth.trade.mq.producer.TradeMessageProducer;
import com.smartwealth.trade.service.ITradeOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TradeMessageListener {

    @Autowired
    private TradeMessageProducer messageProducer; // 包含你定义的函数

    /**
     * 【核心】只有当 purchase 的事务成功 COMMIT 后，才会触发此方法
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePurchaseEvent(ProdPurchaseEvent event) {
        messageProducer.sendPurchaseMessage(event.getMsg());
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleRedeemEvent(ProdRedeemEvent event) {
        messageProducer.sendRedemptionMessage(event.getMsg());
    }
}