package com.smartwealth.asset.job;

import com.smartwealth.asset.dto.AssetCheckDTO;
import com.smartwealth.asset.mapper.AssetWalletMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class AssetCheckJobHandler {

    @Autowired
    private AssetWalletMapper assetWalletMapper;

    /**
     * 每日资产审计任务
     * 建议时间：每天凌晨 03:00 (避开交易高峰)
     */
    @XxlJob("dailyAssetCheckJob")
    public void dailyAssetCheckJob() {
        log.info("========== 开始执行每日资产体检 ==========");

        // 1. 直接查数据库，把有问题的用户拉出来
        // 如果大家都没问题，这个 list 就是空的
        List<AssetCheckDTO> errorList = assetWalletMapper.checkAssetSnapshotMismatch();

        // 2. 判断结果
        if (errorList.isEmpty()) {
            log.info("✅ 资产核对完美通过，无异常。");
            return; // 直接结束
        }

        // 3. 有问题？遍历打印 WARN 日志
        // 只要日志打出来了，你在 ELK 或者日志文件里 grep "账目不平" 就能看到
        for (AssetCheckDTO error : errorList) {
            log.warn("⚠️【账目不平警告】User ID: [{}], 钱包余额: [{}], 流水快照: [{}]",
                    error.getUserId(),
                    error.getWalletBalance(),
                    error.getSnapshotBalance());
        }

        log.info("========== 资产体检结束，发现 {} 个异常账户 ==========", errorList.size());
    }
}
