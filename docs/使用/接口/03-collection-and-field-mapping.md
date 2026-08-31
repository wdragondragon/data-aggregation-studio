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
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页数据为 `PageView<T>`：`{ pageNo, pageSize, total, items }`，位于 `Result.data`。

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

## 2026-08-27 Native Streaming 增量契约

### 模式、状态与保存字段

- `executionMode` 为 `BATCH | STREAMING`，省略时按 `BATCH` 处理，历史任务升级后统一回填为 `BATCH` 且不会自动启动。
- `streamingOptions` 只对 `STREAMING` 有效，仅包含流式容器的微批、重试和停止参数：`maxBatchRecords`、`maxBatchBytes`、`batchRetryCount`、`stopTimeoutMs`、`maxConsecutiveFailures`、`retryInitialDelayMs` 和 `retryMaxDelayMs`。
- Kafka 批处理和流式任务共用的消费参数统一放在 `sourceBindings[].readerOptions`：`groupId`、`offsetReset`、`resetOffset`、`pollTimeoutMs`、`batchSize`、`keepReadTime`、`retryPoll`、`parsingRules`、`fieldDelimiter` 和 `otherProperties`。未填写 `groupId` 时，服务端生成稳定的任务级默认值。
- Kafka Writer 参数统一放在 `targetBinding.writerOptions`：`ack`、`retries`、`batchSize`、`fieldDelimiter`、Topic 创建参数、`writeType` 和 `otherProperties`。
- Kafka 数据源只保存 Broker、认证和安全连接属性；Topic 只来自 Kafka 模型的 `physicalLocator`，不会再使用数据源的旧 `topic`、`queue`、`queueName` 或消费组字段。
- 任务业务状态为 `DRAFT | ONLINE | OFFLINE`；流式部署期望状态为 `RUNNING | STOPPED`，观测状态为 `STARTING | RUNNING | STOPPING | STOPPED | RECOVERING | FAILED`。
- STREAMING 的交付语义固定为 `AT_LEAST_ONCE`。目标微批成功后才提交 Kafka offset；提交失败允许同一批次重放。

STREAMING 保存示例：

```json
{
  "name": "NativeStreaming-example",
  "runtimeClusterId": "<runtimeClusterId>",
  "executionMode": "STREAMING",
  "streamingOptions": {
    "maxBatchRecords": 1000,
    "maxBatchBytes": 16777216,
    "batchRetryCount": 3,
    "stopTimeoutMs": 60000,
    "maxConsecutiveFailures": 10,
    "retryInitialDelayMs": 5000,
    "retryMaxDelayMs": 300000
  },
  "sourceBindings": [
    {
      "sourceAlias": "src1",
      "datasourceId": "<kafkaDatasourceId>",
      "modelId": "<kafkaModelId>",
      "readerOptions": {
        "groupId": "studio.<tenantId>.<taskId>",
        "offsetReset": "earliest",
        "resetOffset": false,
        "pollTimeoutMs": 1000,
        "batchSize": 500
      }
    }
  ],
  "targetBinding": {
    "datasourceId": "<targetDatasourceId>",
    "modelId": "<targetModelId>",
    "writerOptions": {}
  },
  "fieldMappings": [],
  "executionOptions": {}
}
```

示例中的数据源、模型、字段映射和 ID 必须使用选项接口返回的真实值，不应直接提交空数组或占位符。

### Kafka 参数来源

Kafka 的配置分为三层，保存和运行时均按此优先级装配：

| 层级 | 配置内容 | 来源 |
|---|---|---|
| 数据源连接 | `bootstrap.servers`、用户名/密码、Kerberos、`security.*`、`sasl.*`、`ssl.*` 等安全属性 | Kafka 数据源技术元数据 |
| Kafka 模型 | Topic | 模型 `physicalLocator`，必须非空 |
| 任务运行参数 | 消费组、位点策略、Poll、批量和解析参数 | `sourceBindings[].readerOptions` |
| 任务目标参数 | 确认级别、发送重试、批量、Topic 创建和写入格式 | `targetBinding.writerOptions` |
| 流式容器 | 微批大小、容器重试和停止参数 | `streamingOptions` |

