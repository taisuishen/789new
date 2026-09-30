# 789bingo 游戏聚合平台（微服务骨架）

持牌合规运营的线上游戏聚合平台。以**单一钱包（Seamless）**为主线，同时支持**转账钱包（Transfer）**厂商。
KYC、反洗钱（AML）、负责任博彩（RG）从架构初期就放在用户服务、钱包和风控服务里，而不是后期再补。

> 状态：骨架阶段。核心链路（钱包、厂商回调、转账钱包）已按设计实现；外围服务实现了主流程，细节以 `TODO:` 标注。
> 用户线路（user_line）、影子账户、稽核服务（bingo-turnover）、通用活动表、KYC（bingo-kyc、bingo-common-obs）这一轮的改动**尚未编译**，最后统一构建时一起修正。

## 技术栈

| 类别 | 选型 | 说明 |
|---|---|---|
| 基础 | JDK 25 LTS、Spring Boot **4.0.8**、Spring Cloud 2025.1.3、Spring Cloud Alibaba 2025.1.0.0 | Spring Cloud Alibaba 目前只有 2025.1（对应 Boot 4.0）是正式版，面向 Boot 3.5 的只有 preview，所以没有用原文写的 Boot 3.x |
| 注册/配置 | Nacos 3 | 分组 `BINGO`，data id `bingo-common.yaml` + `<服务名>.yaml` |
| 服务调用 | OpenFeign + LoadBalancer | API 模块中的接口同时被 Controller 实现、被 Feign 客户端继承 |
| 数据访问 | MyBatis-Plus 3.5.17（boot4 starter）、HikariCP、自研按用户分库路由（`bingo.shard.*`，1024 逻辑分片） | 金额统一 `BigDecimal` / `DECIMAL(20,4)` |
| 数据库 | TaurusDB（本地用 MySQL 8.4） | 钱包独立实例，所有语句带 `/*FORCE_MASTER*/` Hint |
| 缓存 | DCS(Redis) + Redisson、Caffeine | Redis 存会话、限流、等候室计数；钱包不用 Redis |
| 消息 | DMS Kafka：数据流 + 业务事件（事务 outbox 发送，失败转 `<topic>.DLT`） | 只有一种 MQ |
| 调度 | XXL-Job 3.4.2 | |
| 限流熔断 | Sentinel（回调入站按厂商/玩家限流）、Resilience4j（出站舱壁 + 熔断） | |
| 可观测 | Micrometer + Prometheus、OpenTelemetry Java Agent、LTS | |
| 分析 | 一套 StarRocks（华为云 CloudTable StarRocks），Routine Load 直接消费现有 Kafka 事件流 | 只给后台报表、日终逐笔对账、风控分析用；玩家查询不走 OLAP，走 TaurusDB 只读节点 |

## 模块与端口

```
789bingo
├── bingo-common                    公共模块（均为自动配置，引入即生效）
│   ├── bingo-common-core           Result / ErrorCode / Money / 雪花ID / JsonUtils(Jackson 3) / HMAC / UserLine、LineScope / GameType、TurnoverScope
│   ├── bingo-common-web            TraceId、CurrentUser、全局异常处理（Servlet）
│   ├── bingo-common-mybatis        分页、TaurusDB 强制主库 Hint、雪花 ID 生成器、唯一键冲突识别
│   ├── bingo-common-redis          Spring Data Redis + Redisson
│   ├── bingo-common-mq             事件目录、Kafka 事务 outbox、业务事件监听工厂（重试 + DLT）
│   ├── bingo-common-job            XXL-Job 执行器
│   └── bingo-common-obs            华为 OBS：图片上传（按文件头识别格式）、签名 URL、URL ↔ 对象 key
├── bingo-gateway            8080   玩家侧网关：会话鉴权、地域限制、限流（不承载厂商回调）
├── bingo-user               8101   注册登录、KYC 状态、负责任博彩、游戏 token、用户线路迁移与影子账户、上级代理与注册渠道
├── bingo-wallet             8102   ★ 钱包：余额、扣款、加款、流水（独立部署、独立库）
├── bingo-game-integration   8103   ★ 厂商适配、回调入口、游戏启动、转账钱包（独立集群）
├── bingo-lobby              8104   游戏列表、分类、维护开关、启动前校验
├── bingo-bet-record         8105   注单（按月分区）、厂商注单拉取、超时未结算处理
├── bingo-payment            8106   充值提现、支付渠道 SPI
├── bingo-risk               8107   提现审核、AML 预警
├── bingo-promotion          8108   通用活动表（按线路可见）、返水、首存奖励
├── bingo-reconcile          8109   三层对账、GGR、厂商结算、RTP 监控
├── bingo-turnover           8110   ★ 稽核（流水要求）：多稽核桶、按游戏 / 游戏类型 / 全类型扣减，按玩家分库
├── bingo-kyc                8111   KYC：证件 / 自拍上传 OBS、提交 RunPod 校验、webhook 回调、人脸比对
└── deploy                          docker-compose、SQL、Nacos 配置样例、K8s(CCE)、Flink CDC
```

