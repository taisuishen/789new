# 789bingo 部署与基础设施

```
Dockerfile / .dockerignore     # 仓库根目录：所有服务共用的运行时镜像
deploy/
  docker-compose.yml           # 本地开发用中间件（只有基础设施，服务在 IDE 里跑）
  sql/                         # 建库建表脚本（由各服务维护），MySQL 首次启动时自动执行
  nacos/                       # 需导入 Nacos 的配置样例
  k8s/                         # CCE 清单（纯 YAML）；扩缩容只用 KEDA（autoscaling.yaml）
  shard_schemas.sh             # 分片库建表语句生成（不放在 sql/ 里：本地 MySQL 会执行 sql/ 下所有脚本）
  starrocks/                   # 报表 / 对账 / 风控分析：StarRocks 建表、Routine Load、JDBC Catalog
  flink/wallet_txn_cdc.sql     # wallet_txn -> Kafka 的 Flink CDC 作业
```

> **100 万在线的容量模型、各服务规格、弹性机制（KEDA / 节点弹性）、压测方案和大促检查清单**见 [docs/capacity-1m.md](../docs/capacity-1m.md)。本文只讲怎么部署。

## 1. 启动本地基础设施

需要 Docker Desktop（Compose v2）。

```bash
cd deploy
docker compose up -d
docker compose ps          # 除 kafka-init（执行完即退出）外都应为 healthy
```

| 组件 | 地址（仅绑定 127.0.0.1） | 账号 |
|---|---|---|
| MySQL 8.4 | 127.0.0.1:3306 | root / root，应用账号 bingo / bingo（`sql/99_local_dev_account.sql`，只给本地用） |
| Redis 7.4 | 127.0.0.1:6379 | 默认无密码（设置 `REDIS_PASSWORD` 后启用） |
| Nacos 3.2.4 | API 127.0.0.1:8848，gRPC 9848，控制台 http://127.0.0.1:18080 | nacos / 首次访问时设置 |
| Kafka 4.x (KRaft) | 127.0.0.1:9092 | - |
| XXL-Job Admin 3.4.2 | http://127.0.0.1:18081 | admin / 123456（官方默认，登录后修改） |

- Nacos 3 的控制台默认占用 8080，与 bingo-gateway 冲突，所以映射到宿主机 18080。
- 可在 `deploy/.env` 里覆盖：`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD`、`NACOS_AUTH_TOKEN`、`XXL_JOB_TOKEN`。
- `sql/` 下的脚本只在 MySQL 数据卷为空时执行一次。需要重新初始化：`docker compose down -v`（会删除所有本地数据）。
- Kafka 数据不持久化，每次 `up` 由 kafka-init 重新建 topic。
- **时区统一 UTC+8**：所有容器 `TZ=Asia/Manila`（UTC+8，无夏令时），MySQL `--default-time-zone=+08:00`，Kafka / XXL-Job 额外设置 `-Duser.timezone=Asia/Manila`，XXL-Job 的 cron 按 UTC+8 触发。改动时区后需要 `docker compose down -v` 重建 MySQL 数据卷（已有数据按旧时区写入）。

## 2. 导入 XXL-Job 表结构

xxl-job-admin 需要官方表结构，本仓库不复制该脚本：

1. 下载 v3.4.2 的 `doc/db/tables_xxl_job.sql`：
   https://github.com/xuxueli/xxl-job/blob/3.4.2/doc/db/tables_xxl_job.sql
2. 首次启动前保存为 `deploy/sql/90_xxl_job.sql`，会自动执行（脚本自带 `CREATE DATABASE xxl_job` / `USE xxl_job`）。
3. MySQL 已经初始化过时，手动导入：
   ```bash
   docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < /docker-entrypoint-initdb.d/90_xxl_job.sql'
   docker compose restart xxl-job-admin
   ```
