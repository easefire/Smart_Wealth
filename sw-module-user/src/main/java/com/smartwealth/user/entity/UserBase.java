package com.smartwealth.user.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.smartwealth.user.enums.UserStatusEnum;

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

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getIdCard() {
        return idCard;
    }

    public void setIdCard(String idCard) {
        this.idCard = idCard;
    }

    public Byte getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(Byte riskLevel) {
        this.riskLevel = riskLevel;
    }

    public UserStatusEnum getStatus() {
        return status;
    }

    public void setStatus(UserStatusEnum status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    @Override
    public String toString() {
        return "UserBase{" +
            "id = " + id +
            ", username = " + username +
            ", phone = " + phone +
            ", realName = " + realName +
            ", idCard = " + idCard +
            ", riskLevel = " + riskLevel +
            ", status = " + status +
            ", createTime = " + createTime +
        "}";
    }
}
