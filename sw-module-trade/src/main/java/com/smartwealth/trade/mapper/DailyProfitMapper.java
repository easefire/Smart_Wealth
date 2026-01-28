package com.smartwealth.trade.mapper;



import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.trade.entity.DailyProfit;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DailyProfitMapper extends BaseMapper<DailyProfit> {
    void insertBatch(List<DailyProfit> profitInsertList);
}