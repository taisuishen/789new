# 上线部署手册

- §1 **测试环境**：最低配置，先验证登录、注册、KYC 流程，不对外开放。
- §2–§4 **生产环境**：支付、游戏、后台开发完成后，再按这几节搭建，面向玩家。

容量与弹性细节见 [capacity-1m.md](capacity-1m.md)，清单文件怎么渲染和 apply 见 [deploy/README.md](../deploy/README.md)。

## 0. 原则

- **区域**：香港或新加坡，全部服务放在同一区域、同一 VPC（马尼拉缺少 CSE、DLI 等服务）。测试环境只用 1 个可用区，生产用 3 个。开通前确认所选区域有要用的服务，尤其是 CloudTable StarRocks（生产才用）。
- **CI/CD**：本地 GitLab + 自建 Jenkins（放在 GitLab 旁边），构建镜像推到 SWR，再更新 CCE 上的镜像（§5）。
  - 测试环境：CCE API 绑 EIP，只放通办公室出口 IP。
  - 生产环境：办公室和 VPC 之间建站点到站点 VPN，CCE API 不开公网。
- **第一天就定死、以后不能改的**（测试环境也按这个来，免得两套行为）：
  - 1024 个逻辑分片：代码里固定，钱包从测试环境起就开分片模式（§1.4）。
  - 雪花 ID 的 worker-id 规则、UTC+8 时区、`user_line` 快照语义。
  - 生产 Kafka 的数据流 topic 分区数一次建到 100 万在线的规模（192 / 96 / 24），之后不能再加，否则同一玩家的消息顺序会乱。测试环境不受限制。

## 1. 测试环境（最低配置）：登录、注册、KYC

原则：单可用区、单副本、最小规格；不上 WAF，靠 ELB 白名单只让办公室访问。

### 1.1 部署哪些服务

| 服务 | 为什么需要 |
|---|---|
| bingo-gateway | 唯一入口：鉴权、限流、等候室 |
| bingo-user | 注册、登录、会话、KYC 状态 |
| bingo-wallet | 注册时开钱包（`walletClient.open`），查余额 |
| bingo-kyc | 证件上传 OBS、提交 RunPod、结果轮询 |
| xxl-job-admin | user 和 kyc 的定时任务（KYC 重试、超时拒绝、同步用户状态）；放在命名空间 `bingo-infra` |
| nacos（单节点） | 注册中心、配置中心；测试环境自建，不开 CSE（[test/nacos-standalone.yaml](../deploy/k8s/test/nacos-standalone.yaml)），也在 `bingo-infra` |

其余服务先不部署。网关上它们的路由返回 503，不影响这几个服务。

### 1.2 开通的云服务（最低配置）

| 服务 | 最低配置 |
|---|---|
| VPC、子网、安全组 | 1 个 VPC、1 个可用区：节点 / 容器子网、数据库子网 |
| NAT 网关 + EIP | 最小规格，SNAT 给容器子网出网调 RunPod（CCE Turbo 的 Pod 走容器子网，必须经 NAT 出网） |
| ELB | 独享型最小规格 1 个；443 监听配访问控制，只放办公室出口 IP |
| 域名、证书 | 一个测试域名 + DV 证书 |
| CCE Turbo | 控制节点选单节点（非高可用）、最小集群规模；1 个节点池，标签 `bingo.io/pool=general`，2 台 4 核 8G；插件用默认的；API Server 绑 EIP 给 Jenkins 用，安全组只放办公室 IP |
| SWR | 组织 `bingo`；镜像：4 个服务 + `nacos-server:v3.2.4`、`xxl-job-admin:3.4.2`（从 Docker Hub 同步） |
| TaurusDB | 最小规格，只要主节点（不加只读节点、不开数据库代理），应用直连主节点地址 |
| DCS Redis | 单机最小规格 |
| OBS | 私有桶 `bingo-kyc-test`，默认加密 |
| RunPod（外部） | bbwave_face serverless 端点 + facecmp 常驻 Pod，已部署 |

**测试环境不开**：
- 安全与入口：WAF、DDoS 高防、CDN、回调 ELB、VPN。
- 平台托管：DEW（密钥用普通 K8s Secret）、CSE。
- 可观测：AOM、LTS 采集、链路追踪（日志先用 `kubectl logs`）。
- 数据链路：Kafka、StarRocks、Flink、KEDA。

钱包的 `spring.kafka` 只有在第一次发消息时才会去连 Kafka，这几个服务都不会发消息，所以 `KAFKA_SERVERS` 随便填一个地址也不影响启动。

### 1.3 数据库

1. 参数模板：`time_zone=+08:00`、`binlog_format=ROW`、`binlog_row_image=FULL`、字符集 `utf8mb4`（和生产一致，以后接 Flink CDC 不用改）。
2. 执行脚本：
   - `00_databases.sql`：只建库，会建全部库，多出来的空库不影响。**不要执行 `99_local_dev_account.sql`**：那是本地开发用的 `bingo` / `bingo` 账号，能访问所有库。
   - `01_user.sql`、`11_kyc.sql`。
   - 钱包分片库：`sh deploy/shard_schemas.sh wallet > wallet_shards.sql`，生成 `bingo_wallet_00` … `bingo_wallet_15` 16 个库的建表语句，在 DAS 里执行。每个分片是一个独立的库，以后才能整库搬到别的实例（§3）。
   - XXL-Job 官方 v3.4.2 的 `tables_xxl_job.sql`，导入 `xxl_job` 库。
