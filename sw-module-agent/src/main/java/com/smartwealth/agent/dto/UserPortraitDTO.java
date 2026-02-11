package com.smartwealth.agent.dto;


import com.smartwealth.trade.entity.TradeOrder;
import com.smartwealth.trade.vo.PositionVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 需求 2：提供给 AI 的用户画像（已脱敏）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserPortraitDTO {
    private Long userId;
    private Integer riskLevel;       // 风险等级
    private List<PositionVO> positions; // 当前持仓
}
