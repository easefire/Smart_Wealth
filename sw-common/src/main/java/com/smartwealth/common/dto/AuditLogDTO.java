package com.smartwealth.common.dto;

import lombok.Data;

// 1. 定义 DTO (放在 common 模块)
@Data
public class AuditLogDTO {
    private Long adminId;
    private String module;
    private String operation;
    private String params;
    private String ipAddress;
}


