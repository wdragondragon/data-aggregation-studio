# 协议转换服务接口

覆盖协议转换服务配置、发布下线、调试、订阅令牌、开放访问和调用链路追踪。

## 界面范围

- `/protocol-conversions` -> `frontend/apps/web/src/views/ProtocolConversionServicesView.vue`
- `/protocol-conversions/access-logs` -> `frontend/apps/web/src/views/ProtocolConversionAccessLogsView.vue`

## 调用前置条件

- 管理端基础地址：`${BASE_URL}`，默认在线环境为 `http://127.0.0.1:18080/api/v1`。
- 认证头：`Authorization: Bearer ${TOKEN}`。
- 租户和项目上下文：大多数项目内接口需要 `X-Tenant-Id: ${TENANT_ID}` 与 `X-Project-Id: ${PROJECT_ID}`。
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页通常为 `PageResult<T>`：`{ pageNo, pageSize, total, items }`。

## 典型调用链

1. 创建协议转换：GET /field-mapping-rules/option-summaries -> POST /protocol-conversions -> POST /protocol-conversions/{id}/publish。
2. 调试转换：GET /protocol-conversions/{id} -> POST /protocol-conversions/{id}/debug，body 中放 headers/query/body/rawBody -> 查看 trace.steps。
3. 开放调用：POST /protocol-conversions/{id}/subscriptions 创建 token -> 调用 /openapi/protocol-conversions/{serviceCode}/{serviceKey}，请求头 X-Protocol-Conversion-Token=<token>。
4. SOAP 协议转换：GET /protocol-conversions/{id}/webservice/preview -> GET /openapi/ws/protocol-conversions/{serviceCode}/{serviceKey}?wsdl -> POST /openapi/ws/protocol-conversions/{serviceCode}/{serviceKey}。
5. 日志追踪：POST /protocol-conversion-metrics/access-logs/query -> GET /protocol-conversion-metrics/access-logs/{accessLogId}/trace 或 GET /invocation-logs/PROTOCOL_CONVERSION/{accessLogId}。

## 2026-07-20 多集群增量契约

- `ProtocolConversionServiceSaveRequest.runtimeClusterId` 为必填字段。协议转换引用的数据源、模型或关联配置必须适用于该集群。
- 列表和详情返回 `runtimeClusterId/runtimeClusterName` 与 `runtimeValid/runtimeValidationMessage`。运行配置失效时不能发布、调试或接受新的转换调用。
- 对外 REST、SOAP 和 WSDL 地址保持 OMS 不变。所有运行集群统一通过目标 Worker 的 HTTP/SLB 端点执行，不存在 Server 本地执行快速路径；目标不可达返回 `503`，不自动重试、不跟随重定向，JSON/XML/SOAP 状态与响应体保持原样。
- `ProtocolConversionMetricQueryRequest` 增加 `requestedClusterId/actualClusterId`，访问日志返回相同字段。协议转换 Trace 和归档日志不得包含受管端点认证配置。
- 同步代理请求体超过统一上限时返回 `413 PAYLOAD_TOO_LARGE`，不会转发到目标集群。

以上增量字段优先于下方历史自动抽取表中未包含集群参数的旧签名。