3. 账号：`bingo_user`、`bingo_wallet`、`bingo_kyc`、`xxl_job`，每个只授权自己的库；钱包用通配授权：``GRANT ALL PRIVILEGES ON `bingo\_wallet\_%`.* TO 'bingo_wallet'@'%';``
4. 连接数：钱包每个 Pod 开 16 个分片池，`DB_POOL_SIZE` 设 2，共 32 个连接；全部服务加起来不到 100。
5. 查数据用华为云 DAS（数据管理服务）的网页控制台，不用给数据库开公网。

### 1.4 CCE 清单改动

清单按生产写的，测试环境这样改：

| 文件 | 测试环境改动 |
|---|---|
| `02-common-config.yaml` | `REDIS_HOST`；`NACOS_ADDR: "nacos.bingo-infra:8848"`；`NACOS_NAMESPACE`：在 Nacos 里建的命名空间 **ID**（例如 `test`）；`KAFKA_SERVERS` 填占位地址；`OTEL_EXPORTER_OTLP_ENDPOINT` 随便填；另加 3 行：`OTEL_JAVAAGENT_ENABLED: "false"`（没有链路追踪后端）、`JDK_JAVA_OPTIONS: "-XX:+UseG1GC -XX:MaxRAMPercentage=60"`（1.5 GiB 的 Pod 堆只给 60%）、`GEO_FENCE_ENABLED: "false"`（没有 WAF 写国家头，访问已由 ELB 白名单限制） |
| `01-networkpolicy.yaml` | `__PLAYER_ELB_SUBNET_CIDR__` 填玩家 ELB 所在子网的 CIDR；第 6 条（回调 ELB）删掉不 apply |
| `bingo-kyc-env.yaml` | `__REGION__`（和 SWR、CCE 同一个区域，例如 `ap-southeast-1`）、`__KYC_BUCKET__`（`bingo-kyc-test`） |
| `test/nacos-standalone.yaml`、`xxl-job-admin.yaml` | `__REGISTRY__`；xxl-job-admin 另填 `__TAURUSDB_SHARED_HOST__`，`replicas: 1` |
| `bingo-wallet.yaml` | **16 个 `WALLET_DB_HOST_NN` 全部填同一个主节点地址**（16 个库都在这个实例上）：分片模式从第一天开，以后按 application-sharding.yml 文件头的步骤把库整体搬到新实例，不用改代码、不用切换模式。`DB_POOL_SIZE` 改 `2` |
| `bingo-gateway.yaml` | `CLIENT_IP_TRUSTED_HOPS` 改 `"1"`（只有 ELB 一层代理，没有 WAF） |
| `service-template.yaml` | 渲染 bingo-user（8101 / 9101）和 bingo-kyc（8111 / 9111），`__DB_POOL_SIZE__` 都填 `4` |
| `ingress-player.yaml` | `__PLAYER_ELB_ID__`、`__PLAYER_CERT_ID__`（ELB 控制台里的 ID）、`__PLAYER_HOST__`（测试域名） |
| 上面 4 个服务的 resources | requests `cpu: 250m`、`memory: 1536Mi`，limits `cpu: "1"`、`memory: 1536Mi` |
| 所有清单 | `__REGISTRY__` = `swr.<区域>.myhuaweicloud.com/bingo`；`__TAG__` = Jenkins 第一次构建（`DEPLOY_TO=none`）打出的 tag，即提交号前 12 位；`__TAURUSDB_SHARED_HOST__` = TaurusDB 主节点地址 |
| 不 apply | `autoscaling.yaml`（不装 KEDA，每个服务 1 个 Pod）、`ingress-callback.yaml`、`bingo-game-integration.yaml`、两个 shards 文件、`dew-secrets.yaml`、`dew-patch.yaml` |

改完用 `grep -rn '__[A-Z_0-9]*__' deploy/k8s` 查还有没有漏填的（`service-template.yaml` 的模板占位符由渲染命令替换）。

镜像拉取：清单里的 Pod 都引用了 `imagePullSecrets: default-secret`，这是 CCE 在每个命名空间自动创建的 SWR 拉取凭据。建完命名空间后确认一下：`kubectl -n bingo get secret default-secret`、`kubectl -n bingo-infra get secret default-secret`。

Secret 用普通 K8s Secret，格式见 `secrets-example.yaml`（复制到仓库外再填，别提交）：

