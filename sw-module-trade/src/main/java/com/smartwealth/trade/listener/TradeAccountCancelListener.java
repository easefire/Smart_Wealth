package com.smartwealth.trade.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.trade.entity.TradeOrder;
import com.smartwealth.trade.enums.TradeStatusEnum;
import com.smartwealth.trade.mapper.TradeOrderMapper;
import com.smartwealth.user.event.AccountCancelEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 【REFACTOR-Step4-3】交易模块对"用户账户注销"事件的拦截器。
 *
 * <p>从 {@link com.smartwealth.trade.service.impl.TradeOrderServiceImpl} 拆出来，
 * 原因是 {@code @EventListener} 这种"被动触发"的逻辑跟主门面的"主动写路径"概念上完全独立，
 * 二者放一个类里既膨胀代码量又会让 Spring 在初始化阶段做无谓的事件总线扫描污染。
 *
 * <p>语义保持不变（默认同步 EventListener）：用户注销前，只要该用户存在 HOLDING / PENDING
 * 状态的订单，就抛出 {@link ResultCode#ORDER_NOT_CLOSED} 阻断注销主事务。
 */
@Slf4j
@Component
public class TradeAccountCancelListener {

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    /**
     * 默认同步执行：让异常能正常打断 user 模块的注销事务。
     */
    @EventListener
    public void handleAccountCancel(AccountCancelEvent event) {
        Long exists = tradeOrderMapper.selectCount(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getUserId, event.getUserId())
                .in(TradeOrder::getStatus, TradeStatusEnum.HOLDING, TradeStatusEnum.PENDING));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.ORDER_NOT_CLOSED);
        }
    }
}
