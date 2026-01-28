package com.smartwealth.common.baseentity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class BaseLocalMessage implements Serializable {
    private Long id;
    private String msgId;
    private String topic;
    private String content;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetry;
    private LocalDateTime createTime;
}
