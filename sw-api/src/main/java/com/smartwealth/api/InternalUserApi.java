package com.smartwealth.api;

/**
 * 用户模块对外 SPI 契约。
 *
 * <p>这里只暴露 trade / asset 模块在动账前真正需要的"用户级断言"。
 * 用户的明细（手机号、KYC、银行卡等）一律不通过 SPI 暴露——那些是用户模块自己的内政。
 *
 * <p><strong>实现类</strong>：{@code com.smartwealth.user.service.impl.InternalUserService}（@Service）。
 */
public interface InternalUserApi {

    /**
     * 取用户的风险评级（R1~R5）。
     *
     * <p>语义：
     * <ul>
     *   <li>{@code null} → 用户未做风险测评（不能购买）；</li>
     *   <li>1~5 → 已测评结果，可与产品 risk-level 比较是否合规。</li>
     * </ul>
     * 调用方必须用 {@code == null} 显式判断，否则会重蹈 BUGFIX-#20 之前 NPE 的覆辙。
     */
    Integer getUserRiskLevel(Long userId);
}