4. 登录 http://127.0.0.1:18081 ->「执行器管理」，为每个服务新建执行器：AppName = `spring.application.name`（bingo-user、bingo-wallet ……），注册方式选「自动注册」。
5. 调度中心在容器里，要回调宿主机上的执行器。自动探测的 IP 不通时，给服务设置
   `XXL_JOB_EXECUTOR_ADDRESS=http://host.docker.internal:<执行器端口>`。

## 3. 导入 Nacos 配置

1. **初始化管理员密码**：打开 http://127.0.0.1:18080，首次访问时设置 `nacos` 用户的密码；也可以调用 API：
   `curl -X POST http://127.0.0.1:8848/nacos/v3/auth/user/admin -d 'password=<密码>'`
2. **命名空间**：本地使用 `public`（`NACOS_NAMESPACE` 留空）。测试 / 生产环境新建命名空间（例如 ID 为 `test` / `prod`），并创建用户 `bingo-app` 给服务使用，授予该命名空间的**读写**权限（服务注册实例是写操作，只读账号注册不上）。
3. **导入配置**：Group 一律为 `BINGO`，格式 YAML，Data ID 就是文件名：
   - `nacos/bingo-common.yaml`：所有服务共享；
   - `nacos/bingo-game-integration.yaml`：游戏厂商配置；
   - 各服务自己的 `<service-name>.yaml` 可选（服务以 `optional:nacos:` 方式导入）。

   **方式 A：控制台**：配置管理 -> 配置列表 -> 创建配置，填写 Data ID、Group=BINGO，格式选 YAML，粘贴文件内容后发布。

   **方式 B：Open API**（bash / Git Bash，在仓库根目录执行；`type` 参数名未核实，控制台里格式不对时手动改为 YAML）：
   ```bash
   NACOS=http://127.0.0.1:8848
   TOKEN=$(curl -s -X POST "$NACOS/nacos/v3/auth/user/login" -d 'username=nacos' --data-urlencode "password=$NACOS_PASSWORD" \
     | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
   for f in deploy/nacos/*.yaml; do
     curl -s -X POST "$NACOS/nacos/v3/admin/cs/config" -H "accessToken: $TOKEN" \
       --data-urlencode "dataId=$(basename "$f")" \
       --data-urlencode 'groupName=BINGO' \
       --data-urlencode 'type=yaml' \
       --data-urlencode "content@$f"
     echo
   done
   ```
   导入到其他命名空间时加 `--data-urlencode 'namespaceId=prod'`。

说明：配置里的 `${KAFKA_SERVERS:...}` 这类占位符在各服务启动时由环境变量解析，所以本地和生产共用同一份内容。厂商密钥 `secret: dew:csms/<secret-name>` 在运行时从 DEW CSMS 读取，只有本地开发允许写明文。

## 4. Kafka 的 topic

消息只用 Kafka：数据流（`bingo.wallet.txn`、`bingo.round.settled`、`bingo.provider.bet`，key = userId）和业务事件（`bingo.payment.deposit-succeeded`、`bingo.payment.withdraw-requested`、`bingo.payment.withdraw-finished`、`bingo.promotion.bonus-granted`，经事务 outbox 发送，key = 订单号 / 业务号）。业务事件消费失败会一直按退避重试（最长 1 分钟一次），约 10 分钟后每次失败都打 ALERT 日志，这个分区等它成功再往后消费：业务事件丢了就是少一条稽核要求或余额不对，宁可等。只有解析不了的消息立即转入 `<topic>.DLT`（ALERT），排查后重新发回原 topic 即可（消费者按 key 幂等）。outbox 同理：发送失败一直重试（最长 5 分钟一次），失败 20 次后每次都打 ALERT，已发送的记录 7 天后清理。

- 本地：`kafka-init` 自动创建全部 topic 和 DLT（12 分区）。查看结果：`docker compose logs kafka-init`。手动创建：
  ```bash
  docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092     --create --if-not-exists --topic bingo.wallet.txn --partitions 12 --replication-factor 1
  ```
