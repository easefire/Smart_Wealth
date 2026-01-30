package com.smartwealth.common.dto;

import lombok.Data;

/**
 * 审计日志传输对象
 */
@Data
public class AuditLogDTO {
    private Long adminId;
    private String module;
    private String operation;
    private String params;
    private String ipAddress;
}


