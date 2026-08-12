# Studio 非结构化文件传输接口联调指南

> 适用范围：Studio Web、外部管理端和联调测试调用文件浏览、即时传输、预设任务、运行明细与文件传输指标接口。
>
> 实现基线：2026-08-09 单集群 Worker-only 改造。

## 1. 公共约定

- 所有面向 Studio 用户的文件传输接口都使用 `/api/v1` 前缀。
- 响应统一使用 `Result<T>`，成功判断字段为 `success`，业务数据位于 `data`。
- 分页统一使用 `PageView<T>`，数组字段为 `data.items`，不是 `content`、`records` 或顶层数组。
- 请求继续使用 Studio 的 Bearer Token、租户和项目上下文：`Authorization`、`X-Tenant-Id`、`X-Project-Id`。
- ID 是 `Long`，JavaScript 调用端应按 `string | number` 处理，不应假设始终可安全转换为 IEEE-754 整数。
- 浏览和文件选择只返回元数据，不通过公共接口返回文件内容或数据源凭据。

成功响应示例：

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "pageNo": 1,
    "pageSize": 20,
    "total": 1,
    "items": []
  }
}
```

## 2. 文件浏览

### 2.1 浏览目录

`POST /api/v1/file-transfer/browser/list`

```json
{
  "runtimeClusterId": 11,
  "datasourceId": 101,
  "path": "/incoming/finance",
  "cursor": null,
  "pageSize": 200
}
```

返回 `FileTransferBrowserPageView`：

```json
{
  "success": true,
  "data": {
    "path": "/incoming/finance",
    "nextCursor": null,
    "pageSize": 200,
    "hasMore": false,
    "entries": [
      {
        "path": "/incoming/finance/20260807.csv",
        "name": "20260807.csv",
        "directory": false,
        "size": 1048576,
        "modifiedAtMillis": 1786032000000,
        "etag": "optional-etag"
      }
    ],
    "capabilities": {}
  }
}
```

Server 会校验数据源可执行状态、项目可用运行集群和数据源适用范围，再将浏览请求路由到对应 Worker。Server 不加载文件插件，也不直接连接 Local、FTP、SFTP、MinIO 或 OSS。

### 2.2 单源非结构化管理

非结构化管理用于在一个运行集群内对单个文件数据源执行浏览和文件操作。运行集群、数据源和目录均由调用端显式选择；未选择数据源时不加载目录。

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/unstructured-management/sources?runtimeClusterId={id}` | 查询当前项目中适用于运行集群的 Local、FTP、SFTP、MinIO、OSS 数据源 |
| `POST` | `/api/v1/unstructured-management/browser/list` | 单源分页浏览，响应为 `FileTransferBrowserPageView` |
| `GET` | `/api/v1/unstructured-management/download` | 仅下载文件，响应为 `application/octet-stream` 流，不包裹 `Result<T>` |
| `POST` | `/api/v1/unstructured-management/operations` | 新建目录、重命名、同源移动、文件/目录删除 |
| `GET` | `/api/v1/unstructured-management/permissions` | 查询当前用户在数据源路径上的有效权限 |

文件操作请求示例：

```json
{
  "runtimeClusterId": 11,
  "datasourceId": 101,
  "operation": "MOVE",
  "sourcePath": "/incoming/a.csv",
  "targetPath": "/archive/a.csv",
  "recursiveConfirmed": false
}
```

操作枚举为 `CREATE_DIRECTORY`、`RENAME`、`MOVE`、`DELETE`。服务端统一规范化路径并拒绝 `..`、NUL 和越界路径；重命名必须保持同一父目录，移动只允许同一数据源，目标存在时拒绝覆盖；根目录不可删除，非空目录删除必须显式传递递归确认。Worker 执行真实文件操作，Server 只负责鉴权和流式转发。成功和失败变更操作都会写入 `unstructured_op_audit`；Worker 返回业务错误时保留原始错误消息，失败审计不随该运行时异常回滚。

OSS 目录判空会忽略仅用于表示当前目录的 marker，并在 marker 占满当前截断页时继续读取下一页。只有当前页没有文件、子目录或公共前缀且 `truncated=false` 时，目录才被证明为空；若返回截断但没有可见条目，则按“无法证明为空”失败关闭，未确认递归删除必须拒绝。

ACL 接口：

```text
GET    /api/v1/unstructured-management/acl/source/{datasourceId}
PUT    /api/v1/unstructured-management/acl/source/{datasourceId}
GET    /api/v1/unstructured-management/acl/path?datasourceId={id}&path={path}
PUT    /api/v1/unstructured-management/acl/path
DELETE /api/v1/unstructured-management/acl/{id}
GET    /api/v1/unstructured-management/users/options
```

