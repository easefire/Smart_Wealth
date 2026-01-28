package com.smartwealth.user.controller;

import com.smartwealth.common.result.Result;
import com.smartwealth.user.dto.BankCardBindDTO;
import com.smartwealth.user.dto.UserRealNameDTO;
import com.smartwealth.user.dto.UserRiskAssessmentDTO;
import com.smartwealth.user.vo.BankCardVO;
import com.smartwealth.user.service.IUserBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 用户基础信息表 前端控制器
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@RestController
@RequestMapping("/sw/user/base")
@Tag(name = "用户端-信息完善")
public class UserBaseController {
    @Autowired
    private IUserBaseService userService;

    @Operation(summary = "实名认证")
    @PutMapping("/real-name")
    public Result<String> realNameAuth(@Valid @RequestBody UserRealNameDTO dto) {
        userService.realNameAuth(dto);
        return Result.success("实名认证成功");
    }

    @Operation(summary = "风险测评")
    @PutMapping("/risk-assessment")
    public Result<Integer> riskAssessment(@Valid @RequestBody UserRiskAssessmentDTO dto) {
        Integer level = userService.doRiskAssessment(dto);
        return Result.success(level);
    }

    @Operation(summary = "绑定新银行卡")
    @PostMapping("/bank-card/bind")
    public Result<Void> bindCard(@Valid @RequestBody BankCardBindDTO dto) {
        userService.bindBankCard(dto);
        return Result.success();
    }

    @Operation(summary = "解绑银行卡")
    @DeleteMapping("/bank-card/{id}")
    public Result<Void> deleteCard(@Parameter(description = "银行卡记录ID") @PathVariable("id") Long id) {
        userService.removeBankCard(id);
        return Result.success();
    }

    @Operation(summary = "查询我的银行卡列表")
    @GetMapping("/bank-card/list")
    public Result<List<BankCardVO>> listCards() {
        return Result.success(userService.queryMyBankCards());
    }


}
