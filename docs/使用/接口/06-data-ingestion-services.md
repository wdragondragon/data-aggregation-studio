# 数据接入服务接口

覆盖数据接入服务配置、发布下线、调试、订阅令牌、REST/SOAP 写入入口和接入监控。

## 界面范围

- `/data-ingestion-services` -> `frontend/apps/web/src/views/DataIngestionServicesView.vue`
- `/data-ingestion-metrics` -> `frontend/apps/web/src/views/DataIngestionMetricsView.vue`
- `/data-ingestion-metrics/access-logs` -> `frontend/apps/web/src/views/DataIngestionAccessLogsView.vue`

## 调用前置条件

- 管理端基础地址：`${BASE_URL}`，默认在线环境为 `http://127.0.0.1:18080/api/v1`。
- 认证头：`Authorization: Bearer ${TOKEN}`。
- 租户和项目上下文：大多数项目内接口需要 `X-Tenant-Id: ${TENANT_ID}` 与 `X-Project-Id: ${PROJECT_ID}`。
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页通常为 `PageResult<T>`：`{ pageNo, pageSize, total, items }`。

## 典型调用链

1. 创建接入服务：先选择运行集群，GET /data-development/datasource-options?runtimeClusterId=<id> -> GET /models/datasource-options?datasourceId=<target> -> POST /data-ingestion-services/resolve-fields -> POST /data-ingestion-services -> POST /data-ingestion-services/{id}/publish。
2. 调试写入：GET /data-ingestion-services/{id} -> POST /data-ingestion-services/{id}/debug，body 中放 headers/query/body/form/sourcePayload。
3. 开放写入：POST /data-ingestion-services/{id}/subscriptions 创建 token -> POST /openapi/data-ingestion-services/{serviceCode}/{serviceKey}，请求头 X-Data-Ingestion-Token=<token>。
4. SOAP 写入：GET /data-ingestion-services/{id}/webservice/preview -> GET /openapi/ws/data-ingestion-services/{serviceCode}/{serviceKey}?wsdl -> POST /openapi/ws/data-ingestion-services/{serviceCode}/{serviceKey}。
5. 接入监控：POST /data-ingestion-metrics/dashboard/query -> POST /data-ingestion-metrics/access-logs/query -> GET /invocation-logs/DATA_INGESTION/{accessLogId}。

## 2026-07-20 多集群增量契约

- `DataIngestionServiceSaveRequest.runtimeClusterId` 为必填字段；`DataIngestionResolveFieldsRequest` 同样必须携带运行集群。目标数据源和模型候选只从该集群适用范围加载。
- 列表和详情返回 `runtimeClusterId/runtimeClusterName` 与 `runtimeValid/runtimeValidationMessage`。运行配置失效时不能发布、调试或接受新的写入调用。
- 对外 REST、SOAP 和 WSDL 地址保持 OMS 不变。所有运行集群统一通过目标 Worker 的 HTTP/SLB 端点执行，不存在 Server 本地执行快速路径；目标不可达返回 `503`，不自动重试、不跟随重定向，实际业务状态与响应体保持原样。
- `DataIngestionMetricQueryRequest` 增加 `requestedClusterId/actualClusterId`，访问日志返回相同字段。日志只在实际执行集群写一次。
- 同步代理请求体超过统一上限时返回 `413 PAYLOAD_TOO_LARGE`，不会转发到目标集群。

以上增量字段优先于下方历史自动抽取表中未包含集群参数的旧签名。

