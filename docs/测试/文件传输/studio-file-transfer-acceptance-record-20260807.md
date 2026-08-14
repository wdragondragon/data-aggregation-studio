# Studio 非结构化文件传输验收记录（2026-08-07，2026-08-12 修订）

> 结论：三阶段文件传输及 2026-08-09 单集群、单 Worker、非结构化管理、ACL、队列删除和 SSE 改造已完成；2026-08-11 的 OSS 非空目录保护、终态峰值保留、管理页数据源下拉和 SSE 超时断连缺陷均已修复。原 OSS/FTP 小样本验收已经通过；用户新增的 SFTP 4 GiB 四方向与三源文件管理扩展验收正在执行，尚未形成最终通过结论。
>
> 当前版本不提供跨集群文件传输或 Server Relay。至少 10 GiB 的资源压测、SFTP/MinIO 真实兼容矩阵和生产反向代理验证仍属于部署环境扩展验收，不影响本次 OSS/FTP 单集群功能验收结论。
>
> 2026-08-13 MySQL Outbox 高可用 SSE 的代码、事务/HA/重放/清理/Schema 自动化、真实 MySQL 健康、统一入口 P95/重连和双 Server 独立游标验收已通过；生产负载、反向代理滚动发布和 4 GiB 大文件业务矩阵仍属于独立扩展验收。

## 1. 验收范围与环境

- DataAggregation 文件传输核心、插件类型、CLI 和 Local/FTP/SFTP/MinIO/OSS 适配。
- Studio 文件浏览、即时队列、预设任务、调度、Dispatch、单 Worker 执行、工作流和指标。
- 单源非结构化管理：浏览、下载、新建目录、重命名、同源移动、删除和递归删除。
- 源级/路径级 ACL、项目成员边界、数据源创建者和管理员权限、操作审计。
- 共享运行集群选择、数据源和目录延迟加载、队列手动删除、SSE 实时更新。
- 本地验收服务：Server `18080`、Worker `18081`；所有页面操作统一从 `http://127.0.0.1:8000/dfs/data-aggregation-studio/` 进入，不使用 `5173`，运行集群为 `DEFAULT-LOCAL`。
- 真实文件源：现有 OSS、FTP、SFTP 三个测试源。本文不记录连接地址、账号、密码、AccessKey、Secret 或其他凭据。

## 2. 自动化测试结果

### 2.1 DataAggregation 内核与适配器

执行：

```powershell
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
mvn -q -pl file-transfer-core,file-transfer-plugin,file-transfer-cli,`
data-source-plugins/data-source-handler-file-local,`
data-source-plugins/data-source-handler-file-ftp,`
data-source-plugins/data-source-handler-file-sftp,`
data-source-plugins/data-source-handler-file-s3-minio,`
data-source-plugins/data-source-handler-file-oss -am test
```

结果：6 个测试类、36 项测试，0 失败、0 错误。

| 测试类 | 用例数 | 结果 |
|---|---:|---|
| `PluginRuntimeSessionTest` | 8 | 通过 |
| `FtpHelperTest` | 4 | 通过 |
| `LocalSourcePluginTest` | 7 | 通过 |
| `TransferEngineLocalIntegrationTest` | 11 | 通过 |
| `TransferConfigurationMapperTest` | 3 | 通过 |
| `FileTransferCliTest` | 3 | 通过 |

### 2.2 Studio 聚焦回归

执行：

```powershell
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
mvn -q -pl studio-server,studio-worker,studio-test -am `
  '-Dtest=*FileTransfer*,*Unstructured*,GlobalExceptionHandlerTest,RuntimeDatasourceProbeExecutorFileOperationTest,WorkerLifecycleRunnerRegressionTest,WorkerLifecycleRunnerClusterTest,WorkerResourceRevisionGuardTest,ExecutionEventServiceRegressionTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dspring.arthas.enabled=false' test
```

结果：23 个测试类、101 项测试，0 失败、0 错误。覆盖：

- 同集群归一化、跨集群拒绝和历史字段兼容。
- Worker Dispatch 身份、运行恢复、资源修订和工作流集成。
- 队列取消、Dispatch 领取竞争、运行/文件项删除和终态指标归零。
- 文件浏览、下载、mkdir、rename、move、delete、递归确认、路径穿越和目标冲突。
- ACL 默认权限、路径作用域、覆盖、拒绝、创建者/管理员绕过和项目成员边界。
- MySQL/SQLite 表结构、在线升级字段和实体映射。
- 操作成功/失败审计；运行时异常不会回滚失败审计。
- SSE 事件隔离和客户端正常断连；普通 REST I/O 异常仍返回统一 500。

本次没有重跑 Studio 仓库全部非文件传输测试，因此不把该结果表述为整个 Studio 仓库全量测试绿色。

### 2.3 Web 类型检查与构建

执行：

```powershell
npm run build:web
```

结果：`vue-tsc --noEmit` 和 Vite 生产构建通过，4324 个模块完成转换。仅输出仓库既有的 npm `electron_mirror` 配置弃用警告。

### 2.4 2026-08-11 缺陷修复定向回归

本轮使用 JDK 17 执行以下 5 个测试类，共 32 项测试，0 失败、0 错误：

| 测试类 | 用例数 | 重点 |
|---|---:|---|
| `AliyunOssHelperTest` | 2 | OSS 目录 marker、截断分页和真实子项 |
| `RuntimeDatasourceProbeExecutorFileOperationTest` | 9 | 非空目录失败关闭和递归确认 |
| `FileTransferNodeExecutorRetryTest` | 9 | 终态保留监听器已保存峰值 |
| `StudioTransferEventListenerTest` | 3 | 速率采样、峰值和终态归零 |
| `GlobalExceptionHandlerTest` | 9 | SSE 断连/超时不写 JSON，普通异步超时保持错误响应 |

