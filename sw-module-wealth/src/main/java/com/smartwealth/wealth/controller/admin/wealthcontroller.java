package com.smartwealth.wealth.controller.admin;

import com.smartwealth.common.result.Result;
import com.smartwealth.wealth.vo.PlatformDashboardVO;
import com.smartwealth.wealth.service.IWealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理端-财富管理")
@RestController
@Slf4j
@RequestMapping("/sw/admin/wealth")
@PreAuthorize("hasRole('ADMIN')")
public class wealthcontroller {
    @Autowired
    private IWealthService wealthService;
    @Operation(summary = "获取平台资产概览看板", description = "管理员看板大盘数据，包含总 AUM 和实时浮盈统计")
    @GetMapping("/asset-summary")
    public Result<PlatformDashboardVO> getPlatformDashboardSummary() {
        return wealthService.getPlatformDashboardSummary();
    }
}
