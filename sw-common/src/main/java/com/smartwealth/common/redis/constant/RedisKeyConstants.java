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
     * 银行卡信息和锁
     * 示例：sw:card:info:1234567890
     */
    public static final String CARD_INFO = "sw:card:info:%d:%d";
    public static final String CARD_LOCK = "sw:lock:card:%d:%d";

    // 在售产品列表缓存，格式：sw:product:onsale:list:page:{pageNo}:size:{pageSize}
    // 必须带上分页参数，否则翻页时数据永远是第一页的
    public static final String PRODUCT_ON_SALE_LIST = "sw:prod:onsale:list:page:%d:size:%d";

    // 单个产品详情缓存
    public static final String PRODUCT_DETAIL = "sw:prod:detail:%d";
    public static final String PRODUCT_HISTORY = "sw:prod:history:%d";
    public static final String PRODUCT_STOCK = "sw:prod:stock:%s";
    public static final String PRODUCT_LOCK = "sw:lock:prod:%s";
    // 市场情绪最新数据缓存
    public static final String KEY_MARKET_SENTIMENT_LATEST = "sw:market:sentiment:latest";
}