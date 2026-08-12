# Studio 单集群文件传输与非结构化管理改造变更跟踪

> 状态：实施已完成，4 GiB SFTP 扩展验收进行中
> 目标线程 ID：`019fdb69-25b0-7bd3-a544-39738bf0e618`
> 基线设计文档：[`studio-unstructured-file-transfer-plan-20260807.md`](./studio-unstructured-file-transfer-plan-20260807.md)
> 需求确认日期：2026-08-09
> 当前阶段：W6 长时间环境验收进行中

## 1. 目的与使用规则

本文档跟踪 Studio 非结构化文件传输从“可跨集群、Server Relay 中继”的初始设计收敛到“单集群、单 Worker 执行”，并新增单源非结构化管理和 ACL 的全部实施变化。本文是实施期的变更记录，不替代设计基线；两者必须保持一致。

后续若调整公共接口、表结构、状态机、权限模型、安全边界或运行拓扑，必须先在本文档记录，再同步更新基线计划、接口文档、数据库脚本和测试材料。每个工作包完成时补充完成时间、修改文件、行为变化、接口/表结构变化、测试命令与结果、是否更新基线、是否重启 Server/Worker/Frontend。

状态流转：

```text
待开始 → 进行中 → 已完成
                    └→ 阻塞
```

## 2. 已确认需求与产品决策

- 文件传输只允许在同一个运行集群内执行，源数据源和目标数据源由同一个 Worker 访问。
- 新传输路径不使用 Server Relay、Worker-to-Worker 直连或跨集群票据；历史字段和历史跨集群记录保留查询兼容，但不可重试、恢复或再次执行。
- 运行集群在传输中心只选择一次；进入页面时运行集群、源数据源、目标数据源均不默认选择。
- 选择运行集群后仅加载该集群适用的数据源；选择具体数据源后才加载目录列表。
- 队列、运行记录和文件详情不展示方向列、集群名称或集群 ID；用“从：[数据源名称] 源路径”和“到：[数据源名称] 目标路径”表达端点。
- 队列使用 SSE 长连接接收状态事件，不使用前端定时轮询；传输队列支持手动删除。
- 首期支持 Local、FTP、SFTP、MinIO、OSS、Aliyun OSS；跨数据源文件移动仍通过传输中心完成。
- 传输支持多文件并行，入队默认自动开始；摘要一致时统一跳过；源端默认保留。
- 首期接入工作流，工作流文件传输节点必须遵循同集群校验。
- 数据资产菜单增加“非结构化管理”，提供单源浏览、直接下载、新建目录、移动、重命名和递归删除。
- Server 只负责鉴权、编排和流式转发，不加载文件插件、不落盘；Worker 执行真实文件操作并写入审计记录。
- 数据源创建者和管理员维护 ACL；授权对象限定为当前项目成员。权限为 BROWSE、DOWNLOAD、EDIT、DELETE，支持源级公开、用户级授权、路径级 ALLOW/DENY/INHERIT。

## 3. 当前系统基线

- Studio Server、Studio Worker、Studio Infra、Studio DTO 和 Web 前端已存在文件浏览、手工队列、预设任务、运行记录、指标和工作流节点的旧实现。
- 旧实现同时保存 source/target runtime cluster 字段，并存在 `direction`、`channel`、`SERVER_RELAY` 运行语义。
- DataAggregation 文件传输抽象已经提供 `stat`、`listPage`、`openRead`、`mkdir`、`move`、`delete`、`commit`、`abort`、`capabilities`。
- 数据库同时维护 MySQL、桌面 SQLite、在线升级脚本；本次改造必须保持三套结构一致。
- 旧实现曾验证过后端编译、前端类型检查和生产构建，但这些结果不作为本次单集群/ACL 改造的完成证明。
- `unstructured-transfer` 仅作为分层、队列和适配器组织方式的参考，不原样移植旧实现。

## 4. 变更范围

### 4.1 本次实施范围

1. 单集群模型收敛：规范字段 `runtimeClusterId`、同集群校验、历史跨集群不可执行、移除新运行路径 Relay。
2. 传输中心交互：共享运行集群选择、数据源和目录延迟加载、无默认选择、SSE 长连接和手动删除队列。
3. 非结构化管理后端：单源浏览、下载、mkdir、rename、move、delete、路径规范化、能力检查和审计。
4. ACL：数据源创建者、项目成员边界、源级和路径级授权、权限继承和管理员绕过。
5. 非结构化管理前端、菜单、路由、接口联调文档和测试用例。
6. MySQL、SQLite、在线升级脚本、定向测试、前端类型检查和生产构建。

