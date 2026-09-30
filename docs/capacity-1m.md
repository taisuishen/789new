# 100 万在线容量设计（1M concurrent players）

> 状态：**估算值，全部需要全链路压测确认**。本文与 `deploy/k8s/` 中的清单一一对应；清单里标 `# verify` 的地方，本文“待确认项”一节有汇总。
> 部署区域：华为云 CCE，亚太-香港（ap-southeast-1）或亚太-新加坡（ap-southeast-3），两地都是 UTC+8。
> 平台时区：**全部 UTC+8**（见根目录 README“时区约定”），本文所有时间均为 UTC+8。

## 1. 容量模型

| 项目 | 峰值估算 | 设计容量 | 推导 / 说明 |
|---|---|---|---|
| 同时在线 | 1,000,000 | - | |
| 正在玩老虎机 | ~500,000 | - | 在线的 ~50% |
| spin 速率 | ~125,000 spin/s | - | 1 spin / 4 s / 人 |
| 钱包 TPS | **~250,000** | **~400,000**（~1.5x） | 每 spin 2 次钱包操作（投注 + 派彩） |
| 厂商回调 QPS | ~250,000 | ~400,000 | 每次钱包操作对应一次厂商回调 |
| 玩家 API QPS | ~100,000 | ~150,000 | 静态资源、游戏列表走 CDN |
| Kafka `bingo.wallet.txn` | ~250k msg/s（~100 MB/s） | 192 分区 | Flink CDC 从 16 个钱包实例的 binlog 产生 |
| Kafka `bingo.round.settled` | ~125k msg/s | 96 分区 | bet-record 产出 |
| Kafka `bingo.provider.bet` | 厂商注单拉取 | 24 分区 | |
| 钱包库 | 1024 逻辑分片（user_id 哈希） | 16 个 TaurusDB 实例 | 每实例 1 主 + 1 跨 AZ 只读；单主约 20–25k 钱包 TPS；设计峰值 400k / 16 = 25k，已到单主上限 |

几点说明：

- 钱包 400k / 16 实例 = 25k TPS/主，正好在单主能力的上沿。1024 个逻辑分片落在 16 个库 `bingo_wallet_00..15`（每库 64 个），库可以任意组合放在实例上；扩容就是用 DRS 把整个库在线搬到新实例，低峰切路由，这部分玩家只停写几秒（步骤见 `application-sharding.yml` 文件头）。**库的个数和 1024 一样第一天定死**，所以每类最多 16 个实例；再往上只能升单实例规格（计算器里香港、新加坡最大 32 核），或者按 `shard_no` 在停写状态下拆库。
- 热点：个别大户和机器人会让某些逻辑分片偏热，按实例（而不是按平均值）监控 TPS / CPU / 行锁等待。
- 平峰（凌晨、工作日白天）约为峰值的 1/5–1/10，HPA 的 `minReplicas` 按 1/5 取值。
- 超出容量时，网关对**新登录 / 新开游戏**排队（等候室），**已在游戏中的玩家不受影响**（厂商回调不经过网关）。

## 2. 各服务规格与伸缩方式

Pod 规格统一 4 vCPU / 8 GiB，requests == limits（Guaranteed QoS；HPA 的 CPU 利用率按 requests 计算，limit 等于 request 时目标值才有意义）。JVM 堆为内存上限的 75%（6 GiB），业务服务使用虚拟线程。

| 服务 | 峰值 Pod | 最小 / 最大 | 伸缩机制 | CPU 目标 | 节点池 | 优先级 | PDB | DB 连接池 |
|---|---|---|---|---|---|---|---|---|
| bingo-game-integration | ~100 | 20 / 160 | HPA + CronHPA | 50% | callback（专用、污点） | bingo-critical | 10% | 4 |
| bingo-wallet | ~100 | 20 / 160 | HPA + CronHPA | 50% | general | bingo-critical | 10% | 8 / 实例 |
| bingo-gateway | ~50 | 10 / 80 | HPA + CronHPA | 60% | general | bingo-online | 15% | - |
| bingo-user | ~20 | 6 / 30 | HPA + CronHPA | 60% | general | bingo-online | 20% | 16 |
| bingo-lobby | ~20 | 6 / 30 | HPA + CronHPA | 60% | general | bingo-online | 20% | 8 |
| bingo-payment | 10 | 3 / 15 | HPA + CronHPA | 60% | general | bingo-online | 20% | 16 |
| bingo-bet-record | ≤64 | 12 / 64 | KEDA（lag + CPU + cron） | 70% | consumer（污点） | bingo-async | 20% | 8 / 实例 |
| bingo-reconcile | 8–32 | 8 / 32 | KEDA（2 个 lag + CPU） | 70% | consumer | bingo-async | 20% | 8 |
| bingo-turnover | 8–32 | 8 / 32 | KEDA（lag + CPU） | 70% | consumer | bingo-async | 20% | 8 / 实例 |
| bingo-risk | 2–8 | 2 / 8 | HPA（CPU） | 60% | general | bingo-async | 20% | 8 |
| bingo-promotion | 8–32 | 8 / 32 | KEDA（lag + CPU） | 70% | consumer | bingo-async | 20% | 8 |

