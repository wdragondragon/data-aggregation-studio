# 数据开发、工作流与运行日志接口

覆盖脚本目录、SQL/脚本执行、脚本环境、工作流定义、运行列表和日志查看下载。

## 界面范围

- `/data-development` -> `frontend/apps/web/src/views/DataDevelopmentView.vue`
- `/workflows` -> `frontend/apps/web/src/views/WorkflowsView.vue`
- `/runs` -> `frontend/apps/web/src/views/RunsView.vue`
- `/runs/:workflowRunId` -> `frontend/apps/web/src/views/WorkflowRunDetailView.vue`

## 调用前置条件

- 管理端基础地址：`${BASE_URL}`，默认在线环境为 `http://127.0.0.1:18080/api/v1`。
- 认证头：`Authorization: Bearer ${TOKEN}`。
- 租户和项目上下文：大多数项目内接口需要 `X-Tenant-Id: ${TENANT_ID}` 与 `X-Project-Id: ${PROJECT_ID}`。
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页通常为 `PageResult<T>`：`{ pageNo, pageSize, total, items }`。

## 典型调用链

1. 脚本开发：GET /data-development/tree -> POST /data-development/directories 创建目录 -> POST /data-development/scripts 保存 SQL/FLINK_QUESTION_SQL/JAVA/PYTHON 脚本 -> POST /data-development/scripts/{id}/execute 执行。
2. SQL 试跑：GET /data-development/datasource-options -> POST /data-development/sql/execute，body 包含 datasourceId、scriptType=SQL、content/sql。
3. Java 脚本提示：GET /script-environments/options?enabledOnly=true -> GET /data-development/java/import-hints -> GET /data-development/java/member-hints。
4. 工作流编排：GET /collection-tasks/workflow-options 和 GET /quality-tasks/workflow-options 选节点 -> POST /workflows 保存定义 -> POST /workflows/{id}/publish -> POST /workflows/{id}/trigger -> GET /workflow-runs/{workflowRunId}。
5. 运行日志：GET /runs/page 或 GET /workflow-runs -> GET /runs/{runRecordId}/summary -> GET /runs/{runRecordId}/log -> GET /runs/{runRecordId}/log/download。

