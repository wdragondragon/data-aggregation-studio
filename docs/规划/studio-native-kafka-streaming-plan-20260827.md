# Studio Native Kafka 长期运行能力实施主计划

**计划编号：** P0-KS-01

**状态：** 本地编码收口已完成（M0-M8 及收口补丁已验收；生产环境待单独验收）

**创建日期：** 2026-08-27

**最后更新：** 2026-08-28 04:20 +08:00

**权威性：** 本文件是 Native Kafka 长期运行能力的唯一实施基线。每次继续实施前必须重新读取本文件；代码与本文件不一致时，先更新“决策变更记录”，再修改代码。

## 1. 目标与完成定义

本计划在不引入 Flink、不新增独立部署服务、不破坏现有 Reader/Writer 插件契约的前提下，为 Studio 增加 Native 长期运行采集能力。第一版以 Kafka 为流式 Source，允许搭配任意现有 Writer，并实现上线后持续消费、下线后停止、Worker 重启恢复、受控日志和分钟指标。

只有同时满足以下条件，计划才能标记为“本地实施已完成”：

1. DataAggregation 流式 SPI、微批容器和正式取消能力完成，普通 JobContainer 与旧插件回归通过。
2. Kafka offset 只在目标批次成功后提交；Writer、commit、Worker 和 Broker 故障均不会造成提前推进 offset。
3. Studio 完成 BATCH/STREAMING、上线/下线/恢复状态机、逻辑 run、物理 attempt、并发保护和历史迁移。
4. Worker 重启后 RUNNING 任务自动恢复，STOPPED/OFFLINE 任务不会恢复，连续失败 10 次后暂停。
5. 运行日志按小时或 128MB 分片，查询和下载不再把无限日志整份读入 JVM 堆。
6. 流式分钟指标、Kafka lag、checkpoint 和 attempt 时间线可通过 API 与 UI 查询，且不与批处理最终指标重复累计。
7. Kafka -> MySQL、Kafka -> Kafka、Kafka -> OSS 真实常驻作业和 MySQL -> Kafka 批任务回归通过。
8. Browser UI、故障恢复、2 小时长稳、模块回归、接口文档、测试用例、部署与回滚说明全部有可追溯证据。

部分代码完成、个别接口成功、短时 UI 可见或单一作业通过，都不能替代以上完成定义。

## 2. 范围与架构不变量

### 2.1 范围

- 不使用 Flink。
- 不新增 Studio Maven 模块或独立进程。
- 在 DataAggregation `core` 的现有模块内增加 streaming 包。
- 在 Studio 现有 `studio-dto`、`studio-core`、`studio-infra`、`studio-server`、`studio-worker` 和前端内实现。
- 第一版只定义“流式 Source 搭配任意 Writer”；Kafka Writer 作为普通目标时不会把批 Reader 自动变为常驻任务。
- 第一版一个任务只有一个流式 Source、一个 active attempt，不实现流式 Fusion、动态并行度或同一任务多 Worker 并行消费。

### 2.2 插件兼容不变量

- 不修改 `Reader.Job.startRead(RecordSender)`。
- 不修改 `Writer.Job.startWrite(RecordReceiver)`。
- 不向旧 Reader/Writer 抽象类增加必须实现的抽象方法。
- 流式 Source 通过可选 SPI 接入；普通插件保持原有行为。
- 任意旧 Writer 通过有界微批完整执行 `init -> prepare -> startWrite -> post -> destroy`，以整个 Writer 生命周期成功作为批次确认。

### 2.3 交付语义

- 第一版统一声明 `AT_LEAST_ONCE`。
- Kafka `enable.auto.commit=false`。
- 目标 Writer 成功后才能提交对应 Topic/Partition 的下一 offset。
- Writer 成功但 offset commit 失败时允许重放；不允许为了避免重复而提前提交 offset。
- MySQL 通过主键/upsert实现业务幂等；OSS 通过稳定 batchId 对象路径实现重放覆盖；Kafka Writer 开启幂等 producer，但不宣称跨 attempt exactly-once。

## 3. 公开状态与配置契约

```text
executionMode = BATCH | STREAMING

taskStatus     = DRAFT | ONLINE | OFFLINE

desiredState  = RUNNING | STOPPED
observedState = STARTING | RUNNING | STOPPING | STOPPED | RECOVERING | FAILED

deliverySemantics = AT_LEAST_ONCE
```

STREAMING 默认参数：

| 参数 | 默认值 | 说明 |
| --- | ---: | --- |
| `pollTimeoutMs` | 1000 | Kafka poll 等待时间。 |
| `maxBatchRecords` | 1000 | 单微批最大记录数。 |
| `maxBatchBytes` | 16777216 | 单微批软字节上限，默认 16MB。 |
| `batchRetryCount` | 3 | 当前 attempt 内同一批次失败后的重试次数；总尝试次数为 4。 |
| `stopTimeoutMs` | 60000 | 下线等待当前批次完成的最长时间。 |
| `maxConsecutiveFailures` | 10 | deployment 自动恢复上限。 |
| `retryInitialDelayMs` | 5000 | attempt 恢复初始退避。 |
| `retryMaxDelayMs` | 300000 | attempt 恢复最大退避。 |

标准批次运行变量：

- `${stream.runId}`
- `${stream.attemptId}`
- `${stream.batchId}`
- `${stream.windowStart}`

## 4. 实现细则

### 4.1 DataAggregation 流式内核

新增可选 `StreamingSource`、`StreamingSession`、`StreamingBatch`、`StreamingCheckpoint` 和 `StreamingMetricsSnapshot`。Session 提供 `pollBatch`、`commit`、`abort`、`wakeup`、`metrics`、`close`。

新增 `StreamingJobContainer`：

1. 长期持有流式 Source 及其插件 ClassLoader。
2. poll 得到有界 `StreamingBatch`。
3. 用普通 JobContainer 提取出的有界执行器运行 Transformer 和任意 Writer。
4. Writer 成功后提交 checkpoint，失败时 abort。
5. 同一批次本地重试间隔为 1、2、4 秒。
6. 接收 cancel 后 wakeup Source，等待当前批次或在超时后中断。

`batchId` 根据排序后的 Topic、Partition、起始 offset、结束 offset 计算稳定摘要；同一批次重放不得生成不同 ID。

为 `JobContainer` 和 `StreamingJobContainer` 增加正式、幂等 `cancel()`；Worker 的反射兼容取消保留，用于加载旧框架版本。

### 4.2 Kafka Source

KafkaReader 在保持原 `startRead` 批模式的同时实现流式 SPI。Kafka Consumer 由固定 Source 线程持有；Writer 处理批次时暂停已分配分区并继续发送心跳，避免超过 `max.poll.interval.ms` 后无意义 rebalance。

Topic 从源模型 `physicalLocator` 解析，Kafka 数据源连接只提供 Broker、认证与协议。groupId 属于任务运行配置，默认 `studio.<tenantId>.<taskId>`；STREAMING 任务运行期间不可修改。

现有 `QueueCheckpointReadable` 作为 KafkaReader 内部 datasource 适配层完成 snapshot/commit/restore，但不能替代框架级 StreamingSource。

### 4.3 Studio 数据模型

`collection_task_definition` 增加：

- `execution_mode`
- `streaming_options_json`

新增表：

| 表 | 职责 |
| --- | --- |
| `stream_task_deploy` | 每个任务唯一 deployment、generation、期望/实际状态、当前 run/attempt、失败计数和退避。 |
| `stream_task_run` | 一次上线到下线的逻辑生命周期。 |
| `stream_task_attempt` | Worker 每次启动或恢复的物理尝试，关联 run_record。 |
| `stream_metric_bucket` | 每分钟增量指标和 lag gauge。 |
| `stream_task_event` | 状态迁移、恢复、下线和错误事件。 |
| `run_log_chunk` | 日志分片、本地/对象位置、字节数、校验和与状态。 |

MySQL、SQLite 初始化 Schema、`StudioSchemaUpgradeService` 和版本化原始增量 SQL 同步更新，不使用 Liquibase。历史任务全部回填 `BATCH`，不得因数据库升级自动启动。

### 4.4 Studio API 与状态机

- `POST /api/v1/collection-tasks/{id}/online`：BATCH 仅发布；STREAMING 幂等设置 ONLINE/RUNNING 并创建逻辑 run。
- `POST /api/v1/collection-tasks/{id}/offline`：幂等设置 OFFLINE/STOPPED，终止当前 attempt，优雅完成当前批次。
- `POST /api/v1/collection-tasks/{id}/recover`：只允许 desired=RUNNING 且 observed=FAILED，清零失败计数并恢复。
- `GET /api/v1/collection-tasks/{id}/streaming-runtime`：返回 deployment、run、attempt 和最近 checkpoint。
- `GET /api/v1/collection-tasks/{id}/streaming-metrics`：按时间范围返回聚合后的分钟指标分页，默认最新分钟优先；`pageNo/pageSize` 作用于跨 attempt 聚合后的分钟桶，不作用于原始 attempt 行。
- 流式事件和日志分片使用分页查询。

全部接口继续使用 Studio 自有 `Result<T>` 和 `PageView<T>`。STREAMING 拒绝 schedule、trigger、运行中编辑运行配置和运行中删除；任务级 terminate 提示使用 offline。

