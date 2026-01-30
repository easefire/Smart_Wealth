package com.smartwealth.asset.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.smartwealth.asset.dto.RechargeDTO;
import com.smartwealth.asset.dto.WithdrawDTO;
import com.smartwealth.asset.entity.AssetLocalMsg;
import com.smartwealth.asset.mapper.AssetLocalMsgMapper;
import com.smartwealth.asset.vo.WalletVO;
import com.smartwealth.asset.entity.AssetFlow;
import com.smartwealth.asset.entity.AssetWallet;
import com.smartwealth.asset.enums.TransactionTypeEnum;
import com.smartwealth.asset.mapper.AssetFlowMapper;
import com.smartwealth.asset.mapper.AssetWalletMapper;
import com.smartwealth.asset.service.IAssetWalletService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.result.Result;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.user.vo.BankCardVO;
import com.smartwealth.user.event.AccountCancelEvent;
import com.smartwealth.user.service.InternalBankCardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 * 用户钱包总账表 服务实现类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Service
public class AssetWalletServiceImpl extends ServiceImpl<AssetWalletMapper, AssetWallet> implements IAssetWalletService {

    @Autowired
    private InternalBankCardService internalBankCardService; // 跨模块接口
    @Autowired
    private AssetFlowMapper assetFlowMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AssetLocalMsgMapper assetLocalMsgMapper;

    // 初始化用户钱包
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initWallet(Long userId) {
        // 1. 校验是否已存在
        if (baseMapper.selectById(userId) != null) {
            return;
        }

        // 2. 初始化对象
        AssetWallet wallet = new AssetWallet();
        wallet.setUserId(userId);
        wallet.setBalance(BigDecimal.ZERO);
        wallet.setFrozenAmount(BigDecimal.ZERO);
        wallet.setVersion(1); // 乐观锁初始值

        // 3. 插入数据库
        baseMapper.insert(wallet);
    }

