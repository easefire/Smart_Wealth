package com.smartwealth.product.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.entity.ProductRateHistory;
import com.smartwealth.product.enums.MarketSentiment;
import com.smartwealth.product.mapper.ProdInfoMapper;
import com.smartwealth.product.mapper.ProductRateHistoryMapper;
import com.smartwealth.product.utils.NavAlgorithmUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 【REFACTOR-Step3-3】每日净值更新调度。
 *
 * <p>从 {@link ProdInfoServiceImpl} 中拆出来，原因：
 *   ① 净值算法是产品经理三天两头改的"软算法"，跟产品 CRUD/查询完全无关；
 *   ② TransactionTemplate 是这里独有的事务策略，挪出来后调用方一目了然；
 *   ③ 单产品独立小事务的写法对 self-invocation 敏感，拆出独立 bean 才彻底安全。
 *
 * <p>对外行为与原 {@code ProdInfoServiceImpl.updateAllProductNav} 保持一致。
 */
@Slf4j
@Service
public class ProductNavUpdateService {

    /**
     * 【BUGFIX-P2-#26】单产品净值更新的事务边界。
     * 用 TransactionTemplate 而不是再开一个 helper @Component 加 @Transactional，是为了：
     *   ① 不再扩散一个新 bean；
     *   ② 不被 self-invocation 坑（同类直接调 this.xxx 不走代理，@Transactional 直接失效）。
     */
    private final TransactionTemplate navUpdateTxTemplate;

    @Autowired
    private RedisService redisService;
    @Autowired
    private ProdInfoMapper prodInfoMapper;
    @Autowired
    private ProductRateHistoryMapper historyMapper;

    @Autowired
    public ProductNavUpdateService(PlatformTransactionManager txManager) {
        this.navUpdateTxTemplate = new TransactionTemplate(txManager);
        this.navUpdateTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 核心调度入口：更新所有产品净值。
     *
     * 【BUGFIX-P2-#26】拆解原"超大事务"：
     *   - 老实现给整个方法加 @Transactional，把"读全量产品 + N 次 update + N 次 insert"
     *     全部塞在一个 RR 事务里。当产品数量上千时，行锁和 undo log 飙高，
     *     某一条产品计算失败会让所有产品的更新一并回滚，反而违背"每日净值"业务的语义
     *     （理财净值是按产品独立的，A 产品挂了不应连累 B）。
     *   - 新实现：
     *       ① 外层不开事务（读不需要、写按产品独立）；
     *       ② 单产品的 update + history insert 在一个独立 small-tx 里完成，
     *          通过 TransactionTemplate 编程式事务，避免 self-invocation 让 @Transactional 失效；
     *       ③ 单产品失败只记录、跳过，整体批次仍能完成；
     *       ④ 返回的 list 只包含<strong>真正成功更新</strong>的产品，warmUp 缓存预热不会写入失败的脏数据。
     */
    public List<ProdInfo> updateAllProductNav() {
        log.info("========== 开始执行每日净值更新计算 ==========");

        // 1. 获取今日市场情绪
        String sentimentCode = redisService.get(RedisKeyConstants.KEY_MARKET_SENTIMENT_LATEST, String.class);
        MarketSentiment sentiment = StrUtil.isBlank(sentimentCode)
                ? MarketSentiment.SIDEWAYS
                : MarketSentiment.valueOf(sentimentCode);

        log.info("今日市场剧本：{}", sentiment.getDescription());

        // 2. 查出所有"运行中"的产品（不需要事务）
        List<ProdInfo> productList = prodInfoMapper.selectList(
                new LambdaQueryWrapper<ProdInfo>().eq(ProdInfo::getStatus, 1)
        );

        if (CollectionUtils.isEmpty(productList)) {
            return Collections.emptyList();
        }

        LocalDate recordDate = LocalDate.now().minusDays(1);

        // 3. 单产品独立小事务，互不影响
        List<ProdInfo> succeeded = new ArrayList<>(productList.size());
        int failed = 0;
        for (ProdInfo prod : productList) {
            try {
                updateOneProductNavInTx(prod, sentiment, recordDate);
                succeeded.add(prod);
            } catch (Exception e) {
                failed++;
                log.error("产品净值更新失败，跳过该产品。productId={}, name={}",
                        prod.getId(), prod.getName(), e);
            }
        }

        log.info("净值计算落库完成，成功 {} / 失败 {} / 总计 {}",
                succeeded.size(), failed, productList.size());

        return succeeded;
    }

    /**
     * 单产品净值更新的独立事务边界。
     * 用 TransactionTemplate 编程式事务，绕开 self-invocation 让 @Transactional 失效的坑。
     */
    private void updateOneProductNavInTx(ProdInfo prod, MarketSentiment sentiment, LocalDate recordDate) {
        BigDecimal changeRate = NavAlgorithmUtils.calculateDailyChange(sentiment, prod.getRiskLevel());
        BigDecimal oldNav = prod.getCurrentNav();
        BigDecimal newNav = oldNav.multiply(BigDecimal.ONE.add(changeRate))
                .setScale(10, RoundingMode.HALF_UP);

        navUpdateTxTemplate.executeWithoutResult(status -> {
            prod.setCurrentNav(newNav);
            prod.setLatestRate(changeRate);
            prod.setUpdateTime(LocalDateTime.now());
            prodInfoMapper.updateById(prod);

            ProductRateHistory history = new ProductRateHistory();
            history.setProdId(prod.getId());
            history.setNav(newNav);
            history.setRate(changeRate);
            history.setRecordDate(recordDate);
            historyMapper.insert(history);
        });
    }
}
