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

    /**
     * 这里才加 @Transactional
     * 作用：仅在执行批量 SQL 的这几十毫秒内开启事务，极大地缩短了锁竞争时间。
     * 哪怕这一千条失败了回滚，也不会影响其他批次。
     */
    @Transactional(rollbackFor = Exception.class)
    public void doBatchSave(List<DailyProfit> profitInsertList, List<TradeOrder> orderUpdateList) {
        if (!CollectionUtils.isEmpty(profitInsertList)) {
            // 注意：底层的 mapper 需要实现 ON DUPLICATE KEY UPDATE 或者 INSERT IGNORE
            // 防止同一天的同一笔订单重复插入流水
            dailyProfitMapper.insertBatch(profitInsertList);
        }

        if (!CollectionUtils.isEmpty(orderUpdateList)) {
            tradeOrderMapper.batchAddIncome(orderUpdateList);
        }
    }
}