## 前端 SDK 接口清单

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 | 前端调用点 | 源码 |
|---|---|---|---|---|---|---|
| `protocolConversionMetrics.queryAccessLogs()` | POST | `/protocol-conversion-metrics/access-logs/query` | payload?: ProtocolConversionMetricQueryRequest<br>body: `payload` | `ProtocolConversionAccessLogListView` | `frontend/apps/web/src/views/ProtocolConversionAccessLogsView.vue:268` | `frontend/packages/api-sdk/src/client.ts:881` |
| `protocolConversionMetrics.options()` | GET | `/protocol-conversion-metrics/options` | - | `DataServiceMetricOptionsView` | `frontend/apps/web/src/views/ProtocolConversionAccessLogsView.vue:260` | `frontend/packages/api-sdk/src/client.ts:878` |
| `protocolConversions.list()` | GET | `/protocol-conversions` | params?: { pageNo?: number; pageSize?: number; keyword?: string; status?: string; }<br>config?: StudioRequestConfig | `ProtocolConversionServiceListView` | `frontend/apps/web/src/views/ProtocolConversionServicesView.vue:175` | `frontend/packages/api-sdk/src/client.ts:816` |
| `protocolConversions.save()` | POST | `/protocol-conversions` | payload: ProtocolConversionServiceSaveRequest<br>body: `payload` | `ProtocolConversionServiceView` | `frontend/apps/web/src/views/ProtocolConversionServiceEditorView.vue:920` | `frontend/packages/api-sdk/src/client.ts:827` |
| `invocationLogs.download()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/download`` | domain: string<br>accessLogId: EntityId | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:210` | `frontend/packages/api-sdk/src/client.ts:1640` |
| `invocationLogs.downloadSection()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/sections/${encodeURIComponent(sectionKey)}/download`` | domain: string<br>accessLogId: EntityId<br>sectionKey: string | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:209` | `frontend/packages/api-sdk/src/client.ts:1653` |
| `invocationLogs.getSection()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/sections/${encodeURIComponent(sectionKey)}`` | domain: string<br>accessLogId: EntityId<br>sectionKey: string<br>params?: RunLogQuery | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:165` | `frontend/packages/api-sdk/src/client.ts:1646` |
| `invocationLogs.get()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}`` | domain: string<br>accessLogId: EntityId<br>params?: RunLogQuery | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:143` | `frontend/packages/api-sdk/src/client.ts:1633` |
| `protocolConversionMetrics.getAccessLogTrace()` | GET | ``/protocol-conversion-metrics/access-logs/${id}/trace`` | id: EntityId | `ProtocolConversionTraceView` | `frontend/apps/web/src/views/ProtocolConversionAccessLogsView.vue:323` | `frontend/packages/api-sdk/src/client.ts:884` |
| `protocolConversions.debug()` | POST | ``/protocol-conversions/${id}/debug`` | id: EntityId<br>payload: ProtocolConversionDebugRequest<br>body: `payload` | `ProtocolConversionDebugResult` | `frontend/apps/web/src/views/ProtocolConversionServiceEditorView.vue:964` | `frontend/packages/api-sdk/src/client.ts:845` |
| `protocolConversions.offlineSummary()` | POST | ``/protocol-conversions/${id}/offline-summary`` | id: EntityId | `ProtocolConversionServiceListView` | `frontend/apps/web/src/views/ProtocolConversionServicesView.vue:220` | `frontend/packages/api-sdk/src/client.ts:842` |
| `protocolConversions.offline()` | POST | ``/protocol-conversions/${id}/offline`` | id: EntityId | `ProtocolConversionServiceView` | - | `frontend/packages/api-sdk/src/client.ts:839` |
| `protocolConversions.publishSummary()` | POST | ``/protocol-conversions/${id}/publish-summary`` | id: EntityId | `ProtocolConversionServiceListView` | `frontend/apps/web/src/views/ProtocolConversionServicesView.vue:214` | `frontend/packages/api-sdk/src/client.ts:836` |
| `protocolConversions.publish()` | POST | ``/protocol-conversions/${id}/publish`` | id: EntityId | `ProtocolConversionServiceView` | `frontend/apps/web/src/views/ProtocolConversionServiceEditorView.vue:942` | `frontend/packages/api-sdk/src/client.ts:833` |
| `protocolConversions.disableSubscription()` | POST | ``/protocol-conversions/${id}/subscriptions/${subscriptionId}/disable`` | id: EntityId<br>subscriptionId: EntityId | `ProtocolConversionSubscriptionView` | `frontend/apps/web/src/views/ProtocolConversionServicesView.vue:300` | `frontend/packages/api-sdk/src/client.ts:858` |
| `protocolConversions.enableSubscription()` | POST | ``/protocol-conversions/${id}/subscriptions/${subscriptionId}/enable`` | id: EntityId<br>subscriptionId: EntityId | `ProtocolConversionSubscriptionView` | `frontend/apps/web/src/views/ProtocolConversionServicesView.vue:308` | `frontend/packages/api-sdk/src/client.ts:870` |
| `protocolConversions.rotateSubscription()` | POST | ``/protocol-conversions/${id}/subscriptions/${subscriptionId}/rotate`` | id: EntityId<br>subscriptionId: EntityId | `ProtocolConversionSubscriptionView` | `frontend/apps/web/src/views/ProtocolConversionServicesView.vue:291` | `frontend/packages/api-sdk/src/client.ts:864` |
| `protocolConversions.createSubscription()` | POST | ``/protocol-conversions/${id}/subscriptions`` | id: EntityId<br>subscriptionName: string<br>body: `{ subscriptionName }` | `ProtocolConversionSubscriptionView` | `frontend/apps/web/src/views/ProtocolConversionServicesView.vue:277` | `frontend/packages/api-sdk/src/client.ts:851` |
| `protocolConversions.listSubscriptions()` | GET | ``/protocol-conversions/${id}/subscriptions`` | id: EntityId | `ProtocolConversionSubscriptionView[]` | `frontend/apps/web/src/views/ProtocolConversionServicesView.vue:244` | `frontend/packages/api-sdk/src/client.ts:848` |
| `protocolConversions.delete()` | DELETE | ``/protocol-conversions/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/ProtocolConversionServicesView.vue:227` | `frontend/packages/api-sdk/src/client.ts:830` |
| `protocolConversions.get()` | GET | ``/protocol-conversions/${id}`` | id: EntityId | `ProtocolConversionServiceView` | `frontend/apps/web/src/views/ProtocolConversionServiceEditorView.vue:843` | `frontend/packages/api-sdk/src/client.ts:824` |


## 后端 Controller 对照

| Controller | 方法 | 路径 | Java 方法 | 参数来源 | 源码 |
|---|---|---|---|---|---|
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}` | `log()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSizeBytes"`<br>implicit: `required = false) Integer pageSizeBytes` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:26` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/download` | `download()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:35` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/sections/{sectionKey}` | `sectionLog()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>path: `@PathVariable("sectionKey") String sectionKey`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSizeBytes"`<br>implicit: `required = false) Integer pageSizeBytes` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:42` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/sections/{sectionKey}/download` | `downloadSection()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>path: `@PathVariable("sectionKey") String sectionKey` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:52` |
| ProtocolConversionMetricsController | GET | `/api/v1/protocol-conversion-metrics/access-logs/{id}/trace` | `accessLogTrace()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionMetricsController.java:37` |
| ProtocolConversionMetricsController | POST | `/api/v1/protocol-conversion-metrics/access-logs/query` | `queryAccessLogs()` | body: `@RequestBody(required = false) ProtocolConversionMetricQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionMetricsController.java:32` |
| ProtocolConversionMetricsController | GET | `/api/v1/protocol-conversion-metrics/options` | `options()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionMetricsController.java:27` |
| ProtocolConversionServiceController | GET | `/api/v1/protocol-conversions` | `list()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "status"`<br>implicit: `required = false) String status` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:39` |
| ProtocolConversionServiceController | POST | `/api/v1/protocol-conversions` | `save()` | body: `@Valid @RequestBody ProtocolConversionServiceSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:54` |
| ProtocolConversionServiceController | GET | `/api/v1/protocol-conversions/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:48` |
| ProtocolConversionServiceController | DELETE | `/api/v1/protocol-conversions/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:60` |
| ProtocolConversionServiceController | POST | `/api/v1/protocol-conversions/{id}/debug` | `debug()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) ProtocolConversionDebugRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:91` |
| ProtocolConversionServiceController | POST | `/api/v1/protocol-conversions/{id}/offline` | `offline()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:79` |
| ProtocolConversionServiceController | POST | `/api/v1/protocol-conversions/{id}/offline-summary` | `offlineSummary()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:85` |
| ProtocolConversionServiceController | POST | `/api/v1/protocol-conversions/{id}/publish` | `publish()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:67` |
| ProtocolConversionServiceController | POST | `/api/v1/protocol-conversions/{id}/publish-summary` | `publishSummary()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:73` |
| ProtocolConversionServiceController | GET | `/api/v1/protocol-conversions/{id}/subscriptions` | `subscriptions()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:98` |
| ProtocolConversionServiceController | POST | `/api/v1/protocol-conversions/{id}/subscriptions` | `createSubscription()` | path: `@PathVariable("id") Long id`<br>body: `@Valid @RequestBody DataServiceSubscriptionCreateRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:104` |
| ProtocolConversionServiceController | POST | `/api/v1/protocol-conversions/{id}/subscriptions/{subscriptionId}/disable` | `disableSubscription()` | path: `@PathVariable("id") Long id`<br>path: `@PathVariable("subscriptionId") Long subscriptionId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:118` |
| ProtocolConversionServiceController | POST | `/api/v1/protocol-conversions/{id}/subscriptions/{subscriptionId}/enable` | `enableSubscription()` | path: `@PathVariable("id") Long id`<br>path: `@PathVariable("subscriptionId") Long subscriptionId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:125` |
| ProtocolConversionServiceController | POST | `/api/v1/protocol-conversions/{id}/subscriptions/{subscriptionId}/rotate` | `rotateSubscription()` | path: `@PathVariable("id") Long id`<br>path: `@PathVariable("subscriptionId") Long subscriptionId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ProtocolConversionServiceController.java:111` |
| OpenProtocolConversionController | GET|POST|PUT|PATCH | `/openapi/protocol-conversions/{serviceCode}/{serviceKey}` | `invoke()` | path: `@PathVariable("serviceCode") String serviceCode`<br>path: `@PathVariable("serviceKey") String serviceKey`<br>header: `@RequestHeader(value = TOKEN_HEADER, required = false) String token`<br>query: `@RequestParam(required = false) Map<String, Object> query`<br>implicit: `HttpServletRequest request`<br>implicit: `HttpServletResponse response` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpenProtocolConversionController.java:41` |
| OpenWebServiceProtocolConversionController | GET | `/openapi/ws/protocol-conversions/{serviceCode}/{serviceKey}` | `wsdl()` | path: `@PathVariable("serviceCode") String serviceCode`<br>path: `@PathVariable("serviceKey") String serviceKey`<br>implicit: `HttpServletRequest request`<br>implicit: `HttpServletResponse response` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpenWebServiceProtocolConversionController.java:34` |
| OpenWebServiceProtocolConversionController | POST | `/openapi/ws/protocol-conversions/{serviceCode}/{serviceKey}` | `invoke()` | path: `@PathVariable("serviceCode") String serviceCode`<br>path: `@PathVariable("serviceKey") String serviceKey`<br>header: `@RequestHeader(value = TOKEN_HEADER, required = false) String token`<br>implicit: `HttpServletRequest request`<br>implicit: `HttpServletResponse response` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/OpenWebServiceProtocolConversionController.java:46` |


