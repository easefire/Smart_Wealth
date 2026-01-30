package com.smartwealth.common.redis.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 工具服务
 */
public interface RedisService {
    // 存数据
    void set(String key, Object value);
    // 存数据并设置过期时间
    void set(String key, Object value, long timeout, TimeUnit unit);
    // 存数据-库存
    void setSTOCK(String key, String value);
    // 存数据如果不存在
    Boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit);
    // 取数据
    Object get(String key);
    // 取数据并转换类型
    <T> T get(String key, Class<T> clazz);
    // 批量获取
    List<Object> multiGet(Collection<String> keys);
    // 删除数据
    void delete(String key);
    // 判断是否存在
    Boolean hasKey(String key);
    // 设置过期时间
    void expire(String key, long timeout, TimeUnit unit);
    // 获取过期时间
    Long getExpire(String key);
    // 分布式锁：尝试加锁
    boolean tryLock(String key, long timeout, TimeUnit unit);
    // 分布式锁：释放锁
    void unlock(String key);
    // 扣减库存
    Long executeStock(String key, BigDecimal quantity);
    // 增加库存
    Long incrementStock(String key, BigDecimal delta);
    // 获取库存
    BigDecimal getRedisStock(Long id);
}