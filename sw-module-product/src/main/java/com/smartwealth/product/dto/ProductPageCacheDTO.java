package com.smartwealth.product.dto;

import com.smartwealth.product.vo.ProductVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 【NEW for P2-#28】产品列表分页的缓存载体。
 *
 * <p>背景：
 *   原实现 {@code redisService.set(cacheKey, page, ...)}，缓存的是 mybatis-plus 的 {@code Page<ProductVO>} 整对象，
 *   它带了 {@code orders}, {@code countId}, {@code maxLimit} 等大量与"列表数据"无关的内部状态。
 *   一旦升级 mybatis-plus 版本或者把 ProductVO 加字段、Jackson 默认开启的"未知字段报错"，
 *   旧缓存反序列化直接抛异常，灰度发布时几乎必踩。
 *
 * <p>这里只缓存真正稳定的三件事：
 *   <ul>
 *     <li>当前页 records（业务列表）</li>
 *     <li>当前页码 current</li>
 *     <li>总条数 total（决定前端分页按钮）</li>
 *   </ul>
 *   读出后由调用方重新装回 {@code Page<ProductVO>}。
 *
 * <p>额外好处：序列化体积更小，Redis 也更省。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductPageCacheDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<ProductVO> records;
    private long current;
    private long size;
    private long total;
}
