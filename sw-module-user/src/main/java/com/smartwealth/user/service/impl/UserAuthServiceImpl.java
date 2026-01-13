package com.smartwealth.user.service.impl;

import com.smartwealth.user.entity.UserAuth;
import com.smartwealth.user.mapper.UserAuthMapper;
import com.smartwealth.user.service.IUserAuthService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

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

}
