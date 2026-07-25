# 数据服务开放接口

覆盖数据服务配置、发布下线、调试、订阅令牌、REST/SOAP 开放访问和监控日志。

## 界面范围

- `/data-services` -> `frontend/apps/web/src/views/DataServicesView.vue`
- `/data-service-metrics` -> `frontend/apps/web/src/views/DataServiceMetricsView.vue`
- `/data-service-metrics/access-logs` -> `frontend/apps/web/src/views/DataServiceAccessLogsView.vue`

## 调用前置条件

- 管理端基础地址：`${BASE_URL}`，默认在线环境为 `http://127.0.0.1:18080/api/v1`。
- 认证头：`Authorization: Bearer ${TOKEN}`。
- 租户和项目上下文：大多数项目内接口需要 `X-Tenant-Id: ${TENANT_ID}` 与 `X-Project-Id: ${PROJECT_ID}`。
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页通常为 `PageResult<T>`：`{ pageNo, pageSize, total, items }`。

## 典型调用链

1. 创建数据服务：先选择运行集群，GET /data-development/datasource-options?runtimeClusterId=<id> -> GET /models/datasource-options?datasourceId=<id> -> POST /data-services/resolve-fields -> POST /data-services -> POST /data-services/{id}/publish。
2. 调试服务：GET /data-services/{id} -> POST /data-services/{id}/debug，body 中放 headers/query/body -> 根据返回 result/table 判断输出。
3. 开放调用：GET /data-services/{id}/subscriptions -> POST /data-services/{id}/subscriptions 创建 token -> 调用 /openapi/data-services/{serviceCode}/{serviceKey}，请求头 X-Data-Service-Token=<token>。
4. SOAP 调用：GET /data-services/{id}/webservice/preview -> GET /openapi/ws/data-services/{serviceCode}/{serviceKey}?wsdl -> POST /openapi/ws/data-services/{serviceCode}/{serviceKey}。
5. 监控排查：POST /data-service-metrics/dashboard/query -> POST /data-service-metrics/access-logs/query -> GET /invocation-logs/DATA_SERVICE/{accessLogId}。

## 2026-07-20 多集群增量契约

- `DataServiceSaveRequest.runtimeClusterId` 为必填字段；`DataServiceResolveFieldsRequest` 同样必须携带运行集群。数据源和模型候选必须先按该集群筛选。
- 列表和详情返回 `runtimeClusterId/runtimeClusterName` 与 `runtimeValid/runtimeValidationMessage`。运行配置失效的服务不能发布、调试或接收新的开放调用。
- 对外 REST、SOAP 和 WSDL 地址仍由 OMS 暴露。无论选择 `DEFAULT-LOCAL` 还是其它运行集群，实际数据面调用都通过目标集群的 Worker HTTP/SLB 端点执行。目标不可达返回 `503`，不回退到 Server 本地执行，不自动重试、不跟随重定向，目标业务 HTTP 状态和响应体保持原样。
- `DataServiceMetricQueryRequest` 增加 `requestedClusterId/actualClusterId`；访问日志返回相同两个字段。请求集群表示服务保存的目标，实际集群表示真正写入调用日志的执行位置。
- 同步代理请求体受统一大小限制，超限返回 `413 PAYLOAD_TOO_LARGE`。访问日志只在实际执行位置写一次，不记录内部认证或受管端点秘密。

以上增量字段优先于下方历史自动抽取表中未包含集群参数的旧签名。

