# Studio 单集群文件传输与非结构化管理改造变更跟踪

> 状态：单集群功能、MySQL Outbox 高可用和 W10 日志增强已完成；4 GiB SFTP 扩展业务矩阵仍待完成
> 目标线程 ID：`019fdb69-25b0-7bd3-a544-39738bf0e618`
> 基线设计文档：[`studio-unstructured-file-transfer-plan-20260807.md`](./studio-unstructured-file-transfer-plan-20260807.md)
> 需求确认日期：2026-08-09
> 当前阶段：W6 长时间环境验收收尾；W10 日志增强已完成

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
- 数据资产菜单增加“非结构化管理”，提供单源浏览、单文件上传、浏览器原生下载、目录/多选 ZIP 下载、新建目录、移动、重命名和递归删除。
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
- 不实现跨集群传输、Server Relay、Worker-to-Worker 直连、在线编辑或内容预览。
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
| W6 | 后端、前端、真实 OSS/FTP/SFTP、重启和健康检查验收 | 进行中 | - | OSS/FTP 小样本和 SFTP 连接、浏览、目录操作、出方向暂停/恢复已通过；4 GiB 四方向终态和三源文件管理矩阵仍在执行 |
| W10 | 非结构化管理与文件传输日志增强 | 已完成 | 2026-08-15 | 代码、自动化、真实 SFTP 权限拒绝、三源操作、统一入口、敏感扫描和重启健康检查均已完成 |

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

### W7：MySQL Outbox 高可用事件分发（已完成）

