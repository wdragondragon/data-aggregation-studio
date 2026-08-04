# 数据资产、数据源、元模型与模型中心接口

覆盖元模型、数据源、模型、模型发现/同步、血缘、模型统计等界面能力。

## 界面范围

- `/metadata` -> `frontend/apps/web/src/views/MetadataSchemasView.vue`
- `/datasources` -> `frontend/apps/web/src/views/DatasourcesView.vue`
- `/models` -> `frontend/apps/web/src/views/ModelsView.vue`
- `/statistics` -> `frontend/apps/web/src/views/ModelStatisticsView.vue`
- `/models/sync-tasks/:taskId` -> `frontend/apps/web/src/views/ModelSyncTaskDetailView.vue`

## 调用前置条件

- 管理端基础地址：`${BASE_URL}`，默认在线环境为 `http://127.0.0.1:18080/api/v1`。
- 认证头：`Authorization: Bearer ${TOKEN}`。
- 租户和项目上下文：大多数项目内接口需要 `X-Tenant-Id: ${TENANT_ID}` 与 `X-Project-Id: ${PROJECT_ID}`。
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页通常为 `PageResult<T>`：`{ pageNo, pageSize, total, items }`。

## 典型调用链

1. 创建数据源：GET /runtime-clusters/options -> GET /catalog/datasource-types -> GET /meta-schemas?includeFields=true -> POST /datasources -> POST /datasources/{id}/test?runtimeClusterId=<id> -> GET /datasources/{id}/connection-history?runtimeClusterId=<id>。
2. 按数据源发现模型：POST /datasources/{id}/discover-options?runtimeClusterId=<id> -> POST /models/sync-tasks 或 POST /models/sync?runtimeClusterId=<id> -> GET /model-sync-tasks/{taskId} -> GET /models?datasourceId=<id>。
3. 维护模型血缘：GET /models/{modelId}/lineage?level=TABLE|FIELD -> POST /models/{modelId}/lineage/manual -> GET /models/{modelId}/lineage/edges/{edgeId} -> DELETE /models/{modelId}/lineage/manual/{relationId}。
4. 模型统计：POST /statistics/options 获取可统计字段和维度 -> POST /statistics/charts/query 生成趋势、柱状、饼图或 TopN 数据。
5. 模型数据预览：GET /runtime-clusters/options 选择当前项目授权集群 -> GET /models/selector-options?runtimeClusterId=<clusterId>&keyword=<keyword> 选择该集群可运行模型 -> GET /models/{modelId}/preview?runtimeClusterId=<clusterId>&limit=20。

## 2026-07-20 多集群增量契约

- 进入数据源或模型操作前先调用 `GET /runtime-clusters/options`。所有环境都必须明确提交真实 `runtimeClusterId`；只有一个授权集群时客户端可以自动选中，但请求中仍须携带该集群 ID。
- `DataSourceSaveRequest` 增加 `applicableClusterIds`，至少选择一个当前项目已授权集群。移除已有绑定前先调用 `POST /datasources/{id}/cluster-binding-impact`；存在影响时，用户确认后保存请求须携带 `confirmClusterBindingImpact=true`。
- `GET /datasources/options?runtimeClusterId={id}` 仅返回适用于所选集群的数据源。数据源列表/详情返回 `applicableClusterIds`、`applicableClusters`，列表另返回逐集群 `clusterHealth`；健康状态只用于提示，不参与隐藏数据源。
- 已保存和当前表单连接测试、连接历史、`discover`、`discover-options` 均增加 Query `runtimeClusterId`。同一连接指纹在不同集群拥有独立健康记录；无法在目标集群执行探测时为 `UNKNOWN`。
- `GET /models/selector-options`、模型预览、数据源模型同步和模型同步任务均携带运行集群。同步全部使用 Query `runtimeClusterId`；选择同步和 `ModelSyncTaskCreateRequest` 使用 Body `runtimeClusterId`。
- 模型本身不保存运行集群，继承所属数据源的适用集群。`ModelSyncTaskView` 返回 `runtimeClusterId/runtimeClusterName`，便于确认元数据实际在哪个网络位置发现。
- 数据源 DTO 因继承公共基类也会出现 `runtimeValid/runtimeValidationMessage`，但当前不把它作为数据源逐集群可用性的判据；数据源范围看 `applicableClusters`，连接结果看 `clusterHealth`。后端仍会在测试、发现、同步和预览时复核项目授权与数据源绑定。
- 数据源详情和保存响应不返回密码、Token、Secret、AccessKey 等敏感技术元数据值；`savedSensitiveFieldKeys` 仅说明哪些字段已经保存过值。编辑时未填写或留空的敏感字段由服务端保留原值，不要把掩码、占位符或 `savedSensitiveFieldKeys` 当作新密码回传。

以上增量字段优先于下方历史自动抽取表中未包含集群参数的旧签名。