对外暴露的服务拆成 `*-api`（契约：DTO + 接口 + Feign 客户端）和 `*-service`（实现）两个模块。XXL-Job 执行器端口为 91xx，与服务端口一一对应。

## 资金链路

```
玩家 ──► ELB(玩家域名) ──► bingo-gateway ──► lobby ──► user(token) ──► game-integration ──► 厂商(启动游戏)

厂商 ──► ELB/WAF(回调域名, IP 白名单) ──► bingo-game-integration(独立节点池)
            验签 → 协议转换 → 一次钱包 RPC → 按厂商格式响应
                                   │
                                   ▼
                           bingo-wallet ──► TaurusDB(独立实例, 主库)
                                                   │ binlog
                                                   ▼
                              Flink CDC ──► Kafka bingo.wallet.txn ──► bet-record ──► bingo.round.settled
                                                   │                                    ├─► promotion(返水)
                                                   │                                    └─► turnover(稽核扣减)
                                                   └─► reconcile(对账/GGR)
```

同步链路只有“验签 → 协议转换 → 扣加款”三步，其余都从账本异步派生。钱包不写 MQ（不双写），事件由 binlog 经 Flink CDC 产生。

## 设计要点与代码位置

### 钱包（系统心脏）

| 设计要求 | 实现 |
|---|---|
| 余额表 + 流水表同一本地事务，条件更新代替先查后改 | [WalletMapper](bingo-wallet/bingo-wallet-service/src/main/java/com/bingo789/wallet/mapper/WalletMapper.java)（`balance >= #{amount}`，影响 0 行即拒绝）、[WalletTxnExecutor](bingo-wallet/bingo-wallet-service/src/main/java/com/bingo789/wallet/service/WalletTxnExecutor.java) |
| 不用 Seata、不用 `SELECT ... FOR UPDATE` | 本地事务 + 唯一键 + CDC + 对账；每个事务先做一条 UPDATE 拿行锁，天然串行化同一玩家 |
| 幂等：唯一索引是最终防线，重复请求返回首次结果与当前余额 | `uk_idempotency (provider_code, provider_txn_id, txn_type)`，[WalletService](bingo-wallet/bingo-wallet-service/src/main/java/com/bingo789/wallet/service/WalletService.java) `idempotent()` / `replay()` |
| 余额不足时仍要先判断是否是已成功请求的重试 | 拒绝前先查幂等键，命中则按成功回放 |
| 钱包读写全部走主库 | `bingo.mybatis.force-master=true` → [ForceMasterInnerInterceptor](bingo-common/bingo-common-mybatis/src/main/java/com/bingo789/common/mybatis/ForceMasterInnerInterceptor.java) 给每条 SQL 加 `/*FORCE_MASTER*/` |
| 按 user_id 分库（100 万在线：16 个库） | `SPRING_PROFILES_ACTIVE=sharding`，见 [application-sharding.yml](bingo-wallet/bingo-wallet-service/src/main/resources/application-sharding.yml)。user_id 经固定哈希落到 1024 个逻辑分片，路由表把逻辑分片映射到物理库；扩容只迁移逻辑分片、不重新切分，路由和迁移标记可从 Nacos 热更新。钱包 SQL 全部是单用户的，所以用 [ShardTemplate](bingo-common/bingo-common-mybatis/src/main/java/com/bingo789/common/mybatis/shard/ShardTemplate.java) 按用户选库，不解析 SQL；在分片作用域之外访问数据库会直接报错 |
| 汇总账户不逐笔更新 | 钱包里没有平台/代理汇总行，由 reconcile 异步汇总 |
| 余额查询不经缓存 | 玩家余额 / 流水读玩家分片的只读节点（`ReplicaRoute`），扣款只看主库；钱包不依赖 Redis |

乱序与异常场景：

| 场景 | 处理 |
|---|---|
| 重复投注/派彩 | 唯一键冲突 → 回放首次结果，`replay=true` |
| 回滚先于投注到达 | 在投注的幂等键上写**墓碑**（status=3），迟到的投注撞键后返回 `BET_CANCELLED` |
| 同一注单多次回滚（不同回滚 ID） | 回滚行的幂等键 = 被回滚交易 ID，最多退一次 |
| 派彩找不到投注 | `requireBet` 时返回 `BET_NOT_FOUND`（厂商重试）；先锁行再查，避免与并发回滚竞争 |
| 免费旋转、奖池、活动奖励 | `FREE_PAYOUT` / `JACKPOT_PAYOUT` / `PROMO_PAYOUT`，不要求有投注 |
| 零派彩 | 照常记账（direction=0），用于关闭注单 |
| 长时间未结算 | bet-record 定时任务 → game-integration `RoundResolver` 向厂商查询 → 补派彩或整局回滚 |
| 厂商调账 | `ADJUST` 新行 + `ref_txn_id`，永不改原流水；负余额策略 `bingo.wallet.negative-balance-policy` |
| 自我排除 / AML 冻结 | 钱包状态 `BET_LOCKED`（禁止投注，允许提现）/ `FROZEN`（禁止一切扣款）；加款永远放行 |

