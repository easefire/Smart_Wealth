package com.smartwealth.agent.controller;

import com.smartwealth.agent.dto.AiReportCallbackDTO;
import com.smartwealth.agent.dto.UserPortraitDTO;
import com.smartwealth.agent.service.AgentService;
import com.smartwealth.product.vo.ProductDetailVO;
import com.smartwealth.product.vo.ProductVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/sw/system")
@Slf4j
@Tag(name = "系统端-AI请求")
public class AgentInternalController {
    @Autowired
    private AgentService agentService;
    /**
     * 需求 1：接收 Python 异步反馈的结果
     * 由 Python Agent 在生成结束后反向调用
     */
    @PostMapping("/report")
    public void handleReportCallback(@RequestBody AiReportCallbackDTO callbackDTO) {
        log.info("📩 收到 Python 反馈，任务 ID: {}", callbackDTO.getTaskId());

        agentService.processTaskCallback(
                callbackDTO.getTaskId(),
                callbackDTO.getReportResult(),
                callbackDTO.getErrorMsg()
        );
    }
    /**
     * 需求 2：获取用户信息（用户画像）
     */
    @GetMapping("/user/{userId}")
    public UserPortraitDTO getUserPortrait(@PathVariable(value = "userId") Long userId) {
        log.info("👤 Python 正在调取用户画像，用户ID: {}", userId);
        return agentService.getUserPortrait(userId);
    }

    /**
     * 需求 3：获取低于该风险等级的所有产品简要信息
     * 逻辑：Python 传来风险等级，Java 返回符合条件的 List
     */
    @GetMapping("/products/filter")
    public List<ProductVO> getProductsByRisk(@RequestParam(value = "riskLevel") Integer riskLevel) {
        log.info("📊 Python 正在筛选风险等级 <= {} 的产品池", riskLevel);
        // 这里在 Service 里实现具体的过滤逻辑
        return agentService.getProductList(riskLevel);
    }

    /**
     * 需求 4：通过产品 ID 获取产品详细信息
     */
    @GetMapping("/products/detail/{productId}")
    public ProductDetailVO getProductDetail(@PathVariable(value = "productId") Long productId) {
        log.info("🔎 Python 正在调取产品详情，产品ID: {}", productId);
        // 这里返回包含更多细节（如波动率、持仓说明）的 DTO
        return agentService.getProductDetail(productId);
    }
}
