package com.smartwealth.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.entity.ProductRateHistory;
import com.smartwealth.product.service.IProductRateHistoryService;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.product.vo.ProductHistoryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 【REFACTOR-Step3-4】每日净值任务后的<strong>异步缓存预热</strong>。
 *
 * <p>从 {@link ProdInfoServiceImpl} 中拆出来，原因：
 *   ① @Async 注解必须由代理生效，而 self-invocation 会让它失效；
 *      之前预热方法塞在 ServiceImpl 里，被 JobHandler 直接调倒是 OK，
 *      但如果未来有人在 ServiceImpl 内部 this.warmUp(...)，会静默退化为同步。
 *      拆出独立 bean 后，所有调用都必须经过 Spring 代理，规避陷阱。
 *   ② 预热的核心动作（查 DB + 组 VO + Pipeline 写 Redis）跟产品 CRUD 完全无关，
 *      合并在 ServiceImpl 里只会让那个类继续膨胀。
 *
 * <p>对外 API：{@link #warmUpCacheAfterNavUpdate(List)}（由 XxlJob 调用）。
 */
@Slf4j
@Service
public class ProductCacheWarmupService {

    @Autowired
    private IProductRateHistoryService rateHistoryService;
    @Autowired
    private RedisService redisService;
    @Autowired
    private ThreadPoolExecutor warmupThreadPool;

    /**
     * 【核心预热函数】每日净值更新后调用。
     *
     * <p>【REFACTOR-Step3-4】注意此方法<strong>不带 @Async</strong>：
     * 异步语义由 {@link com.smartwealth.product.service.IProdInfoService#warmUpCacheAfterNavUpdate} 的
     * {@code @Async("warmupThreadPool")} 在门面层提供，避免门面与本服务双重派发到同一线程池
     * 浪费 task 槽位。本服务作为协作者，内部仍然用 {@code warmupThreadPool} 做<strong>子任务并行</strong>。
     *
     * @param productList 刚刚更新完净值的 ProdInfo 列表（里面的 currentNav 已经是新的了）
     */
    public void warmUpCacheAfterNavUpdate(List<ProdInfo> productList) {
        if (CollectionUtils.isEmpty(productList)) return;

        long start = System.currentTimeMillis();

        try {
            // 1. 【并行采集】：利用线程池并发执行查库和数据封装
            List<CompletableFuture<Map<String, Object>>> futures = productList.stream()
                    .map(prod -> CompletableFuture.supplyAsync(
                            () -> prepareCacheDataForProduct(prod),
                            warmupThreadPool
                    ))
                    .toList();

            // 2. 【结果汇总】：等待所有任务完成，并将散落的 Map 合并成一个大 Map
            Map<String, Object> allDataMap = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(map -> !map.isEmpty())
                    .flatMap(map -> map.entrySet().stream())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (existing, replacement) -> existing
                    ));

            // 3. 【批量冲锋】：一次 Pipeline 网络往返，写入所有数据
            if (!allDataMap.isEmpty()) {
                long ttl = TimeUnit.HOURS.toSeconds(25);
                redisService.setPipelinedEx(allDataMap, ttl, TimeUnit.SECONDS);
            }

            log.info("========== [核弹级预热] 完成，总耗时: {}ms，写入总 Key 数: {} ==========",
                    System.currentTimeMillis() - start, allDataMap.size());

        } catch (Exception e) {
            log.error("缓存预热主流程发生异常", e);
        }
    }

    /**
     * 单个产品的数据准备逻辑（子线程执行）
     */
    private Map<String, Object> prepareCacheDataForProduct(ProdInfo prod) {
        Map<String, Object> productData = new HashMap<>();
        try {
            List<ProductHistoryVO> historyVOList = fetchHistoryFromDb(prod.getId());
            ProductDetailVO.ProductBaseInfoVO baseInfoVO = buildBaseInfoVO(prod);

            productData.put(String.format(RedisKeyConstants.PRODUCT_DETAIL, prod.getId()), baseInfoVO);
            if (!CollectionUtils.isEmpty(historyVOList)) {
                productData.put(String.format(RedisKeyConstants.PRODUCT_HISTORY, prod.getId()), historyVOList);
            }
        } catch (Exception e) {
            log.error("产品 [{}] 数据预处理失败: {}", prod.getId(), e.getMessage());
        }
        return productData;
    }

    private List<ProductHistoryVO> fetchHistoryFromDb(Long prodId) {
        List<ProductRateHistory> dbHistory = rateHistoryService.list(
                new LambdaQueryWrapper<ProductRateHistory>()
                        .eq(ProductRateHistory::getProdId, prodId)
                        .orderByDesc(ProductRateHistory::getRecordDate)
                        .last("LIMIT 90")
        );

        if (CollectionUtils.isEmpty(dbHistory)) return Collections.emptyList();

        Collections.reverse(dbHistory);
        return dbHistory.stream().map(h -> {
            ProductHistoryVO vo = new ProductHistoryVO();
            vo.setDate(h.getRecordDate());
            vo.setNav(h.getNav());
            vo.setRate(h.getRate());
            return vo;
        }).collect(Collectors.toList());
    }

    private ProductDetailVO.ProductBaseInfoVO buildBaseInfoVO(ProdInfo prod) {
        ProductDetailVO.ProductBaseInfoVO baseInfoVO = new ProductDetailVO.ProductBaseInfoVO();
        BeanUtils.copyProperties(prod, baseInfoVO);
        baseInfoVO.setCurrentNav(prod.getCurrentNav());
        baseInfoVO.setLatestRate(prod.getLatestRate());
        return baseInfoVO;
    }
}
