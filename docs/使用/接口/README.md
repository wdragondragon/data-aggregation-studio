# Studio 界面接口文档索引

本文档集面向后续编写自动化 Skill 使用，按 Studio Web 界面模块拆分接口、DTO 字段、Controller 对照和 cURL 模板。接口来源以 `frontend/packages/api-sdk/src/client.ts` 为主，并用 `backend/studio-server/.../controller` 中的 Controller 校准路径和参数来源。

## 全局调用约定

```bash
BASE_URL="http://127.0.0.1:18080/api/v1"
STUDIO_ORIGIN="http://127.0.0.1:18080"
STUDIO_USERNAME="${STUDIO_USERNAME:?set STUDIO_USERNAME}"
STUDIO_PASSWORD="${STUDIO_PASSWORD:?set STUDIO_PASSWORD}"
TOKEN="$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$STUDIO_USERNAME\",\"password\":\"$STUDIO_PASSWORD\"}" | jq -r '.data.token')"
TENANT_ID="<从 /auth/me 或 /auth/login 的 data.tenants 中选择>"
PROJECT_ID="<从 /auth/me 或 /auth/login 的 data.projects 中选择>"
```

通用请求头：

```bash
-H "Authorization: Bearer $TOKEN" \
-H "X-Tenant-Id: $TENANT_ID" \
-H "X-Project-Id: $PROJECT_ID"
```

## 模块文档

| 模块文档 | 前端 SDK 接口数 | Controller 接口数 |
|---|---:|---:|
| [公共约定、认证与 cURL 模板](./00-common-auth-and-conventions.md) | 7 | 7 |
| [工作台与目录能力接口](./01-dashboard-catalog.md) | 4 | 4 |
| [数据资产、数据源、元模型与模型中心接口](./02-assets-datasources-models.md) | 63 | 54 |
| [字段映射规则、采集任务和采集运行接口](./03-collection-and-field-mapping.md) | 27 | 27 |
| [数据开发、工作流与运行日志接口](./04-data-development-workflows-runs.md) | 57 | 57 |
| [数据服务开放接口](./05-open-data-services.md) | 25 | 28 |
| [数据接入服务接口](./06-data-ingestion-services.md) | 25 | 28 |
| [协议转换服务接口](./07-protocol-conversions.md) | 21 | 24 |
| [数据质量规则、任务、指标和问题接口](./08-quality.md) | 46 | 45 |
| [系统管理、权限、访问申请、通知和关注接口](./09-system-access-notifications.md) | 58 | 59 |
| [运维中心、运行时、导入导出和 AI 助手接口](./10-ops-center-runtime-import-export-assistant.md) | 19 | 20 |
| [统一告警中心接口](./11-alert-center.md) | 28 | 26 |
| [运行集群与数据源适用范围接口](./12-runtime-clusters.md) | 16 | 16 |
| [运行调用幂等保护](./13-runtime-invocation-idempotency.md) | 0（公共写入口增量） | 0（既有入口增量） |
| [业务接口运行集群 ID 下推分析](./14-runtime-cluster-id-pushdown-analysis.md) | 0（分析记录） | 0（既有接口分析） |

## 界面路由覆盖

