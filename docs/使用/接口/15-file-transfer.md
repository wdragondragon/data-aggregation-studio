# Studio 非结构化文件传输接口联调指南

> 适用范围：Studio Web、外部管理端和联调测试调用文件浏览、即时传输、预设任务、运行明细与文件传输指标接口。
>
> 实现基线：2026-08-09 单集群 Worker-only 改造；2026-08-12 MySQL Outbox 高可用事件分发。

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
| `GET` | `/api/v1/unstructured-management/stat` | 上传前检查目标路径；要求目标父目录 `EDIT` 权限 |
| `POST` | `/api/v1/unstructured-management/upload` | 向当前目录上传单个原始文件；要求目标父目录 `EDIT` 权限 |
| `GET` | `/api/v1/unstructured-management/download` | 仅下载文件，响应为 `application/octet-stream` 流，不包裹 `Result<T>` |
| `POST` | `/api/v1/unstructured-management/download/archive` | 递归下载单个目录或多个选择项，响应为 `application/zip` 流 |
| `POST` | `/api/v1/unstructured-management/download-tickets` | 校验选择项并签发 120 秒有效的一次性原生下载票据 |
| `GET` | `/api/v1/unstructured-management/download/native` | 使用票据发起浏览器原生文件或 ZIP 流下载 |
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

#### 上传前检查与单文件上传

上传前使用以下接口检查完整目标文件路径：

```http
GET /api/v1/unstructured-management/stat?runtimeClusterId=11&datasourceId=101&path=/incoming/report.csv
```

目标存在时返回 `Result<FileTransferFileEntryView>`；目标不存在时返回 HTTP `404`。该接口不是通用浏览权限探测，调用者必须拥有目标父目录的 `EDIT` 权限。

文件上传接口使用原始二进制请求体，不使用 multipart：

```http
POST /api/v1/unstructured-management/upload?runtimeClusterId=11&datasourceId=101&targetPath=/incoming/report.csv&overwrite=false
Content-Type: application/octet-stream
Content-Length: 1048576

<原始文件字节>
```

成功响应示例：

```json
{
  "success": true,
  "code": "SUCCESS",
  "data": {
    "operation": "UPLOAD",
    "targetPath": "/incoming/report.csv",
    "bytes": 1048576,
    "overwritten": false,
    "message": "Upload completed"
  }
}
```

联调流程如下：

1. 页面将文件名拼到当前浏览路径，形成完整 `targetPath`，先调用 `stat`。
2. `stat` 返回 `404` 时，以 `overwrite=false` 直接上传。
3. `stat` 返回成功时，页面提示覆盖或取消；确认后以 `overwrite=true` 上传。
4. 即使预检查返回不存在，提交期间仍可能发生并发写入。此时上传返回 HTTP `409`，页面应再次询问是否覆盖，再使用 `overwrite=true` 重试。
5. 上传成功后刷新当前目录并清空表格选择。

请求必须携带有效的 `Content-Length`，缺失时返回 HTTP `411 Length Required`。`overwrite=false` 且目标已存在时返回 HTTP `409 Conflict`。非法路径、目标为根目录、目标是已有目录或写入字节数不完整时返回业务错误。上传不设置应用层固定大小上限，但仍受反向代理、Servlet 容器、HTTP 客户端和具体数据源限制。浏览器 SDK 调用示例：

```ts
await studioApi.unstructuredManagement.upload(
  { runtimeClusterId: 11, datasourceId: 101, targetPath: "/incoming/report.csv", overwrite: false },
  file,
  (percentage) => { uploadProgress.value = percentage; },
);
```

Server 只校验租户、项目、集群、数据源绑定、路径和 ACL，并将请求流转发给目标 Worker；不会缓存完整文件，也不会落本地临时文件。Worker 通过 `TransferFileSystem.prepareWrite/append/commit` 写入同目录临时路径，失败时执行 `abort`，因此 Local、FTP、SFTP、MinIO 和 OSS 共用同一上传链路。成功和失败上传均以 `UPLOAD` 写入 `unstructured_op_audit`。

