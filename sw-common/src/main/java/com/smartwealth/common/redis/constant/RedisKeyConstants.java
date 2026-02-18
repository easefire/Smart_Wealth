package com.smartwealth.common.redis.constant;

/**
 * Redis Key 常量类
 */
public class RedisKeyConstants {

    // 命名规范：项目:模块:功能:ID

    /**
     * 用户登录 Token
     * 示例：sw:user:token:1001
     */
    public static final String USER_TOKEN = "sw:user:token:%s";

    /**
     * 管理员登录 Token
     * 示例：sw:admin:token:1001
     */
    public static final String ADMIN_TOKEN = "sw:admin:token:%s";
    /**
     * 防重提交 Key 前缀
     * 示例：sw:repeat:submit:
     */
    public static final String REPEAT_SUBMIT = "sw:repeat:submit:";
    /**
     * 银行卡信息
     * 示例：sw:card:info:1001:6222020200044444444
     */
    public static final String CARD_INFO = "sw:card:info:%d:%d";
    /**
     * 银行卡操作锁
     * 示例：sw:lock:card:1001:6222020200044444444
     */
    public static final String CARD_LOCK = "sw:lock:card:%d:%d";

    /**
     * 产品在售列表缓存
     * 示例：sw:prod:onsale:list:page:1:size:20
     */
    public static final String PRODUCT_ON_SALE_LIST = "sw:prod:onsale:list:page:%d:size:%d";

    /**
     * 产品详情缓存
     * 示例：sw:prod:detail:1001
     */
    public static final String PRODUCT_DETAIL = "sw:prod:detail:%d";
    /**
     * 产品历史收益率缓存
     * 示例：sw:prod:history:1001
     */
    public static final String PRODUCT_HISTORY = "sw:prod:history:%d";
    /**
     * 产品库存缓存
     * 示例：sw:prod:stock:1001
     */
    public static final String PRODUCT_STOCK = "sw:prod:stock:%s";
    /**
     * 产品操作锁
     * 示例：sw:lock:prod:1001
     */
    public static final String PRODUCT_LOCK = "sw:lock:prod:%s";
    /**
     * 最新市场情绪数据缓存
     * 示例：sw:market:sentiment:latest
     */
    public static final String KEY_MARKET_SENTIMENT_LATEST = "sw:market:sentiment:latest";
    /**
     * 产品列表读取分布式锁
     */
    public static final String PRODLIST_LOCK = "sw:lock:prodlist:%s";
}