## cURL 模板

以下模板按前端 SDK 自动生成，动态路径中的 `{id}`、`{serviceId}` 等需要替换为上游接口返回值。出现 `?<query>` 时按表格中的 query 参数追加。

如果接口清单的“参数/请求体”列没有显示 `body:`，则自动化调用时删除模板中的 `-H "Content-Type: application/json"` 和 `-d` 行；只有列出 `body:` 的接口才发送 JSON 请求体。

### protocolConversionMetrics.queryAccessLogs()

```bash
curl -X POST "${BASE_URL}/protocol-conversion-metrics/access-logs/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### protocolConversionMetrics.options()

```bash
curl -X GET "${BASE_URL}/protocol-conversion-metrics/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### protocolConversions.list()

```bash
curl -X GET "${BASE_URL}/protocol-conversions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### protocolConversions.save()

```bash
curl -X POST "${BASE_URL}/protocol-conversions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### invocationLogs.getSection()

```bash
curl -X GET "${BASE_URL}/invocation-logs/{encodeURIComponent(domain)}/{accessLogId}/sections/{encodeURIComponent(sectionKey)}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### invocationLogs.get()

```bash
curl -X GET "${BASE_URL}/invocation-logs/{encodeURIComponent(domain)}/{accessLogId}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### protocolConversionMetrics.getAccessLogTrace()

