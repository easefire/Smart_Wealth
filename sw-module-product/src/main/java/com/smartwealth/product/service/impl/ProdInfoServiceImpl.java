package com.smartwealth.product.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.product.dto.ProductSaveDTO;
import com.smartwealth.product.enums.MarketSentiment;
import com.smartwealth.product.mapper.ProductRateHistoryMapper;
import com.smartwealth.product.utils.NavAlgorithmUtils;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.product.vo.ProductHistoryVO;
import com.smartwealth.product.vo.ProductVO;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.entity.ProductRateHistory;
import com.smartwealth.product.mapper.ProdInfoMapper;
import com.smartwealth.product.service.IProdInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartwealth.product.service.IProductRateHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 理财产品信息表 服务实现类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Slf4j
@Service
public class ProdInfoServiceImpl extends ServiceImpl<ProdInfoMapper, ProdInfo> implements IProdInfoService {

    // 定义本地缓存：初始容量 100，最大容量 1000，写入后 60 秒过期
    private final Cache<String, ProductDetailVO> localProductCache = Caffeine.newBuilder()
            .initialCapacity(100)
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();
    private static final String BLOOM_FILTER_NAME = "prod:id:bloom:filter";
    private static final String LOCK_KEY_PREFIX = "lock:prod:detail:";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    // 定义空对象常量
    private static final ProductDetailVO NULL_DETAIL_VO = new ProductDetailVO();

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
    @Autowired
    private ProductRateHistoryMapper historyMapper;

