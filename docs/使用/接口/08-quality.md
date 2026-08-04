# 数据质量规则、任务、指标和问题接口

覆盖质量规则解析/校验、质量任务预览/发布/触发、质量运行记录、资产风险和问题闭环。

## 界面范围

- `/quality-metrics` -> `frontend/apps/web/src/views/QualityMetricsView.vue`
- `/quality-rules` -> `frontend/apps/web/src/views/QualityRulesView.vue`
- `/quality-tasks` -> `frontend/apps/web/src/views/QualityTasksView.vue`
- `/quality-task-runs` -> `frontend/apps/web/src/views/QualityTaskRunsView.vue`

## 调用前置条件

- 管理端基础地址：`${BASE_URL}`，默认在线环境为 `http://127.0.0.1:18080/api/v1`。
- 认证头：`Authorization: Bearer ${TOKEN}`。
- 租户和项目上下文：大多数项目内接口需要 `X-Tenant-Id: ${TENANT_ID}` 与 `X-Project-Id: ${PROJECT_ID}`。
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页通常为 `PageResult<T>`：`{ pageNo, pageSize, total, items }`。

## 典型调用链

1. 质量规则：POST /quality-rules/parse-params -> POST /quality-rules/validate -> POST /quality-rules -> POST /quality-rules/{id}/enable-summary。
2. 质量任务：GET /quality-rules/option-summaries -> 选择运行集群 -> GET /data-development/datasource-options?runtimeClusterId=<id> -> GET /models/datasource-options -> POST /quality-tasks/preview -> POST /quality-tasks/validate -> POST /quality-tasks -> POST /quality-tasks/{id}/publish。
3. 手动执行质量任务：POST /quality-tasks/{id}/trigger -> GET /runs/page?qualityTaskId=<id> -> GET /runs/{runRecordId}/log。
4. 质量指标：GET /quality-metrics/options -> POST /quality-metrics/dashboard/query -> POST /quality-metrics/assets/query -> GET /quality-metrics/assets/{assetId}。
5. 问题闭环：POST /quality-metrics/issues/query -> GET /quality-metrics/issues/{id} -> POST /quality-metrics/issues/{id}/assign -> POST /quality-metrics/issues/{id}/status -> POST /quality-metrics/issues/{id}/comments。

## 2026-07-20 多集群增量契约

- `QualityTaskSaveRequest.runtimeClusterId` 为保存、预览和校验的必填字段；规则引用的数据源和模型必须适用于该集群。
- 编辑器先选择运行集群，再按集群加载数据源和模型。切换集群必须确认并清理不兼容绑定、参数和 SQL 预览。
- `GET /quality-tasks/workflow-options` 的 Query `runtimeClusterId` 必填，只返回可绑定到同一集群工作流的质量任务；未选择运行集群时前端不得发起请求。
- `POST /quality-tasks/{id}/trigger` 接受可选 Body `{"runtimeClusterId": 4601}`。省略 Body 使用任务保存值；定时调度不允许覆盖。
- 列表和详情返回 `runtimeClusterId/runtimeClusterName` 与 `runtimeValid/runtimeValidationMessage`。失效任务不能上线、启用调度或触发新运行。
- 质量运行仍通过 `/runs`、`/runs/page` 查询，并支持 `requestedClusterId/actualClusterId` 过滤；运行记录返回请求和实际集群信息。

以上增量字段优先于下方历史自动抽取表中未包含集群参数的旧签名。

