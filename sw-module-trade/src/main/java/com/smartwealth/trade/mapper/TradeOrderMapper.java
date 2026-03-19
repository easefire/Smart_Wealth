package com.smartwealth.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.trade.dto.PoolCheckDTO;
import com.smartwealth.trade.dto.TradeCheckDTO;
import com.smartwealth.trade.entity.TradeOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * <p>
 * 理财交易订单表 Mapper 接口
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

    /**
     * 赎回扣减持仓：同时减少 份额、本金、累计收益、冻结份额
     * @param id 订单ID
     * @param reduceQuantity 减少的份额
     * @param reducePrincipal 减少的本金
     * @param reduceIncome 减少的累计收益
     * @return 影响行数
     */
    int deductPositions(@Param("id") Long id,
                        @Param("reduceQuantity") BigDecimal reduceQuantity,
                        @Param("reducePrincipal") BigDecimal reducePrincipal,
                        @Param("reduceIncome") BigDecimal reduceIncome);

    // 更新状态的简单方法
    void updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 失败：仅逻辑解冻
     */
    void onlyUnfreeze(@Param("orderId") Long orderId,
                     @Param("amount") BigDecimal amount);

    /**
     * 游标查询持有中的订单
     */
    @Select("SELECT id, prod_id, quantity, user_id FROM t_trade_order WHERE status = 1 AND id > #{lastId} ORDER BY id ASC LIMIT #{limit}")
    List<TradeOrder> selectBatchForSettlement(@Param("lastId") Long lastId, @Param("limit") int limit);

    /**
     * 批量累加收益
     * 注意：这里是做加法 set accumulated_income = accumulated_income + #{item.accumulatedIncome}
     */

    List<TradeOrder> selectUnsettledOrders(@Param("lastId") Long lastId,
                                           @Param("fetchSize") int fetchSize,
                                           @Param("shardIndex") int shardIndex,
                                           @Param("shardTotal") int shardTotal,
                                           @Param("bizDate") LocalDate bizDate);

    // 批量累加收益
    int batchAddIncome(@Param("list") List<TradeOrder> list);

    List<TradeCheckDTO> checkIncomeConsistency();


    List<PoolCheckDTO> sumTradeSharesByPool();
}