#### 单文件下载与 ZIP 归档下载

单选普通文件时继续使用原接口，不改变已有响应头和文件内容契约：

```http
GET /api/v1/unstructured-management/download?runtimeClusterId=11&datasourceId=101&path=/incoming/report.csv
```

该接口返回 `application/octet-stream`，保留原文件名并设置文件 `Content-Length`；传入目录仍会被拒绝。

单选目录，或多选任意文件/目录时使用：

```http
POST /api/v1/unstructured-management/download/archive
Content-Type: application/json

{
  "runtimeClusterId": 11,
  "datasourceId": 101,
  "paths": ["/reports", "/readme.txt"]
}
```

响应为流式 `application/zip`，不设置 `Content-Length`。单选目录的下载文件名为 `目录名.zip`，多选为 `download.zip`。调用前会对每个选择路径逐项校验 `DOWNLOAD` 权限；任一路径无权限、缺失或非法时整体失败，不产生部分归档。

ZIP 在 Worker 响应流上直接生成，Server 只转发响应流，二者都不落临时 ZIP，也不缓存完整文件或归档。目录使用分页 `listPage` 递归遍历，空目录也会写入条目；父目录和其后代同时被选中时只处理父目录。条目名相对于所有有效选择路径的共同祖先目录，例如 `/a/report.txt` 和 `/b/report.txt` 分别写为 `a/report.txt`、`b/report.txt`，不会因同名互相覆盖。归档下载成功或失败以 `DOWNLOAD_ARCHIVE` 写入 `unstructured_op_audit`。

上述两个旧接口继续保留，SDK 的 Blob 方法也保持兼容。当前非结构化管理页面不再用 Axios 等待完整 Blob，而是使用下面的票据和浏览器原生下载流程，页面内存不会随下载文件大小线性增长。

#### 原生浏览器下载票据

页面先通过正常认证的 JSON 控制请求创建票据：

```http
POST /api/v1/unstructured-management/download-tickets
Authorization: Bearer <studio-token>
X-Tenant-Id: 1
X-Project-Id: 2
Content-Type: application/json

{
  "runtimeClusterId": 11,
  "datasourceId": 101,
  "paths": ["/reports", "/readme.txt"]
}
```

成功返回 `Result<UnstructuredDownloadTicketView>`：

```json
{
  "success": true,
  "code": "SUCCESS",
  "data": {
    "ticket": "32-byte-random-base64url",
    "fileName": "download.zip",
    "archive": true,
    "contentLength": null,
    "expiresAt": "2026-08-14T02:02:00Z"
  }
}
```

Server 在签发时规范化、去重路径，并校验登录用户、租户、项目、运行集群、数据源、文件状态和所有选择项的 `DOWNLOAD` ACL。一个普通文件签发文件票据；一个目录或多个选择项签发归档票据。票据由 32 个随机字节生成，使用 Base64 URL 无填充编码，有效期固定为 120 秒。Redis 只保存原始票据的 SHA-256 摘要和受控下载上下文；创建或消费时 Redis 不可用均返回 `503`，不回退到 Server 本机内存。

取得票据后，用普通浏览器导航发起实际下载，不要通过 Axios、`fetch().blob()` 或 `URL.createObjectURL()` 接收文件：

```ts
const result = await studioApi.unstructuredManagement.createDownloadTicket({
  runtimeClusterId: 11,
  datasourceId: 101,
  paths: ["/reports", "/readme.txt"],
});
const url = new URL(
  resolveStudioApiBaseUrl("/unstructured-management/download/native"),
  window.location.origin,
);
url.searchParams.set("ticket", result.ticket);
const anchor = document.createElement("a");
anchor.href = url.toString();
anchor.style.display = "none";
document.body.appendChild(anchor);
anchor.click();
anchor.remove();
```

实际传输请求为：

```http
GET /api/v1/unstructured-management/download/native?ticket=<ticket>
```

