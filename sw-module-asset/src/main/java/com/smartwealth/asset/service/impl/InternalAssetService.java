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
import org.springframework.dao.DuplicateKeyException;
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
    // 检查该订单是否已经处理过
    public boolean isProcessed(Long orderId, TransactionTypeEnum type) {
        // 1. 根据类型构造出当时存进去的 flow_no
        String targetFlowNo;
        if (type == TransactionTypeEnum.PURCHASE) {
            targetFlowNo = "PURC" + orderId;
        } else if (type == TransactionTypeEnum.REDEEM) {
            targetFlowNo = "REDE" + orderId; // 这里 orderId 其实传的是 requestId
        } else {
            return false;
        }

        // 2. 直接查 flow_no 是否存在
        // 因为 flow_no 是唯一的，只要查到了，说明这笔单子肯定处理过了
        return flowMapper.selectCount(new LambdaQueryWrapper<AssetFlow>()
                .eq(AssetFlow::getFlowNo, targetFlowNo)) > 0;
    }
    @Transactional(rollbackFor = Exception.class)
    public void deductForPurchase(PurchaseMessageDTO msg) {
        Long userId = msg.getUserId();
        BigDecimal amount = msg.getAmount();
        BigDecimal share = msg.getShare();
        Long orderId = msg.getOrderId();
        Long productId = msg.getProductId();
        // 1. 【核心新增】幂等校验：绝对不允许对同一个 orderId 重复扣款 [cite: 2026-01-24]
        if (this.isProcessed(orderId, TransactionTypeEnum.PURCHASE)) {
            log.warn("订单 {} 已扣款成功，忽略重复请求", orderId);
            return;
        }
        try{
            AssetWallet walletCheck = assetWalletService.getOne(new LambdaQueryWrapper<AssetWallet>()
                    .eq(AssetWallet::getUserId, userId));
            if (!passwordEncoder.matches(msg.getPayPassword(), walletCheck.getPayPassword())) {
                throw new BusinessException("支付密码错误");
            }

            // 2. 锁定钱包（保留原有的悲观锁，防止同一用户的其他动账操作干扰）
            AssetWallet wallet = assetWalletService.getOne(new LambdaQueryWrapper<AssetWallet>()
                    .eq(AssetWallet::getUserId, userId)
                    .last("FOR UPDATE"));
            if (wallet == null) throw new BusinessException("账户不存在");

            // 4. 余额检查
            if (wallet.getBalance().compareTo(amount) < 0) {
                throw new BusinessException("余额不足");
            }
            // 5. 执行动账
            int rows = walletMapper.update(null, new LambdaUpdateWrapper<AssetWallet>()
                    .eq(AssetWallet::getUserId, userId)
                    .eq(AssetWallet::getVersion, wallet.getVersion())
                    .set(AssetWallet::getBalance, wallet.getBalance().subtract(amount))
                    .set(AssetWallet::getVersion, wallet.getVersion() + 1));

            if (rows == 0) throw new BusinessException("并发动账冲突，请重试");
            // 6. 记录流水：bizId 必须存入 orderId 用于幂等校验
            AssetFlow flow = new AssetFlow();
            flow.setFlowNo("PURC" + orderId); // 使用订单号生成流水号，进一步增强唯一性
            flow.setUserId(userId);
            flow.setBizId(productId);
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
                        // B. 发送成功，更新本地消息状态为 1
                        localMsgService.updateStatusSuccess(localMsg.getId());
                    } catch (Exception e) {
                        // 【修改点 1】删除了 setRollbackOnly()。因为事务已提交，调用它毫无意义且容易引起误解。
                        log.error("【告警】动账已成功，但回传MQ结果失败。订单号: {}。将由定时任务补偿发送。", orderId, e);
                        try {
                            // 【修改点 2】使用独立事务 (REQUIRES_NEW) 更新这条本地消息的状态为 2 (发送失败)
                            // 并记录失败原因，方便排查。如果保持 status=0 也可以，看你的业务定义。
                            localMsgService.saveFailMsgInNewTx(orderId, "MSG_PURC_RES_", e.getMessage());
                        } catch (Exception ex) {
                            // 连写库都失败了，只能打日志，依靠定时任务扫描 status=0 的记录
                            log.error("【严重告警】更新本地消息失败，消息ID: {}", localMsg.getId(), ex);
                        }
                    }
                }
            });
        }catch (BusinessException e){
            saveResultLocalMsg(orderId,"MSG_PURC_RES_","FAIL", e.getMessage());
            throw e;
        }
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
        Long requestId = dto.getRequestId(); // 赎回记录ID

        if (this.isProcessed(requestId, TransactionTypeEnum.REDEEM)) {
            log.warn("订单 {} 已扣款成功，忽略重复请求", requestId);
            return;
        }


        try {
            // 2. 锁定钱包
            AssetWallet wallet = assetWalletService.getOne(new LambdaQueryWrapper<AssetWallet>()
                    .eq(AssetWallet::getUserId, userId)
                    .last("FOR UPDATE"));
            if (wallet == null) throw new BusinessException("用户钱包不存在");

            // 3. 乐观锁更新余额
            walletMapper.update(null, new LambdaUpdateWrapper<AssetWallet>()
                    .eq(AssetWallet::getUserId, userId)
                    .eq(AssetWallet::getVersion, wallet.getVersion())
                    .set(AssetWallet::getBalance, wallet.getBalance().add(amount))
                    .set(AssetWallet::getVersion, wallet.getVersion() + 1));


            // 4. 记录流水 (你的逻辑是对的，分为本金和收益)
            BigDecimal principal = amount.subtract(profit);

            // 4.1 本金流水
            if (principal.compareTo(BigDecimal.ZERO) > 0) {
                saveFlow(userId,requestId, dto.getProductId(), TransactionTypeEnum.REDEEM, principal,
                        wallet.getBalance().subtract(profit),
                        "理财赎回-本金退回，订单号：" + requestId + " 份额：" + share);
            }

            // 4.2 收益流水
            if (profit.compareTo(BigDecimal.ZERO) != 0) {
                saveFlow(userId,requestId, dto.getProductId(), TransactionTypeEnum.INCOME, profit,
                        wallet.getBalance(),
                        "理财赎回-溢价收益，订单号：" + requestId + " 份额：0"); // 份额通常只记一次，或者按比例拆分，这里写0防止重复统计
            }

            // 6. 保存本地消息 (状态=0)
            AssetLocalMsg localMsg = saveResultLocalMsg(requestId, "MSG_REED_RES", "SUCCESS", null);

            // 7. 注册钩子：事务提交后立马发
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    try {
                        // A. 发送 MQ 给 Trade 端
                        assetResultProducer.sendResult(requestId, "REDEEM", true, "SUCCESS");
                        // B. 更新本地消息状态为 1
                        localMsgService.updateStatusSuccess(localMsg.getId());
                    } catch (Exception e) {
                        log.error("【告警】动账已成功，但回传MQ结果失败。订单号: {}。将由定时任务补偿发送。", requestId, e);
                        try {
                            localMsgService.saveFailMsgInNewTx(requestId, "MSG_REED_RES_", e.getMessage());
                        } catch (Exception ex) {
                            // 连写库都失败了，只能打日志，依靠定时任务扫描 status=0 的记录
                            log.error("【严重告警】更新本地消息失败，消息ID: {}", localMsg.getId(), ex);
                        }
                    }
                }
            });

        } catch (BusinessException e) {
            // ======================== 失败处理 START ========================
            log.warn("赎回入账业务失败: {}", e.getMessage());

            // A. 手动回滚 (撤销刚才加的余额)
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

            // B. 独立事务保存失败消息
            localMsgService.saveFailMsgInNewTx(requestId, "MSG_REED_RES", e.getMessage());

            // C. 抛出异常让 Listener 感知并 ACK
            throw e;
            // ======================== 失败处理 END ========================
        }
    }
    // 保存资金流水的私有方法
    private void saveFlow(Long userId,Long requestId, Long bizId, TransactionTypeEnum type, BigDecimal amount, BigDecimal snapshot, String remark) {
        try {
            // 尝试插入流水
            AssetFlow flow = new AssetFlow();
            flow.setFlowNo("REDE" + requestId); // 使用赎回记录ID生成流水号
            flow.setUserId(userId);
            flow.setBizId(bizId); // 关联产品ID，消除 <null>
            flow.setAmount(amount);
            flow.setType(type);
            flow.setBalanceSnapshot(snapshot);
            flow.setRemark(remark);
            flowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            // 捕获唯一键冲突异常
            // 💡 关键点：如果是流水号重复，说明这条消息之前已经成功消费过了
            log.warn("⚠️ 幂等性保护：检测到重复流水号 {}, 视为消费成功。忽略本次插入。",requestId);

            // 这里必须吞掉异常，不要抛出！
            // 如果抛出异常，RabbitMQ 会以为消费失败，无限重试，导致死循环报错。
        }
    }

    public List<AssetFlowTradeDTO> selectPurchaseFlowsWithRemark() {
        return flowMapper.selectPurchaseFlowsWithRemark();
    }
}
