package com.smartwealth.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.product.dto.ProductSaveDTO;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.entity.ProductRateHistory;
import com.smartwealth.product.mapper.ProdInfoMapper;
import com.smartwealth.product.service.IProdInfoService;
import com.smartwealth.product.service.IProductRateHistoryService;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.product.vo.ProductVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
// 【MIGRATION】Adapter 已被 Spring 6 移除，统一使用 TransactionSynchronization 接口的 default 方法。
import org.springframework.transaction.support.TransactionSynchronization;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization;

/**
 * <p>
 * 理财产品信息表 服务实现类（重构后的"瘦门面"）。
 * </p>
 *
 * 【REFACTOR-Step3】之前这里是 815 行的"上帝类"，把 CRUD、分页缓存、详情多级缓存、
 * 净值更新、异步预热五件事全揉一起。本次拆分后：
 * <ul>
 *   <li>{@link #initProduct(ProductSaveDTO)} / {@link #offShelf(Long)} / {@link #getAllProducts()}
 *       —— 产品基础 CRUD 留在本类（顺带承担 IService 的契约）；</li>
 *   <li>{@link #getUserProductPage(Integer, Integer)} → {@link ProductPageQueryService}</li>
 *   <li>{@link #getProductDetail(Long, Integer)} 与 {@link #getRawProductDetailFromDb(Long)}
 *       → {@link ProductDetailQueryService}</li>
 *   <li>{@link #updateAllProductNav()} → {@link ProductNavUpdateService}</li>
 *   <li>{@link #warmUpCacheAfterNavUpdate(List)} → {@link ProductCacheWarmupService}</li>
 * </ul>
 *
 * <p>{@link IProdInfoService} 接口保持不变，所有外部调用方（Controller / JobHandler / SPI 实现）
 * 零感知。本类负责把方法委派给真正的执行者，自身只持有"产品 entity 主表"这一份职责。
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Slf4j
@Service
public class ProdInfoServiceImpl extends ServiceImpl<ProdInfoMapper, ProdInfo> implements IProdInfoService {

    @Autowired
    private IProductRateHistoryService rateHistoryService;
    @Autowired
    private RedisService redisService;
    @Autowired
    private RedissonClient redissonClient;

    // ===== 子领域协作者，按"读 / 写 / 调度 / 预热"四个轴拆分 =====
    @Autowired
    private ProductPageQueryService pageQueryService;
    @Autowired
    private ProductDetailQueryService detailQueryService;
    @Autowired
    private ProductNavUpdateService navUpdateService;
    @Autowired
    private ProductCacheWarmupService cacheWarmupService;

    // ============================================================
    //  产品 CRUD（本类自留地）
    // ============================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initProduct(ProductSaveDTO dto) {
        // 1. 业务校验：代码唯一性
        if (this.count(new LambdaQueryWrapper<ProdInfo>().eq(ProdInfo::getCode, dto.getCode())) > 0) {
            throw new BusinessException("产品代码 [" + dto.getCode() + "] 已存在");
        }

        // 2. 封装产品主表数据
        ProdInfo product = new ProdInfo();
        BeanUtils.copyProperties(dto, product);

        BigDecimal initNav = new BigDecimal("1.0000000000");
        product.setCurrentNav(initNav);
        product.setLatestRate(dto.getBaseRate());
        product.setLockedStock(BigDecimal.ZERO);
        product.setStatus(1);

        if (!this.save(product)) {
            throw new BusinessException(ResultCode.PRODUCT_SAVE_FAILURE);
        }

        // 3. 关键：同步初始化历史表 (T0 数据)
        // 【BUGFIX-#8】之前 record_date 写的是 LocalDate.now()（今天），
        //              而每日净值 Job 在次日凌晨插入的 record_date 是 LocalDate.now().minusDays(1)
        //              ——也是同一天，会与初始化这条直接撞 (prod_id, record_date) 唯一索引，
        //              正确做法：将"T0"标记为"昨日"，让 Job 在 T+1 自然续写。
        ProductRateHistory history = new ProductRateHistory();
        history.setProdId(product.getId());
        history.setRate(dto.getBaseRate());
        history.setNav(initNav);
        history.setRecordDate(LocalDate.now().minusDays(1));
        rateHistoryService.save(history);

        log.info("产品入库完成：{}, ID: {}, 初始净值: {}", product.getName(), product.getId(), initNav);

        registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 1. 模糊删除所有列表页缓存（新产品可能插在任何一页）
                Set<String> keys = redisService.getkeys(RedisKeyConstants.PRODUCT_ON_SALE_LIST + "*");
                if (CollectionUtils.isNotEmpty(keys)) {
                    redisService.delete(keys);
                }
                // 2. 注入布隆过滤器，保证后续详情查询不会被它误判为"不存在"
                RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(ProductDetailQueryService.BLOOM_FILTER_NAME);
                if (bloomFilter.isExists()) {
                    bloomFilter.add(product.getId());
                    log.info("🔥 新产品 ID {} 已同步至布隆过滤器", product.getId());
                }
                log.info("新品发布，已清除列表缓存 keys: {}", keys);
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void offShelf(Long id) {
        ProdInfo product = this.getById(id);
        if (product == null) {
            throw new BusinessException("产品不存在");
        }
        // 幂等性：已下架直接返回
        if (product.getStatus() == 2) {
            return;
        }
        product.setStatus(2);
        if (!this.updateById(product)) {
            throw new BusinessException("产品下架失败");
        }
        log.info("管理操作：产品 ID [{}] 已下架", id);

        registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // ==================== 🗑️ 清除 Redis 缓存 ====================
                List<String> keysToDelete = new ArrayList<>();
                keysToDelete.add(String.format(RedisKeyConstants.PRODUCT_DETAIL, id));
                // ⚠️【补全】库存缓存（防止前端显示有货）
                keysToDelete.add(String.format(RedisKeyConstants.PRODUCT_STOCK, id));
                redisService.delete(keysToDelete);

                Set<String> listKeys = redisService.getkeys(RedisKeyConstants.PRODUCT_ON_SALE_LIST + "*");
                if (CollectionUtils.isNotEmpty(listKeys)) {
                    redisService.delete(listKeys);
                }

                // ==================== 📢 清除本地缓存 (Caffeine) ====================
                // 动作 A: 清除当前机器的 Caffeine
                detailQueryService.invalidateLocal(id);

                // 动作 B: 广播通知其他机器清除
                // 生产环境必须加这个，否则其他节点会有短暂的数据不一致
                RTopic topic = redissonClient.getTopic("product:cache:invalidate");
                topic.publish(id);

                log.info("产品下架缓存清理完成。ID: {}", id);
            }
        });
    }

    @Override
    public List<ProdInfo> getAllProducts() {
        return this.list();
    }

    // ============================================================
    //  以下方法仅做"代理委派"，业务实现已经搬到对应的子 Service
    // ============================================================

    @Override
    public IPage<ProductVO> getUserProductPage(Integer pageNo, Integer pageSize) {
        return pageQueryService.getUserProductPage(pageNo, pageSize);
    }

    @Override
    public ProductDetailVO getProductDetail(Long prodId, Integer days) {
        return detailQueryService.getProductDetail(prodId, days);
    }

    @Override
    public ProductDetailVO getRawProductDetailFromDb(Long prodId) {
        return detailQueryService.getRawProductDetailFromDb(prodId);
    }

    @Override
    public List<ProdInfo> updateAllProductNav() {
        return navUpdateService.updateAllProductNav();
    }

    @Override
    public void warmUpCacheAfterNavUpdate(List<ProdInfo> productList) {
        cacheWarmupService.warmUpCacheAfterNavUpdate(productList);
    }
}
