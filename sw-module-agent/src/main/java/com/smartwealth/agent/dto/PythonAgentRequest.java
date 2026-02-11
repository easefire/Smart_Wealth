package com.smartwealth.agent.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送给 Python Agent 的标准请求体
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PythonAgentRequest {
    private Long userId;
    private String prompt;
}