前端 `test:file-transfer-queue` 回归通过；`vue-tsc --noEmit` 和 Vite 生产构建通过，4325 个模块完成转换。新增断言覆盖内部数据源下拉事件绑定、可清除操作、防迟到响应和无列表轮询。

### 2.5 2026-08-11 4 GiB 并发调度定向回归

为支持同一个 Worker 上多个长文件运行同时推进，文件传输 dispatch 改由独立固定线程池执行，并使用信号量限制并发槽位；采集、质量等非文件任务保持原执行路径。JDK 17 定向测试结果：

| 测试范围 | 用例数 | 结果 |
|---|---:|---|
| `WorkerFileTransferDispatchTest` | 2 | 通过 |
| `WorkerLifecycleRunnerClusterTest` | 15 | 通过 |
| `WorkerResourceRevisionGuardTest` | 4 | 通过 |
| `FileTransferNodeExecutorRetryTest` | 9 | 通过 |
| `StudioTransferEventListenerTest` | 3 | 通过 |

合计 33 项，0 失败、0 错误。目录展开用例的 Mock 文件系统已补齐 `StorageCapabilities`，测试夹具与当前 `TransferFileSystem` 契约一致。

### 2.6 2026-08-12 MySQL Outbox 定向回归

已使用 JDK 17 完成以下验证：

| 测试范围 | 用例数 | 结果与覆盖 |
|---|---:|---|
| `FileTransferEventServiceOutboxTest` | 13 | 双 Server 独立游标、`Last-Event-ID` 补发、过期快照、发送失败重连、删除事件、200 项拆分、禁用旧扫描、真实积压行数、空轮询只读热路径 |
| `FileTransferEventServiceTest` | 2 | 旧扫描回滚模式兼容 |
| `FileTransferOutboxCleanupTest` | 4 | 终态旧事件、活跃/失联游标、游标边界和批次限制 |
| `FileTransferOutboxWriterTest` | 4 | payload 脱敏、1 秒限频、强制事件和限频键回收 |
| `FileTransferStateMutationServiceTest` | 3 | Outbox 失败传播、业务更新 0 行不写指标/事件、字段级更新 |
| `FileTransferStateMutationTransactionTest` | 4 | Outbox 插入失败时业务状态回滚，成功时状态、文件项和事件同时提交 |
| `FileTransferSchemaIntegrationTest` | 5 | MySQL/SQLite/迁移文本一致，SQLite 新库、半成品旧库和 fail-closed 重复升级 |
| `FileTransferSingleClusterTest` | 8 | 单集群归一化、跨集群拒绝和历史记录保护 |
| `FileTransferNodeExecutorRetryTest` | 9 | Worker 终态、重试和峰值回归 |
| `StudioTransferEventListenerTest` | 5 | 进度、指标、限频和终态事件入口 |
| `WorkerLifecycleRunnerClusterTest` | 21 | Worker 接管、重启恢复、身份异常和终态事件 |
| `StudioServerHaHealthIndicatorTest` | 2 | Outbox 表、索引、游标和健康降级 |
| `FileTransferOutboxServerConfigurationTest` | 1 | 两线程专用调度器和取消任务回收 |
| `ExecutionEventServiceRegressionTest` | 9 | Dispatch 失败终态通过 Outbox 门面写入 |
| `FileTransferPublicRouteContractTest` | 4 | `Last-Event-ID` 透传及 SSE 禁缓存/禁代理缓冲响应头 |

最终核心命令覆盖其中 14 个 Outbox/Worker/HA 测试类、92 项测试，均为 0 失败、0 错误；旧扫描兼容测试在此前定向回归中通过。前端队列回归、`vue-tsc --noEmit`、4331 模块生产构建、真实 MySQL 和服务验收均已完成；未完成的 4 GiB 业务扩展矩阵仍保持“进行中”，没有被记为绿色。

2026-08-13 补充契约回归：`FileTransferPublicRouteContractTest` 在 JDK 17 下 4 项通过，验证没有 `Last-Event-ID` 时传入 `null`，以及 HTTP Header `Last-Event-ID=99` 原样传给 `FileTransferEventService.connect("99")`。

2026-08-13 完成审计再次执行第 2 节的宽范围命令，共生成 31 个测试报告、164 项测试，结果为 0 失败、0 错误、0 跳过。首次执行暴露 `FileTransferPreviewExecutorTest` 的 Mockito `TransferFileSystem` 没有返回 SPI 必备的 `StorageCapabilities`；该问题属于旧测试桩契约遗漏，真实 Local/FTP/SFTP/OSS 实现均已声明能力。测试桩补充大小写敏感和 SHA-256 能力后，定向测试及宽范围套件全部通过，生产代码、接口、数据库和运行服务均未改变。前端 `test:file-transfer-queue`、`vue-tsc --noEmit` 和 4331 模块生产构建同期再次通过。

## 3. 真实 OSS 与 FTP 验收

### 3.1 单源文件操作

OSS 和 FTP 均通过以下真实操作：

- 浏览根目录和子目录。
- 新建目录。
- 文件与目录重命名。
- 同一数据源内移动。
- 文件直接下载。
- 文件删除。
- 非空目录递归删除及二次确认。
- 已存在目标拒绝覆盖。
- `..`、NUL、根目录删除等非法路径拒绝。

