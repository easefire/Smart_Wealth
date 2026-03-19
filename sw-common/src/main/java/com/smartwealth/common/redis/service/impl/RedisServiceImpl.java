package com.smartwealth.common.redis.service.impl;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
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
    @Override
    public void delete(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        redisTemplate.delete(keys);
    }
    //设置过期时间
    @Override
    public void expire(String key, long timeout, TimeUnit unit) {
        redisTemplate.expire(key, timeout, unit);
    }
    //获取过期时间
    @Override
    public Long getExpire(String key) { return redisTemplate.getExpire(key, TimeUnit.SECONDS); }
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

    /**
     * 【生产级】模糊查询 Key
     * 使用 SCAN 命令替代 KEYS 命令，避免大数据量下阻塞 Redis 主线程
     *
     * @param pattern 匹配模式，例如 "prod:list:*"
     * @return 匹配到的 Key 集合
     */
    @Override
    public Set<String> getkeys(String pattern) {
        return redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keys = new HashSet<>();

            // 使用 SCAN 命令，count 1000 表示每次遍历 1000 个槽位，不阻塞
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(1000)
                    .build();

            // 这里的 cursor 不需要手动关闭，Spring 会自动处理
            // 但为了保险，通常放在 try-with-resources 或者由 execute 自动管理
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                throw new RuntimeException("Redis scan failed", e);
            }
            return keys;
        });
    }

    /**
     * 【极其核心】使用 Pipeline 管道批量写入 Redis 并设置相同的过期时间
     * 物理意义：将 N 次网络 RTT (往返耗时) 压缩为 1 次，极大提升吞吐量。
     *
     * @param dataMap 键值对集合 (Key -> Value)
     * @param timeout 过期时间数值
     * @param unit    时间单位
     */
    public void setPipelinedEx(Map<String, Object> dataMap, long timeout, TimeUnit unit) {
        if (CollectionUtils.isEmpty(dataMap)) {
            return;
        }

        // 1. 基础 TTL（例如你传入的 25 小时）
        long baseTtlSeconds = unit.toSeconds(timeout);

        RedisSerializer<String> keySerializer = (RedisSerializer<String>) redisTemplate.getKeySerializer();
        RedisSerializer<Object> valueSerializer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();

        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                if (entry.getValue() == null) continue;

                byte[] rawKey = keySerializer.serialize(entry.getKey());
                byte[] rawVal = valueSerializer.serialize(entry.getValue());

                if (rawKey != null && rawVal != null) {
                    // 2. 【核心改动】：为每个 Key 独立计算随机抖动（0 到 60 分钟之间）
                    // 3600 秒 = 60 分钟
                    long jitter = ThreadLocalRandom.current().nextLong(3600);
                    long finalTtl = baseTtlSeconds + jitter;

                    // 3. 每一个 Key 的过期时间都不一样，彻底打散缓存失效时间
                    connection.setEx(rawKey, finalTtl, rawVal);
                }
            }
            return null;
        });
    }
}

