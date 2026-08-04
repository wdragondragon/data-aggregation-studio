# 运维中心、运行时、导入导出和 AI 助手接口

覆盖运维中心查询、运行模式、导入导出模板、AI 助手能力/工具执行和流式会话。

## 界面范围

- `/ops-center` -> `frontend/apps/web/src/views/OpsCenterView.vue`

## 调用前置条件

- 管理端基础地址：`${BASE_URL}`，默认在线环境为 `http://127.0.0.1:18080/api/v1`。
- 认证头：`Authorization: Bearer ${TOKEN}`。
- 租户和项目上下文：大多数项目内接口需要 `X-Tenant-Id: ${TENANT_ID}` 与 `X-Project-Id: ${PROJECT_ID}`。
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页通常为 `PageResult<T>`：`{ pageNo, pageSize, total, items }`。

## 典型调用链

1. 运维总览：GET /ops-center/options -> POST /ops-center/overview/query -> 分别 POST /ops-center/runs/query、/queue/query、/workers/query、/service-events/query、/log-events/query。
2. 日志联动：从 /ops-center/runs/query 取 runRecordId -> GET /runs/{runRecordId}/log；从 service/ingestion/protocol 日志取 accessLogId -> GET /invocation-logs/{domain}/{accessLogId}。
3. 导出项目：GET /exports/project，返回 metaSchemas、workflows 等项目包；导入模板：GET /imports/template。
4. AI 助手：GET /assistant/config -> GET /assistant/operations -> POST /assistant/tools/execute；流式聊天使用 POST /assistant/chat/stream，Accept 为 text/event-stream。
5. AI 模型预览：`studio.feature.list` 查询 `/runtime-clusters` -> `studio.feature.list` 查询 `/models` 并携带 `runtimeClusterId/keyword` -> `studio.feature.action` 对 `/models` 执行只读 `preview`，提交 `id/runtimeClusterId` 和可选 `limit`。

## 2026-07-20 多集群增量契约

- `OpsCenterQueryRequest` 增加 `requestedClusterId/actualClusterId`。总览、运行、队列、服务、接入、协议转换和日志事件查询均复用这两个筛选条件；队列查询把请求集群解释为 `targetClusterId`。
- 新增 `POST /ops-center/protocol-conversion-events/query`，与数据服务、数据接入事件使用相同分页和集群筛选契约。
- 运行事件和日志事件返回 `requestedClusterId/actualClusterId/actualClusterCode`；队列项返回 `targetClusterId`；Worker 组摘要返回 `runtimeClusterIds/runtimeClusterCodes`；三类同步服务事件返回请求/实际集群。
- AI operation catalog 增加运行集群选项查询，并为数据源测试/发现/同步、七类资源保存、脚本执行和手动触发增加 `runtimeClusterId`。运行、运维和告警查询支持目标/实际集群参数。
- `assistant.script.execute` 属于未绑定持久化资源的即时执行，必须显式提交项目已授权的 `runtimeClusterId`；即使项目只有一个运行集群，前端也应自动选中后把真实 ID 放入请求，后端不再推断唯一或首选集群。
- 模型样例数据预览不再使用 `studio.feature.get view=preview`。操作目录把它声明为 `studio.feature.action` 的只读 `preview` 动作，必填 `path=/models`、`id`、`runtimeClusterId`，可选 `limit`，且 `mutation=false`。
- `/models` 的普通列表仍可不带集群；当助手要选择可预览或可执行 Flink SQL 的模型时，必须携带 `runtimeClusterId`，工具网关将其路由到集群范围的 `listSelectorOptions`。数据开发 SQL 数据源候选同样必须先确定集群再查询。
- 助手不能绕过项目授权、数据源适用范围或手动覆盖许可，也不能创建或修改包含运行端点秘密的配置。所有写操作仍要求确认。

运行集群管理接口独立记录在 [12-runtime-clusters.md](./12-runtime-clusters.md)。以上增量字段优先于下方历史自动抽取表中的旧参数摘要。