- 开始时间：2026-08-12；完成时间：2026-08-13；当前状态：已完成。代码、迁移、自动化测试、真实 MySQL、统一 8000 入口、SSE 重连和双 Server 独立游标均已取得证据。
- 修改模块：`studio-dto`、`studio-infra`、`studio-server`、`studio-worker`、Web SSE 客户端、MySQL/SQLite schema、启动升级、初始化清理和本节列出的文档。
- 数据模型：新增不可变 `file_transfer_event_outbox` 和按 `instance_id/tenant_id/project_id` 唯一的 `file_transfer_event_consumer_cursor`；新增四个 Outbox 查询索引和三个游标索引，不增加外键、不删除历史字段。
- 事件边界：新增 `FileTransferStateMutationService`，Server/Worker 的运行、文件项、检查点、指标、删除和异常终态通过字段级 `LambdaUpdateWrapper` 与事件同一短事务提交；整个文件传输过程不持有数据库长事务。事件类型固定为 `RUN_CREATED`、`RUN_CHANGED`、`ITEM_CHANGED`、`RUN_REMOVED`、`ITEM_REMOVED`。
- 进度控制：运行或文件项的非终态进度默认最多每秒追加一条 Outbox，状态表更新仍正常提交；强制/终态事件立即写入并清除内存限频键。4 GiB 文件不会按 8 MiB 块逐块写事件。
- HA/SSE：Server 默认 250 ms、500 行批次按本实例独立游标消费；同一运行批量加载最新视图，单个事件最多 200 个文件项。SSE 使用 Outbox ID 作为 `id:` 和 `data.eventId`，支持 `Last-Event-ID` 补发、5,000 条上限、过期快照和 15 秒 heartbeat；前端按十进制字符串比较雪花 ID，不增加列表轮询。
- 清理/监控：事件默认保留 7 天；只清理终态或已删除运行的旧事件，活跃 Server 游标落后时不清理，48 小时失联游标不再阻塞。游标等于事件 ID 表示已经扫描，可以清理；`cursor-lag` 使用真实未扫描事件行数，不用雪花 ID 相减。清理复用 `studio_cluster_lock`。
- 数据库交付：新增 `20260812-file-transfer-event-outbox.sql`；MySQL/SQLite 全量结构和在线升级同步。增量 SQL 与在线升级仅对无数据、只包含主键的半成品表执行重建；非空残缺表缺少事件、运行或游标身份列时 fail-closed，要求备份后人工回填或确认重建，绝不猜测历史事件归属。`RESET_TABLES` 先清理 Outbox，再清理运行数据。
- 自动化证据：`FileTransferEventServiceOutboxTest` 覆盖双 Server 独立游标、补发、过期快照、发送失败重连、删除事件、201 文件项拆分、禁用旧扫描和雪花 ID 积压计数；`FileTransferStateMutationTransactionTest` 使用 SQLite trigger 验证 Outbox 失败回滚业务状态；Schema 测试验证 SQLite 新库和半成品旧库重复升级；Outbox/清理/Writer/Worker 定向测试均通过。
- 构建兼容：修复 `StudioCookieCsrfFilterTest` 两处既有测试装配错误，以及 `ScriptEnvironmentOptionsSourceSlimmingRegressionTest` 对新增构造器依赖的 mock，未改变对应生产逻辑。
- 接口变化：公共路径不变；`GET /api/v1/file-transfer/runs/events` 新增可选 `Last-Event-ID` 请求头语义，`FileTransferQueueEventView` 新增可选 `eventId`。不引入 Redis/Kafka/RabbitMQ，不恢复跨集群 Relay。
- 发布影响：需要执行真实 MySQL 迁移，并按 Worker → Server → Frontend 顺序重启/发布。多 Server 必须配置唯一 `STUDIO_INSTANCE_ID`；混合版本期间 Server 保持 `LEGACY_SCAN`，所有 Worker 升级完成后才能统一切到 `OUTBOX`。
- 回滚：停止新增传输，将所有 Server 改为 `LEGACY_SCAN` 并重启；保留 Outbox 和游标表，不允许扫描与 Outbox 双推，也不自动删除事件数据。
- 2026-08-12 迁移安全补强：`StudioSchemaUpgradeService` 和 `20260812-file-transfer-event-outbox.sql` 对空的残缺 Outbox/消费者游标表先删除再完整重建；非空表一旦缺少 `tenant_id`、`project_id`、`run_id`、`event_type`、`occurred_at` 或 `instance_id`、`last_event_id`、`last_seen_at` 等关键身份列，明确拒绝自动升级并保留原表，避免错误归属或丢失历史事件。`FileTransferSchemaIntegrationTest` 新增两个 SQLite 反例，迁移测试共 5 项通过；部署文档与数据库升级指南已同步。无业务接口、表字段或重启要求变更，真实 MySQL 已完成启动升级、表/索引健康核验和重复迁移检查。
- 2026-08-13 W7 真实环境验收：当前 Server `18080` 和 Worker `18081` 均为 `UP`，统一 Web 入口 `http://127.0.0.1:8000/dfs/data-aggregation-studio/` 返回 `200`，未使用 `5173`。Server HA 健康详情确认 `fileTransferEventMode=OUTBOX`、Outbox 可用、七个索引完整、`fileTransferOutboxCursorLag=0`，并记录本实例最大事件 ID、游标、最近消费和清理时间。MySQL 中两张 Outbox/消费者游标表可读，启动升级已完成。
- 2026-08-13 统一入口 SSE 重连验收：首次连接收到 `SNAPSHOT_REQUIRED` 和事件 ID `2087614951562043394`；携带 `Last-Event-ID=2087614951562043393` 重连后真实补发事件 ID `2087614951562043394`，未退化为快照。新增 `FileTransferPublicRouteContractTest` 的 HTTP Header 透传断言，Java 17 下 4 项全部通过。
- 2026-08-13 双 Server HA 验收：临时 Server B 使用独立 `instanceId=studio-server-ha-b` 与 Server A 共享 MySQL；两实例从同一 `Last-Event-ID` 补发同一 `RUN_CHANGED` 事件 `2087612919757312002`，各自消费者游标独立推进。Server B 已停止，未改变 Server A 运行。
- 2026-08-13 事件量验收：真实 4 GiB FTP→OSS Run 的 Outbox 统计为 `ITEM_CHANGED=18`、`RUN_CHANGED=40`、合计 58 条，没有按 8 MiB 传输块产生数十万条事件。
- 2026-08-13 Outbox 延迟与热路径收口：直连 Server 和统一 `8000` 入口的初始总耗时都约 2 秒，排除 Nginx 为主因。线程转储确认默认唯一调度线程阻塞在每 250 ms 执行的游标查询、空轮询游标更新和全范围 `count(*)` 滞后统计。现为 Outbox 消费/心跳/清理配置两个专用调度线程，按作用域缓存本实例游标；空轮询只执行一次 `(tenant_id, project_id, id)` 索引读取，不更新游标、不查询最大 ID、不执行滞后计数；游标保活和精确积压统计移至 15 秒心跳，批次满载时仍立即计算真实积压。
- 2026-08-13 统一入口 P95 证据：12 个创建/删除隔离样本全部清理。创建事务自身 P95 为 `2080.2 ms`；从创建响应返回到 SSE 到达的 P95 为 `440 ms`；按 Outbox `occurredAt` 到统一入口 SSE 到达计算的端到端 P95 为 `949 ms`，满足不超过 1 秒的 W7 目标。Controller 同时设置 `Cache-Control: no-cache`、`X-Accel-Buffering: no` 和 `Connection: keep-alive`，路由契约测试覆盖响应头和 `Last-Event-ID` 透传。
- 2026-08-13 最终 HA/重放复验：临时 Server B 使用独立 `instanceId=studio-server-ha-2` 与主 Server 同时收到同一 `run-created` 事件且 SSE ID 完全一致；断开连接后创建事件，再携带旧 `Last-Event-ID` 重连，真实补发更新事件且 ID 单调递增。临时运行均已删除，临时 Server 已关闭。最终主 Server/Worker 为 `UP`，HA 为 `UP`，Outbox 可用、七个索引完整、游标滞后为 `0`，专用调度线程已运行。
- 2026-08-13 最终自动化证据：Outbox、事务、Schema、单集群、Worker 恢复、健康、SSE 路由和调度器共 14 个定向测试类、92 项测试全部通过；`FileTransferEventServiceOutboxTest` 13 项包含空轮询不写游标、不查询最大 ID或滞后计数的回归。前端队列回归、`vue-tsc --noEmit` 和 4331 模块生产构建通过，`git diff --check` 通过。
- 2026-08-13 完成审计补充：按验收文档的宽范围命令重新执行文件传输、非结构化管理、公共异常、Worker 资源版本和事件回归，共生成 31 个测试报告、164 项测试，结果为 0 失败、0 错误、0 跳过。首次执行发现 `FileTransferPreviewExecutorTest` 的 Mockito 文件系统未声明 SPI 必备的 `StorageCapabilities`，导致测试桩返回 `null`；已仅为测试桩补充大小写敏感和 SHA-256 能力声明，生产代码、接口和数据库均未改变，定向测试及宽范围套件重跑通过。前端队列回归、`vue-tsc --noEmit` 和 4331 模块生产构建再次通过，无需重启 Server、Worker 或 Frontend。
- 2026-08-12 Worker 重启断点恢复：明确“旧 Dispatch 执行所有权失效”不等于“文件传输 Run 或断点失效”。Worker 启动或执行线程被中断时，先以 CAS 关闭旧 Dispatch 和旧运行记录，再在同一短事务中将非暂停、非取消、非完成的 Run 置回 `QUEUED`、追加 `RUN_CHANGED` Outbox，并创建新的 `FILE_TRANSFER` Dispatch；checkpoint、目标 `.part`、源快照和文件项保持不变。新 Worker 只有在运行/文件项/源目标身份、源快照、临时路径、确认偏移和 `.part` 长度全部匹配后才从 `confirmedOffset` 继续。`PAUSED` 保持暂停，`CANCELED` 和已完成运行不恢复，校验失败明确失败而不盲目覆盖。
- 2026-08-12 真实重启证据：同一 4 GiB FTP→OSS 运行连续经历两次 Worker 重启，两次都关闭旧 Dispatch、创建新 Dispatch 并自动启动；第二次恢复仍保留 `3,472,883,712 / 4,294,967,296` 字节，Run 和文件项立即显示 `resumedBytes=3,472,883,712`、`resumedFiles=1`，两个用户主动暂停的 SFTP 文件项没有被自动拉起。当前 SHA-256 完整性实现不会重复写入目标端已确认字节，但源端不能恢复摘要内部状态时，会重新读取断点前缀以重建整文件摘要；恢复准备阶段消息明确为“断点已恢复，正在重建校验摘要”，实时传输速度不把历史断点字节计入。核心续传测试 11 项、Worker/事务/重启恢复测试 23 项均为 0 失败；无接口、数据库或 SQL 变化，需要重启 Worker。

