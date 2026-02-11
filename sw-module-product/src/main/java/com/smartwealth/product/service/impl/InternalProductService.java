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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private IProdInfoService productService;
    @Autowired
    private ProdInfoMapper prodInfoMapper;
    @Autowired
    private RedisService redisService;
    @Autowired
    private ProductStockService productStockService;
    @Autowired
    private ProductRateHistoryMapper productRateHistoryMapper;

    // 根据产品ID获取产品信息
    public ProdInfo getById(Long productId) {
        return productService.getById(productId);
    }

    // 锁定库存
    @Transactional(rollbackFor = Exception.class)
    public void lockStock(Long id, BigDecimal quantity) {
        String stockKey = String.format(RedisKeyConstants.PRODUCT_STOCK, id);


        // 1. 第一步：Redis 预扣
        Long result = redisService.executeStock(stockKey, quantity);

        if (result == -1) {
            log.warn("产品 {} 缓存失效，触发同步", id);
            try {
                // 【修复点1】加上 try-catch，防止同步失败导致事务回滚异常
                productStockService.syncProductStockToRedis(id);
            } catch (Exception e) {
                // 就算同步失败，也只记录日志。反正下面会抛出“让用户重试”的异常，
                // 这样能保证异常类型是 BusinessException，而不是 UnexpectedRollbackException
                log.error("lockStock同步缓存失败，忽略错误: {}", e.getMessage());
            }
            throw new BusinessException("系统繁忙，请稍后重试");
        }

        if (result == -2) {
            throw new BusinessException("产品库存不足");
        }

        // 2. 第二步：数据库扣减
        int rows = prodInfoMapper.lockStock(id, quantity);

        if (rows == 0) {
            // Redis 扣减成功但 DB 扣减失败，执行回滚补偿
            redisService.incrementStock(stockKey, quantity);
            log.error("产品 {} Redis 扣减成功但 DB 扣减失败，执行回滚补偿", id);
            throw new BusinessException("产品已被抢购空，请重试");
        }

    }

    // 解锁库存
    @Transactional(rollbackFor = Exception.class)
    public void unlockStock(Long id, BigDecimal quantity) {
        // 1. 数据库回补
        prodInfoMapper.unlockStock(id, quantity);

        // 2. Redis 份额回补
        String stockKey = String.format(RedisKeyConstants.PRODUCT_STOCK, id);

        Long result = redisService.incrementStock(stockKey, quantity);

        if (result == null || result < 0) {
            log.warn("Redis 库存回补异常，触发手动同步。Key: {}", stockKey);
            try {
                // 【修复点2】解锁是补偿操作，绝不能因为它失败而炸毁主流程
                productStockService.syncProductStockToRedis(id);
            } catch (Exception e) {
                log.error("unlockStock同步缓存失败，仅记录日志: {}", e.getMessage());
            }
        }
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

    public List<ProductRateHistory> selectList(LambdaQueryWrapper<ProductRateHistory> eq) {
        return productRateHistoryMapper.selectList(eq);
    }

    public List<ProductVO> selectListForAgent(Integer RiskLevel) {
        return prodInfoMapper.selectProductVOList(RiskLevel);

    }
}