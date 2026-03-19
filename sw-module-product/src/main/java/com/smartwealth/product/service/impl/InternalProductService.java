package com.smartwealth.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.entity.ProductRateHistory;
import com.smartwealth.product.mapper.ProdInfoMapper;
import com.smartwealth.product.mapper.ProductRateHistoryMapper;
import com.smartwealth.product.service.IProdInfoService;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.product.vo.ProductVO;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.internal.util.stereotypes.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 内部调用的产品服务
 * </p>
 *
 * @author Fire
 * @since 2026-01-12
 */
@Slf4j
@Service
public class InternalProductService {

    @Autowired
    private TransactionHelper selfProxy;
    @Autowired
    private IProdInfoService productService;
    @Autowired
    private ProdInfoMapper prodInfoMapper;
    @Autowired
    private RedisService redisService;
    @Autowired
    private ProductStockService productStockService;
    @Autowired
    private ProductRateHistoryMapper productRateHistoryMapper;

    // Redis锁定库存
    public void lockStock(Long id, BigDecimal quantity) {
        String stockKey = String.format(RedisKeyConstants.PRODUCT_STOCK, id);
        Long result = redisService.executeStock(stockKey, quantity);

        // 1.1 处理缓存失效
        if (result == -1) {
            log.warn("产品 {} 缓存失效，触发同步", id);
            try {
                productStockService.syncProductStockToRedis(id);
                // 同步成功后，重试一次扣减
                result = redisService.executeStock(stockKey, quantity);
            } catch (Exception e) {
                log.error("同步缓存失败: {}", e.getMessage());
                throw new BusinessException("系统繁忙，请稍后重试");
            }
        }
        // 1.2 处理库存不足
        if (result == -2 || result < 0) {
            throw new BusinessException("产品库存不足");
        }
    }
    // Redis解锁库存
    public void unlockStock(Long id, BigDecimal quantity) {
        // 1. 独立短事务：执行数据库回补
        try {
            selfProxy.doStockInDb(id, quantity,"UNLOCK");
        } catch (Exception e) {
            log.error("产品 {} 数据库解锁库存失败", id, e);
            // DB 解锁失败说明底层出现严重异常，直接抛出，此时不应该去加 Redis
            throw new BusinessException("系统异常，解锁库存失败");
        }
        // 2. 无事务环境：执行 Redis 份额回补
        String stockKey = String.format(RedisKeyConstants.PRODUCT_STOCK, id);
        Long result = null;
        try {
            result = redisService.incrementStock(stockKey, quantity);
        } catch (Exception e) {
            log.error("产品 {} Redis 回补请求发生网络异常", id, e);
        }

        // 3. 缓存兜底与同步补偿
        // 如果 Redis 返回异常，或者根本没走到 Redis 就抛了异常（result 为 null）
        if (result == null || result < 0) {
            log.warn("Redis 库存回补异常，触发同步。Key: {}", stockKey);
            try {
                // 因为已经在事务外，读取 DB 绝对不会读到脏数据
                productStockService.syncProductStockToRedis(id);
            } catch (Exception e) {
                log.error("unlockStock 同步缓存失败: {}", e.getMessage());
            }
        }
    }
    // 根据产品ID获取产品信息
    public ProdInfo getById(Long productId) {
        return productService.getById(productId);
    }
    // 根据一组产品ID获取产品名称映射
    public Map<Long, String> getProdNamesByIds(Set<Long> prodIds) {
        if (CollectionUtils.isEmpty(prodIds)) {
            return Collections.emptyMap();
        }
        List<ProdInfo> list = productService.list(new LambdaQueryWrapper<ProdInfo>()
                .select(ProdInfo::getId, ProdInfo::getName)
                .in(ProdInfo::getId, prodIds));
        return list.stream().collect(Collectors.toMap(
                ProdInfo::getId,
                ProdInfo::getName,
                (existing, replacement) -> existing
        ));
    }
    // 根据一组产品ID获取产品当前净值映射
    public Map<Long, BigDecimal> getProdNavMap(Set<Long> prodIds) {
        if (CollectionUtils.isEmpty(prodIds)) {
            return Collections.emptyMap();
        }
        List<ProdInfo> list = productService.list(new LambdaQueryWrapper<ProdInfo>()
                .select(ProdInfo::getId, ProdInfo::getCurrentNav)
                .in(ProdInfo::getId, prodIds));
        return list.stream().collect(Collectors.toMap(
                ProdInfo::getId,
                ProdInfo::getCurrentNav,
                (existing, replacement) -> existing
        ));
    }
    // 根据产品ID获取产品详细信息
    public ProductDetailVO getProductDetail(@NotNull(message = "产品ID不能为空") Long productId, int i) {
        try {
            return productService.getProductDetail(productId, i);
        } catch (Exception e) {
            log.error("【缓存降级】产品 {} 缓存读取/解析失败，原因: {}", productId, e.getMessage());

            // 这里的 getRawProductDetailFromDb 必须确保在 IProdInfoService 接口里定义了
            // 如果报错找不到方法，请确认接口定义
            ProductDetailVO dbDetail = productService.getRawProductDetailFromDb(productId);

            if (dbDetail == null) {
                log.error("【致命】数据库中也不存在产品 {}，请检查数据一致性", productId);
                throw new BusinessException("产品信息不存在");
            }
            return dbDetail;
        }
    }
    // 获取产品历史净值
    public List<ProductRateHistory> selectList(LambdaQueryWrapper<ProductRateHistory> eq) {
        return productRateHistoryMapper.selectList(eq);
    }
}