package com.smartwealth.asset.service.impl;

import cn.hutool.core.util.DesensitizedUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.asset.dto.AdminFlowQueryDTO;
import com.smartwealth.asset.dto.FlowStatisticsVO;
import com.smartwealth.asset.vo.AdminFlowVO;
import com.smartwealth.asset.vo.AssetFlowVO;
import com.smartwealth.asset.vo.ProductHeatmapVO;
import com.smartwealth.asset.entity.AssetFlow;
import com.smartwealth.asset.enums.TransactionTypeEnum;
import com.smartwealth.asset.mapper.AssetFlowMapper;
import com.smartwealth.asset.service.IAssetFlowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartwealth.common.result.Result;
import com.smartwealth.product.service.impl.InternalProductService;
import com.smartwealth.user.entity.UserBase;
import com.smartwealth.user.service.impl.InternalUserService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 资金流水表 服务实现类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Service
public class AssetFlowServiceImpl extends ServiceImpl<AssetFlowMapper, AssetFlow> implements IAssetFlowService {

    @Autowired
    private AssetFlowMapper assetFlowMapper;
    @Autowired
    private InternalUserService userService;
    @Autowired
    private InternalProductService productService;

    /**
     * 管理员视角
     * 全平台流水审计分页查询
     * 支持按用户、产品、交易类型及时间范围过滤
     * 关键点在于批量拉取用户与产品信息，解决 N+1 查询问题
     */