```bash
curl -X GET "${BASE_URL}/protocol-conversion-metrics/access-logs/{id}/trace" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### protocolConversions.debug()

```bash
curl -X POST "${BASE_URL}/protocol-conversions/{id}/debug" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### protocolConversions.offlineSummary()

```bash
curl -X POST "${BASE_URL}/protocol-conversions/{id}/offline-summary" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### protocolConversions.offline()

```bash
curl -X POST "${BASE_URL}/protocol-conversions/{id}/offline" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### protocolConversions.publishSummary()

```bash
curl -X POST "${BASE_URL}/protocol-conversions/{id}/publish-summary" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### protocolConversions.publish()

```bash
curl -X POST "${BASE_URL}/protocol-conversions/{id}/publish" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### protocolConversions.disableSubscription()

```bash
curl -X POST "${BASE_URL}/protocol-conversions/{id}/subscriptions/{subscriptionId}/disable" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### protocolConversions.enableSubscription()

```bash
curl -X POST "${BASE_URL}/protocol-conversions/{id}/subscriptions/{subscriptionId}/enable" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### protocolConversions.rotateSubscription()

```bash
curl -X POST "${BASE_URL}/protocol-conversions/{id}/subscriptions/{subscriptionId}/rotate" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### protocolConversions.createSubscription()

```bash
curl -X POST "${BASE_URL}/protocol-conversions/{id}/subscriptions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### protocolConversions.listSubscriptions()

