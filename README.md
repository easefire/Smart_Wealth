# Smart Wealth · 智能理财平台

> 基于 Spring Boot 3 + JDK 17 的金融理财模块化单体项目，覆盖产品、申购、赎回、收益结算、每日对账等核心业务场景。围绕**高并发申购**、**热点产品访问**与**海量收益结算**三大金融典型问题，从架构、缓存、消息、批处理、对账等维度做了系统性设计。

---

## 目录

- [一、项目简介](#一项目简介)
- [二、技术栈](#二技术栈)
- [三、模块化架构](#三模块化架构)
- [四、核心数据流与业务链路](#四核心数据流与业务链路)
- [五、核心技术亮点](#五核心技术亮点)
- [六、安全与鉴权](#六安全与鉴权)
- [七、定时任务（XXL-JOB）](#七定时任务xxl-job)
- [八、约定与规范](#八约定与规范)
- [九、本地启动指南](#九本地启动指南)
- [十、目录结构](#十目录结构)

---

## 一、项目简介

Smart Wealth 模拟真实的互联网理财平台，业务覆盖：

- **用户**：注册、登录、JWT 鉴权、KYC 实名、风险测评、银行卡管理、账户注销；
- **产品**：理财产品发布/下架、净值（NAV）每日演化、市场情绪剧本、布隆过滤器防穿透、多级缓存；
- **交易**：申购、赎回（FIFO 部分赎回、份额冻结、负向收益流水）、订单状态机；
- **资产**：用户钱包、资金流水、充值/提现、支付密码、悲观+乐观双锁动账；
- **财富**：用户总资产/持仓浮盈/已赎收益、管理员看板；
- **对账与体检**：每日资产对账（钱包余额 vs 流水快照）、份额守恒对账（流水买入份额 vs 业务端持有+赎回）、收益一致性体检（订单累计收益 vs 每日流水汇总）。

**架构选型**：模块化单体（Modular Monolith）。每个模块只能访问自己的数据库表；跨模块调用必须通过 SPI 接口（`sw-api`），保证数据耦合最小化，同时避免分布式部署在初期带来的运维负担。

---

## 二、技术栈

| 分层 | 选型 | 版本/说明 |
|---|---|---|
| 语言/运行时 | Java | 17 |
| Web 框架 | Spring Boot | 3.3.7 |
| 安全 | Spring Security + JWT (jjwt) | 自研 `JwtAuthenticationFilter` + `InternalServiceFilter` |
| ORM | MyBatis-Plus | 3.5.14（含乐观锁、分页插件） |
| 数据库 | MySQL | 8.x |
| 缓存 | Redis + Redisson + Caffeine | L1 本地 + L2 分布式 + 布隆过滤器、分布式锁、RTopic |
| 消息队列 | RabbitMQ | 含 ConfirmCallback、ReturnsCallback、死信交换机 |
| 定时任务 | XXL-JOB | 分片广播 + 线程池并行 |
| 接口文档 | Knife4j (OpenAPI 3) | `/doc.html` |
| 工具 | Hutool、Lombok、FastJSON、Jackson | — |
| 容器化（可选） | 本地部署 MySQL/Redis/RabbitMQ/XXL-JOB Admin | — |

---

## 三、模块化架构

```
smart_wealth (parent pom)
├── sw-common            # 公共组件：Result/Code、JWT、Redis、Redisson、RabbitMQ、ThreadPool、AOP、Security
├── sw-api               # 跨模块 SPI 契约层（只依赖 sw-common，被所有 sw-module-* 依赖）
├── sw-module-user       # 用户模块：注册、登录、KYC、风险测评、银行卡、管理员、审计日志
├── sw-module-product    # 产品模块：CRUD、市场情绪、每日净值、多级缓存、布隆过滤器、缓存预热
├── sw-module-trade      # 交易模块：申购、赎回、订单、每日收益结算、本地消息表
├── sw-module-asset      # 资产模块：钱包、流水、支付密码、充值、提现、对账
├── sw-module-wealth     # 财富聚合模块：总资产看板、持仓浮盈、已赎收益、平台看板
└── sw-web-start         # 应用启动入口（Spring Boot Application + application.yml）
```

### 3.1 SPI 契约层（`sw-api`）

`sw-api` 是跨模块调用的"对外业务能力"接口集，**屏蔽业务 entity/VO**，保证调用方只感知契约不感知实现。

| 接口 | 实现 | 暴露能力 |
|---|---|---|
| `InternalUserApi` | `InternalUserService` | `getUserRiskLevel` |
| `InternalProductApi` | `InternalProductService` | `lockStock` / `unlockStock` / `getProdNamesByIds` / `getProdNavMap` |
| `InternalAssetApi` | `InternalAssetService` | `getTotalWalletBalance` / `selectprelock` / `verifyPayPassword` / `deductForPurchase` / `processRedemption` |
| `InternalTradeApi` | `InternalTradeService` | `getUserPositionSummary` |

> **设计原则**：SPI 里只放"调用方在动账前真正需要的最小契约"。明细查询、按 entity/Wrapper 操作等仍保留在各模块 `Internal*Service` 内部（包内可见），避免 SPI 膨胀成"第二个 ServiceImpl"。

### 3.2 各业务模块结构（统一约定）

```
sw-module-xxx
└── src/main/java/com/smartwealth/xxx
    ├── controller             # 用户端入口
    │   └── admin              # 管理端入口（@PreAuthorize("hasRole('ADMIN')")）
    ├── service / service.impl # 业务服务（每个模块都有"门面 Service + 子领域 Service"两层）
    ├── mapper                 # MyBatis-Plus Mapper
    ├── entity                 # 数据库实体
    ├── dto                    # 入参 DTO（带校验）
    ├── vo                     # 出参 VO（脱敏）
    ├── enums                  # 业务枚举
    ├── event / listener       # 本地事件（@TransactionalEventListener AFTER_COMMIT）
    ├── mq.producer/consumer   # 消息生产/消费
    ├── job                    # XXL-JOB 任务
    └── runner                 # CommandLineRunner（如布隆过滤器预热）
```

> **重构原则**：所有 800 行以上的"上帝类"都被拆成 **"瘦门面 + 子领域 Service"** 模式。例如 `ProdInfoServiceImpl` 委派给 `ProductPageQueryService`/`ProductDetailQueryService`/`ProductNavUpdateService`/`ProductCacheWarmupService`；`TradeOrderServiceImpl` 委派给 `TradeQueryService`/`TradeSettlementService`/`Tradetransactionhelper`。

---

## 四、核心数据流与业务链路

### 4.1 申购链路（高频写入）

```
用户提交申购 (Controller @NoRepeatSubmit)
    │
    ▼
TradeOrderServiceImpl.purchase
    ├─ 校验用户风险等级 ≥ 产品风险等级       (InternalUserApi.getUserRiskLevel)
    ├─ 校验产品净值非空且 > 0
    ├─ 计算份额 = amount / currentNav  (scale=6, RoundingMode.DOWN 防超卖)
    │
    ├─ Redis Lua 原子扣库存                  (InternalProductApi.lockStock)
    │   - Lua 脚本: 先校验 EXISTS、再校验 stock ≥ num
    │   - Redis miss → 触发同步回填 → 重试一次
    │   - 库存不足 → 抛 BusinessException 拦截
    │
    ├─ Tradetransactionhelper.createOrderAndMessage  (@Transactional)
    │   ├─ 写订单 t_trade_order (status=PENDING)
    │   ├─ 写本地消息表 t_trade_local_msg (status=0)
    │   ├─ DB 层 update 锁库存 (CAS: total_stock - locked_stock ≥ qty)
    │   └─ 发布 ProdPurchaseEvent (Spring 本地事件)
    │
    └─ 失败兜底：Redis 库存 incrby 补偿；若 Redis 也挂 → 依赖定时任务修复
                                                          │
                                                          ▼
                                  @TransactionalEventListener AFTER_COMMIT
                                  TradeMessageListener → TradeMessageProducer.sendPurchaseMessage
                                                          │
                                                          ▼
                                  RabbitMQ ex.trade.purchase
                                                          │
                                                          ▼
                                  AssetInfoConsumer.onPurchaseMessage (Manual ACK)
                                  InternalAssetService.deductForPurchase
                                  ├─ 幂等校验 (按 orderId 查 PURC 流水)
                                  ├─ 钱包 FOR UPDATE 悲观锁 + version 乐观锁
                                  ├─ 余额校验 → 扣款 → 写流水 (flowNo = PURC+orderId)
                                  ├─ 写资产端本地消息表 (MSG_PURC_RES_orderId)
                                  └─ TransactionSynchronization.afterCommit
                                      └─ AssetResultProducer.sendResult (CorrelationData=msgId)
                                                          │
                                                          ▼
                                  RabbitMQ ex.trade.result
                                                          │
                                                          ▼
                                  TradeResultConsumer.onResult
                                  TradeOrderServiceImpl.handlePurchaseResult
                                  ├─ success → 状态置 HOLDING
                                  └─ fail    → 状态置 CLOSED + 回滚 Redis 库存（按 quantity，绝不按 amount）
```

**消息可靠性保障**（本地消息表 + ConfirmCallback 双轨制）：

- 业务事务里把消息**先落库**再发送；
- 仅当 broker `ack=true` 时（`RabbitmqConfirmConfig.confirm`），才把 `t_*_local_msg.status` 置为 1（成功）；
- 失败/网络抖动时，`AssetLocalMsgJobHandler` / `TradeLocalMsgJob` 每分钟扫一次 `status=0`，**指数退避（1→2→4→8→16 分钟）**，超过 5 次进入死信状态 (`status=2`) 等待人工干预；
- 消息发送时 `CorrelationData(msgId)` 透传，确保 ConfirmCallback 能精确定位本地消息行（资产端 `MSG_*` 前缀 vs 交易端业务命令前缀分流落库）。

### 4.2 赎回链路（FIFO 部分赎回）

```
用户提交赎回 (Controller @NoRepeatSubmit)
    │
    ▼
TradeOrderServiceImpl.redeemByProduct
    ├─ 支付密码校验  (InternalAssetService.verifyPayPassword，区分 4 种失败码)
    ├─ 提前查产品 (剥离 IO 出强事务)
    │
    ▼
Tradetransactionhelper.executeRedeemWithPessimisticLock (@Transactional)
    ├─ 钱包 FOR UPDATE 占行锁  (InternalAssetService.selectprelock)
    ├─ 锁后重读最新 nav         (避免事务前预读的"陈旧 nav")
    ├─ 查所有持仓订单 ORDER BY create_time ASC (FIFO)
    ├─ 校验可用份额 ≥ 待赎份额
    ├─ 循环按订单冻结 frozen_quantity，计算本金扣减 & 收益
    ├─ 写赎回流水 t_trade_redemption_record (status=APPLYING, freezeDetails=JSON)
    ├─ 批量更新订单 frozen_quantity
    ├─ 写本地消息表 → 发布 ProdRedeemEvent
    └─ 事务提交后 → MQ ex.trade.redemption → 资产模块
                                                          │
                                                          ▼
                              InternalAssetService.processRedemption
                              ├─ 幂等校验 (按 requestId 查 REDE-P 流水)
                              ├─ 加余额 (本金+收益)
                              ├─ 写两条流水：REDE-P-<requestId>(本金) + REDE-I-<requestId>(收益)
                              ├─ balance_snapshot 严格按落账顺序：afterPrincipal / afterAll
                              └─ 提交后发回执给交易端
                                                          │
                                                          ▼
                              TradeOrderServiceImpl.handleRedemptionResult
                              ├─ 流水悲观锁 + 状态防重 (APPLYING → SUCCESS / FAIL)
                              ├─ 按订单批量硬扣 quantity / amount / accumulated_income
                              ├─ 写负向收益流水 DailyProfit(type=2) 用于平账
                              ├─ 尾差终结者：份额相等时整笔清零，避免乘除法残留
                              └─ 全部清零的订单状态置 REDEEMED
```

### 4.3 收益结算链路（XXL-JOB 分片广播）

```
每天 02:00 dailySettlementJobHandler (XxlJob, 分片广播)
    │  分片参数: shardIndex / shardTotal
    ▼
TradeSettlementService.executeDailySettlementWithSharding
    ├─ 预计算每个产品当天的"每份额日收益单价" deltaNav = currentNav - oldNav
    │   oldNav = currentNav / (1 + rate)
    │
    └─ 游标分页拉取本分片下未结算订单 (lastId + LIMIT 5000)
         │
         └─ 切成 1000 条/批，提交 settlementThreadPool 并行处理
              │
              └─ SettlementTxHelper.doBatchSave (@Transactional)
                  ├─ 批量 insert t_trade_daily_profit
                  └─ 批量 update t_trade_order.accumulated_income (+= dailyProfit)
```

- **故意不在外层加 `@Transactional`**：批处理"按产品独立、按批次独立"，一批失败不能连累整批；
- 单批失败仅打错误日志（订单区间）等待人工/补偿补救，不会回滚已结算的批次；
- 分片广播 + 多节点 = 缩短结算时间 + 可靠性提升。

### 4.4 净值更新与缓存预热

```
每天凌晨 dailyMarketScenarioJob   → 演化今日"市场情绪"（5 档马尔可夫 + 黑天鹅）
                                       ↓
每天凌晨 dailyNavUpdateHandler    → ProductNavUpdateService.updateAllProductNav
                                   - 每个产品独立 small-tx (TransactionTemplate REQUIRES_NEW)
                                   - 使用 ThreadLocalRandom.nextGaussian() 计算个股波动
                                   - R1 走独立低波动正收益模型
                                   - 单产品失败只记日志、不影响其他产品
                                       ↓
                                   ProductCacheWarmupService.warmUpCacheAfterNavUpdate
                                   - 并行查 DB + 组 VO (warmupThreadPool)
                                   - Pipeline 批量写 Redis (TTL = 25h + 随机抖动 [0~1h]) 防雪崩
```

### 4.5 对账与体检

| 任务 | 检查项 | 报警 |
|---|---|---|
| `dailyAssetCheckJob` | `t_asset_wallet.balance` vs `t_asset_flow` 最新快照 | 不平账户写 WARN 日志 |
| `dailyTradeCheckJob` | `t_trade_order.accumulated_income` vs `t_trade_daily_profit` 汇总 | 不平订单写 WARN 日志 |
| `sharePoolCheckJob` | 流水买入份额 vs 业务端 (持有 + 已赎回) 份额 | 长款/短款/不平三种异常分别告警 |
| `assetMsgRetryJob` / `tradeMsgRetryJob` | 本地消息表 `status=0` 重发，5 次熔断 | 死信告警 |

---

## 五、核心技术亮点

### 5.1 高并发申购：Redis Lua 预扣减 + 本地消息表

> **JMeter 实测：单机约 3000 QPS。**

- 库存以**整数形式**写入 Redis（实际值 × 10⁶ 放大避免小数），用 Lua 脚本保证 `EXISTS + 比较 + 扣减 + 回写` 原子性；
- 同时在事务内通过 CAS SQL（`UPDATE t_prod_info SET locked_stock += ? WHERE total_stock - locked_stock >= ?`）防止 Redis 与 DB 漂移；
- 业务事务 + 本地消息表 + `@TransactionalEventListener(AFTER_COMMIT)` + RabbitMQ 实现"事务边界 + 可靠投递"；
- 兜底 XXL-JOB（指数退避、5 次熔断、死信状态）。

### 5.2 热点产品访问：多级缓存

针对产品详情接口 ——

- **静态数据**（产品基础信息、历史净值）：
  - **L1 Caffeine**（本地 1 分钟）→ **L2 Redis**（12h + 随机 60min 防雪崩）→ **DB**；
  - 缓存击穿用 Redisson 分布式锁 + DCL；
  - 缓存穿透用 **Redisson 布隆过滤器**（启动期 `ProductBloomFilterRunner` 预热全量在售产品 ID）+ 空对象哨兵（30s）；
- **动态数据**（产品库存）：
  - 极短 TTL，请求时**直接走 Redis**（Lua 维护）；
  - 列表页通过 `multiGetStocks(ids)` **批量拿库存**避免 N+1；
  - 缺失自动 `syncProductStockToRedis` 回填（Redisson 锁防 stampede）；
- **下架触发**：通过 Redisson `RTopic` 广播，所有节点 invalidate 本机 Caffeine。

### 5.3 每日海量收益结算：XXL-JOB 分片 + 线程池并行 + 缓存预热

- XXL-JOB 分片广播策略，按 `shardIndex/shardTotal` 水平拆分用户/订单（`selectUnsettledOrders` 按分片过滤）；
- 单节点内 `settlementThreadPool`（`core+1 ~ core*2`, 5000 容量, `CallerRunsPolicy`）并行处理 1000 条/批；
- 每批独立事务（`SettlementTxHelper`）—— 失败不连累；
- 结算完成后立即执行 `ProductCacheWarmupService` **缓存预热**，Pipeline 批量写 Redis（TTL=25h+随机抖动），同时校准 MySQL 与 Redis 库存的数据漂移。

### 5.4 双锁动账（悲观 + 乐观）

- 钱包动账先 `SELECT ... FOR UPDATE`（悲观锁，挡跨事务并发）；
- 再用 `UPDATE ... WHERE version = ?`（乐观锁，挡同事务/重试冲突）；
- 影响行数为 0 时显式抛业务异常，避免"余额没动但流水写了"的脏账。

### 5.5 资金流水可追溯

- `flowNo` 命名规范：`PURC<orderId>` / `REDE-P-<requestId>` / `REDE-I-<requestId>` / `RECH<id>` / `WITH<id>`；
- 每条流水带 `balance_snapshot`（落账后余额）—— 对账任务可直接对比；
- 赎回拆"本金 + 收益"两条流水，且 `balance_snapshot` 严格按落账顺序计算；
- 写流水时 `DuplicateKeyException` 视为幂等成功，避免无限重试。

### 5.6 跨模块隔离与事件解耦

- 跨模块查询：必须走 `sw-api` SPI（不依赖对方 entity）；
- 跨模块通知：用 **Spring 本地事件 + `@TransactionalEventListener(AFTER_COMMIT)`**
  - 用户注册 → 资产模块异步初始化钱包；
  - 用户注销 → 交易模块校验 HOLDING/PENDING 订单；交易模块 + 资产模块联合校验通过才能软删；
  - 申购/赎回事务提交后 → 投 MQ（确保 MQ 永远不会比 DB 先发生）。

---

## 六、安全与鉴权

### 6.1 鉴权链路

```
请求
 ├─ InternalServiceFilter   （/sw/system/** 内部接口：IP 白名单 → 注入 ROLE_INTERNAL_SERVICE）
 ├─ JwtAuthenticationFilter （/sw/user/** , /sw/admin/** : Bearer Token → Redis 二次校验 → ROLE_USER/ROLE_ADMIN）
 └─ Spring Security authorizeHttpRequests
     ├─ /doc.html, /v3/api-docs/**, /sw/user/auth/** → permitAll
     ├─ /sw/system/**                                → hasRole('INTERNAL_SERVICE')
     └─ 其他                                          → authenticated
```

### 6.2 JWT

- HS256，密钥从 `smartwealth.security.jwt-secret` 注入（生产强制环境变量覆盖）；
- 默认 7 天过期；Redis 双轨校验 `sw:user:token:%s` / `sw:admin:token:%s`，登出时主动失效；
- 剩余 TTL < 15 分钟自动续期到 30 分钟。

### 6.3 敏感数据

- 银行卡号：Hutool **AES** 加密入库（密钥从 `smartwealth.security.aes-key` 注入，启动校验长度），查询时解密并 `StrUtil.hide` 脱敏；
- 用户密码 / 支付密码：**BCrypt**；
- 支付密码校验失败抛 `BusinessException` 区分三种语义：`WALLET_NOT_EXIST` / `PAY_PASSWORD_NOT_SET` / `PAYMENT_PASSWORD_ERROR`（避免引导用户去试错根本没设置过的密码）。

### 6.4 防重复提交

- `@NoRepeatSubmit(lockTime=3)` 切面 + Redis `SETNX`：以 `Token + URI + 参数 MD5` 作为指纹 Key；
- 默认申购、赎回都加了 3 秒锁。

### 6.5 审计日志

- `@LogAudit(module, operation)` 切面 + `ApplicationEvent`；
- `AuditLogListener` `@Async` 异步落库，不阻塞主流程。

### 6.6 统一异常处理

- `GlobalExceptionHandler` 拦截 `BusinessException` / `MethodArgumentNotValidException` / 其他 → 统一 `Result` 返回；
- 业务异常 WARN，系统异常 ERROR，参数错误带具体字段提示。

---

## 七、定时任务（XXL-JOB）

| Handler | 频率 | 作用 |
|---|---|---|
| `dailyMarketScenarioJob` | 每日凌晨 | 演化今日市场情绪（用于次日净值算法） |
| `dailyNavUpdateHandler` | 每日凌晨 | 更新所有运行中产品净值 + 缓存预热 |
| `dailySettlementJobHandler` | 每日 02:00 | **分片广播**结算昨日所有持仓订单收益 |
| `dailyAssetCheckJob` | 每日 03:00 | 钱包余额 vs 流水快照对账 |
| `dailyTradeCheckJob` | 每日 04:30 | 订单累计收益 vs 流水汇总对账 |
| `sharePoolCheckJob` | 每日 | 流水买入份额 vs 业务端持有+赎回份额对账 |
| `assetMsgRetryJob` | 每分钟 | 资产端本地消息补发（指数退避） |
| `tradeMsgRetryJob` | 每分钟 | 交易端本地消息补发（指数退避） |

> 调度中心地址：`http://127.0.0.1:8098/xxl-job-admin`（见 `application.yml`）。
> 执行器 appname：`smart-wealth-executor`。

---

## 八、约定与规范

### 8.1 ResultCode 分段

```
200      操作成功
400-499  通用客户端错误
500      系统通用错误
1000+    用户模块
2000+    产品模块
3000+    交易模块
4000+    资金模块
5000+    管理员/系统模块
6000+    AI 模块（预留）
```

### 8.2 URI 命名

```
/sw/user/**         用户端接口（要求 ROLE_USER）
/sw/admin/**        管理端接口（要求 ROLE_ADMIN）
/sw/system/**       内部接口（要求 ROLE_INTERNAL_SERVICE，IP 白名单准入）
/sw/user/auth/**    免鉴权（注册/登录）
```

### 8.3 Redis Key 规范（`RedisKeyConstants`）

```
sw:user:token:%s            用户登录 Token
sw:admin:token:%s           管理员登录 Token
sw:repeat:submit:           防重提交前缀
sw:card:info:%d:%d          银行卡缓存
sw:lock:card:%d:%d          银行卡操作锁
sw:prod:onsale:list:page:%d:size:%d   产品在售列表分页缓存
sw:prod:detail:%d           产品详情缓存
sw:prod:history:%d          产品历史净值缓存
sw:prod:stock:%s            产品库存（Lua 维护）
sw:lock:prod:%s             产品操作锁
sw:lock:prodlist:%s         产品列表读锁
sw:market:sentiment:latest  最新市场情绪
```

### 8.4 MQ 拓扑（`RabbitConfig`）

```
ex.trade.purchase   ──rk.trade.purchase──▶  q.trade.purchase   (申购)
ex.trade.redemption ──rk.trade.redemption──▶ q.trade.redemption (赎回)
ex.trade.result     ──rk.trade.result──▶     q.trade.result     (扣款/入账回执)
ex.dlx              ──rk.dlx──▶              q.dlx              (死信)
```

- 消费端**强制手动 ACK** (`acknowledge-mode: manual`)；
- 业务失败 → ACK + 落 FAIL 消息回执（避免 MQ 无限重试）；
- 系统异常 → NACK + requeue（让 MQ 重试 → 重试 3 次后入死信）；
- 重试退避：初始 2s × 2.0 倍率，最大 10s，最多 3 次（spring 内存重试）。

### 8.5 模块依赖关系（严格）

```
sw-common  ──(全部依赖)──▶  其他全部
sw-api     ──依赖──▶        sw-common
sw-module-*──依赖──▶        sw-api + sw-common
sw-web-start──依赖──▶       全部 sw-module-*
```

> **禁止跨模块直接 import 对方 entity / mapper / VO**。
> 跨模块取数据请走 `sw-api` SPI，或调用对方模块的 `Internal*Service` 内部读模型方法。

---

## 九、本地启动指南

### 9.1 环境依赖

| 中间件 | 默认端口 | 备注 |
|---|---|---|
| MySQL 8.x | 3306 | 数据库 `smart_wealth_db` |
| Redis | 6379 | 默认 DB 0 |
| RabbitMQ | 5672 / 15672 | 默认 guest/guest |
| XXL-JOB Admin | 8098 | `http://127.0.0.1:8098/xxl-job-admin` |
| 应用本身 | 8099 | — |
| XXL-JOB Executor 内嵌端口 | 8999 | — |

### 9.2 必备环境变量（生产）

```bash
export SMARTWEALTH_JWT_SECRET="<至少 32 字节强随机字符串>"
export SMARTWEALTH_AES_KEY="<必须为 16/24/32 字节>"
export SMARTWEALTH_JWT_EXPIRE_MS=604800000
```

> 开发环境可使用 `application.yml` 中带 `CHANGE_ME_` 前缀的默认值，启动时会打 WARN 提醒。

### 9.3 启动

```bash
# 1. 拉起 MySQL / Redis / RabbitMQ / XXL-JOB Admin（自行准备 docker-compose 或本地服务）

# 2. 创建数据库（schema 文件自行准备/导入；表名约定见 entity）
mysql -u root -p -e "CREATE DATABASE smart_wealth_db DEFAULT CHARSET=utf8mb4;"

# 3. 编译
mvn -DskipTests clean package

# 4. 运行
mvn -pl sw-web-start spring-boot:run
# 或
java -jar sw-web-start/target/sw-web-start-1.0-SNAPSHOT.jar

# 5. 打开接口文档
open http://localhost:8099/doc.html
```

### 9.4 测试

`sw-web-start/src/test/` 下包含针对核心组件的单元测试，例如：

- `RedisServiceImplStockTest` —— Lua 库存原子性；
- `JwtUtilsTest` —— Token 生成/解析/过期；
- `NavAlgorithmUtilsTest` —— 净值算法多线程安全；
- `TradeOrderPurchasePreCheckTest` —— 申购预检（NPE / 风险等级 / 净值异常）；
- `AssetWalletServicePayPasswordTest` / `InternalAssetServicePayPasswordTest` —— 支付密码语义；
- `ResultCodeContractTest` —— ResultCode 与前端契约；
- `InternalUserServiceTest` —— 用户风险评级 null 语义。

```bash
mvn -pl sw-web-start test
```

---

## 十、目录结构

```
.
├── pom.xml                                # 根 POM（聚合 + dependencyManagement + lombok 注解处理器）
├── README.md
├── sw-common                              # 公共组件
│   └── src/main/java/com/smartwealth/common
│       ├── annotations/                   # @NoRepeatSubmit, @LogAudit
│       ├── aspect/                        # 防重提交、审计日志 AOP
│       ├── configuration/                 # Security / Rabbit / Mybatis / Jackson / ThreadPool / XxlJob / OpenApi
│       ├── context/UserContext.java       # 当前请求用户上下文（ThreadLocal）
│       ├── dto/                           # 跨模块消息 DTO（PurchaseMessageDTO 等）
│       ├── entity/BaseLocalMessage.java   # 本地消息表抽象基类
│       ├── event/AuditLogEvent.java
│       ├── exception/                     # BusinessException + GlobalExceptionHandler
│       ├── filter/                        # JwtAuthenticationFilter + InternalServiceFilter
│       ├── handler/MyMetaObjectHandler.java
│       ├── redis/                         # RedisService（含 Lua）+ Redisson 配置
│       ├── result/                        # Result + ResultCode
│       └── util/JwtUtils.java
├── sw-api                                 # 跨模块 SPI
│   └── ...InternalAssetApi / InternalProductApi / InternalTradeApi / InternalUserApi
├── sw-module-user                         # 用户模块
├── sw-module-product                      # 产品模块（含布隆过滤器、多级缓存、净值算法）
├── sw-module-asset                        # 资产模块（钱包、流水、双锁动账、消息队列）
├── sw-module-trade                        # 交易模块（申购/赎回、订单状态机、结算、对账）
├── sw-module-wealth                       # 财富聚合模块（看板、浮盈、已赎收益）
└── sw-web-start                           # 启动入口
    ├── src/main/java/com/smartwealth/swwebstart/SwWebStartApplication.java
    └── src/main/resources/application.yml
```

---

## 十一、关键设计取舍速查

| 决策 | 原因 |
|---|---|
| 模块化单体而非微服务 | 减少初期运维负担；通过 `sw-api` SPI 留好未来拆分的"接缝" |
| Redis Lua 而非 Redisson Counter | 一次 RTT 内完成"判存+判量+扣减+回写"，QPS 与边界条件可控 |
| 库存放大 10⁶ 倍存 long | Lua 不能直接处理 `BigDecimal`，整数化保证精度并避免浮点误差 |
| 本地消息表 + ConfirmCallback | 业务事务和投递行为完全解耦，broker 真正落盘才置成功 |
| `@TransactionalEventListener(AFTER_COMMIT)` | 保证 MQ 永远不会在 DB 失败时发送 |
| 申购份额 `RoundingMode.DOWN` | "宁可少分、不可超扣"，防止库存被放大 |
| 赎回拆"本金/收益"两条流水 | flowNo 唯一索引；balance_snapshot 严格按落账顺序，便于对账 |
| 净值更新单产品 small-tx | A 产品异常不连累 B 产品；避免大事务长持锁 |
| XXL-JOB 分片广播 + 线程池 | 缩短结算时间 + 节点可靠性提升 |
| 布隆过滤器防穿透 | 1%误判率 + 启动期预热全量在售 ID，挡掉 99% 非法 ID 请求 |
| Caffeine + Redis 二级 | 本地极短 TTL 减少 Redis 网络压力，Redis 是真正的"权威读源" |
| 缓存预热 Pipeline + 随机 TTL | 一次 RTT 写 N 个 key；TTL 抖动彻底打散失效时间防雪崩 |
| 支付密码错误区分 3 个码 | 安全 & 体验双赢，避免引导用户试错不存在的密码 |
| 用户风险等级未测评返回 null | 显式判断，避免 NPE；区分 `0` 与 `未测` 两种语义 |
| `ThreadLocalRandom.nextGaussian` | 多线程并发 Job 下零竞争、相同分布，规避 `Random` AtomicLong CAS |
| `ObjectProvider` 解循环依赖 | `Tradetransactionhelper` 与 `ITradeOrderService` 互相持有时按需解析 |

---

> 项目 Author: **Fire** · 文档持续完善中。
