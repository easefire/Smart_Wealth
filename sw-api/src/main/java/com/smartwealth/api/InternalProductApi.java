package com.smartwealth.api;

import com.smartwealth.common.exception.BusinessException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 产品模块对外 SPI 契约。
 *
 * <p>同样只放<strong>对外业务能力</strong>，不暴露 ProdInfo / ProductDetailVO 等业务 entity 与 VO。
 * trade 模块在做申购、赎回、对账时只需要"锁库存 / 解锁库存 / 名称映射 / 净值映射"这四条能力。
 *
 * <p><strong>实现类</strong>：{@code com.smartwealth.product.service.impl.InternalProductService}（@Service）。
 */
public interface InternalProductApi {

    /**
     * Redis 层锁定一份产品库存（Lua 原子扣减）。
     * Redis 缺失会自动回源 DB 同步一次再重试一次。
     *
     * @throws BusinessException 库存不足或同步缓存失败时
     */
    void lockStock(Long productId, BigDecimal quantity);

    /**
     * 解锁/回补一份库存。
     * 内部按"先 DB 后 Redis"的顺序，并在 Redis 异常时触发 DB→Redis 同步兜底。
     */
    void unlockStock(Long productId, BigDecimal quantity);

    /**
     * 批量取产品名称映射，专给 trade 模块"持仓页 / 订单页"做名称回填。
     * 入参为空集合时返回空 map，永不返回 null。
     */
    Map<Long, String> getProdNamesByIds(Set<Long> prodIds);

    /**
     * 批量取产品当前净值映射。永不返回 null。
     */
    Map<Long, BigDecimal> getProdNavMap(Set<Long> prodIds);
}
