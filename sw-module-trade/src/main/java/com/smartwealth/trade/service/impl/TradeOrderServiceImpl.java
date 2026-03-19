package com.smartwealth.trade.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.asset.service.impl.InternalAssetService;
import com.smartwealth.common.configuration.RabbitConfig;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.result.Result;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.entity.ProductRateHistory;
import com.smartwealth.product.service.impl.InternalProductService;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.trade.dto.AdminOrderQueryDTO;
import com.smartwealth.trade.dto.PurchaseDTO;
import com.smartwealth.trade.dto.RedemptionDTO;
import com.smartwealth.trade.dto.TradeCheckDTO;
import com.smartwealth.trade.entity.DailyProfit;
import com.smartwealth.trade.entity.RedemptionRecord;
import com.smartwealth.trade.entity.TradeLocalMsg;
import com.smartwealth.trade.event.ProdPurchaseEvent;
import com.smartwealth.trade.event.ProdRedeemEvent;
import com.smartwealth.trade.job.SettlementTxHelper;
import com.smartwealth.trade.mapper.DailyProfitMapper;
import com.smartwealth.trade.mapper.RedeemRecordMapper;
import com.smartwealth.trade.mapper.TradeLocalMsgMapper;
import com.smartwealth.trade.vo.AdminOrderVO;
import com.smartwealth.trade.vo.OrderHistoryVO;
import com.smartwealth.trade.vo.PositionVO;
import com.smartwealth.trade.entity.TradeOrder;
import com.smartwealth.trade.enums.TradeStatusEnum;
import com.smartwealth.trade.mapper.TradeOrderMapper;
import com.smartwealth.trade.service.ITradeOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartwealth.user.entity.UserBase;
import com.smartwealth.user.event.AccountCancelEvent;
import com.smartwealth.user.service.impl.InternalUserService;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 理财交易订单表 服务实现类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Service
@Slf4j
public class TradeOrderServiceImpl extends ServiceImpl<TradeOrderMapper, TradeOrder> implements ITradeOrderService {

    @Autowired
    private InternalProductService productService; // 模拟获取产品信息的内部接口
    @Autowired
    private InternalAssetService assetService; // 跨模块调用资产扣款
    @Autowired
    private InternalUserService userService; // 模拟获取用户信息的内部接口
    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    @Autowired
    DailyProfitMapper dailyProfitMapper;
    @Autowired
    private TradeLocalMsgMapper tradeLocalMsgMapper;
    @Autowired
    private RedeemRecordMapper redeemRecordMapper;
    @Autowired
    private Tradetransactionhelper tradetransactionhelper;
    @Autowired
    private SettlementTxHelper txHelper;
    @Autowired
    private ThreadPoolExecutor settlementThreadPool;

    // 申购理财产品
    @Override
    public Result<String> purchase(Long userId, PurchaseDTO dto) {
        ProductDetailVO prod = productService.getProductDetail(dto.getProductId(), 7);
        if (prod == null) {
            return Result.fail(ResultCode.PRODUCT_NOT_EXIST);
        }

        Integer userRiskLevel = userService.getUserRiskLevel(userId);
        if (userRiskLevel < prod.getBaseInfo().getRiskLevel()) {
            return Result.fail(ResultCode.RISK_LEVEL_MISMATCH);
        }

        BigDecimal quantity = dto.getAmount().divide(prod.getBaseInfo().getCurrentNav(), 2, RoundingMode.HALF_UP);



        try {
            productService.lockStock(dto.getProductId(), quantity);
        } catch (BusinessException be) {
            // 如果 Lua 脚本判断库存不足，直接阻断，返回给前端
            log.warn("Redis库存扣减拦截: {}", be.getMessage());
            return Result.fail(be.getCode(), be.getMessage());
        } catch (Exception e) {
            // Redis 宕机或网络超时，直接判定系统异常，未扣减成功，安全退出
            return Result.fail(ResultCode.FAILURE);
        }

        // ==========================================
        // 1.5 执行极速数据库事务 (磁盘 I/O 层)
        // ==========================================
        try {
            // 进入我们在步骤 2 写的纯粹 DB 逻辑。
            // 此时才会向连接池申请 1 个 MySQL 连接，并在几毫秒内用完即释放。
            return tradetransactionhelper.createOrderAndMessage(userId, dto, prod, quantity);

        } catch (Exception e) {
            log.error("落库事务执行失败，触发 Redis 库存补偿回滚", e);

            try {
                // 调用解锁方法（同样需要传入 traceId 进行精准回滚）
                productService.unlockStock(dto.getProductId(), quantity);
                log.info("Redis 库存补偿回滚成功");
            } catch (Exception ex) {
                // 严重灾难：DB 没写进去，准备回滚 Redis 时，Redis 网络也断了！
                // 此时库存发生实质性泄露。这就是为什么必须要有兜底的定时任务（步骤3）。
                log.error("【严重告警】落库失败且 Redis 回滚也失败！发生库存泄露！必须依赖兜底任务修复。 quantity:{}", quantity, ex);
            }

            return Result.fail(ResultCode.FAILURE.getCode(), "系统繁忙，申购失败");
        }
    }