| Secret | 命名空间 | 内容 |
|---|---|---|
| `bingo-common-secret` | bingo | `REDIS_PASSWORD`、`NACOS_PASSWORD`、`XXL_JOB_TOKEN`（≥ 32 位随机串，执行器没配会拒绝启动）。`NACOS_USERNAME`（`bingo-app`）在 `02-common-config.yaml` 里 |
| `bingo-user-db`、`bingo-wallet-db`、`bingo-kyc-db` | bingo | `DB_USER`、`DB_PASSWORD` |
| `bingo-gateway-secret` | bingo | `ADMISSION_PASS_SECRET`（≥ 32 位随机串） |
| `bingo-kyc-secret` | bingo | `BINGO_RUNPOD_API_KEY`：config 表里 `RunPodApiKey` 仍是 `dew:csms/bingo-runpod-api-key`，没装 DEW 插件时自动读这个同名环境变量。另加 `OBS_ACCESS_KEY`、`OBS_SECRET_KEY`：专用 IAM 用户，只授权这个桶。再加 `BINGO_PII_KEY_K1`、`BINGO_PII_INDEX_KEY`（见下一行） |
| `bingo-user-secret` | bingo | `BINGO_PII_KEY_K1`（`openssl rand -base64 32`）、`BINGO_PII_INDEX_KEY`（≥ 32 位随机串）：个人信息字段加密的密钥，和 `bingo-kyc-secret` 里填同样的值。**丢了就解不开已加密的数据**，另存一份到团队密码库 |
| `xxl-job-admin-secret` | bingo-infra | `DB_USER`、`DB_PASSWORD`、`XXL_JOB_TOKEN`（和上面同一个值） |
| `nacos-secret` | bingo-infra | `NACOS_AUTH_TOKEN`（≥ 32 字节随机数的 Base64）、`NACOS_AUTH_IDENTITY_KEY`、`NACOS_AUTH_IDENTITY_VALUE` |

### 1.5 入口

- ELB 443 监听只放办公室出口 IP；`ingress-player.yaml` 指向网关。
- 前端和 API 不在同一个域名时，在 Nacos 的 `bingo-gateway.yaml` 里加 CORS（不用改代码）：

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          globalcors:
            cors-configurations:
              '[/**]':
                allowed-origins: "https://<前端域名>"
                allowed-methods: GET,POST,PUT,DELETE,OPTIONS
                allowed-headers: "*"
                max-age: 3600
```

### 1.6 KYC 配置

在 `bingo_kyc.config` 表里设置：

| 配置项 | 值 |
|---|---|
| `RunPodEndpointId` | bbwave_face 的 serverless 端点 ID |
| `FaceCompareUrl` | facecmp Pod 地址（不填就直接用 serverless 做人脸比对） |
| `RunPodWebhookUrl` | **留空**：测试环境没有回调入口，结果全靠 `kycResultPollJob` 轮询 |
| `KycPollAfterSeconds` | `5`（提交 5 秒后开始查 `/status`，结果大约 10–15 秒可见） |

生产环境开了回调 ELB 以后，`RunPodWebhookUrl` 填 `https://<回调域名>:8443/callback/runpod/kyc`，`KycPollAfterSeconds` 改回 `30`。webhook 只当“结果好了”的通知：收到后拿任务 ID 用 API key 去 RunPod `/status` 回查结果，回调内容本身不采信；轮询兜底，不用改代码。

### 1.7 XXL-Job

执行器 `bingo-user`、`bingo-kyc`，任务见 §6 表中标了“测试环境”的 7 个。`walletTxnRetentionJob` **不要配**：没有 StarRocks，流水只在 TaurusDB 里。

### 1.8 部署顺序

1. VPC、NAT、ELB。
2. TaurusDB（建库、账号）、Redis、OBS。
3. CCE 集群和节点池，API Server 绑 EIP。
4. SWR 同步 `nacos-server`、`xxl-job-admin` 镜像；Jenkins 用 `DEPLOY_TO=none` 构建 4 个服务镜像（§5），记下 tag（提交号前 12 位），填进清单的 `__TAG__`。
5. `kubectl apply`，按以下顺序，每一步 `rollout status` 就绪再继续：
   1. `00-namespace`（建 `bingo` 和 `bingo-infra`）、`01-networkpolicy`、`02-common-config`、`bingo-kyc-env`、Secret。
   2. `test/nacos-standalone`，然后 `kubectl -n bingo-infra port-forward svc/nacos 18080:8080` 打开控制台：设管理员密码，建命名空间（ID 和 `NACOS_NAMESPACE` 一致）和 `bingo-app` 用户，给它这个命名空间的**读写**权限（服务注册是写操作），导入 `deploy/nacos/bingo-common.yaml`（Group `BINGO`），需要时加 `bingo-gateway.yaml`（CORS）。
   3. `xxl-job-admin`。
   4. `bingo-wallet` → bingo-user → bingo-kyc → `bingo-gateway`。
6. XXL-Job 控制台（`kubectl -n bingo-infra port-forward svc/xxl-job-admin 18081:8080`）：改默认密码，建执行器和任务。
7. KYC 配置表（§1.6）。
8. DNS 解析到 ELB，apply `ingress-player.yaml`。

### 1.9 冒烟

正常路径：
1. 注册（带 `register_channel`、上级代理）。
2. 登录，调 `/api/user/me`。
3. 钱包余额为 0。
4. 上传证件和自拍，提交 KYC。
5. 10–15 秒后状态变为 2（成功）或 3（拒绝）。
6. 用户的 KYC 状态已同步。