- 回调和钱包的 CPU 目标定为 50%：这两层看的是延迟（厂商回调超时通常 3–10 s，钱包 RPC 预算 1.5 s），不是利用率；多出来的余量用来吸收扩容反应时间内的增长（见 §8.4 的推导）。
- 消费者按 lag 扩缩：`bingo.wallet.txn` 192 分区，每个 Pod 3 个监听线程，所以 bet-record 最多 64 个 Pod 才有意义；`bingo.round.settled` 96 分区 → turnover / promotion 最多 32 个。
- `replicas` 不写在清单里，由 HPA / KEDA 负责（重新 apply 带 `spec.replicas` 的清单会在高峰期把已扩容的工作负载打回最小值）。新建时 HPA 在一个同步周期（~15 s）内从 1 扩到 `minReplicas`。
- PDB 用百分比：100+ 个 Pod 时 `maxUnavailable: 1` 会让节点排空（集群缩容、节点池升级）拖上几个小时；百分比向上取整，所以任何时候至少允许 1 个。
- StatefulSet 滚动升级默认一次一个 Pod，100 个钱包 Pod 要 ~2 小时。清单里写了 `updateStrategy.rollingUpdate.maxUnavailable`，需要集群打开 `MaxUnavailableStatefulSet` 特性门控才生效（`# verify`）；不生效时只在平峰发布。
- Tomcat / JVM：`server.tomcat.max-connections`、`server.tomcat.threads.*`、`accept-count` 和虚拟线程开关由各服务的 application.yml 设置，清单只负责 Pod 规格。虚拟线程下，单 Pod 并发由 max-connections 和下游连接池（Hikari、Feign/HttpClient）约束，不再是平台线程池。网关是 Netty（WebFlux），`server.tomcat.*` 不适用，event loop 数跟 CPU limit（4）走。
- 服务间调用用 OpenFeign（HTTP keep-alive 连接池 + JSON）。唯一的大流量调用是 game-integration → wallet：峰值 250k/s，每个钱包 Pod 2.5k–4k/s。估算每次调用两端合计 0.1–0.2 ms CPU，每 Pod 不到 1 核，内网延迟 < 1 ms；上限在 TaurusDB（单主 ~25k TPS），不在 RPC。压测（§8 第 2 步）要量这一跳的 CPU 占比和 p99；确实吃紧时只把 `WalletApi` 这一跳换成 gRPC（Spring gRPC 官方支持 Boot 4），其余调用不动。Dubbo 3.3.x 官方只支持到 Spring Boot 3.x，支持 Boot 4 之前不采用。

### 2.1 连接数预算

钱包分片后，**每个钱包 Pod 对每个分片库各持有一个连接池**（该库的 64 个逻辑分片共用这个池；100 万在线时一个实例一个库）：

```
每个 TaurusDB 主库的连接数 = 钱包 Pod 数 × DB_POOL_SIZE
  预计峰值：100 × 8 =   800
  HPA 上限：160 × 8 = 1,280   （另加 Flink CDC、对账只读、DBA 会话，约 50）
每个钱包 Pod 的连接数 = 16 实例 × 8 = 128
```

- `HPA 上限 × DB_POOL_SIZE` 要低于实例 `max_connections` 的 ~70%（按所选规格 / Serverless 最小 TCU 核实该值）。**钱包的 `maxReplicas` 就是连接预算的上限**，CronHPA 的目标值不得超过它（超过会让 CronHPA 抬高 HPA 的 max）。
- 池子为什么只要 8：一次钱包操作占用连接 ~1–3 ms，每 Pod 每实例峰值约 2.5k / 16 ≈ 150 TPS，平均不到 1 个连接，池子只是吸收抖动。
- 分片路由是平台自研的（`bingo-common-mybatis` 的 `com.bingo789.common.mybatis.shard`，不用 ShardingSphere）：`userId → SplitMix64 哈希 → 逻辑分片 0..1023 → 路由表 → 数据源`。每个数据源一个 Hikari 池，大小由 `bingo.shard.pool.max-pool-size`（= `DB_POOL_SIZE`，默认 8）决定，`min-idle` 2。配置在 `bingo-wallet-service` 的 `application-sharding.yml`（`SPRING_PROFILES_ACTIVE=sharding`），实例地址 `WALLET_DB_HOST_00..15` 来自 ConfigMap `bingo-wallet-shards`。
- 路由表 `bingo.shard.routes` 与 `bingo.shard.migrating-shards` 可在 Nacos 热更新（非法的新路由会被拒绝、保留旧路由）；迁移中的逻辑分片拒绝写入（HTTP 503，可重试），读继续走旧库。迁移步骤见 `application-sharding.yml` 文件头。

bet-record 与钱包同样分片（同一个哈希，1024 逻辑分片、16 个独立 TaurusDB 实例，`BET_RECORD_DB_HOST_00..15` 来自 ConfigMap `bingo-bet-record-env`，见 `deploy/k8s/bingo-bet-record-shards.yaml`）：每个 bet-record 主库 64 Pod × 8 = 512 连接（KEDA 上限）。注单拉取的检查点表在 ds00；扫描类任务（未结算局、补发事件）按数据源逐个执行。

共享实例（user、lobby、payment、risk、promotion、reconcile、game）按 HPA / KEDA 上限计（稽核 bingo-turnover 与注单一样分 16 个库，可与注单同实例：每主库 32 Pod × 8 = 256 连接）：

```
user 30×16 + lobby 30×8 + payment 15×16 + risk 8×8 + promotion 32×8 + reconcile 32×8 + game-integration 160×4
= 480 + 240 + 240 + 64 + 256 + 256 + 640 = 2,176
```

game-integration 的 application.yml 写死了 `maximum-pool-size: 20`（160 × 20 = 3,200），清单用环境变量 `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=4` 覆盖（环境变量优先于 application.yml 和 Nacos 配置；回调链路本身不访问数据库，只有转账钱包订单用）。

> 注意：bet-record 分片后每个实例约 16k 次写事务/s（250k 事件 / 16），逐条写仍然偏重；稽核（bingo-turnover）已按玩家分片，且只有持有未完成稽核的玩家才写库；promotion（有效投注）仍逐局去重写共享实例，峰值 ~125k 局/s 压力很大。长期方案是 Phase 2 的 Flink 聚合（§10）：按局 / 按分钟汇总后再写库。

