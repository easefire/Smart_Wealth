package com.smartwealth.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartwealth.product.entity.ProductRateHistory;
import com.smartwealth.product.service.impl.InternalProductService;
import com.smartwealth.trade.dto.TradeCheckDTO;
import com.smartwealth.trade.entity.DailyProfit;
import com.smartwealth.trade.entity.TradeOrder;
import com.smartwealth.trade.job.SettlementTxHelper;
import com.smartwealth.trade.mapper.TradeOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 【REFACTOR-Step4-2】每日结算与收益体检。
 *
 * <p>从 {@link TradeOrderServiceImpl} 中拆出来，原因：
 *   ① 结算是<strong>批处理领域</strong>，跟"用户实时下单/赎回"完全不同的失败模型：
 *      用户请求允许整事务回滚，结算批次必须容错（一批失败不影响下一批），二者放一起难以演化；
 *   ② {@code settlementThreadPool}、{@code SettlementTxHelper}、{@link InternalProductService#selectList}
 *      这套依赖只在结算路径使用，留在交易主门面里属于污染；
 *   ③ 后续的"收益体检 / 异常补偿 / 重算"等批处理任务可以共享本类的并行/分批基础设施。
 *
 * <p>对外行为与原 {@code TradeOrderServiceImpl.executeDailySettlementWithSharding/checkIncomeConsistency} 一致。
 */
@Slf4j
@Service
public class TradeSettlementService {

    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    @Autowired
    private SettlementTxHelper txHelper;
    @Autowired
    private ThreadPoolExecutor settlementThreadPool;
    @Autowired
    private InternalProductService productService;

    /**
     * 分片结算主入口。
     *
     * <p>注意：本方法故意<strong>不</strong>声明 {@code @Transactional}——
     * 真正的事务边界在 {@link SettlementTxHelper#doBatchSave} 上，由它给每个 1000 条的小批次单独开事务；
     * 把外层包成大事务反而会让连接长时间持有 + 任意一批失败导致全量回滚，违背批处理语义。
     *
     * <p>外部接口 {@code ITradeOrderService} 上仍声明了 {@code @Transactional}，这一历史遗留语义会在
     * 门面层透传到此处实际并不生效（异步线程拿不到事务上下文），不影响行为正确性，但属于待清理的死注解。
     */
    public void executeDailySettlementWithSharding(LocalDate bizDate, int shardIndex, int shardTotal) {
        log.info("========== 分片[{}/{}] {} 开始结算 ==========", shardIndex, shardTotal, bizDate);

        Map<Long, BigDecimal> unitProfitMap = preCalculateRates(bizDate);
        if (unitProfitMap.isEmpty()) {
            log.info("今日产品净值无波动或无记录，提前结束。");
            return;
        }

        int fetchSize = 5000;
        long lastId = 0L;
        while (true) {
            List<TradeOrder> orders = tradeOrderMapper.selectUnsettledOrders(
                    lastId, fetchSize, shardIndex, shardTotal, bizDate);
            if (CollectionUtils.isEmpty(orders)) {
                log.info("分片[{}] 无待结算订单，退出主循环。", shardIndex);
                break;
            }
            lastId = orders.get(orders.size() - 1).getId();

            List<List<TradeOrder>> partitions = partitionList(orders, 1000);
            List<CompletableFuture<Void>> futures = partitions.stream()
                    .map(batchOrders -> CompletableFuture.runAsync(
                            () -> processAndSaveBatch(batchOrders, unitProfitMap, bizDate),
                            settlementThreadPool
                    ).exceptionally(ex -> {
                        Long startId = batchOrders.get(0).getId();
                        Long endId = batchOrders.get(batchOrders.size() - 1).getId();
                        log.error("【结算核损】批次入库异常！订单区间: [{} - {}]。请排查 DB 状态或入异常表补偿。",
                                startId, endId, ex);
                        return null;
                    }))
                    .toList();

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (Exception e) {
                log.error("分片[{}] 主线程屏障等待发生未知崩溃", shardIndex, e);
                throw new RuntimeException("结算主流程被意外打断", e);
            }
        }
        log.info("========== 分片[{}] 每日结算完成 ==========", shardIndex);
    }

    /**
     * 收益体检：拉出"账面累计收益"与"流水汇总"对不上的订单。
     */
    public List<TradeCheckDTO> checkIncomeConsistency() {
        return tradeOrderMapper.checkIncomeConsistency();
    }

    private void processAndSaveBatch(List<TradeOrder> batchOrders, Map<Long, BigDecimal> unitProfitMap, LocalDate bizDate) {
        List<DailyProfit> profitInsertList = new ArrayList<>();
        List<TradeOrder> orderUpdateList = new ArrayList<>();

        for (TradeOrder order : batchOrders) {
            BigDecimal unitProfit = unitProfitMap.get(order.getProdId());
            if (unitProfit == null || unitProfit.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal dailyProfitAmt = order.getQuantity().multiply(unitProfit).setScale(4, RoundingMode.HALF_UP);
            if (dailyProfitAmt.compareTo(BigDecimal.ZERO) == 0) continue;

            DailyProfit profitLog = new DailyProfit();
            profitLog.setId(IdWorker.getId());
            profitLog.setOrderId(order.getId());
            profitLog.setUserId(order.getUserId());
            profitLog.setProdId(order.getProdId());
            profitLog.setDailyProfit(dailyProfitAmt);
            profitLog.setProfitDate(bizDate);
            profitLog.setType(1);
            profitInsertList.add(profitLog);

            // 注意：本字段在 mapper.batchAddIncome 里以 CASE WHEN 累加方式更新（accumulated_income += dailyProfit）。
            // 这里只承担"传递当天单产品收益"的载体，赋值即代表"当日增量"，不是覆盖式。
            TradeOrder updateVo = new TradeOrder();
            updateVo.setId(order.getId());
            updateVo.setAccumulatedIncome(dailyProfitAmt);
            orderUpdateList.add(updateVo);
        }

        if (!profitInsertList.isEmpty() || !orderUpdateList.isEmpty()) {
            txHelper.doBatchSave(profitInsertList, orderUpdateList);
        }
    }

    /**
     * 把"当日净值变动"换算成"每份额日收益单价"，便于后续乘以持仓份额直接拿到收益。
     *
     * <p>公式：oldNav = currentNav / (1 + rate)，deltaNav = currentNav - oldNav。
     */
    private Map<Long, BigDecimal> preCalculateRates(LocalDate bizDate) {
        List<ProductRateHistory> historyList = productService.selectList(
                new LambdaQueryWrapper<ProductRateHistory>().eq(ProductRateHistory::getRecordDate, bizDate)
        );
        Map<Long, BigDecimal> map = new HashMap<>();
        if (CollectionUtils.isEmpty(historyList)) return map;

        for (ProductRateHistory history : historyList) {
            BigDecimal currentNav = history.getNav();
            BigDecimal rate = history.getRate();
            if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal divisor = BigDecimal.ONE.add(rate);
            BigDecimal oldNav = currentNav.divide(divisor, 10, RoundingMode.HALF_UP);
            BigDecimal deltaNav = currentNav.subtract(oldNav);
            map.put(history.getProdId(), deltaNav);
        }
        return map;
    }

    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(new ArrayList<>(list.subList(i, Math.min(list.size(), i + size))));
        }
        return parts;
    }
}
