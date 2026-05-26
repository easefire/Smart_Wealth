package com.smartwealth.common.redis.service;

import org.springframework.data.redis.core.RedisOperations;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 工具服务
 */
public interface RedisService {
    // 存数据
    /**
     * 通用存值接口（业务对象 / VO / DTO）。
     *
     * <p>【BUGFIX-P2-#24】明确"两套 RedisTemplate"的边界：
     * <ul>
     *   <li>{@code set / get / multiGet / delete / expire} —— 走 <strong>Jackson 序列化</strong>的 {@code redisTemplate}，
     *       存的是 JSON，<strong>禁止</strong>跟 {@code stringRedisTemplate} 写入的 key 互读。</li>
     *   <li>{@code setSTOCK / setIfAbsent / executeStock / incrementStock / getRedisStock / multiGetStocks}
     *       —— 走 <strong>纯字符串</strong>的 {@code stringRedisTemplate}，专用于<br>
     *       (a) 数字型库存（Lua 脚本）；(b) 防重提交标记；(c) 任何不需要类型信息的字符串值。</li>
     * </ul>
     * 命名规则：业务上同一个 key 的写入和读取，必须保持在同一组方法之内，否则会出现
     * 反序列化错乱（Jackson 把 "12345" 解成 {@code Long}, 或把 {@code Long} 解成 String 失败）。
     */
    void set(String key, Object value);
    // 存数据并设置过期时间（同上，走 Jackson 序列化器）
    void set(String key, Object value, long timeout, TimeUnit unit);
    // 存库存数字（走 stringRedisTemplate，纯字符串，配合 Lua 脚本使用）
    void setSTOCK(String key, String value);
    // 防重复提交锁等纯字符串值（走 stringRedisTemplate）
    Boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit);
    // 取数据
    Object get(String key);
    // 取数据并转换类型
    <T> T get(String key, Class<T> clazz);
    // 批量获取
    List<Object> multiGet(Collection<String> keys);
    // 删除数据
    void delete(String key);
    //批量删除
    void delete(Collection<String> keys);
    // 判断是否存在
    Boolean hasKey(String key);
    // 设置过期时间
    void expire(String key, long timeout, TimeUnit unit);
    // 获取过期时间
    Long getExpire(String key);
    // 扣减库存
    Long executeStock(String key, BigDecimal quantity);
    // 增加库存
    Long incrementStock(String key, BigDecimal delta);
    // 获取库存
    BigDecimal getRedisStock(Long id);

    /**
     * 【NEW for #7】批量获取库存（已经反 scale 还原成业务面值的 BigDecimal）。
     * <p>
     * 之前 ProdInfoServiceImpl.getUserProductPage 直接调 multiGet（走 Jackson 序列化器），
     * 而 setSTOCK 是 stringRedisTemplate 写入；两条序列化路径混用，
     * 在 Jackson 配置变化时极易出现 ClassCastException 或反序列化为奇怪类型。
     * 这里统一走 stringRedisTemplate，把 scale 处理收敛到一个地方。
     *
     * @param ids 产品 ID 列表，顺序与返回一致
     * @return 与 ids 等长的库存列表；某一项查不到 / 解析失败 → null
     */
    List<BigDecimal> multiGetStocks(List<Long> ids);

    Set<String> getkeys(String s);

    void setPipelinedEx(Map<String, Object> cacheData, long finalTtlSeconds, TimeUnit timeUnit);
}