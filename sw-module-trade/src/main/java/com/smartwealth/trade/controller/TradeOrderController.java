package com.smartwealth.trade.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.common.context.UserContext;
import com.smartwealth.common.result.Result;
import com.smartwealth.trade.dto.PurchaseDTO;
import com.smartwealth.trade.dto.RedemptionDTO;
import com.smartwealth.trade.vo.OrderHistoryVO;
import com.smartwealth.trade.vo.PositionVO;
import com.smartwealth.trade.service.ITradeOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 理财交易订单表 前端控制器
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Tag(name = "用户端-产品交易")
@RestController
@RequestMapping("sw/user/trade")
public class TradeOrderController {

    @Autowired
    private ITradeOrderService tradeOrderService;

    @Operation(summary = "申购理财产品")
    @PostMapping("/purchase")
    public Result<String> purchase(@Valid @RequestBody PurchaseDTO dto) {
        Long userId = UserContext.getUserId(); // 从上下文获取用户ID
        return tradeOrderService.purchase(userId, dto);
    }

    @Operation(summary = "查看我的持仓列表")
    @GetMapping("/positions")
    public Result<IPage<PositionVO>> listMyPositions(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        Long userId = UserContext.getUserId();
        return tradeOrderService.listMyPositions(userId, current, size);
    }

    @Operation(summary = "分页查询个人订单历史")
    @GetMapping("/orders-history")
    public Result<Page<OrderHistoryVO>> getOrderHistory(
            @Parameter(description = "页码") @RequestParam(value = "current", defaultValue = "1") Integer current,
            @Parameter(description = "每页大小") @RequestParam(value = "size", defaultValue = "10") Integer size)   {
        // 1. 参数合法性校验
        if (size > 100) {
            size = 100; // 防止恶意大分页查询拖慢数据库
        }
        // 2. 从上下文获取当前登录用户 ID
        Long userId = UserContext.getUserId();

        // 3. 调用 Service 执行分页查询并返回结果
        Page<OrderHistoryVO> result = tradeOrderService.getOrderHistory(userId, current, size);

        return Result.success(result);
    }

    @Operation(summary = "按产品批量赎回")
    @PostMapping("/redeem")
    public Result<String> redeemByProduct(@Valid @RequestBody RedemptionDTO dto) {
        // 1. 从安全上下文获取当前用户 ID
        Long userId = UserContext.getUserId();
        // 2. 调用 Service 执行 FIFO 核销逻辑
        // 内部涉及：多笔订单筛选、到期校验、份额扣减、资产入账
        return tradeOrderService.redeemByProduct(userId, dto);
    }


}
