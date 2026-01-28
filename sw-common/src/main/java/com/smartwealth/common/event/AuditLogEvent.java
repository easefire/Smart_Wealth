package com.smartwealth.common.event;

import com.smartwealth.common.dto.AuditLogDTO;
import org.springframework.context.ApplicationEvent;

public class AuditLogEvent extends ApplicationEvent {
    private final AuditLogDTO logDTO;

    public AuditLogEvent(Object source, AuditLogDTO logDTO) {
        super(source);
        this.logDTO = logDTO;
    }

    public AuditLogDTO getLogDTO() {
        return logDTO;
    }
}