## 前端 SDK 接口清单

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 | 前端调用点 | 源码 |
|---|---|---|---|---|---|---|
| `assistant.capabilities()` | GET | `/assistant/capabilities` | - | `AssistantKnowledgeCapability[]` | - | `frontend/packages/api-sdk/src/client.ts:441` |
| `assistant.config()` | GET | `/assistant/config` | - | `AssistantConfigResponse` | `frontend/apps/web/src/layout/StudioLayout.vue:221` | `frontend/packages/api-sdk/src/client.ts:438` |
| `assistant.learn()` | POST | `/assistant/learn` | payload: AssistantLearnRequest<br>body: `payload` | `AssistantLearnResponse` | - | `frontend/packages/api-sdk/src/client.ts:453` |
| `assistant.operations()` | GET | `/assistant/operations` | - | `AssistantStudioOperation[]` | `frontend/apps/web/src/components/assistant/StudioAssistantDrawer.vue:595` | `frontend/packages/api-sdk/src/client.ts:444` |
| `assistant.skills()` | GET | `/assistant/skills` | - | `AssistantPortableSkill[]` | - | `frontend/packages/api-sdk/src/client.ts:447` |
| `assistant.executeTool()` | POST | `/assistant/tools/execute` | payload: AssistantToolExecuteRequest<br>body: `payload` | `AssistantToolExecutionResult` | `frontend/apps/web/src/components/assistant/StudioAssistantDrawer.vue:2333`<br>`frontend/apps/web/src/components/assistant/StudioAssistantDrawer.vue:2359`<br>`frontend/apps/web/src/components/assistant/StudioAssistantDrawer.vue:2391` | `frontend/packages/api-sdk/src/client.ts:450` |
| `exports.project()` | GET | `/exports/project` | - | `ExportProjectBundle` | - | `frontend/packages/api-sdk/src/client.ts:1944` |
| `imports.template()` | GET | `/imports/template` | - | `Record<string, unknown>` | - | `frontend/packages/api-sdk/src/client.ts:1939` |
| `opsCenter.queryIngestionEvents()` | POST | `/ops-center/ingestion-events/query` | payload?: OpsCenterQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `OpsCenterServiceEventView` | `frontend/apps/web/src/views/OpsCenterView.vue:425` | `frontend/packages/api-sdk/src/client.ts:1687` |
| `opsCenter.queryProtocolConversionEvents()` | POST | `/ops-center/protocol-conversion-events/query` | payload?: OpsCenterQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `OpsCenterServiceEventView` | `frontend/apps/web/src/views/OpsCenterView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `opsCenter.queryLogEvents()` | POST | `/ops-center/log-events/query` | payload?: OpsCenterQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `OpsCenterLogEventView` | `frontend/apps/web/src/views/OpsCenterView.vue:434` | `frontend/packages/api-sdk/src/client.ts:1690` |
| `opsCenter.options()` | GET | `/ops-center/options` | config?: StudioRequestConfig | `OpsCenterOptionsView` | `frontend/apps/web/src/views/OpsCenterView.vue:334` | `frontend/packages/api-sdk/src/client.ts:1669` |
| `opsCenter.queryOverview()` | POST | `/ops-center/overview/query` | payload?: OpsCenterQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `OpsCenterOverviewView` | `frontend/apps/web/src/views/OpsCenterView.vue:380` | `frontend/packages/api-sdk/src/client.ts:1672` |
| `opsCenter.queryQueue()` | POST | `/ops-center/queue/query` | payload?: OpsCenterQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `OpsCenterQueueItemView` | `frontend/apps/web/src/views/OpsCenterView.vue:389` | `frontend/packages/api-sdk/src/client.ts:1678` |
| `opsCenter.queryRuns()` | POST | `/ops-center/runs/query` | payload?: OpsCenterQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `OpsCenterRunIncidentView` | `frontend/apps/web/src/views/OpsCenterView.vue:407` | `frontend/packages/api-sdk/src/client.ts:1675` |
| `opsCenter.queryServiceEvents()` | POST | `/ops-center/service-events/query` | payload?: OpsCenterQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `OpsCenterServiceEventView` | `frontend/apps/web/src/views/OpsCenterView.vue:416` | `frontend/packages/api-sdk/src/client.ts:1684` |
| `opsCenter.queryWorkers()` | POST | `/ops-center/workers/query` | payload?: OpsCenterQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `OpsCenterWorkerGroupView` | `frontend/apps/web/src/views/OpsCenterView.vue:398` | `frontend/packages/api-sdk/src/client.ts:1681` |
| `runtimeClusters.options()` | GET | `/runtime-clusters/options` | projectId?: EntityId<br>query: projectId 为空时使用当前 `X-Project-Id` | `RuntimeClusterView[]` | 运行集群选择器和 AI 助手 | `frontend/packages/api-sdk/src/client.ts` |
| `runtime.mode()` | GET | `/runtime/mode` | - | `RuntimeModeResponse` | - | `frontend/packages/api-sdk/src/client.ts:1949` |


## 后端 Controller 对照

| Controller | 方法 | 路径 | Java 方法 | 参数来源 | 源码 |
|---|---|---|---|---|---|
| AssistantController | GET | `/api/v1/assistant/capabilities` | `capabilities()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AssistantController.java:86` |
| AssistantController | POST | `/api/v1/assistant/chat/stream` | `streamChat()` | body: `@RequestBody(required = false) AssistantPlanRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AssistantController.java:71` |
| AssistantController | GET | `/api/v1/assistant/config` | `config()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AssistantController.java:92` |
| AssistantController | POST | `/api/v1/assistant/learn` | `learn()` | body: `@RequestBody(required = false) AssistantLearnRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AssistantController.java:62` |
| AssistantController | GET | `/api/v1/assistant/operations` | `operations()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AssistantController.java:102` |
| AssistantController | POST | `/api/v1/assistant/plan` | `plan()` | body: `@RequestBody(required = false) AssistantPlanRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AssistantController.java:56` |
| AssistantController | GET | `/api/v1/assistant/skills` | `skills()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AssistantController.java:108` |
| AssistantToolController | POST | `/api/v1/assistant/tools/execute` | `executeTool()` | body: `@RequestBody(required = false) Map<String, Object> request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AssistantToolController.java:26` |
| ImportExportController | GET | `/api/v1/exports/project` | `exportProject()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ImportExportController.java:30` |
| ImportExportController | GET | `/api/v1/imports/template` | `importTemplate()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ImportExportController.java:39` |
| OpsCenterController | POST | `/api/v1/ops-center/ingestion-events/query` | `queryIngestionEvents()` | body: `@RequestBody(required = false) OpsCenterQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpsCenterController.java:70` |
| OpsCenterController | POST | `/api/v1/ops-center/protocol-conversion-events/query` | `queryProtocolConversionEvents()` | body: `@RequestBody(required = false) OpsCenterQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpsCenterController.java` |
| OpsCenterController | POST | `/api/v1/ops-center/log-events/query` | `queryLogEvents()` | body: `@RequestBody(required = false) OpsCenterQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpsCenterController.java:76` |
| OpsCenterController | GET | `/api/v1/ops-center/options` | `options()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpsCenterController.java:34` |
| OpsCenterController | POST | `/api/v1/ops-center/overview/query` | `queryOverview()` | body: `@RequestBody(required = false) OpsCenterQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpsCenterController.java:40` |
| OpsCenterController | POST | `/api/v1/ops-center/queue/query` | `queryQueue()` | body: `@RequestBody(required = false) OpsCenterQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpsCenterController.java:52` |
| OpsCenterController | POST | `/api/v1/ops-center/runs/query` | `queryRuns()` | body: `@RequestBody(required = false) OpsCenterQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpsCenterController.java:46` |
| OpsCenterController | POST | `/api/v1/ops-center/service-events/query` | `queryServiceEvents()` | body: `@RequestBody(required = false) OpsCenterQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpsCenterController.java:64` |
| OpsCenterController | POST | `/api/v1/ops-center/workers/query` | `queryWorkers()` | body: `@RequestBody(required = false) OpsCenterQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpsCenterController.java:58` |
| RuntimeClusterController | GET | `/api/v1/runtime-clusters/options` | `options()` | query: `projectId?: Long`；省略时使用当前项目上下文 | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RuntimeClusterController.java` |
| DesktopRuntimeController | GET | `/api/v1/runtime/mode` | `mode()` | - | `backend/studio-desktop-runtime/src/main/java/com/jdragon/studio/desktopruntime/runtime/DesktopRuntimeController.java:19` |