这些场景都有集成测试：[WalletServiceIT](bingo-wallet/bingo-wallet-service/src/test/java/com/bingo789/wallet/WalletServiceIT.java)。

### 厂商适配层

- SPI：[ProviderAdapter](bingo-game-integration/bingo-game-integration-service/src/main/java/com/bingo789/game/adapter/ProviderAdapter.java)（验签 / 解析成统一命令 / 按厂商格式渲染 / 启动 / 拉注单 / 查单局），转账厂商另外实现 [TransferCapable](bingo-game-integration/bingo-game-integration-service/src/main/java/com/bingo789/game/adapter/TransferCapable.java)。
- 统一命令：[WalletCommand](bingo-game-integration/bingo-game-integration-service/src/main/java/com/bingo789/game/adapter/model/WalletCommand.java)（sealed interface），后续流程不接触厂商报文。
- 回调入口 `/callback/{provider}/{action}`：[CallbackController](bingo-game-integration/bingo-game-integration-service/src/main/java/com/bingo789/game/callback/CallbackController.java) 读原始字节 → [CallbackGuard](bingo-game-integration/bingo-game-integration-service/src/main/java/com/bingo789/game/callback/CallbackGuard.java)（IP 白名单 → 验签 → 时间戳防重放）→ [CallbackDispatcher](bingo-game-integration/bingo-game-integration-service/src/main/java/com/bingo789/game/callback/CallbackDispatcher.java)。**未知结果一律回厂商的“系统错误、可重试”，绝不回成功。**
- 入站限流：Sentinel 热点参数规则，按厂商、按玩家两个维度。
- 出站舱壁：每家厂商独立的 JDK HttpClient（独立连接池）+ Bulkhead + CircuitBreaker；熔断打开时自动把大厅中该厂商标记为 `AUTO_MAINTENANCE`，恢复后自动上线，不覆盖运营手工设置的维护状态。
- 密钥：`secret: dew:csms/<name>` 从 CCE DEW/CSMS 插件挂载的文件读取（[SecretResolver](bingo-game-integration/bingo-game-integration-service/src/main/java/com/bingo789/game/security/SecretResolver.java)）。
- 转账钱包：[TransferWalletService](bingo-game-integration/bingo-game-integration-service/src/main/java/com/bingo789/game/transfer/TransferWalletService.java) 状态机 + [TransferRecoveryJob](bingo-game-integration/bingo-game-integration-service/src/main/java/com/bingo789/game/transfer/TransferRecoveryJob.java) 补偿；结果不明时先查询、不退款，避免“钱卡在厂商”或重复入账。
- 模板：[DemoProviderAdapter](bingo-game-integration/bingo-game-integration-service/src/main/java/com/bingo789/game/adapter/demo/DemoProviderAdapter.java)。

**接入新厂商**：
1. 复制 `adapter/demo`，按厂商文档实现 `ProviderAdapter`（转账模式再实现 `TransferCapable`），`name()` 返回协议名；
2. 在 Nacos `bingo-game-integration.yaml` 的 `bingo.providers.<CODE>` 下加配置（同协议的厂商只需配置 `adapter: <协议名>`，不必写代码）；
3. 在 CCE ELB/WAF 和配置中同时加入厂商回调 IP 白名单；
4. 在大厅中添加厂商，运行 `GameSyncJob` 同步游戏（新游戏默认下线，审核后上线）；
5. 与厂商确认：重复请求的返回格式、回滚找不到原单的返回、派彩找不到投注的返回、负余额策略、单局查询接口、注单拉取频率限制。

### 对账、注单、分析

- 注单：`game_round` 按月分区，由 `bingo.wallet.txn` 事件驱动，`round_txn` 表保证事件恰好应用一次；厂商注单拉取按时间窗增量，窗口之间有重叠。每局记录 `user_line`、`game_code`、`game_type`、`game_name`（来自大厅游戏目录，本地缓存，大厅不可用时降级为 OTHER）和结算后余额，`RoundSettledEvent` 带上这些字段供稽核、活动使用。
- 对账：小时表、日 GGR 按线路分行存，与厂商比对、结算、RTP 监控按所有线路合计。
- 对账三层：实时（超时未结算监控）、小时（按厂商/币种汇总比对）、日终（逐笔比对，TODO：在 StarRocks 上做）；GGR 与厂商结算、RTP 偏离监控在 reconcile。
- Flink CDC 作业：[deploy/flink/wallet_txn_cdc.sql](deploy/flink/wallet_txn_cdc.sql)。

### 高可用

