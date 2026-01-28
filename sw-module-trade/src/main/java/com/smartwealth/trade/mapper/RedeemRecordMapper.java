package com.smartwealth.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.trade.entity.RedemptionRecord;
import org.apache.ibatis.annotations.Select;

public interface RedeemRecordMapper extends BaseMapper<RedemptionRecord> {
    @Select("select * from t_trade_redemption_record where request_id = #{requestId} for update")
    RedemptionRecord selectForUpdate(Long requestId);
}