## 3. 伸缩机制

### 3.1 HPA（autoscaling/v2）

- 清单：钱包、回调、网关的 HPA 在各自的清单里；user / lobby / payment 在 `autoscaling-hpa.yaml`。
- `behavior`：扩容快（稳定窗口 0–30 s，每 15 s 最多翻倍或 +N 个，取大者），缩容慢（稳定窗口 5–10 分钟，每分钟最多 10%）。
- 内存不作为指标：JVM 堆不会回缩，内存利用率不反映负载。
- HPA 需要资源指标 API：安装 CCE 插件“Kubernetes Metrics Server”（或云原生监控插件的 adapter）。

### 3.2 KEDA（Kafka 消费者）

- **CCE 没有 KEDA 插件**（插件总览里没有），用 Helm 安装：
  ```bash
  helm repo add kedacore https://kedacore.github.io/charts
  helm install keda kedacore/keda --namespace keda --create-namespace --version <与集群版本匹配的 chart>
  ```
  版本按 KEDA 兼容矩阵选：2.21 → Kubernetes 1.34–1.36，2.20 → 1.33–1.35，2.18 → 1.31–1.33。镜像在 ghcr.io，节点无外网时先同步到 SWR。
- 一个集群只能有一个 `external.metrics.k8s.io` 提供者，安装前 `kubectl get apiservice v1beta1.external.metrics.k8s.io` 确认没有被别的组件占用。
- **一个工作负载只能有一个 HPA 或一个 ScaledObject，不能同时有**：KEDA 会为每个 ScaledObject 创建并接管自己的 HPA（`keda-hpa-<名字>`），KEDA 的准入 webhook 会拒绝目标已被其它 HPA 管理的 ScaledObject。所以消费者没有单独的 HPA，CPU 指标是 ScaledObject 里的 `cpu` 触发器；CronHPA 也绝不能指向 `keda-hpa-*`（KEDA 会改回去），KEDA 工作负载的定时下限用 KEDA 自己的 `cron` 触发器（支持 IANA 时区，bet-record 已配置晚高峰预扩到 32）。
- 清单：`autoscaling-keda.yaml`。消费组 ID 取自 Java 源码的 `@KafkaListener`：

  | 服务 | 消费组 | Topic | lagThreshold（每 Pod） | 最小 / 最大 |
  |---|---|---|---|---|
  | bet-record | `bingo-bet-record` | bingo.wallet.txn | 20,000 | 12 / 64 |
  | reconcile | `bingo-reconcile-platform` | bingo.wallet.txn | 20,000 | 8 / 32 |
  | reconcile | `bingo-reconcile-provider` | bingo.provider.bet | 5,000 | （同上，此触发器最多要 8 个） |
  | turnover | `bingo-turnover` | bingo.round.settled | 10,000 | 8 / 32 |
  | promotion | `bingo-promotion-validbet` | bingo.round.settled | 10,000 | 8 / 32 |

- 副本计算：`期望副本 = 消费组总 lag / lagThreshold`（AverageValue），再取所有触发器的最大值。Kafka scaler 默认把副本数封顶在分区数（假定每 Pod 1 个消费者）；我们每 Pod 3 线程，所以 `maxReplicaCount = 分区数 / 3`。lagThreshold 约等于“每 Pod 5 秒的积压”，压测后按实测单 Pod 吞吐重算。
- `offsetResetPolicy: earliest` 与应用的 `auto-offset-reset: earliest` 一致；新消费组没有提交过位点时，KEDA 把整个 topic 当成 lag，直接扩到最大去追，这是预期行为。
- DMS Kafka SASL_SSL：`TriggerAuthentication dms-kafka-sasl-ssl` 引用 Secret `keda-dms-kafka`（占位见 `secrets-example.yaml`，生产放 DEW/CSMS）：`sasl=scram_sha512`（或 `plaintext` = SASL/PLAIN）、专用只读用户、`tls=enable`、`ca` = DMS 控制台下载的 PEM 证书；内网 SASL 端口 9093（以实例“连接信息”为准）。DMS 要求客户端关闭证书域名校验，而 KEDA（Go）默认校验主机名；如果 scaler 报 x509 主机名错误，只能把 `unsafeSsl` 设为 `"true"`（VPC 内仍加密，但不再校验服务端身份）。
- `fallback`：Kafka 指标连续失败 4 次时按固定副本数兜底（cpu 触发器照常工作，HPA 取最大值，不会因此缩容）。
- 重平衡：每次扩缩都是一次消费组重平衡，所以消费者扩容是分步的（每 30 s +50% 或 +8 个）。需要服务团队配合：静态成员（`group.instance.id = POD_NAME`，StatefulSet 的 Pod 名稳定）+ `CooperativeStickyAssignor`，滚动发布和扩缩时只迁移少量分区。

### 3.3 CronHPA（定时预扩容）

已核实（cce_10_0415）：插件“CCE容器弹性引擎”（原 cce-hpa-controller）≥ 1.2.13；`apiVersion: autoscaling.cce.io/v2alpha1`，`kind: CronHorizontalPodAutoscaler`；`scaleTargetRef` 指向 HPA（文档示例用 `autoscaling/v1`）或 Deployment；`rules[]` 含 `ruleName`、`schedule`（5 段 cron）、`targetReplicas`、`disable`；每个策略最多 10 条规则，触发时间不能相同。指向 HPA 时，CronHPA 改写 HPA 的 `minReplicas`（只有目标值大于 max 时才改 `maxReplicas`）——这是文档推荐的 CronHPA + HPA 组合方式；**不要**在已有 HPA 时让 CronHPA 直接指向工作负载（两者会互相覆盖）。

