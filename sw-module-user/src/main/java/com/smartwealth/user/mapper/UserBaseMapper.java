package com.smartwealth.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartwealth.user.entity.UserBase;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 用户基础信息表 Mapper 接口
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface UserBaseMapper extends BaseMapper<UserBase> {

    boolean existsByUsernameIgnoreSelf(@Param("username") String username, @Param("userId") Long userId);

    boolean existsByPhoneIgnoreSelf(@Param("phone") String phone,@Param("userId") Long userId);

    @Select("SELECT COUNT(1) > 0 FROM t_user_base WHERE id_card = #{idCard} AND id != #{currentUserId}")
    boolean existsByIdCardIgnoreSelf(@Param("idCard") String idCard, @Param("currentUserId") Long currentUserId);
}