    // 监听用户注销事件，校验钱包余额是否为0
    @EventListener // 默认同步执行
    public void handleAccountCancel(AccountCancelEvent event) {
        AssetWallet wallet = this.getById(event.getUserId());
        BigDecimal balance = wallet.getBalance();
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            // 直接抛出异常，阻断注销流程
            throw new BusinessException(ResultCode.WALLET_NOT_EMPTY);
        }
    }

    // 用户充值实现
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> recharge(Long userId, RechargeDTO dto) {
        // 1. 跨模块获取银行卡信息
        BankCardVO card = internalBankCardService.getCardById(dto.getBankCardId(), userId);
        if (card == null) {
            throw new BusinessException(ResultCode.BANK_CARD_ERROR);
        }

        // 2. 校验单笔限额
        if (dto.getAmount().compareTo(card.getLimitPerDay()) > 0) {
            throw new BusinessException(ResultCode.DEPOSIT_FAIL);
        }

        // 3. 校验今日累计额度（核心逻辑）
        // 计算公式：当日已充值总额 + 本次金额 <= 单日限额
        BigDecimal todaySum = assetFlowMapper.getTodayRechargeSum(userId, dto.getBankCardId());
        if (todaySum.add(dto.getAmount()).compareTo(card.getLimitPerDay()) > 0) {
            throw new BusinessException(ResultCode.DEPOSIT_FAIL);
        }

        // 4. 锁定钱包并更新金额（悲观锁，防止并发算错余额）
        System.out.println(userId);
        AssetWallet wallet = this.getOne(new LambdaQueryWrapper<AssetWallet>()
                .eq(AssetWallet::getUserId, userId)
                .last("FOR UPDATE"));


        if (wallet.getPayPassword() == null || wallet.getPayPassword().trim().isEmpty()) {
            // 返回特定的错误码，告知前端跳转到设置支付密码页面
            return Result.fail(ResultCode.PAY_PASSWORD_NOT_SET);
        }

        // 2. 修改余额
        BigDecimal newBalance = wallet.getBalance().add(dto.getAmount());
        wallet.setBalance(newBalance);
        wallet.setVersion(wallet.getVersion() + 1); // 乐观锁版本号+1
        wallet.setUpdateTime(LocalDateTime.now());

        int rows = this.baseMapper.updateById(wallet);
        if (rows == 0) {
            // 如果更新失败，说明在 selectForUpdate 之后到 update 之前，version 变了
            throw new BusinessException(ResultCode.FAILURE);
        }

        // 5. 插入流水记录
        AssetFlow flow = new AssetFlow();
        flow.setFlowNo("RECH" + IdWorker.getIdStr()); // 生成唯一流水号
        flow.setUserId(userId);
        flow.setBizId(dto.getBankCardId()); // 充值业务，bizId 存银行卡 ID
        flow.setAmount(dto.getAmount());
        flow.setType(TransactionTypeEnum.RECHARGE); // RECHARGE 类型
        flow.setBalanceSnapshot(newBalance); // 变动后余额快照
        flow.setRemark("银行卡充值-" + card.getBankName());

        assetFlowMapper.insert(flow);

        AssetLocalMsg message = new AssetLocalMsg();

        // 生成全局唯一 ID
        message.setMsgId("MSG_RECH_" + IdWorker.getIdStr());

        // 定义 Topic，方便异步任务根据类型处理
        message.setTopic("ASSET_RECHARGE_SUCCESS");

        // 封装消息载荷：包含用户ID、充值金额和流水号
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("amount", dto.getAmount());
        payload.put("flowNo", flow.getFlowNo());
        message.setContent(JSON.toJSONString(payload));

        // 设置初始状态和重试参数
        message.setStatus(1);
        message.setRetryCount(0);

        // 存入数据库
        assetLocalMsgMapper.insert(message);

        return Result.success(flow.getFlowNo());
    }



    // 用户提现实现
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> withdraw(Long userId, WithdrawDTO dto) {
        // 1. 悲观锁锁定钱包行，确保动账期间数据不被其他事务修改
        AssetWallet wallet = this.getOne(new LambdaQueryWrapper<AssetWallet>()
                .eq(AssetWallet::getUserId, userId)
                .last("FOR UPDATE"));
        if (wallet == null) {
            throw new BusinessException(ResultCode.WALLET_NOT_EXIST);
        }

        // 2. 支付密码强制校验
        if (StringUtils.isBlank(wallet.getPayPassword())) {
            return Result.fail(ResultCode.PAY_PASSWORD_NOT_SET);
        }
        if (!passwordEncoder.matches(dto.getPayPassword(), wallet.getPayPassword())) {
            return Result.fail(ResultCode.PAYMENT_PASSWORD_ERROR);
        }

        // 3. 余额充足校验
        if (wallet.getBalance().compareTo(dto.getAmount()) < 0) {
            return Result.fail(ResultCode.BALANCE_NOT_ENOUGH);
        }

        // 4. 银行卡状态校验 (跨模块调用)
        BankCardVO card = internalBankCardService.getCardById(dto.getBankCardId(), userId);
        if (card == null) {
            return Result.fail(ResultCode.BANK_CARD_ERROR);
        }

        // 5. 执行动账：乐观锁更新余额
        Integer currentVersion = wallet.getVersion();
        BigDecimal newBalance = wallet.getBalance().subtract(dto.getAmount());

        boolean success = this.update(new LambdaUpdateWrapper<AssetWallet>()
                .eq(AssetWallet::getUserId, userId)
                .eq(AssetWallet::getVersion, currentVersion) // WHERE version = 1
                .set(AssetWallet::getBalance, newBalance)
                .set(AssetWallet::getVersion, currentVersion + 1) // SET version = 2
                .set(AssetWallet::getUpdateTime, LocalDateTime.now()));

        if (!success) {
            throw new BusinessException(ResultCode.FAILURE);
        }

        // 6. 记录资金流水
        AssetFlow flow = new AssetFlow();
        flow.setFlowNo("WITH" + IdWorker.getIdStr());
        flow.setUserId(userId);
        flow.setBizId(dto.getBankCardId()); // 提现业务，bizId 存目标银行卡 ID
        flow.setAmount(dto.getAmount().negate()); // 提现是支出，流水记为负数
        flow.setType(TransactionTypeEnum.WITHDRAW);
        flow.setBalanceSnapshot(newBalance);
        flow.setRemark("提现至银行卡-" + card.getBankName());

        assetFlowMapper.insert(flow);

        // 逻辑：只要事务提交，消息就落地，保证了“余额扣减”和“发起转账指令”的原子性
        AssetLocalMsg message = new AssetLocalMsg();

        // 生成全局唯一 ID，前缀区分业务
        message.setMsgId("MSG_WITH_" + IdWorker.getIdStr());

        // 提现主题，未来由 XXL-JOB 扫描后通过 MQ 发出或直接调用 Mock 的银行接口
        message.setTopic("ASSET_WITHDRAW_PROCESS");

        // 封装消息载荷：核心包含流水号、用户ID、金额、目标卡号
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("amount", dto.getAmount());
        payload.put("flowNo", flow.getFlowNo());
        payload.put("bankCardId", dto.getBankCardId());
        message.setContent(JSON.toJSONString(payload));

        // 设置初始状态
        message.setStatus(1); // 0-待处理
        message.setRetryCount(0);

        // 存入资产模块本地消息表
        assetLocalMsgMapper.insert(message);

        return Result.success(flow.getFlowNo()+"提现申请已提交，请等待银行处理");
    }

    // 查询钱包余额信息
    @Override
    public WalletVO getBalanceInfo(Long userId) {
        AssetWallet wallet = this.getById(userId);
        if (wallet == null) {
            throw new BusinessException("钱包账户异常");
        }

        WalletVO vo = new WalletVO();
        vo.setTotalAmount(wallet.getBalance().setScale(4, RoundingMode.HALF_UP));
        vo.setBalance(wallet.getBalance().subtract(wallet.getFrozenAmount())
                .setScale(4, RoundingMode.HALF_UP));
        vo.setFrozenAmount(wallet.getFrozenAmount());
        vo.setHasPayPassword(wallet.getPayPassword() != null && !wallet.getPayPassword().trim().isEmpty());
        vo.setUpdateTime(wallet.getUpdateTime());
        return vo;
    }

    // 设置支付密码
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setPayPassword(Long userId, String password) {
        // 建议使用 BCrypt 等算法进行加密存储，不要存明文
        String encryptedPwd = passwordEncoder.encode(password);
        this.update(new LambdaUpdateWrapper<AssetWallet>()
                .eq(AssetWallet::getUserId, userId)
                .set(AssetWallet::getPayPassword, passwordEncoder.encode(password)));
    }
}