## 前端 SDK 接口清单

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 | 前端调用点 | 源码 |
|---|---|---|---|---|---|---|
| `datasources.list()` | GET | `/datasources` | config?: StudioRequestConfig | `DataSourceListView[]` | - | `frontend/packages/api-sdk/src/client.ts:605` |
| `datasources.save()` | POST | `/datasources` | payload: DataSourceSaveRequest<br>body: `payload` | `DataSourceDefinition` | `frontend/apps/web/src/views/DatasourcesView.vue:1040` | `frontend/packages/api-sdk/src/client.ts:617` |
| `datasources.options()` | GET | `/datasources/options` | runtimeClusterId: EntityId<br>query: `{ runtimeClusterId }` | `DataSourceOptionView[]` | 需要按运行集群筛选候选的资源编辑器 | `frontend/packages/api-sdk/src/client.ts` |
| `datasources.optionsByRuntimeCluster()` | GET | `/datasources/options` | runtimeClusterId: EntityId<br>query: `{ runtimeClusterId }` | `DataSourceOptionView[]` | 多集群资源编辑器 | `frontend/packages/api-sdk/src/client.ts` |
| `datasources.listPage()` | GET | `/datasources/page` | params?: { pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `DataSourceListView` | `frontend/apps/web/src/views/DatasourcesView.vue:975`<br>`frontend/apps/web/src/views/DatasourcesView.vue:993` | `frontend/packages/api-sdk/src/client.ts:611` |
| `datasources.testCurrent()` | POST | `/datasources/test` | payload: DataSourceSaveRequest<br>runtimeClusterId: EntityId<br>query: `{ runtimeClusterId }`<br>body: `payload` | `ConnectionTestResult` | `frontend/apps/web/src/views/DatasourcesView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `datasources.clusterBindingImpact()` | POST | ``/datasources/${id}/cluster-binding-impact`` | id: EntityId<br>body: `{ applicableClusterIds }` | `DatasourceClusterBindingImpactView` | `frontend/apps/web/src/views/DatasourcesView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `metaSchemas.list()` | GET | `/meta-schemas` | params?: { includeFields?: boolean } | `MetadataSchemaDefinition[]` | `frontend/apps/web/src/views/DatasourcesView.vue:997`<br>`frontend/apps/web/src/views/MetadataSchemasView.vue:814`<br>`frontend/apps/web/src/views/ModelsView.vue:711` | `frontend/packages/api-sdk/src/client.ts:566` |
| `metaSchemas.details()` | GET | `/meta-schemas/details` | schemaIds: EntityId[]<br>query: `{ schemaIds: schemaIds.map(String).join(",") }` | `MetadataSchemaDefinition[]` | `frontend/apps/web/src/views/DatasourcesView.vue:961`<br>`frontend/apps/web/src/views/ModelsView.vue:463` | `frontend/packages/api-sdk/src/client.ts:572` |
| `metaSchemas.saveDraft()` | POST | `/meta-schemas/draft` | payload: Record<string, unknown><br>body: `payload` | `MetadataSchemaDefinition` | `frontend/apps/web/src/views/MetadataSchemasView.vue:962` | `frontend/packages/api-sdk/src/client.ts:594` |
| `metaSchemas.syncStandardRuntimeOptions()` | POST | `/meta-schemas/runtime-options/sync-standard` | - | `MetadataSchemaDefinition[]` | - | `frontend/packages/api-sdk/src/client.ts:591` |
| `metaSchemas.syncAllTechnical()` | POST | `/meta-schemas/technical/sync-all` | - | `MetadataSchemaDefinition[]` | - | `frontend/packages/api-sdk/src/client.ts:585` |
| `metaSchemas.syncAllTechnicalSummary()` | POST | `/meta-schemas/technical/sync-all/summary` | - | `MetadataSchemaDefinition[]` | `frontend/apps/web/src/views/MetadataSchemasView.vue:1064` | `frontend/packages/api-sdk/src/client.ts:588` |
| `modelSyncTasks.create()` | POST | `/model-sync-tasks` | payload: ModelSyncTaskCreateRequest<br>body: `payload` | `ModelSyncTaskView` | `frontend/apps/web/src/views/ModelsView.vue:1009`<br>`frontend/apps/web/src/views/ModelsView.vue:1060` | `frontend/packages/api-sdk/src/client.ts:1207` |
| `modelSyncTasks.list()` | GET | `/model-sync-tasks` | params?: { pageNo?: number; pageSize?: number; datasourceType?: string; datasourceId?: EntityId; status?: string; } | `ModelSyncTaskView` | `frontend/apps/web/src/views/ModelsView.vue:600` | `frontend/packages/api-sdk/src/client.ts:1210` |
| `models.list()` | GET | `/models` | params?: { datasourceType?: string; pageNo?: number; pageSize?: number; sortField?: string; sortOrder?: string; }<br>query: `{ datasourceType: params?.datasourceType, pageNo: params?.pageNo ?? 1, pageSize: params?.pageSize ?? 5000, sortField: params?.sortField, sortOrder: params?.sortOrder, }` | `PageResult<DataModelDefinition>` | - | `frontend/packages/api-sdk/src/client.ts:948` |
| `models.listPage()` | GET | `/models` | params?: { datasourceType?: string; pageNo?: number; pageSize?: number; sortField?: string; sortOrder?: string; }<br>config?: StudioRequestConfig | `unknown` | - | `frontend/packages/api-sdk/src/client.ts:917` |
| `models.save()` | POST | `/models` | payload: DataModelSaveRequest<br>body: `payload` | `DataModelDefinition` | `frontend/apps/web/src/views/ModelsView.vue:972` | `frontend/packages/api-sdk/src/client.ts:1157` |
| `models.indexQueueStatus()` | GET | `/models/index/queue-status` | - | `DataModelIndexQueueStatusView` | `frontend/apps/web/src/views/ModelsView.vue:618` | `frontend/packages/api-sdk/src/client.ts:1145` |
| `models.rebuildIndex()` | POST | `/models/index/rebuild` | datasourceId?: EntityId<br>query: `datasourceId == null ? undefined : { datasourceId }` | `number` | `frontend/apps/web/src/views/ModelsView.vue:812` | `frontend/packages/api-sdk/src/client.ts:1138` |
| `models.listOptions()` | GET | `/models/options` | params?: { keyword?: string; pageNo?: number; pageSize?: number; }<br>config?: StudioRequestConfig | `unknown` | `frontend/apps/web/src/components/ModelLineagePanel.vue:606` | `frontend/packages/api-sdk/src/client.ts:939` |
| `models.listSelectorOptions()` | GET | `/models/selector-options` | params: { runtimeClusterId: EntityId; datasourceType?: string; datasourceId?: EntityId; keyword?: string; pageNo?: number; pageSize?: number; }<br>config?: StudioRequestConfig | `PageView<DataModelDatasourceOptionView>` | `frontend/apps/web/src/views/DataDevelopmentView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `models.query()` | POST | `/models/query` | payload: DataModelQueryRequest<br>params?: { pageNo?: number; pageSize?: number; }<br>query: `{ pageNo: params?.pageNo ?? payload.pageNo ?? 1, pageSize: params?.pageSize ?? payload.pageSize ?? 5000, }`<br>body: `payload` | `PageResult<DataModelDefinition>` | - | `frontend/packages/api-sdk/src/client.ts:1105` |
| `models.queryPage()` | POST | `/models/query` | payload: DataModelQueryRequest<br>params?: { pageNo?: number; pageSize?: number; }<br>body: `payload` | `unknown` | - | `frontend/packages/api-sdk/src/client.ts:1071` |
| `models.querySummaries()` | POST | `/models/query/summaries` | payload: DataModelQueryRequest<br>params?: { pageNo?: number; pageSize?: number; }<br>query: `{ pageNo: params?.pageNo ?? payload.pageNo ?? 1, pageSize: params?.pageSize ?? payload.pageSize ?? 5000, }`<br>body: `payload` | `PageResult<DataModelListView>` | - | `frontend/packages/api-sdk/src/client.ts:1120` |
| `models.querySummaryPage()` | POST | `/models/query/summaries` | payload: DataModelQueryRequest<br>params?: { pageNo?: number; pageSize?: number; }<br>body: `payload` | `unknown` | `frontend/apps/web/src/views/ModelsView.vue:748` | `frontend/packages/api-sdk/src/client.ts:1088` |
| `models.statistics()` | POST | `/models/statistics` | payload: DataModelStatisticsRequest<br>body: `payload` | `DataModelStatisticsView` | `frontend/apps/web/src/views/ModelStatisticsView.vue:905` | `frontend/packages/api-sdk/src/client.ts:1135` |
| `models.listSummaries()` | GET | `/models/summaries` | params?: { datasourceType?: string; pageNo?: number; pageSize?: number; sortField?: string; sortOrder?: string; }<br>query: `{ datasourceType: params?.datasourceType, pageNo: params?.pageNo ?? 1, pageSize: params?.pageSize ?? 5000, sortField: params?.sortField, sortOrder: params?.sortOrder, }` | `PageResult<DataModelListView>` | - | `frontend/packages/api-sdk/src/client.ts:968` |
| `models.listSummaryPage()` | GET | `/models/summaries` | params?: { datasourceType?: string; pageNo?: number; pageSize?: number; sortField?: string; sortOrder?: string; }<br>config?: StudioRequestConfig | `unknown` | `frontend/apps/web/src/views/ModelsView.vue:758` | `frontend/packages/api-sdk/src/client.ts:928` |
| `statistics.queryChart()` | POST | `/statistics/charts/query` | payload: DataModelStatisticsChartRequest<br>body: `payload` | `DataModelStatisticsChartView` | `frontend/apps/web/src/views/ModelStatisticsView.vue:906` | `frontend/packages/api-sdk/src/client.ts:1245` |
| `statistics.options()` | POST | `/statistics/options` | payload?: DataModelStatisticsOptionsRequest<br>body: `payload` | `DataModelStatisticsOptionsView` | `frontend/apps/web/src/views/ModelStatisticsView.vue:851` | `frontend/packages/api-sdk/src/client.ts:1242` |
| `datasources.connectionHistory()` | GET | ``/datasources/${id}/connection-history`` | id: EntityId<br>params: { runtimeClusterId: EntityId; days?: number; limit?: number } | `DatasourceConnectionTestRecordView[]` | `frontend/apps/web/src/views/DatasourcesView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `datasources.discoverOptions()` | POST | ``/datasources/${id}/discover-options`` | id: EntityId<br>options: { runtimeClusterId: EntityId; keyword?: string; pageNo?: number; pageSize?: number } | `ModelDiscoveryOptionResult` | `frontend/apps/web/src/components/ModelSyncTableSelector.vue`<br>`frontend/apps/web/src/views/DatasourcesView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `datasources.discover()` | POST | ``/datasources/${id}/discover`` | id: EntityId<br>options: { runtimeClusterId: EntityId; keyword?: string; pageNo?: number; pageSize?: number } | `ModelDiscoveryResult` | - | `frontend/packages/api-sdk/src/client.ts` |
| `datasources.test()` | POST | ``/datasources/${id}/test`` | id: EntityId<br>runtimeClusterId: EntityId<br>query: `{ runtimeClusterId }` | `ConnectionTestResult` | `frontend/apps/web/src/views/DatasourcesView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `datasources.delete()` | DELETE | ``/datasources/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/DatasourcesView.vue:1226` | `frontend/packages/api-sdk/src/client.ts:663` |
| `datasources.get()` | GET | ``/datasources/${id}`` | id: EntityId | `DataSourceDefinition` | `frontend/apps/web/src/views/DatasourcesView.vue:728` | `frontend/packages/api-sdk/src/client.ts:614` |
| `metaSchemas.publish()` | POST | ``/meta-schemas/${schemaId}/publish`` | schemaId: EntityId | `MetadataSchemaDefinition` | `frontend/apps/web/src/views/MetadataSchemasView.vue:993` | `frontend/packages/api-sdk/src/client.ts:597` |
| `metaSchemas.delete()` | DELETE | ``/meta-schemas/${schemaId}`` | schemaId: EntityId | `void` | `frontend/apps/web/src/views/MetadataSchemasView.vue:1020` | `frontend/packages/api-sdk/src/client.ts:600` |
| `metaSchemas.get()` | GET | ``/meta-schemas/${schemaId}`` | schemaId: EntityId | `MetadataSchemaDefinition` | `frontend/apps/web/src/views/MetadataSchemasView.vue:861` | `frontend/packages/api-sdk/src/client.ts:569` |
| `metaSchemas.syncTechnicalSummary()` | POST | ``/meta-schemas/technical/sync/${typeCode}/summary`` | typeCode: string | `MetadataSchemaDefinition[]` | `frontend/apps/web/src/views/MetadataSchemasView.vue:1046` | `frontend/packages/api-sdk/src/client.ts:582` |
| `metaSchemas.syncTechnical()` | POST | ``/meta-schemas/technical/sync/${typeCode}`` | typeCode: string | `MetadataSchemaDefinition[]` | - | `frontend/packages/api-sdk/src/client.ts:579` |
| `modelSyncTasks.listItems()` | GET | ``/model-sync-tasks/${taskId}/items`` | taskId: EntityId<br>params?: { pageNo?: number; pageSize?: number; keyword?: string; status?: string; } | `ModelSyncTaskItemView` | `frontend/apps/web/src/views/ModelSyncTaskDetailView.vue:186` | `frontend/packages/api-sdk/src/client.ts:1222` |
| `modelSyncTasks.stop()` | POST | ``/model-sync-tasks/${taskId}/stop`` | taskId: EntityId | `ModelSyncTaskView` | `frontend/apps/web/src/views/ModelsView.vue:641`<br>`frontend/apps/web/src/views/ModelSyncTaskDetailView.vue:230` | `frontend/packages/api-sdk/src/client.ts:1234` |
| `modelSyncTasks.delete()` | DELETE | ``/model-sync-tasks/${taskId}`` | taskId: EntityId | `void` | `frontend/apps/web/src/views/ModelsView.vue:659`<br>`frontend/apps/web/src/views/ModelSyncTaskDetailView.vue:244` | `frontend/packages/api-sdk/src/client.ts:1237` |
| `modelSyncTasks.get()` | GET | ``/model-sync-tasks/${taskId}`` | taskId: EntityId | `ModelSyncTaskView` | `frontend/apps/web/src/views/ModelSyncTaskDetailView.vue:177` | `frontend/packages/api-sdk/src/client.ts:1219` |
| `models.lineageEdgeDetail()` | GET | ``/models/${modelId}/lineage/edges/${encodeURIComponent(edgeId)}`` | modelId: EntityId<br>edgeId: string<br>level: DataModelLineageLevel | string<br>query: `{ level }` | `DataModelLineageEdgeDetailView` | `frontend/apps/web/src/components/ModelLineagePanel.vue:581` | `frontend/packages/api-sdk/src/client.ts:1175` |
| `models.deleteManualLineage()` | DELETE | ``/models/${modelId}/lineage/manual/${relationId}`` | modelId: EntityId<br>relationId: EntityId | `void` | `frontend/apps/web/src/components/ModelLineagePanel.vue:731` | `frontend/packages/api-sdk/src/client.ts:1196` |
| `models.updateManualLineage()` | PUT | ``/models/${modelId}/lineage/manual/${relationId}`` | modelId: EntityId<br>relationId: EntityId<br>payload: DataModelManualLineageSaveRequest<br>body: `payload` | `void` | `frontend/apps/web/src/components/ModelLineagePanel.vue:704` | `frontend/packages/api-sdk/src/client.ts:1189` |
| `models.createManualLineage()` | POST | ``/models/${modelId}/lineage/manual`` | modelId: EntityId<br>payload: DataModelManualLineageSaveRequest<br>body: `payload` | `void` | `frontend/apps/web/src/components/ModelLineagePanel.vue:706` | `frontend/packages/api-sdk/src/client.ts:1182` |
| `models.lineage()` | GET | ``/models/${modelId}/lineage`` | modelId: EntityId<br>level: DataModelLineageLevel | string<br>query: `{ level }` | `DataModelLineageView` | `frontend/apps/web/src/components/ModelLineagePanel.vue:490` | `frontend/packages/api-sdk/src/client.ts:1168` |
| `models.preview()` | GET | ``/models/${modelId}/preview`` | modelId: EntityId<br>limit: number<br>runtimeClusterId: EntityId<br>query: `{ limit, runtimeClusterId }` | `Record<string, unknown>[]` | `frontend/apps/web/src/views/ModelsView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `models.delete()` | DELETE | ``/models/${modelId}`` | modelId: EntityId | `void` | `frontend/apps/web/src/views/ModelsView.vue:1088` | `frontend/packages/api-sdk/src/client.ts:1202` |
| `models.get()` | GET | ``/models/${modelId}`` | modelId: EntityId<br>config?: StudioRequestConfig | `DataModelDefinition` | `frontend/apps/web/src/components/ModelLineagePanel.vue:456`<br>`frontend/apps/web/src/components/ModelLineagePanel.vue:645`<br>`frontend/apps/web/src/views/CollectionTaskEditorView.vue:457`<br>`frontend/apps/web/src/views/DataIngestionServiceEditorView.vue:1638` | `frontend/packages/api-sdk/src/client.ts:1068` |
| `models.listDatasourceOptions()` | GET | ``/models/datasource/${datasourceId}/options`` | datasourceId: EntityId<br>params?: { keyword?: string; pageNo?: number; pageSize?: number; } | `unknown` | `frontend/apps/web/src/views/CollectionTaskEditorView.vue:421`<br>`frontend/apps/web/src/views/DataIngestionServiceEditorView.vue:1608`<br>`frontend/apps/web/src/views/DataServiceEditorView.vue:878`<br>`frontend/apps/web/src/views/QualityTaskEditorView.vue:442` | `frontend/packages/api-sdk/src/client.ts:1016` |
| `models.listSqlHintsByDatasource()` | GET | ``/models/datasource/${datasourceId}/sql-hints`` | datasourceId: EntityId<br>config?: StudioRequestConfig | `DataModelSqlHintView[]` | `frontend/apps/web/src/views/DataDevelopmentView.vue:886` | `frontend/packages/api-sdk/src/client.ts:1065` |
| `models.listSqlHintsByIds()` | GET | `/models/sql-hints` | modelIds: EntityId[]<br>query: `{ modelIds: modelIds.join(",") }` | `DataModelSqlHintView[]` | `frontend/apps/web/src/views/DataDevelopmentView.vue` | `frontend/packages/api-sdk/src/client.ts` |
| `models.listSummariesByDatasource()` | GET | ``/models/datasource/${datasourceId}/summaries`` | datasourceId: EntityId<br>params?: { pageNo?: number; pageSize?: number; sortField?: string; sortOrder?: string; }<br>query: `{ pageNo: params?.pageNo ?? 1, pageSize: params?.pageSize ?? 5000, sortField: params?.sortField, sortOrder: params?.sortOrder, }` | `PageResult<DataModelListView>` | - | `frontend/packages/api-sdk/src/client.ts:1047` |
| `models.listSummaryByDatasourcePage()` | GET | ``/models/datasource/${datasourceId}/summaries`` | datasourceId: EntityId<br>params?: { pageNo?: number; pageSize?: number; sortField?: string; sortOrder?: string; } | `unknown` | `frontend/apps/web/src/views/ModelsView.vue:753` | `frontend/packages/api-sdk/src/client.ts:1002` |
| `models.syncSelected()` | POST | ``/models/datasource/${datasourceId}/sync-selected`` | datasourceId: EntityId<br>payload: ModelSyncRequest<br>body: `payload` | `DataModelDefinition[]` | `frontend/apps/web/src/views/ModelsView.vue:1035` | `frontend/packages/api-sdk/src/client.ts:1154` |
| `models.sync()` | POST | ``/models/datasource/${datasourceId}/sync`` | datasourceId: EntityId<br>runtimeClusterId: EntityId<br>query: `{ runtimeClusterId }` | `DataModelDefinition[]` | - | `frontend/packages/api-sdk/src/client.ts` |
| `models.listByDatasource()` | GET | ``/models/datasource/${datasourceId}`` | datasourceId: EntityId<br>params?: { pageNo?: number; pageSize?: number; sortField?: string; sortOrder?: string; }<br>query: `{ pageNo: params?.pageNo ?? 1, pageSize: params?.pageSize ?? 5000, sortField: params?.sortField, sortOrder: params?.sortOrder, }` | `PageResult<DataModelDefinition>` | - | `frontend/packages/api-sdk/src/client.ts:1029` |
| `models.listByDatasourcePage()` | GET | ``/models/datasource/${datasourceId}`` | datasourceId: EntityId<br>params?: { pageNo?: number; pageSize?: number; sortField?: string; sortOrder?: string; } | `unknown` | - | `frontend/packages/api-sdk/src/client.ts:988` |


## 后端 Controller 对照

| Controller | 方法 | 路径 | Java 方法 | 参数来源 | 源码 |
|---|---|---|---|---|---|
| DataSourceController | GET | `/api/v1/datasources` | `list()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java:40` |
| DataSourceController | POST | `/api/v1/datasources` | `save()` | body: `@Valid @RequestBody DataSourceSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java:65` |
| DataSourceController | POST | `/api/v1/datasources/{id}/cluster-binding-impact` | `clusterBindingImpact()` | path: `id`<br>body: `DatasourceClusterBindingImpactRequest` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java` |
| DataSourceController | GET | `/api/v1/datasources/{id}` | `get()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java:59` |
| DataSourceController | DELETE | `/api/v1/datasources/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java:109` |
| DataSourceController | GET | `/api/v1/datasources/{id}/connection-history` | `connectionHistory()` | path: `@PathVariable("id") Long id`<br>query: `@RequestParam(value = "days"`<br>implicit: `required = false) Integer days`<br>query: `@RequestParam(value = "limit"`<br>implicit: `required = false) Integer limit` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java:83` |
| DataSourceController | POST | `/api/v1/datasources/{id}/discover` | `discover()` | path: `@PathVariable("id") Long id`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java:91` |
| DataSourceController | POST | `/api/v1/datasources/{id}/discover-options` | `discoverOptions()` | path: `@PathVariable("id") Long id`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java:100` |
| DataSourceController | POST | `/api/v1/datasources/{id}/test` | `test()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java:71` |
| DataSourceController | GET | `/api/v1/datasources/options` | `options()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java:53` |
| DataSourceController | GET | `/api/v1/datasources/page` | `listPage()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java:46` |
| DataSourceController | POST | `/api/v1/datasources/test` | `testCurrent()` | body: `@Valid @RequestBody DataSourceSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/DataSourceController.java:77` |
| MetaSchemaController | GET | `/api/v1/meta-schemas` | `list()` | query: `@RequestParam(value = "includeFields"`<br>implicit: `defaultValue = "true") boolean includeFields` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/MetaSchemaController.java:44` |
| MetaSchemaController | GET | `/api/v1/meta-schemas/{schemaId}` | `get()` | path: `@PathVariable("schemaId") Long schemaId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/MetaSchemaController.java:50` |
| MetaSchemaController | DELETE | `/api/v1/meta-schemas/{schemaId}` | `delete()` | path: `@PathVariable("schemaId") Long schemaId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/MetaSchemaController.java:111` |
| MetaSchemaController | POST | `/api/v1/meta-schemas/{schemaId}/publish` | `publish()` | path: `@PathVariable("schemaId") Long schemaId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/MetaSchemaController.java:69` |
| MetaSchemaController | GET | `/api/v1/meta-schemas/details` | `details()` | query: `@RequestParam("schemaIds") List<Long> schemaIds` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/MetaSchemaController.java:56` |
| MetaSchemaController | POST | `/api/v1/meta-schemas/draft` | `saveDraft()` | body: `@Valid @RequestBody MetadataSchemaSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/MetaSchemaController.java:62` |
| MetaSchemaController | POST | `/api/v1/meta-schemas/runtime-options/sync-standard` | `syncStandardRuntimeOptions()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/MetaSchemaController.java:104` |
| MetaSchemaController | POST | `/api/v1/meta-schemas/technical/sync-all` | `syncAllTechnical()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/MetaSchemaController.java:90` |
| MetaSchemaController | POST | `/api/v1/meta-schemas/technical/sync-all/summary` | `syncAllTechnicalSummary()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/MetaSchemaController.java:97` |
| MetaSchemaController | POST | `/api/v1/meta-schemas/technical/sync/{typeCode}` | `syncTechnical()` | path: `@PathVariable("typeCode") String typeCode` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/MetaSchemaController.java:76` |
| MetaSchemaController | POST | `/api/v1/meta-schemas/technical/sync/{typeCode}/summary` | `syncTechnicalSummary()` | path: `@PathVariable("typeCode") String typeCode` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/MetaSchemaController.java:83` |
| ModelSyncTaskController | POST | `/api/v1/model-sync-tasks` | `create()` | body: `@Valid @RequestBody ModelSyncTaskCreateRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelSyncTaskController.java:34` |
| ModelSyncTaskController | GET | `/api/v1/model-sync-tasks` | `list()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `defaultValue = "1") Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `defaultValue = "20") Integer pageSize`<br>query: `@RequestParam(value = "datasourceType"`<br>implicit: `required = false) String datasourceType`<br>query: `@RequestParam(value = "datasourceId"`<br>implicit: `required = false) Long datasourceId`<br>query: `@RequestParam(value = "status"`<br>implicit: `required = false) String status` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelSyncTaskController.java:40` |
| ModelSyncTaskController | GET | `/api/v1/model-sync-tasks/{taskId}` | `get()` | path: `@PathVariable("taskId") Long taskId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelSyncTaskController.java:50` |
| ModelSyncTaskController | DELETE | `/api/v1/model-sync-tasks/{taskId}` | `delete()` | path: `@PathVariable("taskId") Long taskId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelSyncTaskController.java:72` |
| ModelSyncTaskController | GET | `/api/v1/model-sync-tasks/{taskId}/items` | `listItems()` | path: `@PathVariable("taskId") Long taskId`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `defaultValue = "1") Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `defaultValue = "20") Integer pageSize`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "status"`<br>implicit: `required = false) String status` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelSyncTaskController.java:56` |
| ModelSyncTaskController | POST | `/api/v1/model-sync-tasks/{taskId}/stop` | `stop()` | path: `@PathVariable("taskId") Long taskId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelSyncTaskController.java:66` |
| ModelController | GET | `/api/v1/models` | `list()` | query: `@RequestParam(value = "datasourceType"`<br>implicit: `required = false) String datasourceType`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "sortField"`<br>implicit: `required = false) String sortField`<br>query: `@RequestParam(value = "sortOrder"`<br>implicit: `required = false) String sortOrder` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:61` |
| ModelController | POST | `/api/v1/models` | `save()` | body: `@Valid @RequestBody DataModelSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:165` |
| ModelController | GET | `/api/v1/models/{modelId}` | `get()` | path: `@PathVariable("modelId") Long modelId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:124` |
| ModelController | DELETE | `/api/v1/models/{modelId}` | `delete()` | path: `@PathVariable("modelId") Long modelId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:218` |
| ModelController | GET | `/api/v1/models/{modelId}/lineage` | `lineage()` | path: `@PathVariable("modelId") Long modelId`<br>query: `@RequestParam("level") LineageLevel level` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:178` |
| ModelController | GET | `/api/v1/models/{modelId}/lineage/edges/{edgeId}` | `lineageEdgeDetail()` | path: `@PathVariable("modelId") Long modelId`<br>path: `@PathVariable("edgeId") String edgeId`<br>query: `@RequestParam("level") LineageLevel level` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:185` |
| ModelController | POST | `/api/v1/models/{modelId}/lineage/manual` | `createManualLineage()` | path: `@PathVariable("modelId") Long modelId`<br>body: `@Valid @RequestBody DataModelManualLineageSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:193` |
| ModelController | PUT | `/api/v1/models/{modelId}/lineage/manual/{relationId}` | `updateManualLineage()` | path: `@PathVariable("modelId") Long modelId`<br>path: `@PathVariable("relationId") Long relationId`<br>body: `@Valid @RequestBody DataModelManualLineageSaveRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:201` |
| ModelController | DELETE | `/api/v1/models/{modelId}/lineage/manual/{relationId}` | `deleteManualLineage()` | path: `@PathVariable("modelId") Long modelId`<br>path: `@PathVariable("relationId") Long relationId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:210` |
| ModelController | GET | `/api/v1/models/{modelId}/preview` | `preview()` | path: `@PathVariable("modelId") Long modelId`<br>query: `@RequestParam(value = "limit", defaultValue = "20") int limit`<br>query: `@RequestParam("runtimeClusterId") Long runtimeClusterId`（必填） | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:202` |
| ModelController | GET | `/api/v1/models/datasource/{datasourceId}` | `listByDatasource()` | path: `@PathVariable("datasourceId") Long datasourceId`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "sortField"`<br>implicit: `required = false) String sortField`<br>query: `@RequestParam(value = "sortOrder"`<br>implicit: `required = false) String sortOrder` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:89` |
| ModelController | GET | `/api/v1/models/datasource/{datasourceId}/options` | `listDatasourceOptions()` | path: `@PathVariable("datasourceId") Long datasourceId`<br>query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:109` |
| ModelController | GET | `/api/v1/models/datasource/{datasourceId}/sql-hints` | `listSqlHintsByDatasource()` | path: `@PathVariable("datasourceId") Long datasourceId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:118` |
| ModelController | GET | `/api/v1/models/datasource/{datasourceId}/summaries` | `listSummariesByDatasource()` | path: `@PathVariable("datasourceId") Long datasourceId`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "sortField"`<br>implicit: `required = false) String sortField`<br>query: `@RequestParam(value = "sortOrder"`<br>implicit: `required = false) String sortOrder` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:99` |
| ModelController | POST | `/api/v1/models/datasource/{datasourceId}/sync` | `sync()` | path: `@PathVariable("datasourceId") Long datasourceId`<br>query: `@RequestParam("runtimeClusterId") Long runtimeClusterId`（必填） | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:179` |
| ModelController | POST | `/api/v1/models/datasource/{datasourceId}/sync-selected` | `syncSelected()` | path: `@PathVariable("datasourceId") Long datasourceId`<br>body: `@Valid @RequestBody ModelSyncRequest request`，其中 `runtimeClusterId/physicalLocators` 必填 | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:187` |
| ModelController | GET | `/api/v1/models/index/queue-status` | `indexQueueStatus()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:231` |
| ModelController | POST | `/api/v1/models/index/rebuild` | `rebuildIndex()` | query: `@RequestParam(value = "datasourceId"`<br>implicit: `required = false) Long datasourceId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:225` |
| ModelController | GET | `/api/v1/models/options` | `listOptions()` | query: `@RequestParam(value = "keyword"`<br>implicit: `required = false) String keyword`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:81` |
| ModelController | GET | `/api/v1/models/selector-options` | `listSelectorOptions()` | query: `runtimeClusterId: Long`（必填）<br>query: `datasourceType?: string`<br>query: `datasourceId?: Long`<br>query: `keyword?: string`<br>query: `pageNo?: Integer`<br>query: `pageSize?: Integer` | 模型 Flink SQL 和模型预览选择使用；只返回适用于指定运行集群的模型。`datasourceId` 与 `datasourceType` 同传时取交集，`keyword` 匹配模型名称和物理表名。 |
| ModelController | GET | `/api/v1/models/sql-hints` | `listSqlHintsByModelIds()` | query: `modelIds?: Long[]`，支持逗号分隔 | 模型 Flink SQL 编辑器使用；只返回已选模型的 `id/datasourceId/name/physicalLocator/columns`。 |
| ModelController | POST | `/api/v1/models/query` | `query()` | body: `@RequestBody(required = false) DataModelQueryRequest request`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:130` |
| ModelController | POST | `/api/v1/models/query/summaries` | `querySummaries()` | body: `@RequestBody(required = false) DataModelQueryRequest request`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:138` |
| ModelController | POST | `/api/v1/models/statistics` | `statistics()` | body: `@RequestBody DataModelStatisticsRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:146` |
| ModelController | GET | `/api/v1/models/summaries` | `listSummaries()` | query: `@RequestParam(value = "datasourceType"`<br>implicit: `required = false) String datasourceType`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "sortField"`<br>implicit: `required = false) String sortField`<br>query: `@RequestParam(value = "sortOrder"`<br>implicit: `required = false) String sortOrder` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/ModelController.java:71` |
| StatisticsController | POST | `/api/v1/statistics/charts/query` | `queryChart()` | body: `@RequestBody DataModelStatisticsChartRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/StatisticsController.java:34` |
| StatisticsController | POST | `/api/v1/statistics/options` | `options()` | body: `@RequestBody(required = false) DataModelStatisticsOptionsRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/StatisticsController.java:28` |


## cURL 模板

以下模板按前端 SDK 自动生成，动态路径中的 `{id}`、`{serviceId}` 等需要替换为上游接口返回值。出现 `?<query>` 时按表格中的 query 参数追加。

如果接口清单的“参数/请求体”列没有显示 `body:`，则自动化调用时删除模板中的 `-H "Content-Type: application/json"` 和 `-d` 行；只有列出 `body:` 的接口才发送 JSON 请求体。

### datasources.list()

```bash
curl -X GET "${BASE_URL}/datasources" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### datasources.save()

