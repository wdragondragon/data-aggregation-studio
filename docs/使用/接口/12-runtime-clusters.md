# 运行集群与数据源适用范围接口

## 通用约定

基础路径为 `${BASE_URL}/api/v1`。接口统一使用 `Result<T>`：`{ success, code, message, data, timestamp }`；项目上下文使用 `X-Tenant-Id`、`X-Project-Id`。集群管理、端点和项目授权只向超级管理员、租户管理员开放，普通项目成员只能通过业务编辑器读取当前项目已授权的集群选项。

端点 URL、Header 值、Token、数据源连接秘密和完整请求体不会出现在查询响应中。

Studio 只有一种显式运行集群语义。所有新增、保存和执行请求必须提交真实 `runtimeClusterId`；历史空值只能在发布窗口通过一次性迁移工具处理，正常运行时不会推断默认集群。

## 运行集群

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/runtime-clusters` | 管理员查询本租户集群。 |
| GET | `/runtime-clusters/options?projectId={id}` | 查询项目已授权且启用的轻量集群选项。 |
| GET | `/runtime-clusters/{id}` | 查询一个集群和实例摘要。 |
| GET | `/runtime-clusters/{id}/instances` | 查询实例摘要及服务端计算的在线状态。 |
| POST | `/runtime-clusters` | 新建或更新集群。创建后 `code` 不可修改。 |
| POST | `/runtime-clusters/{id}/enable` | 启用集群。 |
| POST | `/runtime-clusters/{id}/disable` | 禁用集群；不会迁移已定向队列。 |
| DELETE | `/runtime-clusters/{id}` | 删除已停用且没有在线实例、端点或业务引用的集群。 |
| GET | `/runtime-clusters/{id}/endpoints` | 查询端点脱敏视图。 |
| POST | `/runtime-clusters/endpoints` | 保存 Worker `HTTP/SLB` 端点。旧 `LOCAL` 记录只能查看、获得固定测试失败提示、停用和删除。 |
| POST | `/runtime-clusters/endpoints/{id}/test` | 测试端点并更新最近测试结果。 |
| DELETE | `/runtime-clusters/endpoints/{id}` | 删除已停用的端点。 |
| GET | `/runtime-clusters/project-authorizations?projectId={id}` | 查询项目授权。 |
| POST | `/runtime-clusters/project-authorizations` | 保存项目对集群的授权、首选及手动覆盖许可。 |
| POST | `/runtime-clusters/validations/query` | 查询当前项目失效的运行配置；支持按资源类型和资源 ID 集合过滤。 |

Controller 另保留 `POST /runtime-clusters/internal/heartbeat` 作为内部兼容入口，请求头必须携带 `X-Studio-Internal-Token`，Body 为 `{ tenantId, clusterCode, instanceId, version, summary }`。它不属于前端 SDK，也不应暴露给浏览器；当前标准 Worker 的主心跳直接写共享数据库，不通过该 HTTP 接口。

`GET /runtime-clusters/{id}` 返回的 `instances` 是心跳上报的实例摘要，不是静态部署清单。`GET /runtime-clusters/{id}/instances` 返回相同的实例结构，适合实例抽屉独立刷新。Worker 当前每 5 秒上报一次；服务端以 30 秒为心跳有效期，并每 10 秒刷新一次数据库中的集群 `OFFLINE` 状态。查询接口还会实时计算 `status`、`online` 和 `onlineInstanceCount`，前端不能自行推导在线状态：

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "id": 4601,
    "code": "46",
    "name": "46 集群",
    "enabled": true,
    "status": "ONLINE",
    "version": "2026.07",
    "preferred": false,
    "allowManualOverride": false,
    "lastHeartbeatAt": "2026-07-20T10:30:00",
    "onlineInstanceCount": 1,
    "instances": [
      {
        "instanceId": "studio-worker-46-0",
        "bootId": "boot-20260720-01",
        "workerGroupCode": "studio-worker",
        "version": "2026.07",
        "summary": "http://studio-worker-46:18081",
        "heartbeatAt": "2026-07-20T10:30:00",
        "status": "ONLINE",
        "online": true
      }
    ]
  },
  "timestamp": "2026-07-20T10:30:01"
}
```

