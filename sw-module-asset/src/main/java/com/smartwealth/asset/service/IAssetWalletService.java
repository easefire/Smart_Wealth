package com.smartwealth.asset.service;

import com.smartwealth.asset.dto.RechargeDTO;
import com.smartwealth.asset.dto.WithdrawDTO;
import com.smartwealth.asset.vo.WalletVO;
import com.smartwealth.asset.entity.AssetWallet;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartwealth.common.result.Result;
import jakarta.validation.Valid;

/**
 * <p>
 * 用户钱包总账表 服务类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface IAssetWalletService extends IService<AssetWallet> {
    void initWallet(Long userId);

    Result<String> recharge(Long userId, @Valid RechargeDTO dto);

    void setPayPassword(Long userId, String password);

    Result<String> withdraw(Long userId, @Valid WithdrawDTO dto);


    WalletVO getBalanceInfo(Long userId);
}