只有这个精确 GET 路径允许匿名访问，不需要 Studio Cookie、Bearer Token、租户头或项目头；票据本身是短期下载凭证。票据在处理开始时由 Redis 原子消费，只能成功使用一次，过期、重放或不存在统一返回 `401`。Server 使用票据内身份重建执行上下文，并重新执行路径、文件类型和 ACL 校验，因此签发后撤销权限、删除文件或改变文件类型仍会阻止下载。校验失败、传输中断或用户取消后票据均不能复用，需要重新点击下载获取新票据。

普通文件响应为 `application/octet-stream`，携带准确 `Content-Length`，浏览器下载管理器可以显示确定的百分比。ZIP 仍由 Worker 实时生成并以 `application/zip` 流式返回，不预生成、不落盘且不设置 `Content-Length`，浏览器只能显示已接收字节或不确定进度。两种响应均设置 `Content-Disposition`、`Cache-Control: no-store`、`Referrer-Policy: no-referrer`、`X-Content-Type-Options: nosniff` 和 `X-Accel-Buffering: no`。当前不支持 `Range`、断点续传、暂停恢复或失败后复用票据。

下载按钮的 loading 只覆盖票据创建；浏览器 GET 发起后页面立即恢复可操作状态，后续进度和取消由浏览器下载管理器负责。响应流开始后的网络中断不会回到页面业务错误弹窗，浏览器会报告下载失败。

常见错误：

| HTTP 状态 | 场景 | 前端处理 |
|---|---|---|
| `400` | 路径非法、目标为目录、归档路径为空、票据格式错误或数据源不支持文件访问 | 展示后端 `message`；票据错误时重新点击下载 |
| `401` | 原生下载票据不存在、过期、已消费或已重放 | 重新创建票据并从头下载 |
| `403` | 缺少目标父目录 `EDIT` 或任一下载路径的 `DOWNLOAD` 权限 | 禁止继续提交并展示权限错误 |
| `404` | `stat` 或归档路径不存在 | 上传预检查的 `404` 表示可以尝试新建；归档 `404` 直接报错 |
| `409` | `overwrite=false` 时目标在提交阶段已存在 | 询问用户后以 `overwrite=true` 重试 |
| `411` | 上传请求缺少有效 `Content-Length` | 修复客户端或代理，禁止改用分块请求 |
| `503` | Redis 不可用、运行集群离线、Worker 不可达或内部流转失败 | 展示错误并允许用户稍后重试 |

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
- FTP/SFTP 数据源的登录目录在传输接口中映射为逻辑根 `/`，浏览返回和递归发现始终使用逻辑路径，不能把物理登录目录再次拼接到自身。根目录下的文件统一表示为 `/file.txt`，路径拼接必须消除重复分隔符，不能生成 `//file.txt`。目标路径冲突按目标文件系统能力判断大小写：Local 跟随 Worker 操作系统，SFTP/MinIO/OSS 默认大小写敏感；FTP 默认大小写不敏感，可在数据源连接配置中使用 `pathCaseSensitive=true` 覆盖。

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
| `GET` | `/api/v1/file-transfer/runs/events` | 以 SSE 长连接推送队列和运行变化；重连可携带 `Last-Event-ID` 请求头 |

运行由所选集群的单个 Worker 执行，服务端不参与文件中继。历史 `channel`、源/目标集群字段仍可在旧记录中返回用于兼容，但新列表不展示这些字段。

Worker 意外退出或重启时，旧 Dispatch 的终态只表示旧执行所有权已经关闭，不表示 Run 或 checkpoint 不可恢复。对非 `PAUSED`、非 `CANCELED`、非完成的运行，Worker 自动创建新 Dispatch；新执行验证 checkpoint、源快照和临时目标长度后从确认偏移继续。用户主动暂停的运行必须显式调用 `POST /api/v1/file-transfer/runs/{runId}/resume`，不会被 Worker 重启自动恢复。

