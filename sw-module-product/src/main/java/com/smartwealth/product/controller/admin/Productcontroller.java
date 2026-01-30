package com.smartwealth.product.controller.admin;

import com.smartwealth.common.context.UserContext;
import com.smartwealth.common.result.Result;
import com.smartwealth.product.dto.ProductSaveDTO;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.enums.MarketSentiment;
import com.smartwealth.product.service.IMarketService;
import com.smartwealth.product.service.IProdInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理端-产品管理")
@RestController
@RequestMapping("/sw/admin/product")
@PreAuthorize("hasRole('ADMIN')")
public class Productcontroller {
    @Autowired
    private IProdInfoService productService;
    @Autowired
    private IMarketService marketService;
    @Operation(summary = "产品入库")
    @PostMapping("/save")
    public Result<String> save(@Validated @RequestBody ProductSaveDTO dto) {
        productService.initProduct(dto);
        return Result.success("产品入库成功");
    }
    @Operation(summary = "下架产品")
    @PatchMapping("/{id}/off-shelf")
    public Result<String> offShelf(@PathVariable("id") Long id) {
        productService.offShelf(id);
        return Result.success("产品已下架");
    }
    @Operation(summary = "手动设置市场情绪")
    @PostMapping("/sentiment-adjust")
    public Result<String> adjust(@Parameter(description = "市场情绪选择") @RequestParam("sentiment") MarketSentiment sentiment) {
        String operator = UserContext.getUserId().toString();
        marketService.saveSentiment(sentiment, "ADMIN-"+operator);
        return Result.success("市场情绪设置成功"+sentiment.getDescription());
    }
    @Operation(summary = "查询所有在售产品")
    @GetMapping("/list")
    public Result<List<ProdInfo>> listAll() {
        return Result.success(productService.getAllProducts());
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
