package com.smartwealth.trade.controller.admin;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartwealth.common.result.Result;
import com.smartwealth.trade.dto.AdminOrderQueryDTO;
import com.smartwealth.trade.vo.AdminOrderVO;
import com.smartwealth.trade.service.ITradeOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理端-交易管理")
@RestController
@RequestMapping("sw/admin/trade")
public class TradeController {

    @Autowired
    private ITradeOrderService tradeOrderService;

    @Operation(summary = "全平台订单详情查询", description = "管理员分页查看所有用户的交易订单")
    @GetMapping("/orders-page")
    @PreAuthorize("hasRole('ADMIN')") // 垂直权限控制
    public Result<IPage<AdminOrderVO>> getAdminOrderPage(@ParameterObject AdminOrderQueryDTO query) {
        return tradeOrderService.getAdminOrderPage(query);
    }
}
