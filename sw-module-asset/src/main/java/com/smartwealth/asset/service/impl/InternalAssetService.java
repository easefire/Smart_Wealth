package com.smartwealth.asset.service.impl;


import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartwealth.asset.dto.AssetFlowTradeDTO;
import com.smartwealth.asset.entity.AssetFlow;
import com.smartwealth.asset.entity.AssetLocalMsg;
import com.smartwealth.asset.entity.AssetWallet;
import com.smartwealth.asset.mapper.AssetFlowMapper;
import com.smartwealth.asset.mapper.AssetLocalMsgMapper;
import com.smartwealth.asset.mapper.AssetWalletMapper;
import com.smartwealth.asset.enums.TransactionTypeEnum;
import com.smartwealth.asset.mq.producer.AssetResultProducer;
import com.smartwealth.asset.service.IAssetWalletService;
import com.smartwealth.common.configuration.RabbitConfig;
import com.smartwealth.common.dto.PurchaseMessageDTO;
import com.smartwealth.common.dto.RedemptionMessageDTO;
import com.smartwealth.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 内部调用的资产服务
 * </p>
 *
 * @author Fire
 * @since 2026-01-12
 */
@Slf4j
@Service
public class InternalAssetService {

    @Autowired
    private IAssetWalletService assetWalletService;
    @Autowired
    private AssetWalletMapper walletMapper;
    @Autowired
    private AssetFlowMapper flowMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AssetLocalMsgService localMsgService;
    @Autowired
    private AssetLocalMsgMapper assetLocalMsgMapper;
    @Autowired
    private AssetResultProducer assetResultProducer;

    // 分页查询资金流水（内部调用）
    public IPage<AssetFlow> selectPage(Page<AssetFlow> page, LambdaQueryWrapper<AssetFlow> wrapper) {
        return flowMapper.selectPage(page, wrapper);
    }
    // 根据用户ID获取钱包信息
    public AssetWallet getWalletByUserId(Long userId) {
        return walletMapper.getWalletByUserId(userId);
    }
    // 获取整个平台的总钱包余额
    public BigDecimal getTotalWalletBalance() {
        BigDecimal totalBalance = walletMapper.getTotalWalletBalance();
        return totalBalance != null ? totalBalance : BigDecimal.ZERO;
    }
    // 悲观锁锁定用户钱包记录
    public void selectprelock(Long userId) {
        walletMapper.selectforupdate(userId);
    }