### 4.2 非变更范围

- 不删除历史数据库字段和历史运行记录。
- 不实现跨集群传输、Server Relay、Worker-to-Worker 直连、文件上传、在线编辑或内容预览。
- 不改变现有结构化采集、融合、质量和非文件数据源的业务语义。
- 不将文件插件加载到 Server；不以 Server 本地文件系统作为回退路径。

## 5. 工作包与执行状态

| 工作包 | 内容 | 状态 | 完成时间 | 备注 |
|---|---|---|---|---|
| W0 | 文档、导航、设计基线修订 | 已完成 | 2026-08-09 | 基线改为单集群 Worker-only，补充非结构化管理、ACL、FTP/OSS 验收边界 |
| W1 | 单集群字段、校验、历史兼容、Relay 运行路径移除 | 已完成 | 2026-08-09 | 保留历史数据库字段；新请求跨集群返回 `FILE_TRANSFER_CROSS_CLUSTER_DISABLED` |
| W2 | 传输中心共享集群选择、延迟浏览、SSE、队列手动删除 | 已完成 | 2026-08-09 | 不增加轮询；队列和运行展示只使用数据源名称与路径 |
| W3 | Worker 文件管理接口和 Server 鉴权/流式转发 | 已完成 | 2026-08-09 | Local/FTP/SFTP/MinIO/OSS；Worker 执行真实文件操作 |
| W4 | 数据源创建者、源级/路径级 ACL、操作审计 | 已完成 | 2026-08-09 | 项目成员边界、ALLOW/DENY/INHERIT、管理员/创建者绕过 |
| W5 | 非结构化管理菜单、浏览器、ACL 页面和权限状态 | 已完成 | 2026-08-09 | `/dfs/data-aggregation-studio/unstructured-management` |
| W6 | 后端、前端、真实 OSS/FTP/SFTP、重启和健康检查验收 | 进行中 | - | 原 OSS/FTP 小样本验收已通过；新增 4 GiB SFTP 四方向和三源文件管理矩阵仍在执行 |

## 6. 公共接口变更清单

### 6.1 传输中心

- 新请求统一携带 `runtimeClusterId`，源/目标数据源必须适用于同一集群。
- 跨集群新请求返回 `FILE_TRANSFER_CROSS_CLUSTER_DISABLED`。
- 历史跨集群运行、包含历史 Relay 通道的记录可查询，但重试、恢复和再次执行返回业务错误。
- 队列与运行视图返回数据源名称和路径端点，不返回方向展示字段；底层旧字段保留兼容。
- SSE 事件继续作为列表状态更新唯一实时来源；队列删除为显式后端操作。

### 6.2 非结构化管理

```text
GET  /api/v1/unstructured-management/sources
POST /api/v1/unstructured-management/browser/list
GET  /api/v1/unstructured-management/download
POST /api/v1/unstructured-management/operations
```

操作枚举：`CREATE_DIRECTORY`、`RENAME`、`MOVE`、`DELETE`。

ACL：

```text
GET    /api/v1/unstructured-management/acl/source/{datasourceId}
PUT    /api/v1/unstructured-management/acl/source/{datasourceId}
GET    /api/v1/unstructured-management/acl/path
PUT    /api/v1/unstructured-management/acl/path
DELETE /api/v1/unstructured-management/acl/{id}
GET    /api/v1/unstructured-management/users/options
```

所有 JSON 接口统一使用项目现有 `Result<T>` 返回约定；文件列表和运行列表遵循 Studio 现有 `PageView`/列表视图结构，下载接口直接返回二进制流。错误至少包括未授权、数据源不适用、插件能力不支持、目标冲突、路径穿越、递归确认缺失和跨集群禁用。

## 7. 数据库字段和表结构变更清单

- `datasource_definition.created_by`：保存数据源创建者。
- 文件传输任务、运行、文件项增加规范字段 `runtime_cluster_id`；旧 source/target 集群字段保留。
- 新增 `unstructured_source_acl`：数据源级公开/用户权限。
- 新增 `unstructured_path_acl`：目录/文件路径级 ALLOW、DENY、INHERIT 权限。
- 新增 `unstructured_op_audit`：mkdir、rename、move、delete 成功和失败审计。
- MySQL schema、SQLite schema、StudioSchemaUpgradeService 和 `backend/studio-server/src/main/resources/update/` 增量脚本必须同步。