- 生产（DMS for Kafka，控制台创建，副本数 3，`min.insync.replicas=2`，关闭自动创建）：
  - 数据流分区数上线前定好，以后加分区会改变 userId 到分区的映射，破坏单用户内的顺序。按 100 万在线规划：`bingo.wallet.txn` **192**、`bingo.round.settled` **96**、`bingo.provider.bet` **24** 个分区（消费者每 Pod 3 个线程，所以 KEDA 的最大副本数 = 分区数 / 3，见 docs/capacity-1m.md）。
  - 业务事件量小：各 12 个分区；对应的 `.DLT` 用**相同分区数**（失败记录保留原分区号）。
  - **保留时间显式设置**（不依赖实例默认值）：数据流 `retention.ms` = 7 天（消费者、StarRocks 导入、Flink 停摆后追数的上限，磁盘按这个算），业务事件 14 天，`.DLT` 30 天（留足排查和重投的时间）。
  - 另建一个只读 DMS 用户给 KEDA 查询消费组 lag（`secrets-example.yaml` 中的 `keda-dms-kafka`），StarRocks 的 Routine Load 也用这个只读用户。

## 5. 在 IDE 里运行服务

每个服务的 Run Configuration 中设置以下环境变量（IntelliJ：Run/Debug Configurations -> Environment variables）：

| 变量 | 本地值 | 说明 |
|---|---|---|
| `DB_HOST` / `DB_PORT` | `127.0.0.1` / `3306` | |
| `DB_USER` / `DB_PASSWORD` | `bingo` / `bingo` | |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` | |
| `REDIS_PASSWORD` | 空 | 与 compose 的 `REDIS_PASSWORD` 保持一致 |
| `NACOS_ADDR` | `127.0.0.1:8848` | |
| `NACOS_NAMESPACE` | 空（= public） | |
| `NACOS_USERNAME` / `NACOS_PASSWORD` | `nacos` / 第 3 步设置的密码 | |
| `NACOS_ENABLED` | `true` | `false` 时不连 Nacos（没有配置中心，也没有服务发现） |
| `KAFKA_SERVERS` | `127.0.0.1:9092` | |
| `XXL_JOB_ADMIN` | `http://127.0.0.1:18081` | |
| `XXL_JOB_TOKEN` | `bingo-local-dev-xxl-token` | 与 xxl-job-admin 的 accessToken 一致 |
| `WORKER_ID` | 每个实例唯一，0..1023 | 同一服务起多个实例时必须不同 |
| `WALLET_AFTER_COMMIT_PUBLISH` | `true` | 仅 bingo-wallet：本地没有 Flink CDC，由服务提交后直接发 Kafka |
| `ADMISSION_ENABLED` | `false` | 仅 bingo-gateway：本地关掉等候室；开启时必须设置 `ADMISSION_PASS_SECRET`（≥ 32 字符），否则网关拒绝启动 |
| `GEO_FENCE_ENABLED` | `false` | 仅 bingo-gateway：本地没有 WAF 注入国家头，关掉地域限制（生产必须开启） |
| `BINGO_PII_KEY_K1` / `BINGO_PII_INDEX_KEY` | 任意本地值（前者是 32 字节的 Base64，后者 ≥ 32 字符） | 仅 bingo-user、bingo-kyc：个人信息字段加密（`PiiCipher`），不设不能启动 |
| `OBS_ENDPOINT` / `OBS_KYC_BUCKET` | 你的 OBS 区域终端节点和桶 | 仅 bingo-kyc：没有默认值，不设不能启动；或者设 `OBS_ENABLED=false`（不能上传证件） |

各服务 HTTP 端口：gateway 8080、user 8101、wallet 8102、game-integration 8103、lobby 8104、bet-record 8105、payment 8106、risk 8107、promotion 8108、reconcile 8109、turnover 8110、kyc 8111。本地不加载 OpenTelemetry agent。

## 6. 构建镜像

