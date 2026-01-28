package com.smartwealth.product.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.smartwealth.product.entity.MarketSentimentLog;
import com.smartwealth.product.enums.MarketSentiment;

public interface IMarketService extends IService<MarketSentimentLog> {
    void saveSentiment(MarketSentiment sentiment, String operator);

    MarketSentimentLog selectLatestOne();

    MarketSentiment simulateNextSentiment(MarketSentiment todaySentiment);

}
