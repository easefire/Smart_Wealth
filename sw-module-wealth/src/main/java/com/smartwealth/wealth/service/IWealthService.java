package com.smartwealth.wealth.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartwealth.common.result.Result;
import com.smartwealth.wealth.dto.ProfitQueryDTO;
import com.smartwealth.wealth.vo.PlatformDashboardVO;
import com.smartwealth.wealth.vo.ProfitVO;
import com.smartwealth.wealth.vo.RedeemedProfitVO;
import com.smartwealth.wealth.vo.TotalAssetsVO;
import jakarta.validation.Valid;

public interface IWealthService {
    Result<TotalAssetsVO> getTotalAssets(Long userId);

    Result<IPage<ProfitVO>> getHoldingProfitPage(Long userId, @Valid ProfitQueryDTO query);


    Result<IPage<RedeemedProfitVO>> getRedeemedProfitPage(Long userId, @Valid ProfitQueryDTO query);

    Result<PlatformDashboardVO> getPlatformDashboardSummary();
}