异常路径：
- RunPod 端点 ID 故意填错：记录先是 status 0 并重试，5 分钟后超时拒绝（状态 4）。
- 同一天提交超过 5 次被拦。
- 登录连续输错 5 次被锁。

### 1.10 接支付、游戏时，测试环境再加什么

还是按最低配置：
- **Kafka**：DMS 最小规格，分区数可以小，topic 和 `.DLT` 按 deploy/README §4 建。
- **服务**：game-integration、lobby、bet-record、turnover、payment、risk、promotion 各 1 个 Pod。bet-record、turnover 的分片库用 `shard_schemas.sh bet_record` / `turnover` 建在同一个 TaurusDB 上，16 个分片地址也全部指向它。
- **入口**：回调 ELB 一个（给厂商、支付渠道的测试回调加白）。
- **数据链路**：Flink CDC 和 StarRocks 等到要测报表、对账时再加。

## 2. 生产环境云服务清单

| 类别 | 服务 | 用途 | 开通 / 配置要点 |
|---|---|---|---|
| 网络 | VPC、子网、安全组 | 全部资源 | 3 个 AZ 各建：节点子网、容器子网（CCE Turbo，每 AZ 至少 /22）、数据库子网、ELB 子网 |
| 网络 | VPN 网关 | 办公室 ↔ VPC | Jenkins 发布、运维访问控制台和数据库 |
| 网络 | NAT 网关 + EIP | 出网 | 调游戏厂商、支付渠道、RunPod；出口 IP 固定，提供给厂商和支付渠道加白 |
| 网络 | 云解析 DNS、SCM 证书 | 域名、证书 | 玩家域名、回调域名（最好是不同的根域名） |
| 入口 | ELB 独享型 × 2 | 玩家入口、回调入口 | 玩家 ELB：443 → 网关。回调 ELB：443（厂商 / 支付渠道 IP 地址组白名单）+ 8443（RunPod webhook，靠 URL token） |
| 安全 | WAF、DDoS 高防 | 防护、地域限制 | 见 §2.1；按活动规模报备防护等级 |
| 加速 | CDN | 静态资源、游戏列表 | `/api/lobby/games` 短 TTL；**配置区域访问控制**（缓存命中的请求不经过 WAF 和网关） |
| 计算 | CCE Turbo | 全部服务 | 控制节点高可用（3 个）；节点池 general / callback / consumer（标签、污点见 deploy/README §7）；命名空间 `bingo`（我们的服务）和 `bingo-infra`（xxl-job-admin）；插件：集群弹性引擎、Metrics Server、云原生监控、DEW 密钥管理 |
| 计算 | KEDA（Helm 安装） | 全部工作负载的扩缩容（CPU + 晚高峰定时下限 + 消费者 lag） | 按 `autoscaling.yaml` 文件头安装；各档数值也在文件头 |
| 镜像 | SWR | 镜像仓库 | 开启镜像安全扫描 |
| 数据库 | TaurusDB | 业务数据 | 共享实例、钱包分片实例、注单 + 稽核分片实例（各阶段实例数见 §3）；每实例 1 主 + 1 个同规格跨 AZ 只读 + 数据库代理（读写分离），应用连代理地址 |
| 缓存 | DCS Redis 集群版 | 会话、限流、等候室 | 只在 VPC 内访问 |
| 消息 | DMS for Kafka | 全部消息（唯一的 MQ） | 应用走 VPC 内明文；SASL_SSL 只读用户给 KEDA 和 StarRocks；topic 和 `.DLT` 见 deploy/README §4，**分区数一次建到位** |
| 存储 | OBS | KYC 证件 | 私有桶 `bingo-kyc`，SSE-KMS，生命周期按牌照留存期；CCE 节点委托授权读写，不放 AK/SK（上线前确认 Pod 能取到节点委托的临时凭据） |
| 密钥 | DEW（KMS + CSMS） | 全部密钥 | 数据库、Redis、Nacos、XXL token、`ADMISSION_PASS_SECRET`、RunPod key 和 webhook token、厂商和支付渠道密钥；每个值一个 CSMS 凭据；`dew-secrets.yaml` + `dew-patch.yaml` 挂到 `/mnt/csms`，并同步成和测试环境同名的 K8s Secret |
| 注册配置 | CSE 微服务引擎（Nacos） | 注册 + 配置中心 | 命名空间 `prod`，账号 `bingo-app`（命名空间读写）；区域没有 CSE 时在 CCE 自建 3 节点集群 |
| 分析 | CloudTable StarRocks | 报表、对账（bingo-reconcile 的全部汇总都从这里读）、风控 | `deploy/starrocks/bingo_dw.sql`；只从 Kafka 导入；reconcile 用只读账号连 FE 9030 端口 |
| 流计算 | Flink（CCE 上 Flink Kubernetes Operator） | wallet_txn → Kafka | 每个钱包实例一个作业，`deploy/flink/wallet_txn_cdc.sql`；checkpoint 存 OBS |
| 调度 | XXL-Job admin | 定时任务 | `xxl-job-admin.yaml`，2 副本 |
| 可观测 | AOM、LTS、CES + SMN、OTel Collector | 指标、日志、告警、链路 | 容器日志进 LTS；链路追踪自建后端（APM 在亚太不提供） |
| 运维安全 | HSS、CBH、IAM、CTS | 主机防护、审计 | 运维经堡垒机；IAM 最小权限；开启操作审计 |

