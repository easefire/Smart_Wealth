package com.smartwealth.wealth.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.asset.entity.AssetFlow;
import com.smartwealth.asset.entity.AssetWallet;
import com.smartwealth.asset.enums.TransactionTypeEnum;
import com.smartwealth.asset.service.impl.InternalAssetService;
import com.smartwealth.common.result.Result;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.service.impl.InternalProductService;
import com.smartwealth.trade.entity.DailyProfit;
import com.smartwealth.trade.entity.TradeOrder;
import com.smartwealth.trade.enums.TradeStatusEnum;
import com.smartwealth.trade.service.impl.InternalTradeService;
import com.smartwealth.wealth.dto.ProfitQueryDTO;
import com.smartwealth.wealth.vo.PlatformDashboardVO;
import com.smartwealth.wealth.vo.ProfitVO;
import com.smartwealth.wealth.vo.RedeemedProfitVO;
import com.smartwealth.wealth.vo.TotalAssetsVO;
import com.smartwealth.wealth.service.IWealthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class WealthServiceImpl implements IWealthService {

    private static final Pattern BATCH_RATE_PATTERN = Pattern.compile("批量收益率: (\\d+\\.\\d+)%");

    @Autowired
    private InternalAssetService internalAssetService; // 依赖 Asset 模块

    @Autowired
    private InternalTradeService internalTradeService; // 依赖 Trade 模块

    @Autowired
    private InternalProductService productService;

    // 用户查看总资产
    @Override
    public Result<TotalAssetsVO> getTotalAssets(Long userId) {
        // 1. 获取钱包余额
        AssetWallet wallet = internalAssetService.getWalletByUserId(userId);
        BigDecimal balance = (wallet != null) ? wallet.getBalance() : BigDecimal.ZERO;

        // 2. 获取持仓汇总（市值与盈亏）
        Map<String, BigDecimal> positionSummary = internalTradeService.getUserPositionSummary(userId);
        BigDecimal marketValue = positionSummary.getOrDefault("marketValue", BigDecimal.ZERO);
        BigDecimal profit = positionSummary.getOrDefault("profit", BigDecimal.ZERO);

        // 3. 组装 VO
        TotalAssetsVO vo = new TotalAssetsVO();
        vo.setWalletBalance(balance);
        vo.setTotalMarketValue(marketValue);
        vo.setTotalAmount(balance.add(marketValue).setScale(4, RoundingMode.HALF_UP));
        vo.setTotalProfit(profit);

        return Result.success(vo);
    }

    // 用户查看持仓浮盈分页
    @Override
    public Result<IPage<ProfitVO>> getHoldingProfitPage(Long userId, ProfitQueryDTO query) {
        // 1. 获取该产品最早的持仓订单，作为业务逻辑的“时间地平线”
        TradeOrder earliestOrder = internalTradeService.getOne(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getUserId, userId)
                .eq(TradeOrder::getProdId, query.getProductId())
                .orderByAsc(TradeOrder::getCreateTime)
                .last("LIMIT 1"));

        if (earliestOrder == null) {
            return Result.success(new Page<>());
        }

        // 2. 核心校验：查询起始日期不能早于首次购买日期
        if (query.getStartDate() != null && query.getStartDate().isBefore(earliestOrder.getCreateTime().toLocalDate())) {
            // 直接拦截非法请求，体现银行项目的严谨性
            return Result.fail("查询起始日期不能早于产品首次买入日期：" + earliestOrder.getCreateTime().toLocalDate());
        }

        // 1. 判断是否为“全生命周期”查询（即未传起始日期）
        if (query.getStartDate() == null && query.getProductId() != null) {
            return getFullLifecycleProfit(userId, query);
        }

        // 2. 正常的分页查询逻辑（查询每日波动明细）
        Page<DailyProfit> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<DailyProfit> wrapper = new LambdaQueryWrapper<DailyProfit>()
                .eq(DailyProfit::getUserId, userId)
                .eq(DailyProfit::getType, 1) // 仅限持仓浮盈
                .eq(DailyProfit::getProdId, query.getProductId())
                .ge(query.getStartDate() != null, DailyProfit::getProfitDate, query.getStartDate())
                .le(query.getEndDate() != null, DailyProfit::getProfitDate, query.getEndDate())
                .orderByDesc(DailyProfit::getProfitDate);

        IPage<DailyProfit> profitPage = internalTradeService.selectPageDaily(page, wrapper);
        return Result.success(convertToProfitVO(profitPage, query));
    }

    // 用户获取产品全生命周期持仓浮盈汇总
    private Result<IPage<ProfitVO>> getFullLifecycleProfit(Long userId, ProfitQueryDTO query) {
        // 1. 获取该产品下的所有持仓订单
        List<TradeOrder> holdingOrders = internalTradeService.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getUserId, userId)
                .eq(TradeOrder::getProdId, query.getProductId())
                .eq(TradeOrder::getStatus, TradeStatusEnum.HOLDING)); //

        if (CollectionUtils.isEmpty(holdingOrders)) {
            return Result.success(new Page<>());
        }

        // 2. 获取实时净值并计算
        ProdInfo prod = productService.getById(query.getProductId());
        BigDecimal currentNav = prod.getCurrentNav();

        // 3. 计算起始时间（最早的一笔订单创建时间）
        LocalDateTime earliestTime = holdingOrders.stream()
                .map(TradeOrder::getCreateTime).min(LocalDateTime::compareTo).get();

        // 4. 计算实时总浮盈：(当前总份额 * 最新净值) - 剩余本金成本
        BigDecimal totalProfit = holdingOrders.stream()
                .map(order -> order.getQuantity().multiply(currentNav).subtract(order.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 5. 封装为分页对象（此时只有 1 条汇总记录）
        ProfitVO vo = new ProfitVO();
        vo.setProdName(prod.getName());
        vo.setProfit(totalProfit.setScale(4, RoundingMode.HALF_UP));
        vo.setStartTime(earliestTime); // 默认从订单开始时间
        vo.setEndTime(LocalDateTime.now()); // 默认到今天
        vo.setQueryTime(LocalDateTime.now());

        Page<ProfitVO> resultPage = new Page<>(1, 1);
        resultPage.setRecords(Collections.singletonList(vo));
        resultPage.setTotal(1);
        return Result.success(resultPage);
    }

    // 将 DailyProfit 分页转换为 ProfitVO 分页
    private IPage<ProfitVO> convertToProfitVO(IPage<DailyProfit> profitPage, ProfitQueryDTO query) {
        // 1. 批量获取产品名称，优化查询性能
        Set<Long> prodIds = profitPage.getRecords().stream()
                .map(DailyProfit::getProdId).collect(Collectors.toSet());
        Map<Long, String> prodNameMap = productService.getProdNamesByIds(prodIds);

        // 2. 执行分页转换
        return profitPage.convert(item -> {
            ProfitVO vo = new ProfitVO();

            // A. 基础字段赋值
            vo.setProdName(prodNameMap.getOrDefault(item.getProdId(), "未知产品"));
            vo.setProfit(item.getDailyProfit().setScale(4, RoundingMode.HALF_UP));

            // B. 估值执行时间：记录系统处理该数据的时刻
            vo.setQueryTime(LocalDateTime.now());

            // C. 收益跨度设定
            // 由于是从 t_trade_daily_profit 表查出的每日记录，
            // 每一行的收益跨度即为该收益归属日 (00:00:00 - 23:59:59)
            vo.setStartTime(item.getProfitDate().atStartOfDay());
            vo.setEndTime(item.getProfitDate().atTime(LocalTime.MAX));

            return vo;
        });
    }

    // 用户查看已赎回收益分页
    @Override
    public Result<IPage<RedeemedProfitVO>> getRedeemedProfitPage(Long userId, ProfitQueryDTO query) {
        // 1. 初始化分页对象，目标是 AssetFlow (流水表)
        Page<AssetFlow> page = new Page<>(query.getCurrent(), query.getSize());

        // 2. 构造查询条件：锁定用户、锁定“收益入账”类型
        LambdaQueryWrapper<AssetFlow> wrapper = new LambdaQueryWrapper<AssetFlow>()
                .eq(AssetFlow::getUserId, userId)
                .eq(AssetFlow::getType, TransactionTypeEnum.INCOME) // 仅查已实现收益
                .orderByDesc(AssetFlow::getCreateTime);

        // 3. 【核心指令实现】仅根据产品ID过滤，显式忽略 startDate 和 endDate
        if (query.getProductId() != null) {
            wrapper.eq(AssetFlow::getBizId, query.getProductId()); // biz_id 对应产品ID
        }

        // 4. 执行物理分页查询
        IPage<AssetFlow> flowPage = internalAssetService.selectPage(page, wrapper);

        if (flowPage.getRecords().isEmpty()) {
            return Result.success(new Page<>());
        }

        // 5. 【性能优化】批量获取产品名称
        Set<Long> prodIds = flowPage.getRecords().stream()
                .map(AssetFlow::getBizId).collect(Collectors.toSet());
        Map<Long, String> prodNameMap = productService.getProdNamesByIds(prodIds);

        // 6. 转换为已赎回专用 VO (RedeemedProfitVO)
        IPage<RedeemedProfitVO> voPage = flowPage.convert(flow -> {
            RedeemedProfitVO vo = new RedeemedProfitVO();
            vo.setProdName(prodNameMap.getOrDefault(flow.getBizId(), "未知产品"));

            // 流水表中的 amount 即为该笔赎回固化下来的净收益
            vo.setTotalProfit(flow.getAmount().setScale(4, RoundingMode.HALF_UP));

            // 2. 使用正则提取收益率
            String remark = flow.getRemark();
            if (remark != null) {
                Matcher matcher = BATCH_RATE_PATTERN.matcher(remark);
                if (matcher.find()) {
                    // matcher.group(1) 提取出的就是 "15.50" 这种纯数字字符串
                    try {
                        vo.setProfitRate(matcher.group(1) + "%");
                    } catch (NumberFormatException e) {
                        vo.setProfitRate("0%");
                    }
                }
            }

            return vo;
        });

        return Result.success(voPage);
    }

    // 管理员获取平台看板数据汇总
    @Override
    public Result<PlatformDashboardVO> getPlatformDashboardSummary() {
        // 1. 计算平台用户总余额 (Sum of t_wallet.balance)
        BigDecimal totalWalletBalance = internalAssetService.getTotalWalletBalance();

        // 2. 获取所有“持有中”的订单
        List<TradeOrder> holdingOrders = internalTradeService.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getStatus, TradeStatusEnum.HOLDING));

        if (CollectionUtils.isEmpty(holdingOrders)) {
            return Result.success(buildEmptyDashboard(totalWalletBalance));
        }

        // 3. 批量获取产品最新净值，用于市值重估
        Set<Long> prodIds = holdingOrders.stream().map(TradeOrder::getProdId).collect(Collectors.toSet());
        Map<Long, BigDecimal> navMap = productService.getProdNavMap(prodIds);

        // 4. 实时计算持仓市值与浮盈
        // 市值 = 份额 * 实时净值
        // 浮盈 = 市值 - 剩余本金成本
        BigDecimal totalHoldingValue = BigDecimal.ZERO;
        BigDecimal totalFloatingProfit = BigDecimal.ZERO;

        for (TradeOrder order : holdingOrders) {
            BigDecimal nav = navMap.getOrDefault(order.getProdId(), BigDecimal.ZERO);
            BigDecimal marketValue = order.getQuantity().multiply(nav);
            BigDecimal profit = marketValue.subtract(order.getAmount());

            totalHoldingValue = totalHoldingValue.add(marketValue);
            totalFloatingProfit = totalFloatingProfit.add(profit);
        }

        // 5. 组装看板数据
        PlatformDashboardVO vo = new PlatformDashboardVO();
        vo.setTotalWalletBalance(totalWalletBalance);
        vo.setTotalHoldingValue(totalHoldingValue);
        vo.setTotalAum(totalWalletBalance.add(totalHoldingValue));
        vo.setTotalFloatingProfit(totalFloatingProfit);
        vo.setSnapshotTime(LocalDateTime.now());

        return Result.success(vo);
    }

    // 管理员构建空持仓时的平台看板数据
    private PlatformDashboardVO buildEmptyDashboard(BigDecimal totalWalletBalance) {
        PlatformDashboardVO vo = new PlatformDashboardVO();

        // 1. 基础余额赋值，若传入为 null 则兜底为 ZERO
        BigDecimal balance = totalWalletBalance != null ? totalWalletBalance : BigDecimal.ZERO;
        vo.setTotalWalletBalance(balance.setScale(4, RoundingMode.HALF_UP));

        // 2. 核心业务字段全部初始化为 0，避免前端出现 undefined 或 NaN
        vo.setTotalHoldingValue(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        vo.setTotalFloatingProfit(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));

        // 3. 总资产 AUM 此时等于总余额
        // 公式：$$TotalAUM = Balance + 0$$
        vo.setTotalAum(balance.setScale(4, RoundingMode.HALF_UP));

        // 4. 记录统计快照时刻
        vo.setSnapshotTime(LocalDateTime.now());

        return vo;
    }


}