```bash
mvn -DskipTests package

# OpenTelemetry Java agent：只需下载一次，放到 otel/（不要提交到 git）
mkdir -p otel
curl -L -o otel/opentelemetry-javaagent.jar \
  https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.31.1/opentelemetry-javaagent-2.31.1.jar

docker build --build-arg JAR=bingo-wallet/bingo-wallet-service/target/bingo-wallet-service-0.1.0-SNAPSHOT.jar \
  -t bingo-wallet:0.1.0-SNAPSHOT .
```

- jar 路径：`bingo-gateway/target/bingo-gateway-0.1.0-SNAPSHOT.jar`，其余服务为 `<module>/<module>-service/target/<module>-service-0.1.0-SNAPSHOT.jar`（例如 `bingo-user/bingo-user-service/target/bingo-user-service-0.1.0-SNAPSHOT.jar`）。
- 不带 agent 构建：加 `--build-arg OTEL_AGENT=false`（需要 BuildKit，Docker 23+ 默认启用）。
- 镜像以 uid 10001 运行，`ENTRYPOINT` 为 exec 形式，固定 `-Duser.home=/tmp -XX:+ExitOnOutOfMemoryError`（根文件系统只读，Nacos 客户端的缓存和日志写到 /tmp）；其他 JVM 参数通过 `JDK_JAVA_OPTIONS` 传入，默认 `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0`，覆盖时两项都要写上。agent 通过镜像内的 `JAVA_TOOL_OPTIONS` 启用，覆盖这个变量时要保留 `-javaagent`。日志只输出到 stdout，由 LTS 采集。
- 推送到 SWR：在 SWR 控制台获取登录指令登录后执行
  `docker tag bingo-wallet:0.1.0-SNAPSHOT swr.<region>.myhuaweicloud.com/<org>/bingo-wallet:0.1.0-SNAPSHOT`，再 `docker push`。

## 7. 部署到 CCE

完整上线顺序（云服务开通、第一期最小部署、Jenkins 流水线、XXL-Job 任务清单）见 [docs/go-live.md](../docs/go-live.md)；本节只说明清单本身。

**前置条件**

- CCE 集群 Kubernetes >= 1.28（`WORKER_ID` 依赖 Pod 标签 `apps.kubernetes.io/pod-index`），VPC 网络模式。推荐 CCE Turbo：独享型 ELB 可以直通 Pod，Ingress 后端可以用 ClusterIP；CCE 标准集群需要把 Ingress 后端的 Service 改为 NodePort。集群管理规模按 100 万在线选 ≥ 200 节点档。
- 节点池（都跨 3 个 AZ，各 AZ 最小数相同；上下限见 docs/capacity-1m.md §3.4）：
  - general：K8s 标签 `bingo.io/pool=general`，无污点（wallet、gateway、user、lobby、payment 和系统组件）；
  - callback：标签 `bingo.io/pool=callback`，污点 `bingo.io/pool=callback:NoSchedule`（只有 bingo-game-integration）；
  - consumer：标签 `bingo.io/pool=consumer`，污点 `bingo.io/pool=consumer:NoSchedule`（bet-record、reconcile、turnover、promotion）。
- 弹性相关插件：
  - 「Kubernetes Metrics Server」（KEDA cpu 触发器的指标）；
  - 「CCE集群弹性引擎」（节点池自动伸缩），各节点池开启弹性伸缩并设置缩容最小数 / 扩容最大数 / 冷却时间；
  - **KEDA 不是 CCE 插件**，用 Helm 安装（`helm install keda kedacore/keda -n keda --create-namespace`，版本按 KEDA 兼容矩阵与集群版本对应），见 `k8s/autoscaling.yaml` 文件头。不需要「CCE容器弹性引擎」（CronHPA），定时下限由 KEDA 的 cron 触发器负责（带时区）。