## 11. 测试与验收记录

- 2026-08-14 非结构化上传与 ZIP 下载：公共端新增上传前 `stat`、原始 `application/octet-stream` 单文件上传和归档下载接口；上传要求固定 `Content-Length`，缺失返回 411，冲突返回 409 并由页面确认覆盖。单个普通文件继续走既有原文件下载，单选目录或多选文件/目录由 Worker 递归流式生成 ZIP，保留空目录、分页目录和共同祖先相对路径，父目录选中时去除重复后代。Server 仅执行 ACL/项目/集群/数据源校验和流转发，文件内容仍由 Worker 通过 `TransferFileSystem` 访问；未新增表、字段或 Liquibase，上传与归档复用 `unstructured_op_audit` 的 `UPLOAD`、`DOWNLOAD_ARCHIVE` 操作类型。
- 2026-08-14 自动化验证：Java 17 下 `RuntimeDatasourceProbeExecutorFileOperationTest`、`RuntimeEndpointHttpClientTest`、`UnstructuredManagementOperationAuditTest`、`UnstructuredManagementControllerContractTest`、`InternalDatasourceProbeControllerTest` 覆盖上传临时提交/覆盖/abort、固定长度流式转发、ZIP 递归分页/空目录/去重、公共 411/归档响应和 Worker 内部身份协议。Web SDK 和非结构化页面增加上传进度、覆盖确认、下载分流与重复提交保护，并通过 `npm run build:web` 验证。完整命令和最终数量以同日验收记录为准。
- 2026-08-14 原生下载优化：新增认证控制接口 `POST /api/v1/unstructured-management/download-tickets` 和唯一匿名数据接口 `GET /api/v1/unstructured-management/download/native`。Server 在签发与消费时分别执行路径、文件状态和 `DOWNLOAD` ACL 校验；票据由 32 字节安全随机值生成，Redis 只保存 SHA-256 摘要，120 秒有效并通过原子 `GETDEL` 只能消费一次。Redis 不可用时返回 `503`，不使用进程内存降级。消费时以票据中的用户、租户和项目重建执行上下文，签发后的权限撤销、文件删除或类型变化会被二次校验发现。
- 2026-08-14 原生下载链路：非结构化页面只等待票据创建，随后用临时 `<a>` 发起普通浏览器 GET，删除页面内 Blob、Object URL 和文件大小相关内存占用；旧 `/download`、`/download/archive` 及 SDK Blob 方法继续兼容，旧单文件 SDK timeout 调整为 `0`。单文件保留准确 `Content-Length` 和浏览器百分比，实时 ZIP 不预生成、不落盘且无总长度；当前不支持 Range、断点续传或票据重用。
- 2026-08-14 下载超时与代理：Server/Worker 只对下载路径关闭 Servlet 异步总超时，其他接口保留全局 5 分钟；Server 到 Worker 使用 30 分钟响应字节读取空闲超时。WSL `nginx-local` 增加精确原生下载 location，保持 `proxy_pass http://host.docker.internal:18080`，关闭缓冲/缓存并配置 30 分钟读写空闲超时；专用日志不记录查询串。`nginx -t`、reload 和 `nginx -T` 均已确认生效。
- 2026-08-14 原生下载自动化：Java 17 下票据、运行时 HTTP 流、非结构化文件操作与审计、Controller 契约、精确匿名安全边界和 Worker 内部接口定向套件共 57 项通过，0 失败、0 错误（Infra 33、Server 16、Worker 8）；Web 静态回归、`vue-tsc --noEmit` 和 Vite 生产构建通过（4331 个模块）。覆盖票据长度、哈希存储、过期/重放、Redis 故障、消费时上下文重建与二次校验、公共接口响应头、成功体直接流转、读取空闲超时、错误体上限、仅原生 GET 匿名放行，以及页面不再使用 Blob。未新增数据库表、字段、索引、SQL 或 Liquibase。

完整命令、测试类、真实运行证据、页面交互和残余风险记录在 [`studio-file-transfer-acceptance-record-20260807.md`](../测试/文件传输/studio-file-transfer-acceptance-record-20260807.md)。原 OSS/FTP 小样本聚焦验收已通过；新增 4 GiB SFTP 扩展矩阵仍在执行。本次没有把正在运行或尚未执行的项目表述为绿色，也没有把未执行的 Studio 全仓非文件传输测试表述为绿色。

## 12. 风险、阻塞与回滚