## 前端 SDK 接口清单

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 | 前端调用点 | 源码 |
|---|---|---|---|---|---|---|
| `dataIngestionMetrics.queryAccessLogs()` | POST | `/data-ingestion-metrics/access-logs/query` | payload?: DataIngestionMetricQueryRequest<br>body: `payload` | `DataIngestionAccessLogListView` | `frontend/apps/web/src/views/DataIngestionAccessLogsView.vue:271` | `frontend/packages/api-sdk/src/client.ts:912` |
| `dataIngestionMetrics.queryApiStats()` | POST | `/data-ingestion-metrics/api-stats/query` | payload?: DataIngestionMetricQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `DataIngestionApiMetricView` | `frontend/apps/web/src/views/DataIngestionMetricsView.vue:332` | `frontend/packages/api-sdk/src/client.ts:909` |
| `dataIngestionMetrics.queryDashboard()` | POST | `/data-ingestion-metrics/dashboard/query` | payload?: DataIngestionMetricQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `DataIngestionMetricDashboardView` | `frontend/apps/web/src/views/DataIngestionMetricsView.vue:321` | `frontend/packages/api-sdk/src/client.ts:906` |
| `dataIngestionMetrics.options()` | GET | `/data-ingestion-metrics/options` | config?: StudioRequestConfig | `DataServiceMetricOptionsView` | `frontend/apps/web/src/views/DataIngestionAccessLogsView.vue:263`<br>`frontend/apps/web/src/views/DataIngestionMetricsView.vue:309` | `frontend/packages/api-sdk/src/client.ts:903` |
| `dataIngestionServices.list()` | GET | `/data-ingestion-services` | params?: { pageNo?: number; pageSize?: number; keyword?: string; status?: string; targetType?: string; }<br>config?: StudioRequestConfig | `DataIngestionServiceListView` | `frontend/apps/web/src/views/DataIngestionServicesView.vue:178` | `frontend/packages/api-sdk/src/client.ts:744` |
| `dataIngestionServices.save()` | POST | `/data-ingestion-services` | payload: DataIngestionServiceSaveRequest<br>body: `payload` | `DataIngestionServiceView` | `frontend/apps/web/src/views/DataIngestionServiceEditorView.vue:1905` | `frontend/packages/api-sdk/src/client.ts:756` |
| `dataIngestionServices.resolveFields()` | POST | `/data-ingestion-services/resolve-fields` | payload: DataIngestionResolveFieldsRequest<br>body: `payload` | `DataIngestionResolveFieldsView` | `frontend/apps/web/src/views/DataIngestionServiceEditorView.vue:1810` | `frontend/packages/api-sdk/src/client.ts:774` |
| `dataIngestionServices.debug()` | POST | ``/data-ingestion-services/${id}/debug`` | id: EntityId<br>payload: DataIngestionDebugRequest<br>body: `payload` | `DataIngestionInvokeResult` | `frontend/apps/web/src/views/DataIngestionServiceEditorView.vue:2241` | `frontend/packages/api-sdk/src/client.ts:777` |
| `dataIngestionServices.offlineSummary()` | POST | ``/data-ingestion-services/${id}/offline-summary`` | id: EntityId | `DataIngestionServiceListView` | `frontend/apps/web/src/views/DataIngestionServicesView.vue:226` | `frontend/packages/api-sdk/src/client.ts:771` |
| `dataIngestionServices.offline()` | POST | ``/data-ingestion-services/${id}/offline`` | id: EntityId | `DataIngestionServiceView` | - | `frontend/packages/api-sdk/src/client.ts:768` |
| `dataIngestionServices.publishSummary()` | POST | ``/data-ingestion-services/${id}/publish-summary`` | id: EntityId | `DataIngestionServiceListView` | `frontend/apps/web/src/views/DataIngestionServicesView.vue:220` | `frontend/packages/api-sdk/src/client.ts:765` |
| `dataIngestionServices.publish()` | POST | ``/data-ingestion-services/${id}/publish`` | id: EntityId | `DataIngestionServiceView` | `frontend/apps/web/src/views/DataIngestionServiceEditorView.vue:1986` | `frontend/packages/api-sdk/src/client.ts:762` |
| `dataIngestionServices.disableSubscription()` | POST | ``/data-ingestion-services/${id}/subscriptions/${subscriptionId}/disable`` | id: EntityId<br>subscriptionId: EntityId | `DataIngestionSubscriptionView` | `frontend/apps/web/src/views/DataIngestionServicesView.vue:321` | `frontend/packages/api-sdk/src/client.ts:796` |
| `dataIngestionServices.enableSubscription()` | POST | ``/data-ingestion-services/${id}/subscriptions/${subscriptionId}/enable`` | id: EntityId<br>subscriptionId: EntityId | `DataIngestionSubscriptionView` | `frontend/apps/web/src/views/DataIngestionServicesView.vue:329` | `frontend/packages/api-sdk/src/client.ts:808` |
| `dataIngestionServices.rotateSubscription()` | POST | ``/data-ingestion-services/${id}/subscriptions/${subscriptionId}/rotate`` | id: EntityId<br>subscriptionId: EntityId | `DataIngestionSubscriptionView` | `frontend/apps/web/src/views/DataIngestionServicesView.vue:302` | `frontend/packages/api-sdk/src/client.ts:802` |
| `dataIngestionServices.createSubscription()` | POST | ``/data-ingestion-services/${id}/subscriptions`` | id: EntityId<br>subscriptionName: string<br>body: `{ subscriptionName }` | `DataIngestionSubscriptionView` | `frontend/apps/web/src/views/DataIngestionServicesView.vue:283` | `frontend/packages/api-sdk/src/client.ts:789` |
| `dataIngestionServices.listSubscriptions()` | GET | ``/data-ingestion-services/${id}/subscriptions`` | id: EntityId | `DataIngestionSubscriptionView[]` | `frontend/apps/web/src/views/DataIngestionServicesView.vue:250` | `frontend/packages/api-sdk/src/client.ts:786` |
| `dataIngestionServices.debugWebService()` | POST | ``/data-ingestion-services/${id}/webservice/debug`` | id: EntityId<br>payload: WebServiceDebugRequest<br>body: `payload` | `WebServiceDebugResult` | `frontend/apps/web/src/views/DataIngestionServiceEditorView.vue:2457` | `frontend/packages/api-sdk/src/client.ts:783` |
| `dataIngestionServices.previewWebService()` | GET | ``/data-ingestion-services/${id}/webservice/preview`` | id: EntityId | `WebServicePreviewView` | `frontend/apps/web/src/views/DataIngestionServiceEditorView.vue:2424` | `frontend/packages/api-sdk/src/client.ts:780` |
| `dataIngestionServices.delete()` | DELETE | ``/data-ingestion-services/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/DataIngestionServicesView.vue:233` | `frontend/packages/api-sdk/src/client.ts:759` |
| `dataIngestionServices.get()` | GET | ``/data-ingestion-services/${id}`` | id: EntityId | `DataIngestionServiceView` | `frontend/apps/web/src/views/DataIngestionServiceEditorView.vue:1500` | `frontend/packages/api-sdk/src/client.ts:753` |
| `invocationLogs.download()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/download`` | domain: string<br>accessLogId: EntityId | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:210` | `frontend/packages/api-sdk/src/client.ts:1640` |
| `invocationLogs.downloadSection()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/sections/${encodeURIComponent(sectionKey)}/download`` | domain: string<br>accessLogId: EntityId<br>sectionKey: string | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:209` | `frontend/packages/api-sdk/src/client.ts:1653` |
| `invocationLogs.getSection()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/sections/${encodeURIComponent(sectionKey)}`` | domain: string<br>accessLogId: EntityId<br>sectionKey: string<br>params?: RunLogQuery | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:165` | `frontend/packages/api-sdk/src/client.ts:1646` |
| `invocationLogs.get()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}`` | domain: string<br>accessLogId: EntityId<br>params?: RunLogQuery | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:143` | `frontend/packages/api-sdk/src/client.ts:1633` |


## 后端 Controller 对照

| Controller | 方法 | 路径 | Java 方法 | 参数来源 | 源码 |
|---|---|---|---|---|---|
| DataIngestionMetricsController | POST | `/api/v1/data-ingestion-metrics/access-logs/query` | `queryAccessLogs()` | body: `@RequestBody(required = false) DataIngestionMetricQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionMetricsController.java:42` |
| DataIngestionMetricsController | POST | `/api/v1/data-ingestion-metrics/api-stats/query` | `queryApiStats()` | body: `@RequestBody(required = false) DataIngestionMetricQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionMetricsController.java:37` |
| DataIngestionMetricsController | POST | `/api/v1/data-ingestion-metrics/dashboard/query` | `queryDashboard()` | body: `@RequestBody(required = false) DataIngestionMetricQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionMetricsController.java:32` |
| DataIngestionMetricsController | GET | `/api/v1/data-ingestion-metrics/options` | `options()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionMetricsController.java:27` |
| DataIngestionServiceController | GET | `/api/v1/data-ingestion-services` | `list()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "status"`<br>implicit: `required = false) String status`<br>query: `@RequestParam(value = "targetType"`<br>implicit: `required = false) String targetType` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:44` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services` | `save()` | body: `@Valid @RequestBody DataIngestionServiceSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:60` |
| DataIngestionServiceController | GET | `/api/v1/data-ingestion-services/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:54` |
| DataIngestionServiceController | DELETE | `/api/v1/data-ingestion-services/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:66` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services/{id}/debug` | `debug()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) DataIngestionDebugRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:103` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services/{id}/offline` | `offline()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:85` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services/{id}/offline-summary` | `offlineSummary()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:91` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services/{id}/publish` | `publish()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:73` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services/{id}/publish-summary` | `publishSummary()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:79` |
| DataIngestionServiceController | GET | `/api/v1/data-ingestion-services/{id}/subscriptions` | `subscriptions()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:123` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services/{id}/subscriptions` | `createSubscription()` | path: `@PathVariable("id") Long id`<br>body: `@Valid @RequestBody DataServiceSubscriptionCreateRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:129` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services/{id}/subscriptions/{subscriptionId}/disable` | `disableSubscription()` | path: `@PathVariable("id") Long id`<br>path: `@PathVariable("subscriptionId") Long subscriptionId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:143` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services/{id}/subscriptions/{subscriptionId}/enable` | `enableSubscription()` | path: `@PathVariable("id") Long id`<br>path: `@PathVariable("subscriptionId") Long subscriptionId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:150` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services/{id}/subscriptions/{subscriptionId}/rotate` | `rotateSubscription()` | path: `@PathVariable("id") Long id`<br>path: `@PathVariable("subscriptionId") Long subscriptionId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:136` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services/{id}/webservice/debug` | `debugWebService()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) WebServiceDebugRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:116` |
| DataIngestionServiceController | GET | `/api/v1/data-ingestion-services/{id}/webservice/preview` | `previewWebService()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:110` |
| DataIngestionServiceController | POST | `/api/v1/data-ingestion-services/resolve-fields` | `resolveFields()` | body: `@RequestBody DataIngestionResolveFieldsRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataIngestionServiceController.java:97` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}` | `log()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSizeBytes"`<br>implicit: `required = false) Integer pageSizeBytes` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:26` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/download` | `download()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:35` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/sections/{sectionKey}` | `sectionLog()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>path: `@PathVariable("sectionKey") String sectionKey`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSizeBytes"`<br>implicit: `required = false) Integer pageSizeBytes` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:42` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/sections/{sectionKey}/download` | `downloadSection()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>path: `@PathVariable("sectionKey") String sectionKey` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:52` |
| OpenDataIngestionServiceController | POST | `/openapi/data-ingestion-services/{serviceCode}/{serviceKey}` | `invoke()` | path: `@PathVariable("serviceCode") String serviceCode`<br>path: `@PathVariable("serviceKey") String serviceKey`<br>header: `@RequestHeader(value = TOKEN_HEADER, required = false) String token`<br>implicit: `HttpServletRequest request`<br>implicit: `HttpServletResponse response` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpenDataIngestionServiceController.java:38` |
| OpenWebServiceDataIngestionController | GET | `/openapi/ws/data-ingestion-services/{serviceCode}/{serviceKey}` | `wsdl()` | path: `@PathVariable("serviceCode") String serviceCode`<br>path: `@PathVariable("serviceKey") String serviceKey`<br>implicit: `HttpServletRequest request`<br>implicit: `HttpServletResponse response` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpenWebServiceDataIngestionController.java:34` |
| OpenWebServiceDataIngestionController | POST | `/openapi/ws/data-ingestion-services/{serviceCode}/{serviceKey}` | `invoke()` | path: `@PathVariable("serviceCode") String serviceCode`<br>path: `@PathVariable("serviceKey") String serviceKey`<br>header: `@RequestHeader(value = TOKEN_HEADER, required = false) String token`<br>implicit: `HttpServletRequest request`<br>implicit: `HttpServletResponse response` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpenWebServiceDataIngestionController.java:46` |


## cURL 模板

以下模板按前端 SDK 自动生成，动态路径中的 `{id}`、`{serviceId}` 等需要替换为上游接口返回值。出现 `?<query>` 时按表格中的 query 参数追加。

如果接口清单的“参数/请求体”列没有显示 `body:`，则自动化调用时删除模板中的 `-H "Content-Type: application/json"` 和 `-d` 行；只有列出 `body:` 的接口才发送 JSON 请求体。

### dataIngestionMetrics.queryAccessLogs()

```bash
curl -X POST "${BASE_URL}/data-ingestion-metrics/access-logs/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionMetrics.queryApiStats()

