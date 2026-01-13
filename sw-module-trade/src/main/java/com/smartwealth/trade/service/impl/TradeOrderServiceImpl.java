package com.smartwealth.trade.service.impl;

import com.smartwealth.trade.entity.TradeOrder;
import com.smartwealth.trade.mapper.TradeOrderMapper;
import com.smartwealth.trade.service.ITradeOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 理财交易订单表 服务实现类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Service
public class TradeOrderServiceImpl extends ServiceImpl<TradeOrderMapper, TradeOrder> implements ITradeOrderService {

}
