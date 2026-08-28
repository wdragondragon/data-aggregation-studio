# Studio Native Kafka 长期运行验收用例

**计划编号：** P0-KS-01
**适用版本：** 2026-08-27 本地实施基线
**状态：** 本地验收记录持续回填，测试资源保留

## 1. 范围与保留规则

本用例覆盖 DataAggregation Native Streaming 内核、Kafka Source、Studio Server/Worker、Web UI、日志、指标和四类真实数据链路。除明确写为只读探测的用例外，新增资源统一使用 `NativeStreaming-*` 命名并保留，便于回顾和重放。

不得删除或重置以下已有资源：`test-data`、既有 Kafka 兼容性 Topic/消费组、三个 Kafka 兼容性任务、已有模型、运行记录、日志和指标。每次执行记录任务 ID、模型 ID、逻辑 run ID、attempt ID、Topic、消费组、测试消息范围和 offset 前后值；凭据、Token、密码和对象存储秘密不得写入记录。

## 2. 环境前置

| 项目 | 基线 |
|---|---|
| Java | `C:\dev\Java\jdk-17.0.12` |
| Maven | `C:\dev\apache-maven-3.8.8` |
| Kafka | `kafka-single`，`apache/kafka:3.9.1`，监听 `127.0.0.1:9092` |
| Studio | Server `18080`，Worker `18081`，集群 `DEFAULT-LOCAL` |
| UI | 应用内 Browser，桌面 `1440x900`、移动 `390x844` |

Java 测试命令必须先设置 `JAVA_HOME`，例如：

```powershell
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
& 'C:\dev\apache-maven-3.8.8\bin\mvn.cmd' -pl studio-infra,studio-server,studio-worker -am test
```

## 3. 框架与插件

| 编号 | 用例 | 操作与断言 | 证据 |
|---|---|---|---|
| F-01 | 普通批任务兼容 | 使用未修改的 Reader/Writer 抽象和普通 `JobContainer` 执行一条批任务；断言原有生命周期、Reporter 和运行记录行为不变。 | Maven 报告、任务/run ID |
| F-02 | 流式 SPI 可选接入 | Kafka Reader 实现 `StreamingSource/Session/Batch/Checkpoint`；旧 Reader/Writer 不增加必须实现的方法。 | 契约 diff、编译报告 |
| F-03 | 有界微批执行 | 流式 Source 返回有限批次，复用 Transformer、Channel、Reporter 和任意旧 Writer；Writer 生命周期完整结束后才允许 checkpoint commit。 | Core 测试、attempt 日志 |
| F-04 | 取消和资源释放 | 调用正式 `cancel()`，Source wakeup，当前批次在超时内结束，插件 ClassLoader、Consumer、Writer 只销毁一次。 | Core 测试、Worker 日志 |

## 4. Kafka Reader/Writer 与 checkpoint

| 编号 | 用例 | 操作与断言 |
|---|---|---|
| K-01 | 连接和扁平配置 | 使用 `bootstrap.servers`、`group.id`、`topic` 等扁平键初始化 Kafka；断言配置被正确读取，缺少 Broker/Topic 时返回脱敏错误。 |
| K-02 | JSON Reader | 发送合法 JSON，配置 `columns`、`offsetReset=earliest`；断言字段和值按模型类型转换。 |
| K-03 | split Reader | 使用分隔文本和尾部空字段；断言列数、空值和字段顺序正确。 |
| K-04 | Reader checkpoint | `enable.auto.commit=false`；poll 后 snapshot 包含 Topic/Partition/offset；未 commit 前消费组位点不推进。 |
| K-05 | 成功 commit | Writer 完整成功后提交每个分区下一 offset；断言 committed offset 等于 checkpoint next offset，稳定 `batchId` 可复现。 |
| K-06 | Writer 失败 abort | 让 Writer 抛错或返回失败；断言不提交 offset，abort 后重新 poll 得到相同 batchId，可解释地重放。 |
| K-07 | commit 失败 | 注入 Kafka commit 异常；断言 Writer 不重复执行于同一 commit 边界之外，offset 不提前推进，后续 attempt 可重放。 |
| K-08 | Listener 上报失败 | 让 commit 后 Studio checkpoint/指标上报失败；断言不重跑 Writer、不 abort 已提交批次；上报独立退避，attempt heartbeat 先停止再做最终上报。 |
| K-09 | resetOffset | 首个 attempt 可按 `resetOffset=true` 从 beginning 建立消费组起点；恢复 attempt 不重复 reset，从 committed offset 继续。 |
| K-10 | pause/heartbeat | Writer 工作期间暂停已分配分区并持续 poll/heartbeat；断言不因 `max.poll.interval.ms` 失去分区。 |
| K-11 | Writer 配置 | 验证 JSON、split、rawData、`acks`、`retries`、`batchSize`、自动建 Topic 和既有 Topic 写入；记录实际消息内容和数量。 |
| K-12 | dirty record | 发送字段缺失、类型错误和非法 JSON；断言 dirty 计数/错误转换符合任务策略，日志不包含完整消息正文或敏感值。 |