## 8. 兼容、迁移与回滚

- 旧字段不删除；新字段允许为空，历史记录按旧字段读取。
- 新建或更新任务时写入 `runtime_cluster_id` 并校验源/目标一致；旧记录只有在可证明同集群时才允许迁移执行。
- 无法确定创建者的数据源 `created_by` 保持为空，仅管理员可维护 ACL。
- 历史跨集群记录只读，任何重试/恢复路径必须先拒绝。
- 迁移脚本采用幂等的列/表存在检查；回滚仅回退新代码和新增空表/字段，不删除历史记录。

## 9. 安全与行为约束

- 所有路径先规范化，拒绝 `..`、绝对路径越界、空目标和跨根目录操作。
- 移动只允许同一数据源；重命名只允许同一父目录；目标存在时拒绝覆盖。
- 下载只允许文件；非空目录删除必须显式递归确认。
- ACL 最具体路径优先，目录权限递归到后代，文件规则只作用于当前文件；创建者和管理员始终拥有全部权限。
- Server 不读取数据源内容；Worker 日志和审计不得记录明文凭据或完整文件内容。

## 10. 阶段执行记录

### W0：文档与基线

- 开始时间：2026-08-09
- 修改文件：本文档、`docs/README.md`、`studio-unstructured-file-transfer-plan-20260807.md`
- 行为变化：设计基线改为单集群 Worker-only，补充非结构化管理、ACL、下载和测试章节。
- 接口/表结构：仅记录计划，不改变运行代码。
- 测试：Markdown 标题/围栏、Mermaid 架构块、相对链接和 `git diff --check` 均通过。
- 是否更新基线计划：是。
- 是否需要重启 Server/Worker：否。

### W1-W6

按工作包完成情况追加记录，至少填写修改文件、行为变化、接口/表结构、测试命令与结果、基线同步和服务重启情况。

### W1：单集群模型收敛

- 完成时间：2026-08-09
- 修改模块：`studio-dto`、`studio-infra`、`studio-server`、`studio-worker` 文件传输任务、运行、文件项和 Worker 执行器。
- 行为变化：规范写入 `runtimeClusterId`；源/目标不适用于同一集群时拒绝；历史跨集群记录只读；新运行路径不创建或调用 Server Relay。
- 接口/表结构：保留旧 source/target 集群字段用于历史兼容，新增规范字段和错误码 `FILE_TRANSFER_CROSS_CLUSTER_DISABLED`。
- 测试：`FileTransferSingleClusterTest`、`FileTransferRunRemovalTest`、`FileTransferNodeExecutorRetryTest`、`WorkerFileTransferDispatchTest` 均通过。
- 是否更新基线计划：是，已同步单集群架构。
- 是否需要重启 Server/Worker：需要，发布代码后执行。

### W2：传输中心交互

- 完成时间：2026-08-09
- 修改模块：文件传输中心、队列、运行记录和指标前端组件及对应 API SDK。
- 行为变化：共享一个运行集群选择器；未选择集群/数据源不加载目录；去除方向和集群展示；保留 SSE 长连接和队列手动删除。
- 接口/表结构：新请求统一携带共享 `runtimeClusterId`；历史字段继续返回但不作为列表展示。
- 测试：前端 `vue-tsc --noEmit`、生产构建和浏览器验收通过；SSE 无列表轮询，唯一 `setTimeout` 用于封顶退避重连。
- 是否更新基线计划：是。
- 是否需要重启 Server/Worker：需要发布 Web 和后端后执行。

### W3-W4：Worker 管理能力与 ACL

- 完成时间：2026-08-09
- 修改模块：`RuntimeDatasourceProbeExecutor`、`RuntimeDatasourceProbeRouter`、`RuntimeEndpointHttpClient`、`UnstructuredManagementService`、`UnstructuredManagementController`、ACL DTO/Entity/Mapper，以及 MySQL/SQLite/在线升级 SQL。
- 行为变化：新增单源浏览、流式下载、目录创建、重命名、同源移动、文件/递归目录删除、路径安全校验、操作审计和源级/路径级 ACL。
- 接口/表结构：新增 `/api/v1/unstructured-management/*` 接口及 `unstructured_source_acl`、`unstructured_path_acl`、`unstructured_op_audit` 表；`datasource_definition.created_by` 同步加入三套 schema。
- 测试：ACL、文件操作、schema、Controller、审计测试纳入最终 23 个测试类、101 项 Studio 聚焦回归，0 失败、0 错误。
- 是否更新基线计划：是，已补非结构化管理、ACL 和 FTP 验收章节。
- 是否需要重启 Server/Worker：需要。

