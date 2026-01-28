package com.smartwealth.product.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartwealth.product.entity.ProductRateHistory;

public interface IProductRateHistoryService extends IService<ProductRateHistory> {
    /**
     * 获取产品最近的一条历史记录（用于计算今日净值）
     */
    ProductRateHistory getLastHistory(Long prodId);
}