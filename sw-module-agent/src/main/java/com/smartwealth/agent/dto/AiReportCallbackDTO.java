package com.smartwealth.agent.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 需求 1 响应：Python Agent 异步回调 Java 的数据契约
 * 用于更新任务状态及存储生成的研报内容
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiReportCallbackDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Python 端生成的唯一任务追踪 ID (UUID)
     * 对应数据库中的 task_id 字段，用于精确定位记录
     */
    private String taskId;

    /**
     * 最终生成的研报内容
     * 通常为 Markdown 或 HTML 格式的长文本
     * 如果任务失败，此字段为 null
     */
    private String reportResult;

    /**
     * 任务失败时的错误堆栈或友好提示
     * 如果任务成功，此字段为 null
     */
    private String errorMsg;

}