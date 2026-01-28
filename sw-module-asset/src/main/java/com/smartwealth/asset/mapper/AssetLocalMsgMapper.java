package com.smartwealth.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.asset.entity.AssetLocalMsg;

import java.util.List;

public interface AssetLocalMsgMapper extends BaseMapper<AssetLocalMsg> {


    List<AssetLocalMsg> selectPendingMsgs();
}
