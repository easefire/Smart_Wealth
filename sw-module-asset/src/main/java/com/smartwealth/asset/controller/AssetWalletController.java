package com.smartwealth.asset.controller;

import com.smartwealth.asset.dto.PayPasswordRequestDTO;
import com.smartwealth.asset.dto.RechargeDTO;
import com.smartwealth.asset.dto.WithdrawDTO;
import com.smartwealth.asset.vo.WalletVO;
import com.smartwealth.asset.service.IAssetWalletService;
import com.smartwealth.common.context.UserContext;
import com.smartwealth.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 用户钱包总账表 前端控制器
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@RestController
@RequestMapping("/sw/user/asset-wallet")
@Slf4j
@Tag(name = "用户端-资产钱包")
@PreAuthorize("hasRole('USER')")
public class AssetWalletController {
    @Autowired
    private IAssetWalletService walletService;

    @Operation(summary = "钱包充值", description = "用户向钱包充值资金")
    @PostMapping("/recharge")
    public Result<String> recharge(@Valid @RequestBody RechargeDTO dto) {
        Long userId = UserContext.getUserId();
        return walletService.recharge(userId, dto);
    }

    @Operation(summary = "设置支付密码")
    @PutMapping("/pay-password")
    public Result<Void> setPayPassword(@Valid @RequestBody PayPasswordRequestDTO request) {
        Long userId = UserContext.getUserId();
        walletService.setPayPassword(userId, request.getPassword());
        return Result.success();
    }

    @Operation(summary = "用户提现")
    @PostMapping("/withdraw")
    public Result<String> withdraw(@Valid @RequestBody WithdrawDTO dto) {
        Long userId = UserContext.getUserId();
        return walletService.withdraw(userId, dto);
    }

    @Operation(summary = "查询钱包余额", description = "获取当前登录用户的钱包总余额与可用余额")
    @GetMapping("/balance")
    public Result<WalletVO> getBalance() {
        Long userId = UserContext.getUserId();
        WalletVO balanceInfo = walletService.getBalanceInfo(userId);
        return Result.success(balanceInfo);
    }




}
