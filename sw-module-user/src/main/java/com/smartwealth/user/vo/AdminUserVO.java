package com.smartwealth.user.vo;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.smartwealth.user.entity.UserBase;
import com.smartwealth.user.enums.UserStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserVO {
    private Long id;
    private String username;
    private String phone;
    private UserStatusEnum status; // 用户状态
    private LocalDateTime createTime; // 注册时间

    public static AdminUserVO fromEntity(UserBase user) {
        if (user == null) return null;
        AdminUserVO vo = new AdminUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());

        if (user.getPhone() != null && user.getPhone().length() == 11) {
            vo.setPhone(user.getPhone().replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2"));
        }
        return vo;
    }
}