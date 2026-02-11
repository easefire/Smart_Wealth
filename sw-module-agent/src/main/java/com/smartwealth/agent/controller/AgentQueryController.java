package com.smartwealth.agent.controller;

import com.smartwealth.agent.client.PythonAgentClient;
import com.smartwealth.agent.dto.PromptRequest;
import com.smartwealth.agent.dto.PythonAgentRequest;
import com.smartwealth.agent.service.AgentService;
import com.smartwealth.common.context.UserContext;
import com.smartwealth.common.result.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sw/user/agent")
@Slf4j
@Tag(name = "用户端-AI请求")
@PreAuthorize("hasRole('USER')")
public class AgentQueryController {

    @Autowired
    private AgentService agentService;

    /**
     * 用户提交需求，转发给 Python 处理
     */
    @PostMapping("/submit")
    public Result<String> submitReportTask(@RequestBody PromptRequest query) {
        // 1. 从之前定义的 UserContext 中获取当前用户的 userId
        Long userId = UserContext.getUserId();
        String prompt = query.getPrompt();
        log.info("🚀 收到研报生成请求，用户ID: {}, Prompt: {}", userId, prompt);
        agentService.submitReportTask(prompt);
        return Result.success("任务已提交，稍后请留意系统通知。");
    }
}