| 项目 | 风险/阻塞 | 处理 | 状态 |
|---|---|---|---|
| 历史跨集群记录 | 旧记录可能缺少可验证的单集群身份 | 只读展示，重试/恢复前强制拒绝 | 自动化通过 |
| 插件能力差异 | FTP/SFTP/OSS 对移动、删除和目录语义不同 | FTP/OSS 真实通过；SFTP/MinIO 保留部署环境矩阵 | 部分环境待验收 |
| ACL 兼容 | 历史数据源没有创建者 | 空值仅管理员可维护；真实路径作用域和在线字段通过 | 已验证 |
| 实时列表 | SSE 断线、重连和事件顺序 | 事件/快照恢复，无轮询；正常断连不写错误响应 | 已验证 |
| Outbox HA | 多 Server 独立游标、断线补发、事务回滚、事件清理和 P95 | 自动化、真实 MySQL 健康、统一入口 P95/重连、双 Server 同事件投递和 4 GiB 事件量均已取得证据 | 已完成；生产负载和滚动发布仍属部署扩展验收 |
| 数据库升级 | MySQL/SQLite/在线脚本不一致 | 三套结构和 20260807/20260809 增量脚本做 schema 测试 | 已验证 |
| 生产规模 | 10 GiB、10 万小文件和反向代理参数依赖部署环境 | 作为发布环境扩展验收，不扩大当前功能边界 | 待环境验收 |
| 日志依赖提示 | 启动时 `spring-jcl` 提示类路径仍存在 `commons-logging.jar` | 不影响本次传输结果；后续按依赖树单独治理，避免与功能修复混改 | 待依赖治理 |
| SFTP 旧服务端算法 | 个别服务端可能只提供 SHA-1、ssh-rsa、CBC 或 HMAC-MD5 | 默认保持现代算法；仅对确认无法升级的单个数据源显式开启 `allowLegacyAlgorithms`，并在服务端升级后关闭 | 已提供兼容开关，待矩阵验收 |
| 4 GiB SFTP 吞吐 | 当前真实环境单条 SFTP 传输约为数百 KiB/s，完整四方向矩阵耗时较长 | 保持任务运行并以接口字节计数和 UI 进度交叉核对；完成前不判定成功 | 进行中 |
| 重启恢复摘要重建 | checkpoint 保存了目标确认偏移，但标准 JCA SHA-256 摘要内部状态未持久化；恢复时可能重新读取源端断点前缀 | 不重复写目标；页面显示已恢复字节和恢复校验消息。后续如需缩短超大文件恢复准备时间，应单独评审可版本化、可校验的摘要状态持久化 | 功能已验证，性能待扩展优化 |

回滚原则：先停止新入口和调度，再回滚应用代码；保留新增字段、表和审计记录，禁止删除历史传输记录。必要时隐藏前端菜单并保留只读查询。

## 13. 变更审批与确认记录

| 日期 | 确认事项 | 结论 | 记录 |
|---|---|---|---|
| 2026-08-09 | 取消跨集群文件传输和 Server Relay | 已确认 | 用户需求 |
| 2026-08-09 | 单集群、单 Worker、共享运行集群选择 | 已确认 | 用户需求 |
| 2026-08-09 | 增加非结构化管理和 ACL | 已确认 | 用户需求 |
| 2026-08-09 | SSE 长连接、队列手动删除、无默认数据源 | 已确认 | 用户需求 |
| 2026-08-09 | FTP 测试数据源纳入真实操作验收 | 已确认 | 用户补充 |
| 2026-08-12 | 不引入新中间件，使用 MySQL Outbox 实现多 Server SSE HA | 已确认 | 用户实施计划 |
| 2026-08-12 | Worker 意外退出或重启后应自动复用有效断点继续传输；旧 Dispatch 关闭仅用于隔离执行所有权 | 已确认并实现 | 用户确认 |

## 14. 最终完成标准

单集群模型、历史兼容、非结构化管理、ACL、前端交互、原 OSS/FTP 小样本和 W7 MySQL Outbox 高可用实现/本地环境验收已经完成。W6 仍需完成 4 GiB 四方向终态、SHA-256、断点恢复、三源下载/修改/删除、精确文件 ACL、队列与指标复核；这些是独立的大文件业务矩阵，不能用 Outbox 验收替代。完成 W6 残余项目并通过最终构建和 `git diff --check` 后，整体文件传输验收目标才能标记完成。

## 15. W8：低速实时进度与 Worker 失活接管

