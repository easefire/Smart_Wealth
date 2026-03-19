package com.smartwealth.product.service.impl;

import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.product.mapper.ProdInfoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Slf4j
public class TransactionHelper {
    @Autowired
    private ProdInfoMapper prodInfoMapper;
    //数据库库存操作
    @Transactional(rollbackFor = Exception.class)
    public void doStockInDb(Long id, BigDecimal quantity, String action) {
        if(action.equals("LOCK")){
            int rows = prodInfoMapper.lockStock(id, quantity);
            if (rows == 0) {
                throw new BusinessException("产品已被抢购空，请重试");
            }
        }else if(action.equals("UNLOCK")){
            int rows=prodInfoMapper.unlockStock(id, quantity);
            if (rows==0) {
                throw new BusinessException("份额回滚失败");
            }

        }
    }
}