```bash
curl -X POST "${BASE_URL}/datasources" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### datasources.options()

```bash
curl -X GET "${BASE_URL}/datasources/options?runtimeClusterId=${RUNTIME_CLUSTER_ID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### datasources.listPage()

```bash
curl -X GET "${BASE_URL}/datasources/page" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### datasources.testCurrent()

```bash
curl -X POST "${BASE_URL}/datasources/test?runtimeClusterId=${RUNTIME_CLUSTER_ID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### metaSchemas.list()

```bash
curl -X GET "${BASE_URL}/meta-schemas" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### metaSchemas.details()

```bash
curl -X GET "${BASE_URL}/meta-schemas/details?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### metaSchemas.saveDraft()

```bash
curl -X POST "${BASE_URL}/meta-schemas/draft" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### metaSchemas.syncStandardRuntimeOptions()

```bash
curl -X POST "${BASE_URL}/meta-schemas/runtime-options/sync-standard" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### metaSchemas.syncAllTechnical()

```bash
curl -X POST "${BASE_URL}/meta-schemas/technical/sync-all" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### metaSchemas.syncAllTechnicalSummary()

```bash
curl -X POST "${BASE_URL}/meta-schemas/technical/sync-all/summary" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### modelSyncTasks.create()

```bash
curl -X POST "${BASE_URL}/model-sync-tasks" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### modelSyncTasks.list()

```bash
curl -X GET "${BASE_URL}/model-sync-tasks" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### models.list()