**时区**：CronHPA 没有时区字段，“触发时间基于节点所在时区计算”。香港、新加坡都是 UTC+8；上线前在节点上 `date -R`、在 `customedhpa-controller` Pod 里 `date` 确认。如果是 UTC，按下表换算（只有 night-down 跨日，而它每天都触发，所以不受影响）：

| 规则 | 生效日 | UTC+8 | 若控制器为 UTC | 设置的下限 |
|---|---|---|---|---|
| weekend-day | 周六、周日 | `0 10 * * 0,6` | `0 2 * * 0,6` | D |
| payday-day | 15 日、28–31 日 | `0 11 15,28-31 * *` | `0 3 15,28-31 * *` | D |
| weekend-evening | 周五、六、日 | `25 18 * * 0,5,6` | `25 10 * * 0,5,6` | W |
| weekday-evening | 周一至周四 | `30 18 * * 1-4` | `30 10 * * 1-4` | E |
| payday-evening | 15 日、28–31 日 | `35 18 15,28-31 * *` | `35 10 15,28-31 * *` | P（晚于 E/W 触发，所以发薪日以它为准） |
| night-down | 每天 | `15 1 * * *` | `15 17 * * *` | B（= HPA 清单里的 min） |

各服务下限（B / D / E / W / P，HPA max）：

| 服务 | B | D | E | W | P | max |
|---|---|---|---|---|---|---|
| bingo-wallet | 20 | 40 | 70 | 85 | 100 | 160 |
| bingo-game-integration | 20 | 40 | 70 | 85 | 100 | 160 |
| bingo-gateway | 10 | 20 | 35 | 42 | 50 | 80 |
| bingo-user | 6 | 9 | 14 | 16 | 20 | 30 |
| bingo-lobby | 6 | 9 | 14 | 16 | 20 | 30 |
| bingo-payment | 3 | 6 | 6 | 8 | 10 | 15 |

- 晚高峰 19:00–01:00：18:25 / 18:30 预扩（提前 30–35 分钟，给节点扩容留时间），01:15 回到基线，之后 HPA 按每分钟 10% 慢慢缩。
- 发薪日：菲律宾一般 15 日和月底发薪。5 段 cron 写不出“每月最后一天”，所以 28–31 日都按发薪日处理（每月最多多预扩 3 个晚上，成本可接受）。发薪日逢周末/节假日提前到前一个工作日、以及一次性活动，临时加规则或手工调高 min（§9 检查清单）。
- 事故中手工调高的下限会被下一条规则覆盖——保持手工下限期间把相关规则 `disable: true`。
- 高峰时段不要重新 apply HPA 清单：会把 `minReplicas` 重置为基线，直到下一条 CronHPA 规则触发。
- `# verify`：文档只描述了 HPA → Deployment；钱包、回调、user、lobby、payment 都是 StatefulSet。抬高 HPA 的 min 本身就会让 HPA 在一个同步周期内扩容 StatefulSet，上线前在集群里实测一次。另外星期列表、日期列表/范围写法（`0,5,6`、`15,28-31`）文档没有示例，需确认。

### 3.4 谁来设置 min / max

扩容只有三种机制，各管一件事：

| 组件 | 改什么 | 什么时候 | 约束 |
|---|---|---|---|
| HPA | 在线服务的副本数（按 CPU），限定在 [min, max] | 每 15 s | 唯一决定在线服务实际副本数的组件 |
| CronHPA | HPA 的 `minReplicas` | 规则触发时刻（已知高峰前） | 所有目标值 ≤ HPA max，max 永远不被改；钱包的 max = 连接预算上限 |
| KEDA | 消费者自己的 HPA（`keda-hpa-*`），按 Kafka lag + CPU | 每 15 s | 只管消费者；CronHPA 不得指向它，消费者的定时下限用 KEDA 的 `cron` 触发器 |

节点由集群弹性引擎按 Pending Pod 增加（§3.5）；已知高峰前 CronHPA 抬高下限，节点提前扩出来。不做预测式弹性和 CCI 突发：CronHPA 覆盖了可预见的高峰，突发流量由 HPA + 节点弹性 + 网关等候室承接。

### 3.5 节点弹性（CCE集群弹性引擎）

- 插件：“CCE集群弹性引擎”（原 autoscaler）。Pod 因资源不足 Pending 时触发扩容，按“最小浪费”选规格；节点空闲（CPU 与内存的分配率都低于缩容阈值）默认 10 分钟后缩容；有 PDB、非控制器 Pod、`cluster-autoscaler.kubernetes.io/safe-to-evict: "false"`、kube-system 非 DaemonSet Pod 的节点不缩。不支持默认节点池。
- 节点池“弹性伸缩”配置：**缩容最小数 / 扩容最大数 / 冷却时间**（新扩出的节点多长时间内不缩，建议 ≥ 30 分钟，避免预热的节点在高峰前被回收）。
- 节点池弹性策略（`HorizontalNodeAutoscaler`，`autoscaling.cce.io/v1alpha1`）支持**周期触发**（cron，只能 ScaleUp、每次加 N 个节点）和**指标触发**（CPU / 内存分配率阈值），每池最多 10 条规则。可以在 CronHPA 之前 15 分钟先把节点加上：

  ```yaml
  apiVersion: autoscaling.cce.io/v1alpha1
  kind: HorizontalNodeAutoscaler
  metadata:
    name: callback-evening-prewarm
    namespace: kube-system
  spec:
    disable: false
    rules:
      - ruleName: evening-prewarm        # 18:10 加 9 个节点，18:25-18:35 CronHPA 抬高下限时直接用上
        type: Cron
        disable: false
        cronTrigger:
          schedule: "10 18 * * *"         # 时区跟随系统设置，确认方法同 CronHPA
        action:
          type: ScaleUp
          unit: Node
          value: 9
      - ruleName: allocation-high        # 分配率高时提前补节点，不必等 Pending Pod
        type: Metric
        disable: false
        metricTrigger:
          metricName: Cpu
          metricOperation: '>'
          metricValue: "80"
          unit: Percent
        action:
          type: ScaleUp
          unit: Node
          value: 3
    targetNodepoolIds:
      - <callback 节点池 ID>
  ```

