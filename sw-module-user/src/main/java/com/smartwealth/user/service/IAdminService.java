package com.smartwealth.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartwealth.user.entity.AuditLog;
import com.smartwealth.user.dto.UserLoginDTO;
import com.smartwealth.user.vo.AdminUserVO;
import com.smartwealth.user.entity.AdminUser;
import com.smartwealth.user.enums.UserStatusEnum;

import java.time.LocalDate;

public interface IAdminService extends IService<AdminUser> {

    Long countTotalUsers(LocalDate date);

    boolean updateUserStatus(Long userId, UserStatusEnum status);

    void register(UserLoginDTO registerDto);

    String login(UserLoginDTO dto);

    IPage<AdminUserVO> listUsersForAdmin(int current, int size);

    IPage<AuditLog> getAuditLogs(int current, int size);

    void logout(Long userId);
}
