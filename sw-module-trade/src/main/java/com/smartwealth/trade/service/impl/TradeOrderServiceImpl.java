package com.smartwealth.trade.service.impl;

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
    private TradeLocalMsgMapper tradeLocalMsgMapper;
    @Autowired
    private RedeemRecordMapper redeemRecordMapper;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private DailyProfitMapper dailyProfitMapper;

    // 用户申购理财产品
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> purchase(Long userId, PurchaseDTO dto) {
        // 1. 获取产品信息
        ProductDetailVO prod = productService.getProductDetail(dto.getProductId(), 7);
        if (prod == null) return Result.fail(ResultCode.PRODUCT_NOT_EXIST);

        // 基础校验
        Integer userRiskLevel = userService.getUserRiskLevel(userId);
        if (userRiskLevel < prod.getBaseInfo().getRiskLevel()) {
            return Result.fail(ResultCode.RISK_LEVEL_MISMATCH);
        }

        // 2. 计算份额
        BigDecimal quantity = dto.getAmount().divide(prod.getBaseInfo().getCurrentNav(), 2, RoundingMode.HALF_UP);

        // 3. 【核心修改】直接调用！不要 try-catch！
        // 如果 lockStock 抛出 BusinessException，Spring 会自动回滚事务，
        // 并且你的 GlobalExceptionHandler 会捕获它并返回给前端 Result.fail(...)
        productService.lockStock(dto.getProductId(), quantity);

        try {
            // 4. 创建订单
            TradeOrder order = new TradeOrder();
            order.setUserId(userId);
            order.setProdId(prod.getBaseInfo().getId());
            order.setAmount(dto.getAmount());
            order.setQuantity(quantity);
            order.setAccumulatedIncome(BigDecimal.ZERO);

            order.setProdNameSnap(prod.getBaseInfo().getName());
            order.setRateSnap(prod.getBaseInfo().getLatestRate());
            order.setStatus(TradeStatusEnum.PENDING);
            order.setCreateTime(LocalDateTime.now());
            order.setExpireTime(LocalDateTime.now().plusDays(prod.getBaseInfo().getCycle()));

            this.save(order);

            // 5. 保存本地消息表
            TradeLocalMsg msg = new TradeLocalMsg();
            msg.setMsgId(order.getId().toString());
            msg.setTopic("ex.trade.purchase");
            msg.setStatus(0);
            msg.setRetryCount(0);
            msg.setNextRetry(LocalDateTime.now());
            msg.setCreateTime(LocalDateTime.now());

            Map<String, Object> payload = new HashMap<>();
            payload.put("userId", userId);
            payload.put("amount", dto.getAmount());
            payload.put("share", quantity);
            payload.put("orderId", order.getId());
            payload.put("payPassword", dto.getPayPassword());
            msg.setContent(JSON.toJSONString(payload));

            tradeLocalMsgMapper.insert(msg);

            eventPublisher.publishEvent(new ProdPurchaseEvent(this, msg));

            return Result.success(order.getId().toString() + "申购申请已受理");

        } catch (Exception e) {
            // 6. 异常处理：这里的 try-catch 是为了回滚 Redis 库存（补偿）
            // 因为 Redis 不受 Spring 事务控制，必须手动回滚
            try {
                productService.unlockStock(dto.getProductId(), quantity);
            } catch (Exception ex) {
                log.error("【严重告警】申购异常回滚时，Redis 额度补偿失败！产品ID: {}, 数量: {}",
                        dto.getProductId(), quantity, ex);
            }
            // 【必须】继续抛出异常，触发 DB 事务回滚
            throw e;
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
            }
            return vo;
        });

        return Result.success(voPage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> redeemByProduct(Long userId, RedemptionDTO dto) {
        // 1. 并发预锁 (保持原样)
        assetService.selectprelock(userId);

        // 2. 查找符合条件的持仓订单 (FIFO)
        List<TradeOrder> eligibleOrders = this.list(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getUserId, userId)
                .eq(TradeOrder::getProdId, dto.getProductId())
                .eq(TradeOrder::getStatus, TradeStatusEnum.HOLDING)
                .orderByAsc(TradeOrder::getCreateTime));

        // 3. 校验“可用份额”
        BigDecimal totalAvailable = eligibleOrders.stream()
                .map(o -> o.getQuantity().subtract(o.getFrozenQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalAvailable.compareTo(dto.getRedeemQuantity()) < 0) {
            return Result.fail(ResultCode.HOLDING_NOT_ENOUGH);
        }

        // 4. 初始化数据
        ProdInfo prod = productService.getById(dto.getProductId());
        BigDecimal currentNav = prod.getCurrentNav();
        BigDecimal remainingNeed = dto.getRedeemQuantity();

        Long requestId = IdWorker.getId();
        List<TradeOrder> ordersToUpdate = new ArrayList<>();

        // --- 【新增】用于记录冻结明细的快照清单 ---
        List<Map<String, Object>> freezeDetails = new ArrayList<>();

        BigDecimal totalRedeemAmount = BigDecimal.ZERO;
        BigDecimal totalRealizedProfit = BigDecimal.ZERO;

        for (TradeOrder order : eligibleOrders) {
            if (remainingNeed.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal orderAvailable = order.getQuantity().subtract(order.getFrozenQuantity());
            if (orderAvailable.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal canRedeem = orderAvailable.min(remainingNeed);

            // 核心逻辑：增加订单冻结
            order.setFrozenQuantity(order.getFrozenQuantity().add(canRedeem));
            order.setUpdateTime(LocalDateTime.now());
            ordersToUpdate.add(order);

            // 1. 先计算本金扣减额 (把这段计算提到 Map 封装之前)
            // 逻辑：(本次赎回份额 / 订单总份额) * 订单总本金
            BigDecimal costReduction = canRedeem
                    .divide(order.getQuantity(), 8, RoundingMode.HALF_UP)
                    .multiply(order.getAmount())
                    .setScale(2, RoundingMode.HALF_UP); // 建议存库用2位小数

            // 2. 【核心修改】将本金扣减额也记录到明细中！
            Map<String, Object> detail = new HashMap<>();
            detail.put("orderId", order.getId());
            detail.put("amount", canRedeem);      // 告诉消费者扣多少份额
            detail.put("principal", costReduction); // 👈【新增】告诉消费者扣多少本金！
            freezeDetails.add(detail);

            // 3. 计算收益 (保持原样)
            BigDecimal redeemValue = canRedeem.multiply(currentNav).setScale(4, RoundingMode.HALF_UP);
            BigDecimal incrementalProfit = redeemValue.subtract(costReduction);

            totalRedeemAmount = totalRedeemAmount.add(redeemValue);
            totalRealizedProfit = totalRealizedProfit.add(incrementalProfit);

            remainingNeed = remainingNeed.subtract(canRedeem);
        }

        // 5. 落流水表 (带上冻结清单快照)
        RedemptionRecord record = RedemptionRecord.builder()
                .userId(userId)
                .productId(dto.getProductId())
                .amount(dto.getRedeemQuantity())
                .requestId(requestId)
                .status(RedemptionRecord.Status.APPLYING) // 👈 注意：用 .getCode()
                .freezeDetails(JSON.toJSONString(freezeDetails)) // 👈 【核心】把清单存进去
                .build();
        redeemRecordMapper.insert(record);

        // 6. 执行批量更新
        this.updateBatchById(ordersToUpdate);

        // 7. 写入 Payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", requestId);
        payload.put("userId", userId);
        payload.put("amount", totalRedeemAmount.setScale(4, RoundingMode.HALF_UP));
        payload.put("share", dto.getRedeemQuantity());
        payload.put("profit", totalRealizedProfit.setScale(4, RoundingMode.HALF_UP));
        payload.put("productId", dto.getProductId());
        payload.put("remark", "理财赎回入账");

        TradeLocalMsg localMsg = new TradeLocalMsg();
        localMsg.setMsgId(requestId.toString());
        localMsg.setTopic(RabbitConfig.REDEMPTION_EXCHANGE);
        localMsg.setContent(JSON.toJSONString(payload));
        localMsg.setStatus(0);
        tradeLocalMsgMapper.insert(localMsg);

        eventPublisher.publishEvent(new ProdRedeemEvent(this, localMsg));

        return Result.success("赎回申请已提交，预计到账金额：" + totalRedeemAmount.setScale(2, RoundingMode.HALF_UP));
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
            vo.setCreateTime(order.getCreateTime());
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
        // 1. 幂等校验：通过 requestId 锁住流水记录
        RedemptionRecord record = redeemRecordMapper.selectForUpdate(requestId);

        if (record == null) {
            log.error("【致命异常】未找到赎回流水记录，requestId: {}", requestId);
            return;
        }

        // 如果流水状态已经不是“申请中”，说明已经处理过了，直接返回
        if (record.getStatus() != RedemptionRecord.Status.APPLYING) {
            log.warn("赎回流水 {} 已经是终态（{}），忽略重复回执", requestId, record.getStatus());
            return;
        }

        // 2. 【关键：解析小抄】从流水记录中解析出当时冻结的订单清单
        String freezeDetailsJson = record.getFreezeDetails();
        if (StrUtil.isBlank(freezeDetailsJson)) {
            log.error("【数据异常】流水 {} 缺少冻结明细快照", requestId);
            return;
        }

        // 解析为 List<Map> 或专门的 DTO
        List<JSONObject> details = JSON.parseArray(freezeDetailsJson, JSONObject.class);

        if (success) {
            // --- 情况 A：赎回成功 ---
            for (JSONObject detail : details) {
                Long orderId = detail.getLong("orderId");
                BigDecimal redeemShares = detail.getBigDecimal("amount"); // 本次赎回的份额

                // 1. 【关键】先查出当前订单的最新状态
                //我们需要它的 currentQuantity 和 accumulatedIncome 来算比例
                TradeOrder currentOrder = this.getById(orderId);

                if (currentOrder == null) {
                    log.error("订单不存在或已丢失: {}", orderId);
                    continue;
                }

                // 2. 获取本金扣减额 (兼容旧数据)
                BigDecimal redeemPrincipal = detail.getBigDecimal("principal");
                if (redeemPrincipal == null) {
                    // 如果前端没传本金，为了保险，我们可以按比例反算：当前本金 * (赎回份额 / 当前份额)
                    if (currentOrder.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal ratio = redeemShares.divide(currentOrder.getQuantity(), 10, RoundingMode.HALF_UP);
                        redeemPrincipal = currentOrder.getAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                    } else {
                        redeemPrincipal = BigDecimal.ZERO;
                    }
                }

                // 3. 【核心新增】计算需要扣除的累计收益
                // 公式：扣除收益 = 当前累计收益 * (本次赎回份额 / 当前持有份额)
                BigDecimal incomeToDeduct = BigDecimal.ZERO;

                // 防御性编程：防止除以0
                if (currentOrder.getQuantity().compareTo(BigDecimal.ZERO) > 0 &&
                        currentOrder.getAccumulatedIncome() != null) {

                    // 计算比例
                    BigDecimal ratio = redeemShares.divide(currentOrder.getQuantity(), 10, RoundingMode.HALF_UP);

                    // 计算应扣金额
                    incomeToDeduct = currentOrder.getAccumulatedIncome()
                            .multiply(ratio)
                            .setScale(2, RoundingMode.HALF_UP); // 收益保留2位小数
                }

                // 4. 【修改】调用 Mapper，同时扣减 份额、本金、累计收益
                // 方法名建议改为 deductPositions (扣减持仓)
                int rows = tradeOrderMapper.deductPositions(orderId, redeemShares, redeemPrincipal, incomeToDeduct);

                if (rows == 0) {
                    log.warn("订单扣减失败（可能余额不足或并发冲突）: {}", orderId);
                    throw new BusinessException("赎回扣减失败"); // 抛异常回滚事务
                }

                // 5. 判定该子订单是否结清
                // 因为刚才已经扣减了数据库，我们判断剩余份额
                // 注意：这里的 currentOrder 是旧快照，不能用来判断，要用扣减后的逻辑推算，或者简单点再次查询(如果不介意性能)，
                // 或者直接判断 redeemShares 是否等于 currentOrder.getQuantity()

                // 比较安全的做法：如果赎回份额 >= 持有份额，直接更新状态
                if (redeemShares.compareTo(currentOrder.getQuantity()) >= 0) {
                    // 只有当完全赎回时，状态才变更为 REDEEMED (已赎回/已结清)
                    // 注意：这里需要 update status where id = ?
                    tradeOrderMapper.updateStatus(orderId, TradeStatusEnum.REDEEMED.getValue());
                }
            }
        } else {
            // --- 情况 B：赎回失败 ---
            for (JSONObject detail : details) {
                Long orderId = detail.getLong("orderId");
                BigDecimal amount = detail.getBigDecimal("amount");

                // a. 逻辑解冻（只减冻结份额）
                tradeOrderMapper.onlyUnfreeze(orderId, amount);
            }

            // b. 更新流水状态
            record.setStatus(RedemptionRecord.Status.FAIL);
            record.setFailReason(reason);

            log.error("赎回流水 {} 失败：原因 {}。涉及订单已批量原路解冻", requestId, reason);
        }

        redeemRecordMapper.updateById(record);
    }

    //每日订单结算任务

    /**
     * 执行每日收益结算
     * 建议在每日净值更新任务完成后调用
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void executeDailySettlement() {
        LocalDate bizDate = LocalDate.now().minusDays(1);
        Long count = dailyProfitMapper.selectCount(
                new LambdaQueryWrapper<DailyProfit>()
                        .eq(DailyProfit::getProfitDate, bizDate) // 查昨天
                        .last("LIMIT 1")
        );

        if (count > 0) {
            log.warn("日期 {} 的收益已结算过，跳过本次执行，防止重复加钱！", bizDate);
            return;
        }
        log.info("========== 开始执行 {} 每日收益结算 ==========", bizDate);

        // 1. 获取今日所有产品的净值记录
        // 对应表: t_prod_rate_history
        List<ProductRateHistory> historyList = productService.selectList(
                new LambdaQueryWrapper<ProductRateHistory>()
                        .eq(ProductRateHistory::getRecordDate, bizDate)
        );

        if (CollectionUtils.isEmpty(historyList)) {
            log.warn("今日无净值记录，无法结算。请检查净值更新任务是否执行。");
            return;
        }

        // 2. 预计算每个产品的“每份收益 (Delta NAV)”
        // Map<ProdId, UnitProfit>
        Map<Long, BigDecimal> unitProfitMap = new HashMap<>();

        for (ProductRateHistory history : historyList) {
            BigDecimal currentNav = history.getNav();
            BigDecimal rate = history.getRate(); // 当日涨跌幅

            // 如果今日没涨跌，跳过
            if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            // 核心推导：根据今日净值和涨幅，反推昨日净值，算出差价
            // 公式：OldNav = CurrentNav / (1 + Rate)
            // 差价 Delta = CurrentNav - OldNav
            BigDecimal divisor = BigDecimal.ONE.add(rate);
            BigDecimal oldNav = currentNav.divide(divisor, 10, RoundingMode.HALF_UP);
            BigDecimal deltaNav = currentNav.subtract(oldNav);

            unitProfitMap.put(history.getProdId(), deltaNav);
        }

        if (unitProfitMap.isEmpty()) {
            log.info("今日所有产品净值无波动，无需结算。");
            return;
        }

        // 3. 分批处理订单更新 (防止一次性拉取太多订单导致 OOM)
        int batchSize = 1000;
        long lastId = 0L;

        while (true) {
            // 查出所有持仓中的订单
            List<TradeOrder> orders = tradeOrderMapper.selectBatchForSettlement(lastId, batchSize);
            if (CollectionUtils.isEmpty(orders)) break;

            // 准备两个集合：一个用于批量插入流水，一个用于批量更新订单
            List<DailyProfit> profitInsertList = new ArrayList<>();
            List<TradeOrder> orderUpdateList = new ArrayList<>();

            for (TradeOrder order : orders) {
                lastId = order.getId(); // 更新游标

                BigDecimal unitProfit = unitProfitMap.get(order.getProdId());
                if (unitProfit == null || unitProfit.compareTo(BigDecimal.ZERO) == 0) continue;

                // --- 计算收益 ---
                // 收益 = 持有份额 * 单位净值差
                BigDecimal dailyProfitAmt = order.getQuantity().multiply(unitProfit)
                        .setScale(4, RoundingMode.HALF_UP);

                if (dailyProfitAmt.compareTo(BigDecimal.ZERO) == 0) continue;

                // --- A. 准备插入流水表 (t_trade_daily_profit) ---
                DailyProfit profitLog = new DailyProfit();
                profitLog.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
                profitLog.setOrderId(order.getId());
                profitLog.setUserId(order.getUserId());
                profitLog.setProdId(order.getProdId());
                profitLog.setDailyProfit(dailyProfitAmt);
                profitLog.setProfitDate(bizDate);
                profitLog.setType(1); // 假设 1 代表"净值收益"
                profitLog.setCreateTime(LocalDateTime.now());

                profitInsertList.add(profitLog);

                // --- B. 准备更新订单表 (t_trade_order) ---
                TradeOrder updateVo = new TradeOrder();
                updateVo.setId(order.getId());
                updateVo.setAccumulatedIncome(dailyProfitAmt); // 暂存增量，Mapper里做加法

                orderUpdateList.add(updateVo);
            }

            // 3. 批量执行数据库操作 (减少 IO 次数)
            if (!profitInsertList.isEmpty()) {
                // 批量插入流水
                dailyProfitMapper.insertBatch(profitInsertList);

                // 批量更新订单累计收益
                tradeOrderMapper.batchAddIncome(orderUpdateList);
            }
        }

        log.info("========== 每日结算完成 ==========");
    }

    @Override
    public List<TradeCheckDTO> checkIncomeConsistency() {
        return tradeOrderMapper.checkIncomeConsistency();
    }
}