```bash
curl -X GET "${BASE_URL}/protocol-conversions/{id}/subscriptions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### protocolConversions.delete()

```bash
curl -X DELETE "${BASE_URL}/protocol-conversions/{id}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### protocolConversions.get()

```bash
curl -X GET "${BASE_URL}/protocol-conversions/{id}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```


## 开放访问 cURL 补充

开放访问接口不走管理端 `${BASE_URL}` 前缀，使用 `${STUDIO_ORIGIN}`，例如 `http://127.0.0.1:18080`。订阅令牌来自对应服务的订阅创建/轮换接口。

### OpenProtocolConversionController.invoke()

```bash
curl -X GET "${STUDIO_ORIGIN}/openapi/protocol-conversions/{serviceCode}/{serviceKey}" \
  -H "X-Protocol-Conversion-Token: ${SUBSCRIPTION_TOKEN}"
```

### OpenWebServiceProtocolConversionController.wsdl()

```bash
curl -X GET "${STUDIO_ORIGIN}/openapi/ws/protocol-conversions/{serviceCode}/{serviceKey}" \
  -H "X-Protocol-Conversion-Token: ${SUBSCRIPTION_TOKEN}"
```

### OpenWebServiceProtocolConversionController.invoke()

```bash
curl -X POST "${STUDIO_ORIGIN}/openapi/ws/protocol-conversions/{serviceCode}/{serviceKey}" \
  -H "X-Protocol-Conversion-Token: ${SUBSCRIPTION_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '<json-or-xml-body>'
```


