package com.smartwealth.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.asset.entity.AssetLocalMsg;

import java.util.List;

public interface AssetLocalMsgMapper extends BaseMapper<AssetLocalMsg> {


    List<AssetLocalMsg> selectPendingMsgs();

    /**
     * 【NEW for #13】ConfirmCallback 收到 ack=true 时，按业务 msgId 将本地消息置为已成功。
     * 由 {@link com.smartwealth.trade.config.RabbitmqConfirmConfig#confirm} 调用。
     */
    int updateStatusSuccessByMsgId(String msgId);
}
