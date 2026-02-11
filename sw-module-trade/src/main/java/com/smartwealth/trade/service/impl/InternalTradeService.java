package com.smartwealth.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.service.impl.InternalProductService;
import com.smartwealth.trade.entity.DailyProfit;
import com.smartwealth.trade.entity.TradeOrder;
import com.smartwealth.trade.enums.TradeStatusEnum;
import com.smartwealth.trade.mapper.DailyProfitMapper;
import com.smartwealth.trade.mapper.TradeOrderMapper;
import com.smartwealth.trade.service.ITradeOrderService;
import com.smartwealth.trade.vo.PositionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 内部调用的交易服务
 * </p>
 *
 * @author Fire
 * @since 2026-01-12
 */
@Service
public class InternalTradeService {

    @Autowired
    private TradeOrderMapper orderMapper;
    @Autowired
    DailyProfitMapper dailyProfitMapper;
    @Autowired
    private InternalProductService productService;
    @Autowired
    private ITradeOrderService tradeOrderService;

    // 获取用户持仓总市值和总盈亏
    public Map<String, BigDecimal> getUserPositionSummary(Long userId) {
        // 1. 获取所有持有中(1)的订单
        List<TradeOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getUserId, userId)
                .eq(TradeOrder::getStatus, TradeStatusEnum.HOLDING));

        BigDecimal totalMarketValue = BigDecimal.ZERO;
        BigDecimal totalPrincipal = BigDecimal.ZERO;

        for (TradeOrder order : orders) {
            // 获取最新净值进行实时估值
            ProdInfo prod = productService.getById(order.getProdId());
            if (prod != null) {
                // 市值 = 份额 * 最新净值
                BigDecimal marketValue = order.getQuantity().multiply(prod.getCurrentNav());
                totalMarketValue = totalMarketValue.add(marketValue);
                // 累计本金，用于算盈亏
                totalPrincipal = totalPrincipal.add(order.getAmount());
            }
        }

        Map<String, BigDecimal> result = new HashMap<>();
        result.put("marketValue", totalMarketValue.setScale(4, RoundingMode.HALF_UP));
        result.put("profit", totalMarketValue.subtract(totalPrincipal).setScale(4, RoundingMode.HALF_UP));
        return result;
    }
    // 分页查询每日盈亏（内部调用）
    public IPage<DailyProfit> selectPageDaily(Page<DailyProfit> page, LambdaQueryWrapper<DailyProfit> wrapper) {
        return dailyProfitMapper.selectPage(page, wrapper);
    }
    // 查询订单列表（内部调用）
    public List<TradeOrder> selectList(LambdaQueryWrapper<TradeOrder> eq) {
        return orderMapper.selectList(eq);
    }
    // 查询单个订单（内部调用）
    public TradeOrder getOne(LambdaQueryWrapper<TradeOrder> last) {
        return orderMapper.selectOne(last);
    }
    //查看用户所有持仓
    public List<PositionVO> getUserPositions(Long userId) {
        return tradeOrderService.listMyPositions(userId,1,1000).getData().getRecords();
    }
}