## 前端 SDK 接口清单

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 | 前端调用点 | 源码 |
|---|---|---|---|---|---|---|
| `dataServiceMetrics.queryAccessLogs()` | POST | `/data-service-metrics/access-logs/query` | payload?: DataServiceMetricQueryRequest<br>body: `payload` | `DataServiceAccessLogListView` | `frontend/apps/web/src/views/DataServiceAccessLogsView.vue:273` | `frontend/packages/api-sdk/src/client.ts:898` |
| `dataServiceMetrics.queryApiStats()` | POST | `/data-service-metrics/api-stats/query` | payload?: DataServiceMetricQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `DataServiceApiMetricView` | `frontend/apps/web/src/views/DataServiceMetricsView.vue:344` | `frontend/packages/api-sdk/src/client.ts:895` |
| `dataServiceMetrics.queryDashboard()` | POST | `/data-service-metrics/dashboard/query` | payload?: DataServiceMetricQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `DataServiceMetricDashboardView` | `frontend/apps/web/src/views/DataServiceMetricsView.vue:333` | `frontend/packages/api-sdk/src/client.ts:892` |
| `dataServiceMetrics.options()` | GET | `/data-service-metrics/options` | config?: StudioRequestConfig | `DataServiceMetricOptionsView` | `frontend/apps/web/src/views/DataServiceAccessLogsView.vue:265`<br>`frontend/apps/web/src/views/DataServiceMetricsView.vue:321` | `frontend/packages/api-sdk/src/client.ts:889` |
| `dataServices.list()` | GET | `/data-services` | params?: { pageNo?: number; pageSize?: number; keyword?: string; status?: string; serviceType?: string; }<br>config?: StudioRequestConfig | `DataServiceListView` | `frontend/apps/web/src/views/DataServicesView.vue:178` | `frontend/packages/api-sdk/src/client.ts:668` |
| `dataServices.save()` | POST | `/data-services` | payload: DataServiceSaveRequest<br>body: `payload` | `DataServiceDefinitionView` | `frontend/apps/web/src/views/DataServiceEditorView.vue:1140` | `frontend/packages/api-sdk/src/client.ts:680` |
| `dataServices.resolveFields()` | POST | `/data-services/resolve-fields` | payload: DataServiceResolveFieldsRequest<br>body: `payload` | `DataServiceResolveFieldsView` | `frontend/apps/web/src/views/DataServiceEditorView.vue:963` | `frontend/packages/api-sdk/src/client.ts:698` |
| `dataServices.offlineSummary()` | POST | ``/data-services/${id}/offline-summary`` | id: EntityId | `DataServiceListView` | `frontend/apps/web/src/views/DataServicesView.vue:241` | `frontend/packages/api-sdk/src/client.ts:695` |
| `dataServices.offline()` | POST | ``/data-services/${id}/offline`` | id: EntityId | `DataServiceDefinitionView` | - | `frontend/packages/api-sdk/src/client.ts:692` |
| `dataServices.publishSummary()` | POST | ``/data-services/${id}/publish-summary`` | id: EntityId | `DataServiceListView` | `frontend/apps/web/src/views/DataServicesView.vue:228` | `frontend/packages/api-sdk/src/client.ts:689` |
| `dataServices.publish()` | POST | ``/data-services/${id}/publish`` | id: EntityId | `DataServiceDefinitionView` | `frontend/apps/web/src/views/DataServiceEditorView.vue:1260` | `frontend/packages/api-sdk/src/client.ts:686` |
| `dataServices.disableSubscription()` | POST | ``/data-services/${id}/subscriptions/${subscriptionId}/disable`` | id: EntityId<br>subscriptionId: EntityId | `DataServiceSubscriptionView` | `frontend/apps/web/src/views/DataServicesView.vue:363` | `frontend/packages/api-sdk/src/client.ts:724` |
| `dataServices.enableSubscription()` | POST | ``/data-services/${id}/subscriptions/${subscriptionId}/enable`` | id: EntityId<br>subscriptionId: EntityId | `DataServiceSubscriptionView` | `frontend/apps/web/src/views/DataServicesView.vue:376` | `frontend/packages/api-sdk/src/client.ts:736` |
| `dataServices.rotateSubscription()` | POST | ``/data-services/${id}/subscriptions/${subscriptionId}/rotate`` | id: EntityId<br>subscriptionId: EntityId | `DataServiceSubscriptionView` | `frontend/apps/web/src/views/DataServicesView.vue:343` | `frontend/packages/api-sdk/src/client.ts:730` |
| `dataServices.createSubscription()` | POST | ``/data-services/${id}/subscriptions`` | id: EntityId<br>subscriptionName: string<br>body: `{ subscriptionName }` | `DataServiceSubscriptionView` | `frontend/apps/web/src/views/DataServicesView.vue:318` | `frontend/packages/api-sdk/src/client.ts:717` |
| `dataServices.listSubscriptions()` | GET | ``/data-services/${id}/subscriptions`` | id: EntityId | `DataServiceSubscriptionView[]` | `frontend/apps/web/src/views/DataServicesView.vue:285` | `frontend/packages/api-sdk/src/client.ts:714` |
| `dataServices.debugWebService()` | POST | ``/data-services/${id}/webservice/debug`` | id: EntityId<br>payload: WebServiceDebugRequest<br>body: `payload` | `WebServiceDebugResult` | `frontend/apps/web/src/views/DataServiceEditorView.vue:1229` | `frontend/packages/api-sdk/src/client.ts:711` |
| `dataServices.previewWebService()` | GET | ``/data-services/${id}/webservice/preview`` | id: EntityId | `WebServicePreviewView` | `frontend/apps/web/src/views/DataServiceEditorView.vue:1191` | `frontend/packages/api-sdk/src/client.ts:708` |
| `dataServices.delete()` | DELETE | ``/data-services/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/DataServicesView.vue:255` | `frontend/packages/api-sdk/src/client.ts:683` |
| `dataServices.get()` | GET | ``/data-services/${id}`` | id: EntityId | `DataServiceDefinitionView` | `frontend/apps/web/src/views/DataServiceEditorView.vue:826` | `frontend/packages/api-sdk/src/client.ts:677` |
| `invocationLogs.download()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/download`` | domain: string<br>accessLogId: EntityId | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:210` | `frontend/packages/api-sdk/src/client.ts:1640` |
| `invocationLogs.downloadSection()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/sections/${encodeURIComponent(sectionKey)}/download`` | domain: string<br>accessLogId: EntityId<br>sectionKey: string | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:209` | `frontend/packages/api-sdk/src/client.ts:1653` |
| `invocationLogs.getSection()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/sections/${encodeURIComponent(sectionKey)}`` | domain: string<br>accessLogId: EntityId<br>sectionKey: string<br>params?: RunLogQuery | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:165` | `frontend/packages/api-sdk/src/client.ts:1646` |
| `invocationLogs.get()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}`` | domain: string<br>accessLogId: EntityId<br>params?: RunLogQuery | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:143` | `frontend/packages/api-sdk/src/client.ts:1633` |


## 后端 Controller 对照

| Controller | 方法 | 路径 | Java 方法 | 参数来源 | 源码 |
|---|---|---|---|---|---|
| DataServiceMetricsController | POST | `/api/v1/data-service-metrics/access-logs/query` | `queryAccessLogs()` | body: `@RequestBody(required = false) DataServiceMetricQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceMetricsController.java:42` |
| DataServiceMetricsController | POST | `/api/v1/data-service-metrics/api-stats/query` | `queryApiStats()` | body: `@RequestBody(required = false) DataServiceMetricQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceMetricsController.java:37` |
| DataServiceMetricsController | POST | `/api/v1/data-service-metrics/dashboard/query` | `queryDashboard()` | body: `@RequestBody(required = false) DataServiceMetricQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceMetricsController.java:32` |
| DataServiceMetricsController | GET | `/api/v1/data-service-metrics/options` | `options()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceMetricsController.java:27` |
| DataServiceController | GET | `/api/v1/data-services` | `list()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "status"`<br>implicit: `required = false) String status`<br>query: `@RequestParam(value = "serviceType"`<br>implicit: `required = false) String serviceType` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:44` |
| DataServiceController | POST | `/api/v1/data-services` | `save()` | body: `@Valid @RequestBody DataServiceSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:60` |
| DataServiceController | GET | `/api/v1/data-services/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:54` |
| DataServiceController | DELETE | `/api/v1/data-services/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:66` |
| DataServiceController | POST | `/api/v1/data-services/{id}/debug` | `debug()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) DataServiceDebugRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:103` |
| DataServiceController | POST | `/api/v1/data-services/{id}/offline` | `offline()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:85` |
| DataServiceController | POST | `/api/v1/data-services/{id}/offline-summary` | `offlineSummary()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:91` |
| DataServiceController | POST | `/api/v1/data-services/{id}/publish` | `publish()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:73` |
| DataServiceController | POST | `/api/v1/data-services/{id}/publish-summary` | `publishSummary()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:79` |
| DataServiceController | GET | `/api/v1/data-services/{id}/subscriptions` | `subscriptions()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:123` |
| DataServiceController | POST | `/api/v1/data-services/{id}/subscriptions` | `createSubscription()` | path: `@PathVariable("id") Long id`<br>body: `@Valid @RequestBody DataServiceSubscriptionCreateRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:129` |
| DataServiceController | POST | `/api/v1/data-services/{id}/subscriptions/{subscriptionId}/disable` | `disableSubscription()` | path: `@PathVariable("id") Long id`<br>path: `@PathVariable("subscriptionId") Long subscriptionId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:143` |
| DataServiceController | POST | `/api/v1/data-services/{id}/subscriptions/{subscriptionId}/enable` | `enableSubscription()` | path: `@PathVariable("id") Long id`<br>path: `@PathVariable("subscriptionId") Long subscriptionId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:150` |
| DataServiceController | POST | `/api/v1/data-services/{id}/subscriptions/{subscriptionId}/rotate` | `rotateSubscription()` | path: `@PathVariable("id") Long id`<br>path: `@PathVariable("subscriptionId") Long subscriptionId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:136` |
| DataServiceController | POST | `/api/v1/data-services/{id}/webservice/debug` | `debugWebService()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) WebServiceDebugRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:116` |
| DataServiceController | GET | `/api/v1/data-services/{id}/webservice/preview` | `previewWebService()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:110` |
| DataServiceController | POST | `/api/v1/data-services/resolve-fields` | `resolveFields()` | body: `@RequestBody DataServiceResolveFieldsRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataServiceController.java:97` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}` | `log()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSizeBytes"`<br>implicit: `required = false) Integer pageSizeBytes` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:26` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/download` | `download()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:35` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/sections/{sectionKey}` | `sectionLog()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>path: `@PathVariable("sectionKey") String sectionKey`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSizeBytes"`<br>implicit: `required = false) Integer pageSizeBytes` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:42` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/sections/{sectionKey}/download` | `downloadSection()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>path: `@PathVariable("sectionKey") String sectionKey` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:52` |
| OpenDataServiceController | GET|POST | `/openapi/data-services/{serviceCode}/{serviceKey}` | `invoke()` | path: `@PathVariable("serviceCode") String serviceCode`<br>path: `@PathVariable("serviceKey") String serviceKey`<br>header: `@RequestHeader(value = TOKEN_HEADER, required = false) String token`<br>query: `@RequestParam Map<String, Object> query`<br>body: `@RequestBody(required = false) Map<String, Object> body`<br>implicit: `HttpServletRequest request`<br>implicit: `HttpServletResponse response` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpenDataServiceController.java:37` |
| OpenWebServiceDataServiceController | GET | `/openapi/ws/data-services/{serviceCode}/{serviceKey}` | `wsdl()` | path: `@PathVariable("serviceCode") String serviceCode`<br>path: `@PathVariable("serviceKey") String serviceKey`<br>implicit: `HttpServletRequest request`<br>implicit: `HttpServletResponse response` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpenWebServiceDataServiceController.java:34` |
| OpenWebServiceDataServiceController | POST | `/openapi/ws/data-services/{serviceCode}/{serviceKey}` | `invoke()` | path: `@PathVariable("serviceCode") String serviceCode`<br>path: `@PathVariable("serviceKey") String serviceKey`<br>header: `@RequestHeader(value = TOKEN_HEADER, required = false) String token`<br>implicit: `HttpServletRequest request`<br>implicit: `HttpServletResponse response` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpenWebServiceDataServiceController.java:48` |


