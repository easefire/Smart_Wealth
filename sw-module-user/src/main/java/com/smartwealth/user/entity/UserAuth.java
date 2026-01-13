package com.smartwealth.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
/**
 * <p>
 * 用户认证敏感表
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@TableName("t_user_auth")
public class UserAuth implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    @TableId("user_id")
    private Long userId;

    /**
     * BCrypt加密
     */
    private String passwordHash;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    @Override
    public String toString() {
        return "UserAuth{" +
            "userId = " + userId +
            ", passwordHash = " + passwordHash +
        "}";
    }
}
