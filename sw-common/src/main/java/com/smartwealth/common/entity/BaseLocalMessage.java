package com.smartwealth.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
/**
 * 本地消息实体类
 */
@Data
public class BaseLocalMessage implements Serializable {
    private Long id;
    private String msgId;
    private String topic;
    private String content;
    private Integer status;
    private Integer retryCount;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime nextRetry;
    @TableField(fill= FieldFill.INSERT)
    private LocalDateTime createTime;
}
