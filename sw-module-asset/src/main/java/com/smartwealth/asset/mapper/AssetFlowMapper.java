package com.smartwealth.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.asset.dto.AssetFlowTradeDTO;
import com.smartwealth.asset.vo.ProductHeatmapVO;
import com.smartwealth.asset.entity.AssetFlow;
import com.smartwealth.asset.service.impl.AssetFlowServiceImpl;
import jakarta.validation.constraints.NotNull;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 资金流水表 Mapper 接口
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface AssetFlowMapper extends BaseMapper<AssetFlow> {

    BigDecimal getTodayRechargeSum(@Param("userId") Long userId, @NotNull(message = "请选择银行卡") @Param("bankCardId")Long bankCardId);

    // 资金流水统计核心查询
    @Select("SELECT type, SUM(amount) as totalAmount, COUNT(*) as tradeCount " +
            "FROM t_asset_flow " +
            "WHERE create_time BETWEEN #{start} AND #{end} " +
            "GROUP BY type")
    List<AssetFlowServiceImpl.FlowStatResult> selectFlowStats(@Param("start") LocalDateTime start,
                                                              @Param("end") LocalDateTime end);

    /**
     * 分页查询产品资金热力数据
     * 核心逻辑：按 biz_id 分组，通过 CASE WHEN 区分流入和流出
     */
    @Select("SELECT " +
            "  biz_id as productId, " +
            "  SUM(CASE WHEN type = 'PURCHASE' THEN ABS(amount) ELSE 0 END) as totalInflow, " +
            "  SUM(CASE WHEN type = 'REDEEM' THEN ABS(amount) ELSE 0 END) as totalOutflow, " +
            "  COUNT(CASE WHEN type = 'PURCHASE' THEN 1 END) as purchaseCount, " +
            "  COUNT(CASE WHEN type = 'REDEEM' THEN 1 END) as redeemCount " +
            "FROM t_asset_flow " +
            "WHERE type IN ('PURCHASE', 'REDEEM') " +
            "  AND create_time BETWEEN #{start} AND #{end} " +
            "GROUP BY biz_id")
    IPage<ProductHeatmapVO> selectProductHeatmapPage(Page<ProductHeatmapVO> page,
                                                     @Param("start") LocalDateTime start,
                                                     @Param("end") LocalDateTime end);

    List<AssetFlowTradeDTO> selectPurchaseFlowsWithRemark();
}