`preferred` 和 `allowManualOverride` 只在 `/runtime-clusters/options` 的项目授权视图中具有业务含义；管理详情中通常为 `false`。`summary` 是实例上报的诊断摘要，当前 Worker 默认上报 `STUDIO_WORKER_API_BASE_URL`，前端不得依赖其格式做路由判断。

新建集群请求：

```json
{
  "code": "46",
  "name": "46 集群",
  "enabled": true,
  "version": "2026.07"
}
```

`code` 保存时会转换为大写，长度为 1 至 64 位，首位必须是字母或数字，其余可使用字母、数字、`_`、`-`。因此 `OMS`、`46`、`50` 都是有效编码；创建后不可修改。

保存 HTTP 端点请求：

```json
{
  "runtimeClusterId": 4601,
  "mode": "HTTP",
  "endpointUrl": "https://studio-46.internal",
  "connectTimeoutMillis": 3000,
  "readTimeoutMillis": 5000,
  "enabled": true
}
```

端点地址填写 Worker 服务的基础地址，服务端会自行追加 `/internal/runtime/**` 或 `/internal/runtime/datasource/**`，不要在基础地址后重复填写这些内部路径。端点可配置受管 Header 和 Token，但接口文档、日志及工单不得记录它们的实际值。Token 默认作为 Bearer Authorization 发送；若已配置 Authorization Header，则以 Header 配置为准。内部认证使用独立保留 Header，不能被端点配置覆盖。

HTTP 端点默认必须使用 HTTPS，并拒绝回环、链路本地、私网、本机接口、IPv6 过渡地址和云元数据地址。确需访问内网或本地 HTTP 时，由运维通过 `STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS` 按主机名或 IP 精确放行；不支持通配符。云元数据主机及地址不能通过白名单放行。保存、端点测试、数据源探测和同步代理都会重新解析并校验全部地址，实际请求固定使用已校验地址且不跟随重定向；校验通过前不会解密或发送端点 Header、Token、内部 Token 和业务请求体。

读取端点不会回显 `endpointUrl` 明文、Header 值或 `token`，只返回 `endpointMasked`、`headerNames` 和 `hasToken`。编辑已有端点时：

- `endpointUrl` 留空表示保留原地址，填写新值表示替换。
- `headers` 省略或为 `null` 表示保留原 Header；传空对象 `{}` 表示清空。
- `token` 留空表示保留；`clearToken=true` 表示清空。
- `connectTimeoutMillis`、`readTimeoutMillis` 有效范围均为 `100` 至 `60000` 毫秒。

所有同步数据面操作都通过目标集群的 Worker HTTP/SLB 端点执行，`DEFAULT-LOCAL` 与远端集群使用相同协议。端点测试用于上线验收和记录最近结果，不是运行时硬门槛。当前不做应用内负载均衡、自动切换或重试；多个 HTTP 端点同时启用时会按 ID 升序选择第一个，因此每个集群应只保留一个已启用端点，多 Worker 由 Service/SLB 负载均衡。

保存项目授权请求：

```json
{
  "projectId": 1001,
  "runtimeClusterId": 4601,
  "enabled": true,
  "preferred": true,
  "allowManualOverride": false
}
```

同一项目只保留一个 `preferred=true`。`allowManualOverride` 只控制手动触发时是否允许覆盖资源保存的集群，定时调度不使用覆盖值。

### 运行配置失效查询

该接口面向运维诊断，返回当前项目的失效记录，不返回正常资源：

```http
POST /api/v1/runtime-clusters/validations/query
Content-Type: application/json

{
  "resourceType": "COLLECTION_TASK",
  "resourceIds": [3001, 3002]
}
```

`resourceType` 和 `resourceIds` 均可省略。返回项字段如下：

| 字段 | 说明 |
|---|---|
| `projectId` | 资源所属项目。 |
| `resourceType` / `resourceId` / `resourceName` | 失效资源标识。 |
| `runtimeClusterId` | 资源当前保存的运行集群。 |
| `valid` | 本接口当前只返回 `false` 记录。 |
| `issueCode` / `issueMessage` | 稳定问题编码及可直接展示的原因。 |
| `details` | 不含秘密的补充证据。 |
| `validatedAt` | 最近校验时间。 |