## cURL 模板

以下模板按前端 SDK 自动生成，动态路径中的 `{id}`、`{serviceId}` 等需要替换为上游接口返回值。出现 `?<query>` 时按表格中的 query 参数追加。

如果接口清单的“参数/请求体”列没有显示 `body:`，则自动化调用时删除模板中的 `-H "Content-Type: application/json"` 和 `-d` 行；只有列出 `body:` 的接口才发送 JSON 请求体。

### assistant.capabilities()

```bash
curl -X GET "${BASE_URL}/assistant/capabilities" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### assistant.config()

```bash
curl -X GET "${BASE_URL}/assistant/config" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### assistant.learn()

```bash
curl -X POST "${BASE_URL}/assistant/learn" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### assistant.operations()

```bash
curl -X GET "${BASE_URL}/assistant/operations" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### assistant.skills()

```bash
curl -X GET "${BASE_URL}/assistant/skills" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### assistant.executeTool()

```bash
curl -X POST "${BASE_URL}/assistant/tools/execute" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

模型预览的三次工具请求参数如下。前两次均为只读列表；最后一次虽然使用 `studio.feature.action`，但操作目录中的 `mutation=false`，前端不应弹出写操作确认：

```json
{"interfaceCode":"studio.feature.list","params":{"path":"/runtime-clusters"}}
{"interfaceCode":"studio.feature.list","params":{"path":"/models","runtimeClusterId":7,"keyword":"orders","pageNo":1,"pageSize":20}}
{"interfaceCode":"studio.feature.action","params":{"path":"/models","action":"preview","id":5,"runtimeClusterId":7,"limit":20}}
```