- 实施时间：2026-08-13；状态：代码与自动化已完成，真实 4 GiB 环境复验中。
- 低速进度：`TransferEngine` 在一个 8 MiB 块内部按 `checkpointIntervalMillis`（默认 1 秒）发出实时观测事件。`observedBytes` 表示当前进程已经从源流读取并交给目标写入调用的字节，只用于当前页面进度和速度观测；`transferredBytes` 与 checkpoint 继续只表示目标端完成整块写入后的确认偏移。
- 指标语义：文件项速度按相邻实时观测样本计算；运行级 `currentBytesPerSecond` 和 `peakBytesPerSecond` 可以使用实时观测增量，运行累计 `transferredBytes` 仍使用确认字节。首个实时样本可为 `0 B/s`，第二个及后续样本开始形成有效速度；终态当前速度仍归零。
- SSE/前端：Outbox 的 `ITEM_CHANGED` payload 只携带 `live`、`confirmedBytes`、`observedBytes` 和 `liveBytesPerSecond`。Server 只对仍为 `TRANSFERRING` 且确认偏移未超过事件基线的文件项叠加实时值，防止迟到事件覆盖终态；队列和运行详情使用实时字节显示，不增加轮询。
- Worker 重启恢复：孤立运行不再把任意 `QUEUED/RUNNING` Dispatch 都视为有效所有者。当前实例旧 `bootId` 可立即接管；其他实例只有在对应 `worker_lease` 不在线或心跳/租约失效后才可接管；无实例身份的历史 Dispatch 仍以其 Dispatch 租约为保守边界。
- 围栏与幂等：失活 `RUNNING` Dispatch 使用 `id + status + claimToken + workerBootId + workerGroupCode + leaseOwner + workerInstanceId + leaseExpiresAt` 快照进行 CAS 围栏。只有 CAS 成功后才把 Run 置回 `QUEUED` 并创建新 Dispatch；并发恢复或旧 Worker 状态变化会使 CAS 失败并等待下一轮，不产生第二个执行所有者。
- 断点安全：恢复不修改 `checkpointJson`、确认偏移、源快照或目标临时文件。新执行仍由传输核心校验 checkpoint 与临时目标长度后从 `confirmedOffset` 继续；块内 `observedBytes` 不会被持久化为断点。
- 自动化结果：Java 17 下核心 `TransferEngineLocalIntegrationTest` 12 项通过；Worker 恢复和实时指标 29 项通过；Outbox/SSE/状态门面 22 项通过；Web 队列回归、`vue-tsc --noEmit` 和生产构建通过；`git diff --check` 通过。
- 数据库/接口影响：无新增表、字段、索引或 SQL；无公共接口路径变化。文件项视图新增可选 `observedBytes`、`live`，旧前端可忽略。需要重启 Worker、Server 并重新发布 Web 才能启用完整链路。
- 残余验收：需在统一 `8000` 入口以真实低速 4 GiB 传输观察至少两个实时样本，并在活动传输中重启 Worker，确认旧 Dispatch 被围栏、新 Dispatch 自动认领、确认偏移继续增长且最终摘要一致。
- 2026-08-13 低速与恢复边界补强：新增 `activityBytes` 作为速度和活动状态的累计口径。它包含断点校验阶段重新读取源文件前缀和恢复后当前块的读取活动，但不推进 `transferredBytes`、不写入 checkpoint，也不改变页面可恢复基线；断点恢复后的块完成事件继续携带累计活动值，避免速度基线回退到确认偏移。
- 2026-08-13 取消语义补强：断点校验回调因 `InputStream` 接口限制包装为 `IOException` 时，传输核心沿 cause 链识别 `TransferCanceledException`，最终文件项和运行结果保持 `CANCELED`，不进入普通失败重试；取消仍按既有策略清理临时目标和 checkpoint。
- 2026-08-13 定向验证：Java 17 下 `TransferEngineLocalIntegrationTest` 最终 15 项、Worker `FileTransferNodeExecutorRetryTest` 9 项、`StudioTransferEventListenerTest` 8 项和 `WorkerLifecycleRunnerClusterTest` 23 项全部通过；前端队列回归、`vue-tsc --noEmit` 和 4331 模块生产构建通过；`git diff --check` 通过。没有新增数据库表、字段、索引、SQL、公共接口路径或响应结构，不改变结构化采集任务；发布需要重启 Worker、Server 并重新发布 Web。
- 2026-08-13 暂停任务重启一致性：暂停命令现在在同一短事务中将 Run 和活动文件项统一置为 `PAUSED`，并把 Run/文件项当前速度归零；Worker 收到暂停后的迟到 `TRANSFERRING` 事件不能覆盖暂停状态。为兼容修复发布前留下的历史状态，Worker 启动和定时所有权协调遇到 `PAUSED` Run 时，会把残留的 `QUEUED/DISCOVERING/TRANSFERRING/VERIFYING/COMMITTING/POST_ACTION` 文件项归一为 `PAUSED` 并追加 Outbox 事件，但不会创建恢复 Dispatch。归一化不修改 `checkpointJson`、`transferredBytes`、源快照或目标临时文件，用户后续点击恢复仍从确认断点继续。
- 2026-08-13 真实页面复验：Server `18080`、Worker `18081` 均为 `UP`，统一 `8000` 入口返回 `200`，`5173` 未监听。Run `2087201024718897154` 重启前历史文件项错误显示 `3.23 GiB / 4 GiB`、`348 KiB/s`、`传输中`；加载修复并经过一个协调周期后，页面显示相同确认进度、`0 B/s`、`已暂停`，恢复按钮保持可用。未点击恢复、取消或删除，未创建新 Dispatch，checkpoint 和 `.part` 临时目标均保留。归一化先以 `status=PAUSED` 条件更新锁定 Run；并发恢复先赢时安全退出，归一化先赢时恢复等待事务提交后继续。`FileTransferRunRemovalTest` 12 项与上述 Worker 40 项合计 52 项通过，0 失败、0 错误；本次无数据库更新。
- 2026-08-13 schema v2 快速恢复：文件传输核心保存可恢复 SHA-256 状态及头部/边界轻量指纹，默认 FAST 恢复不再完整重读大文件前缀。旧断点或不可信断点自动清理后从零传输，STRICT 仅作为显式兼容模式；最终摘要失败时清理后只进行一次从零重传。Studio SSE/DTO/页面区分恢复校验进度和校验速度，监听器在 `RESTARTED_FROM_ZERO` 时清零旧进度统计。无数据库结构变化，不影响结构化采集任务；需重启 Worker、Server 并发布 Web 后生效。
- 2026-08-13 schema v2 最终自动化复验：Java 17 下 `TransferEngineLocalIntegrationTest` 18 项和 `FileTransferCliTest` 3 项通过；Studio 的 `FileTransferEventServiceOutboxTest`、`FileTransferStateMutationTransactionTest`、`FileTransferNodeExecutorRetryTest`、`StudioTransferEventListenerTest`、`WorkerLifecycleRunnerClusterTest` 合计 61 项通过，均为 0 失败、0 错误。Web 文件传输队列回归、`vue-tsc --noEmit` 和生产构建通过，`git diff --check` 通过。最终版 `file-transfer-core`、`file-transfer-plugin`、`file-transfer-cli` 已安装到本地 Maven 仓库。本轮没有重启 Server、Worker 或 Web，没有暂停、恢复、取消、删除或清理 Run `2087201024718897154`，也没有修改其 checkpoint 或 `.part`；该历史任务仍是 schema v1，必须在新版本发布后用新产生的 schema v2 checkpoint 单独执行真实 4 GiB 快速恢复复验。
- 2026-08-13 schema v2 第一次真实接管：新 4 GiB SFTP 到 FTP 运行在 Worker 重启后由新 Dispatch 实际领取，但恢复消息为 `temporary target offset did not match checkpoint; transfer restarted`，确认进度发生回退。新 Worker 随后产生包含 `confirmedOffset`、`checksumStateOffset`、`BC-SHA256-ENCODED-V1`、头部指纹和边界指纹的完整 schema v2 checkpoint。
- 2026-08-13 schema v2 第二次真实接管：重启前 checkpoint 的确认偏移为 `620,756,992` Byte，重启后仍因临时目标长度大于确认偏移而从零开始。根因是异常退出时目标 `.part` 可能已经多写入一个尚未确认的块内尾部，旧实现却要求物理长度严格等于 durable checkpoint；这会错误丢弃有效 v2 checkpoint。
- 2026-08-13 未确认尾部对齐修复：文件传输专用 `TransferFileSystem` 增加 `recoverWriteSession(...)` 能力，不修改结构化采集继续使用的 `FileHelper` 接口及语义。Local 和 SFTP 将 `.part` 截断到确认偏移；FTP 使用 REST 从确认偏移覆盖未确认尾部；临时目标短于 checkpoint、长于源文件或适配器无法安全对齐时继续从零重传；OSS/MinIO 未声明该能力，保持安全回退。
- 2026-08-13 自动化回归：新增 `fastResumeDiscardsOnlyTheUnconfirmedTemporaryTail`，验证物理临时目标长于 checkpoint 时只丢弃未确认尾部、从确认偏移续传并得到正确最终内容。Java 17 下 `TransferEngineLocalIntegrationTest` 共 19 项通过，FTP/SFTP 适配器及依赖模块打包成功；运行时 Local、FTP、SFTP 插件包已同步。无数据库表、字段、索引或 SQL 变化。
- 2026-08-13 修复后真实快速恢复：重启前 schema v2 checkpoint 确认偏移为 `889,192,448` Byte；新 Worker 接管后消息为 `checkpoint restored; transfer resumes from confirmed offset`，恢复字节为 `905,969,664`，确认进度继续增长到至少 `1,015,021,568` Byte，未回零且未进入 `RESTARTED_FROM_ZERO`。统一 `8000` 页面随后连续取样从约 `1.20 GiB / 30%` 增长到 `1.23 GiB / 31%`，速度从瞬时 `41.9 KiB/s` 回升到 `312 KiB/s`，状态保持“传输中”，证明 Worker 正在执行且 SSE 持续更新。
- 2026-08-13 环境结论：schema v2 快速恢复关键路径已通过真实 4 GiB Worker 重启复验；Server、Worker 均为 `UP`，本地集群在线 Worker 数为 `1`，Web 仅使用统一 `8000` 入口。该 4 GiB 运行尚未到终态，最终 SHA-256、checkpoint 清理、临时 `.part` 提交、终态速度归零、峰值保留和队列 Tab 自动迁移仍待运行完成后复核；四方向大文件矩阵仍保持“进行中”。
- 2026-08-14 最终目标校验进度：`TransferFileSystem.checksum` 增加兼容的进度回调重载，仍使用固定 64 KiB 缓冲回读目标文件。`TransferEngine` 在 `VERIFYING` 阶段按 `checkpointIntervalMillis` 发布 `verificationPhase=TARGET_CHECKSUM`、`verificationBytes`、`verificationTotalBytes` 和累计 `activityBytes`；完成事件不受 Studio 一秒持久化节流。`transferredBytes` 和 checkpoint 保持已确认写入值，不把目标回读误计为再次传输。Worker、Outbox/SSE、SDK、队列和运行详情沿用可选字段展示独立“目标校验”进度及校验速度；Server 只给仍为 `VERIFYING` 的文件项叠加实时值，迟到事件不能覆盖 `COMMITTING` 或终态。
- 2026-08-14 FTP 根目录双斜杠修复：问题不是纯展示。旧 `TransferPaths.join("/", "file")` 生成 `//file`，该值会进入传输计划、`file_transfer_run_item.target_path`、`.part` 临时路径和 FTP 插件逻辑入参；FTP 适配器在解析远程物理路径时会再次规范化，所以历史任务通常仍写入正确物理文件。现从公共路径拼接源头对结果重新规范化，根目录文件统一为 `/file`，Local、FTP、SFTP、MinIO、OSS 共用该修复，并补充公共路径与 FTP 登录目录映射回归测试。无数据库表、字段、索引、SQL 或 Liquibase 变化。
- 2026-08-14 校验进度与根目录路径自动化：Java 17 下核心、Local 和 file-transfer 插件全链路 40 项通过；目标校验、路径大小写和 FTP 登录目录定向套件 32 项通过；Studio Infra/Worker 定向套件 36 项通过。Web 文件传输队列回归、`vue-tsc --noEmit` 和 4331 模块生产构建通过，两层仓库 `git diff --check` 通过。真实 FTP 页面复验需要在发布新版 Worker、Server 和 Web 后执行，未把尚未运行的真实环境用例表述为通过。