- 两个**独享型** ELB（player / callback），各有独立 EIP，最好在不同子网；证书上传到 ELB；为 callback ELB 创建 IP 地址组（包含所有游戏厂商和支付渠道的回调 IP）。两个域名都接入 WAF，callback 域名配置 IP 白名单。
- TaurusDB：bingo-wallet 使用 16 个分片库 `bingo_wallet_00..15`（1024 个逻辑分片，每库 64 个；建库用 `shard_schemas.sh`），100 万在线时每库一个实例（1 主 + 1 跨 AZ 只读，可用 Serverless），库少的阶段可以多个库共用一个实例；地址填在 `bingo-wallet.yaml` 的 ConfigMap `bingo-wallet-shards`；bingo-bet-record / bingo-turnover 同样各 16 个分片库（`bingo_bet_record_NN` / `bingo_turnover_NN`，地址在 `bingo-bet-record-shards.yaml` / `bingo-turnover-shards.yaml`）；其他服务共用实例；DCS Redis（集群版）；DMS Kafka；Nacos 集群（自建或 CSE Nacos 引擎）。
- 安装「CCE 密钥管理（对接 DEW）」插件，密钥保存在 CSMS，每个值一个 CSMS 凭据；`k8s/dew-secrets.yaml` 定义挂载和同步（Kubernetes Secret 的名字、键和测试环境完全相同），`k8s/dew-patch.yaml` 给每个工作负载加上 CSI 卷（挂到 `/mnt/csms`，`dew:csms/<名字>` 引用读这里）和 ServiceAccount。
- 两个命名空间：`bingo` 只放我们自己的镜像（Pod 安全级别 restricted）；`bingo-infra` 放第三方基础组件（xxl-job-admin，测试环境还有单节点 Nacos，它们以 root 运行）。网络策略默认拒绝，只放行声明过的调用方（`01-networkpolicy.yaml` 文件头有调用关系表；新增 Feign 客户端要同时加到这里）。
- xxl-job-admin 部署在 `bingo-infra`（`k8s/xxl-job-admin.yaml`），Service 名 `xxl-job-admin.bingo-infra:8080`，不通过 Ingress 暴露，控制台用 `kubectl -n bingo-infra port-forward` 打开；执行器端口只接受它的调用；容器设置 `TZ=Asia/Manila`，cron 按 UTC+8 触发。执行器没配 `XXL_JOB_TOKEN`（至少 16 位）时拒绝启动。
- **时区 UTC+8**：TaurusDB 参数模板设置 `time_zone = +08:00`（JDBC 会话也会强制为 `+08:00`）；Nacos、DMS 之外自建的中间件和所有 Pod 设置 `TZ=Asia/Manila`（`02-common-config.yaml` 已包含）。

**替换占位符**：`__REGISTRY__`、`__TAG__`、`__TAURUSDB_WALLET_HOST__`、`__TAURUSDB_WALLET_DS0_HOST__` … `__TAURUSDB_WALLET_DS15_HOST__`（16 个钱包分片实例）、`__TAURUSDB_BETRECORD_DS0_HOST__` … `__TAURUSDB_BETRECORD_DS15_HOST__`（16 个注单分片实例）、`__TAURUSDB_TURNOVER_DS0_HOST__` … `__TAURUSDB_TURNOVER_DS15_HOST__`（16 个稽核分片库，可与注单同实例）、`__TAURUSDB_SHARED_HOST__`、`__DCS_REDIS_HOST__`、`__NACOS_ADDR__`、`__NACOS_NAMESPACE_ID__`、`__DMS_KAFKA_BROKERS__`、`__DMS_KAFKA_SASL_BROKERS__`（KEDA 用的 SASL_SSL 地址）、`__OTEL_COLLECTOR_ENDPOINT__`、`__PLAYER_*__`（ELB ID、证书 ID、域名、ELB 子网 CIDR）、`__CALLBACK_*__`、`__REGION__` 和 `__KYC_BUCKET__`（`bingo-kyc-env.yaml`）。`__TAG__` 是 Jenkins 构建的镜像 tag（提交号前 12 位）。检查是否还有遗漏：`grep -rn '__[A-Z_0-9]*__' deploy/k8s`（`service-template.yaml` 的模板占位符除外）。