缺少模型 ID 时返回 `assistant tool id is required`；缺少运行集群时返回 `assistant tool runtimeClusterId is required`。调用方应根据真实缺参补齐选择，不要把两种错误都当成模型 ID 丢失后重复查询详情。

### exports.project()

```bash
curl -X GET "${BASE_URL}/exports/project" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### imports.template()

```bash
curl -X GET "${BASE_URL}/imports/template" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### opsCenter.queryIngestionEvents()

```bash
curl -X POST "${BASE_URL}/ops-center/ingestion-events/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### opsCenter.queryLogEvents()

```bash
curl -X POST "${BASE_URL}/ops-center/log-events/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### opsCenter.options()

```bash
curl -X GET "${BASE_URL}/ops-center/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### opsCenter.queryOverview()

```bash
curl -X POST "${BASE_URL}/ops-center/overview/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### opsCenter.queryQueue()

```bash
curl -X POST "${BASE_URL}/ops-center/queue/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### opsCenter.queryRuns()

```bash
curl -X POST "${BASE_URL}/ops-center/runs/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### opsCenter.queryServiceEvents()

```bash
curl -X POST "${BASE_URL}/ops-center/service-events/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### opsCenter.queryWorkers()

```bash
curl -X POST "${BASE_URL}/ops-center/workers/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### runtime.mode()

```bash
curl -X GET "${BASE_URL}/runtime/mode" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```


## 开放访问 cURL 补充

开放访问接口不走管理端 `${BASE_URL}` 前缀，使用 `${STUDIO_ORIGIN}`，例如 `http://127.0.0.1:18080`。订阅令牌来自对应服务的订阅创建/轮换接口。

本模块无额外开放访问 Controller 模板。


## 主要 DTO 字段

### AssistantConfigResponse

来源：`frontend/packages/api-sdk/src/types.ts:1805`

```ts
interface AssistantConfigResponse
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| enabled | 是 | `boolean` | |
| reason | 否 | `string` | |

### AssistantLearnRequest

来源：`frontend/packages/api-sdk/src/types.ts:1887`

```ts
interface AssistantLearnRequest extends AssistantPlanRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| assistantContent | 否 | `string` | |

### AssistantLearnResponse

