package com.smartwealth.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartwealth.agent.entity.AiReportTask;
import com.smartwealth.agent.dto.UserPortraitDTO;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.product.vo.ProductVO;

import java.util.List;

public interface AgentService extends IService<AiReportTask> {

    // 1. 提交任务 (需求 1 入口)
    void submitReportTask(String prompt);

    // 2. 处理 Python 回调 (需求 1 响应)
    void processTaskCallback(String taskId, String reportResult, String errorMsg);

    // 3. 获取内部用户画像 (需求 2)
    UserPortraitDTO getUserPortrait(Long userId);

    // 4. 获取内部产品信息 (需求 3)
    ProductDetailVO getProductDetail(Long productId);
    // 5. 获取产品列表 (需求 4)
    List<ProductVO> getProductList(Integer risk_level);
}