恢复运行的 `transferredBytes` 保留确认偏移。schema v2 checkpoint 同时保存可恢复 SHA-256 状态、文件头和确认边界各最多 64 KiB 的 SHA-256 指纹；默认 `runtime.checkpointRecoveryMode=FAST` 只读取这两个小范围验证源身份，然后直接从 `confirmedOffset` 续传，不再完整重读源文件前缀。旧版 checkpoint、摘要状态损坏、指纹不一致、临时目标长度不一致时，FAST 模式会安全删除旧临时目标和 checkpoint，并从零重新传输；只有显式 `STRICT` 才对旧 checkpoint 重读前缀重建摘要。最终摘要仍不一致时会先清理无效断点，再自动进行且仅进行一次从零完整重传。

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
| `snapshot` | `SNAPSHOT_REQUIRED` | 首次连接、事件过期或补发数量超限时要求客户端完成一次列表快照加载 |
| `run-created` | `RUN_CREATED` | 新运行已创建，携带最新运行视图 |
| `run-changed` | `RUN_CHANGED` | 运行摘要变化，`run` 携带最新运行视图，`items` 携带本次变化的文件项 |
| `item-removed` | `ITEM_REMOVED` | 文件项已逻辑删除，携带 `runId`、`itemId` 和当前运行视图 |
| `run-removed` | `RUN_REMOVED` | 运行已从队列逻辑移除 |
| `heartbeat` | 无业务数据 | 保活事件，客户端不应触发列表查询 |

SSE 由租户和项目隔离。运行或文件项状态更新与 `file_transfer_event_outbox` 事件在同一短事务中提交；Server 默认每 250 ms 按本实例独立游标读取 Outbox，再批量加载最新安全视图推送给本机连接。事件 ID 同时出现在 SSE `id:` 和 `data.eventId`，必须按十进制字符串处理，不能转成 JavaScript `number`。

事件接口响应设置 `Cache-Control: no-cache`、`X-Accel-Buffering: no` 和 `Connection: keep-alive`。Outbox 消费使用独立调度线程；没有新事件时只执行一次索引读取，不重复写消费者游标或计算全表积压。`last_seen_at` 保活和精确 `cursor-lag` 由 15 秒心跳刷新，不改变 250 ms 的业务事件读取间隔。

首次连接不携带 `Last-Event-ID`，Server 发送带当前最大事件 ID 的 `SNAPSHOT_REQUIRED`。断线重连携带最近成功处理的 `Last-Event-ID` 后，Server 从该 ID 之后补发；请求 ID 已早于 7 天保留窗口、晚于当前最大 ID，或单次补发超过默认 5,000 条时改为快照恢复。客户端按事件 ID 去重并按 `runId/itemId` 幂等合并，只有监听器成功处理事件后才推进本地游标。心跳不带业务事件 ID，也不推进游标。

`Last-Event-ID` 是 HTTP 请求头，不是 query 参数。Controller 会将该值原样传递给事件服务；没有该请求头时传入 `null`。客户端和代理必须按十进制字符串保留雪花 ID，不能转换为 JavaScript `number`。服务端已通过 HTTP 路由契约测试验证 Header 透传和无 Header 的初次连接语义。

进度事件按运行或文件项默认最多每秒产生一条 Outbox 行；终态、创建和删除事件不受限频。一个批次中同一运行的变化会合并，单个 SSE 最多携带 200 个文件项，超过时按事件 ID 顺序拆分。断线时客户端持续进行封顶退避重连，不得退化为 `setInterval` 轮询。客户端离开页面、网络断开或 event-stream 异步请求超时后，Server 将已提交/event-stream 的 I/O 和 `AsyncRequestTimeoutException` 视为正常断连，不再尝试把 JSON `Result` 写入 `text/event-stream`；普通非流式 I/O 异常仍返回统一 500，普通非流式异步超时返回 `SERVICE_UNAVAILABLE`。

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

### 7.1 实时进度与确认进度

文件项响应可以包含以下可选字段：

```json
{
  "transferredBytes": 8388608,
  "observedBytes": 8650752,
  "live": true,
  "currentBytesPerSecond": 131072,
  "verificationPhase": "TARGET_CHECKSUM",
  "verificationBytes": 4194304,
  "verificationTotalBytes": 8388608
}
```

