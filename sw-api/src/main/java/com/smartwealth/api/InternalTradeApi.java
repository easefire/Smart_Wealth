package com.smartwealth.api;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 交易模块对外 SPI 契约。
 *
 * <p>当前只暴露聚合视角下的"用户持仓汇总"，后续如需"按产品维度可赎回份额"、"用户首次申购时间"等
 * 跨模块查询，按需追加；细粒度的订单查询请保留在 InternalTradeService 内部，避免 SPI 膨胀成
 * 第二个 ServiceImpl。
 *
 * <p><strong>实现类</strong>：{@code com.smartwealth.trade.service.impl.InternalTradeService}（@Service）。
 */
public interface InternalTradeApi {

    /**
     * 计算用户的持仓总市值与浮动盈亏。
     *
     * <p>返回 map 键约定：
     * <ul>
     *   <li>{@code "marketValue"} —— 持仓总市值（份额 × 最新净值，已按 4 位小数 HALF_UP）</li>
     *   <li>{@code "profit"}      —— 总浮动盈亏（市值 − 累计本金，可为负）</li>
     * </ul>
     */
    Map<String, BigDecimal> getUserPositionSummary(Long userId);
}
