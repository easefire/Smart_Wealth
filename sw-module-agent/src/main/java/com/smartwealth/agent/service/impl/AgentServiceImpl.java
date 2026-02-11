package com.smartwealth.agent.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartwealth.agent.dto.UserPortraitDTO;
import com.smartwealth.agent.service.AgentService;
import com.smartwealth.common.context.UserContext;
import com.smartwealth.agent.entity.AiReportTask;
import com.smartwealth.agent.mapper.AiReportTaskMapper;
import com.smartwealth.agent.client.PythonAgentClient;
import com.smartwealth.agent.dto.PythonAgentRequest;
import com.smartwealth.product.service.impl.InternalProductService;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.product.vo.ProductVO;
import com.smartwealth.trade.service.impl.InternalTradeService;
import com.smartwealth.trade.vo.PositionVO;
import com.smartwealth.user.service.impl.InternalUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class AgentServiceImpl extends ServiceImpl<AiReportTaskMapper, AiReportTask> implements AgentService {

    @Autowired
    private PythonAgentClient pythonAgentClient;
    @Autowired
    private InternalProductService internalProductService;
    @Autowired
    private InternalUserService internalUserService;
    @Autowired
    private InternalTradeService internalTradeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitReportTask(String prompt) {
        Long userId = UserContext.getUserId();

        // 1. 构造请求 DTO
        PythonAgentRequest request = new PythonAgentRequest(userId, prompt);
        String pythonTaskId;

        try {
            log.info("🚀 正在向 Python 发送研报生成请求...");
            // 2. 先拿 ID：远程调用 Python
            // 注意：按我们之前的设计，Python 的 Router 会秒回一个生成的 TaskID
            pythonTaskId = pythonAgentClient.startAgentTask(request).getTask_id();

        } catch (Exception e) {
            log.error("❌ 调用 Python 失败: {}", e.getMessage());
            // 如果这里失败了，由于还没存库，直接抛异常给前端即可，不需要更新数据库
            throw new RuntimeException("AI 系统服务异常，请稍后再试");
        }

        // 3. 一次性存盘：绑定 Python 的 taskId 并标记为 RUNNING (状态 1)
        AiReportTask task = new AiReportTask();
        task.setTaskId(pythonTaskId);
        task.setUserId(userId);
        task.setPrompt(prompt);
        task.setStatus(1); // 直接进入 RUNNING 状态

        log.info("✅ 任务已存盘，绑定 ID: {}", pythonTaskId);
        this.save(task);
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processTaskCallback(String taskId, String reportResult, String errorMsg) {
        log.info("收到 Python 回调通知，任务 ID: {}", taskId);
        int status = (errorMsg == null) ? 2 : 3; // SUCCESS 为 2，FAIL 为 3
        int rows = baseMapper.updateResultByTaskId(taskId, status, reportResult, errorMsg);

        if (rows == 0) {
            log.warn("回调处理失败：未找到 taskId 为 {} 的任务，或任务状态已更新", taskId);
        } else {
            log.info("任务 {} 状态已成功更新为 {}", taskId, status);
        }
    }
    // 2. 获取用户画像信息
    @Override
    public UserPortraitDTO getUserPortrait(Long userId) {
        Integer riskLevel = internalUserService.getUserRiskLevel(userId);
        List<PositionVO> positions = internalTradeService.getUserPositions(userId);
        return new UserPortraitDTO(userId,riskLevel, positions);

    }
    // 3. 获取产品详细信息
    @Override
    public ProductDetailVO getProductDetail(Long productId) {
        return internalProductService.getProductDetail(productId,90);
    }
    // 4. 获取多个产品的简要信息
    @Override
    public List<ProductVO> getProductList(Integer risk_level) {
        return internalProductService.selectListForAgent(risk_level);
    }

}