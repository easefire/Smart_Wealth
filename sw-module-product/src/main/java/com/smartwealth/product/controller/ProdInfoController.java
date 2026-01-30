package com.smartwealth.product.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartwealth.common.result.Result;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.product.vo.ProductVO;
import com.smartwealth.product.service.IProdInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 理财产品信息表 前端控制器
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Tag(name = "用户端-产品信息")
@RestController
@RequestMapping("/sw/user/product")
@PreAuthorize("hasRole('USER')")
public class ProdInfoController {
    @Autowired
    private IProdInfoService productService;

    @Operation(summary = "查询所有在售产品")
    @GetMapping("/list")
    public Result<IPage<ProductVO>> listAll(@RequestParam(value = "current", defaultValue = "1") Integer current,
                                            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        return Result.success(productService.getUserProductPage(current,size));
    }

    @Operation(summary = "获取产品详情画像")
    @GetMapping("/info/{prodId}")
    public Result<ProductDetailVO> getProductDetail(
            @PathVariable("prodId") Long prodId,
            @RequestParam(value = "days", defaultValue = "7") Integer days) {

        // 调用业务层获取聚合后的对象
        ProductDetailVO detail = productService.getProductDetail(prodId, days);

        return Result.success(detail);
    }

}