    // 产品入库初始化
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initProduct(ProductSaveDTO dto) {
        // 1. 业务校验：代码唯一性
        if (this.count(new LambdaQueryWrapper<ProdInfo>().eq(ProdInfo::getCode, dto.getCode())) > 0) {
            throw new BusinessException("产品代码 [" + dto.getCode() + "] 已存在");
        }

        // 2. 封装产品主表数据
        ProdInfo product = new ProdInfo();
        BeanUtils.copyProperties(dto, product);

        // 初始化关键业务字段
        BigDecimal initNav = new BigDecimal("1.0000000000"); // 初始净值 1.0
        product.setCurrentNav(initNav);
        product.setLatestRate(dto.getBaseRate()); // 初始实际利率 = 基准利率
        product.setLockedStock(BigDecimal.ZERO);  // 锁定库存初始为0
        product.setStatus(1);                     // 入库即自动上架状态

        // 保存主表
        if (!this.save(product)) {
            throw new BusinessException(ResultCode.PRODUCT_SAVE_FAILURE);
        }

        // 3. 关键：同步初始化历史表 (T0 数据)
        // 必须存入这一条，否则第二天的 Job 找不到“昨日净值”作为计算基数
        ProductRateHistory history = new ProductRateHistory();
        history.setProdId(product.getId());
        history.setRate(dto.getBaseRate()); // 初始历史利率
        history.setNav(initNav);            // 初始历史净值
        history.setRecordDate(LocalDate.now());

        rateHistoryService.save(history);

        log.info("产品入库完成：{}, ID: {}, 初始净值: {}", product.getName(), product.getId(), initNav);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                // 1. 模糊删除所有列表页缓存 (prod:on_sale_list:*)
                // 因为你不知道新产品会插在第几页（受排序影响），所以全删最安全
                Set<String> keys = redisService.getkeys(RedisKeyConstants.PRODUCT_ON_SALE_LIST + "*");
                if (CollectionUtils.isNotEmpty(keys)) {
                    redisService.delete(keys);
                }
                RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(ProdInfoServiceImpl.BLOOM_FILTER_NAME);
                if (bloomFilter.isExists()) { // 防止布隆过滤器还没初始化
                    bloomFilter.add(product.getId());
                    log.info("🔥 新产品 ID {} 已同步至布隆过滤器", product.getId());
                }
                log.info("新品发布，已清除列表缓存 keys: {}", keys);
            }
        });
    }

    // 产品下架操作
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void offShelf(Long id) {
        // 1. 查询产品是否存在
        ProdInfo product = this.getById(id);
        if (product == null) {
            throw new BusinessException("产品不存在");
        }

        // 2. 幂等性检查：如果已经是下架状态(2)，直接返回
        if (product.getStatus() == 2) {
            return;
        }

        // 3. 更新状态
        product.setStatus(2);
        if (!this.updateById(product)) {
            throw new BusinessException("产品下架失败");
        }

        log.info("管理操作：产品 ID [{}] 已下架", id);

        // 4. 事务提交后清理缓存
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // ==================== 🗑️ 清除 Redis 缓存 ====================
                List<String> keysToDelete = new ArrayList<>();

                // 1. 详情缓存
                keysToDelete.add(String.format(RedisKeyConstants.PRODUCT_DETAIL, id));

                // 2. ⚠️【补全】库存缓存 (防止前端显示有货)
                keysToDelete.add(String.format(RedisKeyConstants.PRODUCT_STOCK, id));

                // 执行删除
                redisService.delete(keysToDelete);

                // 3. 列表缓存 (模糊匹配删除)
                Set<String> listKeys = redisService.getkeys(RedisKeyConstants.PRODUCT_ON_SALE_LIST + "*");
                if (CollectionUtils.isNotEmpty(listKeys)) {
                    redisService.delete(listKeys);
                }

                // ==================== 📢 清除本地缓存 (Caffeine) ====================
                // 动作 A: 清除当前机器的缓存
                localProductCache.invalidate(String.valueOf(id));

                // 动作 B: 【进阶】广播通知其他机器清除 (依赖 Redisson)
                // 生产环境必须加这个，否则其他节点会有短暂的数据不一致
                RTopic topic = redissonClient.getTopic("product:cache:invalidate");
                topic.publish(id); // 发送消息：所有监听这个 Topic 的节点都要执行 invalidate(id)

                log.info("产品下架缓存清理完成。ID: {}", id);
            }
        });
    }

    // 获取所有产品（管理端使用）
    @Override
    public List<ProdInfo> getAllProducts() {
        return this.list();
    }

    @Override
    public IPage<ProductVO> getUserProductPage(Integer pageNo, Integer pageSize) {
        String cacheKey = String.format(RedisKeyConstants.PRODUCT_ON_SALE_LIST, pageNo, pageSize);
        Object rawData = redisService.get(cacheKey, Object.class);
        Page<ProductVO> cachedPage = null;
        if (rawData != null) {
            try {
                //TypeReference 解决泛型转换问题
                cachedPage = JSON_MAPPER.convertValue(rawData, new TypeReference<Page<ProductVO>>() {});
            } catch (Exception e) {
                log.error("缓存反序列化失败", e);
            }
        }

        // 3. 缓存未命中，查数据库
        if (cachedPage == null) {
            cachedPage = this.loadAndCacheProductPage(pageNo, pageSize, cacheKey);
        }

        // 4. 处理库存回填
        List<ProductVO> records = cachedPage.getRecords();
        if (!CollectionUtils.isEmpty(records)) {
            // 提取 ID
            List<Long> prodIds = records.stream().map(ProductVO::getId).toList();

            // 生成库存 Key List
            List<String> stockKeys = prodIds.stream()
                    .map(id -> String.format(RedisKeyConstants.PRODUCT_STOCK, id))
                    .toList();

            // 批量获取 Redis 实时库存
            List<Object> realTimeStocks = redisService.multiGet(stockKeys);

            for (int i = 0; i < records.size(); i++) {
                ProductVO vo = records.get(i);
                Object stockObj = realTimeStocks.get(i);

                if (stockObj == null) {
                    // 情况 A: Redis 没库存 -> 查库 -> 这里的 dbStock 是正常的 466111.59
                    BigDecimal dbStock = productStockService.syncProductStockToRedis(vo.getId());
                    vo.setAvailableStock(dbStock);
                } else {
                    // 情况 B: Redis 有库存 -> 它是 466111590000 -> 必须缩小 10^6 倍
                    BigDecimal redisStock = new BigDecimal(stockObj.toString());
                    // 小数点向左移动 6 位，还原真实数值
                    vo.setAvailableStock(redisStock.movePointLeft(6));
                }
            }
        }
        return cachedPage;
    }
    /**
     * 封装原本的查库和缓存写入逻辑
     */
    private Page<ProductVO> loadAndCacheProductPage(Integer pageNo, Integer pageSize, String cacheKey) {
        // 使用分布式锁，锁粒度细化到 "cacheKey" (即 specific page)
        // 这样查第1页的人不会阻塞查第2页的人
        String lockKey = RedisKeyConstants.PRODLIST_LOCK + cacheKey;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试加锁：等待 3秒，持有锁 10秒
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    // 1. 二次检查 (Double Check)
                    Object rawData = redisService.get(cacheKey, Object.class);
                    if (rawData != null) {
                        try {
                            return JSON_MAPPER.convertValue(rawData, new TypeReference<Page<ProductVO>>() {});
                        } catch (Exception e) {
                            log.warn("DCL 转换失败，降级查库");
                        }
                    }

                    // 2. 查数据库
                    Page<ProdInfo> queryPage = new Page<>(pageNo, pageSize);
                    IPage<ProdInfo> dbPage = this.page(queryPage, new LambdaQueryWrapper<ProdInfo>()
                            .eq(ProdInfo::getStatus, 1)
                            .orderByAsc(ProdInfo::getRiskLevel));

                    // 3. 转换 VO
                    List<ProductVO> voList = dbPage.getRecords().stream().map(p -> {
                        ProductVO vo = new ProductVO();
                        BeanUtils.copyProperties(p, vo);
                        vo.setAvailableStock(p.getTotalStock().subtract(p.getLockedStock()));
                        return vo;
                    }).collect(Collectors.toList());

                    Page<ProductVO> resultPage = new Page<>(dbPage.getCurrent(), dbPage.getSize(), dbPage.getTotal());
                    resultPage.setRecords(voList);

                    // 4. 写入缓存
                    long ttl = 10 + ThreadLocalRandom.current().nextLong(10);
                    redisService.set(cacheKey, resultPage, ttl, TimeUnit.MINUTES);

                    return resultPage;
                } finally {
                    lock.unlock();
                }
            } else {
                // 获取锁失败（说明有别人正在查这个页），短暂休眠后直接读缓存
                // 或者直接抛出“系统繁忙”让前端重试
                Thread.sleep(100);
                // 递归一次或者直接返回 null
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
    // 获取产品详情

    @Override
    public ProductDetailVO getProductDetail(Long prodId, Integer days) {
        // 0. 【第一道防线】布隆过滤器拦截 (解决缓存穿透)
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_NAME);
        if (!bloomFilter.contains(prodId)) {
            // 布隆过滤器说不存在，那肯定不存在
            log.warn("🚨 布隆过滤器拦截非法请求 ID: {}", prodId);
            throw new BusinessException(ResultCode.PRODUCT_NOT_EXIST);
        }
        // 注意：布隆过滤器说存在，由于误判率，实际上可能不存在，所以后面还得有空值处理

        int queryDays = (days == null || days <= 0) ? 7 : Math.min(days, 90);

        // 1. 【L1 本地缓存 / L2 分布式缓存】获取静态详情
        // Caffeine 的 loader 内部会调用 getFullProductDetailFromL2
        ProductDetailVO fullDetail = localProductCache.get(String.valueOf(prodId), id -> {
            return getFullProductDetailFromL2(Long.valueOf(id));
        });

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

        // 5. 【动态注入库存】(这部分逻辑保持你之前的降级策略，不需要锁)
        this.injectRealTimeStock(prodId, result);

        return result;
    }

    // 抽取库存注入逻辑，保持主方法整洁
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
                    // 这里可以设为 0 或者抛异常，视业务容忍度而定
                    result.setAvailableStock(BigDecimal.ZERO);
                }
            }
        } else {
            result.setAvailableStock(new BigDecimal(stockObj.toString()));
        }
    }
    /**
     * 获取全量静态数据 (Base + History)
     * 使用 Redisson 分布式锁解决缓存击穿
     */
    private ProductDetailVO getFullProductDetailFromL2(Long prodId) {
        String baseKey = String.format(RedisKeyConstants.PRODUCT_DETAIL, prodId);

        // 1. 查询 Redis
        ProductDetailVO.ProductBaseInfoVO baseInfo = redisService.get(baseKey, ProductDetailVO.ProductBaseInfoVO.class);

        // 2. 如果缓存命中，且不是空对象，直接组装返回 (历史数据通常和 BaseInfo 一起过期，简化处理)
        if (baseInfo != null) {
            // 检查是否为空对象标记
            if (baseInfo.getId() == null) return NULL_DETAIL_VO;

            // 缓存命中 Base，顺便取 History (大概率也在缓存)
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
            // 尝试加锁：等待 3秒，上锁 10秒自动释放
            // tryLock 能防止大量请求阻塞，快速失败或等待
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    // 3. 【DCL 双重检查】再次查 Redis，防止重复查库
                    baseInfo = redisService.get(baseKey, ProductDetailVO.ProductBaseInfoVO.class);
                    if (baseInfo != null) {
                        // 检查是否为空对象标记
                        if (baseInfo.getId() == null) return NULL_DETAIL_VO;

                        // 缓存命中 Base，顺便取 History (大概率也在缓存)
                        List<ProductHistoryVO> historyList = getProductHistoryFromCacheOrDb(prodId);
                        ProductDetailVO vo = new ProductDetailVO();
                        vo.setBaseInfo(baseInfo);
                        vo.setHistoryList(historyList);
                        return vo;
                    }

                    // 4. 查数据库
                    ProdInfo product = this.getById(prodId);

                    // 5. 【防穿透】数据库也没有
                    if (product == null) {
                        // 写空值，有效期 30s
                        redisService.set(baseKey, new ProductDetailVO.ProductBaseInfoVO(), 30, TimeUnit.SECONDS);
                        return NULL_DETAIL_VO;
                    }

                    // 6. 组装数据
                    baseInfo = new ProductDetailVO.ProductBaseInfoVO();
                    BeanUtils.copyProperties(product, baseInfo);

                    // 7. 【防雪崩】回写 Redis，随机过期时间
                    long ttl = 12 * 60 + ThreadLocalRandom.current().nextLong(60);
                    redisService.set(baseKey, baseInfo, ttl, TimeUnit.MINUTES);

                    // 8. 同时加载历史数据
                    List<ProductHistoryVO> historyList = getProductHistoryFromCacheOrDb(prodId);

                    ProductDetailVO fullDetail = new ProductDetailVO();
                    fullDetail.setBaseInfo(baseInfo);
                    fullDetail.setHistoryList(historyList);
                    return fullDetail;

                } finally {
                    lock.unlock(); // 必须在 finally 中释放锁
                }
            } else {
                // 获取锁失败 (太拥挤了)，可以报错，也可以自旋重试，或者降级返回空
                throw new BusinessException(ResultCode.FAILURE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.FAILURE);
        }
    }
    /**
     * 获取历史数据
     */
    private List<ProductHistoryVO> getProductHistoryFromCacheOrDb(Long prodId) {
        String historyKey = String.format(RedisKeyConstants.PRODUCT_HISTORY, prodId);

        // 1. 查缓存
        ProductHistoryVO[] cachedArray = redisService.get(historyKey, ProductHistoryVO[].class);
        if (cachedArray != null) return cachedArray.length == 0 ? Collections.emptyList() : Arrays.asList(cachedArray);

        // 2. 加锁 (这里可以复用上面的锁，或者用一把新锁)
        // 为了简单，假设上层方法已经锁住了 prodId，这里其实可以直接查。
        // 但如果此方法也会被单独调用，则必须加锁。这里演示加锁：
        RLock lock = redissonClient.getLock("lock:prod:history:" + prodId);

        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    // DCL
                    cachedArray = redisService.get(historyKey, ProductHistoryVO[].class);
                    if (cachedArray != null) return Arrays.asList(cachedArray);

                    // 查库
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
        return Collections.emptyList(); // 降级返回空
    }


    /**
     * 纯数据库获取逻辑：仅在缓存完全不可用时调用
     */
    @Override
    public ProductDetailVO getRawProductDetailFromDb(Long prodId) {
        log.warn("【容错机制】开始从数据库获取纯净产品详情: {}", prodId);

        // 1. 获取基础信息 (MySQL)
        ProdInfo product = this.getById(prodId);
        if (product == null) return null;

        ProductDetailVO.ProductBaseInfoVO baseInfo = new ProductDetailVO.ProductBaseInfoVO();
        BeanUtils.copyProperties(product, baseInfo);

        // 2. 获取 90 天历史数据 (MySQL)
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

        // 3. 组装结果 (直接用数据库里的库存，不经过 Redis)
        ProductDetailVO result = new ProductDetailVO();
        result.setBaseInfo(baseInfo);
        result.setHistoryList(historyVOs);
        result.setAvailableStock(product.getTotalStock().subtract(product.getLockedStock()));

        return result;
    }

    //更新产品净值

    /**
     * 核心调度入口：更新所有产品净值
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ProdInfo> updateAllProductNav() {
        log.info("========== 开始执行每日净值更新计算 ==========");

        // 1. 获取今日市场情绪
        String sentimentCode = redisService.get(RedisKeyConstants.KEY_MARKET_SENTIMENT_LATEST, String.class);
        MarketSentiment sentiment = StrUtil.isBlank(sentimentCode)
                ? MarketSentiment.SIDEWAYS
                : MarketSentiment.valueOf(sentimentCode);

        log.info("今日市场剧本：{}", sentiment.getDescription());

        // 2. 查出所有“运行中”的产品
        List<ProdInfo> productList = prodInfoMapper.selectList(
                new LambdaQueryWrapper<ProdInfo>().eq(ProdInfo::getStatus, 1)
        );

        if (CollectionUtils.isEmpty(productList)) {
            return Collections.emptyList();
        }

        LocalDate recordDate = LocalDate.now().minusDays(1);

        // 3. 遍历更新
        for (ProdInfo prod : productList) {
            // --- A. 计算涨跌幅 ---
            BigDecimal changeRate = NavAlgorithmUtils.calculateDailyChange(
                    sentiment,
                    prod.getRiskLevel()
            );

            // --- B. 计算新净值 ---
            BigDecimal oldNav = prod.getCurrentNav();
            BigDecimal newNav = oldNav.multiply(BigDecimal.ONE.add(changeRate))
                    .setScale(10, RoundingMode.HALF_UP);

            // --- C. 更新内存对象 & 数据库 ---
            // 【关键】必须把新值塞回对象，否则返回出去的 list 还是旧数据
            prod.setCurrentNav(newNav);
            prod.setLatestRate(changeRate);
            prod.setUpdateTime(LocalDateTime.now());

            prodInfoMapper.updateById(prod);

            // --- D. 插入历史表 ---
            ProductRateHistory history = new ProductRateHistory();
            history.setProdId(prod.getId());
            history.setNav(newNav);
            history.setRate(changeRate);
            history.setRecordDate(recordDate);
            historyMapper.insert(history);
        }

        log.info("净值计算落库完成，共处理 {} 个产品", productList.size());

        // 4. 直接把全是最新数据的 List 返回去
        return productList;
    }

    /**
     * 【核心预热函数】每日净值更新后调用
     * @param productList 刚刚更新完净值的 ProdInfo 列表（里面的 currentNav 已经是新的了）
     */
    @Async("taskExecutor")
    @Override
    public void warmUpCacheAfterNavUpdate(List<ProdInfo> productList) {
        if (CollectionUtils.isEmpty(productList)) return;

        log.info("========== 开始执行每日缓存预热，涉及产品数: {} ==========", productList.size());
        long start = System.currentTimeMillis();

        // 建议使用 Pipeline 或分批处理，这里为了清晰展示写成循环
        for (ProdInfo prod : productList) {
            try {
                // 1. 刷新 BaseInfo (包含最新的净值和收益率)
                refreshBaseInfoCache(prod);

                // 2. 刷新 History (包含今天刚产生的新数据)
                refreshHistoryCache(prod.getId());

            } catch (Exception e) {
                // 单个产品预热失败不应影响整体
                log.error("产品 [{}] 缓存预热失败", prod.getId(), e);
            }
        }

        log.info("========== 缓存预热完成，耗时: {}ms ==========", System.currentTimeMillis() - start);
    }

    /**
     * 1. 刷新 BaseInfo
     * 策略：强制覆盖。因为 currentNav 变了，不覆盖的话用户看到的还是昨天的净值。
     */
    private void refreshBaseInfoCache(ProdInfo prod) {
        String key = String.format(RedisKeyConstants.PRODUCT_DETAIL, prod.getId());

        // 构建 VO
        ProductDetailVO.ProductBaseInfoVO baseInfoVO = new ProductDetailVO.ProductBaseInfoVO();

        // 复制静态属性 (id, name, code, cycle, riskLevel, status,baseRate, etc.)
        BeanUtils.copyProperties(prod, baseInfoVO);

        // 显式赋值动态属性 (确保是用最新的)
        baseInfoVO.setCurrentNav(prod.getCurrentNav());
        baseInfoVO.setLatestRate(prod.getLatestRate());

        // 写入 Redis，TTL 25小时 (覆盖一天周期，防止击穿)
        redisService.set(key, baseInfoVO, 25, TimeUnit.HOURS);
    }

    /**
     * 2. 刷新 History
     * 策略：查库取最新 90 条 -> 排序 -> 写入。
     */
    private void refreshHistoryCache(Long prodId) {
        String key = String.format(RedisKeyConstants.PRODUCT_HISTORY, prodId);

        // A. 查库：按时间倒序取最新的 90 条
        // 对应 SQL: select * from t_prod_rate_history where prod_id = ? order by record_date desc limit 90
        List<ProductRateHistory> dbHistory = rateHistoryService.list(
                new LambdaQueryWrapper<ProductRateHistory>()
                        .eq(ProductRateHistory::getProdId, prodId)
                        .orderByDesc(ProductRateHistory::getRecordDate)
                        .last("LIMIT 90")
        );

        if (CollectionUtils.isEmpty(dbHistory)) return;

        // B. 内存处理：反转顺序 (变成 日期从小到大，方便前端画图) + 转 VO
        // 你的旧代码里用了 Collections.reverse，保持一致
        Collections.reverse(dbHistory);

        List<ProductHistoryVO> voList = dbHistory.stream().map(h -> {
            ProductHistoryVO vo = new ProductHistoryVO();
            vo.setDate(h.getRecordDate());
            vo.setNav(h.getNav());
            vo.setRate(h.getRate());
            return vo;
        }).collect(Collectors.toList());

        // C. 写入 Redis
        // 注意：读取时是用 ProductHistoryVO[].class，所以存 List 没问题，Jackson 会自动处理
        redisService.set(key, voList, 25, TimeUnit.HOURS);
    }

}