### 4.5 Server 与 Worker

Server 协调器使用 generation、CAS、Dispatch 和现有 Worker lease，确保每个 deployment 最多一个 active attempt。Worker 使用独立 streaming semaphore，默认 `studio.worker.streaming.max-concurrent=2`，不占用普通批任务槽位。

Worker 崩溃或重启后，旧 attempt 标记 INTERRUPTED，RUNNING deployment 进入 RECOVERING，从 Kafka committed offset 创建新 attempt；STOPPED/OFFLINE 不恢复。

attempt 失败使用 5 秒到 5 分钟指数退避。成功提交一个批次后失败计数归零；连续失败 10 个 attempt 后 observed=FAILED 并停止自动重试。

### 4.6 日志

- 每个 attempt 使用动态 `RollingFileAppender`。
- 按小时或 128MB 滚动。
- 关闭分片 gzip 并只上传一次；活动分片每 60 秒同步。
- Kafka 空 poll 降为 DEBUG，或每 60 秒汇总一次。
- 日志查询每次最多读取 512KB。
- 全量下载使用流式 ZIP，不使用 `Files.readAllBytes` 或对象存储整对象加载。
- Server/Worker 生命周期日志携带 taskId、runId、attemptId、generation、dispatchId，但不记录凭据、完整配置和消息正文。

### 4.7 指标

每分钟桶记录读取数、写成功、写失败、dirty、字节数、批次数、重试数、当前/最大 lag、最后消息时间、最后 checkpoint 时间和 rebalance 次数。分钟指标查询先按任务和 `bucketStart` 聚合跨 attempt 原始桶，再按 `bucketStart DESC` 分页；底层 `stream_metric_bucket` 原始桶不压缩、不删除。前端可在当前页内把连续且所有计数为 0、同时 lag/checkpoint/attempt 状态不变的桶合并为一个空闲时间段，但不得隐藏状态变化或改变审计数据。

BATCH 继续使用已结束 run_record；STREAMING 使用分钟桶。attempt 最终累计值不得再次叠加到分钟桶。日志和指标默认保留测试证据；生产可通过 `studio.streaming-history.cleanup-enabled=true` 启用受控清理。清理按保留天数、分批上限和集群锁执行：先清理非活动 attempt/deployment 的指标、事件和日志分片，再清理无日志引用的终态 attempt，最后清理无剩余 attempt 的终态 run；对象删除失败时保留 `run_log_chunk` 元数据并在下一周期重试。测试环境默认关闭，不删除现有任务、Topic、run、日志或指标。

### 4.8 前端

- 编辑器增加 BATCH/STREAMING 分段选择。
- STREAMING 隐藏定时配置，展示 groupId、offset、微批参数和运行集群。
- ONLINE/STARTING/RUNNING/RECOVERING 时锁定运行配置。
- 列表按状态展示上线、下线、恢复、监控和日志操作；STREAMING 不展示手动触发和定时管理。
- 详情展示逻辑 run、attempt 时间线、吞吐、lag、checkpoint、事件和日志分片。
- Web 与 Desktop 共用 SDK 类型和页面组件。

## 5. 里程碑与验收方案

| 里程碑 | 状态 | 交付内容 | 验收门槛 |
| --- | --- | --- | --- |
| M0 文档与基线 | 已验收 | 本文件、文档索引、Git/JDK/Maven/Kafka/任务基线。 | 文档可独立指导实施，基线完整，未删除测试资源。 |
| M1 框架流式内核 | 已验收 | Streaming SPI、微批容器、正式 cancel、兼容测试。 | 旧契约不变；普通 JobContainer 回归；未修改 Writer 的微批测试通过。 |
| M2 Kafka checkpoint | 已验收 | 手动 offset、commit/abort、心跳、batchId、错误转换。 | Writer 成功才推进 offset；Writer/commit/cancel 故障不丢数据；Docker Kafka IT 通过。 |
| M3 Studio 数据与 API | 已验收 | Schema、状态机、API、逻辑 run/attempt、并发保护。 | MySQL/SQLite 新建与重复升级通过；历史任务 BATCH；并发操作无双 attempt。 |
| M4 Worker 常驻执行 | 已验收 | Dispatch、slots、lease、停止、重启恢复、失败暂停。 | 下线停止；Worker 重启续读；10 次失败暂停；批任务槽位可用。 |
| M5 日志和指标 | 已验收 | 日志分片、对象同步、跨分片读取、流式 ZIP、分钟指标和 lag。 | 真实 RollingFileAppender 128MB 滚动、60 秒活动同步、512KB 分页、ZIP API、无双计数指标和脱敏检查通过；见第 6.8 节。 |
| M6 前端与 SDK | 已验收 | 编辑、列表、状态、监控、事件和日志 UI。 | Web/Desktop 构建通过；390x844 与 1440x900 Browser 通过；归档 API/ZIP 有效，应用内 Browser 不暴露 Blob download 事件的限制已记录；见第 6.9 节。 |
| M7 真实作业与长稳 | 已验收 | 三个 STREAMING 作业、批回归、故障注入和超过 2 小时长稳。 | 无 offset 丢失；重放可解释；下线停止；Worker/Server 重启与失败恢复通过；日志指标受控；见第 6.10 节。 |
| M8 收尾与交付 | 已验收 | 全回归、接口/测试/部署/回滚文档和完成审计。 | 两个 Git 边界 diff、文档链接、秘密扫描和运行通道审计通过；生产环境残余风险明确。 |

## 6. M0 当前基线

### 6.1 工具链

```text
JDK:    C:\dev\Java\jdk-17.0.12
Java:   17.0.12 (Oracle Corporation)
Maven:  C:\dev\apache-maven-3.8.8
Maven:  3.8.8
Branch: jdk17
Date:   2026-08-27 Asia/Shanghai
```

### 6.2 Kafka

```text
Container: kafka-single
Image:     apache/kafka:3.9.1
Listener:  127.0.0.1:9092
Status:    running
```

当前 Topic：

- `test-data`
- `codex_kafka_source_20260826`
- `codex_mysql_to_kafka_20260826`
- Kafka 内部 `__consumer_offsets`

### 6.3 既有 Kafka 兼容性任务

以下任务来自此前真实 UI/API 验证，必须保留：

- `Kafka兼容性-Kafka到OSS-20260826`
- `Kafka兼容性-Kafka到MySQL-20260826`
- `Kafka兼容性-MySQL到Kafka-20260826`

### 6.4 工作区保护

实施开始时仓库存在用户生成的未跟踪运行日志、测试源码、报告、依赖树和临时目录。本计划不删除、不回退、不覆盖这些内容。每次修改只处理本计划明确涉及的文件，使用 `git diff` 区分本计划变更和用户文件。

### 6.5 M2 Kafka checkpoint 验收证据

使用固定工具链执行的定向测试：

```powershell
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
& 'C:\dev\apache-maven-3.8.8\bin\mvn.cmd' `
  -pl job-plugins/reader/kafkareader -am `
  '-Dtest=KafkaStreamingSessionTest,StreamingJobContainerTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果为 Core 5/5、Kafka Reader 11/11，合计 16/16，0 failure、0 error、0 skipped。覆盖旧 Writer 微批生命周期、Writer 三次本地重试、checked commit failure、cancel/abort、手动 offset、稳定 batchId、pause/heartbeat、assignment 前 restore、幂等 close、split 尾空字段、全 dirty 批次推进和 JSON 解析错误正文脱敏。

真实 Docker Kafka 集成测试：

```powershell
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
& 'C:\dev\apache-maven-3.8.8\bin\mvn.cmd' `
  -pl job-plugins/reader/kafkareader -am `
  '-Dtest=KafkaStreamingSessionIT' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dkafka.it.enabled=true' test
```

结果为 1/1，0 failure、0 error、0 skipped。保留的验收资源及结果：

```text
Topic:            NativeStreaming-M2-20260827
Group:            NativeStreaming-M2-20260826184558335
Start offset:     20
Committed offset: 25
Log end offset:   25
Active members:   0

Commit batchId:
kafka-12d273f5b6c456291bcc8af8c43f61e7fccaa447ac535ce6ead2279d48bc22d7

Abort/replay batchId:
kafka-502f9494fa3d2727bd64c96a9ca61f9adf554ff31f87e485d41b24ae9cb757fc

Restart/replay batchId:
kafka-c7902a09cd0a18ddc0e358af04dcfa7c2493815ccb3f4bb459cd1140be0f02b1
```

显式 commit 前 committed offset 不推进；commit 后推进到 next offset；abort 后不提交且重读 batchId 相同；未提交批次关闭 Session 后，同组新 Session 从 committed offset 重放且 batchId 相同。Consumer 均正常 LeaveGroup。此前的成功组 `NativeStreaming-M2-20260826183756978` 保持 offset 20，调试组 `NativeStreaming-M2-20260826183057105`、`NativeStreaming-M2-20260826183342511`、`NativeStreaming-M2-20260826183508101` 及其历史均保留。

框架与 Kafka 相关 reactor 回归：

```powershell
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
& 'C:\dev\apache-maven-3.8.8\bin\mvn.cmd' `
  -pl core,data-source-plugins/data-source-handler-queue-kafka,job-plugins/reader/kafkareader,job-plugins/writer/kafkawriter `
  -am test
