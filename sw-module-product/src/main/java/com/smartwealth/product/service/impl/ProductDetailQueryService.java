package com.smartwealth.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.entity.ProductRateHistory;
import com.smartwealth.product.mapper.ProdInfoMapper;
import com.smartwealth.product.service.IProductRateHistoryService;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.product.vo.ProductHistoryVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 【REFACTOR-Step3-2】产品详情查询：本地 L1 (Caffeine) + 分布式 L2 (Redis) + DB 容错。
 *
 * <p>从 {@link ProdInfoServiceImpl} 中拆出来，原因：
 *   ① 多级缓存策略是详情接口的"黑核"，跟产品 CRUD 完全不相关；
 *   ② NULL_DETAIL_VO 这个"防穿透哨兵"必须跟 Caffeine 的 loader 紧贴，
 *      之前散落在 815 行的文件里很难一眼看清完整契约。
 *
 * <p>对外语义保持不变：
 *   - 布隆过滤器先挡：未存在则 PRODUCT_NOT_EXIST；
 *   - Caffeine -> Redis -> DB 三级回源；
 *   - 库存独立注入，永远拿最新值。
 */
@Slf4j
@Service
public class ProductDetailQueryService {

    /**
     * 布隆过滤器名称。{@code public} 是为了让 {@link ProdInfoServiceImpl#initProduct} 在新品入库后
     * 写入这同一份布隆过滤器，避免常量被双写在两个文件里出现笔误漂移。
     */
    public static final String BLOOM_FILTER_NAME = "prod:id:bloom:filter";
    private static final String LOCK_KEY_PREFIX = "lock:prod:detail:";

    /** 防穿透哨兵：标记"数据库也确实没有"，区分于"缓存还没装"。 */
    private static final ProductDetailVO NULL_DETAIL_VO = new ProductDetailVO();

    /** L1 本地缓存：初始 100，最大 1000，写入后 1 分钟过期。 */
    private final Cache<String, ProductDetailVO> localProductCache = Caffeine.newBuilder()
            .initialCapacity(100)
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    @Autowired
    private IProductRateHistoryService rateHistoryService;
    @Autowired
    private RedisService redisService;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private ProductStockService productStockService;
    @Autowired
    private ProdInfoMapper prodInfoMapper;

    /**
     * 获取产品详情（含 N 天历史净值）。
     */
    public ProductDetailVO getProductDetail(Long prodId, Integer days) {
        // 0. 【第一道防线】布隆过滤器拦截 (解决缓存穿透)
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_NAME);
        if (!bloomFilter.contains(prodId)) {
            log.warn("🚨 布隆过滤器拦截非法请求 ID: {}", prodId);
            throw new BusinessException(ResultCode.PRODUCT_NOT_EXIST);
        }

        int queryDays = (days == null || days <= 0) ? 7 : Math.min(days, 90);

        // 1. 【L1 本地缓存 / L2 分布式缓存】获取静态详情
        ProductDetailVO fullDetail = localProductCache.get(
                String.valueOf(prodId),
                id -> getFullProductDetailFromL2(Long.valueOf(id)));

