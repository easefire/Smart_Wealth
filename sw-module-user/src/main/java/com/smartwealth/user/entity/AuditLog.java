package com.smartwealth.user.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体类
 */
@Data
@TableName("t_admin_audit_log") // 对应数据库表名
@Schema(description = "系统审计日志")
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID) // 使用雪花算法生成分布式唯一ID
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "管理员ID")
    private Long adminId;

    @Schema(description = "操作模块")
    private String module;

    @Schema(description = "操作内容描述")
    private String operation;

    @Schema(description = "请求参数内容(JSON格式)")
    private String params;

    @Schema(description = "操作者IP地址")
    private String ipAddress;

    @Schema(description = "创建时间/操作时间")
    private LocalDateTime createTime;
}