### 2.1 WAF 和地域限制

华为云 WAF 能按地理位置拦截，但**不能把客户端所在国家写进请求头**（转发头只支持 `$remote_addr` 等固定变量）。网关的地域限制在缺这个头时会拒绝所有请求（HTTP 451），所以生产这样配：

1. WAF **地理位置访问控制**：只允许菲律宾，其余拦截。
2. WAF **转发自定义头部**：固定值 `X-Country-Code: PH`。能带着这个头到达网关的请求，一定经过了 WAF，并且来自菲律宾。
3. 玩家 ELB 只接受 WAF 回源 IP 段，不让人绕过 WAF 直连。
4. `GEO_FENCE_ENABLED` 保持默认 `true`。
5. 实际上传几张证件照，确认 WAF 不误拦 `POST /api/kyc/images`（multipart，最大 10 MB）。

## 3. 各阶段配置：5 万 / 10 万 / 100 万在线

测试环境见 §1。下面三档都是生产环境，按**峰值同时在线**划分。

### 3.1 按什么算

- **流量模型**（来自 capacity-1m.md）：
  - 每个在线玩家约 8 秒转一次，每转一次钱包 2 笔（下注、派彩）。
  - 在线 N 人时：钱包 N/4 TPS，注单同量级，稽核约为钱包的 15%。
  - 玩家 API 约 N/10 QPS，厂商回调约 N/4 QPS。
- **单个 32 核主库按约 2 万 TPS 估，还没压测**。高峰时每个主库控制在 60–70% 以内。实测数字出来后，按比例调整实例数。
- **每个 TaurusDB 实例都是"1 主 + 1 个同规格只读节点"**：主库故障时由只读节点接管，规格小了接管后会直接过载。
- **Pod 规格统一 4 核 8G**；32 核 64G 的节点放 6 个 Pod，16 核 32G 的节点放 3 个。

### 3.2 配置表

| 组件 | 5 万在线 | 10 万在线 | 100 万在线 |
|---|---|---|---|
| 高峰写入 | 钱包 1.25 万 TPS + 注单 1.25 万 + 稽核约 2 千 | 钱包 2.5 万 + 注单 2.5 万 + 稽核约 4 千 | 钱包 25 万 + 注单 25 万 + 稽核约 4 万 |
| **TaurusDB（32 核 128G，除注明外）** | **3 个实例** | **5 个实例** | **33 个实例** |
| 　钱包 | W：`bingo_wallet_00..15` | W1：`00..07`，W2：`08..15` | 16 个，每个实例一个库 |
| 　注单 + 稽核 | B：`bingo_bet_record_00..15` + `bingo_turnover_00..15` | B1：`00..07`，B2：`08..15`（两类各自的同号库） | 16 个，每个实例放同号的注单库 + 稽核库 |
| 　共享库（用户、KYC、支付、大厅、风控、活动、对账、XXL） | S：8 核 32G | S：16 核 64G | S：32 核 128G |
| 　存储（当前保留期） | 约 20 TB | 约 39 TB | 约 390 TB |
| **CCE 节点** | 16 核 32G × 16 台 | 32 核 64G × 13 台 | 32 核 64G：包年 51 台 + 晚高峰按需约 27 台 |
| 　钱包 / 网关 / 回调 / 注单 Pod（峰值） | 6 / 3 / 6 / 4 | 10 / 5 / 10 / 6 | 100 / 50 / 100 / 64 |
| 　其余服务 Pod | 各 2 个 | 各 2–3 个 | 见 capacity-1m.md §2 |
| 　CCE 集群管理规模 | 200 节点档（高可用） | 200 节点档 | 1000 节点档 |
| **DCS Redis** | 主备 8 GB | 集群版 16 GB | 集群版 64 GB |
| **DMS Kafka** | 3 broker × 4 核 8G，共 1.5 TB | 3 broker × 8 核 16G，共 3 TB | 6 broker × 16 核 32G，共约 25 TB |
| 　分区数（第一天定死） | 192 / 96 / 24 | 同左 | 同左 |
| **Flink CDC** | 1 个作业（实例 W） | 2 个作业（W1、W2） | 16 个作业 |
| **StarRocks** | 3 FE 4 核 16G + 3 BE 8 核 32G | 3 FE 4 核 16G + 4 BE 16 核 64G | 3 FE 8 核 32G + 约 30 BE 16 核 64G |
| 　存储（明细留 400 天、3 副本） | 满一年约 41 TB | 约 83 TB | 约 830 TB |
| **扩缩容数值** | `autoscaling.yaml` 默认档 | 按文件头改 3 个数 | 按文件头改 3 个数 |
| **ELB** | 独享型 × 2（玩家、回调） | 同左 | 同左，回调按厂商拆多个 |
| **WAF** | 云模式铂金版（1 万 QPS） | 铂金版 + 5 个 QPS 扩展包 | 独享模式约 30 个 WI-500 实例（云模式扩展包太贵） |
| **DDoS** | 云原生防护企业版 | 同左 | 高防（无限次防护） |
| **公网流量** | 峰值约 120 Mbps，约 14 TB/月 | 约 250 Mbps，约 29 TB/月 | 约 2.2 Gbps，约 285 TB/月 |
| **估算月费**（挂牌价，数据积满后） | **约 4.1 万美元** | **约 7.7 万美元** | **约 57 万美元** |
| 　缩短保留期后（注单 7 天、稽核 30 天、StarRocks 明细 90 天） | 约 3 万 | 约 5.5 万 | 约 34 万 |