```bash
curl -X POST "${BASE_URL}/data-ingestion-metrics/api-stats/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionMetrics.queryDashboard()

```bash
curl -X POST "${BASE_URL}/data-ingestion-metrics/dashboard/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionMetrics.options()

```bash
curl -X GET "${BASE_URL}/data-ingestion-metrics/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataIngestionServices.list()

```bash
curl -X GET "${BASE_URL}/data-ingestion-services" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataIngestionServices.save()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.resolveFields()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services/resolve-fields" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.debug()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services/{id}/debug" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.offlineSummary()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services/{id}/offline-summary" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.offline()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services/{id}/offline" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.publishSummary()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services/{id}/publish-summary" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.publish()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services/{id}/publish" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.disableSubscription()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services/{id}/subscriptions/{subscriptionId}/disable" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.enableSubscription()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services/{id}/subscriptions/{subscriptionId}/enable" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.rotateSubscription()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services/{id}/subscriptions/{subscriptionId}/rotate" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.createSubscription()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services/{id}/subscriptions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.listSubscriptions()

```bash
curl -X GET "${BASE_URL}/data-ingestion-services/{id}/subscriptions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataIngestionServices.debugWebService()

```bash
curl -X POST "${BASE_URL}/data-ingestion-services/{id}/webservice/debug" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataIngestionServices.previewWebService()

