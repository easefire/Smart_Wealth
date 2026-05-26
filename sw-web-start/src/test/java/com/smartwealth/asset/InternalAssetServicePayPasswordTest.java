package com.smartwealth.asset;

import com.smartwealth.asset.entity.AssetWallet;
import com.smartwealth.asset.mapper.AssetWalletMapper;
import com.smartwealth.asset.service.impl.InternalAssetService;
import com.smartwealth.common.exception.BusinessException;
import com.smartwealth.common.result.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * 覆盖错误码梳理后的 verifyPayPassword 三种失败必须区分：
 *   钱包不存在        → WALLET_NOT_EXIST(4001)
 *   钱包存在但没设密码 → PAY_PASSWORD_NOT_SET(4002)
 *   密码不匹配        → PAYMENT_PASSWORD_ERROR(3007)
 *
 * 老版本一律返回 false，前端只能笼统报"密码错误"，引导用户去试一个根本不存在的密码。
 */
@ExtendWith(MockitoExtension.class)
class InternalAssetServicePayPasswordTest {

    @Mock
    private AssetWalletMapper walletMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private InternalAssetService service;

    @Test
    @DisplayName("入参为 null → 视为密码错误，避免被攻击者用空串绕过")
    void null_input_treated_as_password_error() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyPayPassword(1L, null));
        assertEquals(ResultCode.PAYMENT_PASSWORD_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("钱包不存在 → WALLET_NOT_EXIST，不要 leak 出'密码错误'误导用户")
    void wallet_not_exist() {
        when(walletMapper.getWalletByUserId(1L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyPayPassword(1L, "any"));
        assertEquals(ResultCode.WALLET_NOT_EXIST.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("钱包在但 payPassword 未设置 → PAY_PASSWORD_NOT_SET")
    void pay_password_not_set() {
        AssetWallet w = new AssetWallet();
        w.setUserId(1L);
        w.setPayPassword(null);
        when(walletMapper.getWalletByUserId(1L)).thenReturn(w);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyPayPassword(1L, "any"));
        assertEquals(ResultCode.PAY_PASSWORD_NOT_SET.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("密码不匹配 → PAYMENT_PASSWORD_ERROR")
    void password_mismatch() {
        AssetWallet w = new AssetWallet();
        w.setPayPassword("$2a$mocked");
        when(walletMapper.getWalletByUserId(1L)).thenReturn(w);
        when(passwordEncoder.matches("wrong", "$2a$mocked")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyPayPassword(1L, "wrong"));
        assertEquals(ResultCode.PAYMENT_PASSWORD_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("密码匹配 → 正常返回（无异常）")
    void password_match() {
        AssetWallet w = new AssetWallet();
        w.setPayPassword("$2a$mocked");
        when(walletMapper.getWalletByUserId(1L)).thenReturn(w);
        when(passwordEncoder.matches("right", "$2a$mocked")).thenReturn(true);

        assertDoesNotThrow(() -> service.verifyPayPassword(1L, "right"));
    }
}