```

12 个 reactor 模块全部 SUCCESS；Core 32/32、Kafka Reader 11/11，合计 43/43，0 failure、0 error、0 skipped；Kafka datasource 与 Writer 编译、测试阶段成功且没有模块自有测试。独立真实 Kafka IT 另计 1/1。

最终审计结果：

- `git diff --check` 返回 0，仅报告既有 LF/CRLF 工作区提示。
- `Reader.java`、`Writer.java` 的 diff 为空，旧抽象契约未修改。
- `dependency:tree` 确认 Kafka clients 3.9.0、Core、Kafka datasource 和 Kafka Reader 统一仲裁到 `slf4j-api:1.7.36`。
- JSON 解析异常统一包装为不包含消息正文的错误；Kafka Source 日志不记录 `record.value()`，终止与关闭日志只保留异常类型和脱敏后的限长摘要。
- 控制命令入队与 Source 线程终止通过同一生命周期锁串行化，线程退出时所有待处理命令确定完成或失败，不再落入 35 秒超时竞态。
- Topic 列表仍包含 `test-data`、`codex_kafka_source_20260826`、`codex_mysql_to_kafka_20260826` 和 `NativeStreaming-M2-20260827`，没有删除原有 Kafka 资源。
- Surefire 报告位于 `core/target/surefire-reports` 和 `job-plugins/reader/kafkareader/target/surefire-reports`；最终 reactor 完成时间为 2026-08-27 02:46:42+08:00，最终 IT 完成时间为 2026-08-27 02:46:08+08:00。

### 6.6 M3 Studio 数据与 API 验收证据

使用固定 JDK/Maven 执行最终模块回归：

```powershell
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
& 'C:\dev\apache-maven-3.8.8\bin\mvn.cmd' `
  -pl studio-infra,studio-server -am test
```

最终结果为 `studio-commons` 7/7、`studio-nacos-compat-core` 27/27、`studio-infra` 545/545、`studio-server` 108/108，合计 687/687，0 failure、0 error、0 skipped；完成时间为 2026-08-27 03:43:41+08:00。DTO 与 Core 在 reactor 中编译通过且无模块自有测试。Surefire XML 位于各模块 `target/surefire-reports`。

M3 定向覆盖包含：

- STREAMING 保存约束、默认 groupId、OFFLINE 编辑、运行中编辑/删除/schedule 拒绝及 BATCH 编辑主键保持。
- online/offline/recover 幂等、并发 online、online/offline 竞争、CAS null 字段清理及 M3 不创建物理 attempt。
- `/offline`、`/recover`、`/streaming-runtime`、`/streaming-metrics`、事件与日志分片六个路由的 Studio `Result<T>` 契约和 ISO 时间绑定。
- SQLite 初始化与重复升级、历史任务 BATCH 回填、6 张流式表、13 个关键索引及 deployment 唯一约束。
- Server 无插件目录启动时显式关闭测试内 Arthas，避免与 IDEA 运行实例争用 3658/8563；运行中的 Server/Worker 未重启。

真实 MySQL 隔离验收资源保留：

```text
Initialization database: NativeStreaming_M3_20260827
Upgrade database:        NativeStreaming_M3_Upgrade_20260827

Initialization stream tables: 6
Initialization key indexes:   13
Upgrade stream tables:        6
Upgrade key indexes:          13
Legacy execution_mode:        BATCH
Upgrade deployments:          0
```

两个数据库分别完成完整 Schema 或 legacy 增量 SQL 的连续两次执行，不修改当前 Studio 业务库。`git diff --check` 返回 0，仅有既有 LF/CRLF 提示。M3 审查期间修复了两个真实回归：MyBatis-Plus 默认忽略 null 导致 recover 无法清空错误字段，改为显式 Lambda CAS；新任务预分配 ID 的条件错误会让 BATCH 编辑换主键，改为只在创建时分配并加入回归测试。

### 6.7 M4 Worker 常驻执行验收证据

使用固定 JDK/Maven 执行 Studio Worker 相关完整 reactor 回归：

```powershell
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
& 'C:\dev\apache-maven-3.8.8\bin\mvn.cmd' `
  -pl studio-infra,studio-server,studio-worker -am test
```

最终结果为 `studio-commons` 7/7、`studio-nacos-compat-core` 27/27、`studio-infra` 550/550、`studio-flink` 115/115、`studio-server` 108/108、`studio-worker` 189 项中 188 通过且 1 项因缺少外部 OSS 验收环境按既有条件跳过；合计 996 项，0 failure、0 error、1 skipped。九个 reactor 模块全部 SUCCESS，完成时间为 2026-08-27 04:35:06+08:00。DTO 与 Core 编译通过且无模块自有测试。

M4 定向证据共 17/17 通过，0 failure、0 error、0 skipped：

- `StreamingTaskCoordinatorServiceIntegrationTest` 5/5：并发 reconcile 单 attempt/Dispatch、Worker claim 前下线、Worker restart 恢复、checkpoint 成功清零失败数、连续 10 次失败及人工 recover。
- `WorkerSchedulingConfigurationTest` 6/6：调度线程池配置、独立 streaming executor、默认/自定义槽位和旧兼容类不再注册 Spring 配置。
- `WorkerStreamingDispatchTest` 4/4：无 streaming executor 不 claim、默认两个流式槽位、第三个任务不 claim、流式任务不占文件传输槽位、优雅终止和旧 bootId 恢复。
- `StreamingTaskWorkerExecutorTest` 2/2：逻辑 run 首 attempt 才 reset offset，后续 attempt 从 committed offset 恢复；停止超时调用正式 `StreamingJobContainer.cancel()`。

实现和安全审计结果：

- `WorkerLifecycleRunner.shutdown()` 会取消 active execution 并关闭独立 `streamingExecutor`；默认 `streamingMaxConcurrent=2`，与批任务和文件传输执行器隔离。
- STREAMING Dispatch payload 只包含 `collectionTaskId`、deployment/run/attempt/generation、`groupId` 等运行标识，嵌套 `config` 只含 `collectionTaskId`；不携带密码、Token、数据源连接或完整任务配置。
- 流式生命周期日志不记录凭据、完整配置或 Kafka 消息正文；Kafka `record.value()` 只用于解析与字节统计，没有进入日志参数。
- `Reader.java`、`Writer.java` 的 Git diff 和状态均为空，旧插件抽象契约未修改。
- Spring 扫描只存在 `WorkerSchedulingConfiguration.workerTaskScheduler()`；旧 `WorkerSchedulingConfig` 为非 Spring 兼容类，不会产生同名 Bean。
- Studio 与 DataAggregation 两个仓库的 `git diff --check` 均返回 0，仅输出既有 LF/CRLF 工作区提示。
- 本里程碑的 SQLite run/deployment/attempt/Dispatch 均由隔离集成测试临时创建，无外部业务数据 ID；测试未删除或修改现有 Kafka Topic、消费组、三个兼容性任务及 M2/M3 保留资源。
- Surefire XML 位于 `backend/studio-infra/target/surefire-reports`、`backend/studio-server/target/surefire-reports`、`backend/studio-worker/target/surefire-reports`，完整 reactor 还包含 `studio-commons`、`studio-nacos-compat-core` 和 `studio-flink` 对应报告目录。

### 6.8 M5 日志和指标增量证据

本轮修复与回归：

- `RunLogFileService` 对 `stream-run-*` 日志按活动文件加 `.gz` 滚动分片进行逻辑拼接，分页读取仍受 512KB 上限约束；归档使用逐文件流式 ZIP，不调用 `Files.readAllBytes` 或整对象缓冲。
- 新增回归 `RunLogFileServiceSecurityTest.streamingLogPagesAndArchiveShouldIncludeRolledGzipChunks`，验证历史 gzip 分片和活动文件均能读取，归档包含两个分片；该类 4/4 通过。
- `RunLogFileServiceSecurityTest` 同时验证日志敏感字段脱敏、512KB full 页上限和活动分片首次 INSERT 的 `status/localPath/sizeBytes` 完整性。
- `WorkerSchedulingConfigurationTest` 验证 `syncActiveRunLogs` 初始延迟与固定延迟均为 60000ms，符合活动分片每 60 秒同步约定。
- 固定工具链定向回归：`studio-infra,studio-server,studio-worker -am` 执行 `RunLogFileServiceSecurityTest,RunLogProxyServiceTest,InternalRunLogControllerTest,StreamingTaskRuntimeServiceIntegrationTest,CollectionTaskStreamingRouteContractTest,WorkerSchedulingConfigurationTest`，24 项通过，0 failure、0 error、0 skipped；完成时间 2026-08-27 08:28:51+08:00。
- 任务级指标跨 attempt 同一分钟聚合专项 5/5 通过；计数列求和、lag/current checkpoint 规则与 UI 单行展示一致。真实 Kafka -> MySQL 07:09 桶为读取 3、写成功 3、失败 0、lag 0、批次 1。
- Worker 活动日志同步后真实 `run_log_chunk` 已写入：attempt `2092758845620322306`、sequence 0、status `WRITING`、size 254 bytes、uploadedAt `2026-08-27 07:41:20`；修复前历史 `status` 非空错误保留，不删除历史证据。

后续验收补充：

- `RunLogFileServiceSecurityTest.streamingAppenderShouldRollWhenActiveChunkExceeds128Mb` 使用真实 `RollingFileAppender`，把测试活动文件扩展到 128MB + 1 byte 后写入匹配 attempt MDC 的事件；断言生成 `.log.gz` 历史分片且新活动文件重新小于 128MB。该测试与原有跨分片分页/ZIP/脱敏测试合计 5/5 通过。
- 对象存储配置下的活动分片持续同步有效：长稳 attempt 的 sequence 0 分片保持 `WRITING/OBJECT_STORAGE`，大小 7507 bytes，17:24:30 仍完成最近同步；计划和文档不记录 bucket、认证或连接秘密。
- 归档接口返回 HTTP 200、`application/zip`、`Content-Disposition`，响应首部为 ZIP `PK`；对象分片通过文件/流式下载契约进入归档，不回退到整对象 `byte[]`。`DataIngestionAccessLogSupportTest` 已同步模拟 `downloadTo(Path)` 契约，16/16 通过。
- 长稳任务 14:34 分钟桶记录 read 1000、write success 1000、failure 0、batch 1、lag 0；后续空闲分钟桶保持零增量，没有重复累计。
- 当前本地运行日志和代码敏感扫描未发现未脱敏凭据，运行输出只保留掩码或脱敏摘要。

M5 完成定义已满足。生产对象存储容量、生命周期策略和跨区域下载仍属于生产环境验收，不影响本地里程碑结论。

### 6.9 M6 前端与 Browser 增量证据

- 前端根目录 `npm run build` 通过：Desktop 与 Web 均完成 `vue-tsc --noEmit` 和 Vite production build；仅有既有 chunk 大于 500KB 的构建提示，无编译错误。
- 保留的 Browser 页面为 `http://127.0.0.1:8000/dfs/data-aggregation-studio/collection-tasks`，筛选 `NativeStreaming-M7` 显示 4 条任务，BATCH/STREAMING 执行模式、运行状态和目标信息正确。
- `NativeStreaming-M7-KafkaToMySQL-20260827` 监控抽屉可见逻辑 run `2092748506795180034`、attempt `#9`、groupId、checkpoint、分钟指标、事件和日志分片；Worker 重启事件文案为 `Streaming attempt was interrupted by Worker restart`。
- 流式任务运行中编辑与删除按钮均 disabled；STREAMING 不显示定时配置和手动触发入口；控制台 warn/error 读取结果为空。
- 390x844 页面无水平溢出，监控表格保留受控横向容器；随后应用内 Browser 显式 1440x900 复核得到 `innerWidth=1440`、`innerHeight=900`、`scrollWidth=1425`，同样没有横向溢出，控制台 warn/error 为空。
- 1440x900 运行中编辑页显示配置锁提示；任务名称、执行模式、groupId、offset、微批参数、源/目标绑定、Writer 参数、字段映射、页头/页尾保存入口均 disabled，步骤导航只用于只读回顾。列表页的编辑与删除也 disabled。
- `npm run test:collection-task-streaming-lock` 最终复核通过，覆盖列表操作禁用、编辑器保存防线和 `desiredState=RUNNING` 锁定判定。
- 归档请求在网络层返回 HTTP 200、`application/zip`、`Content-Disposition` 和有效 ZIP `PK` 内容；应用内 Browser 当前没有把前端 Blob 下载暴露为高层 `download` 事件。该限制只影响自动化事件捕获，不能写成 download event 已通过，也不能否定已验证的接口和 ZIP 内容。