## 前端 SDK 接口清单

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 | 前端调用点 | 源码 |
|---|---|---|---|---|---|---|
| `dataDevelopment.listSqlDatasourceOptions()` | GET | `/data-development/datasource-options` | - | `DataSourceOptionView[]` | `frontend/apps/web/src/views/DataDevelopmentView.vue:523`<br>`frontend/apps/web/src/views/DataServiceEditorView.vue:798`<br>`frontend/apps/web/src/views/QualityRuleEditorView.vue:319`<br>`frontend/apps/web/src/views/QualityTaskEditorView.vue:364` | `frontend/packages/api-sdk/src/client.ts:1577` |
| `dataDevelopment.listSqlDatasourceTypes()` | GET | `/data-development/datasource-types` | - | `string[]` | `frontend/apps/web/src/views/QualityRuleEditorView.vue:304` | `frontend/packages/api-sdk/src/client.ts:1580` |
| `dataDevelopment.listSqlDatasources()` | GET | `/data-development/datasources` | - | `DataSourceDefinition[]` | - | `frontend/packages/api-sdk/src/client.ts:1574` |
| `dataDevelopment.listDirectories()` | GET | `/data-development/directories` | - | `DataDevelopmentDirectory[]` | `frontend/apps/web/src/views/DataDevelopmentView.vue:437`<br>`frontend/apps/web/src/views/DataDevelopmentView.vue:473` | `frontend/packages/api-sdk/src/client.ts:1542` |
| `dataDevelopment.saveDirectory()` | POST | `/data-development/directories` | payload: DataDevelopmentDirectorySaveRequest<br>body: `payload` | `DataDevelopmentDirectory` | `frontend/apps/web/src/views/DataDevelopmentView.vue:660` | `frontend/packages/api-sdk/src/client.ts:1545` |
| `dataDevelopment.javaImportHints()` | GET | `/data-development/java/import-hints` | params?: { environmentId?: EntityId; keyword?: string; limit?: number }<br>config?: StudioRequestConfig | `JavaImportHintResponse` | `frontend/apps/web/src/views/DataDevelopmentView.vue:1052` | `frontend/packages/api-sdk/src/client.ts:1592` |
| `dataDevelopment.javaMemberHints()` | GET | `/data-development/java/member-hints` | params: { environmentId?: EntityId; className: string; keyword?: string; staticOnly?: boolean; limit?: number }<br>config?: StudioRequestConfig | `JavaMemberHintResponse` | `frontend/apps/web/src/views/DataDevelopmentView.vue:1067` | `frontend/packages/api-sdk/src/client.ts:1595` |
| `dataDevelopment.listScripts()` | GET | `/data-development/scripts` | scriptType?: ScriptType<br>config?: StudioRequestConfig<br>query: `scriptType ? { scriptType } : undefined` | `DataDevelopmentScriptListView[]` | - | `frontend/packages/api-sdk/src/client.ts:1554` |
| `dataDevelopment.saveScript()` | POST | `/data-development/scripts` | payload: DataDevelopmentScriptSaveRequest<br>body: `payload` | `DataDevelopmentScript` | `frontend/apps/web/src/views/DataDevelopmentView.vue:684` | `frontend/packages/api-sdk/src/client.ts:1565` |
| `dataDevelopment.executeScript()` | POST | `/data-development/scripts/execute` | payload: DataScriptExecutionRequest<br>body: `payload` | `DataScriptExecutionResult` | `frontend/apps/web/src/views/DataDevelopmentView.vue:806` | `frontend/packages/api-sdk/src/client.ts:1586` |
| `dataDevelopment.executeSql()` | POST | `/data-development/sql/execute` | payload: SqlExecutionRequest<br>body: `payload` | `SqlExecutionResult` | `frontend/apps/web/src/views/CollectionTaskEditorView.vue:1016` | `frontend/packages/api-sdk/src/client.ts:1583` |
| `dataDevelopment.tree()` | GET | `/data-development/tree` | - | `DataDevelopmentTreeNode[]` | `frontend/apps/web/src/views/DataDevelopmentView.vue:436`<br>`frontend/apps/web/src/views/DataDevelopmentView.vue:460`<br>`frontend/apps/web/src/views/DataDevelopmentView.vue:472`<br>`frontend/apps/web/src/views/WorkflowEditorView.vue:495` | `frontend/packages/api-sdk/src/client.ts:1539` |
| `environmentDependencies.options()` | GET | `/environment-dependencies/options` | params?: { enabledOnly?: boolean } | `EnvironmentDependencyOption[]` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:391` | `frontend/packages/api-sdk/src/client.ts:1479` |
| `environmentDependencies.queryPage()` | POST | `/environment-dependencies/queryPage` | params?: { pageNum?: number; pageSize?: number; keyword?: string; enabled?: boolean } | `EnvironmentDependencyListView` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:355` | `frontend/packages/api-sdk/src/client.ts:1473` |
| `environmentDependencies.saveOrUpdateCheck()` | POST | `/environment-dependencies/saveOrUpdateCheck` | payload: EnvironmentDependencySaveRequest<br>body: `payload` | `EnvironmentDependency` | - | `frontend/packages/api-sdk/src/client.ts:1485` |
| `environmentDependencies.saveOrUpdateCheckForm()` | POST | `/environment-dependencies/saveOrUpdateCheck` | payload: FormData<br>body: `payload` | `EnvironmentDependency` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:501` | `frontend/packages/api-sdk/src/client.ts:1488` |
| `runs.list()` | GET | `/runs` | params?: RunListQuery | `RunListResponse` | `frontend/apps/web/src/views/CollectionTasksView.vue:322` | `frontend/packages/api-sdk/src/client.ts:1605` |
| `runs.listPage()` | GET | `/runs/page` | params?: RunRecordPageQuery | `RunRecordPageResponse` | `frontend/apps/web/src/views/CollectionTaskRunsView.vue:243`<br>`frontend/apps/web/src/views/QualityTaskRunsView.vue:231` | `frontend/packages/api-sdk/src/client.ts:1612` |
| `schedules.list()` | GET | `/schedules` | - | `WorkflowListView[]` | - | `frontend/packages/api-sdk/src/client.ts:1600` |
| `scriptEnvironments.options()` | GET | `/script-environments/options` | params?: { enabledOnly?: boolean } | `ScriptEnvironmentOption[]` | `frontend/apps/web/src/views/DataDevelopmentView.vue:536` | `frontend/packages/api-sdk/src/client.ts:1519` |
| `scriptEnvironments.queryPage()` | POST | `/script-environments/queryPage` | params?: { pageNum?: number; pageSize?: number; keyword?: string; enabled?: boolean } | `ScriptEnvironmentListView` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:373` | `frontend/packages/api-sdk/src/client.ts:1513` |
| `scriptEnvironments.saveOrUpdateCheck()` | POST | `/script-environments/saveOrUpdateCheck` | payload: ScriptEnvironmentSaveRequest<br>body: `payload` | `ScriptEnvironment` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:596` | `frontend/packages/api-sdk/src/client.ts:1525` |
| `workflowRuns.list()` | GET | `/workflow-runs` | params?: WorkflowRunListQuery | `WorkflowRunSummary` | `frontend/apps/web/src/views/RunsView.vue:215`<br>`frontend/apps/web/src/views/WorkflowDetailView.vue:203` | `frontend/packages/api-sdk/src/client.ts:1731` |
| `workflows.list()` | GET | `/workflows` | config?: StudioRequestConfig | `WorkflowListView[]` | - | `frontend/packages/api-sdk/src/client.ts:1250` |
| `workflows.save()` | POST | `/workflows` | payload: WorkflowSaveRequest<br>body: `payload` | `WorkflowDefinitionView` | `frontend/apps/web/src/views/WorkflowEditorView.vue:908` | `frontend/packages/api-sdk/src/client.ts:1262` |
| `workflows.options()` | GET | `/workflows/options` | config?: StudioRequestConfig | `WorkflowOptionView[]` | `frontend/apps/web/src/views/RunsView.vue:207` | `frontend/packages/api-sdk/src/client.ts:1253` |
| `workflows.listPage()` | GET | `/workflows/page` | params?: { pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `WorkflowListView` | `frontend/apps/web/src/views/WorkflowsView.vue:121` | `frontend/packages/api-sdk/src/client.ts:1256` |
| `dataDevelopment.moveDirectory()` | POST | ``/data-development/directories/${id}/move`` | id: EntityId<br>payload: DataDevelopmentMoveRequest<br>body: `payload` | `void` | `frontend/apps/web/src/views/DataDevelopmentView.vue:947` | `frontend/packages/api-sdk/src/client.ts:1548` |
| `dataDevelopment.deleteDirectory()` | DELETE | ``/data-development/directories/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/DataDevelopmentView.vue:980` | `frontend/packages/api-sdk/src/client.ts:1551` |
| `dataDevelopment.executeSavedScript()` | POST | ``/data-development/scripts/${id}/execute`` | id: EntityId<br>payload?: SavedDataScriptExecutionRequest<br>body: `payload ?? {}` | `DataScriptExecutionResult` | `frontend/apps/web/src/views/DataDevelopmentView.vue:818` | `frontend/packages/api-sdk/src/client.ts:1589` |
| `dataDevelopment.moveScript()` | POST | ``/data-development/scripts/${id}/move`` | id: EntityId<br>payload: DataDevelopmentMoveRequest<br>body: `payload` | `void` | `frontend/apps/web/src/views/DataDevelopmentView.vue:950` | `frontend/packages/api-sdk/src/client.ts:1568` |
| `dataDevelopment.deleteScript()` | DELETE | ``/data-development/scripts/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/DataDevelopmentView.vue:988` | `frontend/packages/api-sdk/src/client.ts:1571` |
| `dataDevelopment.getScript()` | GET | ``/data-development/scripts/${id}`` | id: EntityId | `DataDevelopmentScript` | `frontend/apps/web/src/views/DataDevelopmentView.vue:607`<br>`frontend/apps/web/src/views/WorkflowEditorView.vue:578` | `frontend/packages/api-sdk/src/client.ts:1562` |
| `environmentDependencies.deleteFile()` | DELETE | ``/environment-dependencies/${dependencyId}/files/${fileId}`` | dependencyId: EntityId<br>fileId: EntityId | `void` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:573` | `frontend/packages/api-sdk/src/client.ts:1499` |
| `environmentDependencies.disable()` | POST | ``/environment-dependencies/${id}/disable`` | id: EntityId | `EnvironmentDependency` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:634` | `frontend/packages/api-sdk/src/client.ts:1505` |
| `environmentDependencies.enable()` | POST | ``/environment-dependencies/${id}/enable`` | id: EntityId | `EnvironmentDependency` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:632` | `frontend/packages/api-sdk/src/client.ts:1502` |
| `environmentDependencies.delete()` | DELETE | ``/environment-dependencies/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:653` | `frontend/packages/api-sdk/src/client.ts:1508` |
| `environmentDependencies.get()` | GET | ``/environment-dependencies/${id}`` | id: EntityId | `EnvironmentDependency` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:516`<br>`frontend/apps/web/src/views/ScriptEnvironmentsView.vue:574` | `frontend/packages/api-sdk/src/client.ts:1482` |
| `invocationLogs.download()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/download`` | domain: string<br>accessLogId: EntityId | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:210` | `frontend/packages/api-sdk/src/client.ts:1640` |
| `invocationLogs.downloadSection()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/sections/${encodeURIComponent(sectionKey)}/download`` | domain: string<br>accessLogId: EntityId<br>sectionKey: string | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:209` | `frontend/packages/api-sdk/src/client.ts:1653` |
| `invocationLogs.getSection()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/sections/${encodeURIComponent(sectionKey)}`` | domain: string<br>accessLogId: EntityId<br>sectionKey: string<br>params?: RunLogQuery | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:165` | `frontend/packages/api-sdk/src/client.ts:1646` |
| `invocationLogs.get()` | GET | ``/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}`` | domain: string<br>accessLogId: EntityId<br>params?: RunLogQuery | `RunLogView` | `frontend/apps/web/src/components/InvocationLogDrawer.vue:143` | `frontend/packages/api-sdk/src/client.ts:1633` |
| `runs.downloadLog()` | GET | ``/runs/${id}/log/download`` | id: EntityId | `RunLogView` | `frontend/apps/web/src/components/RunLogDrawer.vue:235` | `frontend/packages/api-sdk/src/client.ts:1628` |
| `runs.getLog()` | GET | ``/runs/${id}/log`` | id: EntityId<br>params?: RunLogQuery | `RunLogView` | `frontend/apps/web/src/components/RunLogDrawer.vue:197`<br>`frontend/apps/web/src/views/DataDevelopmentView.vue:856` | `frontend/packages/api-sdk/src/client.ts:1625` |
| `runs.getSummary()` | GET | ``/runs/${id}/summary`` | id: EntityId | `RunRecordListView` | `frontend/apps/web/src/components/RunLogDrawer.vue:183` | `frontend/packages/api-sdk/src/client.ts:1622` |
| `runs.get()` | GET | ``/runs/${id}`` | id: EntityId | `RunRecord` | - | `frontend/packages/api-sdk/src/client.ts:1619` |
| `scriptEnvironments.disable()` | POST | ``/script-environments/${id}/disable`` | id: EntityId | `ScriptEnvironment` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:680` | `frontend/packages/api-sdk/src/client.ts:1531` |
| `scriptEnvironments.enable()` | POST | ``/script-environments/${id}/enable`` | id: EntityId | `ScriptEnvironment` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:678` | `frontend/packages/api-sdk/src/client.ts:1528` |
| `scriptEnvironments.refresh()` | POST | ``/script-environments/${id}/refresh`` | id: EntityId | `ScriptEnvironment` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:694` | `frontend/packages/api-sdk/src/client.ts:1534` |
| `scriptEnvironments.get()` | GET | ``/script-environments/${id}`` | id: EntityId | `ScriptEnvironment` | `frontend/apps/web/src/views/ScriptEnvironmentsView.vue:533` | `frontend/packages/api-sdk/src/client.ts:1522` |
| `workflowRuns.terminate()` | POST | ``/workflow-runs/${workflowRunId}/terminate`` | workflowRunId: EntityId | `WorkflowRunDetail` | `frontend/apps/web/src/views/WorkflowRunDetailView.vue:235` | `frontend/packages/api-sdk/src/client.ts:1741` |
| `workflowRuns.get()` | GET | ``/workflow-runs/${workflowRunId}`` | workflowRunId: EntityId | `WorkflowRunDetail` | `frontend/apps/web/src/views/WorkflowRunDetailView.vue:184` | `frontend/packages/api-sdk/src/client.ts:1738` |
| `workflows.publish()` | POST | ``/workflows/${id}/publish`` | id: EntityId | `WorkflowDefinitionView` | `frontend/apps/web/src/views/WorkflowsView.vue:190` | `frontend/packages/api-sdk/src/client.ts:1265` |
| `workflows.trigger()` | POST | ``/workflows/${id}/trigger`` | id: EntityId | `void` | `frontend/apps/web/src/views/WorkflowsView.vue:226` | `frontend/packages/api-sdk/src/client.ts:1268` |
| `workflows.delete()` | DELETE | ``/workflows/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/WorkflowsView.vue:244` | `frontend/packages/api-sdk/src/client.ts:1271` |
| `workflows.get()` | GET | ``/workflows/${id}`` | id: EntityId | `WorkflowDefinitionView` | `frontend/apps/web/src/views/WorkflowDetailView.vue:181`<br>`frontend/apps/web/src/views/WorkflowEditorView.vue:654` | `frontend/packages/api-sdk/src/client.ts:1259` |


