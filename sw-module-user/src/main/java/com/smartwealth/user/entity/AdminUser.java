package com.smartwealth.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员实体类
 * 对应表名: admin_user
 */
@Data
@TableName("t_admin_user")
public class AdminUser {

    /**
     * 主键 ID
     * 对应数据库 bigint
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户名
     * 对应数据库 varchar(32)
     */
    private String username;

    /**
     * 密码 (加密存储)
     * 对应数据库 varchar(128)
     */
    private String password;

    /**
     * 昵称
     * 对应数据库 varchar(32)
     */
    private String nickname;

    /**
     * 账号状态
     * 对应数据库 tinyint (通常 1:正常, 0:禁用)
     */
    private Integer status;

    /**
     * 创建时间
     * 对应数据库 datetime
     * fill = FieldFill.INSERT 表示在插入时由 MyBatis-Plus 自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}