ACL 仅允许当前项目成员作为授权对象。创建者和管理员始终绕过 ACL；普通成员默认允许 `BROWSE`、`DOWNLOAD`，`EDIT`、`DELETE` 默认关闭。路径权限按最具体路径计算，目录规则递归作用于后代。

即时入队、预设任务校验与执行、定时触发、工作流定时触发和工作流下游续跑使用同一 ACL 边界：源路径要求 `DOWNLOAD`，目标路径要求 `EDIT`。后台执行不会使用空白线程上下文或隐式管理员权限，而是按任务/工作流创建人或原始触发用户重新加载当前租户、项目和有效角色；账户被禁用、用户被移出项目或权限被撤销后，后续触发会被拒绝。

## 3. 即时传输与运行

### 3.1 创建并自动开始即时运行

`POST /api/v1/file-transfer/runs/manual`

```json
{
  "items": [
    {
      "runtimeClusterId": 11,
      "sourceDatasourceId": 101,
      "sourcePath": "/incoming/finance/20260807.csv",
      "targetDatasourceId": 103,
      "targetPath": "/archive/finance/20260807.csv",
      "recursive": false
    }
  ],
  "policy": {
    "conflictPolicy": "FAIL",
    "sourceSuccessAction": "KEEP",
    "checksumAlgorithm": "SHA-256",
    "skipIdentical": true
  },
  "runtime": {
    "concurrency": 4,
    "retryCount": 3
  },
  "parameters": {},
  "autoStart": true
}
```

已确认的固定语义：

- 摘要算法为 SHA-256。
- 正式目标摘要一致时统一跳过。
- 源端默认保留；删除或备份只能在目标校验和正式提交成功后发生。
- 单文件选择的 `targetPath` 是最终目标文件完整路径；目录选择的 `targetPath` 是目标目录路径，目录下发现的文件会继续保留相对路径。
- 目录执行时展开为叶子文件项；当前文件传输不复制空目录本身。手工选择的路径没有发现任何可传输文件时，运行进入 `FAILED` 并返回 `FILE_TRANSFER_NO_FILES_DISCOVERED`，不得报告零文件成功。
- 一个运行的 `runtimeClusterId` 固定，源和目标数据源必须适用于同一个集群。跨集群请求统一返回 `FILE_TRANSFER_CROSS_CLUSTER_DISABLED`，历史跨集群记录仅可查询。
- 即时运行不绑定预设任务，因此返回的 `taskId` 可以为空；Worker 执行时以 `fileTransferRunId` 作为必需身份，预设任务和工作流运行仍会携带 `taskId`。
- FTP/SFTP 数据源的登录目录在传输接口中映射为逻辑根 `/`，浏览返回和递归发现始终使用逻辑路径，不能把物理登录目录再次拼接到自身。目标路径冲突按目标文件系统能力判断大小写：Local 跟随 Worker 操作系统，SFTP/MinIO/OSS 默认大小写敏感；FTP 默认大小写不敏感，可在数据源连接配置中使用 `pathCaseSensitive=true` 覆盖。

### 3.2 运行接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/file-transfer/runs/manual` | 创建即时运行，可自动开始 |
| `POST` | `/api/v1/file-transfer/runs/{runId}/items` | 在仍为 `QUEUED` 的即时运行中增加文件项 |
| `GET` | `/api/v1/file-transfer/runs` | 分页查询运行，支持 `pageNo/pageSize/taskId/status/triggerType/statusGroup` |
| `GET` | `/api/v1/file-transfer/runs/{runId}` | 查询运行详情 |
| `GET` | `/api/v1/file-transfer/runs/{runId}/items` | 分页查询文件项 |
| `POST` | `/api/v1/file-transfer/runs/{runId}/pause` | 请求暂停 |
| `POST` | `/api/v1/file-transfer/runs/{runId}/resume` | 恢复并重新入队 |
| `POST` | `/api/v1/file-transfer/runs/{runId}/cancel` | 请求取消并清理不可复用临时状态 |
| `POST` | `/api/v1/file-transfer/runs/{runId}/items/{itemId}/retry` | 精确重试单个失败文件项或仅重试后处理 |
| `DELETE` | `/api/v1/file-transfer/runs/{runId}` | 将即时传输运行及其文件项逻辑移出队列 |
| `DELETE` | `/api/v1/file-transfer/runs/{runId}/items/{itemId}` | 将即时传输中的非活动文件项逻辑移出队列 |
| `GET` | `/api/v1/file-transfer/runs/events` | 以 SSE 长连接推送队列和运行变化 |

运行由所选集群的单个 Worker 执行，服务端不参与文件中继。历史 `channel`、源/目标集群字段仍可在旧记录中返回用于兼容，但新列表不展示这些字段。

运行视图和文件项视图除保留内部定位所需的 ID 外，还返回以下展示字段：