验收产生的临时 OSS/FTP 目录和本地下载文件均已清理。FTP 根目录清理后重新读取为空，OSS 验收前缀已递归删除。

2026-08-11 重点复验确认：

- OSS 目录 marker 与真实子对象同时存在时，`recursiveConfirmed=false` 返回明确业务错误，随后浏览仍能看到子对象；`recursiveConfirmed=true` 删除前缀成功。
- FTP 非空目录未确认递归同样被拒绝，确认后删除成功。
- OSS/FTP 均完成非空目录重命名、文件重命名和同源移动；移动后源目录为空，目标目录存在 3,801 Byte 样本文件。
- 同一 `.efile` 经 OSS 和 FTP 下载后均为 1,444,025 Byte，SHA-256 均为 `2b9a41fa544806d0d176211eea5b29f57f9b2763c78c8446577f7a94f10bca13`。

### 3.2 同集群双向传输

真实 OSS → FTP 和 FTP → OSS 均成功，源与目标由 `DEFAULT-LOCAL` 的同一个 Worker 访问。最终 FTP → OSS 证据：

| 项目 | 结果 |
|---|---|
| 运行 ID | `2086332888130015233` |
| 运行记录 ID | `2086332899169460226` |
| 状态 | `SUCCESS` |
| 尝试次数 | `1` |
| 文件大小 | `18,362,156 Byte` |
| SHA-256 | `f7fd28cee793ce8340439a028d0d12c409186d7255ef902b3d314f9664b9d373` |
| 源端动作 | `KEEP`，FTP 源文件在传输完成后仍存在 |

单文件请求使用完整目标文件路径，未再出现源路径重复拼接的问题。真实传输日志中没有 Relay Controller、Client、Ticket 或 Server Relay 调用。

2026-08-11 使用 18,362,156 Byte 样本再次完成 OSS→FTP 和 FTP→OSS，两个方向均展开为单个叶子文件项，源/目标 SHA-256 均为 `f7fd28cee793ce8340439a028d0d12c409186d7255ef902b3d314f9664b9d373`。重复传输同一内容时运行成功且文件项为 `SKIPPED`、传输字节为零；使用不同内容指向已存在目标时运行失败且文件项为 `CONFLICT`，原目标未被覆盖。

### 3.3 SFTP SSH 算法兼容

- Worker 运行时 `plugin/source/sftp/libs` 已确认只加载 `jsch-0.2.23.jar`，历史残留的 `jsch-0.1.51.jar` 已移除。
- `allowLegacyAlgorithms` 保持默认 `false` 时，对已保存 SFTP 测试数据源执行 Worker 内部探测，返回 `AVAILABLE`、`Connection success`，耗时约 1.1 秒；未为该数据源启用 SHA-1、`ssh-rsa`/`ssh-dss`、CBC 或 HMAC-MD5 兼容算法。
- 2026-08-11 修复 `RuntimeDatasourceProbeRouter` 到 Worker 的内部请求头缺失问题：普通 POST 和流式下载均固定发送由 endpoint 绑定的 `X-Studio-Target-Cluster-Id`，且配置头不能覆盖该值。Studio Server 的 `POST /api/v1/datasources/{id}/test?runtimeClusterId={clusterId}` 现返回 `AVAILABLE`、`Connection success`。
- 2026-08-11 通过 Studio 公共接口浏览 SFTP 服务端真实根 `/` 成功，返回 `/upload` 及服务端根下的文件；进入 `/upload` 后可见该用户已有目录。SFTP 连接后的 `pwd()` 为 `/upload`，页面首次选择数据源显示 `/upload`，但 `/` 仍可通过面包屑或路径输入进入，不作为访问边界。Server→Worker→SFTP 链路正常；此前失败属于路由身份头缺失，不是 SSH 算法协商失败。

### 3.4 4 GiB UI 扩展验收（进行中）

- 管理员已通过界面创建独立项目成员，并为 OSS、FTP、SFTP 三个测试源配置访问路径权限；测试用户从统一 `8000` 入口登录后完成后续页面操作。
- SFTP 页面首次定位为连接后的 `pwd()`：`/upload`；页面可见 4 GiB 源文件，绝对路径和服务端真实根 `/` 的原有插件语义未改变。
- OSS、FTP、SFTP 已分别创建隔离测试目录；SFTP 小目录已通过新建、重命名、同源移动和删除操作。
- SFTP→OSS 与 SFTP→FTP 均通过页面完成一次暂停和恢复，状态经历“传输中→已暂停→传输中”；两个文件项都展开为 4 GiB 叶子文件，且仍处于第一次传输尝试，没有触发失败重试。
- 2026-08-11 23:54，SFTP→OSS 已传输约 216 MiB，SFTP→FTP 已传输 128 MiB；两条运行仍为 `RUNNING`，页面与只读运行接口字节计数一致，同一 Worker 正在并发推进。
- 2026-08-12 00:11，FTP 根目录的 4 GiB `test-4g.bin` 已通过页面入队至 OSS 隔离目录，并完成一次暂停/恢复；当前仍为第一次传输尝试，接口已确认约 24 MiB，状态仍为 `RUNNING`。此时三条长传输均在同一 Worker 上并发推进。
- 2026-08-12 00:19 页面复核：SFTP→OSS 为 10% / 106 KiB/s，SFTP→FTP 为 8% / 115 KiB/s，FTP→OSS 为 2% / 114 KiB/s；三条均为“传输中”，没有假完成或停滞。
- 2026-08-12 Worker 重启恢复复验：一条已保留约 3.23 GiB 断点的 4 GiB FTP→OSS 运行连续重启 Worker 两次。每次均先关闭旧 Dispatch，再自动创建并领取新 Dispatch；Run 保持原身份，checkpoint、目标 `.part` 和文件项不被删除或重建。第二次启动后只读接口立即显示已传输和已恢复字节均为 `3,472,883,712`、续传文件数为 `1`，没有回到零；两个用户主动暂停的 SFTP 文件项仍为 `PAUSED`，未产生自动执行。
- 恢复阶段线程栈确认处于 FTP 源前缀读取和 SHA-256 摘要重建，不是死锁。当前实现不会把已确认前缀重新写入目标，但为了最终整文件 SHA-256，在未持久化摘要内部状态时会重新读取源端前缀；页面运行消息已明确标识恢复校验阶段，历史断点字节不计入当前速度。`TransferEngineLocalIntegrationTest` 11 项以及 `StudioTransferEventListenerTest`、`WorkerLifecycleRunnerClusterTest`、`FileTransferStateMutationTransactionTest` 共 23 项通过，0 失败、0 错误。
- 尚未完成：两条 SFTP 出方向的最终提交和 SHA-256；FTP→OSS、OSS→SFTP 及各自一次断点恢复；三个数据源完整 4 GiB 文件的下载、重命名或移动、删除；精确文件 ACL、重复传输 `SKIPPED`、不同内容冲突 `CONFLICT`、终态队列/指标复核。未完成项不得记为通过。