    // 查询用户持仓列表
    @Override
    public Result<IPage<PositionVO>> listMyPositions(Long userId, Integer current, Integer size) {
        // 1. 构造分页参数
        Page<TradeOrder> page = new Page<>(current, size);

        // 2. 物理分页查询 HOLDING 状态订单
        IPage<TradeOrder> orderPage = this.page(page, new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getUserId, userId)
                .eq(TradeOrder::getStatus, TradeStatusEnum.HOLDING)
                .orderByDesc(TradeOrder::getCreateTime));

        // 3. 将 Order 转换为带实时市值的 VO
        IPage<PositionVO> voPage = orderPage.convert(order -> {
            PositionVO vo = new PositionVO();
            BeanUtils.copyProperties(order, vo);

            LocalDateTime now = LocalDateTime.now();
            boolean isRedeemable = order.getExpireTime() == null || !now.isBefore(order.getExpireTime());
            vo.setRedeemable(isRedeemable);

            // 跨模块获取产品最新净值进行估值
            ProdInfo prod = productService.getById(order.getProdId());
            if (prod != null) {
                BigDecimal marketValue = order.getQuantity().multiply(prod.getCurrentNav())
                        .setScale(4, RoundingMode.HALF_UP);
                vo.setProdName(prod.getName());
                vo.setLatestRate(prod.getLatestRate());
                vo.setMarketValue(marketValue);
                vo.setProfit(marketValue.subtract(order.getAmount()));
                vo.setCurrentNav(prod.getCurrentNav());
                vo.setCreateTime(order.getCreateTime());
            }
            return vo;
        });

