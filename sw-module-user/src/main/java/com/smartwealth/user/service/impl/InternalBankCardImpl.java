package com.smartwealth.user.service.impl;

import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.user.vo.BankCardVO;
import com.smartwealth.user.entity.BankCard;
import com.smartwealth.user.mapper.BankCardMapper;
import com.smartwealth.user.service.InternalBankCardService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 内部调用的银行卡服务实现类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Service
@Slf4j
public class InternalBankCardImpl implements InternalBankCardService {

    @Autowired
    BankCardMapper bankCardMapper;
    @Autowired
    private RedisService redisService;
    @Autowired
    private RedissonClient redissonClient;

    // 根据卡ID和用户ID获取银行卡信息
    @Override
    public BankCardVO getCardById(Long cardId, Long userId) {
        // 1. 定义缓存 Key
        String cacheKey = String.format(RedisKeyConstants.CARD_INFO, userId, cardId);
        String lockKey = String.format(RedisKeyConstants.CARD_LOCK, userId, cardId);

        // 2. 先从 Redis 拿数据
        BankCardVO cachedVo = redisService.get(cacheKey, BankCardVO.class);
        if (cachedVo != null) {
            log.info("银行卡查询命中缓存: {}", cacheKey);
            return cachedVo;
        }

        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 3. 尝试加锁。参数：等待时间，锁自动释放时间（-1代表开启看门狗），时间单位
            // 这里建议等待 3 秒，拿到锁后由看门狗管理时间
            if (lock.tryLock(3, -1, TimeUnit.SECONDS)) {
                // 二次检查缓存 (Double Check)
                cachedVo = redisService.get(cacheKey, BankCardVO.class);
                if (cachedVo != null || redisService.hasKey(cacheKey)) {
                    return cachedVo;
                }
                BankCard bankCard = bankCardMapper.selectByIdAndUserId(cardId, userId);
                if (bankCard != null) {
                    BankCardVO bankCardVO = new BankCardVO();
                    BeanUtils.copyProperties(bankCard, bankCardVO);

                    // 4. 回写缓存，并设置过期时间（防止冷数据永久占用内存）
                    // 建议设置 30 分钟到 1 小时随机过期，防止缓存雪崩
                    redisService.set(cacheKey, bankCardVO, 30 + new Random().nextInt(30), TimeUnit.MINUTES);

                    return bankCardVO;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return null;
    }
}
