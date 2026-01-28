package com.smartwealth.user.service.impl;


import com.smartwealth.user.entity.UserBase;
import com.smartwealth.user.service.IUserBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
/**
 * <p>
 * 内部调用的用户服务
 * </p>
 *
 * @author Fire
 * @since 2026-01-12
 */
@Service
public class InternalUserService {

    @Autowired
    private IUserBaseService userService;
    // 获取用户风险评级
    public Integer getUserRiskLevel(Long userId) {
        UserBase user = userService.getById(userId);
        if (user == null) {
            return 0; // 未评级或不存在
        }
        // 假设用户表中有 risk_level 字段
        return user.getRiskLevel().intValue();
    }
    // 根据一组用户ID获取用户信息映射
    public Map<Long, UserBase> getUsersByIds(Set<Long> userIds) {
        return userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(UserBase::getId, user -> user));
    }
}