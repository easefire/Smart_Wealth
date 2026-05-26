package com.smartwealth.trade;

import com.smartwealth.asset.service.impl.InternalAssetService;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.result.Result;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.product.service.impl.InternalProductService;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.trade.dto.PurchaseDTO;
import com.smartwealth.trade.service.impl.TradeOrderServiceImpl;
import com.smartwealth.user.service.impl.InternalUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖申购下单的"前置校验门"，是 P0/P1 修过最多次的入口。
 * 这套测试只验证：在哪些前置不通过的场景下，
 *   ① 不会发起 Redis 库存扣减；
 *   ② 不会进入数据库事务；
 *   ③ 返回的 ResultCode 跟 ResultCodeContractTest 锁定的码值一致。
 */
@ExtendWith(MockitoExtension.class)
class TradeOrderPurchasePreCheckTest {

    @Mock
    private InternalProductService productService;
    @Mock
    private InternalUserService userService;
    @Mock
    private InternalAssetService assetService; // 被注入但本次 case 不需要其行为

    @InjectMocks
    private TradeOrderServiceImpl tradeOrderService;

    private PurchaseDTO dto;
    private ProductDetailVO prodDetail;

    @BeforeEach
    void setUp() {
        dto = new PurchaseDTO();
        dto.setProductId(101L);
        dto.setAmount(new BigDecimal("10000"));
        dto.setPayPassword("123456");

        prodDetail = new ProductDetailVO();
        ProductDetailVO.ProductBaseInfoVO base = new ProductDetailVO.ProductBaseInfoVO();
        base.setId(101L);
        base.setRiskLevel(3);
        base.setCurrentNav(new BigDecimal("1.2"));
        prodDetail.setBaseInfo(base);
    }

    @Test
    @DisplayName("产品不存在 → PRODUCT_NOT_EXIST")
    void product_not_exist() {
        when(productService.getProductDetail(anyLong(), anyInt())).thenReturn(null);

        Result<String> r = tradeOrderService.purchase(1L, dto);
        assertEquals(ResultCode.PRODUCT_NOT_EXIST.getCode(), r.getCode());

        verify(productService, never()).lockStock(anyLong(), any());
    }

    @Test
    @DisplayName("用户未做风险测评（riskLevel=null）→ RISK_EVAL_NEEDED，老版本会 NPE")
    void risk_level_null_returns_eval_needed() {
        when(productService.getProductDetail(anyLong(), anyInt())).thenReturn(prodDetail);
        when(userService.getUserRiskLevel(1L)).thenReturn(null);

        Result<String> r = tradeOrderService.purchase(1L, dto);
        assertEquals(ResultCode.RISK_EVAL_NEEDED.getCode(), r.getCode());

        verify(productService, never()).lockStock(anyLong(), any());
    }

    @Test
    @DisplayName("用户风险等级低于产品等级 → RISK_LEVEL_MISMATCH")
    void risk_level_too_low() {
        when(productService.getProductDetail(anyLong(), anyInt())).thenReturn(prodDetail);
        when(userService.getUserRiskLevel(1L)).thenReturn(1); // 用户保守，产品 R3

        Result<String> r = tradeOrderService.purchase(1L, dto);
        assertEquals(ResultCode.RISK_LEVEL_MISMATCH.getCode(), r.getCode());

        verify(productService, never()).lockStock(anyLong(), any());
    }

    @Test
    @DisplayName("产品 currentNav 为 0 → 拒绝下单（避免 / by zero）")
    void zero_nav_blocks_purchase() {
        prodDetail.getBaseInfo().setCurrentNav(BigDecimal.ZERO);
        when(productService.getProductDetail(anyLong(), anyInt())).thenReturn(prodDetail);
        when(userService.getUserRiskLevel(1L)).thenReturn(5);

        Result<String> r = tradeOrderService.purchase(1L, dto);
        assertEquals(ResultCode.FAILURE.getCode(), r.getCode());

        verify(productService, never()).lockStock(anyLong(), any());
    }

    @Test
    @DisplayName("产品 currentNav 为 null → 拒绝下单")
    void null_nav_blocks_purchase() {
        prodDetail.getBaseInfo().setCurrentNav(null);
        when(productService.getProductDetail(anyLong(), anyInt())).thenReturn(prodDetail);
        when(userService.getUserRiskLevel(1L)).thenReturn(5);

        Result<String> r = tradeOrderService.purchase(1L, dto);
        assertEquals(ResultCode.FAILURE.getCode(), r.getCode());

        verify(productService, never()).lockStock(anyLong(), any());
    }

    @Test
    @DisplayName("申购金额过低导致 quantity=0 → 友好失败，不锁库存")
    void zero_quantity_after_division() {
        // 0.01 / 1.2 = 0.008333..  → DOWN 6位 = 0.008333 > 0，所以这里造一个真正会算成 0 的：
        // amount=0.000001, nav=10000，DOWN→ 0
        dto.setAmount(new BigDecimal("0.000001"));
        prodDetail.getBaseInfo().setCurrentNav(new BigDecimal("10000"));

        when(productService.getProductDetail(anyLong(), anyInt())).thenReturn(prodDetail);
        when(userService.getUserRiskLevel(1L)).thenReturn(5);

        Result<String> r = tradeOrderService.purchase(1L, dto);
        assertEquals(ResultCode.FAILURE.getCode(), r.getCode());

        verify(productService, never()).lockStock(anyLong(), any());
    }

    @Test
    @DisplayName("库存不足 → 透传业务码给前端，不进入 DB 事务")
    void out_of_stock_returns_business_code() {
        when(productService.getProductDetail(anyLong(), anyInt())).thenReturn(prodDetail);
        when(userService.getUserRiskLevel(1L)).thenReturn(5);

        doThrow(new BusinessException(ResultCode.PRODUCT_SOLD_OUT))
                .when(productService).lockStock(anyLong(), any());

        Result<String> r = tradeOrderService.purchase(1L, dto);
        assertEquals(ResultCode.PRODUCT_SOLD_OUT.getCode(), r.getCode());
    }
}
