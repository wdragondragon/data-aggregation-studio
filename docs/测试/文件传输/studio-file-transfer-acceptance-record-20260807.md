# Studio 非结构化文件传输验收记录（2026-08-07，2026-08-09 修订）

> 结论：三阶段文件传输及 2026-08-09 单集群、单 Worker、非结构化管理、ACL、队列删除和 SSE 改造已完成。自动化测试、Web 生产构建、真实 OSS/FTP 双向传输、真实文件操作、ACL、指标、队列清理和服务健康检查均通过。
>
> 当前版本不提供跨集群文件传输或 Server Relay。至少 10 GiB 的资源压测、SFTP/MinIO 真实兼容矩阵和生产反向代理验证仍属于部署环境扩展验收，不影响本次 OSS/FTP 单集群功能验收结论。

## 1. 验收范围与环境

- DataAggregation 文件传输核心、插件类型、CLI 和 Local/FTP/SFTP/MinIO/OSS 适配。
- Studio 文件浏览、即时队列、预设任务、调度、Dispatch、单 Worker 执行、工作流和指标。
- 单源非结构化管理：浏览、下载、新建目录、重命名、同源移动、删除和递归删除。
- 源级/路径级 ACL、项目成员边界、数据源创建者和管理员权限、操作审计。
- 共享运行集群选择、数据源和目录延迟加载、队列手动删除、SSE 实时更新。
- 本地验收服务：Server `18080`、Worker `18081`、Web `5173`，运行集群 `DEFAULT-LOCAL`。
- 真实文件源：现有 OSS 测试源和用户在数据源管理中新增的 FTP 测试源。本文不记录连接地址、账号、密码、AccessKey、Secret 或其他凭据。

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

## 4. ACL、队列与指标验收

- 真实目录 ACL 保存后按“目录权限（作用于后代）”重新加载成功。
- 真实文件 ACL 保存后按“文件权限（仅当前文件）”重新加载成功。
- 临时 ACL 规则在验收后已删除。
- MySQL 在线升级后的 `unstructured_path_acl.directory` 字段工作正常。
- 文件操作成功和 Worker 业务失败均写 `unstructured_op_audit`；失败审计使用不回滚事务语义。
- 三个诊断产生的取消队列项通过页面手动移除，不需要轮询或页面持续刷新。
- 历史运行保留为 `已取消`，队列项清理不物理删除运行、指标或审计证据。
- 终态运行不再贡献陈旧的活动文件数和当前速度；实时指标为活动文件 `0`、当前速度 `0 B/s`。
- OSS/FTP 来源和目标 TopN 显示数据源名称，`count` 按成功文件数汇总（包含摘要一致跳过），不再误用传输字节数，也不显示资源 ID。
- 2026-08-09 口径修复后页面实测：当前运行汇总为成功文件 `3`、传输量 `2.54 MiB`，来源 `aliyun oss` TopN 为 `3`，目标 `ftp测试源` TopN 为 `3`；页面控制台无错误。

## 5. SSE 与前端交互验收

- 传输中心首次进入时运行集群、源数据源和目标数据源均未默认选中。
- 选择运行集群后只加载数据源选项；选择具体数据源后才加载目录。
- 队列和运行记录只显示 `[数据源名称] 路径`，不显示方向列、集群名称或集群 ID。
- 运行名称使用 `MM-dd HH:mm · 触发类型`，例如 `08-09 14:04 · 即时`。
- 队列只使用 `GET /api/v1/file-transfer/runs/events` SSE 更新；代码中没有列表 `setInterval` 或轮询定时器，唯一 `setTimeout` 用于封顶退避重连。
- Server 重启后进入传输中心建立 SSE，离开页面并跨过 15 秒心跳周期，日志没有新增 `AsyncRequestNotUsableException`、`HttpMessageNotWritableException` 或断连 `ERROR`。

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
- Web 开发服务可访问 `http://127.0.0.1:5173`，文件传输和非结构化管理路由可正常加载。

## 8. 残余风险与后续环境验收

以下项目未在本地真实环境完成，不应被误写为已通过：

1. 至少 10 GiB 单文件的内存、带宽、超时和断点恢复压测。
2. SFTP、MinIO 的真实服务兼容矩阵和故障注入。
3. 10 万小文件的发现、数据库写入和指标采样压测。
4. 生产反向代理的 TLS、SSE/下载流、负载均衡空闲超时和滚动发布验证。
5. 启动输出中的 `spring-jcl`/`commons-logging.jar` 类路径提示需按依赖树单独治理；该提示不是文件传输异常，未影响本次健康检查或验收结果。

这些项目是生产规模与环境兼容验收，不扩大当前单集群 OSS/FTP 功能边界。任何后续接口、表结构、状态机或 Worker-only 安全边界调整，都必须同步更新设计基线和变更跟踪文档。