    //获取平台流水分页
    @Override
    public Result<IPage<AdminFlowVO>> getPlatformFlowPage(AdminFlowQueryDTO query) {
        // 1. 构建分页对象与查询条件
        Page<AssetFlow> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<AssetFlow> wrapper = new LambdaQueryWrapper<AssetFlow>()
                .eq(query.getTargetUserId() != null, AssetFlow::getUserId, query.getTargetUserId())
                .eq(query.getProductId() != null, AssetFlow::getBizId, query.getProductId())
                .in(!CollectionUtils.isEmpty(query.getTypes()), AssetFlow::getType, query.getTypes())
                .ge(query.getStartDate() != null, AssetFlow::getCreateTime, query.getStartDate().atStartOfDay())
                .le(query.getEndDate() != null, AssetFlow::getCreateTime, query.getEndDate().atTime(LocalTime.MAX))
                .orderByDesc(AssetFlow::getCreateTime); // 审计通常关注最新变动

        // 2. 执行流水表物理分页
        IPage<AssetFlow> flowPage = assetFlowMapper.selectPage(page, wrapper);
        if (flowPage.getRecords().isEmpty()) {
            return Result.success(new Page<>());
        }

        // 3. 批量拉取关联信息（用户与产品），解决 N+1 问题
        Set<Long> userIds = flowPage.getRecords().stream().map(AssetFlow::getUserId).collect(Collectors.toSet());
        Set<Long> prodIds = flowPage.getRecords().stream().map(AssetFlow::getBizId).collect(Collectors.toSet());

        Map<Long, UserBase> userMap = userService.getUsersByIds(userIds); // 批量查用户
        Map<Long, String> prodNameMap = productService.getProdNamesByIds(prodIds); // 批量查产品

        // 4. 组装 VO 列表
        IPage<AdminFlowVO> voPage = flowPage.convert(flow -> {
            AdminFlowVO vo = new AdminFlowVO();
            vo.setFlowId(flow.getId());
            vo.setUserId(flow.getUserId());

            // 填充用户信息
            UserBase user = userMap.get(flow.getUserId());
            vo.setUserName(user != null ? user.getUsername() : "未知用户");
            vo.setUserPhone(user != null ? DesensitizedUtil.mobilePhone(user.getPhone()) : "-");

            // 填充业务信息
            vo.setProdName(prodNameMap.getOrDefault(flow.getBizId(), "账户余额变动"));
            vo.setAmount(flow.getAmount().setScale(4, RoundingMode.HALF_UP));
            vo.setBalance(flow.getBalanceSnapshot().setScale(4, RoundingMode.HALF_UP));
            vo.setTypeDesc(flow.getType().getDescription());
            vo.setRemark(flow.getRemark());
            vo.setTime(flow.getCreateTime());
            return vo;
        });

        return Result.success(voPage);
    }
    // 获取资金流向分布统计
    @Override
    public Result<FlowStatisticsVO> getFlowDistributionStats(AdminFlowQueryDTO query) {
        // 1. 设置统计时间范围（默认为今日）
        LocalDateTime start = query.getStartDate() != null ?
                query.getStartDate().atStartOfDay() : LocalDate.now().atStartOfDay();
        LocalDateTime end = query.getEndDate() != null ?
                query.getEndDate().atTime(LocalTime.MAX) : LocalDateTime.now();

        // 2. 调用 Mapper 执行分组统计
        // 返回结果通常封装为 List<Map<String, Object>> 或专用 DTO
        List<FlowStatResult> stats = assetFlowMapper.selectFlowStats(start, end);

        // 3. 计算总金额用于计算百分比
        BigDecimal totalAmount = stats.stream()
                .map(FlowStatResult::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 4. 组装 VO
        List<FlowStatisticsVO.StatItem> items = stats.stream().map(res -> {
            TransactionTypeEnum type = res.getType();
            BigDecimal amt = res.getTotalAmount().setScale(2, RoundingMode.HALF_UP);

            Double percent = totalAmount.compareTo(BigDecimal.ZERO) > 0 ?
                    amt.divide(totalAmount, 4, RoundingMode.HALF_UP).doubleValue() * 100 : 0.0;

            return new FlowStatisticsVO.StatItem(
                    type.getDescription(),
                    type.name(),
                    amt,
                    percent,
                    res.getTradeCount()
            );
        }).collect(Collectors.toList());

        FlowStatisticsVO vo = new FlowStatisticsVO();
        vo.setItems(items);
        vo.setTotalPeriodAmount(totalAmount.setScale(2, RoundingMode.HALF_UP));

        return Result.success(vo);
    }
    // 获取产品热力图统计
    @Override
    public Result<IPage<ProductHeatmapVO>> getProductHeatmapStats(AdminFlowQueryDTO query) {
        // 1. 初始化分页对象
        Page<ProductHeatmapVO> page = new Page<>(query.getCurrent(), query.getSize());

        // 2. 确定统计区间
        LocalDateTime start = query.getStartDate() != null ?
                query.getStartDate().atStartOfDay() : LocalDate.now().minusDays(30).atStartOfDay();
        LocalDateTime end = query.getEndDate() != null ?
                query.getEndDate().atTime(LocalTime.MAX) : LocalDateTime.now();

        // 3. 执行 SQL 物理分页查询
        // 此时每一行记录已经包含了一个产品的流入、流出总额
        IPage<ProductHeatmapVO> heatmapPage = assetFlowMapper.selectProductHeatmapPage(page, start, end);

        if (heatmapPage.getRecords().isEmpty()) {
            return Result.success(heatmapPage);
        }

        // 4. 批量获取产品名称（N+1 优化）
        Set<Long> prodIds = heatmapPage.getRecords().stream()
                .map(ProductHeatmapVO::getProductId).collect(Collectors.toSet());
        Map<Long, String> prodNameMap = productService.getProdNamesByIds(prodIds);

        // 5. 补全名称与计算净流入
        heatmapPage.getRecords().forEach(vo -> {
            vo.setProductName(prodNameMap.getOrDefault(vo.getProductId(), "未知产品"));

            // 计算公式：$$NetInflow = TotalInflow - TotalOutflow$$
            vo.setNetInflow(vo.getTotalInflow().subtract(vo.getTotalOutflow())
                    .setScale(4, RoundingMode.HALF_UP));
        });

        return Result.success(heatmapPage);
    }

    /**
     * 流水统计结果 DTO
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FlowStatResult {
        private TransactionTypeEnum type; // 交易类型
        private BigDecimal totalAmount;   // 该类型汇总金额
        private Long tradeCount;          // 该类型交易笔数
    }
    /**
     * 用户视角
     * 分页查询指定用户的资金流水
     * 支持按交易类型过滤
     */
    @Override
    public Page<AssetFlowVO> getUserFlowPage(Long userId, Integer current, Integer size, String type) {
        // 1. 构造分页条件
        Page<AssetFlow> page = new Page<>(current, size);

        // 2. 构造查询条件
        LambdaQueryWrapper<AssetFlow> wrapper = new LambdaQueryWrapper<AssetFlow>()
                .eq(AssetFlow::getUserId, userId)
                .eq(StringUtils.hasText(type), AssetFlow::getType, type)
                .orderByDesc(AssetFlow::getCreateTime); // 最新流水在前

        this.page(page, wrapper);

        // 3. 转换 VO
        Page<AssetFlowVO> voPage = new Page<>(current, size);
        voPage.setTotal(page.getTotal());

        List<AssetFlowVO> voList = page.getRecords().stream().map(flow -> {
            AssetFlowVO vo = new AssetFlowVO();
            BeanUtils.copyProperties(flow, vo);
            vo.setType(flow.getType().getDescription());
            return vo;
        }).collect(Collectors.toList());

        voPage.setRecords(voList);
        return voPage;
    }


}

