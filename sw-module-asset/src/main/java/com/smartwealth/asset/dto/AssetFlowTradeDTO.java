package com.smartwealth.asset.dto;


import lombok.Data;

/**
 * 资产流水传输对象
 * 用于对账时接收 Asset 端的原始流水信息
 */
@Data
public class AssetFlowTradeDTO {

    /** 用户ID */
    private Long userId;

    /** 产品ID (对应 t_asset_flow 表里的 biz_id) */
    private Long prodId;

    /** * 关键字段：备注
     * 例如："申购扣款，订单号：2015... 份额：9088.57"
     * Job 会解析这个字符串提取份额
     */
    private String remark;
}