```bash
curl -X GET "${BASE_URL}/data-ingestion-services/{id}/webservice/preview" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataIngestionServices.delete()

```bash
curl -X DELETE "${BASE_URL}/data-ingestion-services/{id}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```


## 开放访问 cURL 补充

开放访问接口不走管理端 `${BASE_URL}` 前缀，使用 `${STUDIO_ORIGIN}`，例如 `http://127.0.0.1:18080`。订阅令牌来自对应服务的订阅创建/轮换接口。

### OpenDataIngestionServiceController.invoke()

```bash
curl -X POST "${STUDIO_ORIGIN}/openapi/data-ingestion-services/{serviceCode}/{serviceKey}" \
  -H "X-Data-Ingestion-Token: ${SUBSCRIPTION_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '<json-or-xml-body>'
```

### OpenWebServiceDataIngestionController.wsdl()

```bash
curl -X GET "${STUDIO_ORIGIN}/openapi/ws/data-ingestion-services/{serviceCode}/{serviceKey}" \
  -H "X-Data-Ingestion-Token: ${SUBSCRIPTION_TOKEN}"
```

### OpenWebServiceDataIngestionController.invoke()

```bash
curl -X POST "${STUDIO_ORIGIN}/openapi/ws/data-ingestion-services/{serviceCode}/{serviceKey}" \
  -H "X-Data-Ingestion-Token: ${SUBSCRIPTION_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '<json-or-xml-body>'
```


