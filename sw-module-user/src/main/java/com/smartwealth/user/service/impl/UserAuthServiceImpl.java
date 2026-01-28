package com.smartwealth.user.service.impl;

import com.smartwealth.common.context.UserContext;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.user.event.UserRegisteredEvent;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.common.util.JwtUtils;
import com.smartwealth.user.dto.UserLoginDTO;
import com.smartwealth.user.dto.UserRegisterDTO;
import com.smartwealth.user.dto.UserUpdateDTO;
import com.smartwealth.user.vo.UserVO;
import com.smartwealth.user.entity.UserAuth;
import com.smartwealth.user.entity.UserBase;
import com.smartwealth.user.enums.UserStatusEnum;
import com.smartwealth.user.event.AccountCancelEvent;
import com.smartwealth.user.mapper.BankCardMapper;
import com.smartwealth.user.mapper.UserAuthMapper;
import com.smartwealth.user.mapper.UserBaseMapper;
import com.smartwealth.user.service.IUserAuthService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 用户认证敏感表 服务实现类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Service
public class UserAuthServiceImpl extends ServiceImpl<UserAuthMapper, UserAuth> implements IUserAuthService {

    @Autowired
    private UserAuthMapper userMapper;
    @Autowired
    private UserBaseMapper userBaseMapper;
    @Autowired
    BankCardMapper bankCardMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private RedisService redisService;

    // 用户注册
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(UserRegisterDTO dto) {
        // 1. 唯一性校验 (DB 层面也要有唯一索引)
        if (userMapper.existsByUsername(dto.getUsername())) {
            throw new BusinessException(ResultCode.USERNAME_ALREADY_EXIST);
        }
        if (userMapper.existsByPhone(dto.getPhone())) {
            throw new BusinessException(ResultCode.PHONE_ALREADY_EXIST);
        }

        // 2. DTO 转 Entity
        UserBase user = new UserBase();
        user.setUsername(dto.getUsername());
        user.setPhone(dto.getPhone());
        user.setStatus(UserStatusEnum.REGISTERED);
        user.setRiskLevel((byte) 0);
        userBaseMapper.insert(user);

        UserAuth auth = new UserAuth();
        auth.setUserId(user.getId());
        // 3. 密码加密存储
        auth.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        userMapper.insert(auth);
        eventPublisher.publishEvent(new UserRegisteredEvent(user.getId()));


        // 6. 返回 VO (屏蔽敏感字段)
        return UserVO.fromEntity(user);
    }

    // 用户登录
    @Override
    public String login(UserLoginDTO dto) {
        // 1. 根据用户名查询 UserAuth（包含加密后的密码和状态）
        UserBase user = userMapper.selectByUsername(dto.getUsername());
        if (user == null) {
            throw new BusinessException(ResultCode.FAILURE); // 处于安全考虑，建议提示模糊化
        }
        UserAuth auth = userMapper.selectById(user.getId());
        // 2. 检查账户状态（是否被冻结）
        if (user.getStatus() == UserStatusEnum.FROZEN) {
            throw new BusinessException(ResultCode.USER_FROZEN);
        } else if (user.getStatus() == UserStatusEnum.DELETED) {
            throw new BusinessException(ResultCode.USER_DELETED);
        }
        // 3. 核心：校验密码
        // matches(明文, 密文) -> 它会自动从密文提取盐值并进行哈希比对
        if (!passwordEncoder.matches(dto.getPassword(), auth.getPasswordHash())) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }
        String token = JwtUtils.createToken(user.getId(), "USER");
        String redisKey = String.format(RedisKeyConstants.USER_TOKEN, user.getId());
        redisService.set(redisKey, token, 30, TimeUnit.MINUTES);

        // 4. 登录成功，生成 JWT Token（下一步我们需要实现的）
        return token;
    }

    // 用户登出
    @Override
    public void logout(Long userId) {
        // 构造 Redis Key
        String key = String.format(RedisKeyConstants.USER_TOKEN, userId);
        // 核心：从 Redis 中删除，实现“主动失效”
        redisService.delete(key);
        // 清理当前线程的上下文，防止脏数据
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    // 更新用户信息
    @Override
    @Transactional(rollbackFor = Exception.class) // 必须开启事务
    public void updateUserInfo(UserUpdateDTO dto) {
        Long userId = UserContext.getUserId(); // 从上下文获取当前用户 ID

        // 1. 修改用户名和手机号 (操作 t_user_base)
        UserBase base = userBaseMapper.selectById(userId);
        if (StringUtils.hasText(dto.getUsername())) {
            // 唯一性检查：排除自己后，看别人是否占用了这个名字
            if (userBaseMapper.existsByUsernameIgnoreSelf(dto.getUsername(), userId)) {
                throw new BusinessException("该用户名已被占用");
            }
            base.setUsername(dto.getUsername());
        }
        if (StringUtils.hasText(dto.getPhone())) {
            if (userBaseMapper.existsByPhoneIgnoreSelf(dto.getPhone(), userId)) {
                throw new BusinessException("该手机号已被绑定");
            }
            base.setPhone(dto.getPhone());
        }
        userBaseMapper.updateById(base);

        // 2. 修改密码 (操作 t_user_auth)
        if (StringUtils.hasText(dto.getNewPassword())) {
            UserAuth authuser = userMapper.selectById(userId);
            // 必须校验旧密码
            if (!passwordEncoder.matches(dto.getOldPassword(), authuser.getPasswordHash())) {
                throw new BusinessException("旧密码错误");
            }
            // 加密存储新密码
            authuser.setPasswordHash(passwordEncoder.encode(dto.getNewPassword()));
            userMapper.updateById(authuser);
        }
    }

    // 账户注销
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAccount(Long userId) {
        // 1. 发布同步事件，触发其他模块校验
        // 如果其他模块校验失败抛出异常，此处事务会直接回滚
        eventPublisher.publishEvent(new AccountCancelEvent(this, userId));

        Boolean exists = bankCardMapper.countByUserId(userId);
        if (exists) {
            throw new BusinessException(ResultCode.BANKCARD_BOUND);
        }

        // 2. 校验通过，执行逻辑删除
        UserBase user = userBaseMapper.selectById(userId);
        user.setStatus(UserStatusEnum.DELETED);
        userBaseMapper.updateById(user);
    }


}