## 5. Studio 状态、并发和故障

| 编号 | 用例 | 操作与断言 |
|---|---|---|
| S-01 | BATCH 兼容 | 历史任务升级后 `executionMode=BATCH`，仍可发布、定时和手动触发；不自动启动流式 deployment。 |
| S-02 | STREAMING 上线 | 保存 `executionMode=STREAMING` 后调用 `/online`；创建一个逻辑 run 和最多一个 active attempt，状态最终 RUNNING。 |
| S-03 | 重复上线 | 并发调用两次 `/online`；返回相同 generation/run/attempt，不产生双 attempt。 |
| S-04 | 下线 | 调用 `/offline`；期望状态 STOPPED，等待当前批次或按超时取消，后续不再 poll。重复调用幂等。 |
| S-05 | 停止超时 | 让 Writer 超过 `stopTimeoutMs`；断言任务进入 STOPPED/可恢复状态，未提交的批次下次重放。 |
| S-06 | 运行中保护 | 在 `desiredState=RUNNING` 时编辑、保存、删除、schedule、trigger；断言后端拒绝，UI 控件 disabled。 |
| S-07 | FAILED 与 recover | 连续失败达到 10 个 attempt；断言 observedState=FAILED 且不继续刷任务。调用 `/recover` 清零失败计数并继续消费。重复 recover 幂等。 |
| S-08 | Broker 故障 | 暂停 Kafka 容器；断言 checkpoint/committed offset 不提前推进，任务进入恢复或保留运行态并记录事件；恢复 Broker 后继续消费。 |
| S-09 | 目标不可用 | 在隔离目标上停止 MySQL 或 OSS；断言 Writer 失败不提交 offset，恢复目标后可重放且业务幂等。不要暂停承载 Studio 业务库的共享容器代替目标故障。 |
| S-10 | Server 重启 | 严格复用 IDEA 的 `StudioServerApplication` 配置重启；断言 Server 恢复后 deployment generation 不变，Worker 可继续协调。 |
| S-11 | Worker 重启 | 严格复用 IDEA 的 `StudioWorkerApplication` 配置重启；旧 attempt 标记中断，RUNNING deployment 创建新 attempt，从 committed offset 续读；STOPPED/OFFLINE 不恢复。 |
| S-12 | slot 隔离 | 启动超过默认 2 个流式任务；断言多出的任务排队，普通批任务和文件传输槽位仍可使用。 |

## 6. 真实作业矩阵

每个任务上线后保留任务、run、attempt、日志、指标、Topic、消费组和目标数据。输入数据应使用与模型声明一致的数值/字符串类型，避免把类型契约故障误判为框架故障。

| 编号 | 作业 | 模式 | 最小断言 |
|---|---|---|---|
| J-01 | Kafka -> MySQL | STREAMING | JSON 消费、字段映射、主键/upsert、成功后 offset 推进、MySQL 目标行数和值正确。 |
| J-02 | Kafka -> Kafka | STREAMING | 输出 Topic 消息内容/字段顺序/数量正确，幂等 producer 参数生效，commit 失败允许可解释重放。 |
| J-03 | Kafka -> OSS | STREAMING | JSON/split/rawData 代表性写入，稳定 batchId 对象路径，重放不产生不可解释副作用。 |
| J-04 | MySQL -> Kafka | BATCH | 原有批 Reader/Writer 仍可运行；不因 Kafka Writer 目标自动变成长驻任务。 |

