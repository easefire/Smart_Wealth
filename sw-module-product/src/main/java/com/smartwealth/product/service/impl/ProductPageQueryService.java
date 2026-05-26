package com.smartwealth.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.product.dto.ProductPageCacheDTO;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.mapper.ProdInfoMapper;
import com.smartwealth.product.vo.ProductVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 【REFACTOR-Step3-1】列表分页查询 + 缓存装载。
 *
 * <p>从 {@link ProdInfoServiceImpl} 中拆出来，原因：
 *   ① 列表分页的"易变性"远高于产品 CRUD，缓存策略每个版本都在调；
 *   ② {@code ProductPageCacheDTO} 的序列化契约（P2-#28）应当跟读写它的代码放在同一处，方便整体替换；
 *   ③ 单测可以独立 mock RedisService / RedissonClient，不用拖上 ServiceImpl 整堆基类。
 *
 * <p>对外行为完全保持与重构前一致：缓存命中走 DTO、未命中走分布式锁 + Double-Check、拿不到锁降级直查 DB。
 */
@Slf4j
@Service
public class ProductPageQueryService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Autowired
    private RedisService redisService;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private ProductStockService productStockService;
    @Autowired
    private ProdInfoMapper prodInfoMapper;

    /**
     * 列表分页 + 实时库存回填。
     */
    public Page<ProductVO> getUserProductPage(Integer pageNo, Integer pageSize) {
        String cacheKey = String.format(RedisKeyConstants.PRODUCT_ON_SALE_LIST, pageNo, pageSize);

        // 【BUGFIX-P2-#28】之前缓存的是 mybatis-plus 的 Page<ProductVO> 整对象，
        //                  含 orders / countId / 各种内部状态，跨版本/字段变更极易反序列化失败。
        //                  现在统一用 ProductPageCacheDTO（仅 records + 分页元数据），
        //                  反序列化简单且体积小。
        Page<ProductVO> cachedPage = readCachedPage(cacheKey);

        if (cachedPage == null) {
            cachedPage = loadAndCacheProductPage(pageNo, pageSize, cacheKey);
        }

        // 【BUGFIX-#7】之前 multiGet 走 Jackson 序列化器，setSTOCK 走 String 序列化器，
        //              两条路径混用极易触发 ClassCastException；
        //              统一改成 redisService.multiGetStocks(ids)，单一权威路径返回已 scale 的 BigDecimal。
        List<ProductVO> records = cachedPage.getRecords();
        if (!CollectionUtils.isEmpty(records)) {
            List<Long> prodIds = records.stream().map(ProductVO::getId).toList();
            List<BigDecimal> realTimeStocks = redisService.multiGetStocks(prodIds);

            for (int i = 0; i < records.size(); i++) {
                ProductVO vo = records.get(i);
                BigDecimal stock = realTimeStocks.get(i);
                if (stock == null) {
                    vo.setAvailableStock(productStockService.syncProductStockToRedis(vo.getId()));
                } else {
                    vo.setAvailableStock(stock);
                }
            }
        }
        return cachedPage;
    }

    private Page<ProductVO> loadAndCacheProductPage(Integer pageNo, Integer pageSize, String cacheKey) {
        String lockKey = RedisKeyConstants.PRODLIST_LOCK + cacheKey;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    Page<ProductVO> cached = readCachedPage(cacheKey);
                    if (cached != null) {
                        return cached;
                    }

                    Page<ProductVO> resultPage = queryFromDbWithoutCache(pageNo, pageSize);
                    writeCachedPage(cacheKey, resultPage);
                    return resultPage;
                } finally {
                    lock.unlock();
                }
            } else {
                // 【BUGFIX-#9】锁失败时的"排队后再读 + DB 兜底"策略，避免老版本返回 null 让上游 NPE。
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                Page<ProductVO> cached = readCachedPage(cacheKey);
                if (cached != null) {
                    return cached;
                }

                log.warn("loadAndCacheProductPage 获取分布式锁失败且缓存仍空，降级直查 DB。cacheKey={}", cacheKey);
                return queryFromDbWithoutCache(pageNo, pageSize);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return queryFromDbWithoutCache(pageNo, pageSize);
        }
    }

    /**
     * 【NEW for P2-#28】统一缓存读取：从 Redis 反序列化轻量 DTO 并装回 Page。
     * 反序列化失败一律返回 null，让上游降级到查 DB，避免缓存格式跨版本爆炸。
     */
    private Page<ProductVO> readCachedPage(String cacheKey) {
        Object rawData = redisService.get(cacheKey, Object.class);
        if (rawData == null) {
            return null;
        }
        try {
            ProductPageCacheDTO dto = JSON_MAPPER.convertValue(rawData, ProductPageCacheDTO.class);
            if (dto == null || dto.getRecords() == null) {
                return null;
            }
            Page<ProductVO> page = new Page<>(dto.getCurrent(), dto.getSize(), dto.getTotal());
            page.setRecords(dto.getRecords());
            return page;
        } catch (Exception e) {
            log.warn("产品列表缓存反序列化失败，自动降级查库。cacheKey={}", cacheKey, e);
            return null;
        }
    }

    private void writeCachedPage(String cacheKey, Page<ProductVO> resultPage) {
        ProductPageCacheDTO dto = new ProductPageCacheDTO(
                resultPage.getRecords(),
                resultPage.getCurrent(),
                resultPage.getSize(),
                resultPage.getTotal()
        );
        long ttl = 10 + ThreadLocalRandom.current().nextLong(10);
        redisService.set(cacheKey, dto, ttl, TimeUnit.MINUTES);
    }

    /**
     * 【NEW for #9】兜底直查 DB 的降级实现：不写缓存、不解锁，仅保证调用方能拿到有效页对象。
     * 写缓存的工作交还给抢到锁的那条线程，避免 stampede 重复写。
     *
     * <p>【REFACTOR-Step3-1】之前调用 ServiceImpl.page(...)；拆出来后直接走 mapper.selectPage()，
     * MyBatis-Plus 分页插件按 mapper 维度生效，行为一致，但少了一次 ServiceImpl 自身的反射开销。
     */
    private Page<ProductVO> queryFromDbWithoutCache(Integer pageNo, Integer pageSize) {
        Page<ProdInfo> queryPage = new Page<>(pageNo, pageSize);
        Page<ProdInfo> dbPage = prodInfoMapper.selectPage(queryPage, new LambdaQueryWrapper<ProdInfo>()
                .eq(ProdInfo::getStatus, 1)
                .orderByAsc(ProdInfo::getRiskLevel));

        List<ProductVO> voList = dbPage.getRecords().stream().map(p -> {
            ProductVO vo = new ProductVO();
            BeanUtils.copyProperties(p, vo);
            vo.setAvailableStock(p.getTotalStock().subtract(p.getLockedStock()));
            return vo;
        }).collect(Collectors.toList());

        Page<ProductVO> resultPage = new Page<>(dbPage.getCurrent(), dbPage.getSize(), dbPage.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }
}