## cURL 模板

以下模板按前端 SDK 自动生成，动态路径中的 `{id}`、`{serviceId}` 等需要替换为上游接口返回值。出现 `?<query>` 时按表格中的 query 参数追加。

如果接口清单的“参数/请求体”列没有显示 `body:`，则自动化调用时删除模板中的 `-H "Content-Type: application/json"` 和 `-d` 行；只有列出 `body:` 的接口才发送 JSON 请求体。

### dataServiceMetrics.queryAccessLogs()

```bash
curl -X POST "${BASE_URL}/data-service-metrics/access-logs/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServiceMetrics.queryApiStats()

```bash
curl -X POST "${BASE_URL}/data-service-metrics/api-stats/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServiceMetrics.queryDashboard()

```bash
curl -X POST "${BASE_URL}/data-service-metrics/dashboard/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServiceMetrics.options()

```bash
curl -X GET "${BASE_URL}/data-service-metrics/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataServices.list()

```bash
curl -X GET "${BASE_URL}/data-services" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataServices.save()

```bash
curl -X POST "${BASE_URL}/data-services" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServices.resolveFields()

```bash
curl -X POST "${BASE_URL}/data-services/resolve-fields" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServices.offlineSummary()

```bash
curl -X POST "${BASE_URL}/data-services/{id}/offline-summary" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServices.offline()