节点池规划（示例规格 32 vCPU / 64 GiB，扣除系统预留和 DaemonSet 后每节点放 6 个 4U8G 的 Pod；规格与 AZ 库存待确认）：

| 节点池 | K8s 标签 / 污点 | 承载 | 平峰 Pod → 节点 | 峰值 Pod → 节点 | 上限 Pod → 节点 | 缩容最小数 / 扩容最大数 |
|---|---|---|---|---|---|---|
| general | `bingo.io/pool=general`，无污点 | wallet、gateway、user、lobby、payment + 系统组件（CoreDNS、KEDA、监控、xxl-job-admin） | 45 → 8 | 200 → 34 | 315 → 53 | 12（每 AZ 4）/ 60（每 AZ 20） |
| callback | `bingo.io/pool=callback`，污点 `bingo.io/pool=callback:NoSchedule` | game-integration | 20 → 4 | 100 → 17 | 160 → 27 | 6（每 AZ 2）/ 30（每 AZ 10） |
| consumer | `bingo.io/pool=consumer`，污点 `bingo.io/pool=consumer:NoSchedule` | bet-record、reconcile、turnover、promotion | 36 → 6 | ~136 → 23 | 160 → 27 | 6（每 AZ 2）/ 30（每 AZ 10） |

- 每个节点池都要跨 3 个 AZ，各 AZ 的最小数相同（zone 打散依赖它）。钱包满配（160 个 Pod ≈ 27 个节点）的算力要作为 general 池的**预留容量**（包年包月节点或与华为云确认的资源预留），因为钱包不突发。
- CCE 集群管理规模选 ≥ 200 节点的规格（建议 1000 节点档，留出增长和大促余量）。
- CCE Turbo：每个 Pod 占一个 VPC IP，容器子网按上限（~700 Pod + 系统）规划，每 AZ 至少 /22；ENI / IP 配额要同步提升。可评估 CCE Turbo 的容器网卡预热参数（待确认）以缩短 Pod 启动。
- 大促前向华为云（客户经理 / 工单）**报备并预留容量**：ECS 规格 × 数量 × AZ、ELB、EIP 带宽、Anti-DDoS 防护等级、TaurusDB / DCS / DMS 扩容。节点从创建到 Ready 需要几分钟（压测时实测），高峰开始后才扩节点就来不及。

### 3.6 数据层与入口

| 组件 | 规格 / 做法 | 自动伸缩能力 |
|---|---|---|
| TaurusDB（钱包） | 16 实例（每实例一个库 `bingo_wallet_NN`），每实例 1 主 + 1 跨 AZ 只读，1024 逻辑分片；可用 **Serverless** 实例：按 TCU（1 TCU ≈ 1U2G）设定最小/最大值，在范围内纵向秒级伸缩（CPU > 80% 持续 4 s 或 > 60% 持续 20 s 扩；< 30% 持续 10 s 缩），只读节点可横向增减（1 主 + 最多 7 只读） | 纵向：秒级、在 TCU 范围内；横向加实例（DRS 整库搬迁）：人工、小时级 |
| TaurusDB（注单） | bet-record 16 实例，与钱包同样的 1024 逻辑分片（§2.1） | 同上 |
| TaurusDB（共享） | 其余服务 | 同上 |
| Flink CDC | 16 个 binlog 源（每实例一个，增量阶段单并行读）→ `bingo.wallet.txn`；自建（CCE 上的 Flink Operator）或 DLI | 并行度调整需 savepoint 重启 |
| DCS Redis | 集群版。负载：网关会话读取与限流（~100k QPS × 2–3 条命令）、等候室计数、Redisson 锁；按 30–50 万 ops/s 规划分片数 | 分片数扩容涉及 slot 迁移，大促前提前做 |
| DMS Kafka | 312 分区 × 3 副本；写入 ~160 MB/s，读取 ~300 MB/s（wallet.txn、round.settled 各 2 个消费组）+ 副本同步；按 DMS 规格表选 broker 规格与数量 | broker 可在线扩，分区重分配需人工；**分区数上线前定死**（加分区会改变 userId → 分区映射，破坏单用户顺序） |
| ELB | player、callback 两个独享型 ELB（L7，HTTPS）。回调设计 400k QPS，若单实例规格不够，按厂商拆成多个回调域名 / ELB（cb1、cb2…），同时缩小故障半径；要求厂商使用 HTTP keep-alive 连接池（TLS 握手是大头） | 规格变更需提前做、提前报备 |
| CDN | 静态资源 + 游戏列表 `/api/lobby/games`（公开 GET，短 TTL 30–60 s，上下线时刷新）。**CDN 上要配置区域访问控制**：缓存命中不经过网关，否则会绕过网关的地域限制（合规） | 自动 |
| WAF / Anti-DDoS | 玩家域名 CC 防护 + bot 管理；`X-Load-Test` 头只允许压测源 IP（§8） | 防护等级需报备 |
| Nacos | 峰值 ~500–700 个实例注册；3–5 节点集群（自建或 CSE Nacos 引擎，按实例数选规格，待确认） | 人工 |
| 链路追踪 | APM 在亚太不提供，自建 OTel Collector + 后端；采样率已从 10% 降到 1%（`02-common-config.yaml`），错误 / 慢请求用 Collector 的尾部采样保留 | Collector 本身用 HPA |

