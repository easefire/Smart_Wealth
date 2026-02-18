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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ProductStockService {

    @Autowired
    private RedisService redisService;

    // 1. 注入 Mapper，这是你访问数据库的“钥匙”
    @Autowired
    private ProdInfoMapper prodInfoMapper;

    @Autowired
    RedissonClient redissonClient;

    /**
     * 将数据库库存同步至 Redis (支持高并发防击穿)
     *
     * @param prodId 产品ID
     * @return 真实的可用份额 (BigDecimal)
     */

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public BigDecimal syncProductStockToRedis(Long prodId) {
        // 1. 定义锁 Key
        String lockKey = String.format(RedisKeyConstants.PRODUCT_LOCK, prodId);
        RLock lock = redissonClient.getLock(lockKey);

        boolean isLocked = false;
        try {
            // 2. 尝试加锁
            // waitTime=500ms: 给其他争抢线程 0.5秒 的机会，不要稍微慢点就直接失败
            // leaseTime=5s:   防止死锁，5秒后自动释放（数据库查询通常在 ms 级别，5s 足够）
            isLocked = lock.tryLock(500, 5000, TimeUnit.MILLISECONDS);

            if (isLocked) {
                // ==================== 🔒 拿锁成功区域 ====================

                // 3. 双重检查 (DCL): 也许在排队等锁的时候，前一个线程已经同步好了
                BigDecimal cachedStock = redisService.getRedisStock(prodId);
                if (cachedStock != null) {
                    return cachedStock; // 直接返回，节省一次查库
                }

                // 4. 查数据库 (主库)
                ProdInfo prod = prodInfoMapper.selectById(prodId);
                if (prod == null) {
                    // 库里也没这个产品，可能被物理删除了，返回 0
                    return BigDecimal.ZERO;
                }

                // 5. 计算可用库存 = 总库存 - 冻结库存
                BigDecimal available = prod.getTotalStock().subtract(prod.getLockedStock());
                // 容错：防止负库存
                if (available.compareTo(BigDecimal.ZERO) < 0) {
                    available = BigDecimal.ZERO;
                }

                // 6. 【核心】写入 Redis (放大 10^6 倍存为整数)
                // 这里的 multiply(1000000) 必须与读取时的 movePointLeft(6) 完美对应
                long scaledStock = available.multiply(new BigDecimal("1000000")).longValue();

                String stockKey = String.format(RedisKeyConstants.PRODUCT_STOCK, prodId);
                redisService.setSTOCK(stockKey, String.valueOf(scaledStock));

                log.info("✅ 库存同步完成 [ID:{}], DB库存: {}, Redis写入值: {}", prodId, available, scaledStock);
                return available;
            } else {
                // ==================== ⏳ 没抢到锁区域 ====================
                // 7. 降级策略：没抢到锁，说明有人正在同步。
                // 此时不要返回 0！因为这会误导用户以为“卖光了”。
                // 策略：直接读数据库返回给当前用户，但【不写】Redis（防止并发写冲突）
                log.warn("⚠️ [并发保护] 锁等待超时，准备降级。ID:{}", prodId);

                // 1. 【关键补丁】最后一次尝试读 Redis
                // 很有可能拿锁的那个线程 A 已经在第 499ms 把数据写进去了
                // 此时直接读 Redis，就能避免打扰数据库
                BigDecimal lastChance = redisService.getRedisStock(prodId);
                if (lastChance != null) {
                    return lastChance;
                }

                // 2. 真的没办法了，Redis 还是空的，只能查库兜底
                // 这里的流量就是漏网之鱼，虽然还有风险，但概率已经极低了
                return this.getStockFromDbOnly(prodId);
            }

        } catch (InterruptedException e) {
            log.error("库存同步线程被中断", e);
            Thread.currentThread().interrupt();
            return BigDecimal.ZERO; // 异常情况返回 0
        } catch (Exception e) {
            log.error("库存同步未知异常", e);
            throw e; // 抛出异常让上层感知
        } finally {
            // 8. 安全解锁
            // 必须判断：锁是否存在 && 锁是不是当前线程加的
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private BigDecimal getStockFromDbOnly(Long prodId) {
        ProdInfo prod = prodInfoMapper.selectById(prodId);
        if (prod == null) return BigDecimal.ZERO;
        BigDecimal available = prod.getTotalStock().subtract(prod.getLockedStock());
        return available.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : available;
    }
}
