package com.smartwealth.product.service.impl;

import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.product.mapper.ProdInfoMapper;
import com.smartwealth.product.entity.ProdInfo;
import lombok.extern.slf4j.Slf4j;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BigDecimal syncProductStockToRedis(Long prodId) {
        String lockKey = String.format(RedisKeyConstants.PRODUCT_LOCK, prodId);
        boolean locked = redisService.tryLock(lockKey, 5, TimeUnit.SECONDS);

        try {
            if (locked) {
                BigDecimal stock = redisService.getRedisStock(prodId);
                if (stock != null) return stock;

                // 2. 【核心修改】使用注入的 mapper 替代 this.getById
                ProdInfo prod = prodInfoMapper.selectById(prodId);
                if (prod == null) return BigDecimal.ZERO;

                BigDecimal available = prod.getTotalStock().subtract(prod.getLockedStock());

                // 3. 回填 Redis (放大倍数法已经在 redisService 内部处理或此处手动处理)
                // 建议这里统一转成字符串，防止 Jackson 序列化加双引号
                long scaledStock = available.multiply(new BigDecimal("1000000")).longValue();
                redisService.setSTOCK(String.format(RedisKeyConstants.PRODUCT_STOCK, prodId), String.valueOf(scaledStock));

                log.info("成功执行独立事务回填，产品 {} 份额已同步至 Redis: {}", prodId, available);
                return available;
            }
            return BigDecimal.ZERO;
        } finally {
            if (locked) redisService.unlock(lockKey);
        }
    }
}
