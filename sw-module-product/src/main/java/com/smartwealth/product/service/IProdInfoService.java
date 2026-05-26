package com.smartwealth.product.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartwealth.product.dto.ProductSaveDTO;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.product.vo.ProductVO;
import com.smartwealth.product.entity.ProdInfo;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.constraints.NotNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * <p>
 * 理财产品信息表 服务类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface IProdInfoService extends IService<ProdInfo> {

    void initProduct(ProductSaveDTO dto);

    void offShelf(Long id);

    // 获取所有产品（管理端使用）
    List<ProdInfo> getAllProducts();

    IPage<ProductVO> getUserProductPage(Integer pageNo, Integer pageSize);

    ProductDetailVO getProductDetail(Long prodId, Integer days);

    ProductDetailVO getRawProductDetailFromDb(Long prodId);

    @Transactional(rollbackFor = Exception.class)
    List<ProdInfo> updateAllProductNav();

    // 【BUGFIX】之前 bean 名写成 "taskExecutor"，工程内并不存在该 bean，
    //          会回退到 SimpleAsyncTaskExecutor（每次新建线程，无队列、无复用）。
    //          ThreadPoolConfig 里专门为预热定义了 warmupThreadPool，按名指定。
    @Async("warmupThreadPool")
    void warmUpCacheAfterNavUpdate(List<ProdInfo> productList);
}