```bash
curl -X POST "${BASE_URL}/data-services/{id}/offline" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServices.publishSummary()

```bash
curl -X POST "${BASE_URL}/data-services/{id}/publish-summary" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServices.publish()

```bash
curl -X POST "${BASE_URL}/data-services/{id}/publish" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServices.disableSubscription()

```bash
curl -X POST "${BASE_URL}/data-services/{id}/subscriptions/{subscriptionId}/disable" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServices.enableSubscription()

```bash
curl -X POST "${BASE_URL}/data-services/{id}/subscriptions/{subscriptionId}/enable" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServices.rotateSubscription()

```bash
curl -X POST "${BASE_URL}/data-services/{id}/subscriptions/{subscriptionId}/rotate" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServices.createSubscription()

```bash
curl -X POST "${BASE_URL}/data-services/{id}/subscriptions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServices.listSubscriptions()

```bash
curl -X GET "${BASE_URL}/data-services/{id}/subscriptions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataServices.debugWebService()

```bash
curl -X POST "${BASE_URL}/data-services/{id}/webservice/debug" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataServices.previewWebService()

```bash
curl -X GET "${BASE_URL}/data-services/{id}/webservice/preview" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataServices.delete()

```bash
curl -X DELETE "${BASE_URL}/data-services/{id}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataServices.get()

```bash
curl -X GET "${BASE_URL}/data-services/{id}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```


