package com.smartwealth.trade.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartwealth.asset.service.impl.InternalAssetService;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.result.Result;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.service.impl.InternalProductService;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.trade.dto.AdminOrderQueryDTO;
import com.smartwealth.trade.dto.PurchaseDTO;
import com.smartwealth.trade.dto.RedemptionDTO;
import com.smartwealth.trade.dto.TradeCheckDTO;
import com.smartwealth.trade.entity.DailyProfit;
import com.smartwealth.trade.entity.RedemptionRecord;
import com.smartwealth.trade.entity.TradeOrder;
import com.smartwealth.trade.enums.TradeStatusEnum;
import com.smartwealth.trade.mapper.DailyProfitMapper;
import com.smartwealth.trade.mapper.RedeemRecordMapper;
import com.smartwealth.trade.mapper.TradeOrderMapper;
import com.smartwealth.trade.service.ITradeOrderService;
import com.smartwealth.trade.vo.AdminOrderVO;
import com.smartwealth.trade.vo.OrderHistoryVO;
import com.smartwealth.trade.vo.PositionVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 理财交易订单表 服务实现类（重构后的"瘦门面"）。
 * </p>
 *
 * 【REFACTOR-Step4】之前是 570 行的"大杂烩"：申购/赎回写路径、持仓查询、订单历史、
 * 管理员分页、每日结算批处理、收益体检、{@code @EventListener} 全揉在一起。本次拆分后：
 * <ul>
 *   <li>{@link #purchase} / {@link #redeemByProduct} / {@link #handlePurchaseResult} /
 *       {@link #handleRedemptionResult} —— 用户实时<strong>写路径</strong>留在本类
 *       （顺带承担 IService 契约，让 {@link Tradetransactionhelper}、{@link InternalTradeService}
 *       这些已有合作者依然能通过 {@code tradeOrderService.save/list/updateBatchById} 走 MP 基操）；</li>
 *   <li>{@link #listMyPositions} / {@link #getOrderHistory} / {@link #getAdminOrderPage}
 *       → {@link TradeQueryService}（读路径独立家）；</li>
 *   <li>{@link #executeDailySettlementWithSharding} / {@link #checkIncomeConsistency}
 *       → {@link TradeSettlementService}（批处理独立家）；</li>
 *   <li>原 {@code handleAccountCancel} → {@link com.smartwealth.trade.listener.TradeAccountCancelListener}
 *       （事件监听独立 bean）。</li>
 * </ul>
 *
 * <p>{@link ITradeOrderService} 接口保持不变，所有外部调用方
 * （{@code TradeOrderController} / {@code TradeController} / {@code TradeJobHandler} /
 *  {@code TradeResultConsumer} / {@code InternalTradeService} / {@code Tradetransactionhelper}）
 * 零感知。
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Service
@Slf4j
public class TradeOrderServiceImpl extends ServiceImpl<TradeOrderMapper, TradeOrder> implements ITradeOrderService {

    // ===== 写路径直接依赖 =====
    @Autowired
    private InternalProductService productService;
    @Autowired
    private com.smartwealth.user.service.impl.InternalUserService userService;
    @Autowired
    private InternalAssetService assetService;
    @Autowired
    private DailyProfitMapper dailyProfitMapper;
    @Autowired
    private RedeemRecordMapper redeemRecordMapper;

    /**
     * 用 {@link ObjectProvider} 而不是直接 {@code @Autowired}：
     * {@link Tradetransactionhelper} 反过来又持有本 bean，存在循环依赖；
     * Provider 的"按需解析"语义让 Spring 容器跳过初始化期的强引用检查。
     */
    @Autowired
    private ObjectProvider<Tradetransactionhelper> transactionHelperProvider;

    // ===== 子领域协作者 =====
    @Autowired
    private TradeQueryService queryService;
    @Autowired
    private TradeSettlementService settlementService;

    // ============================================================
    //  申购 / 赎回（用户实时写路径，本类自留地）
    // ============================================================

    @Override
    public Result<String> purchase(Long userId, PurchaseDTO dto) {
        ProductDetailVO prod = productService.getProductDetail(dto.getProductId(), 7);
        if (prod == null) {
            return Result.fail(ResultCode.PRODUCT_NOT_EXIST);
        }

        // 【BUGFIX-#20】getUserRiskLevel 在用户未做风险测评时可能返回 null，
        //              老代码 userRiskLevel < ... 直接拆箱抛 NPE，攻击者只要风测前申购就能让接口 500。
        Integer userRiskLevel = userService.getUserRiskLevel(userId);
        if (userRiskLevel == null) {
            return Result.fail(ResultCode.RISK_EVAL_NEEDED);
        }
        if (userRiskLevel < prod.getBaseInfo().getRiskLevel()) {
            return Result.fail(ResultCode.RISK_LEVEL_MISMATCH);
        }

        // 【BUGFIX-#19】
        //   1) 精度只有 2 位 → 大资金 / 低净值产品的份额误差能高达 1%，长期会导致库存/资金账面失衡；
        //   2) 没处理 currentNav 为 null 或 0 → 直接 ArithmeticException(/ by zero) 或 NPE；
        //   3) HALF_UP 在份额这种"宁可少分、不可超扣"的场景下也不合适，应使用 DOWN 防止超卖。
        BigDecimal currentNav = prod.getBaseInfo().getCurrentNav();
        if (currentNav == null || currentNav.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("产品净值异常，无法申购。productId={}, currentNav={}", dto.getProductId(), currentNav);
            return Result.fail(ResultCode.FAILURE.getCode(), "产品净值数据异常，请稍后再试");
        }
        BigDecimal quantity = dto.getAmount().divide(currentNav, 6, RoundingMode.DOWN);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.fail(ResultCode.FAILURE.getCode(), "申购金额过低，无法计算出有效份额");
        }

        try {
            productService.lockStock(dto.getProductId(), quantity);
        } catch (BusinessException be) {
            log.warn("Redis库存扣减拦截: {}", be.getMessage());
            return Result.fail(be.getCode(), be.getMessage());
        } catch (Exception e) {
            return Result.fail(ResultCode.FAILURE);
        }

        try {
            return transactionHelperProvider.getIfAvailable().createOrderAndMessage(userId, dto, prod, quantity);
        } catch (Exception e) {
            log.error("落库事务执行失败，触发 Redis 库存补偿回滚", e);
            try {
                productService.unlockStock(dto.getProductId(), quantity);
                log.info("Redis 库存补偿回滚成功");
            } catch (Exception ex) {
                // 严重灾难：DB 没写进去，准备回滚 Redis 时，Redis 网络也断了！
                // 此时库存发生实质性泄露。这就是为什么必须要有兜底的定时任务。
                log.error("【严重告警】落库失败且 Redis 回滚也失败！发生库存泄露！必须依赖兜底任务修复。 quantity:{}", quantity, ex);
            }
            return Result.fail(ResultCode.FAILURE.getCode(), "系统繁忙，申购失败");
        }
    }

    @Override
    public Result<String> redeemByProduct(Long userId, RedemptionDTO dto) {
        // 【SECURITY】赎回属于资金动账，必须先校验支付密码。
        //   verifyPayPassword 已改为抛 BusinessException 区分三种失败：
        //   WALLET_NOT_EXIST / PAY_PASSWORD_NOT_SET / PAYMENT_PASSWORD_ERROR
        try {
            assetService.verifyPayPassword(userId, dto.getPayPassword());
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }

        // 1. 提前查询静态/弱一致性产品信息（剥离出强事务，避免在持锁期间发起额外 IO）
        ProdInfo prod = productService.getById(dto.getProductId());
        if (prod == null) {
            return Result.fail(ResultCode.PRODUCT_NOT_EXIST);
        }
        Long requestId = IdWorker.getId();
        log.info("开始处理赎回请求, userId:{}, productId:{}, requestId:{}", userId, dto.getProductId(), requestId);
        return transactionHelperProvider.getIfAvailable().executeRedeemWithPessimisticLock(userId, dto, prod, requestId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handlePurchaseResult(Long orderId, boolean success, String reason) {
        TradeOrder order = this.getById(orderId);
        if (order == null || order.getStatus() != TradeStatusEnum.PENDING) {
            log.warn("订单 {} 状态异常或不存在，忽略回执", orderId);
            return;
        }

        if (success) {
            order.setStatus(TradeStatusEnum.HOLDING);
            this.updateById(order);
            log.info("订单 {} 交易成功，状态已更新为 HOLDING", orderId);
        } else {
            order.setStatus(TradeStatusEnum.CLOSED);
            this.updateById(order);
            // 【BUGFIX】必须回滚的是"份额(quantity)"，不是"本金(amount)"。
            // 之前误用 amount 会把 CNY 金额当份额回补，导致库存被严重放大、超卖。
            productService.unlockStock(order.getProdId(), order.getQuantity());
            log.error("订单 {} 交易失败，已关闭订单并回滚 Redis 库存 quantity={}", orderId, order.getQuantity());
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

        // 状态防重：如果不是"申请中"，立刻拦截
        if (record.getStatus() != RedemptionRecord.Status.APPLYING) {
            log.warn("赎回流水 {} 已经是终态，忽略重复回执", requestId);
            return;
        }

        String freezeDetailsJson = record.getFreezeDetails();
        List<JSONObject> details = JSON.parseArray(freezeDetailsJson, JSONObject.class);
        if (CollectionUtils.isEmpty(details)) return;

        // 【核心修复 2】：提取所有关联订单 ID，一次性查出，消灭 N+1 查询
        List<Long> orderIds = details.stream().map(d -> d.getLong("orderId")).collect(Collectors.toList());
        Map<Long, TradeOrder> orderMap = this.listByIds(orderIds).stream()
                .collect(Collectors.toMap(TradeOrder::getId, Function.identity()));

        List<TradeOrder> ordersToUpdate = new ArrayList<>();

        if (success) {
            // ==================== 情况 A：打款成功，执行硬扣减 ====================
            for (JSONObject detail : details) {
                Long orderId = detail.getLong("orderId");
                BigDecimal redeemShares = detail.getBigDecimal("amount");

                TradeOrder currentOrder = orderMap.get(orderId);
                if (currentOrder == null) continue;

                BigDecimal redeemPrincipal = detail.getBigDecimal("principal");
                BigDecimal incomeToDeduct = BigDecimal.ZERO;

                // 【核心修复 3】：尾差终结者 —— 判断是否为"最后一笔"
                if (redeemShares.compareTo(currentOrder.getQuantity()) == 0) {
                    // 全额赎回：放弃乘除法比例，直接把剩余的本金和收益全部清空
                    if (redeemPrincipal == null) {
                        redeemPrincipal = currentOrder.getAmount();
                    }
                    incomeToDeduct = currentOrder.getAccumulatedIncome() != null
                            ? currentOrder.getAccumulatedIncome() : BigDecimal.ZERO;
                } else {
                    // 部分赎回：使用高精度比例计算
                    if (redeemPrincipal == null && currentOrder.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal ratio = redeemShares.divide(currentOrder.getQuantity(), 10, RoundingMode.HALF_UP);
                        redeemPrincipal = currentOrder.getAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                    }
                    if (currentOrder.getQuantity().compareTo(BigDecimal.ZERO) > 0 && currentOrder.getAccumulatedIncome() != null) {
                        BigDecimal ratio = redeemShares.divide(currentOrder.getQuantity(), 10, RoundingMode.HALF_UP);
                        incomeToDeduct = currentOrder.getAccumulatedIncome().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                    }
                }

                currentOrder.setQuantity(currentOrder.getQuantity().subtract(redeemShares));
                currentOrder.setFrozenQuantity(currentOrder.getFrozenQuantity().subtract(redeemShares));
                currentOrder.setAmount(currentOrder.getAmount().subtract(redeemPrincipal));

                if (currentOrder.getAccumulatedIncome() != null) {
                    currentOrder.setAccumulatedIncome(currentOrder.getAccumulatedIncome().subtract(incomeToDeduct));

                    // 【核心修复】：生成一条负数的收益流水，用于平账。
                    DailyProfit negativeProfit = new DailyProfit();
                    negativeProfit.setId(IdWorker.getId());
                    negativeProfit.setOrderId(currentOrder.getId());
                    negativeProfit.setUserId(currentOrder.getUserId());
                    negativeProfit.setProdId(currentOrder.getProdId());
                    negativeProfit.setDailyProfit(incomeToDeduct.negate());
                    negativeProfit.setProfitDate(LocalDate.now());
                    negativeProfit.setType(2); // 2 代表"赎回结转结息"
                    dailyProfitMapper.insert(negativeProfit);
                }

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
                BigDecimal amount = detail.getBigDecimal("amount");

                TradeOrder currentOrder = orderMap.get(orderId);
                if (currentOrder != null) {
                    currentOrder.setFrozenQuantity(currentOrder.getFrozenQuantity().subtract(amount));
                    currentOrder.setUpdateTime(LocalDateTime.now());
                    ordersToUpdate.add(currentOrder);
                }
            }
            record.setStatus(RedemptionRecord.Status.FAIL);
            record.setFailReason(reason);
        }

        // 最终阶段：统一落盘 (1 次订单批量更新 IO + 1 次流水更新 IO)
        if (!ordersToUpdate.isEmpty()) {
            this.updateBatchById(ordersToUpdate);
        }
        redeemRecordMapper.updateById(record);
    }

    // ============================================================
    //  以下方法仅作"代理委派"，业务实现已经搬到对应子 Service
    // ============================================================

    @Override
    public Result<IPage<PositionVO>> listMyPositions(Long userId, Integer current, Integer size) {
        return queryService.listMyPositions(userId, current, size);
    }

    @Override
    public Page<OrderHistoryVO> getOrderHistory(Long userId, Integer current, Integer size) {
        return queryService.getOrderHistory(userId, current, size);
    }

    @Override
    public Result<IPage<AdminOrderVO>> getAdminOrderPage(AdminOrderQueryDTO query) {
        return queryService.getAdminOrderPage(query);
    }

    @Override
    public void executeDailySettlementWithSharding(LocalDate bizDate, int shardIndex, int shardTotal) {
        settlementService.executeDailySettlementWithSharding(bizDate, shardIndex, shardTotal);
    }

    @Override
    public List<TradeCheckDTO> checkIncomeConsistency() {
        return settlementService.checkIncomeConsistency();
    }
}