```bash
curl -X GET "${BASE_URL}/models?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### models.listPage()

```bash
curl -X GET "${BASE_URL}/models" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### models.save()

```bash
curl -X POST "${BASE_URL}/models" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### models.indexQueueStatus()

```bash
curl -X GET "${BASE_URL}/models/index/queue-status" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### models.rebuildIndex()

```bash
curl -X POST "${BASE_URL}/models/index/rebuild?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### models.listOptions()

```bash
curl -X GET "${BASE_URL}/models/options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### models.listSelectorOptions()

```bash
curl -X GET "${BASE_URL}/models/selector-options?runtimeClusterId=${RUNTIME_CLUSTER_ID}&datasourceType=mysql8&datasourceId=11&keyword=customer&pageNo=1&pageSize=20" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### models.listSqlHintsByIds()

```bash
curl -X GET "${BASE_URL}/models/sql-hints?modelIds=1001,1002" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### models.query()

```bash
curl -X POST "${BASE_URL}/models/query?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```


## 开放访问 cURL 补充

开放访问接口不走管理端 `${BASE_URL}` 前缀，使用 `${STUDIO_ORIGIN}`，例如 `http://127.0.0.1:18080`。订阅令牌来自对应服务的订阅创建/轮换接口。

本模块无额外开放访问 Controller 模板。


