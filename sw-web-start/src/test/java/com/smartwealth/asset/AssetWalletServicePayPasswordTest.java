package com.smartwealth.asset;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smartwealth.asset.entity.AssetWallet;
import com.smartwealth.asset.mapper.AssetWalletMapper;
import com.smartwealth.asset.service.impl.AssetWalletServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 P1-#15：setPayPassword 不允许"算两次 encode"。
 *
 * 老代码：
 *   String encryptedPwd = passwordEncoder.encode(password); // 算了一次但没用
 *   ...set(payPassword, passwordEncoder.encode(password))   // 又算了一次
 *
 * 由于 BCrypt 自带 salt，两次结果不同，浪费 CPU 之外更糟糕的是阅读者会迷惑。
 * 这里要锁定的契约：encode 只能被调一次。
 */
@ExtendWith(MockitoExtension.class)
class AssetWalletServicePayPasswordTest {

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AssetWalletServiceImpl walletService;

    @Test
    @DisplayName("setPayPassword 只调用一次 passwordEncoder.encode")
    void encode_called_only_once() {
        when(passwordEncoder.encode("123456")).thenReturn("$2a$mocked");

        // setPayPassword 内部会调 this.update(wrapper)，
        // ServiceImpl.update(wrapper) 实际走 baseMapper，
        // 这里我们只关心 encode 的调用次数。
        // baseMapper 在 InjectMocks 注入下默认是 null，会让 update 抛 NPE，
        // 但调用次数验证可以在抛 NPE 之前观察到，所以包一层 try。
        try {
            walletService.setPayPassword(1L, "123456");
        } catch (Throwable ignored) {
            // baseMapper 缺失导致的链式 NPE 不影响我们要验证的行为
        }

        verify(passwordEncoder, times(1)).encode("123456");
    }

    @Test
    @DisplayName("encode 结果被真正写入更新条件（不是空写）")
    void encoded_value_actually_written() {
        when(passwordEncoder.encode("plain")).thenReturn("$2a$encoded");

        AssetWalletMapper mapperSpy = Mockito.mock(AssetWalletMapper.class);
        // 反射注入 baseMapper（ServiceImpl 内部字段名 = baseMapper）
        try {
            java.lang.reflect.Field baseMapperField =
                    com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                            .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(walletService, mapperSpy);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

        when(mapperSpy.update(isNull(), any())).thenReturn(1);

        walletService.setPayPassword(7L, "plain");

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapperSpy).update(isNull(), wrapperCaptor.capture());

        // LambdaUpdateWrapper 的 sqlSet 是 "pay_password=#{ew.paramNameValuePairs.MPGENVAL...}"，
        // 真正的值在 paramNameValuePairs 里。该方法定义在 AbstractWrapper 上，需要向下转型。
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue();
        boolean encodedValuePresent = wrapper.getParamNameValuePairs()
                .values().stream()
                .anyMatch(v -> "$2a$encoded".equals(String.valueOf(v)));
        assertEquals(true, encodedValuePresent,
                "encode 出来的密文必须出现在 wrapper 参数里：" + wrapper.getParamNameValuePairs());
    }
}