## 16. W9：文件传输分级校验策略

- 实施时间：2026-08-15；状态：代码和定向自动化已完成，五类真实数据源矩阵待发布后复验。
- 策略模型：即时运行和预设任务统一在既有 `policy` 中增加 `verificationMode`、`verificationFrameCount`、`verificationFrameSizeBytes`。模式为 `NONE/PARTIAL/STRONG`，历史缺失字段固定补为 `STRONG`；默认部分校验为 `16 × 1 MiB`，帧数限制 `1..64`，帧大小限制为 `64 KiB..4 MiB` 且按 `64 KiB` 对齐。
- 采样算法：按运行 ID、文件项、源身份、源快照和采样配置生成稳定种子，将文件划分为 n 个区段，每区段选择一个不重叠随机帧。相同运行在重试和 Worker 重启后位置稳定，不同运行位置变化；摘要使用 `PARTIAL-SHA256-V1:<hex>` 并复用现有摘要字段。采样配置总量覆盖文件或文件为空时自动提升为强校验。
- 执行语义：`STRONG` 保持全文件 SHA-256；`PARTIAL` 在传输前读取源样本、传输后读取目标临时文件相同样本；`NONE` 不比较内容摘要，但继续执行长度、源快照、目标并发变化和断点身份检查。已有正式目标仅在强摘要一致或同大小采样指纹一致时跳过；不校验直接执行冲突策略。`NONE` 只允许保留源文件，Server 与 Worker 均拒绝删除或备份。
- 并发与后处理：所有模式保存并复查原目标 size、mtime、etag；强校验和部分校验还按对应计划复查原目标内容指纹。`POST_ACTION_FAILED` 重试复用原运行的完整摘要或稳定采样计划，不重复写入或提交目标。
- 断点安全：所有模式继续保存并验证 checkpoint 文件头和确认边界的轻量 SHA-256 指纹；只有强校验维护可恢复的完整摘要状态。部分校验和不校验不会因关闭完整摘要而削弱断点身份检查，也不会把采样读取计入确认传输字节。
- 数据源覆盖：Local、FTP、SFTP、MinIO、OSS 均已具备 `openRead(path, offset, length)` 且声明 `rangeRead=true`，本次没有修改五类插件生产代码。未来数据源不支持范围读取时，部分校验明确返回 `FILE_TRANSFER_PARTIAL_VERIFY_UNSUPPORTED`，不静默降级；FTP 每帧使用一次 `REST/RETR`，需单独关注往返成本。
- Studio 与 Web：任务保存、校验、发布、触发和即时入队共用策略规范化；运行文件项返回配置/有效模式和采样参数。Outbox/SSE 新增 `TARGET_SAMPLE` 与模式字段，迟到事件仍不能覆盖提交中或终态。即时策略弹窗、预设任务编辑器、任务列表、队列和运行详情均支持三档选择、参数联动、自动提升提示与采样指纹展示。
- 数据库与发布：不新增表、字段、索引、SQL 或 Liquibase，继续使用 `policy_json`、`resolved_spec_json` 和现有摘要字段。生产发布需要更新 `studio-worker`、`studio-server` 和 Studio Web；Local、FTP、SFTP、MinIO、OSS 插件包无需因本功能重新发布。
- 定向自动化：Java 17 下文件传输核心 41 项通过；Studio 策略规范化、运行 DTO 推导、Outbox/SSE、Worker 事件和后处理重试共 43 项通过，均为 0 失败、0 错误。Web 文件传输队列静态回归、`vue-tsc --noEmit` 和 4331 模块生产构建通过。真实五类数据源互传、FTP 采样成本和 MinIO multipart 临时目标采样仍需在发布环境执行，未表述为已通过。

