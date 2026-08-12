# Studio 非结构化文件传输验收记录（2026-08-07，2026-08-11 修订）

> 结论：三阶段文件传输及 2026-08-09 单集群、单 Worker、非结构化管理、ACL、队列删除和 SSE 改造已完成；2026-08-11 的 OSS 非空目录保护、终态峰值保留、管理页数据源下拉和 SSE 超时断连缺陷均已修复。原 OSS/FTP 小样本验收已经通过；用户新增的 SFTP 4 GiB 四方向与三源文件管理扩展验收正在执行，尚未形成最终通过结论。
>
> 当前版本不提供跨集群文件传输或 Server Relay。至少 10 GiB 的资源压测、SFTP/MinIO 真实兼容矩阵和生产反向代理验证仍属于部署环境扩展验收，不影响本次 OSS/FTP 单集群功能验收结论。

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
- 尚未完成：两条 SFTP 出方向的最终提交和 SHA-256；FTP→OSS、OSS→SFTP 及各自一次断点恢复；三个数据源完整 4 GiB 文件的下载、重命名或移动、删除；精确文件 ACL、重复传输 `SKIPPED`、不同内容冲突 `CONFLICT`、终态队列/指标复核。未完成项不得记为通过。

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

## 7. 服务重启与健康检查

- Studio Server 使用项目脚本完成结构升级、离线构建和重启，`/actuator/health` 返回 `UP`。
- Studio Worker 绑定 `DEFAULT-LOCAL`，`/actuator/health` 返回 `UP`。
- Web 统一入口 `http://127.0.0.1:8000/dfs/data-aggregation-studio/` 返回 HTTP `200`，文件传输和非结构化管理路由可正常加载；本轮未启动或使用 `5173`。
- 2026-08-11 最终状态：Server 为本轮 02:30 后启动的新进程，Server/Worker 健康均为 `UP`，Web 返回 HTTP `200`，唯一运行集群在线 Worker 数为 `1`；启动后无新增 `ERROR` 或 Relay 调用。
- 2026-08-11 为修复 SFTP 长异常消息造成的审计截断，新增 `20260811-unstructured-op-audit-message.sql`，并由启动升级将 MySQL `unstructured_op_audit.message` 改为 `TEXT`；无新增表、索引、公共接口路径或响应结构变化。Server 已重启并执行升级，Worker 与前端无需因本次修复重启。
- 2026-08-11 SFTP 路径语义复验：`SftpHelper` 恢复绝对路径原样传递，数据采集旧接口不再受“登录目录逻辑根”改造影响；`TransferFileSystem.initialPath()` 仅用于页面首次定位。通过 Studio 公共接口在 `/upload` 下创建目录成功，实际调用为 `/upload/studio-sftp-pwd-check-20260811-1709`；随后浏览 `/upload` 未再发现该目录及此前同类临时目录，清理状态已确认。该验证不改变 SFTP 的真实根 `/`，也不代表已完成 SFTP 下载或双向传输矩阵。

## 8. 残余风险与后续环境验收

以下项目未在本地真实环境完成，不应被误写为已通过：

1. 至少 10 GiB 单文件的内存、带宽、超时和断点恢复压测。
2. SFTP、MinIO 的真实服务兼容矩阵和故障注入。SFTP 已完成现代算法真实连接、公共连接测试、真实根 `/` 浏览、`/upload` 下目录操作，以及两个 4 GiB SFTP 出方向运行的暂停/恢复；页面首次定位使用连接后的 `pwd()`，绝对路径仍按旧插件语义访问服务端真实根。4 GiB 四方向终态、三源下载/修改/删除和旧算法服务端兼容矩阵仍未完成。
3. 10 万小文件的发现、数据库写入和指标采样压测。
4. 生产反向代理的 TLS、SSE/下载流、负载均衡空闲超时和滚动发布验证。
5. 启动输出中的 `spring-jcl`/`commons-logging.jar` 类路径提示需按依赖树单独治理；该提示不是文件传输异常，未影响本次健康检查或验收结果。

这些项目是生产规模与环境兼容验收，不扩大当前单集群 OSS/FTP 功能边界。任何后续接口、表结构、状态机或 Worker-only 安全边界调整，都必须同步更新设计基线和变更跟踪文档。
