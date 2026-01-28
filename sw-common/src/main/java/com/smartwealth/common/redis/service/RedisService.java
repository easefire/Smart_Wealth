package com.smartwealth.common.redis.service;

import com.fasterxml.jackson.core.type.TypeReference;

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


    void setSTOCK(String key, String value);

    // 存数据并设置过期时间
    void set(String key, Object value, long timeout, TimeUnit unit);

    // 取数据
    Object get(String key);

    // 取数据并转换类型
    <T> T get(String key, Class<T> clazz);

    // 删除数据
    Boolean delete(String key);

    // 判断是否存在
    Boolean hasKey(String key);

    /**
     * 设置过期时间
     * @param key Redis键
     * @param timeout 时间
     * @param unit 单位
     * @return 是否成功
     */
    Boolean expire(String key, long timeout, TimeUnit unit);

    /**
     * 获取剩余过期时间
     * @param key Redis键
     * @return 剩余时间（单位：秒）
     */
    Long getExpire(String key);

    // 批量获取
    List<Object> multiGet(Collection<String> keys);

    // 分布式锁：尝试加锁
    boolean tryLock(String key, long timeout, TimeUnit unit);

    // 分布式锁：释放锁
    void unlock(String key);

    /**
     * 执行库存扣减 Lua 脚本
     * @param key 库存的 Redis Key
     * @param quantity 扣减数量
     * @return 扣减后的库存；-1 表示 Key 不存在；-2 表示余额不足
     */
    Long executeStockLua(String key, BigDecimal quantity);
    /**
     * 增加/减少数值（原子操作）
     * @param key Redis Key
     * @param delta 增量（正数为加，负数为减）
     */

    Long incrementStock(String key, long delta);

    BigDecimal getRedisStock(Long id);
}