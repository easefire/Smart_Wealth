package com.smartwealth.agent.client;

import com.smartwealth.agent.dto.PythonAgentRequest;
import com.smartwealth.agent.dto.PythonAgentResponse;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Python AI 服务的 Feign 客户端
 * url 建议配置在 application.yml 中
 */
@FeignClient(name = "python-agent-service", url = "${smartwealth.ai.python-url}")
public interface PythonAgentClient {

    @PostMapping("/api/agent/dispatch")
    PythonAgentResponse startAgentTask(@RequestBody PythonAgentRequest request);
}

/**
 * 拦截器：自动为 Feign 请求加上内部安全密钥
 */
@Component
class FeignClientInterceptor implements RequestInterceptor {
    @Value("${smartwealth.security.internal-key}")
    private String internalKey;

    @Override
    public void apply(RequestTemplate template) {
        template.header("X-Internal-Service-Key", internalKey);
    }
}