## 7. 日志与指标

| 编号 | 用例 | 操作与断言 |
|---|---|---|
| L-01 | 滚动上限 | 真实 `RollingFileAppender` 活动文件超过 128MB；断言生成 gzip 历史分片，活动文件重新小于上限。 |
| L-02 | 时间滚动 | 跨小时或模拟小时边界；断言每个分片可独立读取和归档。 |
| L-03 | 活动同步 | 验证 Worker 活动分片约每 60 秒同步一次，首次 INSERT 含状态、路径、大小和校验和。 |
| L-04 | 分页 | `/log` 单页不超过 512KB，活动文件与 gzip 历史分片拼接后顺序完整，`full=true` 返回真实页数。 |
| L-05 | ZIP 归档 | `/runs/{id}/log/archive` 返回 `HTTP 200`、`application/zip`、`Content-Disposition` 和 `PK` 首部；逐分片写出，不整份加载进堆。 |
| L-06 | 日志脱敏 | 扫描 Server/Worker 和对象归档日志；密码、Token、消息正文和完整连接配置不得出现，敏感内容使用掩码。 |
| M-01 | 分钟桶 | 连续 snapshot 写入读取、写成功、失败、dirty、字节、批次、重试、lag、最后消息、checkpoint、rebalance。 |
| M-02 | 幂等与聚合 | 同一 `(attempt,bucketStart)` 重复写入不重复累计；任务级查询跨 attempt 同一分钟聚合计数，lag/checkpoint 取当前规则。 |
| M-03 | 空闲流 | Kafka 空 poll 期间分钟桶保持零增量，不产生高频 INFO 日志。 |

## 8. UI 验收

使用应用内 Browser 完成，不以 API 结果冒充 UI 结果。

| 编号 | 视口 | 用例与断言 |
|---|---|---|
| U-01 | 1440x900 | 列表显示执行模式、状态、目标；运行中任务编辑和删除 disabled；无横向溢出。 |
| U-02 | 390x844 | 任务列表、监控抽屉、指标、事件、日志分片可回顾；横向表格在受控容器内，无文字覆盖。 |
| U-03 | 两种视口 | 编辑器 BATCH/STREAMING 选择、groupId、offset、微批参数和运行集群展示正确；STREAMING 隐藏 schedule/trigger。 |
| U-04 | 两种视口 | 运行中配置区、页头和页尾保存入口均锁定；下线后可编辑。 |
| U-05 | 两种视口 | 监控抽屉展示逻辑 run、attempt 时间线、吞吐、lag、checkpoint、事件和日志分片。 |
| U-06 | 两种视口 | 归档按钮请求成功并触发浏览器下载；若应用内 Browser 不暴露 Blob download 事件，记录为环境限制，同时保留 HTTP/ZIP 证据。 |
| U-07 | 两种视口 | Broker/Topic 错误提示展示可理解的脱敏 `Result.message`，不显示堆栈、密码或消息正文。 |

## 9. 证据记录模板

```text
用例编号：
执行时间（Asia/Shanghai）：
任务/模型/run/attempt ID：
Topic / groupId：
输入消息范围：
log end offset：
committed offset（前 -> 后）：
observedState / desiredState：
断言结果：PASS / FAIL / BLOCKED
日志或事件位置：
截图/Browser 页面：
备注（不得包含凭据、Token、密码或对象存储秘密）：
```

## 10. 完成判定

M7 只有在 J-01 至 J-04、Broker/目标/Server/Worker 故障、重复上线、停止超时、10 次失败与 recover、下线停止、Worker 重启续读和本地 2 小时长稳均有运行 ID、offset、日志和指标证据后，才能标记“已验收”。生产网络、高可用、容量、发布窗口和回滚演练仍需单独验收。
