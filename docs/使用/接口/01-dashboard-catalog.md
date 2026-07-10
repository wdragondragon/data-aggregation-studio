# 工作台与目录能力接口

覆盖工作台总览、能力矩阵、数据源类型和插件运行参数元模型查询。

## 界面范围

- `/dashboard` -> `frontend/apps/web/src/views/DashboardView.vue`
- `/catalog` -> `frontend/apps/web/src/views/CatalogView.vue`

## 调用前置条件

- 管理端基础地址：`${BASE_URL}`，默认在线环境为 `http://127.0.0.1:18080/api/v1`。
- 认证头：`Authorization: Bearer ${TOKEN}`。
- 租户和项目上下文：大多数项目内接口需要 `X-Tenant-Id: ${TENANT_ID}` 与 `X-Project-Id: ${PROJECT_ID}`。
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页通常为 `PageResult<T>`：`{ pageNo, pageSize, total, items }`。

## 典型调用链

1. 工作台首屏：GET /dashboard/overview，返回数据源数量、近期工作流、近期运行和脚本类型统计。
2. 配置表单准备：GET /catalog/datasource-types -> 按 typeCode 选择数据源类型 -> GET /catalog/runtime-option-schemas?role=reader|writer&datasourceType=<typeCode> 加载插件运行参数表单。
3. 能力判断链路：GET /catalog/capabilities -> 根据 executableSourceTypes、sourceCapabilities 判断某数据源类型是否可读、可写、可执行 SQL。

## 前端 SDK 接口清单

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 | 前端调用点 | 源码 |
|---|---|---|---|---|---|---|
| `catalog.capabilities()` | GET | `/catalog/capabilities` | - | `CapabilityMatrix` | `frontend/apps/web/src/views/CatalogView.vue:161`<br>`frontend/apps/web/src/views/DatasourcesView.vue:998` | `frontend/packages/api-sdk/src/client.ts:458` |
| `catalog.datasourceTypes()` | GET | `/catalog/datasource-types` | - | `DatasourceTypeCapabilityView[]` | `frontend/apps/web/src/views/DataIngestionServiceEditorView.vue:1434`<br>`frontend/apps/web/src/views/MetadataSchemasView.vue:815` | `frontend/packages/api-sdk/src/client.ts:461` |
| `catalog.runtimeOptionSchema()` | GET | `/catalog/runtime-option-schemas` | params: { role: string; datasourceType: string; protocolMode?: string } | `PluginRuntimeOptionSchemaView` | `frontend/apps/web/src/views/CollectionTaskEditorView.vue:618`<br>`frontend/apps/web/src/views/DataIngestionServiceEditorView.vue:1690` | `frontend/packages/api-sdk/src/client.ts:464` |
| `dashboard.overview()` | GET | `/dashboard/overview` | - | `StudioDashboardView` | `frontend/apps/web/src/views/DashboardView.vue:284` | `frontend/packages/api-sdk/src/client.ts:433` |


## 后端 Controller 对照

| Controller | 方法 | 路径 | Java 方法 | 参数来源 | 源码 |
|---|---|---|---|---|---|
| CatalogController | GET | `/api/v1/catalog/capabilities` | `capabilityMatrix()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CatalogController.java:34` |
| CatalogController | GET | `/api/v1/catalog/datasource-types` | `datasourceTypes()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CatalogController.java:45` |
| CatalogController | GET | `/api/v1/catalog/runtime-option-schemas` | `runtimeOptionSchema()` | query: `@RequestParam("role") String role`<br>query: `@RequestParam("datasourceType") String datasourceType`<br>query: `@RequestParam(value = "protocolMode"`<br>implicit: `required = false) String protocolMode` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CatalogController.java:51` |
| DashboardController | GET | `/api/v1/dashboard/overview` | `overview()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DashboardController.java:24` |


## cURL 模板

以下模板按前端 SDK 自动生成，动态路径中的 `{id}`、`{serviceId}` 等需要替换为上游接口返回值。出现 `?<query>` 时按表格中的 query 参数追加。

如果接口清单的“参数/请求体”列没有显示 `body:`，则自动化调用时删除模板中的 `-H "Content-Type: application/json"` 和 `-d` 行；只有列出 `body:` 的接口才发送 JSON 请求体。

### catalog.capabilities()

```bash
curl -X GET "${BASE_URL}/catalog/capabilities" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### catalog.datasourceTypes()

```bash
curl -X GET "${BASE_URL}/catalog/datasource-types" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### catalog.runtimeOptionSchema()

```bash
curl -X GET "${BASE_URL}/catalog/runtime-option-schemas" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dashboard.overview()

```bash
curl -X GET "${BASE_URL}/dashboard/overview" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```


## 开放访问 cURL 补充

开放访问接口不走管理端 `${BASE_URL}` 前缀，使用 `${STUDIO_ORIGIN}`，例如 `http://127.0.0.1:18080`。订阅令牌来自对应服务的订阅创建/轮换接口。

本模块无额外开放访问 Controller 模板。


## 主要 DTO 字段

### CapabilityMatrix

来源：`frontend/packages/api-sdk/src/types.ts:267`

```ts
interface CapabilityMatrix
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| executableSourceTypes | 是 | `string[]` | |
| executableTargetTypes | 否 | `string[]` | |
| executableDatasourceTypes | 否 | `string[]` | |
| sourceCapabilities | 否 | `SourceCapabilityEntry[]` | |

### PluginRuntimeOptionSchemaView

来源：`frontend/packages/api-sdk/src/types.ts:255`

```ts
interface PluginRuntimeOptionSchemaView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| role | 是 | `string` | |
| datasourceType | 是 | `string` | |
| sourceCategory | 否 | `string` | |
| pluginType | 否 | `string` | |
| runtimeSupported | 否 | `boolean` | |
| incrementalSupported | 否 | `boolean` | |
| fields | 是 | `MetadataFieldDefinition[]` | |
| reservedKeys | 是 | `string[]` | |
| description | 否 | `string` | |

### StudioDashboardView

来源：`frontend/packages/api-sdk/src/types.ts:274`

```ts
interface StudioDashboardView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceCount | 否 | `number \| string \| null` | |
| publishedWorkflowCount | 否 | `number \| string \| null` | |
| scheduledWorkflowCount | 否 | `number \| string \| null` | |
| onlineCollectionTaskCount | 否 | `number \| string \| null` | |
| queuedTaskCount | 否 | `number \| string \| null` | |
| scriptTypeCounts | 否 | `Record<string, number \| string \| null>` | |
| executableDatasourceTypes | 否 | `string[]` | |
| recentWorkflowDefinitions | 否 | `WorkflowListView[]` | |
| recentWorkflowRuns | 否 | `WorkflowRunSummary[]` | |
| recentScripts | 否 | `DataDevelopmentScriptListView[]` | |

