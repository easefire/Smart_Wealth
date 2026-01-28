package com.smartwealth.common.configuration;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 交易链路配置
 * 规范：ex.(交换机), q.(队列), rk.(路由键)
 */
@Configuration
public class RabbitConfig {

    // --- 0. 通用死信配置 ---
    public static final String DLX_EXCHANGE = "ex.dlx";
    public static final String DLX_QUEUE = "q.dlx";
    public static final String DLX_ROUTING_KEY = "rk.dlx";

    @Bean
    public DirectExchange dlxExchange() { return new DirectExchange(DLX_EXCHANGE, true, false); }
    @Bean
    public Queue dlxQueue() { return QueueBuilder.durable(DLX_QUEUE).build(); }
    @Bean
    public Binding dlxBinding(@Qualifier("dlxQueue") Queue q, @Qualifier("dlxExchange") DirectExchange ex) {
        return BindingBuilder.bind(q).to(ex).with(DLX_ROUTING_KEY);
    }

    // --- 1. 申购扣款链路 ---
    public static final String PURCHASE_QUEUE = "q.trade.purchase";
    public static final String PURCHASE_EXCHANGE = "ex.trade.purchase";
    public static final String PURCHASE_ROUTING_KEY = "rk.trade.purchase";

    @Bean
    public DirectExchange purchaseExchange() { // 【补全】交换机定义
        return new DirectExchange(PURCHASE_EXCHANGE, true, false);
    }
    @Bean
    public Queue purchaseQueue() {
        return QueueBuilder.durable(PURCHASE_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY)
                .build();
    }
    @Bean
    public Binding purchaseBinding(@Qualifier("purchaseQueue") Queue q, @Qualifier("purchaseExchange") DirectExchange ex) { // 【补全】绑定关系
        return BindingBuilder.bind(q).to(ex).with(PURCHASE_ROUTING_KEY);
    }

    // --- 2. 赎回加钱链路 ---
    public static final String REDEMPTION_QUEUE = "q.trade.redemption";
    public static final String REDEMPTION_EXCHANGE = "ex.trade.redemption";
    public static final String REDEMPTION_ROUTING_KEY = "rk.trade.redemption";

    @Bean
    public DirectExchange redemptionExchange() { // 【补全】交换机定义
        return new DirectExchange(REDEMPTION_EXCHANGE, true, false);
    }
    @Bean
    public Queue redemptionQueue() {
        return QueueBuilder.durable(REDEMPTION_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY)
                .build();
    }
    @Bean
    public Binding redemptionBinding(@Qualifier("redemptionQueue") Queue q, @Qualifier("redemptionExchange") DirectExchange ex) { // 【补全】绑定关系
        return BindingBuilder.bind(q).to(ex).with(REDEMPTION_ROUTING_KEY);
    }

    // --- 3. 结果回传链路 ---
    public static final String RESULT_QUEUE = "q.trade.result";
    public static final String RESULT_EXCHANGE = "ex.trade.result";
    public static final String RESULT_ROUTING_KEY = "rk.trade.result";

    @Bean
    public DirectExchange resultExchange() { return new DirectExchange(RESULT_EXCHANGE, true, false); }
    @Bean
    public Queue resultQueue() { return QueueBuilder.durable(RESULT_QUEUE).build(); }
    @Bean
    public Binding resultBinding(@Qualifier("resultQueue") Queue q, @Qualifier("resultExchange") DirectExchange ex) {
        return BindingBuilder.bind(q).to(ex).with(RESULT_ROUTING_KEY);
    }
}