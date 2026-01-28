package com.smartwealth.user.service;

import com.smartwealth.user.dto.UserLoginDTO;
import com.smartwealth.user.dto.UserRegisterDTO;
import com.smartwealth.user.dto.UserUpdateDTO;
import com.smartwealth.user.vo.UserVO;
import com.smartwealth.user.entity.UserAuth;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 用户认证敏感表 服务类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface IUserAuthService extends IService<UserAuth> {

    UserVO register(UserRegisterDTO registerDto);

    String login(UserLoginDTO dto);

    void updateUserInfo(UserUpdateDTO dto);

    void deleteAccount(Long userId);

    void logout(Long userId);
}