### W5：前端入口与操作界面

- 完成时间：2026-08-09
- 修改模块：路由、数据资产菜单、API SDK、`UnstructuredManagementView.vue`。
- 行为变化：支持单源浏览、分页、显式刷新、下载、mkdir、rename、move、delete、递归确认、ACL 编辑和有效权限展示；没有权限时隐藏/禁用操作；不使用轮询。
- 测试：`npm exec --workspace @studio/web vue-tsc -- --noEmit` 通过；`npm run build --workspace @studio/web` 通过。
- 是否更新基线计划：是。
- 是否需要重启 Server/Worker：前端发布后需要刷新 Web；后端接口发布后需要重启 Server/Worker。

### W6：构建与真实验收

- 2026-08-09：DataAggregation 传输内核、CLI、Local/FTP/SFTP/MinIO/OSS 适配器聚焦回归 6 个测试类、36 项测试，0 失败、0 错误。
- 2026-08-09：Studio 单集群、传输、非结构化管理、ACL、审计、SSE、指标、工作流和 Worker 聚焦回归 23 个测试类、101 项测试，0 失败、0 错误。
- 2026-08-09：前端 `vue-tsc --noEmit` 和 Vite 生产构建通过，4324 个模块完成转换。
- 真实 OSS 和用户新增 FTP 测试源均完成浏览、mkdir、rename、move、download、delete、递归删除、冲突和路径安全验收，临时验收目录已清理。
- 同集群 OSS→FTP、FTP→OSS 双向传输成功；最终 FTP→OSS 运行 `2086332888130015233`、运行记录 `2086332899169460226`、18,362,156 Byte、一次成功，SHA-256 为 `f7fd28cee793ce8340439a028d0d12c409186d7255ef902b3d314f9664b9d373`，源端 `KEEP` 已确认。
- 真实目录 ACL 和文件 ACL 保存、重新加载成功，分别保持递归目录和精确文件作用域；临时规则验收后已删除。
- 三个取消诊断队列项通过页面移除；终态指标活动文件和当前速度归零，OSS/FTP TopN 使用数据源名称。
- SSE 使用长连接且无列表轮询；进入传输中心后离开页面并跨过 15 秒心跳，Server 未记录断连 `ERROR` 或 `HttpMessageNotWritableException`。
- 后端/前端精确扫描无文件传输 Relay Controller、Client、Ticket、Router、Bean 或配置符号；真实传输日志无 Relay 调用。
- 使用 `scripts/dev-services.ps1` 最终统一重启 Server、Worker 和 Frontend；Server/Worker 健康均为 `UP`，Frontend 返回 HTTP `200`，启动日志无新增错误。Frontend 重启清除了浏览器内存登录态，后续页面访问按预期重定向登录，不读取或恢复任何凭据。
- 2026-08-09 目录零文件回归：确认手工目录选择会递归展开叶子文件并保留目标相对路径；手工路径只包含空目录时不再以零文件成功，而是以 `FILE_TRANSFER_NO_FILES_DISCOVERED` 失败。`FileTransferNodeExecutorRetryTest` 8 项通过；不涉及接口字段、表结构或数据库脚本变更，发布后需要重启 Worker 并刷新 Web。
- 2026-08-09 队列状态 Tab：传输队列增加“传输中”和“已完成”两个视图；文件项由现有 SSE 状态事件自动重新分类，终态迁移不打断用户当前 Tab，且不增加前端轮询。仅修改 Web 展示与测试/设计文档，无接口、数据库、SQL 或 Worker 变化；`vue-tsc --noEmit`、4324 模块生产构建、桌面页面及 `390x844` 响应式验收通过，浏览器控制台无错误；发布后只需刷新 Web，不需要重启 Server/Worker。
- 2026-08-09 文件传输 TopN 口径修复：来源/目标数据源 TopN 从错误的累计传输字节数改为成功文件数，摘要一致跳过文件按成功终态计入；增加“字节排名与文件数排名相反”的回归用例，防止口径回退。`FileTransferMetricServiceTest` 2 项和指标源/API 回归 9 项均通过；Server 重启后健康状态为 `UP`，页面显示成功文件 `3`、`aliyun oss` TopN `3`、`ftp测试源` TopN `3`，控制台无错误。接口结构、DTO、数据库、SQL、前端和 Worker 均无变化；不需要数据库更新或重启 Worker。
- 2026-08-10 Review 修复：文件传输浏览、即时入队和预设任务执行统一进入非结构化 ACL 边界，源路径要求 `DOWNLOAD`、目标路径要求 `EDIT`；定时任务、工作流定时触发、后台就绪节点分发和工作流下游续跑按任务/工作流创建人或原始触发用户重建当前角色上下文，禁用账户不可作为后台身份；源级 ACL 固定为用户规则优先且同级 `DENY` 优先；FTP 保留登录目录和既有路径处理语义，递归始终使用插件可接受的路径；预设任务触发复用 `studio_cluster_lock` 的 `file-transfer-task-run:{taskId}` 非重入锁；Worker 在写最终指标前先持久化运行终态计数，终态运行与文件项的当前速度固定归零；SSE 每次重连均重新同步快照，加载期间的事件合并为一次后续同步；运行查询新增 `triggerType` 和 `statusGroup=ACTIVE|TERMINAL`，传输中心完整分页加载全部活动运行和最近 10 个终态运行，并按运行 ID 合并状态迁移期间的重复快照，不再扫描全部历史记录。
- 2026-08-10 Review 验证：DataAggregation 插件运行时、Local、FTP、SFTP、目录发现、大小写路径和传输引擎 7 个测试类、40 项测试全部通过；Studio ACL、单集群、队列删除、并发触发、指标、SSE、Worker 重试、后台身份上下文和 Server 路由契约 22 个测试类、75 项测试全部通过；前端队列回归脚本、`vue-tsc --noEmit` 和 Vite 生产构建通过，4325 个模块完成转换。`git diff --check` 通过。本次没有新增或修改数据库表、字段、索引及升级 SQL，锁能力复用既有 `studio_cluster_lock`；数据库无需升级。按用户要求 Server、Worker、Frontend 保持关闭，发布代码后需要重启 Server/Worker 并重新发布 Web 才能生效。
- 2026-08-11 OSS 非空目录保护修复：`RuntimeDatasourceProbeExecutor` 的目录判空页大小由 `1` 调整为 `1000`，只有条目为空且 `truncated=false` 才证明目录为空；`AliyunOssHelper` 遇到仅包含当前目录 marker 的截断页时继续读取下一页，marker 不作为子文件展示，分页标记不前进时安全终止。真实 OSS 验证中，非空目录未确认递归删除返回 `Non-empty directory deletion requires recursive confirmation` 且对象保留，确认递归后前缀删除成功；FTP 同一保护行为未回归。
- 2026-08-11 峰值指标修复：`FileTransferNodeExecutor.finish(...)` 终态保存前重新读取最新运行实体，不再用执行开始时的旧实体把监听器已持久化的 `peakBytesPerSecond` 覆盖为零；终态 `currentBytesPerSecond` 和 `activeFiles` 仍归零。真实 OSS→FTP 与 FTP→OSS 运行分别保留大于零的峰值，运行记录与指标页面显示一致。
- 2026-08-11 非结构化管理下拉修复：管理页绑定 `@update:datasource-id="selectSource"`，数据源切换统一重置路径、条目、选择、分页游标和权限，并用浏览/权限请求序列号拒绝迟到响应；内部下拉增加可清除操作。浏览器实测可从 OSS 切换到 FTP，目录和权限同步更新，清除后目录为空且不加载旧数据，控制台无新增警告或错误。
- 2026-08-11 SSE 断连收口：事件流响应发生 `AsyncRequestTimeoutException` 时不再进入通用 JSON 错误处理；已提交或 `text/event-stream` 响应按正常断连处理，普通非流式异步超时仍返回 `SERVICE_UNAVAILABLE`。重启后短连接收到 `SNAPSHOT_REQUIRED`，客户端退出并跨过心跳清理周期，Server 未记录新的 `ERROR`、`HttpMessageNotWritableException` 或 Relay 调用。
- 2026-08-11 定向回归：`AliyunOssHelperTest` 2 项、`RuntimeDatasourceProbeExecutorFileOperationTest` 9 项、`FileTransferNodeExecutorRetryTest` 9 项、`StudioTransferEventListenerTest` 3 项、`GlobalExceptionHandlerTest` 9 项，共 5 个测试类、32 项测试全部通过。前端队列回归脚本通过，`vue-tsc --noEmit` 和 Vite 生产构建通过，4325 个模块完成转换。
- 2026-08-11 真实环境复验：OSS/FTP 均通过浏览、mkdir、目录/文件 rename、同源 move、二进制 download、未确认非空目录删除拒绝和确认递归删除；同一文件从两端下载均为 1,444,025 Byte，SHA-256 一致。18,362,156 Byte 样本的 OSS→FTP、FTP→OSS 均成功且源/目标 SHA-256 一致；重复传输为 `SKIPPED` 且传输字节为零，不同内容目标为 `CONFLICT` 且未覆盖。SSE 收到 `SNAPSHOT_REQUIRED`、`RUN_CHANGED` 和 heartbeat；传输中/已完成 Tab、数据源名称、简短触发名称、非零峰值、指标数据源选项和 TopN 文件数均通过浏览器复核。临时 OSS/FTP 前缀和本轮即时队列项已清理。
- 2026-08-11 发布影响：本轮没有新增或修改数据库表、字段、索引、SQL、公共接口路径或响应结构，数据库无需更新。Server、Worker 和 Frontend 已加载对应版本并通过健康检查；SSE 断连处理补丁仅需重启 Server，文件传输内核修复需重启 Worker，Web 修复需重新发布或刷新前端。
- 2026-08-11 SFTP SSH 算法协商兼容：SFTP 插件保持 `com.github.mwiede:jsch 0.2.23` 的现代默认算法；新增按数据源生效的 `allowLegacyAlgorithms=false` 开关。仅显式开启时才追加 SHA-1 KEX、`ssh-rsa`/`ssh-dss`、CBC 和 HMAC-MD5，以兼容旧 SFTP 服务；连接错误会提示优先升级服务端算法。Studio 数据源表单同步提供标注安全影响的开关，SFTP 默认端口文档同步校正为 `22`。本次无数据库、SQL、公共接口路径或响应结构变化；需要重启 Studio Server 和 Worker。SFTP 真实服务器复验待连接目标可用后执行。
- 2026-08-11 SFTP 真实连接复验：Worker 实际加载的 `plugin/source/sftp/libs` 已无 `jsch-0.1.51.jar`，仅保留 `jsch-0.2.23.jar`。对已保存 SFTP 数据源的 Worker 内部探测在未设置 `allowLegacyAlgorithms` 的现代默认配置下返回 `AVAILABLE`、`Connection success`（约 1.1 秒），证明本次目标的 SSH 算法协商已通过。同期 Studio Server 公共数据源测试仍返回 `Target runtime cluster probe is unavailable`；Worker 日志存在 MySQL `Communications link failure` 和租约心跳持久化失败，属于 Server 到 Worker endpoint/运行时租约链路问题，不能归因于 SFTP 协商，也不能通过开启旧算法绕过。SFTP 文件浏览和双向传输的公共链路验收待该运行时问题恢复后补做。
- 2026-08-11 Server→Worker 路由修复：`RuntimeDatasourceProbeRouter` 的普通 POST 和流式下载统一发送由 endpoint 绑定的 `X-Studio-Target-Cluster-Id`，并在配置头清洗后重新写入，防止 endpoint 配置伪造目标集群。补充安全回归验证配置值 `999` 不能覆盖实际集群 `46`；无数据库、公共接口路径或响应结构变化，仅需重启 Server。
- 2026-08-11 SFTP 公共链路复验：重启 Server 后，`POST /api/v1/datasources/{id}/test?runtimeClusterId={clusterId}` 返回 `AVAILABLE`、`Connection success`（约 1.4 秒）；`POST /api/v1/unstructured-management/browser/list` 浏览服务端真实根 `/` 成功，返回 `/upload` 及根目录文件（约 6.7 秒）。Worker 继续只加载 `jsch-0.2.23.jar`，`allowLegacyAlgorithms` 保持 `false`；此次问题已确认并非需要全局降级算法。
- 2026-08-11 SFTP 管理失败审计修复：此前试验请求误把 `/codex` 当作登录目录下的目标路径，产生的 `Permission denied` 仅是该次路径/远端权限结果，不是 SFTP 逻辑根定义。原始 Worker 异常文本较长，历史 MySQL `unstructured_op_audit.message` 的 `VARCHAR` 定义会导致审计 INSERT 触发 `Data truncation` 并遮蔽真实错误；现通过在线升级和 `20260811-unstructured-op-audit-message.sql` 将字段改为 `TEXT`，应用侧将审计消息限制为 1,800 字符并保留摘要。审计写入增加失败隔离，不能覆盖文件操作结果；页面现在返回真实的 SFTP 权限/路径错误。`UnstructuredManagementOperationAuditTest`（5 项）和 `UnstructuredManagementSchemaIntegrationTest`（2 项）通过。数据库已由 Server 启动升级，需重启 Server；Worker 和前端无需因本次审计修复重启。
- 2026-08-11 SFTP 路径语义恢复：确认之前把连接后的 `pwd()` 当成不可越出的逻辑根是错误的，已恢复旧版插件语义。`SftpHelper.resolveRemotePath("/")` 保持服务端真实根 `/`，绝对路径（例如 `/upload/file`）原样传给 SFTP 服务端，相对路径才以连接后的 `pwd()` 为基准；`exists`、`listFile`、`isFile`、`rm`、`mv`、`getInputStream`、`getOutputStream` 等数据采集旧接口同步恢复原有路径处理，不改变既有采集任务。公共文件浏览沿用已交付的 `initialPath` 字段：SFTP 首次选择数据源时显示连接后的 `pwd()`（本源为 `/upload`），用户仍可进入真实根 `/`，后续不再自动重定向。通过公共接口在 `/upload` 下创建临时目录成功，实际调用路径为 `/upload/studio-sftp-pwd-check-20260811-1709`；复浏览确认本轮临时目录及此前 `/upload/studio-sftp-root-check-20260811-1543` 均已不存在。本次路径恢复不新增数据库、SQL、接口路径或字段；Worker、Server 已重启，Web 刷新后生效。
- 2026-08-11 4 GiB UI 扩展验收：统一从 `http://127.0.0.1:8000/dfs/data-aggregation-studio/` 进入，不使用 `5173`。已通过管理员界面创建独立项目成员并为 OSS、FTP、SFTP 三个测试源配置路径权限；测试用户通过页面确认 SFTP 初始路径为 `/upload`，源文件 `/upload/test-4g.bin` 显示为 4 GiB。已通过页面创建四方向隔离目录，并完成 SFTP 小目录的新建、重命名、同源移动和删除。
- 2026-08-11 断点恢复与单 Worker 并发：SFTP→OSS、SFTP→FTP 两个 4 GiB 即时运行均通过页面执行过一次“暂停→已暂停→恢复→传输中”，文件项均为叶子文件，且仍处于第一次传输尝试，没有触发失败重试。为避免一个长传输占用调度线程阻塞其他文件运行，`WorkerLifecycleRunner` 将文件传输 dispatch 放入独立固定线程池并用信号量限制并发；非文件任务继续使用原执行路径，Worker 关闭时清理线程池。页面同时显示两个活动运行，证明同一 Worker 可并发推进多个文件传输。
- 2026-08-11 23:54 进度证据：SFTP→OSS 已传输约 216 MiB，当前速率约 151 KiB/s，峰值约 320 KiB/s；SFTP→FTP 已传输 128 MiB，当前速率约 177 KiB/s，峰值约 330 KiB/s。两条运行都仍为 `RUNNING`，不能提前记为传输成功。Worker 定向回归 5 个测试类、33 项测试全部通过；目录展开用例补齐 Mock `TransferFileSystem.capabilities()` 后为 0 失败、0 错误。
- 2026-08-12 00:11 进度证据：FTP 根目录中的 4 GiB `test-4g.bin` 已通过页面入队至 OSS 隔离目录，完成一次“暂停→已暂停→恢复→传输中”，且仍处于第一次传输尝试；接口已确认写入约 24 MiB。此时三条长传输均为 `RUNNING`，SFTP→OSS 约 360 MiB，SFTP→FTP 约 272 MiB，FTP→OSS 约 24 MiB，均未达到终态。
- 2026-08-12 00:19 页面快照：SFTP→OSS 为 10% / 106 KiB/s，SFTP→FTP 为 8% / 115 KiB/s，FTP→OSS 为 2% / 114 KiB/s；三条均显示“传输中”和 `transfer resumed`。当前环境总吞吐在并发任务间分配，完整终态需要跨小时运行。
- 4 GiB 扩展验收未完成项：等待 SFTP→OSS、SFTP→FTP 完整提交并校验 SHA-256；随后通过页面执行 FTP→OSS、OSS→SFTP 及各自一次断点恢复；最后对三个数据源上的完整 4 GiB 文件执行下载、重命名或同源移动、删除，并复核精确文件 ACL、重复传输 `SKIPPED`、冲突 `CONFLICT`、SSE、队列 Tab、终态峰值和 TopN 文件数。以上项目完成前，W6 保持“进行中”。

