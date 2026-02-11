package com.smartwealth.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.agent.entity.AiReportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * AI 研报任务数据访问层
 */
@Mapper
public interface AiReportTaskMapper extends BaseMapper<AiReportTask> {

    /**
     * 根据 Python 端任务 ID 更新报告结果
     * 体现了分布式任务回调的准确性
     */
    int updateResultByTaskId(@Param("taskId") String taskId,
                             @Param("status") Integer status,
                             @Param("reportResult") String reportResult,
                             @Param("errorMsg") String errorMsg);

    /**
     * 根据任务 ID 获取任务详情（包含用户信息）
     * 适用于回调时的二次校验
     */
    AiReportTask selectByTaskId(@Param("taskId") String taskId);
}