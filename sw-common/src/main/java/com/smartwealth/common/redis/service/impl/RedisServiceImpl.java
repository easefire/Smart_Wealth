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

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate; // 👈 必须用它来处理库存计算

    // ... set/setSTOCK 等简单方法保持不变 ...
    @Override
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    @Override
    public void setSTOCK(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    @Override
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    @Override
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 【修复点 1】解决 "Unexpected character 'd'" 问题
     * 不再使用 toString() + readValue()，而是利用 Jackson 的 convertValue
     */

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
    // ... hasKey, delete, expire 等方法保持不变 ...
    @Override
    public Boolean hasKey(String key) { return redisTemplate.hasKey(key); }
    @Override
    public Boolean delete(String key) { return redisTemplate.delete(key); }
    @Override
    public Boolean expire(String key, long timeout, TimeUnit unit) { return redisTemplate.expire(key, timeout, unit); }
    @Override
    public Long getExpire(String key) { return redisTemplate.getExpire(key, TimeUnit.SECONDS); }
    @Override
    public List<Object> multiGet(Collection<String> keys) { return redisTemplate.opsForValue().multiGet(keys); }
    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "LOCK", timeout, unit));
    }
    @Override
    public void unlock(String key) { redisTemplate.delete(key); }

    // Lua 脚本
    private static final String STOCK_LUA =
            "if redis.call('exists', KEYS[1]) == 0 then return -1 end " +
                    "local stock = tonumber(redis.call('get', KEYS[1])) " +
                    "local num = tonumber(ARGV[1]) " +
                    "if stock >= num then " +
                    "  local new_stock = stock - num " +
                    "  redis.call('set', KEYS[1], tostring(new_stock)) " +
                    "  return 1 " +
                    "else return -2 end";

    @Override
    public Long executeStockLua(String key, BigDecimal quantity) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(STOCK_LUA, Long.class);
        // 【修复点 2】必须用 stringRedisTemplate
        // 否则 quantity 会被序列化成带引号的 JSON 字符串，导致 Lua 报错
        return stringRedisTemplate.execute(script, Collections.singletonList(key), String.valueOf(quantity.longValue()));
    }

    private static final String UNLOCK_STOCK_LUA =
            "if redis.call('exists', KEYS[1]) == 1 then " +
                    "    return redis.call('incrby', KEYS[1], ARGV[1]) " +
                    "else " +
                    "    return -1 " +
                    "end";

    @Override
    public Long incrementStock(String key, long delta) {
        String deltaStr = String.valueOf(delta);
        // 【修复点 3】这里也必须用 stringRedisTemplate
        // 之前这里用的是 redisTemplate，导致传进去的是 "4000" (String)，而不是 4000 (Integer)
        return stringRedisTemplate.execute(
                new DefaultRedisScript<>(UNLOCK_STOCK_LUA, Long.class),
                Collections.singletonList(key),
                deltaStr
        );
    }

    @Override
    public BigDecimal getRedisStock(Long id) {
        String stockKey = String.format(RedisKeyConstants.PRODUCT_STOCK, id);
        // 【修复点 4】使用 stringRedisTemplate 获取纯字符串
        String val = stringRedisTemplate.opsForValue().get(stockKey);

        if (val == null) {
            return null; // 返回 null 触发上层同步
        }

        try {
            return new BigDecimal(val).divide(new BigDecimal("1000000"), 6, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.error("库存数据异常: key={}, val={}", stockKey, val);
            return null;
        }
    }
}