## 17. W10：非结构化管理与文件传输日志增强

- 实施日期：2026-08-15；状态：已完成。
- 实现边界：继续使用既有 `SLF4J + Logback` 和五个标准级别；没有新增日志门面、Appender、日志表、日志接口、中间件、数据库变更、公共 API 字段或前端轮询，也没有修改 Logback Pattern、`StudioCapturedMdcDenyFilter`、传输状态、checkpoint、Outbox、SSE、ACL 或文件操作语义。
- 运行日志关联：`FileTransferNodeExecutor` 通过现有 `TransferEngine` task decorator 同时绑定 `PluginRuntimeSession` 和执行线程 MDC；并行 `file-transfer-*` 子线程继承 `runLogId`、`runLogPath`，结束后恢复线程原上下文。文件传输过程日志继续进入当前 `runRecordId` 对应的运行日志归档，不重复写入 Worker 主日志。
- 生命周期日志：增加 `FT_DISPATCH_START/COMPLETED/FAILED`、`FT_PLAN_START/COMPLETED`、`FT_RUN_START/PARTIAL/COMPLETED`。失败阶段固定为 `LOAD_RUN`、`RESOLVE_SPEC`、`PLAN`、`TRANSFER`、`PERSIST_RESULT`、`FINISH`；完整异常栈只在调度生命周期入口输出一次。
- 文件项日志：增加开始、恢复、摘要重建、回零、进度、校验、提交、暂停/恢复、重试、冲突、后置动作失败和终态事件码。单文件进度最多每 30 秒一条，运行汇总最多每 60 秒一条；终态、恢复决策、暂停、冲突和失败不受限频。进度明确区分 `confirmedBytes`、`observedBytes`、`activityBytes`、`resumedBytes` 和 `currentBps`。
- checkpoint/Outbox/SSE：checkpoint 加载、保存和删除只使用 `DEBUG`，不打印 checkpoint JSON、checksum state 或临时物理定位；非空 Outbox 批次使用 `DEBUG`，有效 replay 使用 `INFO`，过期/超限/缺口和发送失败使用 `WARN`，实际清理数大于零才使用 `INFO`。250 ms 空轮询、heartbeat、每个传输块和单条 Outbox 行不产生日志。
- 非结构化关联：Server 为浏览、stat、写操作、上传和实际下载生成 UUID，通过内部可选头 `X-Studio-Operation-Id` 传到 Worker。Worker 在请求和异步流线程内临时写入 MDC 并在 `finally` 恢复；该值只接受 64 字符以内的 `[A-Za-z0-9._-]+`，不参与认证、幂等、ACL 或业务响应。
- 非结构化级别：浏览和 stat 成功使用 `DEBUG`；mkdir、rename、move、delete、上传和下载使用 `INFO`；ACL 拒绝、路径不存在、目标冲突、非空目录未确认和权限不足等预期拒绝使用 `WARN`；网络、协议、插件及不可预期异常使用 `ERROR`。Server 与 Worker 显式打印相同 `operationId` 便于串联。
- 安全边界：只记录运行/操作身份、集群/数据源 ID 和类型、规范化逻辑路径、状态、字节、速度、耗时和异常类型。禁止记录密码、Token、Secret、AccessKey、PrivateKey、Authorization/Cookie、完整数据源配置、请求体、checkpoint JSON、checksum state、Worker 内部地址和对象存储凭据。`StudioSensitiveLogSanitizer.sanitizeSingleLine(...)` 统一执行敏感字段替换、首行截取、控制字符移除和长度限制；Worker 执行层、内部 Controller 响应边界和 Server 领域日志均使用该保护。Server 消息最多 2 KiB，不可预期异常栈脱敏后最多 12 KiB，审计摘要最多 1,800 字符；Worker 继续使用既有 `SanitizingPatternLayoutEncoder`。
- SFTP 权限拒绝：SFTP 插件按协议状态码识别 `SSH_FX_PERMISSION_DENIED`，使用无堆栈 `WARN`，并避免旧 `AggregationException` 把 cause 堆栈拼入业务消息。Worker 将权限拒绝记录为 `UF_OPERATION_REJECTED`，不误报 `UF_WORKER_FAILED`；未知网络或插件异常仍保留脱敏后的 `ERROR` 堆栈。内部文件操作和上传错误响应再次执行单行脱敏，错误码和 HTTP 行为保持不变。
- 自动化结果：Java 17 下日志增强广覆盖回归 Infra 64 项、Worker 33 项通过；最终边界回归包含 SFTP 插件 7 项、Studio Commons 3 项、Studio Infra 27 项和 Worker 内部 Controller 12 项，共 49 项，全部 0 失败、0 错误。`GlobalExceptionHandlerTest` 11 项通过；Worker 全模块仅保留两个与本次改动无关的历史 `WorkerSchedulingConfigurationTest` 夹具缺失问题，未将其表述为全量绿色。
- 发布影响：不需要数据库升级或前端发布；需要重启 Studio Server 和 Studio Worker 才能启用新增日志。回滚只需回滚本工作包 Java 代码并重启，不处理数据库或历史运行记录。
- 真实传输日志验收：持续超过 65 秒的大文件传输已产生 30 秒文件进度和 60 秒运行汇总；暂停/恢复、两次 Worker 重启后的 checkpoint 接管、`FT_RESUME_ACCEPTED`、SSE 自动更新和取消终态均已取得证据。运行日志 MDC 并行隔离自动化通过。
- 真实非结构化验收：OSS、FTP、SFTP 的浏览和写操作均已覆盖，ACL 拒绝已验证。SFTP 真实根目录无权限创建返回兼容的 `400/BUSINESS_ERROR` 和单行脱敏消息；Server 与 Worker 使用相同 `operationId`，两端均为 `UF_OPERATION_REJECTED/WARN`，SFTP 插件为无堆栈权限 `WARN`，没有 `UF_WORKER_FAILED` 或业务堆栈。
- 环境与安全验收：Server、Worker 健康状态均为 `UP`，运行集群在线 Worker 数为 1，统一 Web 入口返回 200；浏览器页面为默认租户/默认项目，文件传输中心正常渲染且控制台无新增错误或警告。前端继续通过流式 `/file-transfer/runs/events`、`Last-Event-ID` 和 `ReadableStream` 消费 SSE，文件传输代码中不存在列表 `setInterval`。应用日志抽取 228 条 `FT_*`/`UF_*` 事件，凭据赋值、checkpoint JSON、checksum state、内部认证头、技术元数据和物理 endpoint 六类扫描均为 0 命中。
- 清理与残余风险：通过 Studio 非结构化接口重新浏览并确认 5 个日志验收隔离目录；底层共享目录经 3 次明确递归删除后，OSS、FTP、SFTP 三个视图的该前缀剩余数均为 0。启动日志仍有既有 Spring JCL/Commons Logging 类路径警告，属于依赖治理项，不影响本工作包验收；W6 的完整 4 GiB 四方向业务矩阵继续独立保持“进行中”。