## 开放访问 cURL 补充

开放访问接口不走管理端 `${BASE_URL}` 前缀，使用 `${STUDIO_ORIGIN}`，例如 `http://127.0.0.1:18080`。订阅令牌来自对应服务的订阅创建/轮换接口。

### OpenDataServiceController.invoke()

```bash
curl -X GET "${STUDIO_ORIGIN}/openapi/data-services/{serviceCode}/{serviceKey}" \
  -H "X-Data-Service-Token: ${SUBSCRIPTION_TOKEN}"
```

### OpenWebServiceDataServiceController.wsdl()

```bash
curl -X GET "${STUDIO_ORIGIN}/openapi/ws/data-services/{serviceCode}/{serviceKey}" \
  -H "X-Data-Service-Token: ${SUBSCRIPTION_TOKEN}"
```

### OpenWebServiceDataServiceController.invoke()

```bash
curl -X POST "${STUDIO_ORIGIN}/openapi/ws/data-services/{serviceCode}/{serviceKey}" \
  -H "X-Data-Service-Token: ${SUBSCRIPTION_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '<json-or-xml-body>'
```


## 主要 DTO 字段

### DataServiceAccessLogListView

来源：`frontend/packages/api-sdk/src/types.ts:1038`

```ts
interface DataServiceAccessLogListView
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
| cacheEnabled | 否 | `boolean` | |
| cacheHit | 否 | `boolean` | |
| rowCount | 否 | `number` | |

### DataServiceApiMetricView

来源：`frontend/packages/api-sdk/src/types.ts:1008`

```ts
interface DataServiceApiMetricView
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
| minResponseTimeMs | 否 | `number` | |
| maxResponseTimeMs | 否 | `number` | |
| avgResponseTimeMs | 否 | `number` | |
| p95ResponseTimeMs | 否 | `number` | |
| p99ResponseTimeMs | 否 | `number` | |
| lastAccessAt | 否 | `string` | |
| cacheEnabledCount | 否 | `number` | |
| cacheHitCount | 否 | `number` | |
| cacheMissCount | 否 | `number` | |
| cacheDisabledCount | 否 | `number` | |
| cacheHitRate | 否 | `number` | |

