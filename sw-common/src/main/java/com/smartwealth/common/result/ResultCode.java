package com.smartwealth.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局统一返回结果枚举
 * 规定：
 * 200: 成功
 * 400-499: 客户端参数/权限问题
 * 500: 系统通用错误
 * 1000-1999: 用户模块 (User)
 * 2000-2999: 产品模块 (Product)
 * 3000-3999: 交易模块 (Trade)
 * 4000-4999: 资金模块 (Asset)
 * 5000-5999: 管理员/系统模块 (Admin/System)
 * 6000-6999: AI 模块
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    // --- 🟢 通用状态 ---
    SUCCESS(200, "操作成功"),
    FAILURE(500, "系统繁忙，请稍后重试"),
    PARAM_ERROR(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或Token已过期"),
    FORBIDDEN(403, "无权访问此资源"),
    REPEAT_SUBMIT(409, "请勿重复提交"),

    // --- 👤 用户模块 (1000+) ---
    // 注册登录相关
    USER_NOT_EXIST(1001, "用户不存在"),
    USER_ALREADY_EXIST(1002, "该手机号已注册"),
    PASSWORD_ERROR(1003, "账号或密码错误"),
    VERIFY_CODE_ERROR(1004, "验证码错误或已过期"),

    // 冻结逻辑
    USER_FROZEN(1005, "账号已被冻结，请联系客服"),

    // 实名认证 (KYC)
    USER_NOT_KYC(1006, "请先完成实名认证"),
    KYC_ALREADY_DONE(1007, "您已完成实名认证，无需重复提交"),
    KYC_FAIL(1008, "实名认证失败，身份证信息有误"),

    // 风险测评
    RISK_EVAL_NEEDED(1009, "请先完成风险测评"),

    // --- 📈 产品模块 (2000+) ---
    // 产品浏览与上下架
    PRODUCT_NOT_EXIST(2001, "理财产品不存在"),
    PRODUCT_OFF_SHELF(2002, "该产品已下架"),
    PRODUCT_NOT_START(2003, "该产品尚未开始募集"),
    PRODUCT_SOLD_OUT(2004, "手慢了，该产品份额已售罄"),

    // --- 🤝 交易模块 (3000+) ---
    // 买入逻辑
    ORDER_NOT_EXIST(3001, "订单不存在"),
    ORDER_AMOUNT_INVALID(3002, "起购金额不足或超过限额"),

    // 核心风控：激进型产品不能卖给保守型用户
    RISK_LEVEL_MISMATCH(3003, "您的风险承受能力不足以购买此高风险产品"),

    // 赎回逻辑
    HOLDING_NOT_ENOUGH(3004, "可用持仓份额不足"),
    ORDER_STATUS_ERROR(3005, "订单状态异常，无法执行此操作"),
    CANNOT_REDEEM_LOCKED(3006, "产品处于封闭期，暂不可赎回"),

    // --- 💰 资金模块 (4000+) ---
    // 充值提现
    BALANCE_NOT_ENOUGH(4001, "钱包余额不足"),
    BANK_CARD_NOT_BIND(4002, "请先绑定银行卡"), //
    BANK_CARD_ERROR(4003, "银行卡信息无效"),
    DEPOSIT_FAIL(4004, "充值失败，银行端扣款异常"),
    WITHDRAW_FAIL(4005, "提现失败，请检查账户状态"),

    // --- 👮 管理员/系统模块 (5000+) ---
    // 管理员登录
    ADMIN_NOT_EXIST(5001, "管理员账户不存在"),
    ADMIN_FROZEN(5002, "管理员账户已被禁用"),

    // 市场剧本
    MARKET_SCRIPT_EXECUTE_FAIL(5003, "市场剧本触发失败"),

    // 强制关单
    FORCE_CLOSE_FAIL(5004, "强制关单失败"),

    // --- 🤖 AI 模块 (6000+) ---
    //
    AI_SERVICE_BUSY(6001, "AI助手正在思考中，请稍后再试"),
    AI_QUOTA_EXCEEDED(6002, "今日AI对话次数已达上限");

    private final Integer code;
    private final String message;
}