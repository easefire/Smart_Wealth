package com.smartwealth.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.smartwealth.user.enums.UserStatusEnum;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
/**
 * <p>
 * 用户基础信息表
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
@Data
@TableName("t_user_base")
public class UserBase implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 登录账号
     */
    private String username;

    /**
     * 手机号 (脱敏)
     */
    private String phone;

    /**
     * 真实姓名 (AES加密)
     */
    private String realName;

    /**
     * 身份证号 (AES加密)
     */
    private String idCard;

    /**
     * 风险等级: 0-未测, 1-保守...5-激进 (AI核心参数)
     */
    private Byte riskLevel;

    /**
     * 1-正常, 2-冻结 (风控用)
     */
    private UserStatusEnum status;

    /**
     * 注册时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
