package com.smartwealth.user.listener;

import com.smartwealth.common.dto.AuditLogDTO;
import com.smartwealth.common.event.AuditLogEvent;
import com.smartwealth.user.entity.AuditLog;
import com.smartwealth.user.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogListener {

    private final AuditLogMapper auditLogMapper;

    // 使用 @EventListener 监听事件
    // 建议加上 @Async 变为异步执行，这样存日志不会拖慢接口响应
    @Async
    @EventListener
    public void onAuditLogEvent(AuditLogEvent event) {
        AuditLogDTO dto = event.getLogDTO();

        // 将 DTO 转换为数据库 Entity
        AuditLog entity = new AuditLog();
        BeanUtils.copyProperties(dto, entity);


        // 执行入库
        auditLogMapper.insert(entity);
        log.info("审计日志异步保存成功：{}", dto.getOperation());
    }
}