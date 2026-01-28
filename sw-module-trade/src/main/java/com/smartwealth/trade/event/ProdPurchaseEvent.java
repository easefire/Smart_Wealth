package com.smartwealth.trade.event;

import com.smartwealth.trade.entity.TradeLocalMsg;
import org.springframework.context.ApplicationEvent;

public class ProdPurchaseEvent extends ApplicationEvent {

    private final TradeLocalMsg msg;

    public ProdPurchaseEvent(Object source, TradeLocalMsg msg) {
        super(source);
        this.msg = msg;
    }

    public TradeLocalMsg getMsg() {
        return msg;
    }
}