## 4. 自动的与必须预置的

| 类别 | 内容 | 反应时间 |
|---|---|---|
| 秒级自动 | HPA（15 s 同步）+ Pod 启动（JVM 30–60 s）；KEDA lag（15 s）；TaurusDB Serverless 纵向（4–20 s 触发）；网关降级开关（Nacos 推送即时生效）；等候室按每秒放行速率自动排队 / 放行 | 秒 – 1 分钟 |
| 分钟级自动 | 集群弹性引擎扩节点（几分钟，实测）；TaurusDB Serverless 加只读节点 | 分钟 |
| 定时自动 | CronHPA、KEDA cron、节点池周期触发 | 按日历 |
| **必须预置** | 逻辑分片数 1024（永久不变）；TaurusDB 物理实例数与逻辑分片分布；Kafka 分区数（192 / 96 / 24，上线后不改）；DMS broker 规格；DCS 分片数；ELB 规格与数量；EIP 带宽；Anti-DDoS 等级；CCE 集群管理规模；容器子网 IP；节点池上限与云资源配额（ECS、ENI）；钱包满配节点的预留容量；厂商容量承诺（各厂商能给我们打多少回调 QPS、他们的限流与报备流程，按周计）；华为云大促资源报备 | 小时 – 周 |

## 5. 过载保护：等候室与降级开关

实现在 `bingo-gateway`（`AdmissionGlobalFilter`、`WaitingRoom`、`OnlineTracker`、`DegradeGlobalFilter`），配置在 `application.yml`，可由 Nacos `bingo-gateway.yaml`（Group `BINGO`）覆盖：

```yaml
bingo:
  gateway:
    admission:                  # 等候室：只拦新登录 / 新开游戏，已在游戏中的玩家不受影响
      enabled: true             # ADMISSION_ENABLED；开启时必须有 ADMISSION_PASS_SECRET（K8s Secret bingo-gateway-secret）
      max-online: 1000000       # 已被压测验证的在线上限（5 分钟内有请求的去重玩家数，Redis HyperLogLog）；超过即排队
      online-window: 5m
      protected-paths:          # 只放入口：登录、开游戏。不要加游戏内、钱包、提现路径
        - /api/user/login
        - /api/lobby/games/*/launch
      admit-rate-per-second: 2000   # 全集群每秒从队列放行的人数，同时不超过剩余容量；起始值 = 压测得出的登录吞吐 × 70%
      pass-ttl: 30m             # 放行凭证有效期；每次放行的登录 / 开游戏都会刷新（响应头 X-Admission-Pass）
      ticket-ttl: 1h
    degrade:
      disabled-paths:           # 命中的路径直接返回 503“功能暂不可用”，不再打到后端；每次 Nacos 变更重新读取
        - /api/bet-records/**   # L1：历史注单查询
        - /api/promotion/**     # L1：活动页
```

- 客户端流程：受保护请求在满载时收到 HTTP 429 + 签名的排队票据（`ticket`、`position`、`retryAfter`）→ 轮询 `GET /api/queue/status?ticket=...`（网关内存直接应答，不访问 Redis）→ 轮到时拿到 `pass` → 带 `X-Admission-Pass` 头重试。票据和凭证用 HMAC 签名并绑定 userId（未登录时绑定客户端 IP），不可伪造、不可转让。
- Redis 故障时等候室**放行**（fail open），失败次数见指标 `bingo.gateway.admission.redis.failures`；在线数 / 排队长度见 `bingo.gateway.admission.online`、`bingo.gateway.admission.queue.length`。
- 会话：网关每个请求读一次 Redis（峰值约 10 万次/s，DCS 集群版按此规划分片），注销、自我排除删除会话后下一个请求立即生效。

- 顺序：先靠弹性；接近 `maxReplicas` 或数据库到上限时，先开 L1 降级（关掉非核心读流量），再降低 `admit-rate-per-second`，最后才降低 `max-online`。这两个值和 `degrade.disabled-paths` 在 Nacos 修改后立即生效，无需重启；其余 admission 配置需要滚动重启。
- 不建议降级：登录、开游戏、余额、充值；**提现入口不关**（合规：自我排除的玩家必须能提现，最多延后审核）。
- 厂商回调不经过网关，等候室和降级不影响进行中的投注 / 派彩；回调层自己的保护是 Sentinel（按厂商、按玩家限流）。
- 两个开关都要在压测和演练里实际打开一次，确认推送生效时间和客户端表现（排队页、重试间隔）。

## 6. 容量上限清单（什么先到顶）

| 顺序 | 瓶颈 | 信号 | 处理 |
|---|---|---|---|
| 1 | 单个钱包主库（热点分片） | 实例 CPU / TCU 到顶、行锁等待、p99 上升 | 把这个实例上的其他库搬走 / 升规格；短期开等候室 |
| 2 | 钱包 `maxReplicas`（连接预算） | 钱包 Pod = 160 且 CPU > 50% | 不能单纯加 Pod；加实例（搬库）后再重算预算 |
| 3 | 回调 ELB / 回调节点池 | ELB QPS、新建连接数；Pending Pod 数 | 拆 ELB；扩节点池上限 |
| 4 | Kafka 消费 | lag 持续增长且 Pod = 分区数/3 | 异步链路，可以延迟；Phase 2 Flink |
| 5 | DCS | 单分片 CPU、大 key | 扩分片（提前） |

## 7. 发布与运维约定（1M 规模）

