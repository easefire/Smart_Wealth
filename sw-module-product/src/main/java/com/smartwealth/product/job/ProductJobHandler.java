package com.smartwealth.product.job;

import com.smartwealth.product.entity.MarketSentimentLog;
import com.smartwealth.product.entity.ProdInfo;
import com.smartwealth.product.enums.MarketSentiment;
import com.smartwealth.product.service.IMarketService;
import com.smartwealth.product.service.IProdInfoService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ProductJobHandler {

    @Autowired
    private IMarketService marketService;
    @Autowired
    private IProdInfoService productService;

    @XxlJob("dailyMarketScenarioJob")
    public void dailyMarketScenarioJob() {
        // 1. 从数据库查询最近一次的情绪记录
        MarketSentimentLog lastLog = marketService.selectLatestOne();
        MarketSentiment lastSentiment = (lastLog != null)
                ? MarketSentiment.valueOf(lastLog.getScenarioCode())
                : MarketSentiment.SIDEWAYS; // 系统初始化默认震荡

        // 2. 调用算法生成今天的
        MarketSentiment todaySentiment = marketService.simulateNextSentiment(lastSentiment);

        // 3. 保存今天的情绪记录到数据库
        marketService.saveSentiment(todaySentiment, "SYSTEM");
    }

    /**
     * 每日净值更新主任务
     * 1. 算净值 (DB事务)
     * 2. 刷缓存 (Redis操作)
     */
    @XxlJob("dailyNavUpdateHandler")
    public void dailyNavUpdateHandler() {
        XxlJobHelper.log("开始每日净值更新流程...");
        long start = System.currentTimeMillis();

        try {
            // Step 1: 执行核心业务，更新数据库，并拿到最新数据
            // 这一步执行完，数据库事务已经提交了
            List<ProdInfo> updatedList = productService.updateAllProductNav();

            // Step 2: 利用刚才内存里算好的数据，直接预热缓存
            if (updatedList != null && !updatedList.isEmpty()) {
                XxlJobHelper.log("数据库更新完成，开始同步缓存预热...");
                productService.warmUpCacheAfterNavUpdate(updatedList);
            } else {
                XxlJobHelper.log("无运行中产品，跳过预热");
            }

            XxlJobHelper.handleSuccess("任务完成，总耗时：" + (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            log.error("每日净值更新失败", e);
            XxlJobHelper.handleFail("异常终止: " + e.getMessage());
        }
    }
}
