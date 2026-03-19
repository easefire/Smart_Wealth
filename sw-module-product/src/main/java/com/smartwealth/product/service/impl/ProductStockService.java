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

    /**
     * 将数据库库存同步至 Redis
     *
     * @param prodId 产品ID
     * @return 真实的可用份额 (BigDecimal)
     */
    public BigDecimal syncProductStockToRedis(Long prodId) {
        // 【步骤 1】第一重检查：拦截 99% 的正常流量
        BigDecimal cachedStock = redisService.getRedisStock(prodId);
        if (cachedStock != null) {
            return cachedStock;
        }

        String lockKey = String.format(RedisKeyConstants.PRODUCT_LOCK, prodId);
        RLock lock = redissonClient.getLock(lockKey);
        boolean isLocked = false;

        try {
            // 【步骤 2】尝试加锁：最多等待 2000 毫秒，拿到锁后持有 5000 毫秒
            // 等待的目的是让未拿到锁的线程在这里“挂起”，等待第一个抢到锁的线程建好缓存
            isLocked = lock.tryLock(2000, 5000, TimeUnit.MILLISECONDS);

            if (isLocked) {
                // 【步骤 3】第二重检查 (DCL)：拿到锁的第一件事，看看前面拿到锁的兄弟是不是已经把缓存写好了
                cachedStock = redisService.getRedisStock(prodId);
                if (cachedStock != null) {
                    return cachedStock;
                }

                // 【步骤 4】核心逻辑：完全移除事务包裹，只做普通查询
                ProdInfo prod = prodInfoMapper.selectById(prodId);
                if (prod == null) {
                    // 防缓存穿透：可以写入一个特殊标识（如特定负数），这里简化返回 0
                    return BigDecimal.ZERO;
                }

                BigDecimal available = prod.getTotalStock().subtract(prod.getLockedStock());
                if (available.compareTo(BigDecimal.ZERO) < 0) {
                    available = BigDecimal.ZERO;
                }

                // 【步骤 5】同步 Redis
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