资源列表和详情已经直接返回 `runtimeValid` 与 `runtimeValidationMessage`，页面不得为每一行重复调用该诊断接口。当前诊断接口没有前端 SDK 快捷封装，运维工具可按上述 HTTP 契约调用。

### 删除保护

- 集群必须先停用，且心跳有效期内不能仍有在线实例。
- Server 不具有本地运行集群身份。集群没有活跃引用并满足通用删除约束时可以删除；`DEFAULT-LOCAL` 不享有额外保护。
- 存在未删除端点、启用中的项目授权或数据源绑定、仍引用该集群的采集/质量/工作流/脚本/数据服务/接入/协议转换资源，以及排队或运行中的 Dispatch、模型同步任务时，删除会被拒绝。
- 历史运行记录和访问日志不会被级联删除；删除保护只阻止会造成当前配置或活动工作失效的引用。
- 端点必须先通过编辑将 `enabled=false`，再调用删除接口。这样可以防止一次点击直接移除当前正在使用的内部路由地址。
- 前端应直接展示后端返回的引用错误，不要替换成通用失败文案。

## 数据源适用范围

保存数据源时提交 `applicableClusterIds`，至少选择一个项目已授权集群：

```json
{
  "name": "生产 Oracle",
  "typeCode": "oracle",
  "applicableClusterIds": [4601, 5001],
  "technicalMetadata": {},
  "businessMetadata": {}
}
```

编辑数据源并移除适用集群前，先预览影响：

```http
POST /api/v1/datasources/2001/cluster-binding-impact
Content-Type: application/json

{
  "applicableClusterIds": [5001]
}
```

响应中的 `removedClusterIds` 表示被移除的集群，`affectedResources` 包含受影响资源的 `resourceType`、`resourceId`、`resourceName`、`runtimeClusterId`、`issueCode` 和 `issueMessage`。用户确认影响后，保存数据源时必须额外提交：

```json
{
  "id": 2001,
  "name": "生产 Oracle",
  "typeCode": "oracle",
  "applicableClusterIds": [5001],
  "confirmClusterBindingImpact": true,
  "technicalMetadata": {},
  "businessMetadata": {}
}
```

按运行集群加载数据源：

```text
GET /api/v1/datasources/options?runtimeClusterId=4601
```

返回数据源包含轻量 `applicableClusters`。数据源列表还返回 `clusterHealth`，每项包含集群标识、`AVAILABLE/UNAVAILABLE/UNKNOWN`、最近测试时间、脱敏消息、耗时、探测中/过期状态和下次探测时间。后端仍会在保存、发布、预览、调试、手动触发和同步调用时复核项目授权与数据源绑定，前端筛选不能替代服务端校验。

以下数据源接口同样必须明确运行集群：

| 方法 | 路径 | 集群参数 |
|---|---|---|
| POST | `/datasources/{id}/test` | Query `runtimeClusterId` |
| POST | `/datasources/test` | Query `runtimeClusterId`，Body 为当前数据源表单 |
| GET | `/datasources/{id}/connection-history` | Query `runtimeClusterId` |
| POST | `/datasources/{id}/discover` | Query `runtimeClusterId` |
| POST | `/datasources/{id}/discover-options` | Query `runtimeClusterId` |

连接状态按“租户 + 运行集群 + connection fingerprint”隔离。目标集群没有可执行实例时，探测返回 `UNKNOWN`；它表示当前无法探测，不表示已经确认连接不可用。

## 资源运行集群

采集、质量、工作流、脚本、数据服务、数据接入和协议转换的保存请求包含 `runtimeClusterId`。工作流版本也固化该值。切换值后客户端必须让用户确认，并清除不再适用的资源引用和预览缓存；若资源被标记为运行配置失效，不能发布、启用、触发或接收新的同步调用。

手动运行可携带目标集群覆盖，但只有项目显式允许覆盖，并且目标已授权、所有引用数据源均适用时才成功。定时运行不采用覆盖，永远使用资源保存的集群。

采集、质量和工作流手动触发使用可选 Body：

```json
{
  "runtimeClusterId": 4601
}
```

