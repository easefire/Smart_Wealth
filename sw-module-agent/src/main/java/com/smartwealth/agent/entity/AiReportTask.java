package com.smartwealth.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_agent_report_task")
public class AiReportTask implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 对应 Python 端的 UUID
     */
    private String taskId;

    /**
     * 用户输入的 Prompt
     */
    private String prompt;

    /**
     * 任务状态: 0-INIT, 1-RUNNING, 2-SUCCESS, 3-FAIL
     */
    private Integer status;

    /**
     * 最终生成的研报内容
     */
    private String reportResult;

    /**
     * 错误信息
     */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}