- `/login` -> `frontend/apps/web/src/views/LoginView.vue`
- `/register` -> `frontend/apps/web/src/views/RegisterView.vue`
- `/access-center` -> `frontend/apps/web/src/views/WorkspaceAccessView.vue`
- `/dashboard` -> `frontend/apps/web/src/views/DashboardView.vue`
- `/catalog` -> `frontend/apps/web/src/views/CatalogView.vue`
- `/metadata` -> `frontend/apps/web/src/views/MetadataSchemasView.vue`
- `/datasources` -> `frontend/apps/web/src/views/DatasourcesView.vue`
- `/models` -> `frontend/apps/web/src/views/ModelsView.vue`
- `/statistics` -> `frontend/apps/web/src/views/ModelStatisticsView.vue`
- `/models/sync-tasks/:taskId` -> `frontend/apps/web/src/views/ModelSyncTaskDetailView.vue`
- `/models/:modelId` -> `frontend/apps/web/src/views/ModelsView.vue`
- `/data-development` -> `frontend/apps/web/src/views/DataDevelopmentView.vue`
- `/script-environments` -> `frontend/apps/web/src/views/ScriptEnvironmentsView.vue`
- `/data-services` -> `frontend/apps/web/src/views/DataServicesView.vue`
- `/data-services/new` -> `frontend/apps/web/src/views/DataServiceEditorView.vue`
- `/data-services/:serviceId/edit` -> `frontend/apps/web/src/views/DataServiceEditorView.vue`
- `/data-ingestion-services` -> `frontend/apps/web/src/views/DataIngestionServicesView.vue`
- `/data-ingestion-services/new` -> `frontend/apps/web/src/views/DataIngestionServiceEditorView.vue`
- `/data-ingestion-services/:serviceId/edit` -> `frontend/apps/web/src/views/DataIngestionServiceEditorView.vue`
- `/protocol-conversions` -> `frontend/apps/web/src/views/ProtocolConversionServicesView.vue`
- `/protocol-conversions/new` -> `frontend/apps/web/src/views/ProtocolConversionServiceEditorView.vue`
- `/protocol-conversions/access-logs` -> `frontend/apps/web/src/views/ProtocolConversionAccessLogsView.vue`
- `/protocol-conversions/:serviceId/edit` -> `frontend/apps/web/src/views/ProtocolConversionServiceEditorView.vue`
- `/data-service-metrics` -> `frontend/apps/web/src/views/DataServiceMetricsView.vue`
- `/data-service-metrics/access-logs` -> `frontend/apps/web/src/views/DataServiceAccessLogsView.vue`
- `/data-ingestion-metrics` -> `frontend/apps/web/src/views/DataIngestionMetricsView.vue`
- `/data-ingestion-metrics/access-logs` -> `frontend/apps/web/src/views/DataIngestionAccessLogsView.vue`
- `/field-mapping-rules` -> `frontend/apps/web/src/views/FieldMappingRulesView.vue`
- `/field-mapping-rules/new` -> `frontend/apps/web/src/views/FieldMappingRuleEditorView.vue`
- `/field-mapping-rules/:ruleId/edit` -> `frontend/apps/web/src/views/FieldMappingRuleEditorView.vue`
- `/collection-tasks` -> `frontend/apps/web/src/views/CollectionTasksView.vue`
- `/collection-tasks/new` -> `frontend/apps/web/src/views/CollectionTaskEditorView.vue`
- `/collection-tasks/:taskId/edit` -> `frontend/apps/web/src/views/CollectionTaskEditorView.vue`
- `/collection-task-runs` -> `frontend/apps/web/src/views/CollectionTaskRunsView.vue`
- `/quality-metrics` -> `frontend/apps/web/src/views/QualityMetricsView.vue`
- `/quality-rules` -> `frontend/apps/web/src/views/QualityRulesView.vue`
- `/quality-rules/new` -> `frontend/apps/web/src/views/QualityRuleEditorView.vue`
- `/quality-rules/:ruleId/edit` -> `frontend/apps/web/src/views/QualityRuleEditorView.vue`
- `/quality-tasks` -> `frontend/apps/web/src/views/QualityTasksView.vue`
- `/quality-tasks/new` -> `frontend/apps/web/src/views/QualityTaskEditorView.vue`
- `/quality-tasks/:taskId/edit` -> `frontend/apps/web/src/views/QualityTaskEditorView.vue`
- `/quality-task-runs` -> `frontend/apps/web/src/views/QualityTaskRunsView.vue`
- `/workflows` -> `frontend/apps/web/src/views/WorkflowsView.vue`
- `/workflows/:workflowId` -> `frontend/apps/web/src/views/WorkflowDetailView.vue`
- `/workflows/new` -> `frontend/apps/web/src/views/WorkflowEditorView.vue`
- `/workflows/:workflowId/edit` -> `frontend/apps/web/src/views/WorkflowEditorView.vue`
- `/runs` -> `frontend/apps/web/src/views/RunsView.vue`
- `/runs/:workflowRunId` -> `frontend/apps/web/src/views/WorkflowRunDetailView.vue`
- `/run-metrics` -> `frontend/apps/web/src/views/RunMetricsView.vue`
- `/ops-center` -> `frontend/apps/web/src/views/OpsCenterView.vue`
- `/alerts` -> `frontend/apps/web/src/views/AlertsView.vue`
- `/runtime-clusters` -> `frontend/apps/web/src/views/RuntimeClustersView.vue`
- `/notifications` -> `frontend/apps/web/src/views/NotificationsView.vue`
- `/system` -> `frontend/apps/web/src/views/SystemView.vue`

## 抽取覆盖说明

- 前端 SDK 接口总数：368
- 后端产品 Controller 方法总数：374（375 个标准 Mapping 方法 + 2 个通用 `@RequestMapping` 方法，再排除仅用于开发期冒烟测试的 `HttpWriterSinkController` 3 个临时接收接口）
- 文档覆盖方式：管理端界面优先按前端 SDK 分组，开放访问接口按后端 Controller 路径补充到对应服务模块。
- 最新校准日期：2026-08-03

2026-07-20 的多集群增量同时修改了多个既有接口的参数和 DTO，但没有增加对应方法数量。各模块开头的“多集群增量契约”优先于历史自动抽取表中的旧参数摘要；源码行号可能随本轮实现漂移，联调时以接口路径、方法名和增量契约为准。
