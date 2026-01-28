package com.smartwealth.trade.job;

import com.smartwealth.asset.dto.AssetFlowTradeDTO;
import com.smartwealth.asset.service.impl.InternalAssetService;
import com.smartwealth.trade.dto.PoolCheckDTO;
import com.smartwealth.trade.mapper.TradeOrderMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class SharePoolCheckJob {

    @Autowired
    private TradeOrderMapper tradeOrderMapper; // 查业务端
    @Autowired
    private InternalAssetService assetService;  // 查流水端

    private static final Pattern SHARE_PATTERN = Pattern.compile("份额[：:]\\s*(\\d+(\\.\\d+)?)");

    @XxlJob("sharePoolCheckJob")
    public void execute() {
        log.info("========== 开始执行 [份额守恒] 对账 ==========");

        // -------------------------------------------------------
        // 1. 左边：从 Asset 流水计算“历史总买入份额”
        // -------------------------------------------------------
        // 查出所有 type='PURCHASE' 的流水，带着 remark 字段
        List<AssetFlowTradeDTO> purchaseFlows = assetService.selectPurchaseFlowsWithRemark();

        // Map<"userId_prodId", 总份额>
        Map<String, BigDecimal> assetShareMap = new HashMap<>();

        for (AssetFlowTradeDTO flow : purchaseFlows) {
            BigDecimal shares = parseShares(flow.getRemark());

            if (shares != null) {
                // Key = userId + "_" + prodId (假设流水表里有prodId，或者从biz_id取)
                String key = flow.getUserId() + "_" + flow.getProdId();
                assetShareMap.merge(key, shares, BigDecimal::add);
            }
        }

        // -------------------------------------------------------
        // 2. 右边：从 Trade 业务端计算“持有+已赎回”
        // -------------------------------------------------------
        // 直接复用之前写的 SQL，算出 (SUM(t_trade_order.amount) + SUM(t_trade_redemption_record.amount))
        // 这里的 amount 指的是份额
        List<PoolCheckDTO> tradeShareList = tradeOrderMapper.sumTradeSharesByPool();

        // -------------------------------------------------------
        // 3. 核心比对
        // -------------------------------------------------------
        for (PoolCheckDTO trade : tradeShareList) {
            String poolKey = trade.getUserId() + "_" + trade.getProdId();
            BigDecimal tradeTotalShares = trade.getAmount(); // 业务端总份额

            BigDecimal assetTotalShares = assetShareMap.remove(poolKey); // 流水端总份额

            // 异常 A: 业务端有份额，流水端没记录 (白嫖?)
            if (assetTotalShares == null) {
                log.error("🚨【严重长款】Pool[{}], 业务端有份额 {}, 但流水无记录！", poolKey, tradeTotalShares);
                continue;
            }

            // 异常 B: 份额对不上 (允许 0.01 误差)
            if (tradeTotalShares.subtract(assetTotalShares).abs().compareTo(new BigDecimal("0.01")) > 0) {
                log.error("🚨【份额不平】Pool[{}], 流水记录买入: {}, 业务端(持有+赎回): {}, 差额: {}",
                        poolKey, assetTotalShares, tradeTotalShares, tradeTotalShares.subtract(assetTotalShares));
            }
        }

        // 异常 C: 流水有记录，业务端没份额 (钱扣了，份额没给?)
        assetShareMap.forEach((key, shares) ->
                log.error("🚨【严重短款】Pool[{}], 流水显示买入份额 {}, 但业务端无记录！", key, shares)
        );

        log.info("========== 对账结束 ==========");
    }

    /**
     * 解析工具：从 "申购扣款... 份额：9088.57" 中提取 9088.57
     */
    private BigDecimal parseShares(String remark) {
        if (remark == null) return null;
        Matcher matcher = SHARE_PATTERN.matcher(remark);
        if (matcher.find()) {
            try {
                return new BigDecimal(matcher.group(1));
            } catch (Exception e) {
                log.warn("解析份额失败: {}", remark);
            }
        }
        return null;
    }
}