来源：`frontend/packages/api-sdk/src/types.ts:1891`

```ts
interface AssistantLearnResponse
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| accepted | 否 | `boolean` | |
| message | 否 | `string` | |

### AssistantToolExecuteRequest

来源：`frontend/packages/api-sdk/src/types.ts:1810`

```ts
interface AssistantToolExecuteRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| interfaceCode | 是 | `string` | |
| params | 否 | `Record<string, unknown>` | |

### AssistantToolExecutionResult

来源：`frontend/packages/api-sdk/src/types.ts:1815`

```ts
interface AssistantToolExecutionResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| schema | 否 | `"studio.tool-result.v1" \| string` | |
| interfaceCode | 否 | `string` | |
| path | 否 | `string` | |
| action | 否 | `string` | |
| resource | 否 | `string` | |
| entrypointId | 否 | `string` | |
| executedBy | 否 | `"backend" \| string` | |
| mutation | 否 | `boolean` | |
| requiresConfirmation | 否 | `boolean` | |
| params | 否 | `Record<string, unknown>` | |
| effectiveParams | 否 | `Record<string, unknown>` | |
| defaultedParams | 否 | `string[]` | |
| data | 否 | `T` | |

### ExportProjectBundle

来源：`frontend/packages/api-sdk/src/types.ts:3286`

```ts
interface ExportProjectBundle
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| metaSchemas | 是 | `MetadataSchemaDefinition[]` | |
| workflows | 是 | `WorkflowDefinitionView[]` | |

### OpsCenterLogEventView

来源：`frontend/packages/api-sdk/src/types.ts:3003`

```ts
interface OpsCenterLogEventView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| executionType | 否 | `string` | |
| workflowRunId | 否 | `EntityId` | |
| workflowDefinitionId | 否 | `EntityId` | |
| collectionTaskId | 否 | `EntityId` | |
| qualityTaskId | 否 | `EntityId` | |
| nodeCode | 否 | `string` | |
| status | 否 | `string` | |
| workerGroupCode | 否 | `string` | |
| workerInstanceId | 否 | `string` | |
| startedAt | 否 | `string` | |
| endedAt | 否 | `string` | |
| logStorageType | 否 | `string` | |
| logStatus | 否 | `string` | |
| logErrorSummary | 否 | `string` | |

### OpsCenterOptionsView

来源：`frontend/packages/api-sdk/src/types.ts:2885`

```ts
interface OpsCenterOptionsView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| executionTypes | 是 | `string[]` | |
| statuses | 是 | `string[]` | |
| workerGroups | 是 | `string[]` | |

### OpsCenterOverviewView

来源：`frontend/packages/api-sdk/src/types.ts:2899`

```ts
interface OpsCenterOverviewView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| healthStatus | 否 | `OpsCenterHealthStatus \| string` | |
| healthMessage | 否 | `string` | |
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| healthReasons | 否 | `string[]` | |
| metrics | 否 | `OpsCenterMetricCardView[]` | |
| runTotal | 否 | `number \| string \| null` | |
| failedRuns | 否 | `number \| string \| null` | |
| runningRuns | 否 | `number \| string \| null` | |
| slowRuns | 否 | `number \| string \| null` | |
| queuedTasks | 否 | `number \| string \| null` | |
| runningQueueTasks | 否 | `number \| string \| null` | |
| serviceFailures | 否 | `number \| string \| null` | |
| serviceSlowCalls | 否 | `number \| string \| null` | |
| ingestionFailures | 否 | `number \| string \| null` | |
| ingestionSlowCalls | 否 | `number \| string \| null` | |
| logFailures | 否 | `number \| string \| null` | |
| onlineWorkerInstances | 否 | `number \| string \| null` | |
| boundWorkerGroups | 否 | `number \| string \| null` | |

### OpsCenterQueryRequest

来源：`frontend/packages/api-sdk/src/types.ts:2875`

```ts
interface OpsCenterQueryRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| executionType | 否 | `string` | |
| status | 否 | `string` | |
| workerGroupCode | 否 | `string` | |
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |

### OpsCenterQueueItemView