## 主要 DTO 字段

### ConnectionTestResult

来源：`frontend/packages/api-sdk/src/types.ts:356`

```ts
interface ConnectionTestResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| success | 否 | `boolean` | |
| message | 否 | `string` | |
| status | 否 | `DataSourceConnectionStatus` | |
| durationMs | 否 | `number \| string \| null` | |
| testing | 否 | `boolean` | |
| stale | 否 | `boolean` | |
| busy | 否 | `boolean` | |
| lastTestAt | 否 | `string` | |
| nextProbeAt | 否 | `string` | |
| timeoutSeconds | 否 | `number` | |
| recentConnectionTests | 否 | `DatasourceConnectionTestRecordView[]` | |
| detail | 否 | `string` | |

### DataModelDefinition

来源：`frontend/packages/api-sdk/src/types.ts:372`

```ts
interface DataModelDefinition extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 是 | `EntityId` | |
| name | 是 | `string` | |
| modelKind | 否 | `ModelKind` | |
| physicalLocator | 是 | `string` | |
| schemaVersionId | 否 | `EntityId` | |
| technicalMetadata | 是 | `Record<string, unknown>` | |
| businessMetadata | 是 | `Record<string, unknown>` | |

### DataModelIndexQueueStatusView

来源：`frontend/packages/api-sdk/src/types.ts:1328`

