package com.smartwealth.user.service.impl;


import com.smartwealth.api.InternalUserApi;
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
public class InternalUserService implements InternalUserApi {

    @Autowired
    private IUserBaseService userService;
    // 获取用户风险评级
    // 【BUGFIX-#20】
    //   - 之前 user.getRiskLevel().intValue() 在"用户存在但未做风险测评"这条最常见路径上直接 NPE，
    //     导致下游申购等接口被未授权请求打 500，且日志里堆栈对排查毫无帮助。
    //   - 返回值改为 null（语义=未评级），与"返回 0"区分开：0 是测评结果，null 是未做。
    //   - 调用方需用 == null 显式判断（参见 TradeOrderServiceImpl.purchase）。
    @Override
    public Integer getUserRiskLevel(Long userId) {
        if (userId == null) {
            return null;
        }
        UserBase user = userService.getById(userId);
        if (user == null || user.getRiskLevel() == null) {
            return null;
        }
        return user.getRiskLevel().intValue();
    }
    // 根据一组用户ID获取用户信息映射
    public Map<Long, UserBase> getUsersByIds(Set<Long> userIds) {
        return userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(UserBase::getId, user -> user));
    }
}