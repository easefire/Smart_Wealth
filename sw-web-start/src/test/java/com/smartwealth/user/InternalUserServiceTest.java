package com.smartwealth.user;

import com.smartwealth.user.entity.UserBase;
import com.smartwealth.user.service.IUserBaseService;
import com.smartwealth.user.service.impl.InternalUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 覆盖 P1-#20：getUserRiskLevel 在 user 不存在 / riskLevel=null 时必须返回 null（=未测评），
 * 杜绝老版本 user.getRiskLevel().intValue() 的 NPE。
 */
@ExtendWith(MockitoExtension.class)
class InternalUserServiceTest {

    @Mock
    private IUserBaseService userBaseService;

    @InjectMocks
    private InternalUserService internalUserService;

    private UserBase userWithRisk;
    private UserBase userWithoutRisk;

    @BeforeEach
    void setUp() {
        userWithRisk = new UserBase();
        userWithRisk.setId(1L);
        userWithRisk.setRiskLevel((byte) 3);

        userWithoutRisk = new UserBase();
        userWithoutRisk.setId(2L);
        userWithoutRisk.setRiskLevel(null);
    }

    @Test
    @DisplayName("userId 为 null 直接返回 null，不查 DB")
    void null_userId_returns_null() {
        assertNull(internalUserService.getUserRiskLevel(null));
    }

    @Test
    @DisplayName("用户不存在 → 返回 null（未测评语义）")
    void user_not_exist_returns_null() {
        lenient().when(userBaseService.getById(999L)).thenReturn(null);
        assertNull(internalUserService.getUserRiskLevel(999L));
    }

    @Test
    @DisplayName("用户存在但未做风险测评 → 返回 null，老版本会 NPE 的同一路径")
    void user_without_risk_returns_null_not_npe() {
        when(userBaseService.getById(2L)).thenReturn(userWithoutRisk);
        assertNull(internalUserService.getUserRiskLevel(2L));
    }

    @Test
    @DisplayName("正常路径：返回 byte → int 转换正确")
    void normal_path_returns_int_value() {
        when(userBaseService.getById(1L)).thenReturn(userWithRisk);
        assertEquals(3, internalUserService.getUserRiskLevel(1L));
    }
}