    // 检查该订单是否已经处理过 [cite: 2026-01-24]
    public boolean isProcessed(Long orderId,TransactionTypeEnum type) {
        // 根据 bizId（订单号）和类型查询流水，如果已存在则说明扣过款了
        return flowMapper.selectCount(new LambdaQueryWrapper<AssetFlow>()
                .eq(AssetFlow::getBizId, orderId)
                .eq(AssetFlow::getType, type)) > 0;
    }
    @Transactional(rollbackFor = Exception.class)
    public void deductForPurchase(PurchaseMessageDTO msg) {
        Long userId = msg.getUserId();
        BigDecimal amount = msg.getAmount();
        BigDecimal share = msg.getShare();
        Long orderId = msg.getOrderId();

        // 1. 【核心新增】幂等校验：绝对不允许对同一个 orderId 重复扣款 [cite: 2026-01-24]
        if (this.isProcessed(orderId, TransactionTypeEnum.PURCHASE)) {
            log.warn("订单 {} 已扣款成功，忽略重复请求", orderId);
            return;
        }

        try{
            // 2. 锁定钱包（保留原有的悲观锁，防止同一用户的其他动账操作干扰）
            AssetWallet wallet = assetWalletService.getOne(new LambdaQueryWrapper<AssetWallet>()
                    .eq(AssetWallet::getUserId, userId)
                    .last("FOR UPDATE"));

            if (wallet == null) throw new BusinessException("账户不存在");

            // 3. 校验密码（消息中携带的密码，在此进行最终核对）
            if (!passwordEncoder.matches(msg.getPayPassword(), wallet.getPayPassword())) {
                throw new BusinessException("支付密码错误");
            }

            // 4. 余额检查
            if (wallet.getBalance().compareTo(amount) < 0) {
                throw new BusinessException("余额不足");
            }

            // 5. 执行动账（乐观锁更新）
            int rows = walletMapper.update(null, new LambdaUpdateWrapper<AssetWallet>()
                    .eq(AssetWallet::getUserId, userId)
                    .eq(AssetWallet::getVersion, wallet.getVersion())
                    .set(AssetWallet::getBalance, wallet.getBalance().subtract(amount))
                    .set(AssetWallet::getVersion, wallet.getVersion() + 1));

            if (rows == 0) throw new BusinessException("并发动账冲突，请重试");

            // 6. 记录流水：bizId 必须存入 orderId 用于幂等校验 [cite: 2026-01-24]
            AssetFlow flow = new AssetFlow();
            flow.setFlowNo("PURC" + orderId); // 使用订单号生成流水号，进一步增强唯一性 [cite: 2026-01-24]
            flow.setUserId(userId);
            flow.setBizId(orderId); // 这里改为存储 Trade 模块的订单 ID
            flow.setAmount(amount.negate());
            flow.setType(TransactionTypeEnum.PURCHASE);
            flow.setBalanceSnapshot(wallet.getBalance().subtract(amount));
            flow.setRemark("申购扣款，订单号：" + orderId+" 份额："+share);
            flowMapper.insert(flow);

            AssetLocalMsg localMsg=saveResultLocalMsg(orderId,"MSG_PURC_RES_","SUCCESS", null);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    try {
                        // A. 发送 MQ
                        assetResultProducer.sendResult(orderId, "PURCHASE", true, "SUCCESS");
                        // B. 发送成功，更新本地消息状态为 1 (SUCCESS)
                        localMsgService.updateStatusSuccess(localMsg.getId());
                    } catch (Exception e) {
                        // ======================== 失败处理逻辑 START ========================
                        log.warn("业务扣款失败: {}", e.getMessage());

                        // A. 手动回滚当前事务 (撤销刚才可能做了一半的 update)
                        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

                        // B. 【独立事务】保存“失败”消息到本地表
                        // 必须用 REQUIRES_NEW，否则会被上面的 setRollbackOnly 带着一起回滚！
                        localMsgService.saveFailMsgInNewTx(orderId, "MSG_PURC_RES_", e.getMessage());

                        // C. 抛出异常，通知外层 Listener 这是个业务失败 (Listener 会捕获并 ACK)
                        throw e;
                        // ======================== 失败处理逻辑 END ========================
                    }
                }
            });
        }catch (BusinessException e){
            saveResultLocalMsg(orderId,"MSG_PURC_RES_","FAIL", e.getMessage());
            throw e;
        }

        // 7. 【关键新增】发送回执消息给 Trade 模块，通知扣款成功 [cite: 2026-01-24]
        // 这一步建议放在事务提交后，或者利用本地消息表发回执
    }
    private AssetLocalMsg saveResultLocalMsg(Long orderId,String type, String status,String reason) {
        AssetLocalMsg message = new AssetLocalMsg();
        message.setMsgId(type + orderId); // 直接关联订单号，方便排查
        message.setTopic(RabbitConfig.RESULT_EXCHANGE); // 对应回传的交换机

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("status", status); // SUCCESS 或 FAIL
        payload.put("reason", reason); // 失败原因，可选

        message.setContent(JSON.toJSONString(payload));
        message.setStatus(0); // 待发送
        message.setCreateTime(LocalDateTime.now());
        assetLocalMsgMapper.insert(message);

        return message;

    }

    // 赎回入账
    @Transactional(rollbackFor = Exception.class)
    public void processRedemption(RedemptionMessageDTO dto) {
        Long userId = dto.getUserId();
        BigDecimal amount = dto.getAmount();
        BigDecimal profit = dto.getProfit();
        BigDecimal share = dto.getShare();
        Long orderId = dto.getRequestId(); // 赎回记录ID

        // 1. 幂等校验
        // 注意：如果已处理，是否需要补发一次回执？(视业务严格程度而定，简单版直接 return)
        if (this.isProcessed(orderId, TransactionTypeEnum.REDEEM)) {
            log.warn("订单 {} 赎回已处理，跳过重复入账", orderId);
            return;
        }

        try {
            // 2. 锁定钱包
            AssetWallet wallet = assetWalletService.getOne(new LambdaQueryWrapper<AssetWallet>()
                    .eq(AssetWallet::getUserId, userId)
                    .last("FOR UPDATE"));
            if (wallet == null) throw new BusinessException("用户钱包不存在");

            // 3. 更新余额
            wallet.setBalance(wallet.getBalance().add(amount)); // 加钱
            wallet.setUpdateTime(LocalDateTime.now());
            walletMapper.updateById(wallet);

            // 4. 记录流水 (你的逻辑是对的，分为本金和收益)
            BigDecimal principal = amount.subtract(profit);

            // 4.1 本金流水
            if (principal.compareTo(BigDecimal.ZERO) > 0) {
                saveFlow(userId, dto.getProductId(), TransactionTypeEnum.REDEEM, principal,
                        wallet.getBalance().subtract(profit),
                        // 【重点】格式要配合正则对账：订单号：xxx 份额：xxx
                        "理财赎回-本金退回，订单号：" + orderId + " 份额：" + share);
            }

            // 4.2 收益流水
            if (profit.compareTo(BigDecimal.ZERO) != 0) {
                saveFlow(userId, dto.getProductId(), TransactionTypeEnum.INCOME, profit,
                        wallet.getBalance(),
                        // 【重点】备注格式保持一致，方便正则提取
                        "理财赎回-溢价收益，订单号：" + orderId + " 份额：0"); // 份额通常只记一次，或者按比例拆分，这里写0防止重复统计
            }

            // ======================== 新增核心逻辑 START ========================

            // 6. 保存本地消息 (状态=0)
            AssetLocalMsg localMsg = saveResultLocalMsg(orderId, "MSG_REED_RES", "SUCCESS", null);

            // 7. 注册钩子：事务提交后立马发
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    try {
                        // A. 发送 MQ 给 Trade 端
                        assetResultProducer.sendResult(orderId, "REDEEM", true, "SUCCESS");
                        // B. 更新本地消息状态为 1
                        localMsgService.updateStatusSuccess(localMsg.getId());
                    } catch (Exception e) {
                        log.error("赎回回执即时发送失败，等待 Job 兜底: {}", orderId, e);
                    }
                }
            });

            // ======================== 新增核心逻辑 END ========================

        } catch (BusinessException e) {
            // ======================== 失败处理 START ========================
            log.warn("赎回入账业务失败: {}", e.getMessage());

            // A. 手动回滚 (撤销刚才加的余额)
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

            // B. 独立事务保存失败消息
            localMsgService.saveFailMsgInNewTx(orderId, "MSG_REED_RES", e.getMessage());

            // C. 抛出异常让 Listener 感知并 ACK
            throw e;
            // ======================== 失败处理 END ========================
        }
    }
    // 保存资金流水的私有方法
    private void saveFlow(Long userId, Long bizId, TransactionTypeEnum type, BigDecimal amount, BigDecimal snapshot, String remark) {
        AssetFlow flow = new AssetFlow();
        flow.setFlowNo("REDE" + System.currentTimeMillis());
        flow.setUserId(userId);
        flow.setBizId(bizId); // 关联产品ID，消除 <null>
        flow.setAmount(amount);
        flow.setType(type);
        flow.setBalanceSnapshot(snapshot);
        flow.setRemark(remark);
        flow.setCreateTime(LocalDateTime.now()); // 记录收益发生的精确时间
        flowMapper.insert(flow);
    }

    public List<AssetFlowTradeDTO> selectPurchaseFlowsWithRemark() {
        return flowMapper.selectPurchaseFlowsWithRemark();
    }
}
