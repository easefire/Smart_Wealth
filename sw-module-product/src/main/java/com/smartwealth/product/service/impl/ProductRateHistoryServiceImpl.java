package com.smartwealth.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartwealth.product.entity.ProductRateHistory;
import com.smartwealth.product.mapper.ProductRateHistoryMapper;
import com.smartwealth.product.service.IProductRateHistoryService;
import org.springframework.stereotype.Service;

@Service
public class ProductRateHistoryServiceImpl extends ServiceImpl<ProductRateHistoryMapper, ProductRateHistory> implements IProductRateHistoryService {
    // 取某产品的最新利率历史记录
    @Override
    public ProductRateHistory getLastHistory(Long prodId) {
        return this.getOne(new LambdaQueryWrapper<ProductRateHistory>()
                .eq(ProductRateHistory::getProdId, prodId)
                .orderByDesc(ProductRateHistory::getRecordDate)
                .last("LIMIT 1"));
    }
}