来源：`frontend/packages/api-sdk/src/types.ts:2921`

```ts
interface OpsCenterQueueItemView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| executionType | 否 | `string` | |
| workflowRunId | 否 | `EntityId` | |
| workflowDefinitionId | 否 | `EntityId` | |
| workflowVersionId | 否 | `EntityId` | |
| collectionTaskId | 否 | `EntityId` | |
| qualityTaskId | 否 | `EntityId` | |
| targetName | 否 | `string` | |
| nodeCode | 否 | `string` | |
| status | 否 | `string` | |
| workerGroupCode | 否 | `string` | |
| leaseOwner | 否 | `string` | |
| workerInstanceId | 否 | `string` | |
| scheduledFireTime | 否 | `string` | |
| queuedDurationMs | 否 | `number \| string \| null` | |
| scheduleDelayMs | 否 | `number \| string \| null` | |
| attempts | 否 | `number` | |
| maxRetries | 否 | `number` | |

### OpsCenterRunIncidentView

来源：`frontend/packages/api-sdk/src/types.ts:2941`

```ts
interface OpsCenterRunIncidentView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| executionType | 否 | `string` | |
| workflowRunId | 否 | `EntityId` | |
| workflowDefinitionId | 否 | `EntityId` | |
| workflowVersionId | 否 | `EntityId` | |
| collectionTaskId | 否 | `EntityId` | |
| qualityTaskId | 否 | `EntityId` | |
| nodeCode | 否 | `string` | |
| status | 否 | `string` | |
| message | 否 | `string` | |
| workerGroupCode | 否 | `string` | |
| workerCode | 否 | `string` | |
| workerInstanceId | 否 | `string` | |
| workerPodName | 否 | `string` | |
| workerNodeName | 否 | `string` | |
| startedAt | 否 | `string` | |
| endedAt | 否 | `string` | |
| durationMs | 否 | `number \| string \| null` | |
| logStorageType | 否 | `string` | |
| logStatus | 否 | `string` | |
| logErrorSummary | 否 | `string` | |
| slow | 否 | `boolean` | |
| logAbnormal | 否 | `boolean` | |

### OpsCenterServiceEventView

来源：`frontend/packages/api-sdk/src/types.ts:2981`

```ts
interface OpsCenterServiceEventView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| serviceId | 否 | `EntityId` | |
| serviceCode | 否 | `string` | |
| serviceName | 否 | `string` | |
| serviceStatus | 否 | `string` | |
| subscriptionId | 否 | `EntityId` | |
| subscriptionName | 否 | `string` | |
| requestMethod | 否 | `string` | |
| occurredAt | 否 | `string` | |
| durationMs | 否 | `number \| string \| null` | |
| success | 否 | `boolean` | |
| httpStatus | 否 | `number` | |
| errorCode | 否 | `string` | |
| errorMessage | 否 | `string` | |
| rowCount | 否 | `number \| string \| null` | |
| receivedCount | 否 | `number \| string \| null` | |
| writtenCount | 否 | `number \| string \| null` | |
| failedCount | 否 | `number \| string \| null` | |
| slow | 否 | `boolean` | |

### OpsCenterWorkerGroupView

来源：`frontend/packages/api-sdk/src/types.ts:2966`

```ts
interface OpsCenterWorkerGroupView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| workerGroupCode | 否 | `string` | |
| displayStatus | 否 | `string` | |
| boundToProject | 否 | `boolean` | |
| enabled | 否 | `boolean` | |
| onlineInstanceCount | 否 | `number` | |
| recentInstanceCount | 否 | `number` | |
| latestHeartbeatAt | 否 | `string` | |
| latestWorkerCode | 否 | `string` | |
| latestWorkerInstanceId | 否 | `string` | |
| latestPodName | 否 | `string` | |
| latestNodeName | 否 | `string` | |
| leaseExpiresAt | 否 | `string` | |

### RuntimeModeResponse

来源：`frontend/packages/api-sdk/src/types.ts:3280`

```ts
interface RuntimeModeResponse
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| mode | 是 | `string` | |
| syncStrategy | 是 | `string` | |
| offlineExecution | 是 | `boolean` | |
