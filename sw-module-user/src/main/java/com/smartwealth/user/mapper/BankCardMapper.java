package com.smartwealth.user.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.user.entity.BankCard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 银行卡 Mapper 接口
 * 对应表：t_user_bank_card
 */
@Mapper
public interface BankCardMapper extends BaseMapper<BankCard> {
    /**
     * 检查该银行卡号是否已被除当前用户外的其他人绑定
     * 用于保证实名信息的唯一性和安全性
     */
    @Select("SELECT COUNT(1) > 0 FROM t_user_bank_card WHERE card_no = #{cardNo} AND user_id != #{userId}")
    boolean existsByCardNoIgnoreSelf(@Param("cardNo") String cardNo, @Param("userId") Long userId);

    BankCard selectByIdAndUserId(@Param("id") Long cardId, @Param("userId")Long userId);

    @Select("SELECT COUNT(1) > 0 FROM t_user_bank_card WHERE user_id = #{userId}")
    Boolean countByUserId(Long userId);
}