## 主要 DTO 字段

### DataIngestionAccessLogListView

来源：`frontend/packages/api-sdk/src/types.ts:1156`

```ts
interface DataIngestionAccessLogListView
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
| requestId | 否 | `string` | |
| requestMethod | 否 | `string` | |
| occurredAt | 否 | `string` | |
| durationMs | 否 | `number` | |
| success | 否 | `boolean` | |
| httpStatus | 否 | `number` | |
| errorCode | 否 | `string` | |
| errorMessage | 否 | `string` | |
| clientIp | 否 | `string` | |
| userAgent | 否 | `string` | |
| receivedCount | 否 | `number` | |
| successCount | 否 | `number` | |
| failedCount | 否 | `number` | |

### DataIngestionApiMetricView

来源：`frontend/packages/api-sdk/src/types.ts:1134`

```ts
interface DataIngestionApiMetricView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| serviceId | 否 | `EntityId` | |
| serviceName | 否 | `string` | |
| serviceCode | 否 | `string` | |
| status | 否 | `string` | |
| subscriptionId | 否 | `EntityId` | |
| subscriptionName | 否 | `string` | |
| accessCount | 否 | `number` | |
| successCount | 否 | `number` | |
| failureCount | 否 | `number` | |
| successRate | 否 | `number` | |
| receivedCount | 否 | `number` | |
| writtenCount | 否 | `number` | |
| failedCount | 否 | `number` | |
| minResponseTimeMs | 否 | `number` | |
| maxResponseTimeMs | 否 | `number` | |
| avgResponseTimeMs | 否 | `number` | |
| p95ResponseTimeMs | 否 | `number` | |
| p99ResponseTimeMs | 否 | `number` | |
| lastAccessAt | 否 | `string` | |

