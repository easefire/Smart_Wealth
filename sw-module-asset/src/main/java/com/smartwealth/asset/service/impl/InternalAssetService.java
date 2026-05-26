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
import com.smartwealth.api.InternalAssetApi;
import com.smartwealth.common.configuration.RabbitConfig;
import com.smartwealth.common.dto.PurchaseMessageDTO;
import com.smartwealth.common.dto.RedemptionMessageDTO;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
// 【MIGRATION】TransactionSynchronizationAdapter 已被 Spring 标记为 deprecated（Spring 5.3 起），
//             Spring 6 / Boot 3.x 已删除该类，启动会报 ClassNotFoundException。
//             TransactionSynchronization 接口的所有方法都自带 default，无需 Adapter 兜底，直接实现即可。
import org.springframework.transaction.support.TransactionSynchronization;
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
public class InternalAssetService implements InternalAssetApi {

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
    @Override
    public BigDecimal getTotalWalletBalance() {
        BigDecimal totalBalance = walletMapper.getTotalWalletBalance();
        return totalBalance != null ? totalBalance : BigDecimal.ZERO;
    }
    // 悲观锁锁定用户钱包记录
    @Override
    public void selectprelock(Long userId) {
        walletMapper.selectforupdate(userId);
    }

    /**
     * 【SECURITY】校验用户支付密码（赎回/支付类强敏感操作前置）。
     *
     * <p>语义严格区分三种失败：
     * <ul>
     *   <li>钱包不存在 → {@link ResultCode#WALLET_NOT_EXIST}</li>
     *   <li>未设置支付密码 → {@link ResultCode#PAY_PASSWORD_NOT_SET}</li>
     *   <li>密码错误 → {@link ResultCode#PAYMENT_PASSWORD_ERROR}</li>
     * </ul>
     * 通过抛 {@link BusinessException} 上抛具体码，由调用方统一捕获翻译为前端 Result。
     *
     * <p>（旧返回 boolean 的版本会把 3 种情况混在 false 里，
     *      让用户在"还没设过密码"的场景下被引导去试错密码，体验/安全双输。）
     */
    @Override
    public void verifyPayPassword(Long userId, String rawPassword) {
        if (userId == null || rawPassword == null || rawPassword.isEmpty()) {
            throw new BusinessException(ResultCode.PAYMENT_PASSWORD_ERROR);
        }
        AssetWallet wallet = walletMapper.getWalletByUserId(userId);
        if (wallet == null) {
            throw new BusinessException(ResultCode.WALLET_NOT_EXIST);
        }
        if (wallet.getPayPassword() == null || wallet.getPayPassword().isEmpty()) {
            throw new BusinessException(ResultCode.PAY_PASSWORD_NOT_SET);
        }
        if (!passwordEncoder.matches(rawPassword, wallet.getPayPassword())) {
            throw new BusinessException(ResultCode.PAYMENT_PASSWORD_ERROR);
        }
    }
    // 检查该订单/请求是否已经处理过
    // 【与 #30 联动修复】
    //   - PURCHASE 仍用 "PURC" + orderId 唯一定位
    //   - REDEEM 因本金 + 收益拆为两条流水（REDE-P-* / REDE-I-*），
    //     这里以“本金流水”作为幂等关键流水（principal>0 时必然存在）。
    public boolean isProcessed(Long orderId, TransactionTypeEnum type) {
        String targetFlowNo;
        if (type == TransactionTypeEnum.PURCHASE) {
            targetFlowNo = "PURC" + orderId;
        } else if (type == TransactionTypeEnum.REDEEM) {
            targetFlowNo = "REDE-P-" + orderId; // orderId 此处实际是 requestId
        } else {
            return false;
        }

        return flowMapper.selectCount(new LambdaQueryWrapper<AssetFlow>()
                .eq(AssetFlow::getFlowNo, targetFlowNo)) > 0;
    }
    @Override
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

