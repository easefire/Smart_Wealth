package com.smartwealth.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.product.entity.ProdInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * <p>
 * 理财产品信息表 Mapper 接口
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface ProdInfoMapper extends BaseMapper<ProdInfo> {

    @Update("UPDATE t_prod_info SET locked_stock = locked_stock + #{shares} " +
            "WHERE id = #{productId} AND (total_stock - locked_stock) >= #{shares}")
    int lockStock(@Param("productId") Long productId, @Param("shares") BigDecimal shares);

    @Update("UPDATE t_prod_info SET locked_stock = locked_stock - #{shares} " +
            "WHERE id = #{productId} AND locked_stock >= #{shares}")
    int unlockStock(@Param("productId") Long productId, @Param("shares") BigDecimal shares);

}

