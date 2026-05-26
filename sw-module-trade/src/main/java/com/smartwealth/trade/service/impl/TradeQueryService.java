package com.smartwealth.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.common.result.Result;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.service.impl.InternalProductService;
import com.smartwealth.trade.dto.AdminOrderQueryDTO;
import com.smartwealth.trade.entity.TradeOrder;
import com.smartwealth.trade.enums.TradeStatusEnum;
import com.smartwealth.trade.mapper.TradeOrderMapper;
import com.smartwealth.trade.vo.AdminOrderVO;
import com.smartwealth.trade.vo.OrderHistoryVO;
import com.smartwealth.trade.vo.PositionVO;
import com.smartwealth.user.entity.UserBase;
import com.smartwealth.user.service.impl.InternalUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 【REFACTOR-Step4-1】交易模块的"读路径"服务。
 *
 * <p>从 {@link TradeOrderServiceImpl} 中拆出来，原因：
 *   ① 读路径（持仓 / 历史 / 管理员分页）跟写路径（申购 / 赎回 / 回执处理）有<strong>完全不同的事务诉求</strong>：
 *      读路径要快，不需要事务；写路径要严，必须事务。把它们塞在同一个类里导致老门面被迫共享一份
 *      过度的事务配置和过多的依赖；
 *   ② 持仓 / 管理员订单查询都需要"批量跨模块补全产品/用户信息"，
 *      这一类"反 N+1 + 跨模块聚合"的查询模式以后还会增加（资金流水查询、收益排行等），
 *      给它独立的家有利于后续复用而不再侵入写服务。
 *
 * <p>对外语义保持不变：分页字段、VO 内容、空数据降级与原 {@code TradeOrderServiceImpl} 完全一致。
 */
@Slf4j
@Service
public class TradeQueryService {

    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    @Autowired
    private InternalProductService productService;
    @Autowired
    private InternalUserService userService;

    /**
     * 用户持仓列表。
     *
     * <p>【BUGFIX-P1-#21 ／ 后续可继续优化】这里仍存在 N+1：每条订单都会 {@code productService.getById}
     * 一次拿到最新净值。短期保持行为一致，长期建议在 {@link InternalProductService} 上加
     * {@code getByIds(Set)} 批量接口，把 N 次查询压成一次。
     */
    public Result<IPage<PositionVO>> listMyPositions(Long userId, Integer current, Integer size) {
        Page<TradeOrder> page = new Page<>(current, size);
        LambdaQueryWrapper<TradeOrder> wrapper = new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getUserId, userId)
                .eq(TradeOrder::getStatus, TradeStatusEnum.HOLDING)
                .orderByDesc(TradeOrder::getCreateTime);

        IPage<TradeOrder> orderPage = tradeOrderMapper.selectPage(page, wrapper);

        IPage<PositionVO> voPage = orderPage.convert(order -> {
            PositionVO vo = new PositionVO();
            BeanUtils.copyProperties(order, vo);

            LocalDateTime now = LocalDateTime.now();
            boolean isRedeemable = order.getExpireTime() == null || !now.isBefore(order.getExpireTime());
            vo.setRedeemable(isRedeemable);

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

    /**
     * 个人订单历史分页查询。
     *
     * <p>用快照字段（{@code prodNameSnap} / {@code rateSnap}）展示，避免回查产品表，性能极佳。
     */
    public Page<OrderHistoryVO> getOrderHistory(Long userId, Integer current, Integer size) {
        Page<TradeOrder> page = new Page<>(current, size);
        LambdaQueryWrapper<TradeOrder> wrapper = new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getUserId, userId)
                .orderByDesc(TradeOrder::getCreateTime);

        tradeOrderMapper.selectPage(page, wrapper);

        List<OrderHistoryVO> voList = page.getRecords().stream().map(order -> {
            OrderHistoryVO vo = new OrderHistoryVO();
            vo.setId(order.getId());
            vo.setProductName(order.getProdNameSnap());
            vo.setAmount(order.getAmount());
            vo.setRate(order.getRateSnap());
            vo.setStatus(order.getStatus());
            vo.setCreateTime(order.getCreateTime());
            return vo;
        }).collect(Collectors.toList());

        Page<OrderHistoryVO> resultPage = new Page<>(current, size);
        resultPage.setRecords(voList);
        resultPage.setTotal(page.getTotal());
        return resultPage;
    }

    /**
     * 管理端全平台订单分页查询。
     *
     * <p>典型的"批量提取 ID → 跨模块批量查 → 内存装配"反 N+1 模式，
     * 严格遵守模块化约束（不做跨库 JOIN，所有跨模块数据走 SPI 接口）。
     */
    public Result<IPage<AdminOrderVO>> getAdminOrderPage(AdminOrderQueryDTO query) {
        Page<TradeOrder> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<TradeOrder> wrapper = new LambdaQueryWrapper<TradeOrder>()
                .eq(query.getTargetUserId() != null, TradeOrder::getUserId, query.getTargetUserId())
                .eq(query.getProductId() != null, TradeOrder::getProdId, query.getProductId())
                .eq(query.getStatus() != null, TradeOrder::getStatus, query.getStatus())
                .ge(query.getStartDate() != null, TradeOrder::getCreateTime, query.getStartDate() == null ? null : query.getStartDate().atStartOfDay())
                .le(query.getEndDate() != null, TradeOrder::getCreateTime, query.getEndDate() == null ? null : query.getEndDate().atTime(LocalTime.MAX))
                .orderByDesc(TradeOrder::getCreateTime);

        IPage<TradeOrder> orderPage = tradeOrderMapper.selectPage(page, wrapper);
        if (orderPage.getRecords().isEmpty()) {
            return Result.success(new Page<>());
        }

        Set<Long> userIds = orderPage.getRecords().stream().map(TradeOrder::getUserId).collect(Collectors.toSet());
        Set<Long> prodIds = orderPage.getRecords().stream().map(TradeOrder::getProdId).collect(Collectors.toSet());

        Map<Long, UserBase> userMap = userService.getUsersByIds(userIds);
        Map<Long, String> prodNameMap = productService.getProdNamesByIds(prodIds);

        IPage<AdminOrderVO> voPage = orderPage.convert(order -> {
            AdminOrderVO vo = new AdminOrderVO();
            vo.setOrderId(order.getId());
            vo.setUserId(order.getUserId());
            vo.setProductId(order.getProdId());

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
}