### 3.6 schema v2 快速断点恢复（自动化已完成，真实 4 GiB 待复验）

- 2026-08-13 实现 schema v2 checkpoint：每个确认块保存 `confirmedOffset`、Bouncy Castle SHA-256 可恢复编码状态、状态格式标识，以及文件头/确认边界各最多 64 KiB 的 SHA-256 指纹。数据库继续使用原 `checkpoint_json`，没有新增表、字段、索引或 SQL。
- 默认 `FAST` 恢复只读取头部与边界小范围并直接续传；旧 schema v1、摘要状态损坏、边界指纹或临时目标长度不匹配时，清理旧临时目标和 checkpoint 后从零传输。显式 `STRICT` 保留旧前缀摘要重建兼容能力。
- 终态 SHA-256 不一致时立即清理无效断点，并自动且仅自动执行一次从零重传；第二次仍不一致返回失败，避免无界重试。
- 页面和 SSE 增加可选 `resumePhase`、`resumeCheckedBytes`、`resumeTotalBytes`。严格旧断点校验显示“恢复校验/校验速度”，不再把源端校验读取速度误标为目标传输速度；没有增加轮询。
- Java 17 定向结果：`TransferEngineLocalIntegrationTest` 18 项、`FileTransferCliTest` 3 项、`FileTransferEventServiceOutboxTest` 16 项、`StudioTransferEventListenerTest` 9 项通过，0 失败、0 错误。真实 4 GiB SFTP/FTP/OSS Worker 重启恢复仍需在发布新 Worker 后复验，不能以自动化结果替代真实验收。

### 3.5 MySQL Outbox 与多 Server 验收（已完成）

- 真实运行服务：Server `18080`、Worker `18081` 均返回健康 `UP`；Web 统一入口 `http://127.0.0.1:8000/dfs/data-aggregation-studio/` 返回 HTTP `200`，没有启动或使用 `5173`。
- Server HA 健康详情真实返回 `fileTransferEventMode=OUTBOX`、`fileTransferOutbox=available`、`fileTransferOutboxIndexes=7`、实例 ID `studio-server-jdragon`、最大事件 ID 与实例游标一致、`fileTransferOutboxCursorLag=0`，并提供最近消费/清理时间。
- 真实 MySQL 结构检查确认 `file_transfer_event_outbox` 的四个查询索引和 `file_transfer_event_consumer_cursor` 的唯一/两个查询索引均存在；启动在线升级已完成，未新增 Redis、Kafka、RabbitMQ 等中间件。
- 统一入口首次 SSE 收到带 ID 的 `SNAPSHOT_REQUIRED`；携带 `Last-Event-ID=2087614951562043393` 重连后补发 `2087614951562043394` 的业务事件，没有退化为快照。前端按雪花 ID 字符串去重，未增加列表轮询。
- 临时第二 Server 使用不同 `instanceId` 连接同一 MySQL；A/B 两端从同一断点补发相同 `RUN_CHANGED` 事件 `2087612919757312002`，各自游标独立推进。Server B 已停止并清理临时运行目录。
- 4 GiB FTP→OSS 运行 Outbox 事件统计为 `ITEM_CHANGED=18`、`RUN_CHANGED=40`、总计 58 条，事件量受进度限频控制。
- 延迟根因对照：直连 `18080` 与统一 `8000` 入口的原始总耗时均约 2 秒，排除 Nginx 为主因。线程转储确认唯一默认调度线程阻塞在空轮询游标更新和 `count(*)` 积压统计；修复后使用两个专用 Outbox 调度线程，空轮询只做一次索引查询，游标保活/精确积压移到 15 秒心跳。
- 统一入口 12 样本均已清理：创建事务 P95 `2080.2 ms`，创建响应返回到 SSE P95 `440 ms`，Outbox `occurredAt` 到 SSE 到达的端到端 P95 `949 ms`，满足 W7 `P95 <= 1 s`。业务创建接口耗时与事件总线耗时分开统计，不将前者归因于 SSE。
- 最终双 Server 复验：主 Server 与 `instanceId=studio-server-ha-2` 的临时 Server 同时收到同一个 `run-created` 且 SSE ID 相同；临时 Server 已关闭。断线期间创建事件后，携带旧 `Last-Event-ID` 重连可真实补发，ID 单调递增，临时运行均已删除。
- UI 证据：统一入口真实浏览器显示文件传输中心，运行集群/左右数据源无默认选择、未加载目录、队列为“传输中/已完成”两个 Tab，控制台没有 `error/warn`。
- W7 结论：Outbox 代码、迁移、健康、P95、重连、双 Server、响应头和事件量验收通过；生产负载和滚动发布仍列为部署扩展项，不影响 W7 完成。