M6 功能、SDK、桌面/移动布局和状态约束已通过本地验收；Blob download 事件保留为 Browser 表面限制。

### 6.10 M7 真实作业与长稳增量证据

本轮真实作业使用当前本机 `kafka-single`（`apache/kafka:3.9.1`，`127.0.0.1:9092`）和以 `NativeStreaming-M7-*` 命名的隔离资源；不删除既有 Topic、消费组、Studio 任务、模型、运行记录、日志或指标。

新增并保留的输入 Topic 为 `NativeStreaming-M7-NumericInput-20260827`，1 个分区，log end offset 为 7。输入消息使用与模型契约一致的数值型 `id`：`104`–`110`，字段 `test_param` 为字符串。新增并保留模型 `NativeStreaming-M7-NumericInput-20260827`，字段为 `id: INT`、`test_param: STRING`。

新增并保留的真实任务如下：

| 任务 | 模式 | 目标 | 结果 |
| --- | --- | --- | --- |
| `NativeStreaming-M7-Numeric-KafkaToMySQL-20260827` | STREAMING | `agg_test.NativeStreaming_M7_MySQL_Target_20260827` | 首批读取 104–106，后续读取 107–110；Writer 成功后 checkpoint 推进到 offset 7，lag 0，`writeFailedRecords=0`；MySQL 查询确认 107–110 已写入。 |
| `NativeStreaming-M7-Numeric-KafkaToKafka-20260827` | STREAMING | `NativeStreaming-M7-KafkaOutput-20260827` | 首批读取 104–106 并成功写入输出 Topic；输出消息字段和值正确。验证后下线释放独立 streaming slot，任务和输出消息保留。 |
| `NativeStreaming-M7-Numeric-KafkaToOSS-20260827` | STREAMING | `NativeStreaming-M7-OSS-20260827` | 在释放 slot 后由 `QUEUED` 进入 `RUNNING`；首批和后续批次成功写入，Worker 重启后继续消费，checkpoint offset 7、lag 0；OSS 模型预览可读出 109、110。 |

三条任务首次创建时携带 `schedule` 被接口拒绝，错误为 `STREAMING collection tasks do not support schedules`；去掉 `schedule` 后创建成功，验证了流式任务不接受定时配置。既有失败任务 `NativeStreaming-M7-KafkaToMySQL-20260827` 和 `NativeStreaming-M7-KafkaToOSS-20260827` 已下线但其失败 run、attempt、checkpoint、事件和日志保留；既有 `NativeStreaming-M7-KafkaToKafka-20260827` 也保留且已停止；`NativeStreaming-M7-MySQLToKafka-BATCH-20260827` 保持在线用于批回归。

Worker 按 IDEA 的 `StudioWorkerApplication` 配置重启一次：JDK `C:\dev\Java\jdk-17.0.12`、VM `-Xms256m -Xmx512m`、`STUDIO_CLUSTER_CODE=default-local`，端口 18081 健康恢复。重启后 MySQL 和 OSS 任务各生成新的物理 attempt，旧 attempt 标记 `INTERRUPTED/WORKER_RESTART_INTERRUPTED`，逻辑 run 未终止，新 attempt 从已提交 Kafka offset 继续消费，没有从头重置或跳过消息；Server 18080 保持健康。

本轮保留的类型契约故障证据：旧验收 Topic 中的字符串 ID 与模型 `id: INT`、OSS 目标 `id: LONG` 不一致，Writer 在 `CommonFileTableWriter.convertColumn -> StringColumn.asLong` 报转换错误，offset 不推进，任务进入 `RECOVERING`。该任务和失败数据未修改；替代方案是修改旧模型为 `STRING`，但会改变既有任务契约，因此采用新的数值型隔离 Topic、模型和任务完成可运行性验证。

M7 运行层最终覆盖：Writer 成功后提交 offset、Kafka lag/checkpoint、Kafka->MySQL/Kafka/OSS 真实流式链路、MySQL->Kafka BATCH 回归、运行中下线、独立 streaming slot、Worker 重启恢复、Broker 暂停/恢复、MySQL/OSS 隔离目标不可用、真实 commit failure、Server 重启、重复上线幂等、停止超时、连续失败 10 次、人工 recover 和超过 2 小时长稳。

2026-08-27 10:12–10:18 完成 Broker 暂停/恢复增量验证：暂停前 MySQL/OSS 两组 offset 均为 7、lag 0；Kafka 容器暂停期间 checkpoint 保持 7，没有提前推进；恢复后追加消息 111、112，输入 log end offset 和两个消费组提交位点均推进到 9、lag 0，MySQL 模型预览包含 101–112，OSS 模型预览包含 111–112。两个逻辑 run 和 attempt `#2` 均未被错误重建。并发执行两次 MySQL 流式任务 online，返回相同 generation、run 和 attempt，验证重复上线幂等。

目标不可用故障首次尝试暂停了本机 `mysql` 容器，但真实 Writer 使用另一套隔离远端目标，本机容器实际承载 Studio 业务库，因此该注入同时阻断 Server/Worker 协调数据且没有隔离真实 Writer 目标。该次尝试不计为“目标不可用通过”，本机 MySQL 已恢复，未删除数据；计划不记录目标连接地址或凭据。