### DataIngestionDebugRequest

来源：`frontend/packages/api-sdk/src/types.ts:722`

```ts
interface DataIngestionDebugRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| headers | 否 | `Record<string, unknown>` | |
| query | 否 | `Record<string, unknown>` | |
| form | 否 | `Record<string, unknown>` | |
| body | 否 | `unknown` | |

### DataIngestionInvokeResult

来源：`frontend/packages/api-sdk/src/types.ts:729`

```ts
interface DataIngestionInvokeResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| requestId | 否 | `string` | |
| serviceCode | 否 | `string` | |
| receivedCount | 否 | `number` | |
| successCount | 否 | `number` | |
| failedCount | 否 | `number` | |
| status | 否 | `string` | |
| pluginRevisions | 否 | `Record<string, string>` | 后端响应已包含：本次调用实际使用的目标作业插件身份和修订信息，仅属于运行时元数据，不写入服务定义。当前前端 SDK 类型声明暂未补齐此字段。 |
| sourceResults | 否 | `DataIngestionSourceInvokeResult[]` | |

### DataIngestionSourceInvokeResult

来源：`frontend/packages/api-sdk/src/types.ts:898`；`pluginRevisions` 来源于后端 `DataIngestionSourceInvokeResult`。

```ts
interface DataIngestionSourceInvokeResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| sourceCode | 否 | `string` | |
| sourceName | 否 | `string` | |
| targetDatasourceName | 否 | `string` | |
| targetModelName | 否 | `string` | |
| receivedCount | 否 | `number` | |
| successCount | 否 | `number` | |
| failedCount | 否 | `number` | |
| status | 否 | `string` | |
| message | 否 | `string` | |
| jobId | 否 | `EntityId` | |
| logSectionKey | 否 | `string` | 用于定位该来源目标作业的调用日志分段。 |
| pluginRevisions | 否 | `Record<string, string>` | 后端响应已包含：该来源目标作业实例实际使用的插件身份和修订信息。当前前端 SDK 类型声明暂未补齐此字段。 |

### DataIngestionMetricDashboardView

来源：`frontend/packages/api-sdk/src/types.ts:1190`

```ts
interface DataIngestionMetricDashboardView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| summary | 否 | `DataIngestionMetricSummaryView` | |
| accessTrend | 否 | `RunMetricTrendView` | |
| cumulativeAccessTrend | 否 | `RunMetricTrendView` | |
| receivedTrend | 否 | `RunMetricTrendView` | |
| cumulativeReceivedTrend | 否 | `RunMetricTrendView` | |
| writtenTrend | 否 | `RunMetricTrendView` | |
| cumulativeWrittenTrend | 否 | `RunMetricTrendView` | |
| responseTimeTrend | 否 | `RunMetricTrendView` | |
| successRateTrend | 否 | `RunMetricTrendView` | |
| errorDistribution | 否 | `DataServiceMetricDistributionView[]` | |
| topSlowServices | 否 | `DataIngestionApiMetricView[]` | |
| topFailedServices | 否 | `DataIngestionApiMetricView[]` | |
| subscriptionRank | 否 | `DataIngestionApiMetricView[]` | |

