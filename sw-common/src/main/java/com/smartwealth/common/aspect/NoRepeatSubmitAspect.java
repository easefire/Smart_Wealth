package com.smartwealth.common.aspect;

import com.alibaba.fastjson.JSON;
import com.smartwealth.common.annotations.NoRepeatSubmit;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService; // 引用你的 Service
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

/**
 * 防重复提交切面
 * @author Fire
 */
@Aspect
@Component
@Slf4j
public class NoRepeatSubmitAspect {

    @Autowired
    private RedisService redisService;

    @Around("@annotation(noRepeatSubmit)")
    public Object around(ProceedingJoinPoint point, NoRepeatSubmit noRepeatSubmit) throws Throwable {
        // 获取请求对象
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        // 获取请求头中的 token
        String token = request.getHeader("Authorization");
        // 获取请求的 URI 和参数
        String uri = request.getRequestURI();
        // 将参数转换为 JSON 字符串
        String params = JSON.toJSONString(point.getArgs());
        // 生成指纹 Key
        String key = RedisKeyConstants.REPEAT_SUBMIT + token + ":" + uri + ":" + DigestUtils.md5DigestAsHex(params.getBytes());
        Boolean isSuccess = redisService.setIfAbsent(key, "1", noRepeatSubmit.lockTime(), TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(isSuccess)) {
            return point.proceed();
        } else {
            throw new BusinessException("操作太快，请 " + noRepeatSubmit.lockTime() + " 秒后再试");
        }
    }
}