该共享基础设施注入发现一个必须修复的阻塞性缺陷：消息 114 第一次成功写入远端目标并提交 Kafka offset 11 后，Studio checkpoint 上报因业务库不可用失败；`StreamingJobContainer` 将 commit 后的监听器异常误纳入批次重试，第二次 Writer 执行因主键 114 重复失败，并在已无 Kafka in-flight batch 时错误 abort。随后 Worker 的失败/指标上报阻塞发生在移除 active attempt 之前，心跳持续把已关闭 Kafka consumer 的 attempt 刷新为 RUNNING，形成 UI/API 假运行。修复验收要求：commit 后监听器失败不得重跑 Writer 或 abort；checkpoint 上报可取消重试；执行结束后必须先停止 active heartbeat，再执行可失败的指标、attempt 和日志最终上报；业务库恢复后任务必须进入可恢复状态或继续运行，不得长期假 RUNNING。

阻塞修复完成后增加并保留两条隔离目标故障任务：`NativeStreaming-M7-MySQLTargetUnavailable-20260827` 与 `NativeStreaming-M7-OSSTargetUnavailable-20260827`。两者均产生 `ATTEMPT_RECOVERY_SCHEDULED` 时间线，失败 attempt 的 committed batch count 为 0、checkpoint 未推进，随后显式下线为 STOPPED；它们不再通过停止 Studio 业务库来模拟目标故障。

连续失败和人工恢复使用保留任务 `NativeStreaming-M7-FailureLoop-20260827` 与 `NativeStreaming-M7-Fail10Recover-20260827`：事件记录在第 10 个连续失败 attempt 后进入 `ATTEMPT_FAILURE_LIMIT_REACHED/FAILED`，不再自动排队；人工 recover 后 generation 推进并创建后续 attempt，最终按用户下线进入 STOPPED。停止超时的正式 `cancel()` 路径同时由 `StreamingTaskWorkerExecutorTest` 的确定性回归覆盖，未提交批次保留可重放语义。

真实 Kafka commit failure 使用 `KafkaStreamingSessionIT#realKafkaFencedCommitDoesNotAdvanceOffsetAndReplaysBatch` 注入 `FencedInstanceIdException`：commit 失败时消费组 offset 不推进，重放 batchId 稳定，恢复后才提交。该 IT 是独立 Docker Kafka 命令的 1/1 证据，不与普通 reactor 数量合并。

Server 和 Worker 均按 IDEA 配置完成重启验收：Server 18080、Worker 18081 和 `DEFAULT-LOCAL` 恢复；RUNNING deployment 保持单一 active attempt，Worker 重启只替换物理 attempt，逻辑 run 和 committed offset 不重置。重复 online 返回相同 generation/run/attempt。

长稳任务 `NativeStreaming-M7-CommitFailure-20260827` 从 14:31:04 持续运行至 17:25 后仍为 desired/observed `RUNNING/RUNNING`，heartbeat 持续刷新，checkpoint next offset 1015、committed batch count 1、连续失败数 0。14:34 分钟桶为读取/写成功 1000/1000、失败 0、lag 0；后续空闲桶为零增量，活动日志分片持续同步。该任务、输入 Topic、消费组、运行记录、日志和指标全部保留供用户回顾。

M7 本地真实作业、故障恢复和长稳完成定义已满足。生产环境的 Broker 集群高可用、目标系统容量、跨网络抖动和发布窗口故障演练仍需单独验收。

## 7. 验收执行规则

1. Java 构建前显式设置 `JAVA_HOME=C:\dev\Java\jdk-17.0.12`，使用 `C:\dev\apache-maven-3.8.8\bin\mvn.cmd`。
2. M1/M2 在 DataAggregation 根目录运行 core、Kafka datasource、Kafka Reader/Writer 定向测试和 reactor 回归。
3. M3-M5 在 Studio backend 运行 dto/core/infra/server/worker 定向和模块测试。
4. M6 运行前端类型检查、单测和生产构建，并验证 1440x900、390x844。
5. M7 使用当前 kafka-single，不删除已有 Topic、消费组或任务；新增资源以 `NativeStreaming-*` 命名并保留。
6. 故障验收覆盖 Broker 暂停、MySQL/OSS 不可用、offset commit 失败、Server/Worker 重启、重复上线、运行中下线、停止超时、连续失败 10 次和人工恢复。
7. 每个里程碑结束运行 `git diff --check`，记录测试数、失败数、跳过数、运行 ID、Topic、消费组、offset、截图和日志路径。
8. 必须重启时只复用 IDEA 配置：Server `StudioServerApplication`、Worker `StudioWorkerApplication`，VM 参数均为 `-Xms256m -Xmx512m`，Worker 保留 `STUDIO_CLUSTER_CODE=default-local`。
9. 重启后确认 Server 18080、Worker 18081 和 `DEFAULT-LOCAL` 恢复。
10. 最终结论区分框架、API、真实作业、UI、本地长稳和生产环境证据。

## 8. 上线与回滚

- 新增字段和表先向前兼容上线，历史任务全部回填 BATCH。
- 发布顺序为数据库增量、DataAggregation 插件、Worker、Server、Web。
- 只有 Worker 和 Server 都识别 STREAMING 后，前端才开放 STREAMING 保存入口。
- 应用回滚只回退 Web、Server、Worker 和插件版本；新增表、列、run、attempt、checkpoint 审计、日志和指标保留。
- 禁止以回滚名义删表、清 Kafka offset、删除 Topic 或删除既有测试任务。
- 回滚后旧应用忽略新增表列，STREAMING deployment 保持 STOPPED，待新版本恢复后由用户重新上线。

## 9. 风险清单

| 风险 | 控制措施 |
| --- | --- |
| 任意旧 Writer 无批内确认 | 每个微批完整结束 Writer 生命周期后才 commit。 |
| Writer 成功而 offset commit 失败 | 允许重放，目标通过主键、稳定对象路径或下游去重控制副作用。 |
| Writer 初始化开销 | 先保证兼容与正确性；指标记录批耗时，后续再评估可选 StreamingSink 快速路径。 |
| Kafka rebalance | Source 专用线程持有 Consumer，写入期间暂停分区并维持心跳。 |
| 常驻任务占满 Worker | 独立 streaming slots，默认 2。 |
| 无限错误重试 | 10 个连续失败 attempt 后 FAILED，人工 recover。 |
| 日志文件和对象无限增长 | 小文件滚动、密封上传一次、分页读取、流式下载。 |
| 指标重复累计 | 流式趋势只读分钟桶，attempt 最终值不参与同一趋势求和。 |
| 旧任务迁移后意外启动 | migration 全部回填 BATCH，不根据 Reader 类型猜测模式。 |

## 10. 决策变更记录

