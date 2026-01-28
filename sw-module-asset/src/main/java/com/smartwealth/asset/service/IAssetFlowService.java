package com.smartwealth.asset.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.asset.dto.AdminFlowQueryDTO;
import com.smartwealth.asset.dto.FlowStatisticsVO;
import com.smartwealth.asset.vo.AdminFlowVO;
import com.smartwealth.asset.vo.AssetFlowVO;
import com.smartwealth.asset.vo.ProductHeatmapVO;
import com.smartwealth.asset.entity.AssetFlow;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartwealth.common.result.Result;

/**
 * <p>
 * 资金流水表 服务类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface IAssetFlowService extends IService<AssetFlow> {

    Result<IPage<AdminFlowVO>> getPlatformFlowPage(AdminFlowQueryDTO query);

    Result<FlowStatisticsVO> getFlowDistributionStats(AdminFlowQueryDTO query);

    Result<IPage<ProductHeatmapVO>> getProductHeatmapStats(AdminFlowQueryDTO query);

    Page<AssetFlowVO> getUserFlowPage(Long userId, Integer current, Integer size, String type);
}