- HikariCP 超时调短（连接 1s、socket 3s）；TaurusDB 主备倒换期间回调返回可重试错误，由厂商重试/回滚，对账兜底。
- 降级：钱包不可用时，大厅先挡住新的游戏启动；返水、活动、报表都是异步消费，可以延迟。
- 回调入口和玩家 API 物理隔离：不同域名、不同 ELB、game-integration 独立节点池（见 `deploy/k8s`）。
- 优雅停机、就绪/存活探针、PDB、跨可用区打散。

## 100 万在线容量与自动扩容

完整的容量模型、伸缩策略、连接预算和大促前检查清单见 [docs/capacity-1m.md](docs/capacity-1m.md)。按 100 万人同时在线设计，峰值约 25 万钱包 TPS（设计容量 40 万）、25 万回调 QPS、10 万玩家 API QPS。

| 层 | 做法 | 位置 |
|---|---|---|
| 钱包 / 注单分库 | 自研按玩家路由：`userId → SplitMix64 → 1024 逻辑分片 → 16 个 TaurusDB 实例`；路由表和迁移中的分片可在 Nacos 热更新，迁移中的分片拒绝写入（503 可重试）；不在分片作用域内访问数据库直接报错 | `bingo-common-mybatis` 的 `shard` 包；各服务 `application-sharding.yml`；`ShardTemplate` |
| 数据库升配 | TaurusDB Serverless：在 TCU 范围内秒级纵向伸缩；扩实例 = 迁移逻辑分片（改路由表，不改代码） | `application-sharding.yml` 文件头的迁移步骤 |
| Pod 扩容 | 在线服务：HPA（CPU）+ CronHPA（晚高峰、周末、发薪日提前抬高下限）；Kafka 消费者：KEDA（按 lag） | `deploy/k8s/autoscaling-*.yaml` |
| 节点扩容 | CCE 集群弹性引擎按待调度 Pod 自动加节点；钱包按连接预算上限预留节点 | `deploy/k8s`、`docs/capacity-1m.md` §3 |
| 过载保护 | 网关等候室（满载时新登录 / 开游戏排队，已在游戏中的玩家不受影响）；降级开关（Nacos 实时关闭非核心接口） | `bingo-gateway` 的 `admission`、`DegradeGlobalFilter` |
| 连接池 | Feign 统一用 Apache HttpClient 5 连接池；Tomcat `max-connections` 20000；每个分片库 Hikari 池 8 | 各服务 `application.yml` |
| Kafka | `bingo.wallet.txn` 192 分区（上线后不能再改，否则单用户顺序会乱） | `deploy/flink/wallet_txn_cdc.sql` |

## 用户线路（user_line）

线路用来划分玩家对哪些后台账号、哪些报表可见，默认 `user_line = 1`（取值 1..99，`com.bingo789.common.core.line.UserLine`）。

- **所有业务数据都带 `user_line`**：用户、登录日志、RG 日志、钱包与流水、转账单、注单（`game_round`、`provider_bet_record`）、充值提现单、风控决策 / 审核任务 / AML 预警、稽核桶与稽核明细、有效投注 / 返水 / 奖金、对账小时表与日 GGR。存的是**写入那一刻玩家所在的线路（快照）**：迁移线路不改历史，账本只追加，过去的报表不会变。纯技术表（去重表、outbox、检查点、锁）不带。平台级数据（厂商结算、RTP 预警、对账差异）跨线路汇总，只有“全部线路”权限可见。
- **线路在服务间怎么传**：user-service 是唯一来源；钱包持有一份带版本号的副本（`wallet_user_line`），每笔流水直接从钱包行带出线路，经 Flink CDC 进入 `WalletTxnEvent.userLine`，下游注单、对账、稽核、活动都从事件取；充值、提现、转账单在创建时从 `PlayerStatusView.userLine` 取。所有事件的 `userLine` 缺失时按 1 处理（兼容旧消息）。
- **权限范围**：后台 / 报表查询用 `LineScope`（可见线路集合，接口参数格式 `lines=1,2`，`*` 为全部），SQL 条件 `user_line IN (...)`。后台服务本期不做，接口已按线路过滤：`GET /internal/user/players/search`、`/players/{id}/profile`、`/internal/turnover/...`、`/internal/promotion/activities`。
- **上级代理与注册渠道**：`user_account.parent_agent_id`、`parent_agent_name`（注册时上级代理的用户名，冗余给报表用）、`register_channel`。注册接口可选传 `agentId`、`registerChannel`；代理必须是存在且正常的真实账户（TODO：代理模块上线后限定为代理角色）。

### 线路迁移与影子账户

`POST /internal/user/players/{userId}/line-migration`（`targetLine`、`operatorId`、`reason`），由 `UserLineService` 实现：

