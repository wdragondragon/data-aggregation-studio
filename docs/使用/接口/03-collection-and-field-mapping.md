# 字段映射规则、采集任务和采集运行接口

覆盖字段映射规则、采集任务配置、发布、触发、运行记录和采集运行指标。

## 界面范围

- `/field-mapping-rules` -> `frontend/apps/web/src/views/FieldMappingRulesView.vue`
- `/collection-tasks` -> `frontend/apps/web/src/views/CollectionTasksView.vue`
- `/collection-task-runs` -> `frontend/apps/web/src/views/CollectionTaskRunsView.vue`
- `/run-metrics` -> `frontend/apps/web/src/views/RunMetricsView.vue`

## 调用前置条件

- 管理端基础地址：`${BASE_URL}`，默认在线环境为 `http://127.0.0.1:18080/api/v1`。
- 认证头：`Authorization: Bearer ${TOKEN}`。
- 租户和项目上下文：大多数项目内接口需要 `X-Tenant-Id: ${TENANT_ID}` 与 `X-Project-Id: ${PROJECT_ID}`。
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页通常为 `PageResult<T>`：`{ pageNo, pageSize, total, items }`。

## 典型调用链

1. 字段映射规则：GET /field-mapping-rules/option-summaries -> GET /field-mapping-rules/{id} -> POST /field-mapping-rules 保存规则 -> 在数据服务、采集、协议转换中引用规则 ID。
2. 创建采集任务：先选择运行集群，GET /data-development/datasource-options?runtimeClusterId=<id> -> GET /models/datasource-options?datasourceId=<source> -> GET /models/datasource-options?datasourceId=<target> -> POST /collection-tasks/preview -> POST /collection-tasks -> POST /collection-tasks/{id}/online。
3. 手动触发采集：POST /collection-tasks/{id}/trigger -> GET /runs/page?collectionTaskId=<id> -> GET /runs/{runRecordId}/log?pageNo=1&pageSizeBytes=65536。
4. 采集指标：GET /run-metrics/options -> POST /run-metrics/query，按 datasourceId/sourceModelId/targetModelId/startTime/endTime/topN 聚合。

`GET /run-metrics/options` 中的 `data.datasources` 返回当前租户、项目可访问的轻量数据源选项，不要求数据源已经被采集任务绑定；`data.sourceModels` 和 `data.targetModels` 仍来自采集任务指标绑定快照。这样项目只有数据源、尚未生成任务绑定时，指标页面的数据源下拉也可正常选择。

## 2026-07-20 多集群增量契约

- `CollectionTaskSaveRequest.runtimeClusterId` 为新建、编辑和预览的必填字段。来源和目标数据源、模型必须全部适用于同一运行集群。
- 编辑器先调用 `GET /runtime-clusters/options` 选择运行集群，再调用 `/datasources/options?runtimeClusterId=...` 和相应模型选项接口。切换集群时必须确认，并清空不再适用的数据源、模型、字段映射和预览结果。
- `GET /collection-tasks/workflow-options` 的 Query `runtimeClusterId` 必填，只返回可绑定到同一集群工作流的任务；未选择运行集群时前端不得发起请求。
- `POST /collection-tasks/{id}/trigger` 接受可选 Body `{"runtimeClusterId": 4601}`。省略 Body 使用任务保存的集群；覆盖仅在项目允许手动覆盖且全部依赖适用时成功。
- 列表和详情返回 `runtimeClusterId/runtimeClusterName` 以及 `runtimeValid/runtimeValidationMessage`。失效任务可以编辑，但不能上线、启用调度或触发新运行。
- `GET /runs` 和 `GET /runs/page` 支持 `requestedClusterId/actualClusterId` 过滤，运行记录返回请求集群、实际集群和实际集群编码。

定时运行不接受覆盖，始终使用任务保存的集群；目标集群离线时 Dispatch 保留排队，不会转移到其它集群。以上契约优先于下方历史自动抽取表中的旧签名。

