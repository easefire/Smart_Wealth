package com.smartwealth.trade.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartwealth.asset.service.impl.InternalAssetService;
import com.smartwealth.common.configuration.RabbitConfig;
import com.smartwealth.common.result.Result;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.service.impl.InternalProductService;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.trade.dto.PurchaseDTO;
import com.smartwealth.trade.dto.RedemptionDTO;
import com.smartwealth.trade.entity.RedemptionRecord;
import com.smartwealth.trade.entity.TradeLocalMsg;
import com.smartwealth.trade.entity.TradeOrder;
import com.smartwealth.trade.enums.TradeStatusEnum;
import com.smartwealth.trade.event.ProdPurchaseEvent;
import com.smartwealth.trade.event.ProdRedeemEvent;
import com.smartwealth.trade.mapper.RedeemRecordMapper;
import com.smartwealth.trade.mapper.TradeLocalMsgMapper;
import com.smartwealth.trade.service.ITradeOrderService;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class Tradetransactionhelper {
    @Autowired
    ITradeOrderService tradeOrderService;
    @Autowired
    TradeLocalMsgMapper tradeLocalMsgMapper;
    @Autowired
    RedeemRecordMapper redeemRecordMapper;
    @Autowired
    InternalAssetService assetService;
    @Autowired
    InternalProductService productService;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Transactional(rollbackFor = Exception.class)
    public Result<String> createOrderAndMessage(Long userId, PurchaseDTO dto, ProductDetailVO prod, BigDecimal quantity) {

        // 1. 在内存中构建订单对象（无 DB 操作）
        TradeOrder order = new TradeOrder();
        order.setUserId(userId);
        order.setProdId(prod.getBaseInfo().getId());
        order.setAmount(dto.getAmount());
        order.setQuantity(quantity);
        order.setAccumulatedIncome(BigDecimal.ZERO);
        order.setProdNameSnap(prod.getBaseInfo().getName());
        order.setRateSnap(prod.getBaseInfo().getLatestRate());
        order.setStatus(TradeStatusEnum.PENDING);
        order.setExpireTime(LocalDateTime.now().plusDays(prod.getBaseInfo().getCycle()));

        // 2. 执行订单落库 (极速 DB IO)
        tradeOrderService.save(order);

        // 3. 在内存中构建本地消息表对象（无 DB 操作）
        TradeLocalMsg msg = new TradeLocalMsg();
        msg.setMsgId(order.getId().toString()); // 或者直接使用 traceId 保持全局链路一致
        msg.setTopic("ex.trade.purchase");
        msg.setStatus(0); // 0-待发送
        msg.setRetryCount(0);

        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("amount", dto.getAmount());
        payload.put("share", quantity);
        payload.put("orderId", order.getId());
        payload.put("productId", dto.getProductId());
        payload.put("payPassword", dto.getPayPassword());
        msg.setContent(JSON.toJSONString(payload));
        tradeLocalMsgMapper.insert(msg);

        productService.doStockInDb(prod.getBaseInfo().getId(),quantity,"LOCK");

        eventPublisher.publishEvent(new ProdPurchaseEvent(this, msg));

        return Result.success(order.getId().toString() + "申购申请已受理");
    }

    @Transactional(rollbackFor = Exception.class)
    public Result<String> executeRedeemWithPessimisticLock(Long userId, RedemptionDTO dto, ProdInfo prod, Long requestId) {

        // 1. 开启并发预锁 (悲观锁生效，霸占数据库行锁)
        assetService.selectprelock(userId);

        // 2. 查找符合条件的持仓订单 (必须在锁生效后查询，确保数据绝对最新)
        List<TradeOrder> eligibleOrders = tradeOrderService.list(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getUserId, userId)
                .eq(TradeOrder::getProdId, dto.getProductId())
                .eq(TradeOrder::getStatus, TradeStatusEnum.HOLDING)
                .orderByAsc(TradeOrder::getCreateTime));

        // 3. 校验“可用份额”
        BigDecimal totalAvailable = eligibleOrders.stream()
                .map(o -> o.getQuantity().subtract(o.getFrozenQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalAvailable.compareTo(dto.getRedeemQuantity()) < 0) {
            // 抛出异常或返回失败，事务回滚，锁自动释放
            return Result.fail(ResultCode.HOLDING_NOT_ENOUGH);
        }

        // 4. 获取前置层传进来的静态数据，准备核心计算
        BigDecimal currentNav = prod.getCurrentNav();
        BigDecimal remainingNeed = dto.getRedeemQuantity();

        List<TradeOrder> ordersToUpdate = new ArrayList<>();
        List<Map<String, Object>> freezeDetails = new ArrayList<>();

        BigDecimal totalRedeemAmount = BigDecimal.ZERO;
        BigDecimal totalRealizedProfit = BigDecimal.ZERO;

        // 5. 核心扣减计算 (由于依赖悲观锁查出的数据，这部分 CPU 计算只能保留在事务内)
        for (TradeOrder order : eligibleOrders) {
            if (remainingNeed.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal orderAvailable = order.getQuantity().subtract(order.getFrozenQuantity());
            if (orderAvailable.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal canRedeem = orderAvailable.min(remainingNeed);

            // 增加订单冻结
            order.setFrozenQuantity(order.getFrozenQuantity().add(canRedeem));
            order.setUpdateTime(LocalDateTime.now());
            ordersToUpdate.add(order);

            // 本金扣减额计算
            BigDecimal costReduction = canRedeem
                    .divide(order.getQuantity(), 8, RoundingMode.HALF_UP)
                    .multiply(order.getAmount())
                    .setScale(2, RoundingMode.HALF_UP);

            // 记录冻结明细
            Map<String, Object> detail = new HashMap<>();
            detail.put("orderId", order.getId());
            detail.put("amount", canRedeem);
            detail.put("principal", costReduction);
            freezeDetails.add(detail);

            // 收益计算
            BigDecimal redeemValue = canRedeem.multiply(currentNav).setScale(4, RoundingMode.HALF_UP);
            BigDecimal incrementalProfit = redeemValue.subtract(costReduction);

            totalRedeemAmount = totalRedeemAmount.add(redeemValue);
            totalRealizedProfit = totalRealizedProfit.add(incrementalProfit);

            remainingNeed = remainingNeed.subtract(canRedeem);
        }

        // 6. DML 写操作：落流水表
        RedemptionRecord record = RedemptionRecord.builder()
                .userId(userId)
                .productId(dto.getProductId())
                .amount(dto.getRedeemQuantity())
                .requestId(requestId)
                .status(RedemptionRecord.Status.APPLYING)
                .freezeDetails(JSON.toJSONString(freezeDetails))
                .build();
        redeemRecordMapper.insert(record);

        // 7. DML 写操作：执行批量更新
        tradeOrderService.updateBatchById(ordersToUpdate);

        // 8. DML 写操作：构建并写入 MQ 负载本地消息表
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

        // 9. 触发本地事件，随后方法结束，Spring 自动 commit 事务并释放悲观锁
        eventPublisher.publishEvent(new ProdRedeemEvent(this, localMsg));

        return Result.success("赎回申请已提交，预计到账金额：" + totalRedeemAmount.setScale(2, RoundingMode.HALF_UP));
    }

}
