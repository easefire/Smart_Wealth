package com.smartwealth.common.aspect;

import com.alibaba.fastjson.JSON;
import com.smartwealth.common.dto.AuditLogDTO;
import com.smartwealth.common.annotations.LogAudit;
import com.smartwealth.common.context.UserContext;
import com.smartwealth.common.event.AuditLogEvent;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


@Aspect
@Slf4j
@Component
@RequiredArgsConstructor
public class LogAuditAspect {

    private final ApplicationEventPublisher eventPublisher;

    @Around("@annotation(logAudit)")
    public Object around(ProceedingJoinPoint joinPoint, LogAudit logAudit) throws Throwable {
        // 获取请求对象
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes != null ? attributes.getRequest() : null;
        // 1. 执行目标方法
        Object result = joinPoint.proceed();
        try {
            // 2. 封装 DTO
            AuditLogDTO dto = new AuditLogDTO();
            if(UserContext.getUserId() != null)
            {
                dto.setAdminId(UserContext.getUserId());
            }else{
                dto.setAdminId(0L); //系统操作 默认为0
            }
            dto.setModule(logAudit.module());// 模块名称
            dto.setOperation(logAudit.operation());// 操作描述
            dto.setParams(JSON.toJSONString(joinPoint.getArgs()));// 请求参数

            if (request != null) {
                // 获取客户端 IP 地址
                String ip = request.getHeader("X-Forwarded-For");
                dto.setIpAddress(ip != null ? ip : request.getRemoteAddr());
            }
            // 发布事件
            eventPublisher.publishEvent(new AuditLogEvent(this, dto));

        } catch (Exception e) {
            log.error("审计日志发送失败", e);
            // 记日志报错不应影响主业务流程回滚
        }
        return result;
    }
}