`sourceBindings[].readerOptions.batchSize` 是 Kafka Consumer 的 `max.poll.records`；`streamingOptions.maxBatchRecords` 是流式容器处理批次大小，两者语义不同，不能互相替代。`readerOptions.pollTimeoutMs` 在批处理和流式读取中均控制 Kafka Consumer 的 Poll 超时；流式容器等待一个批次的超时使用框架默认值，不再作为重复的 Kafka 配置入口。数据源拥有的 Broker、认证和 `security.*`/`sasl.*`/`ssl.*` 等连接属性不能由 Reader/Writer 专用参数覆盖，但 `otherProperties` 仍可填写 Consumer/Producer 的任务级扩展属性。历史任务中仍存在于 `streamingOptions` 的四个旧公共字段会在编辑或保存时迁移到 Reader 配置，返回结果不再把它们作为新任务的权威来源。Flink Query 使用稳定的内部消费组，不读取数据源消费组字段。

### 流式生命周期和监控接口

| 操作 | 方法与路径 | 请求 | `Result.data` |
|---|---|---|---|
| 上线 | `POST /collection-tasks/{id}/online` | 无 Body | `CollectionTaskListView` |
| 下线 | `POST /collection-tasks/{id}/offline` | 无 Body | `CollectionTaskListView` |
| 人工恢复 | `POST /collection-tasks/{id}/recover` | 无 Body | `CollectionTaskListView` |
| 运行态 | `GET /collection-tasks/{id}/streaming-runtime` | 无 | `CollectionTaskStreamingRuntimeView` |
| 分钟指标 | `GET /collection-tasks/{id}/streaming-metrics` | Query：`startTime/endTime/pageNo/pageSize/onlyWithRecords`，时间为 ISO 日期时间，可选；`onlyWithRecords=true` 仅统计 `recordsRead > 0` 的分钟桶；页码从 1 开始，页大小默认 20、最大 200 | `PageView<StreamingMetricBucketView>`，按 `bucketStart` 倒序；筛选在聚合后、分页前执行 |
| 事件 | `GET /collection-tasks/{id}/streaming-events` | Query：`pageNo/pageSize`，可选 | `PageView<StreamingTaskEventView>` |
| 日志分片 | `GET /collection-tasks/{id}/streaming-log-chunks` | Query：`pageNo/pageSize`，可选 | `PageView<RunLogChunkView>` |
| 日志分片预览 | `GET /collection-tasks/{id}/streaming-log-chunks/{chunkId}/preview` | Query：`pageNo/pageSizeBytes`，可选；单页最多 512KB | `RunLogView`，只读取当前分片并执行敏感信息脱敏 |
| 日志分页 | `GET /runs/{runRecordId}/log` | Query：`pageNo/pageSizeBytes`，单页最大 512KB | `RunLogView` |
| 日志归档 | `GET /runs/{runRecordId}/log/archive` | 无 | ZIP 二进制响应 |

`online`、`offline` 和 `recover` 都是幂等操作。重复上线返回同一代 deployment/run/active attempt；重复下线不会创建额外停止记录；已满足恢复后的目标状态时重复请求不会创建双 attempt。`recover` 只接受 `desiredState=RUNNING` 且 `observedState=FAILED` 的任务。

除 `/runs/{id}/log/archive` 外，上表接口统一返回 Studio `Result<T>`；事件、日志分片和分钟指标的分页对象使用 `PageView<T>`，分页数据在 `Result.data.items`。分钟指标先按任务分钟聚合跨 attempt 原始桶，再按 `onlyWithRecords` 过滤和分页，第一页展示最新分钟。监控页面只将当前页内连续、计数全为 0 且 lag/checkpoint/attempt 状态不变的桶折叠为空闲时间段，原始 `stream_metric_bucket` 记录不被删除。日志分片预览只读取指定 `chunkId`，对象存储和 Worker 本地分片均沿用现有权限、分页和脱敏链路，单页最多 512KB。归档接口直接返回 `HTTP 200 application/zip`，并带 `Content-Disposition: attachment`，不能按 JSON 或 `Result<T>` 解析。