- `transferredBytes`：目标端已经完成整块写入并保存 checkpoint 的确认字节，是刷新、暂停和 Worker 重启后恢复的基线。
- `observedBytes`：当前 Worker 在正在写入的块内已经观察到的字节，只在 `live=true` 时用于实时页面展示；它可能大于确认字节，但绝不能当作断点。
- `currentBytesPerSecond`：文件项按相邻实时样本计算；运行级当前速度和峰值也可使用实时观测增量。首个样本可能为 `0 B/s`，后续样本形成有效速度。
- `activityBytes`：后端事件中的可选活动累计值，用于计算当前/峰值速度。严格模式兼容旧 checkpoint 时，它可以包含重新读取源端断点前缀的校验活动；它不推进确认进度、不写入 checkpoint。
- `resumePhase/resumeCheckedBytes/resumeTotalBytes`：严格恢复校验时分别表示阶段、已校验字节和待校验前缀总量；页面把速度标为“校验速度”，确认传输进度保持不变。新版 FAST checkpoint 正常恢复时使用 `RESUMING_TRANSFER`，无需展示长时间校验。
- `verificationPhase/verificationBytes/verificationTotalBytes`：文件写入完成后，Worker 在 `VERIFYING` 阶段从目标临时文件回读并计算 SHA-256；`TARGET_CHECKSUM` 表示目标校验阶段，页面应显示独立的“目标校验”进度和校验速度。该回读活动不增加 `transferredBytes`，也不推进 checkpoint。
- FTP 等不能直接返回远程 SHA-256 的数据源需要完整回读目标文件，因此大文件的 `VERIFYING` 会持续一段时间；进度默认约每秒经 Outbox/SSE 更新。校验完成后才进入 `COMMITTING`，摘要不一致则终止提交并按既有重试策略处理。
- 前端应优先显示 `live=true` 时的 `observedBytes`，否则显示 `transferredBytes`。终态和迟到事件不得继续显示实时字节。
- SSE 默认约每秒产生一次非终态进度事件，Server 约每 250 ms 检查 Outbox；页面不应增加轮询。实际显示延迟还包括数据库提交、SSE 推送和浏览器渲染时间。

### 7.2 Worker 重启恢复

Worker 重启不会删除文件项 checkpoint。旧 Dispatch 只代表旧执行所有权；确认其 Worker Lease 失活后，新的 Worker 会先 CAS 围栏旧 Dispatch，再创建恢复 Dispatch，并从 `confirmedOffset` 继续。其他 Worker 仍在线、心跳有效或 CAS 竞争失败时不会抢占。

正常关闭会立即把本 Worker Lease 标为离线；异常退出通常在 30 秒心跳窗口过期后进入恢复。页面期间可能短暂保持 `RUNNING`，随后收到 Outbox 状态事件变为 `QUEUED` 并再次进入 `RUNNING`。恢复过程不使用 `observedBytes`，只信任 checkpoint、源快照、临时目标路径和确认偏移。

用户已经明确暂停的 Run 不属于自动恢复对象。Worker 重启或所有权协调遇到 `PAUSED` Run 时不会创建恢复 Dispatch，只会将历史残留的活动文件项状态统一为 `PAUSED`，并将 Run 和文件项的 `currentBytesPerSecond` 归零。该归一化不修改 `checkpointJson`、`transferredBytes`、源快照或目标临时文件；用户后续调用 `POST /api/v1/file-transfer/runs/{runId}/resume` 后，才从确认断点继续执行。

## 8. 安全边界

- 公共 API 不接收数据源明文密码、内部 Token 或任意 Worker 地址。
- 浏览、预览和传输前都校验租户、项目、运行集群授权和数据源适用范围。
- 文件操作和下载请求只接受受管运行集群、数据源和规范化路径，不接受任意 Worker 地址、票据或凭据。
- Server 不加载插件、不解密数据源凭据、不直接连接业务文件系统；文件访问和操作始终在单集群 Worker 完成。
