package com.smartwealth.wealth.controller;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartwealth.common.result.Result;
import com.smartwealth.common.context.UserContext;
import com.smartwealth.wealth.dto.ProfitQueryDTO;
import com.smartwealth.wealth.vo.ProfitVO;
import com.smartwealth.wealth.vo.RedeemedProfitVO;
import com.smartwealth.wealth.service.IWealthService;
import com.smartwealth.wealth.vo.TotalAssetsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户端-财富查看")
@RestController
@Slf4j
@RequestMapping("/sw/user/wealth")
public class WealthController {

    @Autowired
    private IWealthService wealthService;

    @Operation(summary = "查看总资产大盘")
    @GetMapping("/total-assets")
    public Result<TotalAssetsVO> getTotalAssets() {
        Long userId = UserContext.getUserId();
        return wealthService.getTotalAssets(userId);
    }

    @Operation(summary = "查询用户持有中收益明细")
    @GetMapping("/profit-holding")
    public Result<IPage<ProfitVO>> getProfitDetailsPage(@ParameterObject @Valid ProfitQueryDTO query) {
        Long userId = UserContext.getUserId(); //
        return wealthService.getHoldingProfitPage(userId, query);
    }

    @Operation(summary = "查询用户已赎回收益明细")
    @GetMapping("/profit-redeemed")
    public Result<IPage<RedeemedProfitVO>> getRedeemedProfitDetailsPage(@ParameterObject @Valid ProfitQueryDTO query) {
        Long userId = UserContext.getUserId(); //
        return wealthService.getRedeemedProfitPage(userId, query);
    }
}