## 4. ACL、队列与指标验收

- 真实目录 ACL 保存后按“目录权限（作用于后代）”重新加载成功。
- 真实文件 ACL 保存后按“文件权限（仅当前文件）”重新加载成功。
- 临时 ACL 规则在验收后已删除。
- MySQL 在线升级后的 `unstructured_path_acl.directory` 字段工作正常。
- 文件操作成功和 Worker 业务失败均写 `unstructured_op_audit`；失败审计使用不回滚事务语义。2026-08-11 起 MySQL `unstructured_op_audit.message` 为 `TEXT`，应用侧审计摘要上限为 1,800 字符，审计写入异常不会覆盖文件操作原始结果。
- 三个诊断产生的取消队列项通过页面手动移除，不需要轮询或页面持续刷新。
- 历史运行保留为 `已取消`，队列项清理不物理删除运行、指标或审计证据。
- 终态运行不再贡献陈旧的活动文件数和当前速度；实时指标为活动文件 `0`、当前速度 `0 B/s`。
- OSS/FTP 来源和目标 TopN 显示数据源名称，`count` 按成功文件数汇总（包含摘要一致跳过），不再误用传输字节数，也不显示资源 ID。
- 2026-08-09 口径修复后页面实测：当前运行汇总为成功文件 `3`、传输量 `2.54 MiB`，来源 `aliyun oss` TopN 为 `3`，目标 `ftp测试源` TopN 为 `3`；页面控制台无错误。
- 2026-08-11 专用指标窗口实测：运行数 `6`、成功运行 `5`、失败运行 `1`，峰值 `1,642,570 B/s`，终态当前速度和活动文件均为零；来源/目标 TopN 返回 `aliyun oss`、`ftp测试源` 名称，`count` 与成功/跳过文件数一致，不是字节数。

## 5. SSE 与前端交互验收

- 传输中心首次进入时运行集群、源数据源和目标数据源均未默认选中。
- 选择运行集群后只加载数据源选项；选择具体数据源后才加载目录。
- 队列和运行记录只显示 `[数据源名称] 路径`，不显示方向列、集群名称或集群 ID。
- 运行名称使用 `MM-dd HH:mm · 触发类型`，例如 `08-09 14:04 · 即时`。
- 队列只使用 `GET /api/v1/file-transfer/runs/events` SSE 更新；代码中没有列表 `setInterval` 或轮询定时器，唯一 `setTimeout` 用于封顶退避重连。
- Server 重启后进入传输中心建立 SSE，离开页面并跨过 15 秒心跳周期，日志没有新增 `AsyncRequestNotUsableException`、`HttpMessageNotWritableException` 或断连 `ERROR`。
- 2026-08-11 浏览器实测内部数据源下拉从 OSS 切换到 FTP 后，父页面选择、根目录和有效权限同步更新；清除选择后目录立即清空、操作禁用，控制台无 `warn/error`。
- SSE 实测收到 `SNAPSHOT_REQUIRED`、`RUN_CHANGED`（含 `QUEUED`、`RUNNING`、文件发现和传输进度）以及 heartbeat；客户端超时退出后，Server 不再把 `AsyncRequestTimeoutException` 写成 JSON event-stream 错误。
- 传输中心选择集群后显示 `传输中 (0)` 和本轮终态文件项所在的“已完成”Tab；运行记录显示简短触发时间片段、数据源名称和非零峰值，未显示方向列或集群名称。
- 指标页选择运行集群后，数据源下拉显示 `aliyun oss` 和 `ftp测试源`；TopN 表头为“文件”，页面控制台无新增警告或错误。

当前浏览器 `1280x720` 复核：文档宽度 `1265`，无页面横向溢出，传输中心和非结构化管理无操作控件重叠。既有 `390x844` 验收确认双栏上下堆叠、页签和操作按钮可见、长文件名不撑破容器；本次最终改动没有修改前端布局。

## 6. 数据库、接口与安全边界

- MySQL 全量结构、SQLite 全量结构、启动幂等升级和 `20260807`、`20260809` 增量脚本保持一致。
- `datasource_definition.created_by`、规范 `runtime_cluster_id`、源级 ACL、路径级 ACL 和操作审计表均已交付。
- 公共接口统一使用 `/api/v1` 和 `Result<T>`；下载接口按二进制流返回。
- Server 不加载 DataAggregation 文件插件，不连接业务文件源，不读取或落盘传输内容。
- 数据源凭据只在 Worker 安全上下文使用，不进入 SSE、运行快照、错误消息或验收文档。
- 后端和前端运行代码扫描无 Relay Bean、Controller、Client、Ticket、Router 或配置符号；文档中的 Relay 仅作为历史迁移说明。
- 新增 `file_transfer_event_outbox`、`file_transfer_event_consumer_cursor` 与七个索引；业务状态和事件同一短事务提交。Outbox 只保存引用和少量脱敏补充信息，不保存文件内容、数据源凭据、Token、Worker 地址或完整运行视图。
- SSE 正常模式从 Outbox 消费，不再扫描运行/文件项状态表；`LEGACY_SCAN` 只作为迁移和回滚模式。事件 ID 为字符串形式雪花 ID，支持浏览器 `Last-Event-ID` 断点补发。

