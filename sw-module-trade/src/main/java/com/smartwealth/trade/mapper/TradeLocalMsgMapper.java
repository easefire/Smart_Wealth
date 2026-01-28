package com.smartwealth.trade.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.trade.entity.TradeLocalMsg;

import java.util.List;

public interface TradeLocalMsgMapper extends BaseMapper<TradeLocalMsg> {
    List<TradeLocalMsg> selectPendingMsgs();
}