- 月费按新加坡挂牌价、包年折算，不含商务折扣，也不含 RunPod、厂商分成、支付通道费。
- 钱包流水在 TaurusDB 保留 30 天（幂等窗口，要覆盖厂商最长的重试 / 迟到回滚 / 重新结算窗口），比 7 天多出的存储在 100 万在线时约每月 1.2 万美元，5 万、10 万在线按比例。
- StarRocks 存储是逐月增长的，第一年的账单比表里低。
- 各阶段钱花在哪：100 万在线时，大头是 StarRocks 和 TaurusDB 的存储，以及 TaurusDB 计算；5 万、10 万在线时，TaurusDB 计算和存储占四成左右。

### 3.3 什么时候升到下一档

| 信号（高峰时段） | 动作 |
|---|---|
| 某个 TaurusDB 主库 CPU 持续 > 60%，或钱包 p99 > 50 ms | 准备搬库：DRS 全量同步要几个小时，要提前做，按 application-sharding.yml 文件头的步骤执行 |
| 钱包或回调 Pod 数接近 `maxReplicaCount` | 换到下一档的 KEDA 数值（`autoscaling.yaml` 文件头）和节点池上限；钱包先核对连接预算 |
| Kafka 某个消费组 lag 持续增长 | KEDA 上限、broker 规格 |
| 峰值在线接近本档上限的 80% | 按下一档的配置表预留资源；大促前向华为云报备 |

**搬库顺序**：先把钱包从 B / S 分出去（资金链路优先隔离），再拆注单和稽核。每次都是整库搬，这部分玩家停写几秒，代码不用改。

### 3.4 固定不变的

- 1024 个逻辑分片。
- 每类 16 个库（`bingo_wallet_00..15` 等）：每类最多分散到 16 个实例，再往上只能升单实例规格（计算器里香港、新加坡最大 32 核）。
- Kafka 分区数 192 / 96 / 24。

每个分片数据源 `dsNN` 连自己的库 `bingo_wallet_NN`（注单、稽核同理），多个库放在同一实例上互不影响。连接数 = 实例上的库数 × `DB_POOL_SIZE` × Pod 数，别超过 `max_connections` 的 70%。

## 4. 生产部署步骤

1. **账号与网络**：IAM（运维组、只读组、Jenkins 用的发布账号，开 MFA 和 CTS）；VPC、子网、NAT、EIP、VPN。
2. **数据与中间件**：
   1. TaurusDB 参数模板（同 §1.3，binlog 保留 ≥ 3 天），创建实例并开数据库代理。`99_local_dev_account.sql` 不要执行。
   2. 建库建表：
      - 共享实例：`00_databases` 里除钱包、注单、稽核外的库，加 `01_user`、`03_game`、`04_lobby`、`06_payment`、`07_risk`、`08_promotion`、`09_reconcile`、`11_kyc`、XXL-Job 表。
      - 钱包实例：`sh deploy/shard_schemas.sh wallet <起> <止>`，只建这个实例上放的库（例如 00 07）。
      - 注单 + 稽核实例：`shard_schemas.sh bet_record <起> <止>` 和 `shard_schemas.sh turnover <起> <止>`。
      - 账号用通配授权，例如 ``GRANT ALL PRIVILEGES ON `bingo\_bet\_record\_%`.* TO 'bingo_bet_record'@'%';``
   3. 数据库账号：每个服务一个；`bingo_cdc`（钱包实例，复制权限）；`report_ro`（共享实例只读，给 StarRocks）。
   4. Redis、Kafka（topic、`.DLT`、用户）、OBS、DEW 密钥。
3. **CCE**：集群、三个节点池、插件；Helm 安装 KEDA；`00-namespace`、`01-networkpolicy`（生产两个 ELB 子网 CIDR 都填）、`02-common-config`、`bingo-kyc-env`、`bingo-reconcile-env`（StarRocks FE 地址）；DEW：`dew-secrets.yaml` 按服务渲染并 apply（每个服务的额外密钥见文件头的表）。
4. **平台组件**：
   1. Nacos（CSE）导入 `deploy/nacos/*.yaml`。
   2. `xxl-job-admin.yaml`（命名空间 `bingo-infra`）。
   3. OTel Collector。
   4. 每个钱包实例一个 Flink CDC 作业（`server-id` 区间互不重叠）。
   5. StarRocks 执行 `bingo_dw.sql`（先上传 DMS CA 证书），确认 `SHOW ROUTINE LOAD FROM bingo_dw;` 在跑。
