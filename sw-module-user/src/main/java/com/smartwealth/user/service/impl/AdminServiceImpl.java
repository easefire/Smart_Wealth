package com.smartwealth.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartwealth.common.context.UserContext;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import com.smartwealth.user.entity.AuditLog;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.user.mapper.AuditLogMapper;
import com.smartwealth.common.result.ResultCode;
import com.smartwealth.common.util.JwtUtils;
import com.smartwealth.user.dto.UserLoginDTO;
import com.smartwealth.user.vo.AdminUserVO;
import com.smartwealth.user.entity.AdminUser;
import com.smartwealth.user.entity.UserBase;
import com.smartwealth.user.enums.UserStatusEnum;
import com.smartwealth.user.mapper.AdminUserMapper;
import com.smartwealth.user.mapper.UserBaseMapper;
import com.smartwealth.user.service.IAdminService;
import com.smartwealth.user.service.IUserBaseService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 管理员用户表 服务实现类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Slf4j
@Service
public class AdminServiceImpl extends ServiceImpl<AdminUserMapper, AdminUser> implements IAdminService {

    @Autowired
    private UserBaseMapper baseMapper;
    @Autowired
    private AdminUserMapper adminUserMapper;
    @Autowired
    private IUserBaseService userService;
    @Autowired
    private AuditLogMapper auditLogMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RedisService redisService;

    //统计某天注册的用户总数，date 为 null 则统计所有用户总数
    @Override
    public Long countTotalUsers(LocalDate date) {
        LambdaQueryWrapper<UserBase> wrapper = new LambdaQueryWrapper<>();

        if (date != null) {
            // 优化：计算该日期的 00:00:00 到 23:59:59
            // 这样写可以走 create_time 的索引，比 DATE(create_time) 性能高得多
            LocalDateTime startOfDay = date.atStartOfDay();
            LocalDateTime endOfDay = date.atTime(LocalTime.MAX);

            wrapper.between(UserBase::getCreateTime, startOfDay, endOfDay);
        }

        // 如果 date 为 null，wrapper 没有任何条件，自动查询全表总数
        return baseMapper.selectCount(wrapper);
    }
    //更新用户状态（冻结/解冻）
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateUserStatus(Long userId, UserStatusEnum status) {
        // 1. 参数校验
        if (userId == null || status == null) {
            return false;
        }

        // 2. 构造更新条件
        LambdaUpdateWrapper<UserBase> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(UserBase::getId, userId)
                .set(UserBase::getStatus, status) // 设置新状态：0-冻结，1-正常
                .set(UserBase::getCreateTime, LocalDateTime.now());

        // 3. 执行更新
        int success = baseMapper.update(updateWrapper);

        return success == 1;
    }
    // 管理员注册
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(UserLoginDTO registerDto) {
        // 1. 参数基础校验 (防止空数据进库)
        if (StringUtils.isBlank(registerDto.getUsername()) || StringUtils.isBlank(registerDto.getPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        // 2. 唯一性校验
        // 使用 MyBatis-Plus 的 LambdaQueryWrapper 检查用户名是否已存在
        Long count = this.adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, registerDto.getUsername()));
        if (count > 0) {
            throw new BusinessException(ResultCode.USERNAME_ALREADY_EXIST);
        }
        // 3. 构建实体对象
        AdminUser admin = new AdminUser();
        admin.setUsername(registerDto.getUsername());
        // 4. 密码加密存储 (绝对不能存明文)
        admin.setPassword(passwordEncoder.encode(registerDto.getPassword()));
        // 5. 设置默认状态
        // 建议使用之前讨论过的枚举，或者定义常量。这里假设 1 为正常
        admin.setStatus(1);
        // 6. 设置默认昵称 (可选，默认同名)
        admin.setNickname("管理员_" + registerDto.getUsername());
        // 7. 执行插入
        boolean success = this.save(admin);
        if (!success) {
            throw new BusinessException(ResultCode.FAILURE);
        }
        log.info("新管理员注册成功，ID: {}, Username: {}", admin.getId(), registerDto.getUsername());
    }
    // 管理员登录
    @Override
    public String login(UserLoginDTO dto) {
        // 1. 根据用户名查询 UserAuth（包含加密后的密码和状态）
        AdminUser user = adminUserMapper.selectByUsername(dto.getUsername());
        if (user == null) {
            throw new BusinessException(ResultCode.FAILURE); // 处于安全考虑，建议提示模糊化
        }
        // 2. 检查账户状态（是否被冻结）
        if (user.getStatus() == 0) {
            throw new BusinessException(ResultCode.USER_FROZEN);
        }
        // 3. 核心：校验密码
        // matches(明文, 密文) -> 它会自动从密文提取盐值并进行哈希比对
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }
        String token = JwtUtils.createToken(user.getId(), "ADMIN");
        String redisKey = String.format(RedisKeyConstants.ADMIN_TOKEN, user.getId());
        redisService.set(redisKey, token, 30, TimeUnit.MINUTES);

        // 4. 登录成功，生成 JWT Token（下一步我们需要实现的）
        return token;
    }
    // 管理员登出
    @Override
    public void logout(Long userId) {
        // 管理员登出逻辑（如有需要，可以实现会话无效化等操作）
        // 构造 Redis Key
        String key = String.format(RedisKeyConstants.ADMIN_TOKEN, userId);
        // 核心：从 Redis 中删除，实现“主动失效”
        redisService.delete(key);
        // 清理当前线程的上下文，防止脏数据
        UserContext.clear();
        SecurityContextHolder.clearContext();
        log.info("管理员 ID: {} 已登出", userId);
    }
    // 获取用户分页列表（管理端使用）
    @Override
    public IPage<AdminUserVO> listUsersForAdmin(int current, int size) {
        // 1. 创建分页对象 (当前页, 每页条数)
        Page<UserBase> page = new Page<>(current, size);

        // 2. 执行分页查询
        // 这里调用的是 IService 提供的 page 方法
        Page<UserBase> userPage = userService.page(page);

        // 3. 将 Page 中的 Entity 转换为 VO
        return userPage.convert(AdminUserVO::fromEntity);
    }
    // 查看审计日志分页列表
    @Override
    public IPage<AuditLog> getAuditLogs(int current, int size) {
        // 1. 创建分页对象
        Page<AuditLog> page = new Page<>(current, size);
        // 2. 执行分页查询
        IPage<AuditLog> auditLogPage = auditLogMapper.selectAuditLogs(page);
        return auditLogPage;
    }
}