- `sourceDatasourceName`
- `targetDatasourceName`

Studio 页面不得使用集群 ID 或数据源 ID 作为正常展示。传输队列、运行列表和运行明细统一按“从 / 到”表达文件去向：`[数据源名称] 源路径`、`[数据源名称] 目标路径`。不展示方向列或集群名称。

运行列表的展示名称使用触发时间片段和简短触发类型，例如 `08-09 01:03 · 即时`、`08-09 02:15 · 定时`；完整运行 ID 仍保留在接口和日志定位能力中。

`triggerType` 为可选的精确过滤参数，服务端按大写枚举值匹配。`statusGroup` 只接受 `ACTIVE` 或 `TERMINAL`：`ACTIVE` 对应 `QUEUED/RUNNING/PAUSED`，`TERMINAL` 对应 `SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELED`，其他值返回 `BAD_REQUEST`。传输中心使用 `triggerType=MANUAL` 分页读取全部活动运行，并单独读取最近 10 个终态运行；两个查询期间发生状态迁移时按运行 ID 合并，以较新的终态快照覆盖活动快照。不得扫描全部历史记录，也不得先截取混合触发类型的首屏数据再在前端过滤。

### 3.3 队列删除与 SSE 长连接

传输中心首次进入时可以读取一次当前队列，之后通过 `GET /api/v1/file-transfer/runs/events` 接收服务端事件；前端不得使用定时器持续轮询运行详情或文件项。事件使用 `text/event-stream`，主要事件如下：

| 事件名 | `data.type` | 说明 |
|---|---|---|
| `snapshot` | `SNAPSHOT_REQUIRED` | 连接建立后要求客户端完成一次初始列表加载 |
| `run-changed` | `RUN_CHANGED` | 运行摘要变化，`run` 携带最新运行视图，`items` 携带本次变化的文件项 |
| `item-removed` | `ITEM_REMOVED` | 文件项已逻辑删除，携带 `runId`、`itemId` 和当前运行视图 |
| `run-removed` | `RUN_REMOVED` | 运行已从队列逻辑移除 |
| `heartbeat` | 无业务数据 | 保活事件，客户端不应触发列表查询 |

SSE 由租户和项目隔离。Worker 直接更新共享运行库后，Studio Server 会检测变化并通过该长连接推送；服务端动作（创建、暂停、恢复、取消、重试、删除）会立即发出对应事件。每次连接或重连收到 `SNAPSHOT_REQUIRED` 时，客户端都必须重新同步快照；如果快照到达时列表仍在加载，则在当前加载结束后再执行一次同步。断线时客户端持续进行封顶退避重连，不得退化为 `setInterval` 轮询。客户端离开页面、网络断开或 event-stream 异步请求超时后，Server 将已提交/event-stream 的 I/O 和 `AsyncRequestTimeoutException` 视为正常断连，不再尝试把 JSON `Result` 写入 `text/event-stream`；普通非流式 I/O 异常仍返回统一 500，普通非流式异步超时返回 `SERVICE_UNAVAILABLE`。

删除语义：

- 只允许删除 `MANUAL` 即时传输运行，不允许通过队列删除接口修改预设任务或工作流审计运行。
- `QUEUED` 运行删除时会先把尚未领取的 Dispatch 置为 `CANCELED`，确认没有已被 Worker 领取的 `RUNNING` Dispatch 后，再逻辑删除运行和文件项；若领取竞争已发生，则拒绝删除并要求先取消运行。
- `RUNNING`、`PAUSED` 运行必须先调用取消接口；`SUCCESS`、`PARTIAL_SUCCESS`、`FAILED`、`CANCELED` 可直接移除。
- 文件项只允许删除非活动状态；删除不会物理清理运行指标和通用审计记录。

## 4. 预设任务