**按顺序应用**

```bash
kubectl apply -f deploy/k8s/00-namespace.yaml          # 命名空间 bingo / bingo-infra + PriorityClass（critical / online / async）
kubectl apply -f deploy/k8s/01-networkpolicy.yaml      # 默认拒绝 + 按调用方放行 + ELB 到入口 Pod
kubectl apply -f deploy/k8s/02-common-config.yaml      # 公共环境变量
kubectl apply -f deploy/k8s/bingo-kyc-env.yaml         # KYC 的 OBS 区域和桶（先于模板渲染）
kubectl apply -f deploy/k8s/bingo-reconcile-env.yaml   # 对账读 StarRocks 的 FE 地址（先于模板渲染）
# 密钥：生产 apply dew-secrets.yaml（按服务渲染），每个工作负载建好后 kubectl patch dew-patch.yaml；
#       测试集群用 secrets-example.yaml 的副本（替换 CHANGE_ME 后 apply，不要提交）
kubectl apply -f deploy/k8s/xxl-job-admin.yaml         # namespace bingo-infra
kubectl apply -f deploy/k8s/bingo-wallet.yaml          # 含 16 个分片库地址的 ConfigMap、PDB 10%
kubectl -n bingo rollout status statefulset/bingo-wallet
kubectl apply -f deploy/k8s/bingo-game-integration.yaml
kubectl apply -f deploy/k8s/bingo-bet-record-shards.yaml   # 注单分片地址 + sharding profile（先于模板渲染）
kubectl apply -f deploy/k8s/bingo-turnover-shards.yaml     # 稽核分片地址 + sharding profile（先于模板渲染）
# service-template.yaml 渲染 9 次：bingo-user、bingo-lobby、bingo-bet-record、bingo-payment、bingo-risk、bingo-promotion、bingo-reconcile、bingo-turnover、bingo-kyc
kubectl apply -f deploy/k8s/bingo-gateway.yaml
kubectl apply -f deploy/k8s/autoscaling.yaml           # 全部工作负载的 KEDA ScaledObject（需先用 Helm 装好 KEDA）
kubectl apply -f deploy/k8s/ingress-callback.yaml      # 先开放厂商回调
kubectl apply -f deploy/k8s/ingress-player.yaml        # 最后开放玩家流量
```

Pod 扩缩容只有一种机制：每个工作负载一个 KEDA ScaledObject（cpu + 晚高峰 cron 下限 + 消费者的 Kafka lag），清单默认是 5 万在线档，各档数值见 `autoscaling.yaml` 文件头。节点由「CCE集群弹性引擎」按待调度 Pod 自动增加。`autoscaling.yaml` 随时可以重新 apply，不会把已扩容的工作负载打回基线。

PowerShell 渲染模板示例（值 = 端口、执行器端口、节点池、PriorityClass、DB 连接池）：

