package com.smartwealth.product.service.impl;

import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.product.mapper.ProdInfoMapper;
import com.smartwealth.product.entity.ProdInfo;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ProductStockService {

    @Autowired
    private RedisService redisService;

    @Autowired
    private ProdInfoMapper prodInfoMapper;

    @Autowired
    RedissonClient redissonClient;

    public BigDecimal syncProductStockToRedis(Long prodId) {
        BigDecimal cachedStock = redisService.getRedisStock(prodId);
        if (cachedStock != null) {
            return cachedStock;
        }
        String lockKey = String.format(RedisKeyConstants.PRODUCT_LOCK, prodId);
        RLock lock = redissonClient.getLock(lockKey);
        boolean isLocked = false;
        try {
            isLocked = lock.tryLock(2000, 5000, TimeUnit.MILLISECONDS);
            if (isLocked) {
                cachedStock = redisService.getRedisStock(prodId);
                if (cachedStock != null) {
                    return cachedStock;
                }
                ProdInfo prod = prodInfoMapper.selectById(prodId);
                if (prod == null) {
                    return BigDecimal.ZERO;
                }
                BigDecimal available = prod.getTotalStock().subtract(prod.getLockedStock());
                if (available.compareTo(BigDecimal.ZERO) < 0) {
                    available = BigDecimal.ZERO;
                }
                long scaledStock = available.multiply(new BigDecimal("1000000")).longValue();
                String stockKey = String.format(RedisKeyConstants.PRODUCT_STOCK, prodId);
                redisService.setSTOCK(stockKey, String.valueOf(scaledStock));
                log.info("✅ 库存同步完成 [ID:{}], DB库存: {}, Redis写入值: {}", prodId, available, scaledStock);
                return available;
            } else {
                log.warn("获取库存同步锁超时，触发限流保护 [ID:{}]", prodId);
                throw new RuntimeException("系统当前访问人数过多，请稍后再试");
            }
        } catch (InterruptedException e) {
            log.error("库存同步线程被中断", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("系统繁忙");
        } catch (Exception e) {
            log.error("库存同步未知异常", e);
            throw e;
        } finally {
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}