## 11. 测试与验收记录

完整命令、测试类、真实运行证据、页面交互和残余风险记录在 [`studio-file-transfer-acceptance-record-20260807.md`](../测试/文件传输/studio-file-transfer-acceptance-record-20260807.md)。原 OSS/FTP 小样本聚焦验收已通过；新增 4 GiB SFTP 扩展矩阵仍在执行。本次没有把正在运行或尚未执行的项目表述为绿色，也没有把未执行的 Studio 全仓非文件传输测试表述为绿色。

## 12. 风险、阻塞与回滚

| 项目 | 风险/阻塞 | 处理 | 状态 |
|---|---|---|---|
| 历史跨集群记录 | 旧记录可能缺少可验证的单集群身份 | 只读展示，重试/恢复前强制拒绝 | 自动化通过 |
| 插件能力差异 | FTP/SFTP/OSS 对移动、删除和目录语义不同 | FTP/OSS 真实通过；SFTP/MinIO 保留部署环境矩阵 | 部分环境待验收 |
| ACL 兼容 | 历史数据源没有创建者 | 空值仅管理员可维护；真实路径作用域和在线字段通过 | 已验证 |
| 实时列表 | SSE 断线、重连和事件顺序 | 事件/快照恢复，无轮询；正常断连不写错误响应 | 已验证 |
| 数据库升级 | MySQL/SQLite/在线脚本不一致 | 三套结构和 20260807/20260809 增量脚本做 schema 测试 | 已验证 |
| 生产规模 | 10 GiB、10 万小文件和反向代理参数依赖部署环境 | 作为发布环境扩展验收，不扩大当前功能边界 | 待环境验收 |
| 日志依赖提示 | 启动时 `spring-jcl` 提示类路径仍存在 `commons-logging.jar` | 不影响本次传输结果；后续按依赖树单独治理，避免与功能修复混改 | 待依赖治理 |
| SFTP 旧服务端算法 | 个别服务端可能只提供 SHA-1、ssh-rsa、CBC 或 HMAC-MD5 | 默认保持现代算法；仅对确认无法升级的单个数据源显式开启 `allowLegacyAlgorithms`，并在服务端升级后关闭 | 已提供兼容开关，待矩阵验收 |
| 4 GiB SFTP 吞吐 | 当前真实环境单条 SFTP 传输约为数百 KiB/s，完整四方向矩阵耗时较长 | 保持任务运行并以接口字节计数和 UI 进度交叉核对；完成前不判定成功 | 进行中 |