## 7. 服务重启与健康检查

- Studio Server 使用项目脚本完成结构升级、离线构建和重启，`/actuator/health` 返回 `UP`。
- Studio Worker 绑定 `DEFAULT-LOCAL`，`/actuator/health` 返回 `UP`。
- Web 统一入口 `http://127.0.0.1:8000/dfs/data-aggregation-studio/` 返回 HTTP `200`，文件传输和非结构化管理路由可正常加载；本轮未启动或使用 `5173`。
- 2026-08-11 最终状态：Server 为本轮 02:30 后启动的新进程，Server/Worker 健康均为 `UP`，Web 返回 HTTP `200`，唯一运行集群在线 Worker 数为 `1`；启动后无新增 `ERROR` 或 Relay 调用。
- 2026-08-11 为修复 SFTP 长异常消息造成的审计截断，新增 `20260811-unstructured-op-audit-message.sql`，并由启动升级将 MySQL `unstructured_op_audit.message` 改为 `TEXT`；无新增表、索引、公共接口路径或响应结构变化。Server 已重启并执行升级，Worker 与前端无需因本次修复重启。
- 2026-08-11 SFTP 路径语义复验：`SftpHelper` 恢复绝对路径原样传递，数据采集旧接口不再受“登录目录逻辑根”改造影响；`TransferFileSystem.initialPath()` 仅用于页面首次定位。通过 Studio 公共接口在 `/upload` 下创建目录成功，实际调用为 `/upload/studio-sftp-pwd-check-20260811-1709`；随后浏览 `/upload` 未再发现该目录及此前同类临时目录，清理状态已确认。该验证不改变 SFTP 的真实根 `/`，也不代表已完成 SFTP 下载或双向传输矩阵。

## 8. 残余风险与后续环境验收

### 8.0 2026-08-14 非结构化上传与 ZIP 下载自动化

- 后端定向范围：`RuntimeDatasourceProbeExecutorFileOperationTest`、`RuntimeEndpointHttpClientTest`、`UnstructuredManagementOperationAuditTest`、`UnstructuredManagementControllerContractTest`、`InternalDatasourceProbeControllerTest`。
- 覆盖内容：原始固定长度上传、目标冲突/覆盖、失败 abort、Worker 内部 Token 与运行身份、公共接口 411/409 契约、目录递归分页、空目录、父子选择去重、同名文件路径保留、单文件下载兼容和 `UPLOAD`/`DOWNLOAD_ARCHIVE` 审计。
- 前端范围：SDK 的 `stat/upload/downloadArchive` 类型与 Blob 错误解析，页面单文件选择、上传进度、覆盖确认、409 竞态重试、单文件原下载、目录/多选 ZIP 下载以及传输期间防重复提交。
- 验证结果：Java 17 下上述五个定向测试类共 40 项通过，0 失败、0 错误；`npm run build:web` 完成 `vue-tsc --noEmit` 和 Vite 生产构建；`git diff --check` 通过。统一入口页面在 `1280x720` 和 `390x844` 下无横向溢出或操作按钮重叠，浏览器控制台无 warning/error。
- 数据库与边界：没有新增表、字段、索引、SQL 或 Liquibase；文件内容只由运行集群 Worker 访问，Server 不落上传文件或 ZIP 临时文件。

### 8.0.1 2026-08-14 浏览器原生下载优化

- 控制面：新增 `POST /api/v1/unstructured-management/download-tickets`。签发前规范化和去重路径，并校验用户、租户、项目、集群、数据源、状态和 `DOWNLOAD` ACL；单个普通文件为文件模式，一个目录或多个选择项为归档模式。
- 票据安全：票据使用 32 字节 `SecureRandom` 和 Base64 URL 无填充编码，Redis 键只包含原始票据的 SHA-256；默认有效期 120 秒，原子 `GETDEL` 保证并发请求只有一个能消费。格式错误返回 `400`，不存在、过期和重放统一返回 `401`，Redis 创建或消费故障返回 `503` 且不回退到本机内存。
- 数据面：新增唯一匿名路径 `GET /api/v1/unstructured-management/download/native`。消费票据后使用票据中的用户、租户和项目重建执行上下文并重新准备下载，能够发现签发后的 ACL 撤销、文件删除或文件/目录类型变化。异步流只使用已准备的不可变上下文，不读取请求线程 ThreadLocal。
- 响应契约：单文件返回 `application/octet-stream`、UTF-8 文件名和准确 `Content-Length`；ZIP 返回流式 `application/zip` 且不设置总长度。两者都带 `Cache-Control: no-store`、`Referrer-Policy: no-referrer`、`X-Content-Type-Options: nosniff` 和 `X-Accel-Buffering: no`；不声明 Range，也不支持断点续传或票据复用。
- 前端：页面用 Axios 创建票据后立即结束按钮 loading，再通过临时 `<a>` 发起浏览器 GET。页面不再调用 `requestBlob()`、`URL.createObjectURL()` 或 `saveBlob()`；普通文件由浏览器显示确定进度，实时 ZIP 只能显示已接收大小或不确定进度。旧 Blob SDK 方法继续保留，旧单文件方法 timeout 为 `0`。
- 超时：Server/Worker 的路径级拦截器只对旧单文件、旧归档、新原生下载及内部文件/归档流关闭 Servlet 异步总时限；其他异步 API 继续使用全局 5 分钟。Server 到 Worker 下载读取空闲超时统一为 30 分钟，表示相邻响应字节之间连续无数据的上限，不是下载总时长。
- 代理：WSL `nginx-local` 的 `/opt/nginx/nginx.conf` 已增加精确原生下载 location，保持 `proxy_pass http://host.docker.internal:18080`，关闭响应缓冲/缓存，配置 `proxy_read_timeout 30m` 和 `send_timeout 30m`。专用 access log 不包含 `$args`、`$request_uri` 或完整 `$request`。配置备份为 `/opt/nginx/nginx.conf.bak-20260814-native-download`，`nginx -t`、reload 和 `nginx -T` 均成功。
- 自动化结果：Java 17 下最终定向套件共 57 项通过，0 失败、0 错误，其中 Infra 33 项、Server 16 项、Worker 8 项。除票据、审计和 Controller 契约外，还覆盖 `RuntimeEndpointHttpClient` 成功响应直接流转、30 分钟配置作为逐次读取空闲超时、1 MiB 错误体上限，精确原生 GET 匿名放行且旧下载/票据 POST/相邻路径仍需认证，以及 Worker 内部下载协议和既有上传、ZIP 行为。前端文件传输回归脚本和 `npm run build:web` 通过，后者包含 `vue-tsc --noEmit` 和 Vite 生产构建（4331 个模块）。
- 尚未执行：当前 Java Server/Worker 尚未用本次代码重新构建并重启，因此还没有通过统一入口完成真实浏览器原生下载、慢速超过 5 分钟、持续输出超过 30 分钟或连续空闲 30 分钟的端到端验证；这些项目不能标记为已通过。

