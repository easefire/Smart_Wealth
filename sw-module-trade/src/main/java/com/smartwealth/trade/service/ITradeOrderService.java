package com.smartwealth.trade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.common.result.Result;
import com.smartwealth.trade.dto.AdminOrderQueryDTO;
import com.smartwealth.trade.dto.PurchaseDTO;
import com.smartwealth.trade.dto.RedemptionDTO;
import com.smartwealth.trade.dto.TradeCheckDTO;
import com.smartwealth.trade.entity.TradeLocalMsg;
import com.smartwealth.trade.vo.AdminOrderVO;
import com.smartwealth.trade.vo.OrderHistoryVO;
import com.smartwealth.trade.vo.PositionVO;
import com.smartwealth.trade.entity.TradeOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * <p>
 * 理财交易订单表 服务类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface ITradeOrderService extends IService<TradeOrder> {

    Result<String> purchase(Long userId, @Valid PurchaseDTO dto);

    Result<IPage<PositionVO>> listMyPositions(Long userId, Integer current, Integer size);

    Result<String> redeemByProduct(Long userId, @Valid RedemptionDTO dto);

    Result<IPage<AdminOrderVO>> getAdminOrderPage(AdminOrderQueryDTO query);

    Page<OrderHistoryVO> getOrderHistory(Long userId, Integer current, Integer size);

    void handlePurchaseResult(Long orderId, boolean success, String reason);

    @Transactional(rollbackFor = Exception.class)
    void handleRedemptionResult(Long requestId, boolean success, String reason);

    @Transactional(rollbackFor = Exception.class)
    void executeDailySettlementWithSharding(LocalDate bizDate, int shardIndex, int shardTotal);

    List<TradeCheckDTO> checkIncomeConsistency();
}