- 钱包、回调只在平峰发布；StatefulSet 一次一个 Pod 时 100 个 Pod 需要约 2 小时。
- 高峰时段（18:25–01:15、周末白天、发薪日）不重新 apply HPA / CronHPA 清单，不做节点池升级。
- 任何人改 `maxReplicas` 或 `DB_POOL_SIZE`，必须同时更新 §2.1 的连接预算。

## 8. 全链路压测方案

### 8.1 流量标识与数据隔离

- 压测流量带请求头 **`X-Load-Test: <签名令牌>`**。WAF 规则 + 网关过滤器：只接受压测机源 IP 且令牌有效的请求，其余请求一律**剥掉该头**（防止玩家伪造进入压测链路）。
- 标识要贯穿整条链路：网关 → Feign（拦截器透传）→ 厂商回调（压测用模拟厂商，回调里带同样的头）→ 钱包 → Kafka（消息头）→ 消费者。可以放进 OpenTelemetry baggage 统一透传。
- 数据隔离（需要服务团队实现，二选一）：

  | 方案 | 做法 | 优缺点 |
  |---|---|---|
  | A 影子库 / 影子表 | 带标识的请求写影子 schema（钱包 16 个实例上各建影子库）、影子 topic、Redis key 前缀 `shadow:` | 隔离彻底；改造量大，影子库也要占连接预算 |
  | B 真实链路 + 专用编码 | 专用厂商编码 `LOADTEST` + 专用玩家号段，写真实表；对账、GGR、返水、风控、报表按厂商编码 / 号段排除 | 最接近真实（同样的锁、索引、binlog）；必须保证所有下游都排除，否则污染报表 |

- **绝不调用真实厂商和真实支付渠道**：用模拟厂商（按厂商协议回调 game-integration）和模拟支付渠道。
- 在生产做压测放在凌晨低峰（02:00–06:00），提前通知厂商、华为云（避免被当作攻击）。

### 8.2 压测阶段

1. 单 Pod 基准：每个服务单独加压到 SLO 破线，得到拐点吞吐 `T_knee` 和对应 CPU `CPU_knee`。
2. 单链路：模拟厂商 → 回调 → 钱包 → TaurusDB（单实例打到 25k TPS 看能否撑住）。
3. 全链路混合：玩家 API（登录、大厅、开游戏、余额）+ 回调 + 充值，按真实比例。
4. 1.0x 峰值（250k 钱包 TPS）→ 1.6x 设计值（400k）→ 登录潮突刺（19:00 前后 10 分钟内的登录速率 × 3）。
5. 弹性验证：从基线起压，测 HPA 反应（负载阶跃 → 新 Pod Ready）、节点扩容（Pending → 节点 Ready）、CronHPA 实际触发时间与时区。
6. 故障注入：TaurusDB 主备切换、一个 AZ 断网、Kafka broker 重启、Redis 分片主备切换、某厂商回调超时；验证可重试错误码、PDB、zone 打散。
7. 长稳：峰值负载持续 4 小时，看内存、连接、GC、lag 是否收敛。
8. 开关演练：等候室和降级开关各打开一次。

### 8.3 各组件要测的指标

| 组件 | 指标 |
|---|---|
| ELB（两个） | QPS、新建 / 并发连接、TLS 握手、4xx/5xx、后端响应时间 |
| gateway | QPS/Pod、p99、CPU、Netty 连接数、Redis 限流延迟、等候室放行速率 |
| user / lobby | 登录/s、token 签发 p99、缓存命中率 |
| game-integration | 回调 QPS/Pod、p99（预算：远小于厂商超时）、按厂商的错误码、验签耗时、到钱包的 Feign 延迟 |
| wallet | TPS/Pod、p99（目标 < 50 ms）、Hikari active / pending / 等待时间（按实例）、幂等冲突率、JVM GC 停顿 |
| TaurusDB（按实例） | TPS、CPU、TCU、连接数、行锁等待、慢 SQL、只读延迟、binlog 产生速率 |
| Flink CDC | 每个源的 binlog 延迟、写 Kafka 速率、checkpoint 时长 |
| Kafka | 生产延迟、各消费组 lag、重平衡次数与耗时、broker CPU / 网络 |
| 消费者 | msg/s/Pod、批处理耗时、DB 写入耗时 |
| DCS | ops/s（按分片）、CPU、延迟、大 key / 热 key |
| 弹性 | HPA 反应时间、节点 Ready 时间、CronHPA 触发时刻 |

### 8.4 从压测结果推导 HPA / KEDA 参数

1. 单 Pod 拐点：`T_knee`（SLO 内的最大吞吐）与 `CPU_knee`。
2. CPU 目标：`U_target = CPU_knee × (1 − g × t_react)`
   - `g`：最陡爬坡时每分钟的负载增长率（取生产 19:00 前后的实测，例如 5%/min）；
   - `t_react`：HPA 同步（0.25 min）+ Pod 启动到 Ready（~1 min）+ 没有空闲节点时的节点扩容（~3 min）。
   - 例：`CPU_knee = 75%`、`g = 5%/min`、有空闲节点（`t_react = 1.25`）→ 70%；需要扩节点（`t_react = 4.25`）→ 59%。所以钱包 / 回调取 50%，其余 60%，并用 CronHPA 预热避开“要扩节点”的情况。
3. 每 Pod 请求量阈值（KEDA prometheus，可选方案）：`threshold = T_knee × U_target / CPU_knee`。
4. `maxReplicas = 设计峰值 / (T_knee × U_target / CPU_knee) × 1.1`；钱包再受连接预算约束，取两者较小值。
5. CronHPA 各档下限：取历史同类高峰（工作日晚上 / 周末 / 发薪日）实际副本数的 P95 × 1.1。
6. 消费者：`lagThreshold = 单 Pod msg/s × 可接受的积压秒数（~5 s）`；`maxReplicaCount = 分区数 / 每 Pod 线程数`。
7. 把新值写回清单，并同步更新本文 §2、§3.3 的表格。