## 前端 SDK 接口清单

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 | 前端调用点 | 源码 |
|---|---|---|---|---|---|---|
| `qualityMetrics.queryAssetsPage()` | POST | `/quality-metrics/assets/page` | payload?: QualityAssetQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `QualityAssetRiskView` | `frontend/apps/web/src/views/QualityMetricsView.vue:462` | `frontend/packages/api-sdk/src/client.ts:1438` |
| `qualityMetrics.queryAssets()` | POST | `/quality-metrics/assets/query` | payload?: QualityAssetQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `QualityAssetRiskView[]` | - | `frontend/packages/api-sdk/src/client.ts:1435` |
| `qualityMetrics.assigneeOptions()` | GET | `/quality-metrics/assignee-options` | config?: StudioRequestConfig | `QualityIssueAssigneeOptionView[]` | `frontend/apps/web/src/views/QualityMetricsView.vue:434` | `frontend/packages/api-sdk/src/client.ts:1429` |
| `qualityMetrics.queryDashboard()` | POST | `/quality-metrics/dashboard/query` | payload?: QualityMetricDashboardQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `QualityMetricDashboardView` | `frontend/apps/web/src/views/QualityMetricsView.vue:451` | `frontend/packages/api-sdk/src/client.ts:1432` |
| `qualityMetrics.queryIssuesPage()` | POST | `/quality-metrics/issues/page` | payload?: QualityIssueQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `QualityIssueView` | `frontend/apps/web/src/views/QualityMetricsView.vue:501` | `frontend/packages/api-sdk/src/client.ts:1450` |
| `qualityMetrics.queryIssues()` | POST | `/quality-metrics/issues/query` | payload?: QualityIssueQueryRequest<br>config?: StudioRequestConfig<br>body: `payload` | `QualityIssueView[]` | - | `frontend/packages/api-sdk/src/client.ts:1447` |
| `qualityMetrics.modelOptions()` | GET | `/quality-metrics/model-options` | params?: { datasourceId?: EntityId; keyword?: string; pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `RunMetricFilterOption` | `frontend/apps/web/src/views/QualityMetricsView.vue:403` | `frontend/packages/api-sdk/src/client.ts:1423` |
| `qualityMetrics.options()` | GET | `/quality-metrics/options` | config?: StudioRequestConfig | `QualityMetricOptionsView` | `frontend/apps/web/src/views/QualityMetricsView.vue:381` | `frontend/packages/api-sdk/src/client.ts:1420` |
| `qualityRules.list()` | GET | `/quality-rules` | params?: { pageNo?: number; pageSize?: number; keyword?: string; ruleDimension?: string; scopeType?: string; enabled?: boolean; } | `QualityRuleListView` | `frontend/apps/web/src/views/QualityRulesView.vue:135` | `frontend/packages/api-sdk/src/client.ts:508` |
| `qualityRules.save()` | POST | `/quality-rules` | payload: QualityRuleSaveRequest<br>body: `payload` | `QualityRuleView` | `frontend/apps/web/src/views/QualityRuleEditorView.vue:546` | `frontend/packages/api-sdk/src/client.ts:521` |
| `qualityRules.batchDelete()` | POST | `/quality-rules/batch-delete` | ids: EntityId[]<br>body: `{ ids }` | `void` | `frontend/apps/web/src/views/QualityRulesView.vue:229` | `frontend/packages/api-sdk/src/client.ts:527` |
| `qualityRules.optionSummaries()` | GET | `/quality-rules/option-summaries` | params?: { ruleDimension?: string; granularity?: string; datasourceType?: string; enabledOnly?: boolean; } | `QualityRuleOptionView[]` | `frontend/apps/web/src/views/QualityTaskEditorView.vue:373` | `frontend/packages/api-sdk/src/client.ts:556` |
| `qualityRules.options()` | GET | `/quality-rules/options` | params?: { ruleDimension?: string; granularity?: string; datasourceType?: string; enabledOnly?: boolean; } | `QualityRuleView[]` | - | `frontend/packages/api-sdk/src/client.ts:548` |
| `qualityRules.parse()` | POST | `/quality-rules/parse-params` | payload: QualityRuleParseRequest<br>body: `payload` | `QualityRuleParseResult` | `frontend/apps/web/src/views/QualityRuleEditorView.vue:461` | `frontend/packages/api-sdk/src/client.ts:542` |
| `qualityRules.validate()` | POST | `/quality-rules/validate` | payload: QualityRuleValidateRequest<br>body: `payload` | `QualityRuleValidationResult` | `frontend/apps/web/src/views/QualityRuleEditorView.vue:488` | `frontend/packages/api-sdk/src/client.ts:545` |
| `qualityTasks.list()` | GET | `/quality-tasks` | params?: { keyword?: string; status?: string; ruleDimension?: string; granularity?: string; }<br>query: `{ ...params, pageNo: 1, pageSize, }` | `unknown` | - | `frontend/packages/api-sdk/src/client.ts:1345` |
| `qualityTasks.listPage()` | GET | `/quality-tasks` | params?: { pageNo?: number; pageSize?: number; keyword?: string; status?: string; ruleDimension?: string; granularity?: string; } | `unknown` | `frontend/apps/web/src/views/QualityTasksView.vue:168` | `frontend/packages/api-sdk/src/client.ts:1333` |
| `qualityTasks.save()` | POST | `/quality-tasks` | payload: QualityTaskSaveRequest<br>body: `payload` | `QualityTaskDefinitionView` | `frontend/apps/web/src/views/QualityTaskEditorView.vue:1027` | `frontend/packages/api-sdk/src/client.ts:1397` |
| `qualityTasks.listOnline()` | GET | `/quality-tasks/online` | - | `QualityTaskListView[]` | - | `frontend/packages/api-sdk/src/client.ts:1379` |
| `qualityTasks.options()` | GET | `/quality-tasks/options` | - | `QualityTaskOptionView[]` | `frontend/apps/web/src/views/QualityTaskRunsView.vue:223` | `frontend/packages/api-sdk/src/client.ts:1382` |
| `qualityTasks.preview()` | POST | `/quality-tasks/preview` | payload: QualityTaskSaveRequest<br>body: `payload` | `QualityTaskPreviewView` | `frontend/apps/web/src/views/QualityTaskEditorView.vue:968` | `frontend/packages/api-sdk/src/client.ts:1400` |
| `qualityTasks.validate()` | POST | `/quality-tasks/validate` | payload: QualityTaskSaveRequest<br>body: `payload` | `QualityTaskValidationView` | `frontend/apps/web/src/views/QualityTaskEditorView.vue:991` | `frontend/packages/api-sdk/src/client.ts:1403` |
| `qualityTasks.workflowOptions()` | GET | `/quality-tasks/workflow-options` | params: { runtimeClusterId: EntityId; pageNo?: number; pageSize?: number; keyword?: string; } | `unknown` | `frontend/apps/web/src/views/WorkflowEditorView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `runs.list()` | GET | `/runs` | params?: RunListQuery | `RunListResponse` | `frontend/apps/web/src/views/CollectionTasksView.vue:322` | `frontend/packages/api-sdk/src/client.ts:1605` |
| `runs.listPage()` | GET | `/runs/page` | params?: RunRecordPageQuery | `RunRecordPageResponse` | `frontend/apps/web/src/views/CollectionTaskRunsView.vue:243`<br>`frontend/apps/web/src/views/QualityTaskRunsView.vue:231` | `frontend/packages/api-sdk/src/client.ts:1612` |
| `qualityMetrics.getAsset()` | GET | ``/quality-metrics/assets/${assetId}`` | assetId: string<br>params?: { startTime?: string; endTime?: string } | `QualityAssetDetailView` | `frontend/apps/web/src/views/QualityMetricsView.vue:603` | `frontend/packages/api-sdk/src/client.ts:1444` |
| `qualityMetrics.assignIssue()` | POST | ``/quality-metrics/issues/${id}/assign`` | id: EntityId<br>payload?: QualityIssueAssignRequest<br>body: `payload` | `QualityIssueDetailView` | `frontend/apps/web/src/views/QualityMetricsView.vue:668` | `frontend/packages/api-sdk/src/client.ts:1459` |
| `qualityMetrics.addIssueComment()` | POST | ``/quality-metrics/issues/${id}/comment`` | id: EntityId<br>payload: QualityIssueCommentRequest<br>body: `payload` | `QualityIssueDetailView` | `frontend/apps/web/src/views/QualityMetricsView.vue:726` | `frontend/packages/api-sdk/src/client.ts:1468` |
| `qualityMetrics.updateIssueSeverity()` | POST | ``/quality-metrics/issues/${id}/severity`` | id: EntityId<br>payload: QualityIssueSeverityRequest<br>body: `payload` | `QualityIssueDetailView` | `frontend/apps/web/src/views/QualityMetricsView.vue:705` | `frontend/packages/api-sdk/src/client.ts:1465` |
| `qualityMetrics.updateIssueStatus()` | POST | ``/quality-metrics/issues/${id}/status`` | id: EntityId<br>payload: QualityIssueStatusRequest<br>body: `payload` | `QualityIssueDetailView` | `frontend/apps/web/src/views/QualityMetricsView.vue:685` | `frontend/packages/api-sdk/src/client.ts:1462` |
| `qualityMetrics.getIssue()` | GET | ``/quality-metrics/issues/${id}`` | id: EntityId | `QualityIssueDetailView` | `frontend/apps/web/src/views/QualityMetricsView.vue:641` | `frontend/packages/api-sdk/src/client.ts:1456` |
| `qualityRules.disableSummary()` | POST | ``/quality-rules/${id}/disable-summary`` | id: EntityId | `QualityRuleListView` | `frontend/apps/web/src/views/QualityRulesView.vue:189` | `frontend/packages/api-sdk/src/client.ts:539` |
| `qualityRules.disable()` | POST | ``/quality-rules/${id}/disable`` | id: EntityId | `QualityRuleView` | - | `frontend/packages/api-sdk/src/client.ts:536` |
| `qualityRules.enableSummary()` | POST | ``/quality-rules/${id}/enable-summary`` | id: EntityId | `QualityRuleListView` | `frontend/apps/web/src/views/QualityRulesView.vue:190` | `frontend/packages/api-sdk/src/client.ts:533` |
| `qualityRules.enable()` | POST | ``/quality-rules/${id}/enable`` | id: EntityId | `QualityRuleView` | - | `frontend/packages/api-sdk/src/client.ts:530` |
| `qualityRules.delete()` | DELETE | ``/quality-rules/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/QualityRulesView.vue:208` | `frontend/packages/api-sdk/src/client.ts:524` |
| `qualityRules.get()` | GET | ``/quality-rules/${id}`` | id: EntityId | `QualityRuleView` | `frontend/apps/web/src/views/QualityRuleEditorView.vue:270`<br>`frontend/apps/web/src/views/QualityTaskEditorView.vue:399` | `frontend/packages/api-sdk/src/client.ts:518` |
| `qualityTasks.publish()` | POST | ``/quality-tasks/${id}/online`` | id: EntityId | `QualityTaskDefinitionView` | `frontend/apps/web/src/views/QualityTaskEditorView.vue:1064`<br>`frontend/apps/web/src/views/QualityTasksView.vue:239` | `frontend/packages/api-sdk/src/client.ts:1406` |
| `qualityTasks.saveSchedule()` | POST | ``/quality-tasks/${id}/schedule`` | id: EntityId<br>payload: CollectionTaskScheduleDefinition<br>body: `payload` | `QualityTaskDefinitionView` | - | `frontend/packages/api-sdk/src/client.ts:1409` |
| `qualityTasks.trigger()` | POST | ``/quality-tasks/${id}/trigger`` | id: EntityId | `void` | `frontend/apps/web/src/views/QualityTaskEditorView.vue:1088`<br>`frontend/apps/web/src/views/QualityTasksView.vue:283` | `frontend/packages/api-sdk/src/client.ts:1412` |
| `qualityTasks.delete()` | DELETE | ``/quality-tasks/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/QualityTasksView.vue:296` | `frontend/packages/api-sdk/src/client.ts:1415` |
| `qualityTasks.get()` | GET | ``/quality-tasks/${id}`` | id: EntityId | `QualityTaskDefinitionView` | `frontend/apps/web/src/views/QualityTaskEditorView.vue:319` | `frontend/packages/api-sdk/src/client.ts:1394` |
| `runs.downloadLog()` | GET | ``/runs/${id}/log/download`` | id: EntityId | `RunLogView` | `frontend/apps/web/src/components/RunLogDrawer.vue:235` | `frontend/packages/api-sdk/src/client.ts:1628` |
| `runs.getLog()` | GET | ``/runs/${id}/log`` | id: EntityId<br>params?: RunLogQuery | `RunLogView` | `frontend/apps/web/src/components/RunLogDrawer.vue:197`<br>`frontend/apps/web/src/views/DataDevelopmentView.vue:856` | `frontend/packages/api-sdk/src/client.ts:1625` |
| `runs.getSummary()` | GET | ``/runs/${id}/summary`` | id: EntityId | `RunRecordListView` | `frontend/apps/web/src/components/RunLogDrawer.vue:183` | `frontend/packages/api-sdk/src/client.ts:1622` |
| `runs.get()` | GET | ``/runs/${id}`` | id: EntityId | `RunRecord` | - | `frontend/packages/api-sdk/src/client.ts:1619` |


## 后端 Controller 对照

| Controller | 方法 | 路径 | Java 方法 | 参数来源 | 源码 |
|---|---|---|---|---|---|
| QualityMetricsController | GET | `/api/v1/quality-metrics/assets/{assetId}` | `getAssetDetail()` | path: `@PathVariable("assetId") String assetId`<br>query: `@RequestParam(value = "startTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime`<br>query: `@RequestParam(value = "endTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:91` |
| QualityMetricsController | POST | `/api/v1/quality-metrics/assets/page` | `queryAssetsPage()` | body: `@RequestBody(required = false) QualityAssetQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:85` |
| QualityMetricsController | POST | `/api/v1/quality-metrics/assets/query` | `queryAssets()` | body: `@RequestBody(required = false) QualityAssetQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:79` |
| QualityMetricsController | GET | `/api/v1/quality-metrics/assignee-options` | `assigneeOptions()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:67` |
| QualityMetricsController | POST | `/api/v1/quality-metrics/dashboard/query` | `queryDashboard()` | body: `@RequestBody(required = false) QualityMetricDashboardQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:73` |
| QualityMetricsController | GET | `/api/v1/quality-metrics/issues/{id}` | `getIssue()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:113` |
| QualityMetricsController | POST | `/api/v1/quality-metrics/issues/{id}/assign` | `assign()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) QualityIssueAssignRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:119` |
| QualityMetricsController | POST | `/api/v1/quality-metrics/issues/{id}/comment` | `addComment()` | path: `@PathVariable("id") Long id`<br>body: `@Valid @RequestBody QualityIssueCommentRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:140` |
| QualityMetricsController | POST | `/api/v1/quality-metrics/issues/{id}/severity` | `updateSeverity()` | path: `@PathVariable("id") Long id`<br>body: `@Valid @RequestBody QualityIssueSeverityRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:133` |
| QualityMetricsController | POST | `/api/v1/quality-metrics/issues/{id}/status` | `updateStatus()` | path: `@PathVariable("id") Long id`<br>body: `@Valid @RequestBody QualityIssueStatusRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:126` |
| QualityMetricsController | POST | `/api/v1/quality-metrics/issues/page` | `queryIssuesPage()` | body: `@RequestBody(required = false) QualityIssueQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:107` |
| QualityMetricsController | POST | `/api/v1/quality-metrics/issues/query` | `queryIssues()` | body: `@RequestBody(required = false) QualityIssueQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:101` |
| QualityMetricsController | GET | `/api/v1/quality-metrics/model-options` | `modelOptions()` | query: `@RequestParam(value = "datasourceId"`<br>implicit: `required = false) Long datasourceId`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:58` |
| QualityMetricsController | GET | `/api/v1/quality-metrics/options` | `options()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java:52` |
| QualityRuleController | GET | `/api/v1/quality-rules` | `list()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "ruleDimension"`<br>implicit: `required = false) String ruleDimension`<br>query: `@RequestParam(value = "scopeType"`<br>implicit: `required = false) String scopeType`<br>query: `@RequestParam(value = "enabled"`<br>implicit: `required = false) Boolean enabled` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:41` |
| QualityRuleController | POST | `/api/v1/quality-rules` | `save()` | body: `@Valid @RequestBody QualityRuleSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:76` |
| QualityRuleController | GET | `/api/v1/quality-rules/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:70` |
| QualityRuleController | DELETE | `/api/v1/quality-rules/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:82` |
| QualityRuleController | POST | `/api/v1/quality-rules/{id}/disable` | `disable()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:108` |
| QualityRuleController | POST | `/api/v1/quality-rules/{id}/disable-summary` | `disableSummary()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:114` |
| QualityRuleController | POST | `/api/v1/quality-rules/{id}/enable` | `enable()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:96` |
| QualityRuleController | POST | `/api/v1/quality-rules/{id}/enable-summary` | `enableSummary()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:102` |
| QualityRuleController | POST | `/api/v1/quality-rules/batch-delete` | `batchDelete()` | body: `@RequestBody(required = false) QualityRuleBatchDeleteRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:89` |
| QualityRuleController | GET | `/api/v1/quality-rules/option-summaries` | `optionSummaries()` | query: `@RequestParam(value = "ruleDimension"`<br>implicit: `required = false) String ruleDimension`<br>query: `@RequestParam(value = "granularity"`<br>implicit: `required = false) String granularity`<br>query: `@RequestParam(value = "datasourceType"`<br>implicit: `required = false) String datasourceType`<br>query: `@RequestParam(value = "enabledOnly"`<br>implicit: `required = false`<br>implicit: `defaultValue = "true") Boolean enabledOnly` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:61` |
| QualityRuleController | GET | `/api/v1/quality-rules/options` | `options()` | query: `@RequestParam(value = "ruleDimension"`<br>implicit: `required = false) String ruleDimension`<br>query: `@RequestParam(value = "granularity"`<br>implicit: `required = false) String granularity`<br>query: `@RequestParam(value = "datasourceType"`<br>implicit: `required = false) String datasourceType`<br>query: `@RequestParam(value = "enabledOnly"`<br>implicit: `required = false`<br>implicit: `defaultValue = "true") Boolean enabledOnly` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:52` |
| QualityRuleController | POST | `/api/v1/quality-rules/parse-params` | `parse()` | body: `@RequestBody(required = false) QualityRuleParseRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:120` |
| QualityRuleController | POST | `/api/v1/quality-rules/validate` | `validate()` | body: `@RequestBody(required = false) QualityRuleValidateRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityRuleController.java:126` |
| QualityTaskController | GET | `/api/v1/quality-tasks` | `list()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "status"`<br>implicit: `required = false) String status`<br>query: `@RequestParam(value = "ruleDimension"`<br>implicit: `required = false) String ruleDimension`<br>query: `@RequestParam(value = "granularity"`<br>implicit: `required = false) String granularity` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java:44` |
| QualityTaskController | POST | `/api/v1/quality-tasks` | `save()` | body: `@Valid @RequestBody QualityTaskSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java:81` |
| QualityTaskController | GET | `/api/v1/quality-tasks/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java:75` |
| QualityTaskController | DELETE | `/api/v1/quality-tasks/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java:119` |
| QualityTaskController | POST | `/api/v1/quality-tasks/{id}/online` | `publish()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java:99` |
| QualityTaskController | POST | `/api/v1/quality-tasks/{id}/schedule` | `updateSchedule()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) CollectionTaskScheduleDefinition schedule` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java:105` |
| QualityTaskController | POST | `/api/v1/quality-tasks/{id}/trigger` | `trigger()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java:112` |
| QualityTaskController | GET | `/api/v1/quality-tasks/online` | `listOnline()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java:55` |
| QualityTaskController | GET | `/api/v1/quality-tasks/options` | `listOptions()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java:61` |
| QualityTaskController | POST | `/api/v1/quality-tasks/preview` | `preview()` | body: `@RequestBody QualityTaskSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java:87` |
| QualityTaskController | POST | `/api/v1/quality-tasks/validate` | `validate()` | body: `@RequestBody QualityTaskSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java:93` |
| QualityTaskController | GET | `/api/v1/quality-tasks/workflow-options` | `listWorkflowOptions()` | query: `runtimeClusterId`（必填）<br>query: `pageNo/pageSize/keyword`（可选） | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityTaskController.java` |
| RunController | GET | `/api/v1/runs` | `list()` | query: `@RequestParam(value = "collectionTaskId"`<br>implicit: `required = false) Long collectionTaskId`<br>query: `@RequestParam(value = "qualityTaskId"`<br>implicit: `required = false) Long qualityTaskId`<br>query: `@RequestParam(value = "workflowDefinitionId"`<br>implicit: `required = false) Long workflowDefinitionId`<br>query: `@RequestParam(value = "startTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime`<br>query: `@RequestParam(value = "endTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime`<br>query: `@RequestParam(value = "includeRunRecords"`<br>implicit: `required = false) Boolean includeRunRecords` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:36` |
| RunController | GET | `/api/v1/runs/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:66` |
| RunController | GET | `/api/v1/runs/{id}/log` | `log()` | path: `@PathVariable("id") Long id`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSizeBytes"`<br>implicit: `required = false) Integer pageSizeBytes` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:78` |
| RunController | GET | `/api/v1/runs/{id}/log/download` | `download()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:86` |
| RunController | GET | `/api/v1/runs/{id}/summary` | `summary()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:72` |
| RunController | GET | `/api/v1/runs/page` | `listPage()` | query: `@RequestParam(value = "collectionTaskId"`<br>implicit: `required = false) Long collectionTaskId`<br>query: `@RequestParam(value = "qualityTaskId"`<br>implicit: `required = false) Long qualityTaskId`<br>query: `@RequestParam(value = "workflowDefinitionId"`<br>implicit: `required = false) Long workflowDefinitionId`<br>query: `@RequestParam(value = "collectionTaskOnly"`<br>implicit: `required = false) Boolean collectionTaskOnly`<br>query: `@RequestParam(value = "qualityTaskOnly"`<br>implicit: `required = false) Boolean qualityTaskOnly`<br>query: `@RequestParam(value = "status"`<br>implicit: `required = false) String status`<br>query: `@RequestParam(value = "startTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime`<br>query: `@RequestParam(value = "endTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:49` |


## cURL 模板

以下模板按前端 SDK 自动生成，动态路径中的 `{id}`、`{serviceId}` 等需要替换为上游接口返回值。出现 `?<query>` 时按表格中的 query 参数追加。

如果接口清单的“参数/请求体”列没有显示 `body:`，则自动化调用时删除模板中的 `-H "Content-Type: application/json"` 和 `-d` 行；只有列出 `body:` 的接口才发送 JSON 请求体。

### qualityMetrics.queryAssetsPage()

```bash
curl -X POST "${BASE_URL}/quality-metrics/assets/page" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### qualityMetrics.queryAssets()

```bash
curl -X POST "${BASE_URL}/quality-metrics/assets/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### qualityMetrics.assigneeOptions()

```bash
curl -X GET "${BASE_URL}/quality-metrics/assignee-options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### qualityMetrics.queryDashboard()

```bash
curl -X POST "${BASE_URL}/quality-metrics/dashboard/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### qualityMetrics.queryIssuesPage()

```bash
curl -X POST "${BASE_URL}/quality-metrics/issues/page" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### qualityMetrics.queryIssues()

```bash
curl -X POST "${BASE_URL}/quality-metrics/issues/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### qualityMetrics.modelOptions()

```bash
curl -X GET "${BASE_URL}/quality-metrics/model-options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### qualityMetrics.options()

```bash
curl -X GET "${BASE_URL}/quality-metrics/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### qualityRules.list()

```bash
curl -X GET "${BASE_URL}/quality-rules" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### qualityRules.save()

```bash
curl -X POST "${BASE_URL}/quality-rules" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### qualityRules.batchDelete()

```bash
curl -X POST "${BASE_URL}/quality-rules/batch-delete" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### qualityRules.optionSummaries()

```bash
curl -X GET "${BASE_URL}/quality-rules/option-summaries" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### qualityRules.options()

```bash
curl -X GET "${BASE_URL}/quality-rules/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### qualityRules.parse()

```bash
curl -X POST "${BASE_URL}/quality-rules/parse-params" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### qualityRules.validate()

```bash
curl -X POST "${BASE_URL}/quality-rules/validate" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### qualityTasks.list()

```bash
curl -X GET "${BASE_URL}/quality-tasks?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### qualityTasks.listPage()

```bash
curl -X GET "${BASE_URL}/quality-tasks" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### qualityTasks.save()

```bash
curl -X POST "${BASE_URL}/quality-tasks" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### qualityTasks.listOnline()

```bash
curl -X GET "${BASE_URL}/quality-tasks/online" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### qualityTasks.options()

```bash
curl -X GET "${BASE_URL}/quality-tasks/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```


## 开放访问 cURL 补充

开放访问接口不走管理端 `${BASE_URL}` 前缀，使用 `${STUDIO_ORIGIN}`，例如 `http://127.0.0.1:18080`。订阅令牌来自对应服务的订阅创建/轮换接口。

本模块无额外开放访问 Controller 模板。


## 主要 DTO 字段

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

### QualityAssetDetailView

来源：`frontend/packages/api-sdk/src/types.ts:2262`

```ts
interface QualityAssetDetailView extends QualityAssetRiskView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| summaryMetrics | 是 | `Record<string, unknown>` | |
| scoreTrend | 是 | `QualityScoreTrendPoint[]` | |
| activeIssues | 是 | `QualityIssueView[]` | |
| relatedTasks | 是 | `Array<Record<string, unknown>>` | |
| latestFailures | 是 | `Array<Record<string, unknown>>` | |
| fieldIssueGroups | 是 | `Array<Record<string, unknown>>` | |

### QualityAssetQueryRequest

来源：`frontend/packages/api-sdk/src/types.ts:2282`

```ts
interface QualityAssetQueryRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 否 | `EntityId` | |
| modelId | 否 | `EntityId` | |
| ruleDimension | 否 | `string` | |
| granularity | 否 | `string` | |
| taskStatus | 否 | `string` | |
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| onlyProblemAssets | 否 | `boolean` | |
| onlyLowCoverageAssets | 否 | `boolean` | |
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |

### QualityAssetRiskView

来源：`frontend/packages/api-sdk/src/types.ts:2176`

```ts
interface QualityAssetRiskView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| assetId | 否 | `string` | |
| datasourceId | 否 | `EntityId` | |
| datasourceName | 否 | `string` | |
| datasourceTypeCode | 否 | `string` | |
| modelId | 否 | `EntityId` | |
| modelName | 否 | `string` | |
| modelPhysicalLocator | 否 | `string` | |
| executionHealthScore | 否 | `number` | |
| governanceRiskScore | 否 | `number` | |
| activeIssueCount | 否 | `number` | |
| overdueIssueCount | 否 | `number` | |
| coverageRate | 否 | `number` | |
| coverageDimensions | 是 | `string[]` | |
| riskDimensions | 是 | `string[]` | |
| latestRunStatus | 否 | `string` | |
| latestRunAt | 否 | `string` | |
| latestEvidence | 否 | `string` | |

### QualityIssueAssignRequest

来源：`frontend/packages/api-sdk/src/types.ts:2311`

```ts
interface QualityIssueAssignRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| assigneeUserId | 否 | `EntityId \| null` | |

### QualityIssueCommentRequest

来源：`frontend/packages/api-sdk/src/types.ts:2325`

```ts
interface QualityIssueCommentRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| content | 是 | `string` | |

### QualityIssueDetailView

来源：`frontend/packages/api-sdk/src/types.ts:2257`

```ts
interface QualityIssueDetailView extends QualityIssueView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| currentEvidence | 否 | `Record<string, unknown>` | |
| timeline | 是 | `QualityIssueTimelineEvent[]` | |

### QualityIssueQueryRequest

来源：`frontend/packages/api-sdk/src/types.ts:2296`

```ts
interface QualityIssueQueryRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 否 | `EntityId` | |
| modelId | 否 | `EntityId` | |
| ruleDimension | 否 | `string` | |
| granularity | 否 | `string` | |
| taskStatus | 否 | `string` | |
| severity | 否 | `QualityIssueSeverity \| string` | |
| status | 否 | `QualityIssueStatus \| string` | |
| assigneeUserId | 否 | `EntityId` | |
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |

### QualityIssueSeverityRequest

来源：`frontend/packages/api-sdk/src/types.ts:2320`

```ts
interface QualityIssueSeverityRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| severity | 是 | `QualityIssueSeverity \| string` | |
| comment | 否 | `string` | |

### QualityIssueStatusRequest

来源：`frontend/packages/api-sdk/src/types.ts:2315`

```ts
interface QualityIssueStatusRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| status | 是 | `QualityIssueStatus \| string` | |
| comment | 否 | `string` | |

### QualityIssueView

来源：`frontend/packages/api-sdk/src/types.ts:2217`

```ts
interface QualityIssueView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| issueCode | 否 | `string` | |
| issueType | 否 | `string` | |
| title | 否 | `string` | |
| severity | 否 | `QualityIssueSeverity` | |
| systemSeverity | 否 | `QualityIssueSeverity` | |
| manualSeverity | 否 | `QualityIssueSeverity` | |
| status | 否 | `QualityIssueStatus` | |
| assetId | 否 | `string` | |
| datasourceId | 否 | `EntityId` | |
| datasourceName | 否 | `string` | |
| datasourceTypeCode | 否 | `string` | |
| modelId | 否 | `EntityId` | |
| modelName | 否 | `string` | |
| modelPhysicalLocator | 否 | `string` | |
| qualityTaskId | 否 | `EntityId` | |
| qualityTaskName | 否 | `string` | |
| ruleId | 否 | `EntityId` | |
| ruleName | 否 | `string` | |
| ruleDimension | 否 | `string` | |
| granularity | 否 | `string` | |
| columnName | 否 | `string` | |
| outputField | 否 | `string` | |
| firstSeenAt | 否 | `string` | |
| lastSeenAt | 否 | `string` | |
| lastRecoveryAt | 否 | `string` | |
| slaDueAt | 否 | `string` | |
| assigneeUserId | 否 | `EntityId` | |
| assigneeName | 否 | `string` | |
| latestMessage | 否 | `string` | |
| lastRunRecordId | 否 | `EntityId` | |
| lastRunStatus | 否 | `string` | |
| occurrenceCount | 否 | `number` | |
| consecutiveFailureCount | 否 | `number` | |
| reopenCount | 否 | `number` | |
| overdue | 否 | `boolean` | |
| active | 否 | `boolean` | |

### QualityMetricDashboardQueryRequest

来源：`frontend/packages/api-sdk/src/types.ts:2271`

```ts
interface QualityMetricDashboardQueryRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 否 | `EntityId` | |
| modelId | 否 | `EntityId` | |
| ruleDimension | 否 | `string` | |
| granularity | 否 | `string` | |
| taskStatus | 否 | `string` | |
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| topN | 否 | `number` | |

### QualityMetricDashboardView

来源：`frontend/packages/api-sdk/src/types.ts:2196`

```ts
interface QualityMetricDashboardView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| summaryMetrics | 是 | `Record<string, number>` | |
| scoreTrend | 是 | `QualityScoreTrendPoint[]` | |
| issueTrend | 是 | `Array<Record<string, unknown>>` | |
| dimensionDistribution | 是 | `Array<Record<string, unknown>>` | |
| coverageMatrix | 是 | `Array<Record<string, unknown>>` | |
| riskyAssets | 是 | `QualityAssetRiskView[]` | |
| noisyTargets | 是 | `Array<Record<string, unknown>>` | |

### QualityMetricOptionsView

来源：`frontend/packages/api-sdk/src/types.ts:2158`

```ts
interface QualityMetricOptionsView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasources | 是 | `RunMetricFilterOption[]` | |
| models | 是 | `RunMetricFilterOption[]` | |

### QualityRuleListView

来源：`frontend/packages/api-sdk/src/types.ts:1962`

```ts
interface QualityRuleListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| ruleName | 是 | `string` | |
| ruleCode | 是 | `string` | |
| scopeType | 否 | `QualityRuleScopeType` | |
| ruleDimension | 否 | `QualityRuleDimension` | |
| granularity | 否 | `QualityRuleGranularity` | |
| enabled | 否 | `boolean` | |
| createdBy | 否 | `EntityId` | |
| createdByName | 否 | `string` | |
| editable | 否 | `boolean` | |
| deletable | 否 | `boolean` | |

### QualityRuleParseRequest

来源：`frontend/packages/api-sdk/src/types.ts:2006`

```ts
interface QualityRuleParseRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| granularity | 否 | `QualityRuleGranularity` | |
| logicSql | 否 | `string` | |

### QualityRuleParseResult

来源：`frontend/packages/api-sdk/src/types.ts:2016`

```ts
interface QualityRuleParseResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| inputParams | 是 | `QualityRuleInputParamView[]` | |
| outputParams | 是 | `QualityRuleOutputParamView[]` | |
| warnings | 是 | `string[]` | |

### QualityRuleSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:1991`

```ts
interface QualityRuleSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| ruleName | 是 | `string` | |
| ruleCode | 是 | `string` | |
| scopeType | 是 | `QualityRuleScopeType` | |
| ruleDimension | 是 | `QualityRuleDimension` | |
| description | 否 | `string` | |
| supportedDatasourceTypes | 是 | `string[]` | |
| granularity | 是 | `QualityRuleGranularity` | |
| logicSql | 是 | `string` | |
| enabled | 否 | `boolean` | |
| inputParams | 是 | `QualityRuleInputParamSaveRequest[]` | |
| outputParams | 是 | `QualityRuleOutputParamSaveRequest[]` | |

### QualityRuleValidateRequest

来源：`frontend/packages/api-sdk/src/types.ts:2011`

```ts
interface QualityRuleValidateRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| granularity | 否 | `QualityRuleGranularity` | |
| logicSql | 否 | `string` | |

### QualityRuleValidationResult

来源：`frontend/packages/api-sdk/src/types.ts:2022`

```ts
interface QualityRuleValidationResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| valid | 否 | `boolean` | |
| message | 否 | `string` | |
| warnings | 是 | `string[]` | |

### QualityRuleView

来源：`frontend/packages/api-sdk/src/types.ts:1934`

```ts
interface QualityRuleView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| ruleName | 是 | `string` | |
| ruleCode | 是 | `string` | |
| scopeType | 否 | `QualityRuleScopeType` | |
| ruleDimension | 否 | `QualityRuleDimension` | |
| description | 否 | `string` | |
| supportedDatasourceTypes | 是 | `string[]` | |
| granularity | 否 | `QualityRuleGranularity` | |
| logicSql | 是 | `string` | |
| enabled | 否 | `boolean` | |
| createdBy | 否 | `EntityId` | |
| createdByName | 否 | `string` | |
| editable | 否 | `boolean` | |
| deletable | 否 | `boolean` | |
| inputParams | 是 | `QualityRuleInputParamView[]` | |
| outputParams | 是 | `QualityRuleOutputParamView[]` | |

### QualityTaskDefinitionView

来源：`frontend/packages/api-sdk/src/types.ts:2048`

```ts
interface QualityTaskDefinitionView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| createdBy | 否 | `EntityId` | |
| taskName | 是 | `string` | |
| taskCode | 是 | `string` | |
| status | 否 | `QualityTaskStatus` | |
| ruleId | 否 | `EntityId` | |
| ruleName | 否 | `string` | |
| ruleDimension | 否 | `QualityRuleDimension` | |
| granularity | 否 | `QualityRuleGranularity` | |
| datasourceId | 否 | `EntityId` | |
| datasourceName | 否 | `string` | |
| datasourceTypeCode | 否 | `string` | |
| modelId | 否 | `EntityId` | |
| modelName | 否 | `string` | |
| modelPhysicalLocator | 否 | `string` | |
| columnName | 否 | `string` | |
| whereClause | 否 | `string` | |
| resolvedSqlPreview | 否 | `string` | |
| parameterBindings | 是 | `QualityTaskParamBinding[]` | |
| outputParams | 是 | `QualityRuleOutputParamView[]` | |
| alertConfigs | 是 | `QualityTaskAlertConfig[]` | |
| schedule | 否 | `CollectionTaskScheduleDefinition` | |
| ruleSnapshot | 否 | `Record<string, unknown>` | |

### QualityTaskPreviewView

来源：`frontend/packages/api-sdk/src/types.ts:2133`

```ts
interface QualityTaskPreviewView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| resolvedSql | 否 | `string` | |
| warnings | 是 | `string[]` | |

### QualityTaskSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:2117`

```ts
interface QualityTaskSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| taskName | 是 | `string` | |
| taskCode | 是 | `string` | |
| ruleId | 是 | `EntityId` | |
| granularity | 是 | `QualityRuleGranularity` | |
| datasourceId | 是 | `EntityId` | |
| modelId | 是 | `EntityId` | |
| columnName | 否 | `string` | |
| whereClause | 否 | `string` | |
| resolvedSqlPreview | 否 | `string` | |
| parameterBindings | 是 | `QualityTaskParamBinding[]` | |
| alertConfigs | 是 | `QualityTaskAlertConfig[]` | |
| schedule | 否 | `CollectionTaskScheduleDefinition` | |

### QualityTaskValidationView

来源：`frontend/packages/api-sdk/src/types.ts:2138`

```ts
interface QualityTaskValidationView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| valid | 否 | `boolean` | |
| message | 否 | `string` | |
| resolvedSql | 否 | `string` | |
| columns | 是 | `string[]` | |
| rows | 是 | `Record<string, unknown>[]` | |
| outputParams | 是 | `QualityRuleOutputParamView[]` | |
| warnings | 是 | `string[]` | |
| summary | 是 | `Record<string, unknown>` | |

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

### RunMetricFilterOption

来源：`frontend/packages/api-sdk/src/types.ts:2755`

```ts
interface RunMetricFilterOption
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| name | 否 | `string` | |
| label | 否 | `string` | |
| typeCode | 否 | `string` | |

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