### DataServiceDefinitionView

来源：`frontend/packages/api-sdk/src/types.ts:500`

```ts
interface DataServiceDefinitionView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| createdBy | 否 | `EntityId` | |
| serviceCode | 是 | `string` | |
| serviceName | 是 | `string` | |
| serviceType | 否 | `DataServiceType` | |
| status | 否 | `DataServiceStatus` | |
| sourceType | 否 | `DataServiceSourceType` | |
| datasourceId | 否 | `EntityId` | |
| datasourceName | 否 | `string` | |
| datasourceTypeCode | 否 | `string` | |
| modelId | 否 | `EntityId` | |
| modelName | 否 | `string` | |
| modelPhysicalLocator | 否 | `string` | |
| customSql | 否 | `string` | |
| requestMethod | 否 | `DataServiceRequestMethod` | |
| responseType | 否 | `DataServiceResponseType` | |
| endpointPath | 否 | `string` | |
| serviceKey | 否 | `string` | |
| cacheEnabled | 否 | `boolean` | |
| tokenRequired | 否 | `boolean` | |
| defaultSubscriptionName | 否 | `string` | |
| webserviceEnabled | 否 | `boolean` | |
| webserviceConfig | 否 | `WebServiceConfig` | |
| requestParams | 是 | `DataServiceRequestParam[]` | |
| responseParams | 是 | `DataServiceResponseParam[]` | |
| publishParams | 是 | `DataServicePublishParam[]` | |

### DataServiceListView

来源：`frontend/packages/api-sdk/src/types.ts:528`

```ts
interface DataServiceListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| createdBy | 否 | `EntityId` | |
| serviceCode | 是 | `string` | |
| serviceName | 是 | `string` | |
| serviceType | 否 | `DataServiceType` | |
| status | 否 | `DataServiceStatus` | |
| sourceType | 否 | `DataServiceSourceType` | |
| datasourceId | 否 | `EntityId` | |
| datasourceName | 否 | `string` | |
| datasourceTypeCode | 否 | `string` | |
| modelId | 否 | `EntityId` | |
| modelName | 否 | `string` | |
| modelPhysicalLocator | 否 | `string` | |
| requestMethod | 否 | `DataServiceRequestMethod` | |
| responseType | 否 | `DataServiceResponseType` | |
| endpointPath | 否 | `string` | |
| cacheEnabled | 否 | `boolean` | |
| tokenRequired | 否 | `boolean` | |
| defaultSubscriptionName | 否 | `string` | |
| webserviceEnabled | 否 | `boolean` | |

### DataServiceMetricDashboardView

