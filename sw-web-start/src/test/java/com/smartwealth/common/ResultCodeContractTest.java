package com.smartwealth.common;

import com.smartwealth.common.result.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误码契约测试。
 * <p>
 * 这套断言不是"测算法对不对"，而是"测有没有人不小心改了码值"。
 * 前端、监控大盘、风控规则都把这些数字当合同，
 * 一旦有人手抖把 4002 改成 4012，可能要等告警触发才发现。
 * <p>
 * 规则：
 *   - 已上线的关键码值<strong>不允许变动</strong>，需要新码就追加；
 *   - 不允许有重复 code（避免业务里返回了"4001"用户却看到不相干的提示）。
 */
class ResultCodeContractTest {

    @Test
    @DisplayName("关键码值锁死：被前端/外部依赖的常用码")
    void critical_codes_must_not_change() {
        // 通用
        assertEquals(200, ResultCode.SUCCESS.getCode());
        assertEquals(401, ResultCode.UNAUTHORIZED.getCode());
        assertEquals(403, ResultCode.FORBIDDEN.getCode());
        assertEquals(409, ResultCode.REPEAT_SUBMIT.getCode());

        // 用户
        assertEquals(1004, ResultCode.PASSWORD_ERROR.getCode());
        assertEquals(1010, ResultCode.RISK_EVAL_NEEDED.getCode());

        // 交易
        assertEquals(3003, ResultCode.RISK_LEVEL_MISMATCH.getCode());
        assertEquals(3007, ResultCode.PAYMENT_PASSWORD_ERROR.getCode());
        assertEquals(3004, ResultCode.HOLDING_NOT_ENOUGH.getCode());

        // 资金
        assertEquals(4001, ResultCode.WALLET_NOT_EXIST.getCode());
        assertEquals(4002, ResultCode.PAY_PASSWORD_NOT_SET.getCode());
        assertEquals(4003, ResultCode.BALANCE_NOT_ENOUGH.getCode());
    }

    @Test
    @DisplayName("ResultCode 的所有 code 必须唯一")
    void no_duplicated_codes() {
        Set<Integer> seen = new HashSet<>();
        for (ResultCode rc : ResultCode.values()) {
            assertTrue(seen.add(rc.getCode()),
                    "重复的 code: " + rc.getCode() + " 出现在 " + rc.name());
        }
    }
}