### DataIngestionMetricQueryRequest

来源：`frontend/packages/api-sdk/src/types.ts:1206`

```ts
interface DataIngestionMetricQueryRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| serviceId | 否 | `EntityId` | |
| subscriptionId | 否 | `EntityId` | |
| serviceStatus | 否 | `string` | |
| success | 否 | `boolean` | |
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| granularity | 否 | `string` | |
| logFocus | 否 | `"ALL" \| "ERROR" \| "SLOW" \| "ERROR_OR_SLOW"` | |
| minDurationMs | 否 | `number` | |
| topN | 否 | `number` | |
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |

### DataIngestionResolveFieldsRequest

来源：`frontend/packages/api-sdk/src/types.ts:712`

```ts
interface DataIngestionResolveFieldsRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 否 | `EntityId` | |
| modelId | 否 | `EntityId` | |

### DataIngestionResolveFieldsView

来源：`frontend/packages/api-sdk/src/types.ts:717`

```ts
interface DataIngestionResolveFieldsView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| fields | 是 | `DataServiceFieldView[]` | |
| fieldMappings | 是 | `DataIngestionFieldMapping[]` | |

### DataIngestionServiceListView

来源：`frontend/packages/api-sdk/src/types.ts:667`

```ts
interface DataIngestionServiceListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| createdBy | 否 | `EntityId` | |
| serviceCode | 是 | `string` | |
| serviceName | 是 | `string` | |
| status | 否 | `DataIngestionStatus` | |
| requestFormat | 否 | `DataIngestionRequestFormat` | |
| payloadMode | 否 | `DataIngestionPayloadMode` | |
| dataNodePath | 否 | `string` | |
| targetType | 否 | `DataIngestionTargetType` | |
| datasourceId | 否 | `EntityId` | |
| datasourceName | 否 | `string` | |
| datasourceTypeCode | 否 | `string` | |
| modelId | 否 | `EntityId` | |
| modelName | 否 | `string` | |
| modelPhysicalLocator | 否 | `string` | |
| endpointPath | 否 | `string` | |
| maxBatchSize | 否 | `number` | |
| tokenRequired | 否 | `boolean` | |
| defaultSubscriptionName | 否 | `string` | |
| webserviceEnabled | 否 | `boolean` | |
| sourcePositions | 否 | `string[]` | |
| sourceCount | 否 | `number` | |
| targetCount | 否 | `number` | |

### DataIngestionServiceSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:692`

```ts
interface DataIngestionServiceSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| serviceCode | 是 | `string` | |
| serviceName | 是 | `string` | |
| requestFormat | 否 | `DataIngestionRequestFormat` | |
| payloadMode | 否 | `DataIngestionPayloadMode` | |
| dataNodePath | 否 | `string` | |
| targetType | 否 | `DataIngestionTargetType` | |
| datasourceId | 否 | `EntityId` | |
| modelId | 否 | `EntityId` | |
| maxBatchSize | 否 | `number` | |
| tokenRequired | 否 | `boolean` | |
| defaultSubscriptionName | 否 | `string` | |
| webserviceEnabled | 否 | `boolean` | |
| webserviceConfig | 否 | `WebServiceConfig` | |
| writerOptions | 否 | `Record<string, unknown>` | |
| fieldMappings | 是 | `DataIngestionFieldMapping[]` | |
| sourceBindings | 否 | `DataIngestionSourceBinding[]` | |

### DataIngestionServiceView

来源：`frontend/packages/api-sdk/src/types.ts:637`

```ts
interface DataIngestionServiceView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| createdBy | 否 | `EntityId` | |
| serviceCode | 是 | `string` | |
| serviceName | 是 | `string` | |
| status | 否 | `DataIngestionStatus` | |
| requestFormat | 否 | `DataIngestionRequestFormat` | |
| payloadMode | 否 | `DataIngestionPayloadMode` | |
| dataNodePath | 否 | `string` | |
| targetType | 否 | `DataIngestionTargetType` | |
| datasourceId | 否 | `EntityId` | |
| datasourceName | 否 | `string` | |
| datasourceTypeCode | 否 | `string` | |
| modelId | 否 | `EntityId` | |
| modelName | 否 | `string` | |
| modelPhysicalLocator | 否 | `string` | |
| endpointPath | 否 | `string` | |
| serviceKey | 否 | `string` | |
| maxBatchSize | 否 | `number` | |
| tokenRequired | 否 | `boolean` | |
| defaultSubscriptionName | 否 | `string` | |
| webserviceEnabled | 否 | `boolean` | |
| webserviceConfig | 否 | `WebServiceConfig` | |
| writerOptions | 否 | `Record<string, unknown>` | |
| fieldMappings | 否 | `DataIngestionFieldMapping[]` | |
| sourcePositions | 否 | `string[]` | |
| sourceBindings | 否 | `DataIngestionSourceBinding[]` | |
| sourceCount | 否 | `number` | |
| targetCount | 否 | `number` | |