### 8.1 2026-08-13 低速实时进度与 Worker 重启恢复回归

- 核心回归：`TransferEngineLocalIntegrationTest` 12 项通过，覆盖单个慢速块在 checkpoint 保存前产生实时观测；实时事件的 `transferredBytes` 保持确认偏移，`observedBytes` 在块内递增，checkpoint 只在完整块写入后保存。
- Worker 回归：`WorkerLifecycleRunnerClusterTest` 与 `StudioTransferEventListenerTest` 共 29 项通过，覆盖失活 Dispatch 围栏、在线 Worker 不抢占、同实例旧 boot 恢复、CAS 幂等、文件项/运行速度更新及确认累计字节不前移。
- Outbox/SSE 回归：`FileTransferEventServiceOutboxTest`、`FileTransferOutboxWriterTest`、`FileTransferStateMutationServiceTest` 共 22 项通过，覆盖实时 payload 叠加、终态拒绝迟到实时事件、进度限频和状态/事件事务边界。
- 前端回归：`npm run test:file-transfer-queue -w @studio/web`、`npx vue-tsc --noEmit -p apps/web/tsconfig.json` 和 `npm run build:web` 通过。页面通过 SSE 使用可选 `observedBytes/live`，没有新增 `setInterval` 或列表轮询。
- 自动恢复预期：正常关闭时旧 Worker Lease 立即离线；异常退出时等待 30 秒心跳窗口失效。随后新 Worker 先 CAS 围栏旧 Dispatch，再将运行重排队，并从既有 `confirmedOffset` 恢复；仍在线的其他 Worker 或 CAS 竞争失败时不抢占。
- 数据边界：`observedBytes` 仅用于在当前进程存活期间显示块内进度和计算当前/峰值速度，不是可恢复断点。刷新后若尚无新的实时事件，页面回到最近确认字节；Worker 恢复后从确认字节重新产生实时观测。
- 本轮无数据库表、字段、索引或升级 SQL变化；文件项响应只新增可选展示字段。发布需要重启 Worker、Server 并重新发布 Web。
- 待真实环境：使用统一入口启动一个 4 GiB 低速运行，观察至少两个连续实时样本；活动中重启 Worker，确认 Run 经 `RUNNING -> QUEUED -> RUNNING` 恢复、确认字节继续增长且最终 SHA-256 一致。取得该证据前，本项环境状态保持“待复验”。
- 2026-08-13 低速恢复自动化补充：`activityBytes` 只作为当前速度和活动观测累计值，覆盖断点前缀校验读取和恢复后的块内读取；`transferredBytes` 与 checkpoint 仍是目标端整块确认字节。断点校验期间取消会保持 `CANCELED`，不会被包装异常误记为 `FAILED`；核心新增断点恢复活动、累计速度、冲突目标恢复和取消回归后，`TransferEngineLocalIntegrationTest` 最终共 15 项通过。
- 2026-08-13 本轮静态/构建复验：`FileTransferRunRemovalTest` 12 项、`FileTransferNodeExecutorRetryTest` 9 项、`StudioTransferEventListenerTest` 8 项、`WorkerLifecycleRunnerClusterTest` 23 项，合计 52 项通过，0 失败、0 错误；其中覆盖并发恢复先于暂停归一化时不再修改文件项。Web 队列回归通过，`vue-tsc --noEmit -p apps/web/tsconfig.json` 通过，生产构建完成 4331 个模块；`git diff --check` 通过。Server `18080`、Worker `18081` 健康状态均为 `UP`，统一入口 `http://127.0.0.1:8000/dfs/data-aggregation-studio/` 返回 `200`，`5173` 未监听。本轮无数据库更新。
- 2026-08-13 暂停任务重启复验：Run `2087201024718897154` 的真实确认进度保持 `3.23 GiB / 4 GiB`。修复加载前 Run 已暂停但文件项历史残留为 `传输中`、`348 KiB/s`；Worker 协调归一化后，统一入口页面显示文件项 `已暂停`、`0 B/s`，恢复按钮可用。没有点击恢复、取消或删除，没有创建恢复 Dispatch，也没有修改 checkpoint、确认字节或目标 `.part` 文件。该结果证明暂停任务不会被 Worker 重启擅自恢复，同时不会丢失后续断点续传条件。
- 2026-08-13 schema v2 快速恢复自动化复验：核心 18 项、CLI 3 项、Studio Worker/Outbox/事务定向测试 61 项通过，均为 0 失败、0 错误；Web 队列回归、类型检查和生产构建通过，`git diff --check` 通过。默认 `FAST` 只对包含可恢复 SHA-256 状态且通过头部/确认边界轻量指纹校验的 v2 checkpoint 直接续传；旧 checkpoint 或不可信 checkpoint 清理临时目标后从零重传，显式 `STRICT` 保留旧 checkpoint 全前缀摘要重建能力。最终摘要不一致会清理无效断点并且只自动从零重传一次，避免坏断点或坏数据无限重试。本轮未重启服务，也未触碰正在保留的 4 GiB 历史任务；真实 4 GiB 快速恢复必须等新 Worker 发布并产生 v2 checkpoint 后复验，因此环境状态仍为“待复验”。

