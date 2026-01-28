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

    // 注入事件发布器，不再依赖 Mapper
    private final ApplicationEventPublisher eventPublisher;

    @Around("@annotation(logAudit)")
    public Object around(ProceedingJoinPoint joinPoint, LogAudit logAudit) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes != null ? attributes.getRequest() : null;

        // 1. 执行原方法获取结果（保证业务优先）
        Object result = joinPoint.proceed();

        try {
            // 2. 封装 DTO
            AuditLogDTO dto = new AuditLogDTO();
            // 注意：你的 UserContext 需要通过 Spring 注入或者静态方法获取
            if(UserContext.getUserId() != null)
            {
                dto.setAdminId(UserContext.getUserId());
            }else{
                dto.setAdminId(0L); // 系统操作
            }
            dto.setModule(logAudit.module());
            dto.setOperation(logAudit.operation());
            dto.setParams(JSON.toJSONString(joinPoint.getArgs()));

            if (request != null) {
                String ip = request.getHeader("X-Forwarded-For");
                dto.setIpAddress(ip != null ? ip : request.getRemoteAddr());
            }

            // 3. 发布事件
            eventPublisher.publishEvent(new AuditLogEvent(this, dto));

        } catch (Exception e) {
            log.error("审计日志发送失败", e);
            // 记日志报错不应影响主业务流程回滚
        }

        return result;
    }
}