回滚原则：先停止新入口和调度，再回滚应用代码；保留新增字段、表和审计记录，禁止删除历史传输记录。必要时隐藏前端菜单并保留只读查询。

## 13. 变更审批与确认记录

| 日期 | 确认事项 | 结论 | 记录 |
|---|---|---|---|
| 2026-08-09 | 取消跨集群文件传输和 Server Relay | 已确认 | 用户需求 |
| 2026-08-09 | 单集群、单 Worker、共享运行集群选择 | 已确认 | 用户需求 |
| 2026-08-09 | 增加非结构化管理和 ACL | 已确认 | 用户需求 |
| 2026-08-09 | SSE 长连接、队列手动删除、无默认数据源 | 已确认 | 用户需求 |
| 2026-08-09 | FTP 测试数据源纳入真实操作验收 | 已确认 | 用户补充 |

## 14. 最终完成标准

单集群模型、历史兼容、非结构化管理、ACL、前端交互、数据库三套结构、原 OSS/FTP 小样本、接口/部署/测试文档以及既有健康检查已经完成。当前新增完成标准还包括 4 GiB 文件的 SFTP→OSS、SFTP→FTP、FTP→OSS、OSS→SFTP 四方向终态成功和 SHA-256 一致、每个方向一次断点恢复、三个数据源上的 4 GiB 下载/修改/删除、精确文件 ACL、队列与指标复核；这些项目完成并通过最终构建、健康检查和 `git diff --check` 后，W6 才能重新标记为“已完成”。