## 18. W11：传输队列清理与运行历史分离

- 实施日期：2026-08-16；状态：已完成。
- 缺陷根因：传输中心“清理已完成”和运行级“移出队列”直接调用 `DELETE /api/v1/file-transfer/runs/{runId}`；该兼容接口会逻辑删除 `file_transfer_run` 及其文件项，而运行记录页读取同一运行表，因此历史记录同步消失。
- 数据模型：`file_transfer_run` 增加 `queue_visible`，MySQL 使用 `int not null default 1`，SQLite 使用 `integer not null default 1`。新运行显式初始化为可见，历史运行通过默认值保持可见；同步更新 MySQL/SQLite 新库 Schema、启动在线升级和幂等脚本 `20260816-file-transfer-queue-visibility.sql`，不新增索引或表。
- 接口语义：新增 `DELETE /api/v1/file-transfer/runs/{runId}/queue`，只将即时运行移出队列并追加 `RUN_REMOVED` Outbox，不删除运行、文件项、指标、日志、checkpoint 或文件结果。原 `DELETE /{runId}` 保留真正的逻辑删除语义。`GET /runs` 新增可选 `queueOnly=true`，传输中心使用该条件，运行记录页保持无条件历史查询。
- 状态边界：终态运行可直接隐藏；排队运行先取消未领取 Dispatch，确认没有 Worker 已领取后转为 `CANCELED` 并隐藏；`RUNNING/PAUSED` 或存在已领取 Dispatch 时拒绝隐藏。被隐藏的失败运行执行恢复或文件项重试后重新设置为队列可见。
- SSE 与前端：传输中心“清理已完成”和运行级操作改用 `runs.dismiss`，继续由 SSE `RUN_REMOVED` 移除队列行；运行记录页收到同一事件后重新执行未过滤的历史查询，不再把队列移除当作历史删除。`LEGACY_SCAN` 回滚模式也只扫描队列可见运行，避免隐藏记录被旧扫描重新加入队列；未增加轮询。
- 自动化结果：JDK 17 下 `FileTransferRunRemovalTest`、`FileTransferSchemaIntegrationTest`、`FileTransferEventServiceTest`、`FileTransferEventServiceOutboxTest` 共 39 项通过，Server 公共路由 4 项通过，全部 0 失败、0 错误。Web 文件传输队列回归通过，独立 `vue-tsc --noEmit` 和 4,331 模块生产构建通过，`git diff --check` 通过。
- 环境结果：发布前只读查询确认活动文件传输为 0；重启 Server/Worker 后健康状态均为 `UP`，唯一运行集群在线 Worker 数为 1，统一 `8000` 入口返回 HTTP 200。新版 `queueOnly=true` 查询成功，证明当前 MySQL 字段升级已生效。环境没有可用于非破坏性操作验证的既有运行，未人为创建或删除业务运行；接口事务和前端行为由上述自动化覆盖。
- 发布与回滚：本次需要升级数据库、重启 Server 并发布 Web；Worker 同步重启后恢复为在线。回滚代码前可将队列查询暂时停止传入 `queueOnly`，保留 `queue_visible` 字段和历史值，不删除该列；旧版本会忽略新增字段。