## 后端 Controller 对照

| Controller | 方法 | 路径 | Java 方法 | 参数来源 | 源码 |
|---|---|---|---|---|---|
| DataDevelopmentController | GET | `/api/v1/data-development/datasource-options` | `datasourceOptions()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:120` |
| DataDevelopmentController | GET | `/api/v1/data-development/datasource-types` | `datasourceTypes()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:126` |
| DataDevelopmentController | GET | `/api/v1/data-development/datasources` | `datasources()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:114` |
| DataDevelopmentController | GET | `/api/v1/data-development/directories` | `directories()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:54` |
| DataDevelopmentController | POST | `/api/v1/data-development/directories` | `saveDirectory()` | body: `@Valid @RequestBody DataDevelopmentDirectorySaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:60` |
| DataDevelopmentController | DELETE | `/api/v1/data-development/directories/{id}` | `deleteDirectory()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:74` |
| DataDevelopmentController | POST | `/api/v1/data-development/directories/{id}/move` | `moveDirectory()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) DataDevelopmentMoveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:66` |
| DataDevelopmentController | GET | `/api/v1/data-development/java/import-hints` | `javaImportHints()` | query: `@RequestParam(value = "environmentId"`<br>implicit: `required = false) Long environmentId`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "limit"`<br>implicit: `required = false) Integer limit` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:132` |
| DataDevelopmentController | GET | `/api/v1/data-development/java/member-hints` | `javaMemberHints()` | query: `@RequestParam(value = "environmentId"`<br>implicit: `required = false) Long environmentId`<br>query: `@RequestParam("className") String className`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "staticOnly"`<br>implicit: `required = false) Boolean staticOnly`<br>query: `@RequestParam(value = "limit"`<br>implicit: `required = false) Integer limit` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:140` |
| DataDevelopmentController | GET | `/api/v1/data-development/scripts` | `scripts()` | query: `@RequestParam(value = "scriptType"`<br>implicit: `required = false) ScriptType scriptType` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:81` |
| DataDevelopmentController | POST | `/api/v1/data-development/scripts` | `saveScript()` | body: `@Valid @RequestBody DataDevelopmentScriptSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:93` |
| DataDevelopmentController | GET | `/api/v1/data-development/scripts/{id}` | `script()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:87` |
| DataDevelopmentController | DELETE | `/api/v1/data-development/scripts/{id}` | `deleteScript()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:107` |
| DataDevelopmentController | POST | `/api/v1/data-development/scripts/{id}/execute` | `executeSavedScript()` | path: `@PathVariable("id") Long id`<br>body: `@Valid @RequestBody(required = false) SavedDataScriptExecutionRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:156` |
| DataDevelopmentController | POST | `/api/v1/data-development/scripts/{id}/move` | `moveScript()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) DataDevelopmentMoveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:99` |
| DataDevelopmentController | POST | `/api/v1/data-development/scripts/execute` | `executeScript()` | body: `@Valid @RequestBody DataScriptExecutionRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:150` |
| DataDevelopmentController | POST | `/api/v1/data-development/sql/execute` | `execute()` | body: `@Valid @RequestBody SqlExecutionRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:163` |
| DataDevelopmentController | GET | `/api/v1/data-development/tree` | `tree()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataDevelopmentController.java:48` |
| EnvironmentDependencyController | DELETE | `/api/v1/environment-dependencies/{dependencyId}/files/{fileId}` | `deleteFile()` | path: `@PathVariable("dependencyId") Long dependencyId`<br>path: `@PathVariable("fileId") Long fileId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/EnvironmentDependencyController.java:104` |
| EnvironmentDependencyController | GET | `/api/v1/environment-dependencies/{dependencyId}/files/{fileId}/download` | `downloadFile()` | path: `@PathVariable("dependencyId") Long dependencyId`<br>path: `@PathVariable("fileId") Long fileId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/EnvironmentDependencyController.java:90` |
| EnvironmentDependencyController | GET | `/api/v1/environment-dependencies/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/EnvironmentDependencyController.java:58` |
| EnvironmentDependencyController | DELETE | `/api/v1/environment-dependencies/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/EnvironmentDependencyController.java:124` |
| EnvironmentDependencyController | POST | `/api/v1/environment-dependencies/{id}/disable` | `disable()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/EnvironmentDependencyController.java:118` |
| EnvironmentDependencyController | POST | `/api/v1/environment-dependencies/{id}/enable` | `enable()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/EnvironmentDependencyController.java:112` |
| EnvironmentDependencyController | GET | `/api/v1/environment-dependencies/options` | `options()` | query: `@RequestParam(value = "enabledOnly"`<br>implicit: `required = false`<br>implicit: `defaultValue = "true") Boolean enabledOnly` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/EnvironmentDependencyController.java:52` |
| EnvironmentDependencyController | POST | `/api/v1/environment-dependencies/queryPage` | `queryPage()` | query: `@RequestParam(value = "pageNum"`<br>implicit: `required = false) Integer pageNum`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "enabled"`<br>implicit: `required = false) Boolean enabled` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/EnvironmentDependencyController.java:43` |
| EnvironmentDependencyController | POST | `/api/v1/environment-dependencies/saveOrUpdateCheck` | `saveOrUpdateCheck()` | body: `@Valid @RequestBody EnvironmentDependencySaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/EnvironmentDependencyController.java:64` |
| EnvironmentDependencyController | POST | `/api/v1/environment-dependencies/saveOrUpdateCheck` | `saveOrUpdateCheckMultipart()` | query: `@RequestParam(value = "id"`<br>implicit: `required = false) Long id`<br>query: `@RequestParam("name") String name`<br>query: `@RequestParam(value = "version"`<br>implicit: `required = false) String version`<br>query: `@RequestParam(value = "scriptType"`<br>implicit: `required = false) String scriptType`<br>query: `@RequestParam(value = "enabled"`<br>implicit: `required = false) Boolean enabled`<br>query: `@RequestParam(value = "description"`<br>implicit: `required = false) String description`<br>query: `@RequestParam(value = "file"`<br>implicit: `required = false) MultipartFile file`<br>query: `@RequestParam(value = "files"`<br>implicit: `required = false) List<MultipartFile> files` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/EnvironmentDependencyController.java:70` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}` | `log()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSizeBytes"`<br>implicit: `required = false) Integer pageSizeBytes` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:26` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/download` | `download()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:35` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/sections/{sectionKey}` | `sectionLog()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>path: `@PathVariable("sectionKey") String sectionKey`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSizeBytes"`<br>implicit: `required = false) Integer pageSizeBytes` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:42` |
| InvocationLogController | GET | `/api/v1/invocation-logs/{domain}/{accessLogId}/sections/{sectionKey}/download` | `downloadSection()` | path: `@PathVariable("domain") String domain`<br>path: `@PathVariable("accessLogId") Long accessLogId`<br>path: `@PathVariable("sectionKey") String sectionKey` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/InvocationLogController.java:52` |
| RunController | GET | `/api/v1/runs` | `list()` | query: `@RequestParam(value = "collectionTaskId"`<br>implicit: `required = false) Long collectionTaskId`<br>query: `@RequestParam(value = "qualityTaskId"`<br>implicit: `required = false) Long qualityTaskId`<br>query: `@RequestParam(value = "workflowDefinitionId"`<br>implicit: `required = false) Long workflowDefinitionId`<br>query: `@RequestParam(value = "startTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime`<br>query: `@RequestParam(value = "endTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime`<br>query: `@RequestParam(value = "includeRunRecords"`<br>implicit: `required = false) Boolean includeRunRecords` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:36` |
| RunController | GET | `/api/v1/runs/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:66` |
| RunController | GET | `/api/v1/runs/{id}/log` | `log()` | path: `@PathVariable("id") Long id`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSizeBytes"`<br>implicit: `required = false) Integer pageSizeBytes` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:78` |
| RunController | GET | `/api/v1/runs/{id}/log/download` | `download()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:86` |
| RunController | GET | `/api/v1/runs/{id}/summary` | `summary()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:72` |
| RunController | GET | `/api/v1/runs/page` | `listPage()` | query: `@RequestParam(value = "collectionTaskId"`<br>implicit: `required = false) Long collectionTaskId`<br>query: `@RequestParam(value = "qualityTaskId"`<br>implicit: `required = false) Long qualityTaskId`<br>query: `@RequestParam(value = "workflowDefinitionId"`<br>implicit: `required = false) Long workflowDefinitionId`<br>query: `@RequestParam(value = "collectionTaskOnly"`<br>implicit: `required = false) Boolean collectionTaskOnly`<br>query: `@RequestParam(value = "qualityTaskOnly"`<br>implicit: `required = false) Boolean qualityTaskOnly`<br>query: `@RequestParam(value = "status"`<br>implicit: `required = false) String status`<br>query: `@RequestParam(value = "startTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime`<br>query: `@RequestParam(value = "endTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java:49` |
| ScheduleController | GET | `/api/v1/schedules` | `list()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ScheduleController.java:26` |
| ScriptEnvironmentController | GET | `/api/v1/script-environments/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ScriptEnvironmentController.java:50` |
| ScriptEnvironmentController | POST | `/api/v1/script-environments/{id}/disable` | `disable()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ScriptEnvironmentController.java:68` |
| ScriptEnvironmentController | POST | `/api/v1/script-environments/{id}/enable` | `enable()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ScriptEnvironmentController.java:62` |
| ScriptEnvironmentController | POST | `/api/v1/script-environments/{id}/refresh` | `refresh()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ScriptEnvironmentController.java:74` |
| ScriptEnvironmentController | GET | `/api/v1/script-environments/options` | `options()` | query: `@RequestParam(value = "enabledOnly"`<br>implicit: `required = false`<br>implicit: `defaultValue = "true") Boolean enabledOnly` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ScriptEnvironmentController.java:44` |
| ScriptEnvironmentController | POST | `/api/v1/script-environments/queryPage` | `queryPage()` | query: `@RequestParam(value = "pageNum"`<br>implicit: `required = false) Integer pageNum`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "enabled"`<br>implicit: `required = false) Boolean enabled` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ScriptEnvironmentController.java:35` |
| ScriptEnvironmentController | POST | `/api/v1/script-environments/saveOrUpdateCheck` | `saveOrUpdateCheck()` | body: `@Valid @RequestBody ScriptEnvironmentSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ScriptEnvironmentController.java:56` |
| WorkflowRunController | GET | `/api/v1/workflow-runs` | `list()` | query: `@RequestParam(value = "workflowDefinitionId"`<br>implicit: `required = false) Long workflowDefinitionId`<br>query: `@RequestParam(value = "status"`<br>implicit: `required = false) String status`<br>query: `@RequestParam(value = "startTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime`<br>query: `@RequestParam(value = "endTime"`<br>implicit: `required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `defaultValue = "1") Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `defaultValue = "20") Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkflowRunController.java:31` |
| WorkflowRunController | GET | `/api/v1/workflow-runs/{workflowRunId}` | `get()` | path: `@PathVariable("workflowRunId") Long workflowRunId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkflowRunController.java:44` |
| WorkflowRunController | POST | `/api/v1/workflow-runs/{workflowRunId}/terminate` | `terminate()` | path: `@PathVariable("workflowRunId") Long workflowRunId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkflowRunController.java:50` |
| WorkflowController | GET | `/api/v1/workflows` | `list()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkflowController.java:39` |
| WorkflowController | POST | `/api/v1/workflows` | `save()` | body: `@Valid @RequestBody WorkflowSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkflowController.java:64` |
| WorkflowController | GET | `/api/v1/workflows/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkflowController.java:58` |
| WorkflowController | DELETE | `/api/v1/workflows/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkflowController.java:83` |
| WorkflowController | POST | `/api/v1/workflows/{id}/publish` | `publish()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkflowController.java:70` |
| WorkflowController | POST | `/api/v1/workflows/{id}/trigger` | `trigger()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkflowController.java:76` |
| WorkflowController | GET | `/api/v1/workflows/options` | `options()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkflowController.java:52` |
| WorkflowController | GET | `/api/v1/workflows/page` | `listPage()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkflowController.java:45` |


## cURL 模板

以下模板按前端 SDK 自动生成，动态路径中的 `{id}`、`{serviceId}` 等需要替换为上游接口返回值。出现 `?<query>` 时按表格中的 query 参数追加。

如果接口清单的“参数/请求体”列没有显示 `body:`，则自动化调用时删除模板中的 `-H "Content-Type: application/json"` 和 `-d` 行；只有列出 `body:` 的接口才发送 JSON 请求体。

### dataDevelopment.listSqlDatasourceOptions()

```bash
curl -X GET "${BASE_URL}/data-development/datasource-options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataDevelopment.listSqlDatasourceTypes()

```bash
curl -X GET "${BASE_URL}/data-development/datasource-types" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataDevelopment.listSqlDatasources()

```bash
curl -X GET "${BASE_URL}/data-development/datasources" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataDevelopment.listDirectories()

```bash
curl -X GET "${BASE_URL}/data-development/directories" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataDevelopment.saveDirectory()

```bash
curl -X POST "${BASE_URL}/data-development/directories" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataDevelopment.javaImportHints()

```bash
curl -X GET "${BASE_URL}/data-development/java/import-hints" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataDevelopment.javaMemberHints()

```bash
curl -X GET "${BASE_URL}/data-development/java/member-hints" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataDevelopment.listScripts()

```bash
curl -X GET "${BASE_URL}/data-development/scripts?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### dataDevelopment.saveScript()

```bash
curl -X POST "${BASE_URL}/data-development/scripts" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataDevelopment.executeScript()

```bash
curl -X POST "${BASE_URL}/data-development/scripts/execute" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataDevelopment.executeSql()

```bash
curl -X POST "${BASE_URL}/data-development/sql/execute" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### dataDevelopment.tree()

```bash
curl -X GET "${BASE_URL}/data-development/tree" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### environmentDependencies.options()

```bash
curl -X GET "${BASE_URL}/environment-dependencies/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### environmentDependencies.queryPage()

```bash
curl -X POST "${BASE_URL}/environment-dependencies/queryPage" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### environmentDependencies.saveOrUpdateCheck()

```bash
curl -X POST "${BASE_URL}/environment-dependencies/saveOrUpdateCheck" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### environmentDependencies.saveOrUpdateCheckForm()

```bash
curl -X POST "${BASE_URL}/environment-dependencies/saveOrUpdateCheck" \
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

### schedules.list()

```bash
curl -X GET "${BASE_URL}/schedules" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### scriptEnvironments.options()

```bash
curl -X GET "${BASE_URL}/script-environments/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```


## 开放访问 cURL 补充

开放访问接口不走管理端 `${BASE_URL}` 前缀，使用 `${STUDIO_ORIGIN}`，例如 `http://127.0.0.1:18080`。订阅令牌来自对应服务的订阅创建/轮换接口。

本模块无额外开放访问 Controller 模板。


## 主要 DTO 字段

### DataDevelopmentDirectory

来源：`frontend/packages/api-sdk/src/types.ts:2329`

```ts
interface DataDevelopmentDirectory extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| parentId | 否 | `EntityId` | |
| name | 是 | `string` | |
| permissionCode | 否 | `string` | |
| description | 否 | `string` | |

### DataDevelopmentDirectorySaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:2377`

```ts
interface DataDevelopmentDirectorySaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| parentId | 否 | `EntityId` | |
| name | 是 | `string` | |
| permissionCode | 否 | `string` | |
| description | 否 | `string` | |

### DataDevelopmentMoveRequest

来源：`frontend/packages/api-sdk/src/types.ts:2396`

```ts
interface DataDevelopmentMoveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| targetDirectoryId | 否 | `EntityId` | |

### DataDevelopmentScript

来源：`frontend/packages/api-sdk/src/types.ts:2336`

```ts
interface DataDevelopmentScript extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| directoryId | 否 | `EntityId` | |
| fileName | 是 | `string` | |
| scriptType | 是 | `ScriptType` | |
| datasourceId | 否 | `EntityId` | |
| datasourceName | 否 | `string` | |
| datasourceTypeCode | 否 | `string` | |
| environmentId | 否 | `EntityId` | |
| environmentName | 否 | `string` | |
| description | 否 | `string` | |
| content | 是 | `string` | |
| executionConfig | 否 | `Record<string, unknown>` | `FLINK_QUESTION_SQL` 使用 `executionConfig.modelIds` 保存执行模型，SQL/JAVA/PYTHON 为空对象或省略。 |

### DataDevelopmentScriptSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:2385`

```ts
interface DataDevelopmentScriptSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| directoryId | 否 | `EntityId` | |
| fileName | 是 | `string` | |
| scriptType | 是 | `ScriptType` | |
| datasourceId | 否 | `EntityId` | |
| environmentId | 否 | `EntityId` | |
| description | 否 | `string` | |
| content | 是 | `string` | |
| executionConfig | 否 | `Record<string, unknown>` | `FLINK_QUESTION_SQL` 必填 `modelIds: EntityId[]`，执行时注册为 `m_{modelId}` 临时表。前端通过 `/models/selector-options` 按数据源类型、数据源和模型关键字筛选模型，并通过 `/models/sql-hints?modelIds=...` 获取已选模型的 `m_{modelId}` 代码提示。 |

### DataScriptExecutionRequest

来源：`frontend/packages/api-sdk/src/types.ts:2407`

```ts
interface DataScriptExecutionRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| scriptType | 是 | `ScriptType` | |
| datasourceId | 否 | `EntityId` | |
| environmentId | 否 | `EntityId` | |
| content | 是 | `string` | |
| arguments | 否 | `Record<string, unknown>` | |
| maxRows | 否 | `number` | |
| executionConfig | 否 | `Record<string, unknown>` | `FLINK_QUESTION_SQL` 直接执行时必填 `modelIds`；常规 SQL 使用 `datasourceId`，Java/Python 需先保存后执行。 |

### DataScriptExecutionResult

来源：`frontend/packages/api-sdk/src/types.ts:2582`

```ts
interface DataScriptExecutionResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| scriptType | 否 | `ScriptType` | |
| success | 否 | `boolean` | |
| status | 否 | `string` | |
| message | 否 | `string` | |
| executionMs | 否 | `number` | |
| dispatchTaskId | 否 | `EntityId` | |
| runRecordId | 否 | `EntityId` | |
| logStatus | 否 | `string` | |
| datasourceName | 否 | `string` | |
| logs | 否 | `string` | |
| resultJson | 是 | `Record<string, unknown>` | |
| sqlResult | 否 | `SqlExecutionResult` | |

### EnvironmentDependency

来源：`frontend/packages/api-sdk/src/types.ts:2422`

```ts
interface EnvironmentDependency extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| name | 是 | `string` | |
| version | 否 | `string` | |
| scriptType | 是 | `ScriptType \| string` | |
| artifactUrl | 否 | `string` | |
| artifactType | 否 | `"JAR" \| "ZIP" \| string` | |
| checksum | 否 | `string` | |
| enabled | 是 | `boolean` | |
| description | 否 | `string` | |
| files | 否 | `EnvironmentDependencyFile[]` | |

### EnvironmentDependencyListView

来源：`frontend/packages/api-sdk/src/types.ts:2434`

```ts
interface EnvironmentDependencyListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| name | 是 | `string` | |
| version | 否 | `string` | |
| scriptType | 是 | `ScriptType \| string` | |
| enabled | 是 | `boolean` | |
| files | 否 | `EnvironmentDependencyFileListView[]` | |

### EnvironmentDependencySaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:2471`

```ts
interface EnvironmentDependencySaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| name | 是 | `string` | |
| version | 否 | `string` | |
| scriptType | 否 | `ScriptType \| string` | |
| artifactUrl | 否 | `string` | |
| artifactType | 否 | `"JAR" \| "ZIP" \| string` | |
| checksum | 否 | `string` | |
| enabled | 否 | `boolean` | |
| description | 否 | `string` | |

### JavaImportHintResponse

来源：`frontend/packages/api-sdk/src/types.ts:2528`

```ts
interface JavaImportHintResponse
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| environmentId | 否 | `EntityId` | |
| environmentVersion | 否 | `number` | |
| generatedAt | 否 | `string` | |
| classes | 是 | `JavaImportHint[]` | |

### JavaMemberHintResponse

来源：`frontend/packages/api-sdk/src/types.ts:2549`

```ts
interface JavaMemberHintResponse
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| environmentId | 否 | `EntityId` | |
| environmentVersion | 否 | `number` | |
| className | 否 | `string` | |
| generatedAt | 否 | `string` | |
| members | 是 | `JavaMemberHint[]` | |

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

### SavedDataScriptExecutionRequest

来源：`frontend/packages/api-sdk/src/types.ts:2416`

```ts
interface SavedDataScriptExecutionRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| arguments | 否 | `Record<string, unknown>` | |
| executionConfig | 否 | `Record<string, unknown>` | 保存脚本执行默认使用脚本保存时的 `executionConfig`；传入后会覆盖同名执行配置，例如替换 `FLINK_QUESTION_SQL` 的 `modelIds`。 |
| maxRows | 否 | `number` | |
| waitTimeoutSeconds | 否 | `number` | |

### ScriptEnvironment

来源：`frontend/packages/api-sdk/src/types.ts:2483`

```ts
interface ScriptEnvironment extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| environmentName | 是 | `string` | |
| environmentCode | 是 | `string` | |
| enabled | 是 | `boolean` | |
| useApplicationParent | 是 | `boolean` | |
| environmentVersion | 否 | `number` | |
| description | 否 | `string` | |
| dependencyIds | 是 | `EntityId[]` | |
| dependencies | 是 | `EnvironmentDependency[]` | |

### ScriptEnvironmentListView

来源：`frontend/packages/api-sdk/src/types.ts:2494`

```ts
interface ScriptEnvironmentListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| environmentName | 是 | `string` | |
| environmentCode | 是 | `string` | |
| enabled | 是 | `boolean` | |
| useApplicationParent | 是 | `boolean` | |
| environmentVersion | 否 | `number` | |
| dependencyIds | 是 | `EntityId[]` | |
| dependencies | 是 | `EnvironmentDependencyOption[]` | |

### ScriptEnvironmentSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:2510`

```ts
interface ScriptEnvironmentSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| environmentName | 是 | `string` | |
| environmentCode | 是 | `string` | |
| enabled | 否 | `boolean` | |
| useApplicationParent | 否 | `boolean` | |
| dependencyIds | 否 | `EntityId[]` | |
| description | 否 | `string` | |

### ScriptType

来源：`frontend/packages/api-sdk/src/types.ts:160`

```ts
type ScriptType = "SQL" | "FLINK_QUESTION_SQL" | "JAVA" | "PYTHON";
```

### SqlExecutionRequest

来源：`frontend/packages/api-sdk/src/types.ts:2400`

```ts
interface SqlExecutionRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 是 | `EntityId` | |
| scriptType | 是 | `ScriptType` | |
| content | 是 | `string` | |
| maxRows | 否 | `number` | |

### SqlExecutionResult

来源：`frontend/packages/api-sdk/src/types.ts:2569`

```ts
interface SqlExecutionResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| query | 否 | `boolean` | |
| statementCount | 否 | `number` | |
| affectedRows | 否 | `number` | |
| executionMs | 否 | `number` | |
| message | 否 | `string` | |
| datasourceName | 否 | `string` | |
| columns | 是 | `string[]` | |
| rows | 是 | `Record<string, unknown>[]` | |
| summary | 是 | `Record<string, unknown>` | |
| results | 是 | `SqlStatementExecutionResult[]` | |

### WorkflowDefinitionView

来源：`frontend/packages/api-sdk/src/types.ts:2617`

```ts
interface WorkflowDefinitionView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| code | 是 | `string` | |
| name | 是 | `string` | |
| versionId | 否 | `EntityId` | |
| versionNumber | 否 | `number` | |
| published | 否 | `boolean` | |
| schedule | 否 | `WorkflowScheduleDefinition` | |
| nodes | 是 | `WorkflowNodeDefinition[]` | |
| edges | 是 | `WorkflowEdgeDefinition[]` | |

### WorkflowListView

来源：`frontend/packages/api-sdk/src/types.ts:2628`

```ts
interface WorkflowListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| code | 是 | `string` | |
| name | 是 | `string` | |
| versionId | 否 | `EntityId` | |
| versionNumber | 否 | `number` | |
| published | 否 | `boolean` | |
| schedule | 否 | `WorkflowScheduleDefinition` | |

### WorkflowRunDetail

来源：`frontend/packages/api-sdk/src/types.ts:3064`

```ts
interface WorkflowRunDetail extends WorkflowRunSummary
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| workflow | 否 | `WorkflowDefinitionView` | |
| nodeRuns | 是 | `WorkflowNodeRun[]` | |

### WorkflowRunListQuery

来源：`frontend/packages/api-sdk/src/types.ts:3069`

```ts
interface WorkflowRunListQuery
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| workflowDefinitionId | 否 | `EntityId` | |
| status | 否 | `string` | |
| startTime | 否 | `string` | |
| endTime | 否 | `string` | |
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |

### WorkflowRunSummary

来源：`frontend/packages/api-sdk/src/types.ts:3025`

```ts
interface WorkflowRunSummary
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| tenantId | 否 | `string` | |
| projectId | 否 | `EntityId` | |
| workflowRunId | 否 | `EntityId` | |
| workflowDefinitionId | 否 | `EntityId` | |
| workflowVersionId | 否 | `EntityId` | |
| workflowName | 否 | `string` | |
| status | 否 | `string` | |
| startedAt | 否 | `string` | |
| endedAt | 否 | `string` | |
| durationMs | 否 | `number` | |
| totalNodes | 否 | `number` | |
| successNodes | 否 | `number` | |
| failedNodes | 否 | `number` | |
| runningNodes | 否 | `number` | |
| queuedNodes | 否 | `number` | |
| notRunNodes | 否 | `number` | |
| summaryMessage | 否 | `string` | |

### WorkflowSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:2643`

```ts
interface WorkflowSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| definitionId | 否 | `EntityId` | |
| code | 是 | `string` | |
| name | 是 | `string` | |
| schedule | 否 | `WorkflowScheduleDefinition` | |
| nodes | 是 | `WorkflowNodeDefinition[]` | |
| edges | 是 | `WorkflowEdgeDefinition[]` | |