1. 玩家账户只改 `user_line`（条件更新 + `line_version` 加 1），id、登录、钱包、历史数据都不变；
2. 在原线路创建一个**影子账户**（`account_type = SHADOW`，复制用户名、注册时间、上级代理等展示信息），不能登录（密码哈希不可用，且不参与用户名 / 邮箱 / 手机的唯一约束），没有钱包；真实账户与影子的对应关系只在 `user_shadow` 表里；
3. 迁移记录写入 `user_line_migration`；提交后同步钱包（`/internal/wallet/user-line`，带版本号，重试不会覆盖更新的线路），失败由 XXL 任务 `userLineWalletSyncJob` 重试；
4. 玩家迁回某条仍有其影子的线路时，删除那条线路上的影子，同一线路里永远不会同时出现真身和影子。

没有新线路权限的后台账号：按用户名只能搜到影子（看起来和普通 1 线玩家一样，`shadow = false`）；打开迁移前的历史数据里的玩家 id 时，`profile` 接口返回其在可见线路上的影子；迁移后的新数据（新线路）完全不可见。同时有两条线路权限的账号才能看出哪个是影子。

## 稽核（bingo-turnover）

稽核（流水要求）从风控服务独立出来，按玩家分库（与钱包相同的 1024 逻辑分片，`application-sharding.yml`），实时消费 `bingo.round.settled`：

- **稽核桶**：一个玩家可以同时有多个桶（`turnover_bucket`），每个桶的范围是 **指定游戏**（`GAME`，`厂商:游戏code`）、**指定游戏类型**（`GAME_TYPE`：老虎机 SLOT、捕鱼 FISHING、扑克 POKER、真人 LIVE、桌游 TABLE、街机 ARCADE、宾果 BINGO、彩票、体育、电竞）或 **全类型**（`ALL`）。游戏类型在大厅游戏表 `game.game_type` 维护（同步时按厂商分类预填，运营审核时修正），注单记录 `game_code`、`game_type`、`game_name`。
- **扣减顺序**：一局结算后的有效投注先扣“这款游戏”的桶，剩余再扣“这个游戏类型”的桶，最后扣全类型桶；同级内先建的先扣；每笔有效投注只被扣一次；只有桶创建之后下注的局才计入；超出所有桶的部分不结转。
- **来源**：充值（全类型，倍数按线路配置）、奖金 / 返水（倍数和范围来自活动配置，最细到单个游戏）、后台手工添加（`/internal/turnover/players/{id}/buckets`）。
- **自动清零**（`turnover_setting`，按线路和币种配置）：结算后余额低于 `clear_below_balance` 时清除该币种所有未完成的稽核；剩余未完成额低于 `complete_below_remaining` 时视为完成。
- **稽核记录**（`turnover_record`，只追加）：稽核桶的每一次变化都记一条，与稽核桶在同一个本地事务里写入：`CREATE`（创建，金额 = 所需流水）、`WAGER`（某一局的有效投注被这个桶扣减，记录厂商 / 游戏 / 游戏类型、扣后已完成与剩余，完成的那一笔 `status_after = COMPLETED`）、`CLEAR`（余额过低自动清零或后台手工清除，金额 = 被清掉的剩余）。后台 `GET /internal/turnover/players/{id}/records`，玩家 `GET /api/turnover/records`。
- **实时**：稽核扣减和稽核记录与余额一样逐局实时处理（每局结算事件到达即扣，不做分钟聚合，Flink 聚合不适用于稽核）；延迟是事件链路本身（钱包提交 → CDC → 注单 → `bingo.round.settled`），正常为秒级。这段时间内发起提现，看到的未完成稽核只会偏高（转人工审核），不会误放行。
- **高并发**：每个 Kafka 批次按分库各做一次 `IN` 查询筛出有未完成稽核的玩家（多数玩家没有，零写入），有稽核的玩家每批一个本地事务；`WAGER` 记录的 `(round_key, seq=0)` 唯一键保证每局恰好扣一次；KEDA 按消费 lag 扩缩（8–32 个 Pod）。
- **提现**：risk 的 `TURNOVER_REQUIREMENT` 规则调用 `/internal/turnover/players/{id}/outstanding`；玩家可在 `GET /api/turnover/buckets` 查看自己的稽核进度。

## 活动（通用活动表）

`bingo_promotion.promotion`：公共字段放列上，其余全部放 `config_json`。

| 列 | 说明 |
|---|---|
| `id`、`name`、`promo_type` | 活动 id（雪花）、名称、类型（决定 `config_json` 的结构；现有 `FIRST_DEPOSIT`、`REBATE`） |
| `user_lines` | 对哪些线路的玩家可见，JSON 数组如 `[1]`、`[1,2]`（复数命名，与业务表单值的 `user_line` 区分） |
| `start_time`、`end_time` | 有效期 `[start, end)`，UTC+8 |
| `status`、`sort`、`version` | `DRAFT` → `ONLINE` ↔ `OFFLINE`；排序；乐观锁（上线也要带版本号，上线的就是审核过的那一版） |
| `config_json` | 活动专属配置；只有其中的 `display` 对象会返回给玩家 |