| 日期 | 决策 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-08-27 | 首版使用 Native DataAggregation，不考虑 Flink。 | 用户明确要求复用框架本身能力。 | 所有执行均落在 core/Server/Worker 现有边界。 |
| 2026-08-27 | 流式 Source 可搭配任意旧 Writer。 | 保持插件生态兼容。 | 使用有界微批，Writer 每批完整生命周期。 |
| 2026-08-27 | 接受 AT_LEAST_ONCE。 | 任意异构目标无法提供通用跨系统事务。 | 故障可能重放，不能提前提交 offset。 |
| 2026-08-27 | 下线状态新增 OFFLINE。 | 区分从未发布的 DRAFT 与已下线任务。 | DTO、数据库状态校验和前端文案同步扩展。 |
| 2026-08-27 | 不执行“实现前清空”。 | 用户明确表示无需理会该条件，且此前要求测试数据保留。 | 所有既有任务、Topic、offset 和运行历史保留。 |
| 2026-08-27 | `batchRetryCount` 表示失败后的重试次数，不含首次尝试。 | 原始要求同时指定“重试 3 次”和 1/2/4 秒三个退避间隔，原参数说明“最大尝试次数”与其冲突。 | 默认总尝试 4 次；三次重试前分别等待 1、2、4 秒，全部失败后才 abort。 |
| 2026-08-27 | DataAggregation 统一 `slf4j-api` 从 1.7.10 升级到 1.7.36，Logback 暂不升级。 | Kafka clients 3.9.0 的 Consumer 关闭路径依赖 `org.slf4j.event.Level`；旧 API 在真实重启测试中触发 `NoClassDefFoundError`，导致心跳线程泄漏和同组新 attempt 无法获得分区。 | 保持 SLF4J 1.7.x 二进制兼容；框架及插件统一获得 Kafka 3.9 所需 API，需在 M2 reactor 回归中覆盖普通 JobContainer 与 Kafka Reader/Writer。 |
| 2026-08-27 | STREAMING 的 `resetOffset=true` 只在一次逻辑 run 的首个 attempt 执行，并把各分区起始 offset 提交给消费组。 | Kafka 流式 Session 原实现忽略 `resetOffset`；若每个恢复 attempt 都重置则会无限从头重放，若只 seek 不提交则首 attempt 在首批提交前失败后会恢复到旧消费组位置。 | Dispatch 携带 `streamAttemptNo`；Worker 仅为 attemptNo=1 传递 reset；Kafka 在首次 assignment 时 seek 到 beginning 并提交该位置，后续消息 offset 仍严格在 Writer 成功后推进。 |
| 2026-08-27 | 流式协调器和 Worker 的 JSON Map Wrapper 更新显式绑定既有 `JacksonTypeHandler`，事件 details 中的时间统一写 ISO 字符串。 | M4 SQLite 集成测试证实未指定 TypeHandler 的 `LambdaUpdateWrapper.set(jsonMap)` 会落成 `{k=v}` 而非 JSON，且默认 `JacksonTypeHandler` 不能直接序列化 `LocalDateTime`；checkpoint、下线 payload 和失败事件会在真实运行时损坏。 | 复用文件传输 checkpoint 的现有 MyBatis-Plus 写法，CAS 条件不变；空值清理仍使用 Wrapper 显式 set null，事件结构不新增公开字段。 |
| 2026-08-27 | M5 日志采用“流式 attempt 专用分片、批任务旧路径兼容”的增量实现；保留现有 `/log` JSON 接口，新增 `/log/archive` 流式 ZIP 下载。 | 既有批任务和前端依赖 `RunLogView` JSON，直接改造下载接口会扩大回归面；无限流式日志又不能继续使用单文件整读。 | STREAMING attempt 使用小时/128MB 分片、封存上传和 `run_log_chunk` 元数据；BATCH 继续使用旧文件路径。对象存储增加文件/流式能力但保留 byte[] API。 |
| 2026-08-27 | 分钟指标以 Worker 定时 snapshot 与上次 snapshot 的 delta 落库；同一 `(tenant, project, attempt, bucketStart)` 使用幂等更新。 | Kafka session 的 counters 是 attempt 累计值，直接重复写入会造成指标双计数。 | 记录 delta counters、lag/maxLag gauge 和时间戳；缺少 source metrics 的旧/非 Kafka session 仍安全写入零值桶。 |
| 2026-08-27 | M5 增加 Worker 侧 60 秒活动日志同步调度，并让对象存储归档按 `run_log_chunk` 顺序输出全部分片；`full=true` 日志响应改为返回实际 512KB 页数并定位到最后一页。 | 首轮实现仅提供了同步/分片 API，但没有调用同步调度；对象存储指针只保存主文件键，无法覆盖已封存 gzip 分片；`1/1` 元数据会误导分页客户端。 | 活动分片和本地分片元数据均按周期刷新；归档优先查询同一 run 的分片并保留单对象兼容回退；旧客户端仍可读取最后 512KB，同时获得真实 `pageNo/totalPages`。 |
| 2026-08-27 | M6 修复单源任务字段映射未传递 `sourceFields` 的前端阻塞问题。 | Browser 验收使用现有 Kafka `test-data` 模型创建流式任务时，模型详情可见 `id`、`test_param`，但单源映射下拉显示“无数据”，保存被“请填写源字段”拦截；Fusion 路径已有按别名字段集合，不受影响。 | `CollectionTaskMappingSection` 同时向映射编辑器传递单源字段集合，保持 Fusion 的 `sourceFieldOptionsByAlias` 优先级；新增 UI 回归需覆盖 Kafka 单源映射可选和草稿保存。 |
| 2026-08-27 | M6/M3 运行库迁移按项目约定使用独立 `StudioSchemaUpgradeApplication`，不打开 Server/Worker 的自动升级开关。 | 重启后的真实 Studio 业务库仍是历史 Schema，API 查询在加载 `execution_mode` 时返回 `Unknown column`；`application.yml` 明确将 `studio.schema.auto-upgrade-on-startup=false`，以避免多实例并发 DDL。 | 使用固定 JDK/Maven 执行 `StudioSchemaUpgradeApplication` 完成幂等升级，再重试 UI；不删除或重建业务表，不清空任务和 Topic。 |
| 2026-08-27 | 对齐 M5 活动日志同步周期为 60 秒。 | 代码核对发现 Worker 调度方法仍使用 10 秒固定延迟，与主计划“活动分片每 60 秒同步”的约定不一致；10 秒会放大对象存储 PUT、分片元数据更新和数据库写入压力。 | 将调度周期改为 60 秒；attempt 结束时仍立即执行最终分片归档，日志查询继续读取本地/对象分片，不改变批任务旧日志路径。 |
| 2026-08-27 | 修复 `run_log_chunk` 首次插入的非空状态字段顺序。 | 真实 Worker 日志同步发现实体先 `insert` 后设置 `status`，MySQL 报 `Field 'status' doesn't have a default value`，活动分片无法落库，UI 日志分片为空。 | 在首次插入前写入状态、路径、大小、校验和等字段；保留后续幂等更新和对象存储失败处理，不改变表结构或历史日志。 |
| 2026-08-27 | 任务级流式指标查询按分钟聚合跨 attempt 数据，底层仍按 attempt 保留原始桶。 | Worker 重启会产生新的 attempt；直接返回按 attempt 的分钟桶会在 UI 出现同一分钟重复行，并把趋势误读为重复计数。计划要求流式趋势不重复叠加 attempt 最终值。 | `stream_metric_bucket` 表结构和审计明细不变；任务级 API 对计数列按分钟求和、对 lag 取当前/最大值、保留最后消息/检查点时间，避免重启前后重复展示。 |
| 2026-08-27 | 流式日志 `/log` 分页把活动文件与已滚动 gzip 分片拼接为一个逻辑日志。 | 原实现只读取活动文件；滚动后历史分片虽能归档，但分页查看会丢失历史内容，无法满足跨分片回顾。 | 分页按 512KB 上限流式读取并解压分片，归档仍逐分片写入 ZIP；旧批任务路径不变。 |
| 2026-08-27 | 保留字符串 ID 类型契约故障，并新增数值型隔离验收资源。 | 旧验收消息使用字符串 ID，而 Kafka 输入模型声明 `id: INT`、OSS 目标声明 `id: LONG`；Writer 在 `StringColumn.asLong` 转换失败，offset 不推进。 | 保留失败任务、数据、run/attempt、checkpoint、事件和日志作为可追溯故障证据；不修改既有模型契约，改用 `NativeStreaming-M7-NumericInput-20260827` 及三条新任务验证可运行链路。 |
| 2026-08-27 | M7 真实作业先完成短批和 Worker 重启恢复，再执行故障矩阵和长稳。 | 当前 Worker 独立 streaming slot 为 2，且已有任务和历史失败证据需要保留；一次性注入全部故障会混淆 offset、run 和 attempt 归因。 | 保留三条新任务、Topic、消费组和运行记录，按故障类型创建额外 `NativeStreaming-*` 隔离资源；M7 在完成 Broker/目标不可用、commit 失败、Server 重启、10 次失败、recover 和 2 小时长稳后再验收。 |
| 2026-08-27 | commit 成功与 Studio checkpoint 监听器上报必须形成不可逆边界，监听器异常不得回到 Writer/commit 重试；Worker 执行终止时先移除 active heartbeat，再做最终上报。 | 真实业务库故障中，消息 114 已写入目标且 Kafka offset 已提交，但 checkpoint 上报异常触发 Writer 重跑并因主键重复失败；最终上报阻塞又让无 consumer 的 attempt 持续假 RUNNING。 | Core 把 `onBatchCommitted` 移出批次执行/commit 的 retry catch，并增加“监听器失败只执行一次 Writer/commit、零 abort”回归；Worker 对 checkpoint 上报单独退避重试并尊重停止，结束路径提前移除 active attempt，最终指标/日志/失败上报不得维持幽灵心跳。 |
| 2026-08-27 | 流式监控下载 Blob 归档时把临时锚点挂载到 DOM，点击后移除并释放 Object URL。 | 归档接口两次返回 HTTP 200 和完整 ZIP，但未挂载的临时锚点在应用内 Browser 中没有产生可观测 download 事件，阻塞 M6 下载验收；项目现有 `RunLogDrawer` 已采用挂载后点击的兼容写法。 | 不改变归档 API、认证或日志内容；只统一前端下载触发方式，并用 Browser download 事件和 ZIP 回读补充验收。 |
| 2026-08-27 | 为 Worker 的 native streaming Java 包增加精确 `.gitignore` 例外，继续忽略真实运行目录。 | M8 前置审计发现既有 `backend/**/runtime/` 规则同时命中了 `src/main/java/.../runtime` 和 `src/test/java/.../runtime`，导致 Streaming Worker、指标累加器及关键测试只存在于本地构建目录语义中，无法进入交付变更集。 | 只放行 `runtime/streaming` 实现、对应测试和 `WorkerStreamingDispatchTest`；其他 runtime 产物及历史忽略文件保持忽略，不改变运行时路径和部署结构。 |
| 2026-08-27 | STREAMING 编辑锁统一以 `desiredState=RUNNING` 判定，并同时锁定整个配置区、页头/页尾保存入口和保存函数。 | 1440x900 Browser 验收发现运行中的任务只禁用了执行模式与流式参数，任务名、源/目标绑定、Writer 参数和“保存草稿”仍可操作；仅按 `observedState` 判断还会漏掉 FAILED 但期望继续运行的 deployment。 | 上线到下线期间所有运行配置保持只读；步骤导航和刷新仍可用于回顾，必须先下线才能修改或保存；Server 既有运行中编辑拒绝继续作为最终防线。 |
| 2026-08-27 | M6 以“UI 功能和布局通过、Blob download 事件捕获受当前 Browser 表面限制”收口，不把网络 ZIP 证据冒充 download 事件。 | 应用内 Browser 能验证请求、响应和 ZIP 内容，但当前未暴露前端 Blob 下载为高层事件；该差异不属于 Server、SDK 或 ZIP 内容故障。 | M6 标记已验收并保留环境限制；生产浏览器仍需在发布验收中确认最终下载交互。 |
| 2026-08-27 | M8 采用“本地实施已完成、生产环境待验收”的双层结论。 | 本机框架、API、真实作业、UI、故障和长稳均有证据，但单机 Kafka/Worker 不能代表生产网络、容量和高可用。 | 关闭本地完成定义；生产发布前仍必须执行部署文档中的容量、HA、网络和回滚窗口验收。 |
| 2026-08-27 | 增加 Native Streaming 历史清理服务，默认关闭。 | 长期运行会持续产生分钟指标、事件和日志分片元数据；仅依赖对象存储生命周期无法清理数据库元数据。 | 生产通过 `STUDIO_STREAMING_HISTORY_CLEANUP_ENABLED=true` 显式开启；默认 30 天、每批 1000 行、每小时执行，活动 run/attempt 受保护，对象删除失败保留元数据重试；当前测试环境继续保留全部证据。 |
| 2026-08-27 | 历史清理扩展到终态 attempt/run，并保留证据依赖链。 | 仅清理指标、事件和日志分片仍会让长期运行的逻辑 run 与物理 attempt 元数据无限累积；直接删除又可能破坏活动任务或日志回顾。 | 仅删除超过保留期且已结束的终态 attempt/run；活动 deployment、活动 attempt、仍有日志分片引用的 attempt 均受保护，先清理子记录再清理父记录；对象删除失败会阻止后续 attempt/run 删除并等待重试。 |
| 2026-08-27 | 增加 `kafka-it` Maven profile 作为发布前真实 Kafka 门禁。 | 默认 reactor 为避免依赖 Docker 不执行 Kafka IT，容易把跳过误读为兼容性通过。 | `mvn -pl job-plugins/reader/kafkareader -am -Pkafka-it test` 显式启用 `KafkaStreamingSessionIT`；普通构建行为不变。 |
| 2026-08-27 | 发布门禁发现 `FileTransferOutboxServerConfiguration` 缺少 `RunLogObjectStore` 导入，先记录决策再补齐导入。 | `studio-server` 全量 `clean package` 才会编译到该配置类；单元测试阶段未暴露，正式制品构建被阻塞。 | 仅增加显式 import，不改变 Bean、API 或运行时行为；Server/Worker 后续 `clean package` 已通过。 |
| 2026-08-27 | 补齐 `FileTransferOutboxServerConfiguration` 对 `RunLogObjectStore` 的显式导入。 | 发布前 `studio-server` 全量 `clean package` 首次编译发现配置类引用缺少 import；单元测试阶段未覆盖该编译路径，导致正式制品门禁被阻塞。 | 仅增加编译期导入，不改变 Bean、API 或运行时行为；需重新执行 studio-server/studio-worker 打包和定向测试。 |
| 2026-08-28 | 流式分钟指标改为“聚合后分页、最新优先”，空闲 0 记录桶只在当前 UI 页内按状态安全合并。 | 原实现按升序读取最多 10080 条原始 attempt 桶并整体返回，数据超过上限时最新分钟不可见且页面无法翻页；长期空闲任务会产生连续零增量桶，但直接删除或跨状态合并会丢失 lag、checkpoint、rebalance 和故障回顾证据。 | `/streaming-metrics` 返回 `PageView<StreamingMetricBucketView>`，服务端先按分钟合并跨 attempt 再分页；前端增加分页并以最新页为首屏，连续计数为 0 且 lag/checkpoint/attempt 不变的桶仅作展示层区间折叠，数据库原始桶与清理策略不变。 |
| 2026-08-28 | 修正空闲分钟桶折叠的连续分组锚点，并增加“仅显示有读取记录”筛选；日志分片增加按 `chunkId` 的受限预览。 | Browser 验收发现连续 7 个空闲桶被错误拆成多个 2 分钟区间；仅在前端当前页过滤会造成分页总数与可见数据不一致；已有 run 日志接口读取的是逻辑 run，不能准确预览单个滚动分片。 | 前端折叠比较当前分组的最后一个桶，连续空闲分钟可合并为完整区间；`/streaming-metrics` 增加 `onlyWithRecords`，按 `recordsRead > 0` 在聚合后分页；新增 `/streaming-log-chunks/{chunkId}/preview`，单页最多 512KB，沿用 Worker/对象存储权限和脱敏链路，不改变原始桶、日志分片或归档数据。 |
| 2026-08-28 | 交付包更新为 `studio-native-kafka-upgrade-20260828-metrics-v2`，Kafka 插件仓库追加 `20260828-native-kafka-metrics-v2`。 | 分钟指标分页与空闲桶补丁已进入当前源码和构建制品；旧 `release_0824`、旧 `20260828-native-kafka` 插件 release 以及测试数据必须继续保留。 | `package_all/release_0828` 新增完整升级包，包含刷新后的 Server/Worker/Core、Web/Desktop、Kafka 三插件、增量 SQL、主计划、接口文档和测试用例；旧包和旧 release 不覆盖。 |
| 2026-08-28 | 修复日志分片预览被误报为不存在的问题：`RunService.getLogPointer()` 精简查询补充 `collection_task_id`。 | 新增预览接口按分片所属任务做归属校验，但日志指针查询未返回任务 ID，导致有效的 `run_log_chunk` 在 Server 层被错误拒绝为 `NOT_FOUND`。 | 仅增加既有 `run_record` 查询的返回列，不改变权限边界、日志内容、分片元数据或旧日志接口；增加查询契约回归并重新执行 Server/Worker 构建和 Browser 预览验收。 |
| 2026-08-28 | 在按 IDEA 配置重启 Server/Worker 后完成日志预览和分钟过滤运行时复验；本轮插件 staging 采用已验收的 v2 内容生成不可变 v4 release。 | 首次预览路由加载后仍返回 `Streaming log chunk not found`，根因修复需要进入运行进程；直接从工作区聚合目录重打插件会混入旧 `slf4j-api-1.7.10.jar`，而 v2 归档依赖已统一为 1.7.36。 | Server PID `18920`、Worker PID `48492` 使用 JDK 17、IDEA VM 参数和 `STUDIO_CLUSTER_CODE=default-local` 重启；预览显示 12784 字节真实日志，分钟过滤从 8 条变为 1 条；当前插件指针使用 `20260828-native-kafka-metrics-v4`，旧 v2/v3 release 保留且不覆盖。 |

