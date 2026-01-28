package com.smartwealth.user.service;

import com.smartwealth.user.vo.BankCardVO;

public interface InternalBankCardService {
    BankCardVO getCardById(Long cardId, Long userId);
}