- 首存奖励：`{"percent":100,"maxAmount":1000,"currencies":["PHP"],"turnover":{"multiplier":10,"scope":"ALL","scopeValue":null}}`；按充值成功时间、玩家线路、币种选中 ONLINE 的活动（多个取 sort 最高、再取最新），没有就不发。`bingo.promotion.first-deposit.enabled` 只作为总开关保留（默认关闭，原因见 `BonusGrantService` 的合规注释）。
- 返水：`{"defaultRate":0.005,"defaultDailyCap":null,"providers":{"DEMO":{"rate":0.008,"dailyCap":100}},"turnover":{"multiplier":1}}`，替代原 `rebate_rule` 表；按当天玩家所在线路选活动，结算时把条款复制到 `rebate_record`，之后改活动不影响已计算的返水。初始化数据里的返水活动是 `DRAFT`，审批后通过内部接口上线。
- `turnover` 里的 `scope` / `scopeValue` 就是奖金对应的稽核范围（全类型 / 游戏类型 / 单个游戏），随 `BonusGrantedEvent` 交给 bingo-turnover。
- 玩家接口 `GET /api/promotion/activities`：只返回玩家当前线路、当前有效、已上线的活动；自我排除、冷静期、账户停用的玩家返回空列表。
- 后台内部接口 `/internal/promotion/activities`（查询按 `lines` 过滤、创建、修改、改状态）；TODO：后台上线后校验操作人的线路权限覆盖活动的 `user_lines`。

## KYC（bingo-kyc）

