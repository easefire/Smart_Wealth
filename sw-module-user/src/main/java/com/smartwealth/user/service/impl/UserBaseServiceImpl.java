package com.smartwealth.user.service.impl;

import com.smartwealth.user.entity.UserBase;
import com.smartwealth.user.mapper.UserBaseMapper;
import com.smartwealth.user.service.IUserBaseService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 用户基础信息表 服务实现类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Service
public class UserBaseServiceImpl extends ServiceImpl<UserBaseMapper, UserBase> implements IUserBaseService {

}