            AssetLocalMsg localMsg = saveResultLocalMsg(orderId, "MSG_PURC_RES_", "SUCCESS", null);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        // 【BUGFIX-#13】
                        //  ① 把 localMsg.msgId 透传到 sendResult，作为 CorrelationData.id，
                        //     让 ConfirmCallback 能精确定位本地消息行。
                        //  ② 不再在这里立刻调 updateStatusSuccess——
                        //     只有 broker ack=true 时（ConfirmCallback）才置 status=1。
                        //     这是“可靠投递”的关键，避免“发了一半失败但本地表却显示成功”。
                        assetResultProducer.sendResult(localMsg.getMsgId(), orderId, "PURCHASE", true, "SUCCESS");
                    } catch (Exception e) {
                        log.error("【告警】动账已成功，但向 MQ 投递失败。订单号: {}。将由定时任务补偿发送。", orderId, e);
                        // status 仍保持 0，AssetLocalMsgJobHandler 会扫到并重发，无需在此修改 DB。
                    }
                }
            });
        }catch (BusinessException e){
            // 【BUGFIX】之前在主事务里调 saveResultLocalMsg() + throw，
            // 异常向上抛会触发 Spring 回滚整个事务，导致 FAIL 消息一并被回滚，
            // 交易端永远收不到失败回执 → 订单卡在 PENDING，库存泄漏。
            //
            // 正确做法：
            // 1) 显式标记主事务回滚（钱不能动）
            // 2) 用 REQUIRES_NEW 的独立事务把 FAIL 消息落盘
            // 3) 再抛出异常给 Listener，让它 ack 消息，避免 MQ 无限重试
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (Exception ignored) {
                // 兜底：极端情况下事务上下文已无，不影响后续 FAIL 消息持久化
            }
            try {
                localMsgService.saveFailMsgInNewTx(orderId, "MSG_PURC_RES_", e.getMessage());
            } catch (Exception ex) {
                log.error("【严重告警】申购失败消息落库失败！orderId={}, 原因={}", orderId, ex.getMessage(), ex);
            }
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
    @Override
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
            // 【BUGFIX】之前未接收影响行数，并发场景下若 update 命中 0 行，
            // 会出现“余额没加成功，但下面流水照样写”的脏账。这里显式校验。
            int rows = walletMapper.update(null, new LambdaUpdateWrapper<AssetWallet>()
                    .eq(AssetWallet::getUserId, userId)
                    .eq(AssetWallet::getVersion, wallet.getVersion())
                    .set(AssetWallet::getBalance, wallet.getBalance().add(amount))
                    .set(AssetWallet::getVersion, wallet.getVersion() + 1));
            if (rows == 0) {
                throw new BusinessException("并发动账冲突，赎回入账失败");
            }


            // 4. 记录流水 (本金 + 收益 拆两条)
            // 【BUGFIX-#30】之前两条流水的 flowNo 都是 "REDE"+requestId 完全相同，
            //              t_asset_flow.flow_no 唯一索引会让第二条被 DuplicateKey 静默吞掉，
            //              这里改成业务后缀区分（P=Principal, I=Income）。
            // 【BUGFIX-#5】 balanceSnapshot 必须是“本条流水落账之后的余额”：
            //              - 入账顺序：先本金，后收益
            //              - 本金后快照 = oldBalance + principal
            //              - 收益后快照 = oldBalance + amount  (= +principal +profit)
            //              之前写成 (oldBalance - profit) 和 (oldBalance) 完全是错的，
            //              会让对账任务 checkAssetSnapshotMismatch 大量误报。
            BigDecimal oldBalance = wallet.getBalance();
            BigDecimal principal = amount.subtract(profit);
            BigDecimal afterPrincipal = oldBalance.add(principal);
            BigDecimal afterAll = oldBalance.add(amount);

            // 4.1 本金流水
            if (principal.compareTo(BigDecimal.ZERO) > 0) {
                saveFlow(userId, "REDE-P-" + requestId, dto.getProductId(),
                        TransactionTypeEnum.REDEEM, principal,
                        afterPrincipal,
                        "理财赎回-本金退回，订单号：" + requestId + " 份额：" + share);
            }

            // 4.2 收益流水
            if (profit.compareTo(BigDecimal.ZERO) != 0) {
                saveFlow(userId, "REDE-I-" + requestId, dto.getProductId(),
                        TransactionTypeEnum.INCOME, profit,
                        afterAll,
                        "理财赎回-溢价收益，订单号：" + requestId + " 份额：0"); // 份额通常只记一次，或者按比例拆分，这里写0防止重复统计
            }

            // 6. 保存本地消息 (状态=0)
            // 【BUGFIX】统一赎回回执前缀为 "MSG_REED_RES_"（带下划线），与申购 "MSG_PURC_RES_" 对齐；
            //          成功 / 失败共享同一前缀，仅通过 payload.status 区分业务结果，
            //          避免 AssetResultProducer 兼容 overload 因 success 分支产生不同 msgId
            //          而和本地消息表持久化的 msgId 错配，导致 ConfirmCallback 无法精准更新。
            AssetLocalMsg localMsg = saveResultLocalMsg(requestId, "MSG_REED_RES_", "SUCCESS", null);

            // 7. 注册钩子：事务提交后立马发
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        // 【BUGFIX-#13】用 localMsg.msgId 作为 CorrelationData，
                        //              依赖 ConfirmCallback 在 broker ack 后再置 status=1；
                        //              发送异常或未 ack 时由 AssetLocalMsgJobHandler 兜底重试。
                        assetResultProducer.sendResult(localMsg.getMsgId(), requestId, "REDEEM", true, "SUCCESS");
                    } catch (Exception e) {
                        log.error("【告警】动账已成功，但向 MQ 投递失败。订单号: {}。将由定时任务补偿发送。", requestId, e);
                    }
                }
            });

        } catch (BusinessException e) {
            // ======================== 失败处理 START ========================
            log.warn("赎回入账业务失败: {}", e.getMessage());

            // A. 手动回滚 (撤销刚才加的余额)
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

            // B. 独立事务保存失败消息
            //    【BUGFIX】与上方 saveResultLocalMsg 保持完全一致的 "MSG_REED_RES_" 前缀，
            //              确保 retry/ConfirmCallback 用同一个 msgId 匹配本地消息行。
            localMsgService.saveFailMsgInNewTx(requestId, "MSG_REED_RES_", e.getMessage());

            // C. 抛出异常让 Listener 感知并 ACK
            throw e;
            // ======================== 失败处理 END ========================
        }
    }
    // 保存资金流水的私有方法
    // 【BUGFIX】之前签名是 (userId, requestId, ...) 并在内部固定拼 "REDE"+requestId，
    //          导致同一个赎回的本金/收益两条流水撞键，收益条被 DuplicateKey 静默丢失。
    //          现在直接接收外部生成好的 flowNo，由调用方保证唯一性。
    private void saveFlow(Long userId, String flowNo, Long bizId, TransactionTypeEnum type,
                          BigDecimal amount, BigDecimal snapshot, String remark) {
        try {
            AssetFlow flow = new AssetFlow();
            flow.setFlowNo(flowNo);
            flow.setUserId(userId);
            flow.setBizId(bizId);
            flow.setAmount(amount);
            flow.setType(type);
            flow.setBalanceSnapshot(snapshot);
            flow.setRemark(remark);
            flowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            // 流水号重复：说明本条已成功消费过，按幂等吞掉，避免 RabbitMQ 无限重试。
            log.warn("⚠️ 幂等性保护：检测到重复流水号 {}, 视为消费成功。忽略本次插入。", flowNo);
        }
    }

    public List<AssetFlowTradeDTO> selectPurchaseFlowsWithRemark() {
        return flowMapper.selectPurchaseFlowsWithRemark();
    }
}
