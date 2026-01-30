package com.smartwealth.asset.controller.admin;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartwealth.asset.dto.AdminFlowQueryDTO;
import com.smartwealth.asset.vo.FlowStatisticsVO;
import com.smartwealth.asset.vo.AdminFlowVO;
import com.smartwealth.asset.vo.ProductHeatmapVO;
import com.smartwealth.asset.service.IAssetFlowService;
import com.smartwealth.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理端-资产管理")
@RestController
@RequestMapping("/sw/admin/asset")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class AssetController {

    @Autowired
    private IAssetFlowService assetService;
    /**
     * 分页查询全平台流水审计列表
     * 权限要求：必须具备 ADMIN 角色
     */
    @Operation(summary = "全平台流水审计分页查询", description = "支持跨用户、跨业务类型的上帝视角审计")
    @GetMapping("/page")
    @PreAuthorize("hasRole('ADMIN')") // 关键：基于角色的访问控制 (RBAC)
    public Result<IPage<AdminFlowVO>> getPlatformFlowPage(@ParameterObject @Validated AdminFlowQueryDTO query) {
        // 直接调用 Service 层处理复杂的批量映射与穿透逻辑
        return assetService.getPlatformFlowPage(query);
    }

    /**
     * 获取全平台资金流向分类统计
     * 用于管理员看板的饼图（Pie Chart）展示
     */
    @Operation(summary = "资金分类构成统计", description = "按交易类型汇总金额、占比及笔数")
    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')") // 仅限管理员执行全量聚合统计
    public Result<FlowStatisticsVO> getFlowDistributionStats(@ParameterObject @Validated AdminFlowQueryDTO query) {
        // 调用 Service 层执行 GROUP BY 聚合逻辑
        return assetService.getFlowDistributionStats(query);
    }

    @Operation(summary = "产品资金热力监控", description = "分页分析各产品的资金净流入/流出趋势")
    @GetMapping("/product-heatmap")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<IPage<ProductHeatmapVO>> getProductHeatmapStats(@ParameterObject AdminFlowQueryDTO query) {
        return assetService.getProductHeatmapStats(query);
    }


}