## 主要 DTO 字段

### DataServiceMetricOptionsView

来源：`frontend/packages/api-sdk/src/types.ts:984`

```ts
interface DataServiceMetricOptionsView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| services | 是 | `DataServiceMetricOptionView[]` | |
| subscriptions | 是 | `DataServiceMetricOptionView[]` | |

### ProtocolConversionAccessLogListView

来源：`frontend/packages/api-sdk/src/types.ts:926`

```ts
interface ProtocolConversionAccessLogListView
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
| sourceProtocol | 否 | `string` | |
| targetProtocol | 否 | `string` | |
| occurredAt | 否 | `string` | |
| durationMs | 否 | `number` | |
| success | 否 | `boolean` | |
| httpStatus | 否 | `number` | |
| targetHttpStatus | 否 | `number` | |
| errorCode | 否 | `string` | |
| errorMessage | 否 | `string` | |
| clientIp | 否 | `string` | |
| userAgent | 否 | `string` | |
| receivedCount | 否 | `number` | |
| successCount | 否 | `number` | |
| failedCount | 否 | `number` | |

### ProtocolConversionDebugRequest

来源：`frontend/packages/api-sdk/src/types.ts:873`

```ts
interface ProtocolConversionDebugRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| headers | 否 | `Record<string, unknown>` | |
| query | 否 | `Record<string, unknown>` | |
| form | 否 | `Record<string, unknown>` | |
| body | 否 | `unknown` | |
| rawBody | 否 | `string` | |

### ProtocolConversionDebugResult

来源：`frontend/packages/api-sdk/src/types.ts:921`

```ts
interface ProtocolConversionDebugResult extends ProtocolConversionInvokeResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| targetRequest | 否 | `Record<string, unknown>` | |
| conversionTrace | 否 | `ProtocolConversionTraceView` | |

### ProtocolConversionMetricQueryRequest

来源：`frontend/packages/api-sdk/src/types.ts:1104`

```ts
interface ProtocolConversionMetricQueryRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| serviceId | 否 | `EntityId` | |
| subscriptionId | 否 | `EntityId` | |
| serviceStatus | 否 | `string` | |
| success | 否 | `boolean` | |
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| logFocus | 否 | `string` | |
| minDurationMs | 否 | `number` | |
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |

### ProtocolConversionServiceListView

来源：`frontend/packages/api-sdk/src/types.ts:821`

```ts
interface ProtocolConversionServiceListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| createdBy | 否 | `EntityId` | |
| serviceCode | 是 | `string` | |
| serviceName | 是 | `string` | |
| status | 否 | `ProtocolConversionStatus` | |
| endpointPath | 否 | `string` | |
| webserviceEndpointPath | 否 | `string` | |
| tokenRequired | 否 | `boolean` | |
| defaultSubscriptionName | 否 | `string` | |
| sourceProtocol | 否 | `ProtocolConversionProtocol` | |
| sourceMethod | 否 | `string` | |
| sourceDataNodePath | 否 | `string` | |
| conversionMode | 否 | `ProtocolConversionMode` | |
| targetDatasourceId | 否 | `EntityId` | |
| targetDatasourceName | 否 | `string` | |
| targetPath | 否 | `string` | |
| targetProtocol | 否 | `ProtocolConversionProtocol` | |
| targetMethod | 否 | `string` | |
| payloadMode | 否 | `DataIngestionPayloadMode` | |
| batchSize | 否 | `number` | |

### ProtocolConversionServiceSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:843`

