package com.smartwealth.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.asset.dto.AssetCheckDTO;
import com.smartwealth.asset.entity.AssetWallet;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * <p>
 * 用户钱包总账表 Mapper 接口
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface AssetWalletMapper extends BaseMapper<AssetWallet> {

    AssetWallet getWalletByUserId(Long userId);

    BigDecimal getTotalWalletBalance();

    @Select("select * from t_asset_wallet where user_id = #{userId} for update")
    AssetWallet selectforupdate(Long userId);

    List<AssetCheckDTO> checkAssetSnapshotMismatch();
}