## 11. 验收证据索引

| 里程碑 | 测试命令/工具 | 结果 | 证据位置 |
| --- | --- | --- | --- |
| M0 | Java/Maven/Git/Docker/Kafka 只读基线、`git diff --check`、文档索引检查 | 通过；JDK 17.0.12、Maven 3.8.8、Kafka 3.9.1；计划文档已落盘且 README 索引存在 | 本文件第 6 节、`docs/README.md` |
| M1 | 指定 JDK/Maven 执行 `mvn -pl core -am test`；随后执行 `mvn -pl core -am -Dtest=StreamingJobContainerTest,AbstractRunnerTest,ChannelStatisticsTest -Dsurefire.failIfNoSpecifiedTests=false test`；`git diff --check`；Reader/Writer 契约 diff 检查 | 完整 reactor 30/30 通过，最终定向 8/8 通过，0 failure/error/skipped；旧 Reader/Writer 抽象文件无变更；取消保持 KILLED，插件仅销毁一次，取消中断不再触发未持锁 unlock 或 Reporter ERROR。M1 仅使用内存记录，无数据库/Kafka 测试数据 ID。 | `core/target/surefire-reports/TEST-com.jdragon.aggregation.core.streaming.job.StreamingJobContainerTest.xml`、`TEST-com.jdragon.aggregation.core.transport.channel.ChannelStatisticsTest.xml`、`TEST-com.jdragon.aggregation.core.taskgroup.runner.AbstractRunnerTest.xml`；Maven 结束时间 2026-08-27 02:09:53+08:00 |
| M2 | 指定 JDK/Maven 的 16 个定向测试、43 个 reactor 测试、1 个 Docker Kafka IT；Kafka CLI Topic/消费组检查；`dependency:tree`；契约 diff、敏感日志扫描和 `git diff --check` | 全部通过；真实组 offset 20 -> 25；commit 后推进，abort/restart 重放 batchId 稳定；无 active member；SLF4J 1.7.36 仲裁正确；旧契约无变更 | 本文件第 6.5 节；`core/target/surefire-reports`、`job-plugins/reader/kafkareader/target/surefire-reports`；Topic `NativeStreaming-M2-20260827`；Group `NativeStreaming-M2-20260826184558335` |
| M3 | 固定 JDK/Maven 的 687 个后端模块测试；SQLite 初始化/重复升级；两个隔离 MySQL 库的初始化/增量 SQL 双执行；API MockMvc；并发状态机；`git diff --check` | 全部通过；历史任务 BATCH；并发无双 run/attempt；6 张表和 13 个关键索引一致；业务库与既有任务未修改 | 本文件第 6.6 节；各模块 `target/surefire-reports`；数据库 `NativeStreaming_M3_20260827`、`NativeStreaming_M3_Upgrade_20260827` |
| M4 | 固定 JDK/Maven 的 996 项 Studio reactor 测试；17 项 M4 定向测试；Dispatch/契约/日志/Bean 审计；两个仓库 `git diff --check` | 通过；0 failure、0 error、1 个既有外部 OSS 条件测试 skipped；单 active attempt、独立 2 slots、优雅停止、重启恢复、10 次失败暂停与 recover 均有回归覆盖 | 本文件第 6.7 节；`backend/studio-infra/target/surefire-reports`、`backend/studio-worker/target/surefire-reports`；完成时间 2026-08-27 04:35:06+08:00 |
| M5 | 固定 JDK/Maven 的日志/指标定向测试、最新完整 Studio reactor、真实对象同步与长稳 API、ZIP 网络响应 | 已验收；真实 RollingFileAppender 超过 128MB 后滚动，跨 gzip/活动分片分页和流式 ZIP 通过，活动对象分片持续同步，分钟指标零重复，秘密扫描无命中。 | 第 6.8 节；`RunLogFileServiceSecurityTest` 5/5、`DataIngestionAccessLogSupportTest` 16/16、完整 reactor 报告。 |
| M6 | Web/Desktop 构建、应用内 Browser 390x844/1440x900、运行中编辑锁、监控与归档请求 | 已验收；两种视口无页面横向溢出，运行配置完整锁定，控制台无 warn/error；ZIP API/内容有效，Blob download 事件捕获限制已单列。 | 第 6.9 节；保留的 `/collection-tasks` Browser 页面与前端构建输出。 |
| M7 | 三类真实 STREAMING、BATCH 回归、Broker/目标/commit/Server/Worker 故障、失败阈值/recover、重复上线/下线和长稳 | 已验收；三个真实目标、offset/checkpoint、隔离目标失败、Kafka fenced commit、10 次失败暂停、人工 recover、重启恢复和超过 2 小时运行均有事件/指标/日志证据。 | 第 6.10 节；全部 `NativeStreaming-M7-*` 任务、Topic、消费组、运行历史、日志和指标保留。 |
| M8 | 固定 JDK 17/Maven 3.8.8 的框架/后端回归；接口、测试、部署/回滚文档；两个仓库 diff、链接、秘密和运行通道审计 | 已验收；DataAggregation 定向 reactor Core 33/33、Kafka streaming 12/12；Studio 完整 reactor 1009 项、0 failure/error、1 个既有 OSS 条件跳过；两侧 `git diff --check` 为 0。Server/Worker `UP`，DEFAULT-LOCAL 在线，Kafka 容器运行。 | 完成时间 2026-08-27 17:18:32+08:00；`docs/使用/接口/03-collection-and-field-mapping.md`、测试用例、部署文档与本文件。 |
| 收口补丁 | 固定 JDK 17/Maven 3.8.8 的流式历史清理定向测试与 `-Pkafka-it` Docker Kafka 门禁 | 已验收；清理测试 3/3（默认关闭、活动记录保护、终态 attempt/run 级联保护、对象删除失败重试）；Kafka profile 2/2，Core 33/33，0 failure/error/skipped。未删除现有测试资源。 | `backend/studio-infra/target/surefire-reports/TEST-com.jdragon.studio.infra.service.StreamingHistoryCleanupServiceTest.xml`；`job-plugins/reader/kafkareader/target/surefire-reports/TEST-com.jdragon.aggregation.kafka.KafkaStreamingSessionIT.xml`；清理测试完成时间 2026-08-27 21:19:20+08:00，Kafka profile 完成时间 2026-08-27 20:56:52+08:00。 |
| 指标监控补丁 | `StreamingTaskRuntimeServiceIntegrationTest`、`CollectionTaskStreamingRouteContractTest`、Web/Desktop 构建、`test:collection-task-streaming-lock`、应用内 Browser | 已验收；后端分页回归 6/6 与路由契约 1/1 通过，0 failure/error/skipped；API 先按分钟聚合跨 attempt 再分页并按最新优先；前端首屏显示最新页、分页总数和页码，连续零增量桶在当前页内安全折叠；Web/Desktop 构建和流式配置锁定回归通过；Browser 监控页显示“共 8 条”、`2026-08-27 09:48:00 ~ 09:49:00 · 2 分钟空闲`，控制台 warn/error 为空。Server 重启后 PID `51436`，`/actuator/health` 为 `UP`；未删除任何任务、Topic、run、attempt、日志或指标。 | `backend/studio-infra/target/surefire-reports/TEST-com.jdragon.studio.infra.service.StreamingTaskRuntimeServiceIntegrationTest.xml`；`backend/studio-server/target/surefire-reports/TEST-com.jdragon.studio.server.web.controller.CollectionTaskStreamingRouteContractTest.xml`；`frontend/apps/web` 的构建输出与 `scripts/collection-task-streaming-lock.test.mjs`；保留的应用内 Browser 页面 `/dfs/data-aggregation-studio/collection-tasks`。 |
| 发布门禁收口 | DataAggregation/Studio `clean package`、Web/Desktop 生产构建、前端锁定回归、交付白名单 | 通过；DataAggregation 12 个 reactor 模块、Studio Server/Worker 及依赖模块、Web/Desktop 均成功；前端锁定测试通过；缺失 `RunLogObjectStore` 导入已修复。当前证据来自工作区 clean target，不替代干净 clone、正式启动和生产 HA/认证验收。 | `docs/运维/部署/studio-native-kafka-streaming-delivery-manifest-20260827.md`；DataAggregation `core/target`、Kafka Reader/Writer `target`；Studio `backend/studio-server/target/*exec.jar`、`backend/studio-worker/target/*exec.jar`；Web/Desktop `dist`。 |
| 日志预览与过滤运行时补丁 | `RunServiceLogPointerQueryTest`、`StreamingTaskRuntimeServiceIntegrationTest`、Server/Worker `package`/`install`、IDEA 等价重启、应用内 Browser | 已验收；查询契约 1/1、分钟指标回归 7/7；Server/Worker package/install 成功；18080/18081 health 均 `UP`；日志分片预览显示序号 0、12784 字节和真实内容；勾选“只显示有读取记录的分钟桶”后显示 1 条（09:42，读取 3、写入成功 3）；Browser 控制台 `[]`。未删除任务、Topic、run、attempt、日志或指标。 | `backend/studio-infra/target/surefire-reports/TEST-com.jdragon.studio.infra.service.RunServiceLogPointerQueryTest.xml`；`TEST-com.jdragon.studio.infra.service.StreamingTaskRuntimeServiceIntegrationTest.xml`；Server PID `18920`、Worker PID `48492`；应用内 Browser `/dfs/data-aggregation-studio/collection-tasks`；重启日志 `backend/runtime/idea-restart-20260828/`。 |