### DataIngestionSubscriptionView

来源：`frontend/packages/api-sdk/src/types.ts:753`

```ts
interface DataIngestionSubscriptionView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| serviceId | 否 | `EntityId` | |
| subscriptionName | 是 | `string` | |
| token | 否 | `string` | |
| tokenMasked | 否 | `string` | |
| enabled | 否 | `boolean` | |
| createdBy | 否 | `EntityId` | |
| lastUsedAt | 否 | `string` | |
| rotatedAt | 否 | `string` | |
| rotatedBy | 否 | `EntityId` | |

### DataServiceMetricOptionsView

来源：`frontend/packages/api-sdk/src/types.ts:984`

```ts
interface DataServiceMetricOptionsView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| services | 是 | `DataServiceMetricOptionView[]` | |
| subscriptions | 是 | `DataServiceMetricOptionView[]` | |

### RunLogQuery

来源：`frontend/packages/api-sdk/src/types.ts:3020`

```ts
interface RunLogQuery
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| pageNo | 否 | `number` | |
| pageSizeBytes | 否 | `number` | |

### RunLogView

来源：`frontend/packages/api-sdk/src/types.ts:2806`

```ts
interface RunLogView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| runRecordId | 否 | `EntityId` | |
| content | 否 | `string` | 服务端返回前会使用 `StudioSensitiveLogSanitizer` 对密码、Token、Secret 等敏感内容脱敏；自动化不得依赖响应中出现原始敏感值。 |
| truncated | 否 | `boolean` | |
| paged | 否 | `boolean` | |
| sizeBytes | 否 | `number` | |
| updatedAt | 否 | `string` | |
| charset | 否 | `string` | |
| downloadName | 否 | `string` | |
| contentType | 否 | `string` | |
| historicalFallback | 否 | `boolean` | |
| pageNo | 否 | `number` | |
| totalPages | 否 | `number` | |
| pageSizeBytes | 否 | `number` | |
| sections | 否 | `InvocationLogSectionView[]` | |

### WebServiceDebugRequest

来源：`frontend/packages/api-sdk/src/types.ts:484`

```ts
interface WebServiceDebugRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| soapEnvelope | 否 | `string` | |
| soapVersion | 否 | `WebServiceSoapVersion` | |
| headers | 否 | `Record<string, unknown>` | |

### WebServiceDebugResult

来源：`frontend/packages/api-sdk/src/types.ts:490`

```ts
interface WebServiceDebugResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| success | 否 | `boolean` | |
| httpStatus | 否 | `number` | |
| requestEnvelope | 否 | `string` | |
| responseEnvelope | 否 | `string` | |
| result | 否 | `unknown` | |
| errorCode | 否 | `string` | |
| errorMessage | 否 | `string` | |

### WebServicePreviewView

来源：`frontend/packages/api-sdk/src/types.ts:473`

```ts
interface WebServicePreviewView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| endpointPath | 否 | `string` | |
| wsdlPath | 否 | `string` | |
| wsdl | 否 | `string` | |
| sampleRequest | 否 | `string` | |
| sampleResponse | 否 | `string` | |
| soapAction | 否 | `string` | |
| namespaceUri | 否 | `string` | |
| operationName | 否 | `string` | |