## 9. 大促 / 活动前检查清单

| 时间 | 事项 |
|---|---|
| T-4 周 | 活动预估（峰值在线、spin 率、充值量）；**厂商容量确认**（每家的回调 QPS 上限、他们的限流、是否需要报备）；**华为云报备**（ECS 规格 × 数量 × AZ、ELB、EIP 带宽、Anti-DDoS、TaurusDB / DCS / DMS 扩容）；提升配额（ECS、ENI / IP） |
| T-2 周 | 预置类调整完成：TaurusDB 实例与逻辑分片分布、DCS 分片、DMS broker、ELB 规格 / 拆分；全链路压测到预计峰值的 1.2x；故障演练（主备切换、AZ 断网） |
| T-1 周 | **封网**（只允许紧急修复，Nacos 配置冻结）；**镜像预热**（节点预拉镜像）；预案评审：降级分级、等候室阈值、回滚方案；排班表与升级路径 |
| T-1 天 | **扩容到峰值**：为活动加一条 CronHPA 规则（或手工把各 HPA 的 min 调到 P 档），节点池预热到峰值所需节点数（节点池周期规则或手工），TaurusDB Serverless 最小 TCU 调高，确认 KEDA / CronHPA 状态正常；**演练**：开关演练 + 冒烟压测 |
| T-0 | **值守大屏**：在线数与排队人数、登录/s、spin/s、钱包 TPS 与 p99（按实例）、回调 QPS 与错误码（按厂商）、各实例 CPU / TCU / 连接数、Kafka 各组 lag、各服务 Pod 数与 max 的距离、节点池余量、ELB QPS / 5xx、DCS ops/s；明确告警阈值与决策人（谁有权开降级 / 调等候室） |
| T+1 | 逐步恢复下限（先移除活动规则，节点池按冷却时间自然回收）；复盘：实际峰值 vs 预估、弹性反应时间、需要回写的参数 |

## 10. Phase 2

- **Flink 聚合局与流水**：用 Flink（DLI 或自建）按局 / 按玩家做有状态聚合，替代 bet-record 逐条写库；稽核扣减、promotion 的有效投注改为消费 Flink 的聚合结果（按分钟 / 按局汇总）。消费者 Pod 数和共享库写入量会大幅下降，`bingo.round.settled` 的消息量也随之减少。
- **流水保留与历史**：`wallet_txn` 只保留幂等窗口（建议 7 天），按雪花 id 从最老的一段分批限速删除（唯一幂等键必须全局，不能按日分区）；玩家查询走 TaurusDB 只读节点；报表、对账、风控分析走一套 StarRocks，Routine Load 直接消费 Kafka 事件流（只含 INSERT）。
- **AI 容量预测服务**：用历史在线数 / spin 率 + 日历特征（发薪日、节假日、体育赛事、营销活动）预测未来每 15 分钟的负载，自动生成 CronHPA 规则与节点池预热计划；护栏：永远不超过 `maxReplicas` 与连接预算，活动类变更需人工审批。

## 11. 待确认项（清单中的 `# verify`）

| 位置 | 待确认 |
|---|---|
| `autoscaling-cronhpa.yaml` | CronHPA 能否驱动“指向 StatefulSet 的 HPA”；星期 / 日期列表和范围写法；控制器 / 节点时区是否为 UTC+8 |
| `bingo-*.yaml`、`service-template.yaml` | `MaxUnavailableStatefulSet` 特性门控 |
| `autoscaling-keda.yaml` | DMS SASL_SSL 端口；证书主机名校验（`unsafeSsl`） |
| `01-networkpolicy.yaml` | 监控命名空间名 |
| 本文 | 节点规格与 AZ 库存；节点 Ready 耗时；TaurusDB 各规格的 `max_connections` 与 Serverless TCU 上限；ELB L7 规格上限；DMS / DCS 规格 |

## 12. 参考文档（本次核实）

- 工作负载伸缩原理：https://support.huaweicloud.com/usermanual-cce/cce_10_0290.html
- CronHPA 定时策略：https://support.huaweicloud.com/usermanual-cce/cce_10_0415.html
- CCE容器弹性引擎：https://support.huaweicloud.com/usermanual-cce/cce_10_0240.html
- CCE集群弹性引擎：https://support.huaweicloud.com/usermanual-cce/cce_10_0154.html
- 节点池弹性策略（HorizontalNodeAutoscaler）：https://support.huaweicloud.com/usermanual-cce/cce_10_0209.html
- 插件概述：https://support.huaweicloud.com/usermanual-cce/cce_10_0277.html
- 云原生监控插件：https://support.huaweicloud.com/usermanual-cce/cce_10_0406.html
- TaurusDB Serverless：https://support.huaweicloud.com/usermanual-taurusdb/taurusdb_02_0211.html
- DMS Kafka SASL 接入：https://support.huaweicloud.com/intl/en-us/usermanual-kafka/kafka-ug-180801001.html ，PEM 证书：https://support.huaweicloud.com/intl/en-us/usermanual-kafka/kafka-ug-0073.html
- KEDA 2.21：ScaledObject https://keda.sh/docs/2.21/reference/scaledobject-spec/ ，Kafka https://keda.sh/docs/2.21/scalers/apache-kafka/ ，Prometheus https://keda.sh/docs/2.21/scalers/prometheus/ ，CPU https://keda.sh/docs/2.21/scalers/cpu/ ，Cron https://keda.sh/docs/2.21/scalers/cron/ ，兼容矩阵 https://keda.sh/docs/2.21/operate/cluster/