5. **应用**，按 deploy/README §7 的顺序：
   1. `bingo-wallet.yaml`
   2. `bingo-game-integration.yaml`
   3. 两个 shards 文件
   4. `service-template.yaml` 渲染 9 个服务
   5. `bingo-gateway.yaml`
   6. 每个工作负载 `kubectl patch` 一次 `dew-patch.yaml`（切到 DEW 挂载，会滚动重启一次）。
   7. `autoscaling.yaml`：默认是 5 万在线档，更高的档按文件头的表改。
6. **业务配置**：
   - 厂商：Nacos `bingo-game-integration.yaml`（地址、商户号、密钥引用、回调 IP 白名单）；`lobbyGameSyncJob` 同步目录后，运营审核每个游戏的 `game_type` 再上架。**逐家确认厂商最长的重试 / 迟到回滚 / 重新结算窗口不超过 30 天**（钱包幂等窗口 `bingo.wallet.retention.keep`），超过的先调大这个值再接入。
   - 支付渠道：`payment_channel` 表，先 `DISABLED`，联调通过再开。
   - KYC：config 表改用 webhook（§1.6）。
   - 稽核：`turnover_setting`（各线路的充值倍数、清零阈值）。
   - 活动：`/internal/promotion/activities` 创建（DRAFT），审批后 ONLINE；`bingo.promotion.first-deposit.enabled` 在奖金条款上线后再打开。
   - XXL-Job：§6 全部任务；`walletTxnRetentionJob` 在 StarRocks 开始导入流水**之后**才开。
7. **入口与切流**：
   1. 先开 `ingress-callback.yaml`，与厂商、支付渠道联调回调。
   2. WAF（§2.1）、DDoS 高防、CDN、DNS。
   3. 最后开 `ingress-player.yaml`。
   4. 灰度：先只放白名单测试账号，冒烟通过再全量。

## 5. CI/CD：本地 GitLab + 自建 Jenkins

仓库根目录的 [Jenkinsfile](../Jenkinsfile) 已写好，流程是：
1. 测试。
2. 打镜像，推到 SWR。
3. 生产环境需人工审批。
4. `kubectl set image`，然后 `rollout status`。

**Jenkins 机器**（和 GitLab 同一个内网）：
- Linux，装 Docker 和 kubectl，**不用装 JDK 和 Maven**：构建在 `maven:3.9-eclipse-temurin-25` 容器里跑，集成测试（Testcontainers）通过宿主机的 Docker 起 MySQL 等容器。
- Jenkins agent 直接跑在宿主机上，打标签 `docker`。如果 Jenkins 本身跑在容器里，挂载给 Maven 容器的工作目录路径在宿主机上不存在，构建会找不到代码。
- 需要能访问：
  - GitLab；
  - Maven 中央仓库和 Docker Hub（可以换成内网 Nexus / SWR 镜像）；
  - SWR（HTTPS 公网）；
  - CCE API：测试环境走 EIP，生产走 VPN。
- 插件：Pipeline、Git、GitLab（webhook 触发）、Credentials Binding、JUnit、Timestamper。

**凭据**：

| ID | 类型 | 内容 |
|---|---|---|
| `swr-login` | 用户名 / 密码 | SWR 长期有效登录指令里的用户名（`<区域>@<AK>`）和登录密钥 |
| `kubeconfig-staging` | Secret file | 测试环境集群的 kubeconfig（EIP 地址） |
| `kubeconfig-prod` | Secret file | 生产集群的 kubeconfig（内网地址）；IAM 用户只授权命名空间 `bingo` 的工作负载更新 |

**流水线参数**：
- `SERVICES`：默认 `bingo-wallet,bingo-user,bingo-kyc,bingo-gateway`，按这个顺序发布。
- `RUN_IT`：默认跑集成测试。
- `DEPLOY_TO`：none / staging（测试环境）/ prod，prod 前有人工审批。

**约定**：
- 镜像 tag = 提交号前 12 位，不用 `latest`。回滚就是用上一个 tag 再跑一次发布。
- 流水线只更新镜像，不 apply 清单。ConfigMap、`autoscaling.yaml` 这类清单变更手工走审批。
- `rollout status` 的超时：钱包、回调 180 分钟（没有 `MaxUnavailableStatefulSet` 特性门控时 StatefulSet 一次换一个 Pod，100 个 Pod 约 2 小时），其余 20 分钟；整条流水线最长 6 小时。
- 生产发布避开 18:00–01:30 晚高峰；钱包、回调服务单独发，其余可以一起发。
- GitLab 里给 `main` 开保护分支和合并请求审批；webhook 触发 `DEPLOY_TO=none` 的构建，发布手动点。

## 6. XXL-Job 任务清单

每个服务一个执行器（appname = 服务名）。路由策略“第一个”，阻塞处理“丢弃后续调度”。cron 按 UTC+8，下表为建议值。