```ts
interface DataModelIndexQueueStatusView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| queuedRebuildCount | 否 | `number \| string \| null` | |
| activeRebuildCount | 否 | `number \| string \| null` | |
| pendingRebuildCount | 否 | `number \| string \| null` | |
| queuedCommandCount | 否 | `number \| string \| null` | |
| activeCommandCount | 否 | `number \| string \| null` | |
| busy | 否 | `boolean \| null` | |

### DataModelLineageEdgeDetailView

来源：`frontend/packages/api-sdk/src/types.ts:1305`

```ts
interface DataModelLineageEdgeDetailView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| edgeId | 否 | `string` | |
| sourceNodeTitle | 否 | `string` | |
| targetNodeTitle | 否 | `string` | |
| sourceField | 否 | `string` | |
| targetField | 否 | `string` | |
| sourceType | 否 | `string` | |
| sourceTypeLabel | 否 | `string` | |
| displayStatus | 否 | `string` | |
| latestRunStatus | 否 | `string` | |
| latestRunId | 否 | `EntityId` | |
| latestRunAt | 否 | `string` | |
| contributors | 是 | `DataModelLineageContributorView[]` | |

### DataModelLineageLevel

来源：`frontend/packages/api-sdk/src/types.ts:157`

```ts
type DataModelLineageLevel = "DATABASE" | "TABLE" | "FIELD";
```