```powershell
$svcs = [ordered]@{
  'bingo-user'       = @(8101, 9101, 'general',  'bingo-online', 16)
  'bingo-lobby'      = @(8104, 9104, 'general',  'bingo-online',  8)
  'bingo-bet-record' = @(8105, 9105, 'consumer', 'bingo-async',  8)
  'bingo-payment'    = @(8106, 9106, 'general',  'bingo-online', 16)
  'bingo-risk'       = @(8107, 9107, 'general',  'bingo-async',  8)
  'bingo-promotion'  = @(8108, 9108, 'consumer', 'bingo-async',  8)
  'bingo-reconcile'  = @(8109, 9109, 'consumer', 'bingo-async',  8)
  'bingo-turnover'   = @(8110, 9110, 'consumer', 'bingo-async',  8)
  'bingo-kyc'        = @(8111, 9111, 'general',  'bingo-online',  8)
}
foreach ($s in $svcs.GetEnumerator()) {
  $v = $s.Value
  (Get-Content deploy/k8s/service-template.yaml -Raw) `
    -replace '__SERVICE__', $s.Key -replace '__XXL_PORT__', $v[1] -replace '__PORT__', $v[0] `
    -replace '__NODE_POOL__', $v[2] -replace '__PRIORITY_CLASS__', $v[3] `
    -replace '__DB_POOL_SIZE__', $v[4] `
    -replace '__REGISTRY__', 'swr.<region>.myhuaweicloud.com/<org>' -replace '__TAG__', '0.1.0-SNAPSHOT' `
    -replace '__TAURUSDB_SHARED_HOST__', '<taurusdb-host>' | kubectl apply -f -
}
```

**检查**

```bash
kubectl -n bingo get pods -L apps.kubernetes.io/pod-index -o wide
kubectl -n bingo exec bingo-wallet-0 -- printenv WORKER_ID      # 应为 0
```

```bash
kubectl -n bingo get scaledobject                                  # READY 应为 True
kubectl -n bingo get hpa                                           # 只有 keda-hpa-*（KEDA 创建）
```

要点：所有工作负载都由 KEDA 扩缩、百分比 PDB（钱包 / 回调 10%，网关 15%，其余 20%）、跨 AZ 打散；preStop 先写标记文件让服务立刻从 Nacos 摘除（`RegistryDrain`），再等 15 秒（调用方的负载均衡缓存 5 秒过期）后优雅停机；`replicas` 不写在清单里，由 KEDA 负责（没装 KEDA 时 1 个 Pod）。钱包每个主库的连接数 = 钱包 Pod 数 × 该实例上的库数 × `DB_POOL_SIZE`，改 `maxReplicaCount`、连接池或搬库前先算预算（docs/capacity-1m.md §2.1）。TaurusDB 主备切换期间，回调统一返回厂商约定的「系统错误，请重试」，由厂商用同一交易号重试（在应用层实现）。
网关的 actuator 在管理端口 18080（`MANAGEMENT_PORT`），探针和 Prometheus 抓取都走这个端口，不经过 Service / ELB。

## 8. Flink CDC 作业（wallet_txn -> bingo.wallet.txn）

1. **TaurusDB**：在参数模板里开启 binlog，`binlog_format=ROW`、`binlog_row_image=FULL`，binlog 保留时间要大于作业可能停机的最长时间；创建 CDC 账号（授权语句写在 SQL 文件头部注释里）。
2. **自建 Flink**（例如 CCE 上的 Flink Kubernetes Operator）：Flink 1.20，`lib/` 放入 `flink-sql-connector-mysql-cdc-3.5.x.jar` 和对应 Flink 1.20 的 `flink-sql-connector-kafka` jar，替换 SQL 里的 `__占位符__` 后执行：
   ```bash
   ./bin/sql-client.sh -f deploy/flink/wallet_txn_cdc.sql
   ```
   升级作业时先 stop-with-savepoint，再从 savepoint 启动，保证从原 binlog 位置继续。
3. **DLI**：新建 Flink OpenSource SQL 作业，粘贴 SQL，配置增强型跨源连接以访问 VPC 内的 TaurusDB 和 DMS Kafka。先确认所选 DLI Flink 版本的 MySQL CDC 连接器支持 `scan.read-changelog-as-append-only.enabled`（Flink CDC 3.4 起才有），不支持就改为自建 Flink。
4. CDC 上线的环境里 bingo-wallet 必须保持 `WALLET_AFTER_COMMIT_PUBLISH=false`（默认值），否则每条事件会发两次。
   钱包分片后有 16 个 TaurusDB 实例，每个实例一路 binlog 源（增量阶段单并行读），合计约 250k 条/s 写入 `bingo.wallet.txn`（192 分区）；每个实例都要按第 1 步开启 binlog 并建 CDC 账号。
5. 验证：
   ```bash
   kafka-console-consumer.sh --bootstrap-server <broker> --topic bingo.wallet.txn --property print.key=true
   ```
   key 是 userId，value 是 WalletTxnEvent 的 JSON，`createdAt` 形如 `2026-09-30T08:00:00.123Z`。