| 执行器 | 任务 | 建议 cron | 说明 | 测试环境 |
|---|---|---|---|---|
| bingo-user | `rgExpiryJob` | `0 * * * * ?` | 自我排除 / 冷静期到期解锁钱包 | ✓ |
| bingo-user | `rgWalletLockRetryJob` | `0 * * * * ?` | 重试钱包锁定 | ✓ |
| bingo-user | `userLineWalletSyncJob` | `*/30 * * * * ?` | 线路迁移同步到钱包 | ✓ |
| bingo-kyc | `kycSubmitRetryJob` | `*/10 * * * * ?` | 重新提交 status 0 的记录 | ✓ |
| bingo-kyc | `kycResultPollJob` | `*/10 * * * * ?` | 查 RunPod `/status`（测试环境唯一的结果来源） | ✓ |
| bingo-kyc | `kycTimeoutJob` | `*/10 * * * * ?` | 5 分钟没结果 → 状态 4 拒绝 | ✓ |
| bingo-kyc | `kycUserSyncJob` | `*/10 * * * * ?` | KYC 结果同步到用户 | ✓ |
| bingo-wallet | `walletTxnRetentionJob` | `0 */10 5-10 * * ?` | 流水只留 30 天（幂等窗口）；**StarRocks 开始导入流水后再开启** | |
| bingo-game-integration | `transferRecoveryJob` | `*/30 * * * * ?` | 转账钱包卡单恢复 | |
| bingo-game-integration | `openBetRetentionJob` | `0 17 * * * ?` | 删除关闭超过 7 天的 `open_bet`（YGR 捕鱼未结注单跟踪） | |
| bingo-lobby | `lobbyGameSyncJob` | `0 0 */6 * * ?` | 同步厂商游戏目录 | |
| bingo-bet-record | `betRecordProviderPullJob` | `0 * * * * ?` | **每个厂商建一个任务，参数 = 厂商代码** | |
| bingo-bet-record | `betRecordUnsettledRoundJob` | `0 * * * * ?` | 超时未结算局 | |
| bingo-bet-record | `betRecordRoundEventRepublishJob` | `0 * * * * ?` | 补发未确认的局结算事件 | |
| bingo-bet-record | `betRecordPartitionJob` | `0 0 4 * * ?` | 注单分区维护 | |
| bingo-payment | `depositRecoveryJob` | `0 * * * * ?` | 充值对单 | |
| bingo-payment | `withdrawRecoveryJob` | `0 * * * * ?` | 提现对单 | |
| bingo-risk | `riskDecisionRedeliveryJob` | `0 * * * * ?` | 审核结果补投 | |
| bingo-promotion | `bonusGrantRetryJob` | `0 * * * * ?` | 奖金发放重试 | |
| bingo-promotion | `rebateSettleJob` | `0 30 2 * * ?` | 返水结算（前一业务日） | |
| bingo-promotion | `promotionRoundAppliedRetentionJob` | `0 20 4 * * ?` | 删除 35 天前的按局去重记录 | |
| bingo-reconcile | `reconHourlyJob` | `0 10 * * * ?` | 小时对账（StarRocks 汇总） | |
| bingo-reconcile | `reconDailyGgrJob` | `0 30 1 * * ?` | 日 GGR（StarRocks 汇总） | |
| bingo-reconcile | `reconDailyDetailJob` | `0 0 3 * * ?` | 日终逐局对账（StarRocks） | |
| bingo-reconcile | `reconProviderSettlementJob` | `0 0 5 * * ?` | 厂商结算草稿 | |
| bingo-reconcile | `reconRtpMonitorJob` | `0 0 6 * * ?` | RTP 监控 | |
| bingo-turnover | `turnoverPartitionJob` | `0 10 4 * * ?` | 稽核记录分区维护 | |

## 7. 生产上线前检查

- **冒烟**：注册 → KYC → 充值（测试渠道）→ 开游戏（DEMO 厂商）→ 下注 / 派彩 → 注单 → 稽核扣减 → 提现 → 风控审核 → 出款。
- **压测**：按 capacity-1m.md §8；确认 KEDA、节点扩容的反应时间，以及晚高峰 cron 触发器的实际触发时间。
- **演练**：
  - TaurusDB 主备切换、杀 Pod、单 AZ 故障、Kafka broker 重启、Redis 主备切换。
  - 等候室和降级开关各实际打开一次。
- **告警**：网关 5xx 与 451 比例、登录失败率、KYC 状态 4 比例和 status 0 堆积、钱包 p99、回调错误码、Kafka lag、日志里的 `ALERT`（业务事件重试超过 10 分钟、outbox 重试超过 20 次、稽核无法自动回退）、`.DLT` 新消息、Flink 重启、Routine Load 暂停（`max_error_number = 0`，坏消息会让导入暂停）、数据库连接数、节点池余量。
- **备份恢复**：TaurusDB 自动备份 + 一次实际恢复演练；OBS 版本控制。
- **合规**：年龄（21 岁）、地域限制（§2.1）、自我排除 / 冷静期、KYC 才能游戏和提现、审计日志、PII 字段加密（DEW KMS）。
