package com.smartwealth.asset.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.asset.vo.AssetFlowVO;
import com.smartwealth.asset.service.IAssetFlowService;
import com.smartwealth.common.context.UserContext;
import com.smartwealth.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全平台资金流向管理接口
 */
@Tag(name = "用户端-资金流水")
@RestController
@RequestMapping("/sw/user/asset-flow")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('USER')")
public class AssetFlowController {

    private final IAssetFlowService assetService;

    @Operation(summary = "查询个人资金流水", description = "支持按交易类型分页查询")
    @GetMapping("/flows")
    public Result<Page<AssetFlowVO>> getMyFlows(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") Integer size,
            @RequestParam(value = "type", required = false) String type) {
        // 1. 参数校验与限制
        if (size > 100) size = 100;
        // 2. 获取当前用户 ID
        Long userId = UserContext.getUserId();
        // 3. 执行查询
        Page<AssetFlowVO> result = assetService.getUserFlowPage(userId, current, size, type);
        return Result.success(result);
    }


}