## 12. 未完成事项

- [x] M0 文档链接、基线和工作区保护核验。
- [x] M1 DataAggregation 流式内核。
- [x] M2 Kafka checkpoint。
- [x] M3 Studio Schema、状态机和 API。
- [x] M4 Worker 常驻执行与恢复。
- [x] M5 日志分片和分钟指标。
- [x] M6 前端、SDK 与应用内 Browser。
- [x] M7 真实作业、故障恢复和本地长稳。
- [x] M8 全量回归、接口/测试/部署/回滚文档和交付审计。
- [x] 收口补丁：流式历史清理默认关闭、生产可配置，终态 run/attempt 受保护并按证据依赖链分批清理，Kafka Docker IT 有显式 `kafka-it` 门禁。
- [x] 指标监控补丁：分钟指标按任务分钟聚合后分页、最新分钟优先，前端提供页码和总数，连续且状态不变的零增量桶仅在当前页内折叠，原始桶保留。
- [x] 日志分片预览与有记录分钟桶过滤运行时回归：查询投影、受限预览、Browser 真实日志内容和过滤结果均已验证；相关测试数据和日志保留。
- [x] 形成精确 Git 交付白名单，并完成当前工作区 `clean package`、Web/Desktop 生产构建和前端锁定回归；详见发布交付白名单。当前仍未完成真正干净 clone 的复现构建和正式制品启动。
- [ ] 从干净检出复现构建并启动正式 Server/Worker/Web/插件制品；当前工作区仍含用户运行产物和未跟踪交付候选，不能以本地 `target` 制品替代。
- [ ] 当分钟桶达到百万级时，将 `metricsPage()` 的唯一分钟键查询优化为数据库原生 `COUNT DISTINCT` 与 `DISTINCT ... LIMIT/OFFSET`，并补充 MySQL/SQLite 大数据量基准；当前实现已避免原始 attempt 行的 10080 条截断，但仍在 Java 中加载任务范围内的唯一分钟键。
- [ ] 生产 Kafka 多分区/rebalance、协议认证（SASL/SSL/Kerberos）、生产 OSS Provider/权限、HA、容量和发布回滚窗口验收。

本地完成定义已关闭。生产环境仍需单独验收 Kafka/目标系统网络与 ACL、对象存储生命周期和容量、多 Worker/Server 高可用、监控告警、发布窗口与应用版本回滚；当前 Browser 不暴露 Blob download 高层事件的限制也应在生产浏览器验收中复核。Git 交付集和干净制品属于生产前发布工程门禁，不能由本地脏工作区测试代替。