### 8.2 2026-08-13 schema v2 真实 4 GiB 快速恢复复验

- 场景：从 SFTP `/upload/test-4g.bin` 向 FTP 隔离目录传输 4 GiB 文件；全程使用 `http://127.0.0.1:8000/dfs/data-aggregation-studio/` 页面观察，不使用 `5173`，不操作此前保留的历史暂停任务。
- 第一次接管：Worker 重启后新 Dispatch 实际开始执行，但因目标 `.part` 长度与 checkpoint 不完全相等，消息显示 `temporary target offset did not match checkpoint; transfer restarted`，发生保护性从零重传。新 Worker 随后成功写出 schema v2 checkpoint。
- 第二次接管：重启前 checkpoint 确认偏移为 `620,756,992` Byte，并包含可恢复 SHA-256 状态、头部指纹和确认边界指纹；重启后仍从零开始。由此确认真实缺陷不是 checkpoint 缺失，而是异常退出常见的“物理 `.part` 含未确认块内尾部”被严格长度判断误判。
- 修复：在文件传输专用 `TransferFileSystem` 增加写会话恢复对齐能力。Local/SFTP 截断到确认偏移，FTP 从确认偏移覆盖未确认尾部；目标短于 checkpoint、超过源大小或适配器无法安全对齐时仍从零重传。OSS/MinIO 保持安全回退；结构化采集使用的原 `FileHelper` 接口与语义未修改。
- 自动化证据：`TransferEngineLocalIntegrationTest` 增至 19 项并全部通过，新用例验证 `.part` 多出未确认尾部时只丢弃尾部、`resumedBytes` 等于确认偏移且最终内容正确；FTP/SFTP 适配器及依赖模块打包成功。`git diff --check` 通过，无数据库结构或升级 SQL 变化。
- 修复后第三次接管：重启前 checkpoint 确认偏移为 `889,192,448` Byte；新 Worker 接管后消息为 `checkpoint restored; transfer resumes from confirmed offset`，恢复字节为 `905,969,664`，确认进度继续增长到至少 `1,015,021,568` Byte，未回零、未进入 `RESTARTED_FROM_ZERO`。
- 页面连续取样：首次约 `1.20 GiB / 4 GiB`、`30%`、`41.9 KiB/s`；30 秒后约 `1.23 GiB / 4 GiB`、`31%`、`312 KiB/s`。两次均为“传输中”，并显示 checkpoint 恢复消息，证明实际传输与 SSE 页面更新均在继续。
- 当前结论：schema v2 快速恢复关键路径通过；Server/Worker 健康为 `UP`，本地集群在线 Worker 数为 `1`。当前 4 GiB 文件尚未完成，不能把终态验收记为通过；待完成后继续核对源/目标 SHA-256、运行和文件项 `SUCCESS`、当前速度与活动文件归零、峰值保留、checkpoint 清空、`.part` 消失、恢复计数以及自动转入“已完成”Tab。

以下项目未在本地真实环境完成，不应被误写为已通过：

1. 至少 10 GiB 单文件的内存、带宽、超时和断点恢复压测。
2. SFTP、MinIO 的真实服务兼容矩阵和故障注入。SFTP 已完成现代算法真实连接、公共连接测试、真实根 `/` 浏览、`/upload` 下目录操作，以及两个 4 GiB SFTP 出方向运行的暂停/恢复；页面首次定位使用连接后的 `pwd()`，绝对路径仍按旧插件语义访问服务端真实根。4 GiB 四方向终态、三源下载/修改/删除和旧算法服务端兼容矩阵仍未完成。
3. 10 万小文件的发现、数据库写入和指标采样压测。
4. 生产反向代理的 TLS、SSE/下载流、负载均衡空闲超时和滚动发布验证。
5. 启动输出中的 `spring-jcl`/`commons-logging.jar` 类路径提示需按依赖树单独治理；该提示不是文件传输异常，未影响本次健康检查或验收结果。

这些项目是生产规模与环境兼容验收，不扩大当前单集群 OSS/FTP 功能边界。任何后续接口、表结构、状态机或 Worker-only 安全边界调整，都必须同步更新设计基线和变更跟踪文档。