来源：`frontend/packages/api-sdk/src/types.ts:1072`

```ts
interface DataServiceMetricDashboardView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| summary | 否 | `DataServiceMetricSummaryView` | |
| accessTrend | 否 | `RunMetricTrendView` | |
| cumulativeAccessTrend | 否 | `RunMetricTrendView` | |
| outputRowTrend | 否 | `RunMetricTrendView` | |
| cumulativeOutputRowTrend | 否 | `RunMetricTrendView` | |
| cacheHitTrend | 否 | `RunMetricTrendView` | |
| cumulativeCacheHitTrend | 否 | `RunMetricTrendView` | |
| responseTimeTrend | 否 | `RunMetricTrendView` | |
| successRateTrend | 否 | `RunMetricTrendView` | |
| errorDistribution | 否 | `DataServiceMetricDistributionView[]` | |
| topSlowApis | 否 | `DataServiceApiMetricView[]` | |
| topFailedApis | 否 | `DataServiceApiMetricView[]` | |
| subscriptionRank | 否 | `DataServiceApiMetricView[]` | |

### DataServiceMetricOptionsView

来源：`frontend/packages/api-sdk/src/types.ts:984`

```ts
interface DataServiceMetricOptionsView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| services | 是 | `DataServiceMetricOptionView[]` | |
| subscriptions | 是 | `DataServiceMetricOptionView[]` | |

### DataServiceMetricQueryRequest

来源：`frontend/packages/api-sdk/src/types.ts:1088`

```ts
interface DataServiceMetricQueryRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| serviceId | 否 | `EntityId` | |
| subscriptionId | 否 | `EntityId` | |
| serviceStatus | 否 | `string` | |
| success | 否 | `boolean` | |
| cacheHit | 否 | `boolean` | |
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| granularity | 否 | `string` | |
| logFocus | 否 | `"ALL" \| "ERROR" \| "SLOW" \| "ERROR_OR_SLOW"` | |
| minDurationMs | 否 | `number` | |
| topN | 否 | `number` | |
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |

### DataServiceResolveFieldsRequest

来源：`frontend/packages/api-sdk/src/types.ts:571`

```ts
interface DataServiceResolveFieldsRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| sourceType | 否 | `DataServiceSourceType` | |
| datasourceId | 否 | `EntityId` | |
| modelId | 否 | `EntityId` | |
| customSql | 否 | `string` | |

### DataServiceResolveFieldsView

来源：`frontend/packages/api-sdk/src/types.ts:456`

```ts
interface DataServiceResolveFieldsView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| fields | 是 | `DataServiceFieldView[]` | |
| requestParams | 是 | `DataServiceRequestParam[]` | |
| responseParams | 是 | `DataServiceResponseParam[]` | |

### DataServiceSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:550`

```ts
interface DataServiceSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| serviceCode | 是 | `string` | |
| serviceName | 是 | `string` | |
| serviceType | 否 | `DataServiceType` | |
| sourceType | 否 | `DataServiceSourceType` | |
| datasourceId | 否 | `EntityId` | |
| modelId | 否 | `EntityId` | |
| customSql | 否 | `string` | |
| requestMethod | 否 | `DataServiceRequestMethod` | |
| responseType | 否 | `DataServiceResponseType` | |
| cacheEnabled | 否 | `boolean` | |
| tokenRequired | 否 | `boolean` | |
| defaultSubscriptionName | 否 | `string` | |
| webserviceEnabled | 否 | `boolean` | |
| webserviceConfig | 否 | `WebServiceConfig` | |
| requestParams | 是 | `DataServiceRequestParam[]` | |
| responseParams | 是 | `DataServiceResponseParam[]` | |
| publishParams | 是 | `DataServicePublishParam[]` | |

### DataServiceSubscriptionView

来源：`frontend/packages/api-sdk/src/types.ts:594`

```ts
interface DataServiceSubscriptionView extends BaseRecord
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
| content | 否 | `string` | |
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