        return Result.success(voPage);
    }

    // 赎回理财产品
    @Override
    public Result<String> redeemByProduct(Long userId, RedemptionDTO dto) {
            // 1. 提前查询静态/弱一致性产品信息（剥离出强事务，避免在持锁期间发起额外 IO）
            ProdInfo prod = productService.getById(dto.getProductId());
            if (prod == null) {
                return Result.fail(ResultCode.PRODUCT_NOT_EXIST);
            }
            // 2. 提前生成全局唯一的请求流水号（剥离 CPU 计算）
            Long requestId = IdWorker.getId();
            log.info("开始处理赎回请求, userId:{}, productId:{}, requestId:{}", userId, dto.getProductId(), requestId);
            return tradetransactionhelper.executeRedeemWithPessimisticLock(userId, dto, prod, requestId);
    }

    // 分页查询订单历史
    @Override
    public Page<OrderHistoryVO> getOrderHistory(Long userId, Integer current, Integer size) {
        // 1. 分页查询
        Page<TradeOrder> page = new Page<>(current, size);
        LambdaQueryWrapper<TradeOrder> wrapper = new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getUserId, userId)
                .orderByDesc(TradeOrder::getCreateTime);

        this.page(page, wrapper);

        // 2. 直接转换 VO，无需二次查库，性能极佳
        List<OrderHistoryVO> voList = page.getRecords().stream().map(order -> {
            OrderHistoryVO vo = new OrderHistoryVO();
            vo.setId(order.getId());
            vo.setProductName(order.getProdNameSnap()); // 使用快照名称
            vo.setAmount(order.getAmount());
            vo.setRate(order.getRateSnap());            // 使用快照利率
            vo.setStatus(order.getStatus());
            vo.setCreateTime(order.getCreateTime());
            return vo;
        }).collect(Collectors.toList());

        Page<OrderHistoryVO> resultPage = new Page<>(current, size);
        resultPage.setRecords(voList);
        resultPage.setTotal(page.getTotal());
        return resultPage;
    }

    // 账户注销事件监听器
    @EventListener // 默认同步执行
    public void handleAccountCancel(AccountCancelEvent event) {
        Long exists = this.count(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getUserId, event.getUserId())
                .in(TradeOrder::getStatus, TradeStatusEnum.HOLDING, TradeStatusEnum.PENDING));
        if (exists > 0) {
            // 直接抛出异常，阻断注销流程
            throw new BusinessException(ResultCode.ORDER_NOT_CLOSED);
        }
    }

    // 管理员端-分页查询订单列表
    @Override
    public Result<IPage<AdminOrderVO>> getAdminOrderPage(AdminOrderQueryDTO query) {
        // 1. 物理分页查询订单主表
        Page<TradeOrder> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<TradeOrder> wrapper = new LambdaQueryWrapper<TradeOrder>()
                .eq(query.getTargetUserId() != null, TradeOrder::getUserId, query.getTargetUserId())
                .eq(query.getProductId() != null, TradeOrder::getProdId, query.getProductId())
                .eq(query.getStatus() != null, TradeOrder::getStatus, query.getStatus())
                .ge(query.getStartDate() != null, TradeOrder::getCreateTime, query.getStartDate().atStartOfDay())
                .le(query.getEndDate() != null, TradeOrder::getCreateTime, query.getEndDate().atTime(LocalTime.MAX))
                .orderByDesc(TradeOrder::getCreateTime);

        IPage<TradeOrder> orderPage = tradeOrderMapper.selectPage(page, wrapper);
        if (orderPage.getRecords().isEmpty()) {
            return Result.success(new Page<>());
        }

        // 2. 批量提取 ID，准备跨模块补全
        Set<Long> userIds = orderPage.getRecords().stream().map(TradeOrder::getUserId).collect(Collectors.toSet());
        Set<Long> prodIds = orderPage.getRecords().stream().map(TradeOrder::getProdId).collect(Collectors.toSet());

        // 3. 跨模块获取信息（遵守模块化架构约束，不使用 JOIN）
        Map<Long, UserBase> userMap = userService.getUsersByIds(userIds);
        Map<Long, String> prodNameMap = productService.getProdNamesByIds(prodIds);

        // 4. 组装 VO
        IPage<AdminOrderVO> voPage = orderPage.convert(order -> {
            AdminOrderVO vo = new AdminOrderVO();
            vo.setOrderId(order.getId());
            vo.setUserId(order.getUserId());
            vo.setProductId(order.getProdId());

            // 填充关联名称
            UserBase user = userMap.get(order.getUserId());
            vo.setUserName(user != null ? user.getUsername() : "未知用户");
            vo.setProductName(prodNameMap.getOrDefault(order.getProdId(), "未知产品"));

            vo.setAmount(order.getAmount());
            vo.setQuantity(order.getQuantity());
            vo.setAccumulatedIncome(order.getAccumulatedIncome());
            vo.setStatus(order.getStatus());
            return vo;
        });

        return Result.success(voPage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handlePurchaseResult(Long orderId, boolean success, String reason) {
        // 1. 获取订单详情
        TradeOrder order = this.getById(orderId);
        if (order == null || order.getStatus() != TradeStatusEnum.PENDING) {
            log.warn("订单 {} 状态异常或不存在，忽略回执", orderId);
            return;
        }

        if (success) {
            // 2. 成功：更新订单为持有中 (HOLDING) [cite: 2026-01-24]
            order.setStatus(TradeStatusEnum.HOLDING);
            // 如果产品有锁定期，设置到期时间
            this.updateById(order);
            log.info("订单 {} 交易成功，状态已更新为 HOLDING", orderId);
        } else {
            // 3. 失败：关闭订单并释放 Redis 库存 [cite: 2026-01-24]
            order.setStatus(TradeStatusEnum.CLOSED);
            this.updateById(order);
            // 调用 productService 里的逻辑，同步更新 MySQL 和 Redis
            productService.unlockStock(order.getProdId(), order.getAmount());
            log.error("订单 {} 交易失败，已关闭订单并回滚 Redis 库存", orderId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleRedemptionResult(Long requestId, boolean success, String reason) {
        // 1. 悲观锁：锁住当前流水，杜绝并发重试的交叉污染
        RedemptionRecord record = redeemRecordMapper.selectForUpdate(requestId);
        if (record == null) {
            log.error("【致命异常】未找到赎回流水记录，requestId: {}", requestId);
            return;
        }

        // 状态防重：如果不是“申请中”，立刻拦截
        if (record.getStatus() != RedemptionRecord.Status.APPLYING) {
            log.warn("赎回流水 {} 已经是终态，忽略重复回执", requestId);
            return;
        }

        String freezeDetailsJson = record.getFreezeDetails();
        List<JSONObject> details = JSON.parseArray(freezeDetailsJson, JSONObject.class);
        if (CollUtil.isEmpty(details)) return;

        // 【核心修复 2】：提取所有关联订单 ID，一次性查出，消灭 N+1 查询
        List<Long> orderIds = details.stream().map(d -> d.getLong("orderId")).collect(Collectors.toList());
        Map<Long, TradeOrder> orderMap = this.listByIds(orderIds).stream()
                .collect(Collectors.toMap(TradeOrder::getId, Function.identity()));

        List<TradeOrder> ordersToUpdate = new ArrayList<>();

        if (success) {
            // ==================== 情况 A：打款成功，执行硬扣减 ====================
            for (JSONObject detail : details) {
                Long orderId = detail.getLong("orderId");
                BigDecimal redeemShares = detail.getBigDecimal("amount"); // 冻结的份额

                TradeOrder currentOrder = orderMap.get(orderId);
                if (currentOrder == null) continue;

                BigDecimal redeemPrincipal = detail.getBigDecimal("principal");
                BigDecimal incomeToDeduct = BigDecimal.ZERO;

                // 【核心修复 3】：尾差终结者 —— 判断是否为“最后一笔”
                if (redeemShares.compareTo(currentOrder.getQuantity()) == 0) {
                    // 1. 全额赎回：放弃乘除法比例，直接把剩余的本金和收益全部清空！
                    if (redeemPrincipal == null) {
                        redeemPrincipal = currentOrder.getAmount();
                    }
                    incomeToDeduct = currentOrder.getAccumulatedIncome() != null ?
                            currentOrder.getAccumulatedIncome() : BigDecimal.ZERO;
                } else {
                    // 2. 部分赎回：依然使用高精度比例计算
                    if (redeemPrincipal == null && currentOrder.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal ratio = redeemShares.divide(currentOrder.getQuantity(), 10, RoundingMode.HALF_UP);
                        redeemPrincipal = currentOrder.getAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                    }
                    if (currentOrder.getQuantity().compareTo(BigDecimal.ZERO) > 0 && currentOrder.getAccumulatedIncome() != null) {
                        BigDecimal ratio = redeemShares.divide(currentOrder.getQuantity(), 10, RoundingMode.HALF_UP);
                        incomeToDeduct = currentOrder.getAccumulatedIncome().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                    }
                }

                // 【内存计算】：在内存中算出最新状态，而不是每算一次就 update 一次数据库
                currentOrder.setQuantity(currentOrder.getQuantity().subtract(redeemShares));
                currentOrder.setFrozenQuantity(currentOrder.getFrozenQuantity().subtract(redeemShares)); // 成功了，冻结份额也要减掉
                currentOrder.setAmount(currentOrder.getAmount().subtract(redeemPrincipal));

                if (currentOrder.getAccumulatedIncome() != null) {
                    currentOrder.setAccumulatedIncome(currentOrder.getAccumulatedIncome().subtract(incomeToDeduct));
                    // 【核心修复】：生成一条负数的收益流水，用于平账！
                    DailyProfit negativeProfit = new DailyProfit();
                    negativeProfit.setId(IdWorker.getId());
                    negativeProfit.setOrderId(currentOrder.getId());
                    negativeProfit.setUserId(currentOrder.getUserId());
                    negativeProfit.setProdId(currentOrder.getProdId());
                    // 关键：变成负数
                    negativeProfit.setDailyProfit(incomeToDeduct.negate());
                    negativeProfit.setProfitDate(LocalDate.now());
                    negativeProfit.setType(2); // 假设 2 代表“赎回结转结息”

                    dailyProfitMapper.insert(negativeProfit);
                }

                // 状态流转：份额扣光了，订单彻底结清
                if (currentOrder.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    currentOrder.setStatus(TradeStatusEnum.REDEEMED);
                }

                currentOrder.setUpdateTime(LocalDateTime.now());
                ordersToUpdate.add(currentOrder);
            }

            // 【核心修复 1】：补上最致命的状态流转，防止死循环二次扣减
            record.setStatus(RedemptionRecord.Status.SUCCESS);

        } else {
            // ==================== 情况 B：打款失败，执行解冻退回 ====================
            for (JSONObject detail : details) {
                Long orderId = detail.getLong("orderId");
                BigDecimal amount = detail.getBigDecimal("amount"); // 当初冻结的份额

                TradeOrder currentOrder = orderMap.get(orderId);
                if (currentOrder != null) {
                    // 内存计算：只把冻结的份额减回去，总份额不动
                    currentOrder.setFrozenQuantity(currentOrder.getFrozenQuantity().subtract(amount));
                    currentOrder.setUpdateTime(LocalDateTime.now());
                    ordersToUpdate.add(currentOrder);
                }
            }
            record.setStatus(RedemptionRecord.Status.FAIL);
            record.setFailReason(reason);
        }

        // 最终阶段：统一落盘 (1次订单批量更新 IO + 1次流水更新 IO)
        if (CollUtil.isNotEmpty(ordersToUpdate)) {
            this.updateBatchById(ordersToUpdate);
        }
        redeemRecordMapper.updateById(record);
    }

    /**
     * 执行每日收益结算
     * 建议在每日净值更新任务完成后调用
     */
    @Override
    public void executeDailySettlementWithSharding(LocalDate bizDate, int shardIndex, int shardTotal) {
        log.info("========== 分片[{}/{}] {} 开始结算 ==========", shardIndex, shardTotal, bizDate);

        // 1. 预计算每个产品的单位收益
        Map<Long, BigDecimal> unitProfitMap = preCalculateRates(bizDate);
        if (unitProfitMap.isEmpty()) {
            log.info("今日产品净值无波动或无记录，提前结束。");
            return;
        }

        int fetchSize = 5000;
        long lastId = 0L;

        // 2. 游标分页拉取
        while (true) {
            List<TradeOrder> orders = tradeOrderMapper.selectUnsettledOrders(lastId, fetchSize, shardIndex, shardTotal, bizDate);
            if (CollectionUtils.isEmpty(orders)) {
                log.info("分片[{}] 无待结算订单，退出主循环。", shardIndex);
                break;
            }

            lastId = orders.get(orders.size() - 1).getId();

            // 3. 将 5000 条切割成 5 份，每份 1000 条
            List<List<TradeOrder>> partitions = partitionList(orders, 1000);
            CountDownLatch latch = new CountDownLatch(partitions.size());

            // 4. 多线程并发计算与落库
            for (List<TradeOrder> batchOrders : partitions) {
                settlementThreadPool.execute(() -> {
                    try {
                        processAndSaveBatch(batchOrders, unitProfitMap, bizDate);
                    } catch (Exception e) {
                        log.error("批次处理异常，跳过该批次继续执行", e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // 5. 等待这一大批全部处理完
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("主线程等待超时被中断");
            }
        }
        log.info("========== 分片[{}] 每日结算完成 ==========", shardIndex);
    }

    private void processAndSaveBatch(List<TradeOrder> batchOrders, Map<Long, BigDecimal> unitProfitMap, LocalDate bizDate) {
        List<DailyProfit> profitInsertList = new ArrayList<>();
        List<TradeOrder> orderUpdateList = new ArrayList<>();

        for (TradeOrder order : batchOrders) {
            BigDecimal unitProfit = unitProfitMap.get(order.getProdId());
            if (unitProfit == null || unitProfit.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal dailyProfitAmt = order.getQuantity().multiply(unitProfit).setScale(4, RoundingMode.HALF_UP);
            if (dailyProfitAmt.compareTo(BigDecimal.ZERO) == 0) continue;

            // 组装流水
            DailyProfit profitLog = new DailyProfit();
            profitLog.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
            profitLog.setOrderId(order.getId());
            profitLog.setUserId(order.getUserId());
            profitLog.setProdId(order.getProdId());
            profitLog.setDailyProfit(dailyProfitAmt);
            profitLog.setProfitDate(bizDate);
            profitLog.setType(1);
            profitInsertList.add(profitLog);

            // 组装订单累计收益更新参数
            TradeOrder updateVo = new TradeOrder();
            updateVo.setId(order.getId());
            updateVo.setAccumulatedIncome(dailyProfitAmt);
            orderUpdateList.add(updateVo);
        }

        // 调用微事务入库
        if (!profitInsertList.isEmpty() || !orderUpdateList.isEmpty()) {
            txHelper.doBatchSave(profitInsertList, orderUpdateList);
        }
    }

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

    @Override
    public List<TradeCheckDTO> checkIncomeConsistency() {
        return tradeOrderMapper.checkIncomeConsistency();
    }
}