## 前端 SDK 接口清单

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 | 前端调用点 | 源码 |
|---|---|---|---|---|---|---|
| `collectionTasks.list()` | GET | `/collection-tasks` | params?: CollectionTaskListQuery<br>config?: StudioRequestConfig | `CollectionTaskListView[]` | - | `frontend/packages/api-sdk/src/client.ts:1276` |
| `collectionTasks.save()` | POST | `/collection-tasks` | payload: CollectionTaskSaveRequest<br>body: `payload` | `CollectionTaskDefinitionView` | `frontend/apps/web/src/views/CollectionTaskEditorView.vue:1296` | `frontend/packages/api-sdk/src/client.ts:1302` |
| `collectionTasks.listOnline()` | GET | `/collection-tasks/online` | - | `CollectionTaskListView[]` | - | `frontend/packages/api-sdk/src/client.ts:1287` |
| `collectionTasks.options()` | GET | `/collection-tasks/options` | config?: StudioRequestConfig | `CollectionTaskOptionView[]` | `frontend/apps/web/src/views/CollectionTaskRunsView.vue:235` | `frontend/packages/api-sdk/src/client.ts:1279` |
| `collectionTasks.listPage()` | GET | `/collection-tasks/page` | params?: CollectionTaskListQuery & { pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `unknown` | `frontend/apps/web/src/views/CollectionTasksView.vue:235` | `frontend/packages/api-sdk/src/client.ts:1282` |
| `collectionTasks.preview()` | POST | `/collection-tasks/preview` | payload: CollectionTaskSaveRequest<br>body: `payload` | `JobContainerConfig` | `frontend/apps/web/src/views/CollectionTaskEditorView.vue:1277` | `frontend/packages/api-sdk/src/client.ts:1305` |
| `collectionTasks.workflowOptions()` | GET | `/collection-tasks/workflow-options` | params: { runtimeClusterId: EntityId; pageNo?: number; pageSize?: number; keyword?: string; } | `unknown` | `frontend/apps/web/src/views/WorkflowEditorView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `fieldMappingRules.list()` | GET | `/field-mapping-rules` | params?: { pageNo?: number; pageSize?: number; keyword?: string; mappingType?: string; enabled?: boolean; } | `FieldMappingRuleListView` | `frontend/apps/web/src/views/FieldMappingRulesView.vue:117` | `frontend/packages/api-sdk/src/client.ts:474` |
| `fieldMappingRules.save()` | POST | `/field-mapping-rules` | payload: FieldMappingRuleSaveRequest<br>body: `payload` | `FieldMappingRuleView` | `frontend/apps/web/src/views/FieldMappingRuleEditorView.vue:310` | `frontend/packages/api-sdk/src/client.ts:486` |
| `fieldMappingRules.optionSummaries()` | GET | `/field-mapping-rules/option-summaries` | mappingType?: string<br>query: `mappingType ? { mappingType } : undefined` | `FieldMappingRuleOptionView[]` | `frontend/apps/web/src/views/CollectionTaskEditorView.vue:336`<br>`frontend/apps/web/src/views/DataServiceEditorView.vue:799`<br>`frontend/apps/web/src/views/ProtocolConversionServiceEditorView.vue:821` | `frontend/packages/api-sdk/src/client.ts:499` |
| `fieldMappingRules.options()` | GET | `/field-mapping-rules/options` | mappingType?: string<br>query: `mappingType ? { mappingType } : undefined` | `FieldMappingRuleView[]` | - | `frontend/packages/api-sdk/src/client.ts:492` |
| `runMetrics.options()` | GET | `/run-metrics/options` | - | `RunMetricOptionsView` | `frontend/apps/web/src/views/RunMetricsView.vue:276` | `frontend/packages/api-sdk/src/client.ts:1661` |
| `runMetrics.query()` | POST | `/run-metrics/query` | payload?: RunMetricDashboardQueryRequest<br>body: `payload` | `RunMetricDashboardResponse` | `frontend/apps/web/src/views/RunMetricsView.vue:287` | `frontend/packages/api-sdk/src/client.ts:1664` |
| `runs.list()` | GET | `/runs` | params?: RunListQuery | `RunListResponse` | `frontend/apps/web/src/views/CollectionTasksView.vue:322` | `frontend/packages/api-sdk/src/client.ts:1605` |
| `runs.listPage()` | GET | `/runs/page` | params?: RunRecordPageQuery | `RunRecordPageResponse` | `frontend/apps/web/src/views/CollectionTaskRunsView.vue:243`<br>`frontend/apps/web/src/views/QualityTaskRunsView.vue:231` | `frontend/packages/api-sdk/src/client.ts:1612` |
| `collectionTasks.resetIncrementalCursor()` | POST | ``/collection-tasks/${id}/incremental-cursors/reset`` | id: EntityId<br>sourceAlias?: string<br>scope?: { incrColumn?: string; incrModel?: string }<br>query: `{ ...(sourceAlias ? { sourceAlias } : {}), ...(scope?.incrColumn ? { incrColumn: scope.incrColumn } : {}), ...(scope?.incrModel ? { incrModel: scope.incrModel } : {}), }` | `CollectionTaskDefinitionView` | `frontend/apps/web/src/views/CollectionTaskEditorView.vue:1402` | `frontend/packages/api-sdk/src/client.ts:1314` |
| `collectionTasks.publish()` | POST | ``/collection-tasks/${id}/online`` | id: EntityId | `CollectionTaskListView` | `frontend/apps/web/src/views/CollectionTasksView.vue:341` | `frontend/packages/api-sdk/src/client.ts:1308` |
| `collectionTasks.saveSchedule()` | POST | ``/collection-tasks/${id}/schedule`` | id: EntityId<br>payload: CollectionTaskScheduleDefinition<br>body: `payload` | `CollectionTaskDefinitionView` | - | `frontend/packages/api-sdk/src/client.ts:1311` |
| `collectionTasks.trigger()` | POST | ``/collection-tasks/${id}/trigger`` | id: EntityId | `void` | `frontend/apps/web/src/views/CollectionTasksView.vue:376` | `frontend/packages/api-sdk/src/client.ts:1325` |
| `collectionTasks.delete()` | DELETE | ``/collection-tasks/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/CollectionTasksView.vue:393` | `frontend/packages/api-sdk/src/client.ts:1328` |
| `collectionTasks.get()` | GET | ``/collection-tasks/${id}`` | id: EntityId | `CollectionTaskDefinitionView` | `frontend/apps/web/src/views/CollectionTaskEditorView.vue:357` | `frontend/packages/api-sdk/src/client.ts:1299` |
| `fieldMappingRules.delete()` | DELETE | ``/field-mapping-rules/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/FieldMappingRulesView.vue:163` | `frontend/packages/api-sdk/src/client.ts:489` |
| `fieldMappingRules.get()` | GET | ``/field-mapping-rules/${id}`` | id: EntityId | `FieldMappingRuleView` | `frontend/apps/web/src/views/CollectionTaskEditorView.vue:380`<br>`frontend/apps/web/src/views/DataServiceEditorView.vue:820`<br>`frontend/apps/web/src/views/FieldMappingRuleEditorView.vue:179`<br>`frontend/apps/web/src/views/ProtocolConversionServiceEditorView.vue:837` | `frontend/packages/api-sdk/src/client.ts:483` |
| `runs.downloadLog()` | GET | ``/runs/${id}/log/download`` | id: EntityId | `RunLogView` | `frontend/apps/web/src/components/RunLogDrawer.vue:235` | `frontend/packages/api-sdk/src/client.ts:1628` |
| `runs.getLog()` | GET | ``/runs/${id}/log`` | id: EntityId<br>params?: RunLogQuery | `RunLogView` | `frontend/apps/web/src/components/RunLogDrawer.vue:197`<br>`frontend/apps/web/src/views/DataDevelopmentView.vue:856` | `frontend/packages/api-sdk/src/client.ts:1625` |
| `runs.getSummary()` | GET | ``/runs/${id}/summary`` | id: EntityId | `RunRecordListView` | `frontend/apps/web/src/components/RunLogDrawer.vue:183` | `frontend/packages/api-sdk/src/client.ts:1622` |
| `runs.get()` | GET | ``/runs/${id}`` | id: EntityId | `RunRecord` | - | `frontend/packages/api-sdk/src/client.ts:1619` |


## 后端 Controller 对照

| Controller | 方法 | 路径 | Java 方法 | 参数来源 | 源码 |
|---|---|---|---|---|---|
| CollectionTaskController | GET | `/api/v1/collection-tasks` | `list()` | query: `@RequestParam(value = "name"`<br>implicit: `required = false) String name`<br>query: `@RequestParam(value = "targetDatasource"`<br>implicit: `required = false) String targetDatasource`<br>query: `@RequestParam(value = "targetModel"`<br>implicit: `required = false) String targetModel` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:43` |
| CollectionTaskController | POST | `/api/v1/collection-tasks` | `save()` | body: `@Valid @RequestBody CollectionTaskSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:87` |
| CollectionTaskController | GET | `/api/v1/collection-tasks/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:81` |
| CollectionTaskController | DELETE | `/api/v1/collection-tasks/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:128` |
| CollectionTaskController | POST | `/api/v1/collection-tasks/{id}/incremental-cursors/reset` | `resetIncrementalCursor()` | path: `@PathVariable("id") Long id`<br>query: `@RequestParam(value = "sourceAlias"`<br>implicit: `required = false) String sourceAlias`<br>query: `@RequestParam(value = "incrColumn"`<br>implicit: `required = false) String incrColumn`<br>query: `@RequestParam(value = "incrModel"`<br>implicit: `required = false) String incrModel` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:112` |
| CollectionTaskController | POST | `/api/v1/collection-tasks/{id}/online` | `publish()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:99` |
| CollectionTaskController | POST | `/api/v1/collection-tasks/{id}/schedule` | `updateSchedule()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) CollectionTaskScheduleDefinition request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:105` |
| CollectionTaskController | POST | `/api/v1/collection-tasks/{id}/trigger` | `trigger()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:121` |
| CollectionTaskController | GET | `/api/v1/collection-tasks/online` | `listOnline()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:67` |
| CollectionTaskController | GET | `/api/v1/collection-tasks/options` | `options()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:61` |
| CollectionTaskController | GET | `/api/v1/collection-tasks/page` | `listPage()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "name"`<br>implicit: `required = false) String name`<br>query: `@RequestParam(value = "targetDatasource"`<br>implicit: `required = false) String targetDatasource`<br>query: `@RequestParam(value = "targetModel"`<br>implicit: `required = false) String targetModel` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:51` |
| CollectionTaskController | POST | `/api/v1/collection-tasks/preview` | `preview()` | body: `@RequestBody CollectionTaskSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java:93` |
| CollectionTaskController | GET | `/api/v1/collection-tasks/workflow-options` | `listWorkflowOptions()` | query: `runtimeClusterId`（必填）<br>query: `pageNo/pageSize/keyword`（可选） | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java` |
| FieldMappingRuleController | GET | `/api/v1/field-mapping-rules` | `list()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "mappingType"`<br>implicit: `required = false) String mappingType`<br>query: `@RequestParam(value = "enabled"`<br>implicit: `required = false) Boolean enabled` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/FieldMappingRuleController.java:36` |
| FieldMappingRuleController | POST | `/api/v1/field-mapping-rules` | `save()` | body: `@Valid @RequestBody FieldMappingRuleSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/FieldMappingRuleController.java:64` |
| FieldMappingRuleController | GET | `/api/v1/field-mapping-rules/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/FieldMappingRuleController.java:58` |
| FieldMappingRuleController | DELETE | `/api/v1/field-mapping-rules/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/FieldMappingRuleController.java:70` |
| FieldMappingRuleController | GET | `/api/v1/field-mapping-rules/option-summaries` | `optionSummaries()` | query: `@RequestParam(value = "mappingType"`<br>implicit: `required = false) String mappingType` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/FieldMappingRuleController.java:52` |
| FieldMappingRuleController | GET | `/api/v1/field-mapping-rules/options` | `options()` | query: `@RequestParam(value = "mappingType"`<br>implicit: `required = false) String mappingType` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/FieldMappingRuleController.java:46` |
| RunMetricsController | GET | `/api/v1/run-metrics/options` | `options()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunMetricsController.java:28` |
| RunMetricsController | POST | `/api/v1/run-metrics/query` | `query()` | body: `@RequestBody(required = false) RunMetricDashboardQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunMetricsController.java:34` |
| RunController | GET | `/api/v1/runs` | `list()` | query: `@RequestParam(value = "collectionTaskId"`<br>implicit: `required = false) Long collectionTaskId`<br>query: `@RequestParam(value = "qualityTaskId"`<br>implicit: `required = false) Long qualityTaskId`<br>query: `@RequestParam(value = "workflowDefinitionId"`<br>implicit: `required = false) Long workflowDefinitionId`<br>query: `@RequestParam(value = "startTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime`<br>query: `@RequestParam(value = "endTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime`<br>query: `@RequestParam(value = "includeRunRecords"`<br>implicit: `required = false) Boolean includeRunRecords` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:36` |
| RunController | GET | `/api/v1/runs/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:66` |
| RunController | GET | `/api/v1/runs/{id}/log` | `log()` | path: `@PathVariable("id") Long id`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSizeBytes"`<br>implicit: `required = false) Integer pageSizeBytes` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:78` |
| RunController | GET | `/api/v1/runs/{id}/log/download` | `download()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:86` |
| RunController | GET | `/api/v1/runs/{id}/summary` | `summary()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:72` |
| RunController | GET | `/api/v1/runs/page` | `listPage()` | query: `@RequestParam(value = "collectionTaskId"`<br>implicit: `required = false) Long collectionTaskId`<br>query: `@RequestParam(value = "qualityTaskId"`<br>implicit: `required = false) Long qualityTaskId`<br>query: `@RequestParam(value = "workflowDefinitionId"`<br>implicit: `required = false) Long workflowDefinitionId`<br>query: `@RequestParam(value = "collectionTaskOnly"`<br>implicit: `required = false) Boolean collectionTaskOnly`<br>query: `@RequestParam(value = "qualityTaskOnly"`<br>implicit: `required = false) Boolean qualityTaskOnly`<br>query: `@RequestParam(value = "status"`<br>implicit: `required = false) String status`<br>query: `@RequestParam(value = "startTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime`<br>query: `@RequestParam(value = "endTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:49` |


## cURL 模板

以下模板按前端 SDK 自动生成，动态路径中的 `{id}`、`{serviceId}` 等需要替换为上游接口返回值。出现 `?<query>` 时按表格中的 query 参数追加。

如果接口清单的“参数/请求体”列没有显示 `body:`，则自动化调用时删除模板中的 `-H "Content-Type: application/json"` 和 `-d` 行；只有列出 `body:` 的接口才发送 JSON 请求体。

### collectionTasks.list()

```bash
curl -X GET "${BASE_URL}/collection-tasks" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### collectionTasks.save()

```bash
curl -X POST "${BASE_URL}/collection-tasks" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### collectionTasks.listOnline()

```bash
curl -X GET "${BASE_URL}/collection-tasks/online" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### collectionTasks.options()

```bash
curl -X GET "${BASE_URL}/collection-tasks/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### collectionTasks.listPage()

```bash
curl -X GET "${BASE_URL}/collection-tasks/page" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### collectionTasks.preview()

```bash
curl -X POST "${BASE_URL}/collection-tasks/preview" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### collectionTasks.workflowOptions()

```bash
curl -X GET "${BASE_URL}/collection-tasks/workflow-options?runtimeClusterId=${RUNTIME_CLUSTER_ID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### fieldMappingRules.list()

```bash
curl -X GET "${BASE_URL}/field-mapping-rules" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### fieldMappingRules.save()

```bash
curl -X POST "${BASE_URL}/field-mapping-rules" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### fieldMappingRules.optionSummaries()

```bash
curl -X GET "${BASE_URL}/field-mapping-rules/option-summaries?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### fieldMappingRules.options()

```bash
curl -X GET "${BASE_URL}/field-mapping-rules/options?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### runMetrics.options()

```bash
curl -X GET "${BASE_URL}/run-metrics/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### runMetrics.query()

```bash
curl -X POST "${BASE_URL}/run-metrics/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### runs.list()

```bash
curl -X GET "${BASE_URL}/runs" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### runs.listPage()

```bash
curl -X GET "${BASE_URL}/runs/page" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### collectionTasks.resetIncrementalCursor()

```bash
curl -X POST "${BASE_URL}/collection-tasks/{id}/incremental-cursors/reset?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### collectionTasks.publish()

```bash
curl -X POST "${BASE_URL}/collection-tasks/{id}/online" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### collectionTasks.saveSchedule()

```bash
curl -X POST "${BASE_URL}/collection-tasks/{id}/schedule" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### collectionTasks.trigger()

```bash
curl -X POST "${BASE_URL}/collection-tasks/{id}/trigger" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### collectionTasks.delete()

```bash
curl -X DELETE "${BASE_URL}/collection-tasks/{id}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```


## 开放访问 cURL 补充

开放访问接口不走管理端 `${BASE_URL}` 前缀，使用 `${STUDIO_ORIGIN}`，例如 `http://127.0.0.1:18080`。订阅令牌来自对应服务的订阅创建/轮换接口。

本模块无额外开放访问 Controller 模板。


## 主要 DTO 字段

### CollectionTaskDefinitionView

来源：`frontend/packages/api-sdk/src/types.ts:1660`

```ts
interface CollectionTaskDefinitionView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| name | 是 | `string` | |
| taskType | 否 | `CollectionTaskType` | |
| status | 否 | `CollectionTaskStatus` | |
| sourceCount | 否 | `number` | |
| sourceBindings | 是 | `CollectionTaskSourceBinding[]` | |
| targetBinding | 否 | `CollectionTaskTargetBinding` | |
| fieldMappings | 是 | `FieldMappingDefinition[]` | |
| executionOptions | 是 | `Record<string, unknown>` | |
| schedule | 否 | `CollectionTaskScheduleDefinition` | |

### CollectionTaskListQuery

来源：`frontend/packages/api-sdk/src/types.ts:1699`

```ts
interface CollectionTaskListQuery
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| name | 否 | `string` | |
| targetDatasource | 否 | `string` | |
| targetModel | 否 | `string` | |

### CollectionTaskListView

来源：`frontend/packages/api-sdk/src/types.ts:1672`

```ts
interface CollectionTaskListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| name | 是 | `string` | |
| taskType | 否 | `CollectionTaskType` | |
| status | 否 | `CollectionTaskStatus` | |
| sourceCount | 否 | `number` | |
| targetDatasourceName | 否 | `string` | |
| targetDatasourceTypeCode | 否 | `string` | |
| targetModelName | 否 | `string` | |
| targetModelPhysicalLocator | 否 | `string` | |
| schedule | 否 | `CollectionTaskScheduleDefinition` | |

### CollectionTaskSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:1705`

```ts
interface CollectionTaskSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| name | 是 | `string` | |
| sourceBindings | 是 | `CollectionTaskSourceBinding[]` | |
| targetBinding | 是 | `CollectionTaskTargetBinding` | |
| fieldMappings | 是 | `FieldMappingDefinition[]` | |
| executionOptions | 是 | `Record<string, unknown>` | |
| schedule | 否 | `CollectionTaskScheduleDefinition` | |

### CollectionTaskScheduleDefinition

来源：`frontend/packages/api-sdk/src/types.ts:1654`

```ts
interface CollectionTaskScheduleDefinition
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| cronExpression | 否 | `string` | |
| enabled | 否 | `boolean` | |
| timezone | 否 | `string` | |

### FieldMappingRuleListView

来源：`frontend/packages/api-sdk/src/types.ts:1577`

```ts
interface FieldMappingRuleListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| mappingName | 是 | `string` | |
| mappingType | 是 | `string` | |
| mappingCode | 是 | `string` | |
| enabled | 否 | `boolean` | |
| createdByName | 否 | `string` | |

### FieldMappingRuleSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:1594`

```ts
interface FieldMappingRuleSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| mappingName | 是 | `string` | |
| mappingType | 是 | `string` | |
| mappingCode | 是 | `string` | |
| enabled | 否 | `boolean` | |
| description | 否 | `string` | |
| params | 是 | `FieldMappingRuleParamSaveRequest[]` | |

### FieldMappingRuleView

来源：`frontend/packages/api-sdk/src/types.ts:1559`

```ts
interface FieldMappingRuleView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| mappingName | 是 | `string` | |
| mappingType | 是 | `string` | |
| mappingCode | 是 | `string` | |
| enabled | 否 | `boolean` | |
| description | 否 | `string` | |
| createdBy | 否 | `EntityId` | |
| createdByName | 否 | `string` | |
| params | 是 | `FieldMappingRuleParamView[]` | |

### JobContainerConfig

来源：`frontend/packages/api-sdk/src/types.ts:1715`

```ts
type JobContainerConfig = Record<string, unknown>;
```

### RunListQuery

来源：`frontend/packages/api-sdk/src/types.ts:2845`

```ts
interface RunListQuery
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| collectionTaskId | 否 | `EntityId` | |
| qualityTaskId | 否 | `EntityId` | |
| workflowDefinitionId | 否 | `EntityId` | |
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| includeRunRecords | 否 | `boolean` | |

### RunListResponse

来源：`frontend/packages/api-sdk/src/types.ts:2840`

```ts
interface RunListResponse
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| queuedTasks | 是 | `QueuedTaskListView[]` | |
| runRecords | 是 | `RunRecordListView[]` | |

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
| content | 否 | `string` | `RunLogProxyService` 和日志存储服务会在返回前使用 `StudioSensitiveLogSanitizer` 对密码、Token、Secret 等敏感内容脱敏；自动化不得依赖响应中出现原始敏感值。 |
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

### RunMetricDashboardQueryRequest

来源：`frontend/packages/api-sdk/src/types.ts:2796`

```ts
interface RunMetricDashboardQueryRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 否 | `EntityId` | |
| sourceModelId | 否 | `EntityId` | |
| targetModelId | 否 | `EntityId` | |
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| granularity | 否 | `"DAY" \| "WEEK" \| "MONTH" \| string` | |
| topN | 否 | `number` | |

### RunMetricDashboardResponse

来源：`frontend/packages/api-sdk/src/types.ts:2787`

```ts
interface RunMetricDashboardResponse
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| trend | 是 | `RunMetricTrendView` | |
| sourceDatasourceTopN | 是 | `RunMetricTopNItem[]` | |
| targetDatasourceTopN | 是 | `RunMetricTopNItem[]` | |
| sourceModelTopN | 是 | `RunMetricTopNItem[]` | |
| targetModelTopN | 是 | `RunMetricTopNItem[]` | |
| legacyRunCount | 否 | `number \| string \| null` | |

### RunMetricOptionsView

来源：`frontend/packages/api-sdk/src/types.ts:2762`

```ts
interface RunMetricOptionsView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasources | 是 | `RunMetricFilterOption[]` | |
| sourceModels | 是 | `RunMetricFilterOption[]` | |
| targetModels | 是 | `RunMetricFilterOption[]` | |

### RunRecord

来源：`frontend/packages/api-sdk/src/types.ts:2689`

```ts
interface RunRecord extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| executionType | 否 | `string` | |
| workflowRunId | 否 | `EntityId` | |
| workflowDefinitionId | 否 | `EntityId` | |
| workflowVersionId | 否 | `EntityId` | |
| workflowName | 否 | `string` | |
| collectionTaskId | 否 | `EntityId` | |
| collectionTaskName | 否 | `string` | |
| qualityTaskId | 否 | `EntityId` | |
| qualityTaskName | 否 | `string` | |
| nodeCode | 否 | `string` | |
| workerGroupCode | 否 | `string` | |
| workerCode | 否 | `string` | |
| workerInstanceId | 否 | `string` | |
| workerPodName | 否 | `string` | |
| workerNodeName | 否 | `string` | |
| status | 否 | `string` | |
| message | 否 | `string` | |
| startedAt | 否 | `string` | |
| endedAt | 否 | `string` | |
| logFilePath | 否 | `string` | |
| logSizeBytes | 否 | `number` | |
| logCharset | 否 | `string` | |
| metricSummary | 否 | `RunMetricSummary` | |
| payloadJson | 否 | `Record<string, unknown>` | |
| resultJson | 否 | `Record<string, unknown>` | |

### RunRecordListView

来源：`frontend/packages/api-sdk/src/types.ts:2717`

```ts
interface RunRecordListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| executionType | 否 | `string` | |
| workflowRunId | 否 | `EntityId` | |
| workflowDefinitionId | 否 | `EntityId` | |
| workflowVersionId | 否 | `EntityId` | |
| workflowName | 否 | `string` | |
| collectionTaskId | 否 | `EntityId` | |
| collectionTaskName | 否 | `string` | |
| qualityTaskId | 否 | `EntityId` | |
| qualityTaskName | 否 | `string` | |
| nodeCode | 否 | `string` | |
| workerGroupCode | 否 | `string` | |
| workerCode | 否 | `string` | |
| workerInstanceId | 否 | `string` | |
| workerPodName | 否 | `string` | |
| workerNodeName | 否 | `string` | |
| status | 否 | `string` | |
| message | 否 | `string` | |
| startedAt | 否 | `string` | |
| endedAt | 否 | `string` | |
| durationMs | 否 | `number` | |
| metricSummary | 否 | `RunMetricSummary` | |

### RunRecordPageQuery

来源：`frontend/packages/api-sdk/src/types.ts:2854`

```ts
interface RunRecordPageQuery
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| collectionTaskId | 否 | `EntityId` | |
| qualityTaskId | 否 | `EntityId` | |
| workflowDefinitionId | 否 | `EntityId` | |
| collectionTaskOnly | 否 | `boolean` | |
| qualityTaskOnly | 否 | `boolean` | |
| status | 否 | `string` | |
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |

### RunRecordPageResponse

来源：`frontend/packages/api-sdk/src/types.ts:2867`

```ts
interface RunRecordPageResponse extends PageResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| failedCount | 否 | `number \| string \| null` | |
| runningCount | 否 | `number \| string \| null` | |
| successCount | 否 | `number \| string \| null` | |