### DataModelLineageView

来源：`frontend/packages/api-sdk/src/types.ts:1320`

```ts
interface DataModelLineageView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| editable | 否 | `boolean` | |
| summary | 否 | `DataModelLineageSummaryView` | |
| nodes | 是 | `DataModelLineageNodeView[]` | |
| edges | 是 | `DataModelLineageEdgeView[]` | |
| unresolvedExpressions | 是 | `DataModelLineageUnresolvedExpressionView[]` | |

### DataModelListView

来源：`frontend/packages/api-sdk/src/types.ts:382`

```ts
interface DataModelListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 是 | `EntityId` | |
| name | 是 | `string` | |
| modelKind | 否 | `ModelKind` | |
| physicalLocator | 是 | `string` | |
| schemaVersionId | 否 | `EntityId` | |

### DataModelManualLineageSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:1373`

```ts
interface DataModelManualLineageSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| level | 是 | `DataModelLineageLevel` | |
| sourceModelId | 是 | `EntityId` | |
| targetModelId | 是 | `EntityId` | |
| sourceFieldKey | 否 | `string` | |
| targetFieldKey | 否 | `string` | |

### DataModelQueryRequest

来源：`frontend/packages/api-sdk/src/types.ts:1362`

```ts
interface DataModelQueryRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 否 | `EntityId` | |
| datasourceType | 否 | `string` | |
| modelKind | 否 | `ModelKind \| string` | |
| sortField | 否 | `string` | |
| sortOrder | 否 | `string` | |
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |
| groups | 是 | `DataModelQueryGroup[]` | |

### DataModelSaveRequest

来源：`frontend/packages/api-sdk/src/types.ts:1337`

```ts
interface DataModelSaveRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| datasourceId | 是 | `EntityId` | |
| name | 是 | `string` | |
| physicalLocator | 是 | `string` | |
| modelKind | 否 | `ModelKind` | |
| schemaVersionId | 否 | `EntityId` | |
| technicalMetadata | 是 | `Record<string, unknown>` | |
| businessMetadata | 是 | `Record<string, unknown>` | |

### DataModelStatisticsChartRequest

来源：`frontend/packages/api-sdk/src/types.ts:1444`

```ts
interface DataModelStatisticsChartRequest extends DataModelStatisticsRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| chartType | 是 | `StatisticsChartType \| string` | |
| days | 否 | `number` | |
| timeMode | 否 | `"CREATED_AT" \| string` | |

### DataModelStatisticsChartView

来源：`frontend/packages/api-sdk/src/types.ts:1469`

```ts
interface DataModelStatisticsChartView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| chartType | 否 | `StatisticsChartType \| string` | |
| summaryMetrics | 是 | `Record<string, unknown>` | |
| xAxis | 是 | `string[]` | |
| series | 是 | `DataModelStatisticsChartSeriesView[]` | |
| tableRows | 是 | `DataModelStatisticsChartTableRowView[]` | |
| disabledReason | 否 | `string` | |

### DataModelStatisticsOptionsRequest

来源：`frontend/packages/api-sdk/src/types.ts:1412`

```ts
interface DataModelStatisticsOptionsRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 否 | `EntityId` | |
| datasourceType | 否 | `string` | |
| targetScope | 否 | `MetadataScope \| string` | |

### DataModelStatisticsOptionsView

来源：`frontend/packages/api-sdk/src/types.ts:1438`