```ts
interface ProtocolConversionServiceSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| serviceCode | 是 | `string` | |
| serviceName | 是 | `string` | |
| tokenRequired | 否 | `boolean` | |
| defaultSubscriptionName | 否 | `string` | |
| sourceProtocol | 否 | `ProtocolConversionProtocol` | |
| sourceMethod | 否 | `string` | |
| sourceDataNodePath | 否 | `string` | |
| webserviceConfig | 否 | `WebServiceConfig` | |
| conversionMode | 否 | `ProtocolConversionMode` | |
| fieldMappings | 是 | `ProtocolConversionFieldMapping[]` | |
| rawTransformers | 否 | `TransformerBinding[]` | |
| fixedFields | 否 | `ProtocolConversionFixedField[]` | |
| bodyBridgeOptions | 否 | `Record<string, unknown>` | |
| requestPassthrough | 否 | `Record<string, unknown>` | |
| targetDatasourceId | 否 | `EntityId` | |
| targetPath | 否 | `string` | |
| targetProtocol | 否 | `ProtocolConversionProtocol` | |
| targetMethod | 否 | `string` | |
| targetHeaders | 否 | `Record<string, unknown>` | |
| targetQuery | 否 | `Record<string, unknown>` | |
| targetWebserviceConfig | 否 | `WebServiceConfig` | |
| targetBodyTemplate | 否 | `string` | |
| targetDataNodePath | 否 | `string` | |
| payloadMode | 否 | `DataIngestionPayloadMode` | |
| batchSize | 否 | `number` | |
| responseStatus | 否 | `Record<string, unknown>` | |

### ProtocolConversionServiceView

来源：`frontend/packages/api-sdk/src/types.ts:786`

```ts
interface ProtocolConversionServiceView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| createdBy | 否 | `EntityId` | |
| serviceCode | 是 | `string` | |
| serviceName | 是 | `string` | |
| status | 否 | `ProtocolConversionStatus` | |
| endpointPath | 否 | `string` | |
| webserviceEndpointPath | 否 | `string` | |
| serviceKey | 否 | `string` | |
| tokenRequired | 否 | `boolean` | |
| defaultSubscriptionName | 否 | `string` | |
| sourceProtocol | 否 | `ProtocolConversionProtocol` | |
| sourceMethod | 否 | `string` | |
| sourceDataNodePath | 否 | `string` | |
| webserviceConfig | 否 | `WebServiceConfig` | |
| conversionMode | 否 | `ProtocolConversionMode` | |
| fieldMappings | 是 | `ProtocolConversionFieldMapping[]` | |
| rawTransformers | 否 | `TransformerBinding[]` | |
| fixedFields | 否 | `ProtocolConversionFixedField[]` | |
| bodyBridgeOptions | 否 | `Record<string, unknown>` | |
| requestPassthrough | 否 | `Record<string, unknown>` | |
| targetDatasourceId | 否 | `EntityId` | |
| targetDatasourceName | 否 | `string` | |
| targetPath | 否 | `string` | |
| targetProtocol | 否 | `ProtocolConversionProtocol` | |
| targetMethod | 否 | `string` | |
| targetHeaders | 否 | `Record<string, unknown>` | |
| targetQuery | 否 | `Record<string, unknown>` | |
| targetWebserviceConfig | 否 | `WebServiceConfig` | |
| targetBodyTemplate | 否 | `string` | |
| targetDataNodePath | 否 | `string` | |
| payloadMode | 否 | `DataIngestionPayloadMode` | |
| batchSize | 否 | `number` | |
| responseStatus | 否 | `Record<string, unknown>` | |

### ProtocolConversionSubscriptionView

来源：`frontend/packages/api-sdk/src/types.ts:963`

```ts
interface ProtocolConversionSubscriptionView extends BaseRecord
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

### ProtocolConversionTraceView

来源：`frontend/packages/api-sdk/src/types.ts:913`

```ts
interface ProtocolConversionTraceView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| requestId | 否 | `string` | |
| sourceRequest | 否 | `ProtocolConversionTraceStepView` | |
| convertedRequest | 否 | `ProtocolConversionTraceStepView` | |
| targetResponse | 否 | `ProtocolConversionTraceStepView` | |
| convertedResponse | 否 | `ProtocolConversionTraceStepView` | |

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