        // 2. 【防穿透兜底】如果 Redis 里存的是空对象标记
        if (fullDetail == null || fullDetail == NULL_DETAIL_VO) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_EXIST);
        }

        // 3. 【内存截取历史数据】
        List<ProductHistoryVO> fullHistory = fullDetail.getHistoryList();
        List<ProductHistoryVO> subHistory = Collections.emptyList();
        if (!CollectionUtils.isEmpty(fullHistory)) {
            int totalSize = fullHistory.size();
            int limit = Math.min(queryDays, totalSize);
            subHistory = fullHistory.subList(totalSize - limit, totalSize);
        }

        // 4. 【组装结果】
        ProductDetailVO result = new ProductDetailVO();
        result.setBaseInfo(fullDetail.getBaseInfo());
        result.setHistoryList(new ArrayList<>(subHistory));

        // 5. 【动态注入库存】(降级策略，不需要锁)
        injectRealTimeStock(prodId, result);

        return result;
    }

    private void injectRealTimeStock(Long prodId, ProductDetailVO result) {
        BigDecimal stockObj = redisService.getRedisStock(prodId);
        if (stockObj == null) {
            try {
                BigDecimal syncedStock = productStockService.syncProductStockToRedis(prodId);
                result.setAvailableStock(syncedStock);
            } catch (Exception e) {
                log.error("【降级警告】产品 {} 缓存同步失败: {}", prodId, e.getMessage());
                ProductDetailVO dbDetail = this.getRawProductDetailFromDb(prodId);
                if (dbDetail != null) {
                    result.setAvailableStock(dbDetail.getAvailableStock());
                } else {
                    result.setAvailableStock(BigDecimal.ZERO);
                }
            }
        } else {
            result.setAvailableStock(new BigDecimal(stockObj.toString()));
        }
    }

    /**
     * 获取全量静态数据 (Base + History)。使用 Redisson 分布式锁解决缓存击穿。
     */
    private ProductDetailVO getFullProductDetailFromL2(Long prodId) {
        String baseKey = String.format(RedisKeyConstants.PRODUCT_DETAIL, prodId);

        ProductDetailVO.ProductBaseInfoVO baseInfo = redisService.get(baseKey, ProductDetailVO.ProductBaseInfoVO.class);
        if (baseInfo != null) {
            if (baseInfo.getId() == null) return NULL_DETAIL_VO;

            List<ProductHistoryVO> historyList = getProductHistoryFromCacheOrDb(prodId);
            ProductDetailVO vo = new ProductDetailVO();
            vo.setBaseInfo(baseInfo);
            vo.setHistoryList(historyList);
            return vo;
        }

        // ==================== 🔒 分布式锁开始 ====================
        String lockKey = LOCK_KEY_PREFIX + prodId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    // 3. 【DCL 双重检查】再次查 Redis，防止重复查库
                    baseInfo = redisService.get(baseKey, ProductDetailVO.ProductBaseInfoVO.class);
                    if (baseInfo != null) {
                        if (baseInfo.getId() == null) return NULL_DETAIL_VO;

                        List<ProductHistoryVO> historyList = getProductHistoryFromCacheOrDb(prodId);
                        ProductDetailVO vo = new ProductDetailVO();
                        vo.setBaseInfo(baseInfo);
                        vo.setHistoryList(historyList);
                        return vo;
                    }

                    ProdInfo product = prodInfoMapper.selectById(prodId);

                    // 5. 【防穿透】数据库也没有，写空对象 30s
                    if (product == null) {
                        redisService.set(baseKey, new ProductDetailVO.ProductBaseInfoVO(), 30, TimeUnit.SECONDS);
                        return NULL_DETAIL_VO;
                    }

                    baseInfo = new ProductDetailVO.ProductBaseInfoVO();
                    BeanUtils.copyProperties(product, baseInfo);

                    // 7. 【防雪崩】回写 Redis，随机过期时间
                    long ttl = 12 * 60 + ThreadLocalRandom.current().nextLong(60);
                    redisService.set(baseKey, baseInfo, ttl, TimeUnit.MINUTES);

                    List<ProductHistoryVO> historyList = getProductHistoryFromCacheOrDb(prodId);

                    ProductDetailVO fullDetail = new ProductDetailVO();
                    fullDetail.setBaseInfo(baseInfo);
                    fullDetail.setHistoryList(historyList);
                    return fullDetail;

                } finally {
                    lock.unlock();
                }
            } else {
                throw new BusinessException(ResultCode.FAILURE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.FAILURE);
        }
    }

    private List<ProductHistoryVO> getProductHistoryFromCacheOrDb(Long prodId) {
        String historyKey = String.format(RedisKeyConstants.PRODUCT_HISTORY, prodId);

        ProductHistoryVO[] cachedArray = redisService.get(historyKey, ProductHistoryVO[].class);
        if (cachedArray != null) {
            return cachedArray.length == 0 ? Collections.emptyList() : Arrays.asList(cachedArray);
        }

        RLock lock = redissonClient.getLock("lock:prod:history:" + prodId);

        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    cachedArray = redisService.get(historyKey, ProductHistoryVO[].class);
                    if (cachedArray != null) return Arrays.asList(cachedArray);

                    List<ProductRateHistory> historyList = rateHistoryService.list(
                            new LambdaQueryWrapper<ProductRateHistory>()
                                    .eq(ProductRateHistory::getProdId, prodId)
                                    .orderByDesc(ProductRateHistory::getRecordDate)
                                    .last("LIMIT " + 90)
                    );
                    List<ProductHistoryVO> result;
                    long ttl;

                    if (CollectionUtils.isEmpty(historyList)) {
                        result = Collections.emptyList();
                        ttl = 5;
                    } else {
                        Collections.reverse(historyList);
                        result = historyList.stream().map(h -> {
                            ProductHistoryVO vo = new ProductHistoryVO();
                            vo.setDate(h.getRecordDate());
                            vo.setRate(h.getRate());
                            vo.setNav(h.getNav());
                            return vo;
                        }).collect(Collectors.toList());
                        ttl = 60 + ThreadLocalRandom.current().nextLong(10);
                    }
                    redisService.set(historyKey, result, ttl, TimeUnit.MINUTES);
                    return result;
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Collections.emptyList();
    }

    /**
     * 纯数据库获取逻辑：仅在缓存完全不可用时调用（被 InternalProductService.getProductDetail 兜底使用）。
     */
    public ProductDetailVO getRawProductDetailFromDb(Long prodId) {
        log.warn("【容错机制】开始从数据库获取纯净产品详情: {}", prodId);

        ProdInfo product = prodInfoMapper.selectById(prodId);
        if (product == null) return null;

        ProductDetailVO.ProductBaseInfoVO baseInfo = new ProductDetailVO.ProductBaseInfoVO();
        BeanUtils.copyProperties(product, baseInfo);

        List<ProductRateHistory> historyList = rateHistoryService.list(
                new LambdaQueryWrapper<ProductRateHistory>()
                        .eq(ProductRateHistory::getProdId, prodId)
                        .orderByDesc(ProductRateHistory::getRecordDate)
                        .last("LIMIT 90")
        );
        Collections.reverse(historyList);
        List<ProductHistoryVO> historyVOs = historyList.stream().map(h -> {
            ProductHistoryVO vo = new ProductHistoryVO();
            vo.setDate(h.getRecordDate());
            vo.setRate(h.getRate());
            vo.setNav(h.getNav());
            return vo;
        }).collect(Collectors.toList());

        ProductDetailVO result = new ProductDetailVO();
        result.setBaseInfo(baseInfo);
        result.setHistoryList(historyVOs);
        result.setAvailableStock(product.getTotalStock().subtract(product.getLockedStock()));

        return result;
    }

    /**
     * 给上层（{@link ProdInfoServiceImpl#offShelf}）使用：产品下架后清除本机 Caffeine 缓存条目。
     */
    public void invalidateLocal(Long prodId) {
        localProductCache.invalidate(String.valueOf(prodId));
    }
}