```ts
interface DataModelStatisticsOptionsView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceType | 否 | `string` | |
| querySchemas | 是 | `DataModelStatisticsSchemaOptionView[]` | |
| targetSchemas | 是 | `DataModelStatisticsSchemaOptionView[]` | |

### DataModelStatisticsRequest

来源：`frontend/packages/api-sdk/src/types.ts:1387`

```ts
interface DataModelStatisticsRequest extends DataModelQueryRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| targetMetaSchemaCode | 是 | `string` | |
| targetFieldKey | 是 | `string` | |
| targetScope | 否 | `MetadataScope \| string` | |
| statType | 否 | `StatisticType \| string` | |
| topN | 否 | `number` | |
| bucketConfig | 否 | `DataModelStatisticsBucketConfig` | |

### DataModelStatisticsView

来源：`frontend/packages/api-sdk/src/types.ts:1405`

```ts
interface DataModelStatisticsView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| matchedModelCount | 否 | `number \| string \| null` | |
| matchedItemCount | 否 | `number \| string \| null` | |
| buckets | 是 | `DataModelStatisticsBucketView[]` | |
| summaryMetrics | 是 | `Record<string, unknown>` | |

### DataSourceDefinition

来源：`frontend/packages/api-sdk/src/types.ts:308`

```ts
interface DataSourceDefinition extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| name | 是 | `string` | |
| typeCode | 是 | `string` | |
| schemaVersionId | 否 | `EntityId` | |
| enabled | 否 | `boolean` | |
| executable | 否 | `boolean` | |
| connectionFingerprint | 否 | `string` | |
| connectionStatus | 否 | `DataSourceConnectionStatus` | |
| lastConnectionTestAt | 否 | `string` | |
| lastConnectionTestMessage | 否 | `string` | |
| lastConnectionTestDurationMs | 否 | `number \| string \| null` | |
| connectionTesting | 否 | `boolean` | |
| connectionStale | 否 | `boolean` | |
| nextConnectionProbeAt | 否 | `string` | |
| manualConnectionTestTimeoutSeconds | 否 | `number` | |
| scheduledConnectionTestTimeoutSeconds | 否 | `number` | |
| savedSensitiveFieldKeys | 否 | `string[]` | 只读。列出已保存但不会在响应中返回值的敏感 `technicalMetadata` 字段。 |
| applicableClusterIds | 否 | `EntityId[]` | 数据源适用的运行集群 ID。保存时至少提交一个当前项目已授权集群。 |
| applicableClusters | 否 | `RuntimeClusterView[]` | 只读。数据源适用集群的脱敏摘要。 |
| recentConnectionTests | 否 | `DatasourceConnectionTestRecordView[]` | |
| technicalMetadata | 是 | `Record<string, unknown>` | |
| businessMetadata | 是 | `Record<string, unknown>` | |

### DataSourceListView

来源：`frontend/packages/api-sdk/src/types.ts:337`

```ts
interface DataSourceListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| name | 是 | `string` | |
| typeCode | 是 | `string` | |
| schemaVersionId | 否 | `EntityId` | |
| enabled | 否 | `boolean` | |
| executable | 否 | `boolean` | |
| connectionFingerprint | 否 | `string` | |
| connectionStatus | 否 | `DataSourceConnectionStatus` | |
| lastConnectionTestAt | 否 | `string` | |
| lastConnectionTestMessage | 否 | `string` | |
| lastConnectionTestDurationMs | 否 | `number \| string \| null` | |
| connectionTesting | 否 | `boolean` | |
| connectionStale | 否 | `boolean` | |
| nextConnectionProbeAt | 否 | `string` | |
| manualConnectionTestTimeoutSeconds | 否 | `number` | |
| scheduledConnectionTestTimeoutSeconds | 否 | `number` | |
| recentConnectionTests | 否 | `DatasourceConnectionTrendPointView[]` | |

### MetadataSchemaDefinition

来源：`frontend/packages/api-sdk/src/types.ts:214`

```ts
interface MetadataSchemaDefinition extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| schemaCode | 是 | `string` | |
| schemaName | 是 | `string` | |
| objectType | 是 | `string` | |
| typeCode | 是 | `string` | |
| currentVersionId | 否 | `EntityId` | |
| versionNumber | 否 | `number` | |
| status | 否 | `SchemaStatus` | |
| description | 否 | `string` | |
| fields | 是 | `MetadataFieldDefinition[]` | |

### ModelDiscoveryOptionResult

来源：`frontend/packages/api-sdk/src/types.ts:1492`

```ts
interface ModelDiscoveryOptionResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| models | 是 | `DataModelDatasourceOptionView[]` | |
| message | 否 | `string` | |
| total | 否 | `number` | |
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |
| hasMore | 否 | `boolean` | |

### ModelDiscoveryResult

来源：`frontend/packages/api-sdk/src/types.ts:1482`

```ts
interface ModelDiscoveryResult
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| models | 是 | `DataModelDefinition[]` | |
| message | 否 | `string` | |
| total | 否 | `number` | |
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |
| hasMore | 否 | `boolean` | |

### ModelSyncRequest

来源：`frontend/packages/api-sdk/src/types.ts:1478`

```ts
interface ModelSyncRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| physicalLocators | 是 | `string[]` | |

### ModelSyncTaskCreateRequest

来源：`frontend/packages/api-sdk/src/types.ts:1502`

```ts
interface ModelSyncTaskCreateRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 是 | `EntityId` | |
| physicalLocators | 是 | `string[]` | |
| source | 否 | `ModelSyncTaskSource \| string` | |

### ModelSyncTaskItemView

来源：`frontend/packages/api-sdk/src/types.ts:1529`

```ts
interface ModelSyncTaskItemView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| taskId | 否 | `EntityId` | |
| seqNo | 否 | `number` | |
| physicalLocator | 否 | `string` | |
| modelNameSnapshot | 否 | `string` | |
| status | 否 | `ModelSyncTaskItemStatus \| string` | |
| message | 否 | `string` | |
| startedAt | 否 | `string` | |
| finishedAt | 否 | `string` | |
| durationMs | 否 | `number` | |

### ModelSyncTaskView

来源：`frontend/packages/api-sdk/src/types.ts:1508`

```ts
interface ModelSyncTaskView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| datasourceId | 否 | `EntityId` | |
| datasourceType | 否 | `string` | |
| datasourceNameSnapshot | 否 | `string` | |
| batchNo | 否 | `number` | |
| name | 是 | `string` | |
| source | 否 | `ModelSyncTaskSource \| string` | |
| status | 否 | `ModelSyncTaskStatus \| string` | |
| totalCount | 否 | `number` | |
| successCount | 否 | `number` | |
| failedCount | 否 | `number` | |
| stoppedCount | 否 | `number` | |
| progressPercent | 否 | `number` | |
| stopRequested | 否 | `boolean` | |
| createdBy | 否 | `EntityId` | |
| startedAt | 否 | `string` | |
| finishedAt | 否 | `string` | |
| durationMs | 否 | `number` | |
| lastError | 否 | `string` | |
