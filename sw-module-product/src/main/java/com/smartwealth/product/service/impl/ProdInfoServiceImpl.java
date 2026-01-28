package com.smartwealth.product.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

    @Autowired
    private IProductRateHistoryService rateHistoryService;
    @Autowired
    private RedisService redisService;
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

        // 2. 检查当前状态
        // 如果已经是下架状态(2)，则无需重复操作
        if (product.getStatus() == 2) {
            return;
        }

        // 3. 执行下架操作
        // 仅仅修改状态位为 2
        product.setStatus(2);

        if (!this.updateById(product)) {
            throw new BusinessException("产品下架失败");
        }

        log.info("管理操作：产品 ID [{}] 已下架", id);
    }

    // 获取所有产品（管理端使用）
    @Override
    public List<ProdInfo> getAllProducts() {
        return this.list();
    }

    // 获取用户可见的产品列表
    @Override
    public IPage<ProductVO> getUserProductPage(Integer pageNo, Integer pageSize) {
        String cacheKey = String.format(RedisKeyConstants.PRODUCT_ON_SALE_LIST, pageNo, pageSize);
        Page<ProductVO> cachedPage = redisService.get(cacheKey, Page.class);

        if (cachedPage == null) {
            cachedPage = this.loadAndCacheProductPage(pageNo, pageSize, cacheKey);
        }

        List<ProductVO> records = cachedPage.getRecords();
        if (!CollectionUtils.isEmpty(records)) {
            List<Long> prodIds = records.stream().map(ProductVO::getId).toList();

            // 1. 生成所有库存 Key
            List<String> stockKeys = prodIds.stream()
                    .map(id -> String.format(RedisKeyConstants.PRODUCT_STOCK, id))
                    .toList();

            // 2. 批量获取 Redis 实时库存
            List<Object> realTimeStocks = redisService.multiGet(stockKeys);

            // 3. 遍历并检查 null
            for (int i = 0; i < records.size(); i++) {
                ProductVO vo = records.get(i);
                Object stockObj = realTimeStocks.get(i);

                if (stockObj == null) {
                    // 【核心】触发被动加载：从数据库同步并回填
                    BigDecimal dbStock = productStockService.syncProductStockToRedis(vo.getId());
                    vo.setAvailableStock(dbStock);
                } else {
                    vo.setAvailableStock(new BigDecimal(stockObj.toString()));
                }
            }
        }
        return cachedPage;
    }

    /**
     * 封装原本的查库和缓存写入逻辑
     */
    private Page<ProductVO> loadAndCacheProductPage(Integer pageNo, Integer pageSize, String cacheKey) {
        synchronized (this) {
            // 二次检查
            Page<ProductVO> cachedPage = redisService.get(cacheKey, Page.class);
            if (cachedPage != null) return cachedPage;

            // 1. 查数据库
            Page<ProdInfo> queryPage = new Page<>(pageNo, pageSize);
            IPage<ProdInfo> dbPage = this.page(queryPage, new LambdaQueryWrapper<ProdInfo>()
                    .eq(ProdInfo::getStatus, 1)
                    .orderByAsc(ProdInfo::getRiskLevel));

            // 2. 转换 VO
            List<ProductVO> voList = dbPage.getRecords().stream().map(p -> {
                ProductVO vo = new ProductVO();
                BeanUtils.copyProperties(p, vo);
                // 这里存的是数据库快照，之后会被动态注入覆盖
                vo.setAvailableStock(p.getTotalStock().subtract(p.getLockedStock()));
                return vo;
            }).collect(Collectors.toList());

            Page<ProductVO> resultPage = new Page<>(dbPage.getCurrent(), dbPage.getSize(), dbPage.getTotal());
            resultPage.setRecords(voList);

            // 3. 写入缓存
            long ttl = 10 + ThreadLocalRandom.current().nextLong(10);
            redisService.set(cacheKey, resultPage, ttl, TimeUnit.MINUTES);

            return resultPage;
        }
    }
    // 获取产品详情

    /**
     * 本地一级缓存：存储“全量”产品详情（Base + 90天历史），1分钟失效
     */
    @Override
    public ProductDetailVO getProductDetail(Long prodId, Integer days) {
        int queryDays = (days == null || days <= 0) ? 7 : Math.min(days, 90);

        // 1. 【L1/L2 缓存】仅获取“静态”详情镜像
        // 这里的 fullDetail 绝对不能包含 availableStock，否则会导致数据过时
        ProductDetailVO fullDetail = localProductCache.get(String.valueOf(prodId), id -> {
            return getFullProductDetailFromL2(Long.valueOf(id));
        });

        if (fullDetail == null) throw new BusinessException(ResultCode.PRODUCT_NOT_EXIST);

        // 3. 【内存截取】基于 90 天全量数据进行切片

        List<ProductHistoryVO> fullHistory = fullDetail.getHistoryList();
        int totalSize = fullHistory.size();
        int limit = Math.min(queryDays, totalSize);
        // 截取末尾最新的 N 天数据
        List<ProductHistoryVO> subHistory = fullHistory.subList(totalSize - limit, totalSize);

        // 3. 【结果封装】
        ProductDetailVO result = new ProductDetailVO();
        result.setBaseInfo(fullDetail.getBaseInfo());
        result.setHistoryList(new ArrayList<>(subHistory));

        // 4.动态注入“活”的份额
        BigDecimal stockObj = redisService.getRedisStock(prodId);

        if (stockObj == null) {
            try {
                // 1. 尝试走独立事务同步缓存
                BigDecimal syncedStock = productStockService.syncProductStockToRedis(prodId);
                result.setAvailableStock(syncedStock);
            } catch (Exception e) {
                // 2. 【核心】捕获所有异常，绝不让异常往上抛！
                log.error("【降级警告】产品 {} 缓存同步失败，启用数据库直读兜底。原因: {}", prodId, e.getMessage());

                // 3. 数据库方法进行兜底
                ProductDetailVO dbDetail = this.getRawProductDetailFromDb(prodId);
                if (dbDetail != null) {
                    result.setAvailableStock(dbDetail.getAvailableStock());
                } else {
                    throw new BusinessException("产品数据异常");
                }
            }
        } else {
            result.setAvailableStock(new BigDecimal(stockObj.toString()));
        }

        return result;
    }

    /**
     * 二级缓存获取逻辑：只负责静态快照
     */
    private ProductDetailVO getFullProductDetailFromL2(Long prodId) {
        // 1. 获取基础信息 (BaseInfo) - 保持现状，这是对的
        String baseKey = String.format(RedisKeyConstants.PRODUCT_DETAIL, prodId);
        ProductDetailVO.ProductBaseInfoVO baseInfo = redisService.get(baseKey, ProductDetailVO.ProductBaseInfoVO.class);

        if (baseInfo == null) {
            ProdInfo product = this.getById(prodId);
            if (product == null) return null;

            baseInfo = new ProductDetailVO.ProductBaseInfoVO();
            BeanUtils.copyProperties(product, baseInfo);

            // 基础信息缓存 12 小时，绝对不能带库存信息
            redisService.set(baseKey, baseInfo, 12, TimeUnit.HOURS);
        }

        // 2. 获取 90 天历史数据 - 保持现状，DCL 逻辑写得很好
        List<ProductHistoryVO> historyList = getProductHistoryWithCache(prodId);

        // 3. 组装全量 VO
        ProductDetailVO fullDetail = new ProductDetailVO();
        fullDetail.setBaseInfo(baseInfo);
        fullDetail.setHistoryList(historyList);

        // 它是静态快照，不应该感知“份额”的存在 [cite: 2026-01-15, 2026-01-22]

        return fullDetail;
    }

    /**
     * 获取 90 天全量历史数据，带 DCL 缓存保护
     */
    private List<ProductHistoryVO> getProductHistoryWithCache(Long prodId) {
        String historyKey = String.format(RedisKeyConstants.PRODUCT_HISTORY, prodId);

        // 1. 第一次检查 Redis (Array 规避泛型擦除)
        ProductHistoryVO[] cachedArray = redisService.get(historyKey, ProductHistoryVO[].class);
        if (cachedArray != null) return Arrays.asList(cachedArray);

        synchronized (this) {
            // 2. 第二次检查 (DCL 核心)
            cachedArray = redisService.get(historyKey, ProductHistoryVO[].class);
            if (cachedArray != null) return Arrays.asList(cachedArray);

            log.warn("L2 缓存击穿，开始查询数据库历史表: {}", prodId);

            // 3. 查库，拿死 90 天的数据
            List<ProductRateHistory> historyList = rateHistoryService.list(
                    new LambdaQueryWrapper<ProductRateHistory>()
                            .eq(ProductRateHistory::getProdId, prodId)
                            .orderByDesc(ProductRateHistory::getRecordDate)
                            .last("LIMIT " + 90)
            );

            // 4. 逻辑处理：反转排序并转换为 VO
            Collections.reverse(historyList);
            List<ProductHistoryVO> result = historyList.stream().map(h -> {
                ProductHistoryVO vo = new ProductHistoryVO();
                vo.setDate(h.getRecordDate());
                vo.setRate(h.getRate());
                vo.setNav(h.getNav());
                return vo;
            }).collect(Collectors.toList());

            // 5. 回写 Redis
            redisService.set(historyKey, result, 1, TimeUnit.HOURS);
            return result;
        }
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


