package com.smartwealth.common.redis.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class RedisServiceImpl implements RedisService {

    private static final BigDecimal STOCK_SCALE = new BigDecimal("1000000");
    private static final String STOCK_LUA =
            "if redis.call('exists', KEYS[1]) == 0 then return -1 end " +
                    "local stock = tonumber(redis.call('get', KEYS[1])) " +
                    "local num = tonumber(ARGV[1]) " +
                    "if stock >= num then " +
                    "  local new_stock = stock - num " +
                    "  redis.call('set', KEYS[1], tostring(new_stock)) " +
                    "  return 1 " +
                    "else return -2 end";
    private static final String UNLOCK_STOCK_LUA =
            "if redis.call('exists', KEYS[1]) == 1 then " +
                    "    return redis.call('incrby', KEYS[1], ARGV[1]) " +
                    "else " +
                    "    return -1 " +
                    "end";
    private static final DefaultRedisScript<Long> LOCK_STOCK_SCRIPT = new DefaultRedisScript<>(STOCK_LUA, Long.class);
    private static final DefaultRedisScript<Long> UNLOCK_STOCK_SCRIPT = new DefaultRedisScript<>(UNLOCK_STOCK_LUA, Long.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //设置值
    @Override
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }
    //设置值并设置过期时间
    @Override
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }
    //设置值如果不存在
    @Override
    public Boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        return stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit);
    }
    //设置库存值
    @Override
    public void setSTOCK(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }
    //获取值
    @Override
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }
    //获取值并转换类型
    @Override
    public <T> T get(String key, Class<T> clazz) {
        Object value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            return null;
        }
        // 如果已经是目标类型，直接返回
        if (clazz.isInstance(value)) {
            return clazz.cast(value);
        }
        try {
            // 智能转换：将 Map/List 转为目标对象
            return objectMapper.convertValue(value, clazz);
        } catch (IllegalArgumentException e) {
            // 兜底：万一真的是 String JSON
            if (value instanceof String) {
                try {
                    return objectMapper.readValue((String) value, clazz);
                } catch (Exception ex) {
                    log.error("JSON 解析失败: {}", key, ex);
                }
            }
            log.error("类型转换失败: key={}, 期望={}, 实际={}", key, clazz.getSimpleName(), value.getClass().getSimpleName());
            return null;
        }
    }
    //批量获取值
    @Override
    public List<Object> multiGet(Collection<String> keys) { return redisTemplate.opsForValue().multiGet(keys); }
    //判断key是否存在
    @Override
    public Boolean hasKey(String key) { return redisTemplate.hasKey(key); }
    //删除key
    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }
    //设置过期时间
    @Override
    public void expire(String key, long timeout, TimeUnit unit) {
        redisTemplate.expire(key, timeout, unit);
    }
    //获取过期时间
    @Override
    public Long getExpire(String key) { return redisTemplate.getExpire(key, TimeUnit.SECONDS); }
    //获取分布式锁
    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "LOCK", timeout, unit));
    }
    //释放分布式锁
    @Override
    public void unlock(String key) { redisTemplate.delete(key); }
    // Lua 脚本扣减库存
    @Override
    public Long executeStock(String key, BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return -1L;
        }
        long scaledQuantity = quantity.multiply(STOCK_SCALE).longValue();
        return stringRedisTemplate.execute(
                LOCK_STOCK_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(scaledQuantity)
        );
    }
    // Lua 脚本增加库存
    @Override
    public Long incrementStock(String key, BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return 0L;
        }
        long scaledDelta = quantity.multiply(STOCK_SCALE).longValue();
        return stringRedisTemplate.execute(
                UNLOCK_STOCK_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(scaledDelta)
        );
    }
    // 获取 Redis 中的库存
    @Override
    public BigDecimal getRedisStock(Long id) {
        String stockKey = String.format(RedisKeyConstants.PRODUCT_STOCK, id);
        String val = stringRedisTemplate.opsForValue().get(stockKey);
        if (val == null) {
            return null;
        }
        try {
            return new BigDecimal(val).divide(new BigDecimal("1000000"), 6, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.error("库存数据异常: key={}, val={}", stockKey, val);
            return null;
        }
    }

}