### STREAMING 操作限制

- STREAMING 不支持 `schedule`；保存或调用 `/schedule` 会返回业务错误。
- STREAMING 不支持 `/trigger`，上线即开始持续消费；下线才停止。
- `desiredState=RUNNING` 期间禁止修改运行配置和删除任务，前端应禁用编辑、保存和删除，后端仍会拒绝绕过 UI 的请求。
- STREAMING 不使用任务级 `/terminate` 停止，调用方应使用 `/offline`；BATCH 保持原有发布、定时和手动触发行为。
- 下线默认等待当前微批最多 60 秒，超时取消后该批次不提交 offset，下次上线或恢复时允许重放。

常见错误包括：`STREAMING collection tasks do not support schedules`、`STREAMING collection tasks do not support manual trigger`、`Running streaming collection task must be offline before it can be edited`。前端应展示 `Result.message`，同时保留任务当前 `desiredState/observedState` 供用户判断下一步操作。

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
| `collectionTasks.offline()` | POST | ``/collection-tasks/${id}/offline`` | id: EntityId | `CollectionTaskListView` | `frontend/apps/web/src/views/CollectionTasksView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `collectionTasks.recover()` | POST | ``/collection-tasks/${id}/recover`` | id: EntityId | `CollectionTaskListView` | `frontend/apps/web/src/views/CollectionTasksView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `collectionTasks.streamingRuntime()` | GET | ``/collection-tasks/${id}/streaming-runtime`` | id: EntityId | `CollectionTaskStreamingRuntimeView` | `frontend/apps/web/src/components/collection-task/StreamingTaskMonitorDrawer.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `collectionTasks.streamingMetrics()` | GET | ``/collection-tasks/${id}/streaming-metrics`` | id: EntityId<br>params?: `{ startTime?: string; endTime?: string; pageNo?: number; pageSize?: number; onlyWithRecords?: boolean }` | `PageView<StreamingMetricBucketView>` | `frontend/apps/web/src/components/collection-task/StreamingTaskMonitorDrawer.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `collectionTasks.streamingEvents()` | GET | ``/collection-tasks/${id}/streaming-events`` | id: EntityId<br>params?: `{ pageNo?: number; pageSize?: number }` | `PageView<StreamingTaskEventView>` | `frontend/apps/web/src/components/collection-task/StreamingTaskMonitorDrawer.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `collectionTasks.streamingLogChunks()` | GET | ``/collection-tasks/${id}/streaming-log-chunks`` | id: EntityId<br>params?: `{ pageNo?: number; pageSize?: number }` | `PageView<RunLogChunkView>` | `frontend/apps/web/src/components/collection-task/StreamingTaskMonitorDrawer.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `collectionTasks.streamingLogChunkPreview()` | GET | ``/collection-tasks/${id}/streaming-log-chunks/${chunkId}/preview`` | id: EntityId, chunkId: EntityId<br>params?: `RunLogQuery` (`pageNo/pageSizeBytes`) | `RunLogView` | `frontend/apps/web/src/components/collection-task/StreamingTaskMonitorDrawer.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `collectionTasks.saveSchedule()` | POST | ``/collection-tasks/${id}/schedule`` | id: EntityId<br>payload: CollectionTaskScheduleDefinition<br>body: `payload` | `CollectionTaskDefinitionView` | - | `frontend/packages/api-sdk/src/client.ts:1311` |
| `collectionTasks.trigger()` | POST | ``/collection-tasks/${id}/trigger`` | id: EntityId | `void` | `frontend/apps/web/src/views/CollectionTasksView.vue:376` | `frontend/packages/api-sdk/src/client.ts:1325` |
| `collectionTasks.delete()` | DELETE | ``/collection-tasks/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/CollectionTasksView.vue:393` | `frontend/packages/api-sdk/src/client.ts:1328` |
| `collectionTasks.get()` | GET | ``/collection-tasks/${id}`` | id: EntityId | `CollectionTaskDefinitionView` | `frontend/apps/web/src/views/CollectionTaskEditorView.vue:357` | `frontend/packages/api-sdk/src/client.ts:1299` |
| `fieldMappingRules.delete()` | DELETE | ``/field-mapping-rules/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/FieldMappingRulesView.vue:163` | `frontend/packages/api-sdk/src/client.ts:489` |
| `fieldMappingRules.get()` | GET | ``/field-mapping-rules/${id}`` | id: EntityId | `FieldMappingRuleView` | `frontend/apps/web/src/views/CollectionTaskEditorView.vue:380`<br>`frontend/apps/web/src/views/DataServiceEditorView.vue:820`<br>`frontend/apps/web/src/views/FieldMappingRuleEditorView.vue:179`<br>`frontend/apps/web/src/views/ProtocolConversionServiceEditorView.vue:837` | `frontend/packages/api-sdk/src/client.ts:483` |
| `runs.downloadLog()` | GET | ``/runs/${id}/log/download`` | id: EntityId | `RunLogView` | `frontend/apps/web/src/components/RunLogDrawer.vue:235` | `frontend/packages/api-sdk/src/client.ts:1628` |
| `runs.downloadLogArchive()` | GET | ``/runs/${id}/log/archive`` | id: EntityId<br>config?: StudioRequestConfig | `Blob`（ZIP） | `frontend/apps/web/src/components/collection-task/StreamingTaskMonitorDrawer.vue` | `frontend/packages/api-sdk/src/client.ts` |
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
| CollectionTaskController | POST | `/api/v1/collection-tasks/{id}/offline` | `offline()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java` |
| CollectionTaskController | POST | `/api/v1/collection-tasks/{id}/recover` | `recover()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java` |
| CollectionTaskController | GET | `/api/v1/collection-tasks/{id}/streaming-runtime` | `streamingRuntime()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java` |
| CollectionTaskController | GET | `/api/v1/collection-tasks/{id}/streaming-metrics` | `streamingMetrics()` | path: `id`<br>query: `startTime/endTime/pageNo/pageSize/onlyWithRecords`（时间为 ISO 日期时间，可选；`onlyWithRecords=true` 仅保留 `recordsRead > 0`；默认页码 1、页大小 20，最大 200） | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java` |
| CollectionTaskController | GET | `/api/v1/collection-tasks/{id}/streaming-events` | `streamingEvents()` | path: `id`<br>query: `pageNo/pageSize`（可选） | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java` |
| CollectionTaskController | GET | `/api/v1/collection-tasks/{id}/streaming-log-chunks` | `streamingLogChunks()` | path: `id`<br>query: `pageNo/pageSize`（可选） | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java` |
| CollectionTaskController | GET | `/api/v1/collection-tasks/{id}/streaming-log-chunks/{chunkId}/preview` | `streamingLogChunkPreview()` | path: `id/chunkId`<br>query: `pageNo/pageSizeBytes`（可选；单页最大 512KB） | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java` |
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
| RunController | GET | `/api/v1/runs/{id}/log/archive` | `archive()` | path: `@PathVariable("id") Long id`；直接返回 `application/zip` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java` |
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
| runtimeClusterId | 否 | `EntityId` | 任务保存的运行集群 ID。 |
| runtimeClusterName | 否 | `string` | 运行集群展示名。 |
| taskType | 否 | `CollectionTaskType` | |
| status | 否 | `CollectionTaskStatus` | `DRAFT | ONLINE | OFFLINE`。 |
| executionMode | 否 | `CollectionTaskExecutionMode` | `BATCH | STREAMING`。 |
| streamingOptions | 否 | `CollectionTaskStreamingOptions` | STREAMING 容器微批、重试和停止配置；Kafka 消费参数请放在源绑定的 `readerOptions`。 |
| desiredState | 否 | `StreamingDesiredState` | `RUNNING | STOPPED`。 |
| observedState | 否 | `StreamingObservedState` | `STARTING | RUNNING | STOPPING | STOPPED | RECOVERING | FAILED`。 |
| streamingGeneration | 否 | `number` | 部署代次，用于并发控制。 |
| currentStreamRunId | 否 | `EntityId` | 当前逻辑 run。 |
| currentStreamAttemptId | 否 | `EntityId` | 当前物理 attempt。 |
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
| runtimeClusterId | 否 | `EntityId` | |
| runtimeClusterName | 否 | `string` | |
| taskType | 否 | `CollectionTaskType` | |
| status | 否 | `CollectionTaskStatus` | |
| executionMode | 否 | `CollectionTaskExecutionMode` | `BATCH | STREAMING`。 |
| desiredState | 否 | `StreamingDesiredState` | `RUNNING | STOPPED`。 |
| observedState | 否 | `StreamingObservedState` | `STARTING | RUNNING | STOPPING | STOPPED | RECOVERING | FAILED`。 |
| streamingGeneration | 否 | `number` | |
| currentStreamRunId | 否 | `EntityId` | |
| currentStreamAttemptId | 否 | `EntityId` | |
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
| runtimeClusterId | 是 | `EntityId` | 来源和目标必须适用于该运行集群。 |
| sourceBindings | 是 | `CollectionTaskSourceBinding[]` | |
| targetBinding | 是 | `CollectionTaskTargetBinding` | |
| fieldMappings | 是 | `FieldMappingDefinition[]` | |
| executionOptions | 是 | `Record<string, unknown>` | |
| executionMode | 否 | `CollectionTaskExecutionMode` | 默认 `BATCH`。 |
| streamingOptions | 否 | `CollectionTaskStreamingOptions` | `STREAMING` 时有效，仅填写流式容器参数。 |
| schedule | 否 | `CollectionTaskScheduleDefinition` | |

