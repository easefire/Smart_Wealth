package com.smartwealth.common;

import com.smartwealth.common.redis.service.impl.RedisServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 覆盖 P1-#7：multiGetStocks 必须用 stringRedisTemplate（避免 Jackson 序列化器混读），
 * 并把 1e6 反 scale 收敛到一处。
 *
 * Lua 脚本本身需要真实 Redis，不在单测里跑（留给集成测试）；
 * 这里只测 Java 侧的 scale 换算 + 边界。
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceImplStockTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private RedisServiceImpl redisService;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("getRedisStock：从 Redis 拿到 466111590000，反 scale 为 466111.59")
    void getRedisStock_scales_back_correctly() {
        when(valueOps.get("prod:stock:1")).thenReturn("466111590000");
        BigDecimal stock = redisService.getRedisStock(1L);
        assertEquals(0, stock.compareTo(new BigDecimal("466111.59")));
    }

    @Test
    @DisplayName("getRedisStock：值为 null 时返回 null（缺失语义）")
    void getRedisStock_null_value() {
        when(valueOps.get("prod:stock:99")).thenReturn(null);
        assertNull(redisService.getRedisStock(99L));
    }

    @Test
    @DisplayName("getRedisStock：脏数据（非数字）返回 null 而非抛异常，由调用方回源 DB")
    void getRedisStock_dirty_value_returns_null() {
        when(valueOps.get("prod:stock:7")).thenReturn("garbage");
        assertNull(redisService.getRedisStock(7L));
    }

    @Test
    @DisplayName("multiGetStocks：保持顺序对应；缺失值占位 null；不抛异常")
    void multiGetStocks_preserves_order_and_null_holes() {
        List<Long> ids = Arrays.asList(10L, 20L, 30L);
        when(valueOps.multiGet(anyList())).thenReturn(
                Arrays.asList("1500000000", null, "750000")  // 1500、null、0.75
        );

        List<BigDecimal> result = redisService.multiGetStocks(ids);

        assertEquals(3, result.size());
        assertEquals(0, result.get(0).compareTo(new BigDecimal("1500")));
        assertNull(result.get(1));
        assertEquals(0, result.get(2).compareTo(new BigDecimal("0.75")));
    }

    @Test
    @DisplayName("multiGetStocks：入参为空时返回空列表，不查 Redis")
    void multiGetStocks_empty_input() {
        assertTrue(redisService.multiGetStocks(null).isEmpty());
        assertTrue(redisService.multiGetStocks(List.of()).isEmpty());
    }
}