### 4.1 接口列表

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/file-transfer-tasks` | 分页查询，支持 `keyword/status` |
| `GET` | `/api/v1/file-transfer-tasks/{id}` | 查询任务详情 |
| `POST` | `/api/v1/file-transfer-tasks` | 创建草稿 |
| `PUT` | `/api/v1/file-transfer-tasks/{id}` | 更新任务并产生新配置版本 |
| `DELETE` | `/api/v1/file-transfer-tasks/{id}` | 删除允许删除的任务 |
| `POST` | `/api/v1/file-transfer-tasks/{id}/validate` | 校验数据源、集群、策略和调度配置 |
| `POST` | `/api/v1/file-transfer-tasks/{id}/preview-selection` | 由源 Worker 执行真实文件选择预览 |
| `POST` | `/api/v1/file-transfer-tasks/{id}/publish` | 发布任务 |
| `POST` | `/api/v1/file-transfer-tasks/{id}/offline` | 下线任务 |
| `POST` | `/api/v1/file-transfer-tasks/{id}/trigger` | 手工触发已发布任务 |

### 4.2 保存请求

```json
{
  "name": "财务日报归档",
  "code": "FINANCE_DAILY_ARCHIVE",
  "runtimeClusterId": 11,
  "sourceDatasourceId": 101,
  "targetDatasourceId": 103,
  "selection": {
    "basePath": "/incoming/finance",
    "includePatterns": ["**/*.csv"],
    "excludePatterns": ["**/*.tmp"]
  },
  "mapping": {
    "targetBasePath": "/archive/finance/{yyyyMMdd}",
    "preserveRelativePath": true
  },
  "policy": {
    "conflictPolicy": "BACKUP_THEN_OVERWRITE",
    "sourceSuccessAction": "KEEP",
    "checksumAlgorithm": "SHA-256",
    "skipIdentical": true
  },
  "runtime": {
    "concurrency": 4,
    "retryCount": 3
  },
  "schedule": {
    "enabled": true,
    "cronExpression": "0 15 2 * * ?",
    "timezone": "Asia/Shanghai"
  }
}
```

`selection`、`mapping`、`policy`、`runtime` 和 `schedule` 是版本化任务配置。触发时后端生成解析快照，并把目标集群和资源修订绑定到 Dispatch；Worker 不应使用前端展示快照代替数据库中的权威版本。

## 5. 工作流集成

工作流节点类型为 `FILE_TRANSFER`。节点配置保存 `fileTransferTaskId` 和展示快照，工作流发布与运行时由后端校验：

- 任务必须为 `ONLINE`。
- 任务目标 Runtime Cluster 必须与工作流 Runtime Cluster 一致。
- Dispatch 保存 `_resourceRevision`，配置变更后旧 Dispatch 不会静默执行新配置。
- 工作流节点最终复用任务触发、运行记录、文件项和指标链路，并遵循单集群校验。

## 6. 指标

### 6.1 专用文件传输指标

`POST /api/v1/file-transfer-metrics/dashboard`

请求可包含：`taskId`、`runtimeClusterId`、`datasourceId`、`channel`、`status`、`startedAt`、`endedAt` 和 `topN`。

请求时间的标准格式为 `yyyy-MM-dd HH:mm:ss`：

```json
{
  "startedAt": "2026-07-11 02:08:57",
  "endedAt": "2026-08-09 02:08:57",
  "topN": 10
}
```

为兼容已有调用端，后端也接受 ISO 本地时间分隔符 `T`，例如 `2026-07-11T02:08:57`；响应仍统一序列化为空格分隔格式。来源和目标数据源 TopN 的 `name/label` 返回数据源名称，不使用数据源 ID 作为展示文本；`count` 表示成功文件数，并包含因摘要一致而跳过的文件，不表示传输字节数。

返回内容包括运行数、文件结果、字节结果、当前/平均/峰值速度、预计剩余时间、活动文件、重试、续传、后处理失败、趋势点和来源/目标数据源 TopN。运行或文件项进入终态后，其 `currentBytesPerSecond` 固定归零；终态保存只更新终态汇总字段，不使用执行开始时的旧实体覆盖监听器已经持久化的 `peakBytesPerSecond`。最后一个指标采样点仍保留完成前实际计算出的吞吐速度，用于趋势和峰值统计。

### 6.2 通用运行指标

文件传输运行同时进入既有 `/api/v1/run-metrics` 体系。调用端可使用 `executionType=FILE_TRANSFER` 和运行状态筛选；专用页面应优先调用文件传输指标接口获取文件与字节口径。

## 7. 状态与错误处理

运行常见状态：`QUEUED`、`RUNNING`、`PAUSED`、`SUCCESS`、`PARTIAL_SUCCESS`、`FAILED`、`CANCELED`。

文件项常见状态：`DISCOVERING`、`QUEUED`、`TRANSFERRING`、`VERIFYING`、`COMMITTING`、`POST_ACTION`、`SUCCESS`、`SKIPPED`、`CONFLICT`、`POST_ACTION_FAILED`、`FAILED`、`PAUSED`、`CANCELED`。

调用端必须展示后端 `message`，并基于状态决定暂停、恢复、取消和重试按钮是否可用。`POST_ACTION_FAILED` 表示目标文件已经成功提交，只允许重试源端后处理，不能重复覆盖目标。

## 8. 安全边界

- 公共 API 不接收数据源明文密码、内部 Token 或任意 Worker 地址。
- 浏览、预览和传输前都校验租户、项目、运行集群授权和数据源适用范围。
- 文件操作和下载请求只接受受管运行集群、数据源和规范化路径，不接受任意 Worker 地址、票据或凭据。
- Server 不加载插件、不解密数据源凭据、不直接连接业务文件系统；文件访问和操作始终在单集群 Worker 完成。
