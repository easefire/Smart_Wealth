package com.smartwealth.trade.job;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.smartwealth.trade.entity.DailyProfit;
import com.smartwealth.trade.entity.TradeOrder;
import com.smartwealth.trade.mapper.DailyProfitMapper;
import com.smartwealth.trade.mapper.TradeOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Slf4j
public class SettlementTxHelper {

    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    @Autowired
    private DailyProfitMapper dailyProfitMapper;

    @Transactional(rollbackFor = Exception.class)
    public void doBatchSave(List<DailyProfit> profitInsertList, List<TradeOrder> orderUpdateList) {
        if (!CollectionUtils.isEmpty(profitInsertList)) {
            dailyProfitMapper.insertBatch(profitInsertList);
        }
        if (!CollectionUtils.isEmpty(orderUpdateList)) {
            tradeOrderMapper.batchAddIncome(orderUpdateList);
        }
    }
}