人脸比对 / OCR 模型部署在 RunPod：serverless 端点跑 [bbwave_face](https://github.com/taisuishen/bbwave_face)（`runpod_handler.py`，完整 KYC），常驻 GPU Pod 跑 [facecmp](https://github.com/taisuishen/facecmp)（同步人脸比对）。

1. **上传**：`POST /api/kyc/images`（multipart `file`，JPEG / PNG / WebP ≤ 10MB，按文件头识别格式）→ 存到 OBS **私有桶**的 `kyc/{userId}/yyyyMMdd/uuid.ext`，返回 `url`（签名 URL，会过期）和 `key`。公共类 `bingo-common-obs` 的 `ObsStorage`（`bingo.obs.*`，CCE 上默认用节点 IAM 委托，不放 AK/SK）。
2. **提交**：`POST /api/kyc/submissions`（`identityType`、`idFrontUrl`、`idBackUrl` 可选、`selfieUrl`）。只接受玩家自己目录下、已存在的图片；每人同时最多一个进行中的提交，每天最多 `KycMaxSubmissionsPerDay` 次。先写 `kyc_record`（状态 0），马上同步调 RunPod `/run`（图片以签名 URL 传给 worker，带 webhook 地址和 5 分钟 TTL）：成功 → **1 处理中**；失败 → 保持 **0 待提交**，由 XXL-Job 退避重试。
3. **结果**：RunPod 回调 `POST /callback/runpod/kyc?token=...`（经回调 ELB 的独立 8443 监听，RunPod 没有固定出口 IP，靠 URL 里的 token 校验）；webhook 丢失时 `kycResultPollJob` 调 `/status/{id}` 兜底。worker 的 `decision`：approved → **2 成功**，rejected → **3 拒绝**，error（图片下载失败等）→ **4 RunPod 响应失败**。
4. **超时**：提交 5 分钟（`KycTimeoutSeconds`）仍无结果 → **4 RunPod 响应失败**（自动拒绝，并取消 RunPod 任务）；RunPod 自己放弃的任务（FAILED / CANCELLED / TIMED_OUT）在 5 分钟内会重新提交。
5. **用户状态**：每次状态变化同步到 user-service 的 `kyc_status`（0 / 1 → PENDING，2 → VERIFIED，3 / 4 → REJECTED，可重新提交），失败由 `kycUserSyncJob` 重试；VERIFIED 之后才能提现（`PlayerStatusView.canWithdraw`）。玩家轮询 `GET /api/kyc/submissions/latest`，只看到英文拒绝原因。
6. **人脸比对**：`POST /internal/kyc/players/{id}/face-check`（例如提现前自拍）：与已通过 KYC 的自拍比对，先调 facecmp Pod（5 秒超时），失败降级 serverless `action=face_compare`。

XXL-Job（执行器 `bingo-kyc`，都建议每 10 秒一次）：`kycSubmitRetryJob`、`kycResultPollJob`、`kycTimeoutJob`、`kycUserSyncJob`。
配置在 `bingo_kyc.config` 表（`11_kyc.sql` 已插入默认值，30 秒内生效）：部署前把 `RunPodEndpointId`、`RunPodWebhookUrl`、`FaceCompareUrl` 里的 `__占位符__` 换成实际值；`RunPodApiKey`、`RunPodWebhookToken` 是 DEW 引用（`dew:csms/bingo-runpod-api-key`、`dew:csms/bingo-runpod-webhook-token`）。
合规：证件图与 OCR 结果（姓名、证件号、生日）是敏感个人信息（RA 10173）：私有桶、签名 URL（后台 5 分钟）、`result_json` 上线前需 DEW 字段级加密（TODO），保留期按牌照要求配置 OBS 生命周期。

## 合规清单

| 要求 | 位置 |
|---|---|
| 年龄与允许地区校验（注册） | user：`bingo.compliance.min-age`、`allowed-countries` |
| 地域限制（每个请求） | gateway：`GeoFenceGlobalFilter`（国家由 WAF/CDN 注入） |
| KYC 状态控制游戏和提现 | kyc：证件 + 自拍经 RunPod 校验，结果同步到 user 的 `kyc_status`；user：`playerStatus` 统一计算 `canPlay/canDeposit/canWithdraw` |
| 存款限额（提高 24h 冷静期后生效，降低立即生效） | user 维护限额，payment 在充值时校验 |
| 自我排除 / 冷静期立即生效 | user 撤销全部会话 + 钱包置为 `BET_LOCKED`，已打开的游戏下一笔投注即被拒 |
| 自我排除玩家仍可提现、不再发营销奖励、不展示活动 | 钱包 `WITHDRAW_FREEZE` 允许 `BET_LOCKED`；promotion 发奖前检查 `canPlay`，活动列表对受限玩家为空 |
| AML：存款流水要求、大额预警、提现审核 | turnover：`turnover_bucket`（充值即生成全类型稽核）；risk：`aml_alert`、规则引擎 |
| 审计 | 钱包状态变更写 `wallet_status_log`；账本只追加；后台接口 TODO：RBAC + 操作审计 |
| 敏感数据 | 不存证件号原文、收款账号只存令牌化引用；PII 字段需用 DEW 做字段级加密（TODO） |

## 本地运行

```bash
# 1. 基础设施（MySQL、Redis、Nacos、Kafka、XXL-Job），详见 deploy/README.md
docker compose -f deploy/docker-compose.yml up -d

# 2. 构建（需要 JDK 25 + Maven 3.9+）
mvn -DskipTests package

# 3. 在 IDE 中启动各服务（*Application），环境变量见 deploy/README.md
```

没有本地 JDK 时，可以用 Docker 构建：

```bash
docker run --rm -v "$PWD":/src -v "$HOME/.m2":/root/.m2 -w /src maven:3.9-eclipse-temurin-25 mvn -DskipTests package
```

## 构建与测试

```bash
mvn test            # 单元测试
mvn verify -Pit     # 集成测试（*IT，需要 Docker / Testcontainers）
```

## 约定

- **时区全部为 UTC+8**（见下文“时区约定”）。
- 金额：Java `BigDecimal`、库里 `DECIMAL(20,4)`；外部传入的金额超过 4 位小数直接拒绝，不做静默舍入；给厂商展示的余额向下取整。
- 状态流转用条件更新（`WHERE status = ?`），唯一索引兜底幂等。
- 玩家接口 `/api/**` 返回 `Result<T>`；内部接口 `/internal/**` 只在集群内可达，网关和回调入口都不转发。
- 错误码段：1xxxx 通用、2xxxx 用户、3xxxx 钱包、4xxxx 游戏接入、5xxxx 大厅、6xxxx 注单、7xxxx 支付、8xxxx 风控、9xxxx 活动、10xxxx 对账。

## 时区约定（UTC+8）

整个平台统一使用 UTC+8，代码、数据库、中间件一致：

| 层 | 设置 |
|---|---|
| 代码 | 统一用 `com.bingo789.common.core.time.BingoTime`（`ZONE = +08:00`、`now()`、`toLocal()`、`toInstant()`）；禁止使用 `ZoneOffset.UTC` 和 `ZoneId.systemDefault()` |
| JVM | 每个服务 `main()` 第一行调用 `BingoTime.applyJvmDefault()`；镜像 `TZ=Asia/Manila`（UTC+8，无夏令时） |
| 数据库 | MySQL/TaurusDB 参数 `default-time-zone` / `time_zone = +08:00`；JDBC 统一使用 `connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true`（`%2B` 即 `+`），会话时区由应用强制设置，不依赖服务端默认值 |
| DATETIME 列 | 存 UTC+8 本地时间；SQL 里用 `NOW(3)`，不要用 `UTC_TIMESTAMP()` |
| 业务日 | 返水日、存款限额的日/周/月、GGR 报表日、年龄计算都按 UTC+8（`business-zone` / `report-zone` / `compliance.time-zone` 默认 `+08:00`） |
| 中间件 | docker-compose 与 K8s 中所有容器 `TZ=Asia/Manila`；Kafka / XXL-Job 额外设置 `-Duser.timezone=Asia/Manila`；**XXL-Job 的 cron 按 UTC+8 触发** |
| Flink CDC | `server-time-zone` 与 `table.local-time-zone` 为 `Asia/Manila`，事件里的 `createdAt` 输出为 `...+08:00` |
| 分区 | `game_round` / `provider_bet_record` 按 UTC+8 的日期分区；`round_txn` 的雪花 ID 分区边界按 UTC+8 月初计算 |

接口和事件里的 `Instant` 是绝对时刻，Jackson 按 ISO-8601 输出（带 `Z`），表示的时间点不变，前端按 UTC+8 显示即可。如果希望接口直接输出 `+08:00`，可以把 DTO 中的时间字段改为 `OffsetDateTime`。

`WalletServiceIT.databaseAndCodeAgreeOnUtcPlus8` 用于校验会话时区确实为 `+08:00`，并且 Java 写入的时间与数据库 `NOW()` 一致。

## 待办（均未完成）

以下各项**都还没做**（代码里对应位置是 `TODO:` 或占位实现）：

- **统一编译与测试**：用户线路、影子账户、稽核、通用活动表、KYC 这几轮的改动都还没编译，需一次全量 `mvn -Pit clean verify` 并修正。这几轮给 payment、risk、promotion、bet-record、reconcile、turnover、kyc 写了测试，但都还没跑过；bet-record 分片后的扫描任务还缺集成测试。
- **100 万在线第二阶段**：Flink 按局 / 按分钟聚合，替代 bet-record、promotion（有效投注，用于返水）逐条写库。**稽核不在此列**：稽核扣减和稽核记录必须像余额一样逐局实时（已按玩家分库，只有持有未完成稽核的玩家才写库）。另外：钱包流水按日分表 + 归档（跨天幂等校验）；全链路压测（影子库，`X-Load-Test`）；AI 容量预测服务（自动生成 CronHPA 规则）。
- **查询与归档**（一种做法，不引入新的同步链路）：
  1. **玩家查询**（流水 `GET /api/wallet/transactions`、注单 `GET /api/bet-records/rounds`）走 TaurusDB 只读节点，按 `ShardRouter` 路由到玩家所在分片，只查保留窗口内的数据；只读节点与主库共享存储，加节点便宜，数据实时。分片库地址要填 TaurusDB 数据库代理（读写分离）地址：不带 `FORCE_MASTER` 的查询由代理发到只读节点（钱包默认全部强制主库，流水查询用 `ReplicaRoute` 放开）。
  2. **后台报表、日终逐笔对账、风控分析**走一套 StarRocks（托管），用 Routine Load 直接消费现有的 Kafka 事件流（`bingo.wallet.txn`、`bingo.round.settled`、`bingo.provider.bet`，充值、提现、奖金都在钱包流水里）：事件流已经跨分片合并、只有 INSERT（CDC 已按 `row_kind = '+I'` 过滤），不用为 16 + 16 个分片库各建同步任务，OLTP 的清理也不影响历史。用户、代理、充提订单、活动等低频表通过 StarRocks 的 JDBC Catalog（连共享实例的只读节点）直接关联查询，不做同步。脚本：[deploy/starrocks/bingo_dw.sql](deploy/starrocks/bingo_dw.sql)。托管产品用华为云 CloudTable StarRocks（上线前确认所选区域可开通）；不用 TaurusDB HTAP，它的价值在 binlog 自动同步，而我们的数据已经在 Kafka 里。
  3. **OLTP 保留窗口**：`wallet_txn` 保留 7 天（厂商重试、回滚一般不超过 72 小时，按合同核实），`walletTxnRetentionJob` 按雪花 id 从最老的一段分批删除（批后至少停顿与本批同样长的时间，库忙时自动减速），低峰执行；唯一幂等键必须全局，不做按日分区。注单（`game_round`、`provider_bet_record`、`round_txn`）和稽核记录按月分区，由 `betRecordPartitionJob`、`turnoverPartitionJob` 提前建分区并按保留月数 DROP PARTITION（注单默认 2 个月，稽核记录 6 个月）。
- **厂商配置热更新**：`ProviderRegistry` 目前只在启动时构建，改 Nacos 需重启；Sentinel 规则接入 Nacos 数据源。
- **对账**：日终逐笔对账（`DailyDetailReconJob` 为占位，在 StarRocks 上做钱包流水与厂商注单的逐笔 FULL JOIN）；RTP 告警改为基于置信区间；厂商结算的负 GGR 结转与最低费用。
- **后台服务（bingo-admin）**：RBAC、操作审计、人工调账审批；操作人线路权限（`LineScope`）注入与按线路过滤的报表；风控审核任务、AML 预警、KYC 审核列表按线路过滤；活动写操作校验线路覆盖。
- **多账号识别**：需要 user → risk 的设备 / IP 关联数据流，`MultiAccountRule` 目前恒通过。
- **敏感数据**：DEW 字段级加密（user 的邮箱 / 手机 / 生日，KYC 的 `result_json` OCR 字段）；KYC 图片按牌照要求配置 OBS 生命周期（保留期）。