对应接口为：

- `POST /collection-tasks/{id}/trigger`
- `POST /quality-tasks/{id}/trigger`
- `POST /workflows/{id}/trigger`

省略 Body 表示使用资源保存的集群。`POST /data-development/scripts/{id}/execute` 同样允许在请求体中携带可选覆盖；未保存脚本执行和 SQL 即席执行必须明确提交 `runtimeClusterId`。所有保存 DTO 和列表/详情返回的完整字段仍以各业务模块接口文档为准。

OMS 同步代理的请求体上限由 `STUDIO_RUNTIME_INVOCATION_MAX_BODY_BYTES` 控制，默认 10 MiB。超过上限时返回 HTTP `413` 和 `PAYLOAD_TOO_LARGE`，不会把请求转发给目标集群。

目标端点响应体上限由 `STUDIO_RUNTIME_ENDPOINT_MAX_RESPONSE_BYTES` 控制，默认 10 MiB，服务端强制限制在 1 KiB 至 64 MiB。同步代理超限返回 HTTP `502`；管理调试返回明确失败；数据源探测记为目标不可用；端点测试记录失败。所有场景都会在上限处终止读取，不会把超大响应完整载入内存。

## 前端联调顺序

1. 调用 `/runtime-clusters/options` 加载当前项目选项；只有一个选项时自动选中并将选择器设为只读。
2. 用户选定 `runtimeClusterId` 后，再调用 `/datasources/options?runtimeClusterId=...`。
3. 数据源、模型或关联任务下拉在集群未选择前保持禁用。
4. 切换集群前要求用户确认；确认后清理不兼容的数据源、模型、字段映射、SQL 预览和运行参数缓存。
5. 保存、发布、调试和触发失败时直接展示后端 `message`，不要把集群授权、数据源不适用或运行配置失效错误替换成通用文案。

## 常见错误

| 后端消息 | 含义与处理 |
|---|---|
| `Runtime cluster management requires tenant administrator permission` | 当前用户不是超级管理员或租户管理员。 |
| `Runtime cluster code cannot be changed` | 集群编码创建后不可修改。 |
| `Disable the runtime cluster before deleting it` | 删除前先停用集群。 |
| `Only HTTP runtime endpoints are supported; migrate legacy LOCAL endpoints to a Worker HTTP/SLB endpoint` | 新建和编辑端点只支持 `HTTP`；旧 `LOCAL` 记录应迁移到 Worker HTTP/SLB 端点后停用并删除。 |
| `Legacy LOCAL runtime endpoints are no longer executable; configure and test a Worker HTTP/SLB endpoint, then remove this record` | 对旧 `LOCAL` 记录执行端点测试时固定失败；先配置并测试 Worker HTTP/SLB 端点，再清理旧记录。 |
| `Stop all runtime cluster instances before deleting it` | 仍有 30 秒有效期内的实例心跳，停止实例并等待状态刷新。 |
| `Remove runtime endpoints, project authorizations, datasource bindings and resource references before deleting this cluster` | 集群仍被当前配置或活动任务引用，先解除引用。 |
| `Disable the runtime endpoint before deleting it` | 先编辑端点并关闭启用状态。 |
| `Runtime cluster is disabled` / `Runtime cluster is unavailable` | 目标集群已停用或不在当前租户可用范围。 |
| `Runtime cluster is not authorized for this project` | 先在运行集群管理中授权项目。 |
| `Datasource is not applicable to the selected runtime cluster` | 调整数据源适用集群，或改选任务运行集群。 |
| `Removing datasource cluster binding affects {count} resource(s); confirmClusterBindingImpact is required` | 先调用影响预览，用户确认后携带确认字段保存。 |
| `Runtime cluster is required` | 保存、筛选或执行请求必须明确携带运行集群。 |
| `Target runtime cluster is unavailable` | 数据源探测目标没有在线实例/端点，或内部调用失败；对外调用通常返回 `503`。同步服务存在已启用端点时会直接尝试调用，不以最近端点测试状态作为门禁。 |
| `PAYLOAD_TOO_LARGE` | 同步代理请求体超过配置上限；缩小请求或由运维调整统一上限。 |
