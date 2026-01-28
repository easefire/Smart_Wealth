package com.smartwealth.user.service.impl;

import com.smartwealth.common.context.UserContext;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.user.dto.BankCardBindDTO;
import com.smartwealth.user.dto.UserRealNameDTO;
import com.smartwealth.user.dto.UserRiskAssessmentDTO;
import com.smartwealth.user.vo.BankCardVO;
import com.smartwealth.user.entity.BankCard;
import com.smartwealth.user.entity.UserBase;
import com.smartwealth.user.enums.UserStatusEnum;
import com.smartwealth.user.mapper.BankCardMapper;
import com.smartwealth.user.mapper.UserBaseMapper;
import com.smartwealth.user.service.IUserBaseService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;

/**
 * <p>
 * 用户基础信息表 服务实现类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Service
@Slf4j
public class UserBaseServiceImpl extends ServiceImpl<UserBaseMapper, UserBase> implements IUserBaseService {

    private static final byte[] AES_KEY = "SW_Secure_Key_16".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private UserBaseMapper baseMapper;
    @Autowired
    private BankCardMapper bankCardMapper;

    // 实名认证
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void realNameAuth(UserRealNameDTO dto) {
        Long userId = UserContext.getUserId(); // 获取当前登录人ID
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED);

        // 1. 状态检查：只有“已注册”状态才能进行实名认证
        UserBase user = baseMapper.selectById(userId);
        if (user == null) throw new BusinessException(ResultCode.USER_NOT_EXIST);
        if (user.getStatus() == UserStatusEnum.VERIFIED) { //
            throw new BusinessException(ResultCode.KYC_ALREADY_DONE);
        }
        if (user.getStatus() != UserStatusEnum.REGISTERED) { //
            throw new BusinessException(ResultCode.KYC_STATE_INVALID);
        }
        // 2. 唯一性检查：重点！检查身份证是否被他人占用
        if (baseMapper.existsByIdCardIgnoreSelf(dto.getIdCard(), userId)) {
            throw new BusinessException(ResultCode.KYC_FAIL);
        }
        // 3. 执行更新
        user.setRealName(dto.getRealName());
        user.setIdCard(dto.getIdCard());
        user.setStatus(UserStatusEnum.VERIFIED); // 变更状态为：已实名

        baseMapper.updateById(user);
    }

    // 风险测评
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer doRiskAssessment(UserRiskAssessmentDTO dto) {
        Long userId = UserContext.getUserId(); // 获取当前登录用户
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED);

        // 1. 状态校验：必须是已实名用户才能测评
        UserBase user = baseMapper.selectById(userId);
        if (user.getStatus() == UserStatusEnum.REGISTERED) {
            throw new BusinessException(ResultCode.USER_NOT_KYC);
        }

        // 2. 根据总分并判定等级
        int totalScore = dto.getScores();
        int riskLevel = calculateLevel(totalScore);

        // 3. 更新用户风险等级和状态
        user.setRiskLevel((byte) riskLevel);
        user.setStatus(UserStatusEnum.ACTIVE); // 状态流转：已实名 -> 已激活
        baseMapper.updateById(user);

        return riskLevel;
    }

    // 根据分数计算风险等级
    private int calculateLevel(int score) {
        if (score < 20) return 1; // 保守型
        if (score < 40) return 2; // 稳健型
        if (score < 60) return 3; // 平衡型
        if (score < 80) return 4; // 成长型
        return 5; // 进取型
    }

    // 绑定银行卡
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindBankCard(BankCardBindDTO dto) {
        // 1. 获取当前用户ID
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        // 2. 状态校验逻辑
        UserBase user = baseMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_EXIST);
        }
        if (user.getStatus() == UserStatusEnum.REGISTERED) {
            throw new BusinessException(ResultCode.USER_NOT_KYC);
        }
        if (user.getStatus() == UserStatusEnum.VERIFIED) {
            throw new BusinessException(ResultCode.RISK_EVAL_NEEDED);
        }

        // 3. Hutool 加密
        AES aes = SecureUtil.aes(AES_KEY);
        String encryptedCardNo = aes.encryptBase64(dto.getCardNo());
        // 4. 唯一性检查：同一用户不可绑定相同卡号
        boolean exists = new LambdaQueryChainWrapper<>(bankCardMapper)
                .eq(BankCard::getUserId, userId)
                .eq(BankCard::getCardNo, encryptedCardNo)
                .exists();

        if (exists) {
            throw new BusinessException(ResultCode.BANK_CARD_ALREADY_BINDED);
        }

        // 5. 数据转换与落地
        BankCard bankCard = new BankCard();
        BeanUtils.copyProperties(dto, bankCard);
        bankCard.setUserId(userId);
        bankCard.setCardNo(encryptedCardNo);

        // 6. 使用针对 BankCard 的更新包装器处理默认卡逻辑
        if (Integer.valueOf(1).equals(dto.getIsDefault())) {
            new LambdaUpdateChainWrapper<>(bankCardMapper)
                    .set(BankCard::getIsDefault, 0)
                    .eq(BankCard::getUserId, userId)
                    .update();
        }

        // 7. 手动调用注入的 bankCardMapper 进行插入
        bankCardMapper.insert(bankCard);
        user.setStatus(UserStatusEnum.ACTIVE);
        baseMapper.updateById(user);
        log.info("用户 {} 绑定银行卡 {} 成功", userId, dto.getBankName());
    }

    // 解绑银行卡
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeBankCard(Long cardId) {
        // 1. 获取当前登录用户 ID，确保安全
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        // 2. 校验卡片归属权
        // 严禁直接根据 cardId 删除，必须带上 userId 条件，防止越权删除他人的卡
        BankCard card = new LambdaQueryChainWrapper<>(bankCardMapper)
                .eq(BankCard::getId, cardId)
                .eq(BankCard::getUserId, userId)
                .one();

        if (card == null) {
            throw new BusinessException(ResultCode.BANK_CARD_ERROR);
        }


        // 4. 执行删除
        int rows = bankCardMapper.deleteById(cardId);
        if (rows <= 0) {
            throw new BusinessException(ResultCode.BANK_CARD_ERROR);
        }
        List<BankCard> list = new LambdaQueryChainWrapper<>(bankCardMapper)
                .eq(BankCard::getUserId, userId)
                .list(); //
        if (list.isEmpty()) {
            UserBase user = baseMapper.selectById(userId);
            user.setStatus(UserStatusEnum.TESTED);
            baseMapper.updateById(user);//
            log.info("用户 {} 已无绑定银行卡", userId);
        }


        // 5. 如果删掉的是默认卡，且用户还有其他卡，建议逻辑上提示用户重新设置
        if (Integer.valueOf(1).equals(card.getIsDefault())) {
            log.info("用户 {} 删除了默认银行卡，需重新设置默认卡", userId);
        }

        log.info("用户 {} 成功解绑银行卡: {}", userId, card.getBankName());
    }

    // 查询我的银行卡列表
    @Override
    public List<BankCardVO> queryMyBankCards() {
        Long userId = UserContext.getUserId(); //
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED);

        // 1. 查询该用户所有卡
        List<BankCard> list = new LambdaQueryChainWrapper<>(bankCardMapper)
                .eq(BankCard::getUserId, userId)
                .list(); //

        // 2. 初始化 Hutool AES
        AES aes = SecureUtil.aes(AES_KEY);

        return list.stream().map(card -> {
            BankCardVO vo = new BankCardVO();
            BeanUtils.copyProperties(card, vo);

            // 解密并脱敏
            String rawCardNo = aes.decryptStr(card.getCardNo()); //
            vo.setCardNo(StrUtil.hide(rawCardNo, 4, rawCardNo.length() - 4)); // 显示前4后4

            return vo;
        }).collect(Collectors.toList());
    }

}
