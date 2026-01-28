package com.smartwealth.user.service;

import com.smartwealth.user.dto.BankCardBindDTO;
import com.smartwealth.user.dto.UserRealNameDTO;
import com.smartwealth.user.dto.UserRiskAssessmentDTO;
import com.smartwealth.user.vo.BankCardVO;
import com.smartwealth.user.entity.UserBase;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.Valid;

import java.util.List;

/**
 * <p>
 * 用户基础信息表 服务类
 * </p>
 *
 * @author Gemini
 * @since 2026-01-12
 */
public interface IUserBaseService extends IService<UserBase> {

    void realNameAuth(@Valid UserRealNameDTO dto);

    Integer doRiskAssessment(@Valid UserRiskAssessmentDTO dto);

    void bindBankCard(BankCardBindDTO dto);

    void removeBankCard(Long id);

    List<BankCardVO> queryMyBankCards();


}