### CollectionTaskStreamingOptions

来源：`frontend/packages/api-sdk/src/types.ts`

| 字段 | 必填 | 类型 | 默认值/说明 |
|---|---:|---|---|
| maxBatchRecords | 否 | `number` | `1000`。 |
| maxBatchBytes | 否 | `number` | `16777216`（16MB）。 |
| batchRetryCount | 否 | `number` | `3` 次重试，不含首次执行。 |
| stopTimeoutMs | 否 | `number` | `60000`。 |
| maxConsecutiveFailures | 否 | `number` | `10`。 |
| retryInitialDelayMs | 否 | `number` | `5000`。 |
| retryMaxDelayMs | 否 | `number` | `300000`。 |

`groupId`、`offsetReset`、`resetOffset`、`pollTimeoutMs` 虽仍保留在 SDK 类型中以反序列化历史任务，但已废弃，不应再写入 `streamingOptions`。新任务应将它们与 `batchSize` 等 Kafka Reader 参数放入 `sourceBindings[].readerOptions`。

### CollectionTaskStreamingRuntimeView

| 字段 | 类型 | 说明 |
|---|---|---|
| collectionTaskId/taskName | `EntityId/string` | 任务标识与名称。 |
| deployment | `StreamingTaskDeploymentView` | generation、期望/观测状态、当前 run/attempt、失败计数、重试时间、最近 checkpoint 和脱敏错误。 |
| currentRun | `StreamingTaskRunView` | 一次上线到下线的逻辑 run，包含 `AT_LEAST_ONCE`、groupId 和停止信息。 |
| currentAttempt | `StreamingTaskAttemptView` | 当前 Worker 物理 attempt、heartbeat、checkpoint、状态和提交批次数。 |
| recentAttempts | `StreamingTaskAttemptView[]` | 最近 attempt 时间线，Worker 重启不会重建逻辑 run。 |

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
