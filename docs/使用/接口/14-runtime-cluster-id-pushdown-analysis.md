# 业务接口运行集群 ID 下推分析

## 文档目的

本文记录 Studio 多集群场景下，集群管理模块之外的业务接口如何获得 `runtimeClusterId`，用于后续接口调整、前端联调、AI 助手和自动化 Skill 同步时统一判断依据。

本文是截至 2026-08-03 的代码分析记录，不代表下述“可新增推断”能力已经实现。当前接口契约仍以各模块接口文档和 Controller、DTO 实现为准；在完成代码、DTO、SDK、测试和模块文档同步前，调用方仍应按现行契约提交 `runtimeClusterId`。

## 术语和边界

- **显式选择**：请求直接提交 `runtimeClusterId`。
- **数据源推断**：根据一个或多个关联数据源的适用集群，计算唯一可用集群。
- **资源继承**：运行已有任务、脚本、工作流或服务时，使用资源已经保存的 `runtimeClusterId`。
- **手动覆盖**：运行已有资源时，请求提供不同于保存值的集群；必须额外校验项目是否允许手动覆盖。
- **下推**：Server 在进入 Worker 路由、调度队列或运行调用前，得到非空 `runtimeClusterId` 并传给后续执行链路。

本文不建议从 OMS 所在集群、项目首选集群、默认集群或查询结果中的第一个集群静默兜底。这些值不能证明与当前数据源或业务资源兼容。

## 统一解析规则

建议所有可推断接口采用相同优先级：

1. 请求显式提供 `runtimeClusterId` 时，校验租户、项目授权、集群启用状态和全部关联数据源适用性后使用。
2. 编辑或运行已有资源且请求未提供时，优先使用资源已经保存的 `runtimeClusterId`。
3. 新建、预览或临时执行且没有保存值时，收集当前请求关联的全部数据源 ID。
4. 计算这些数据源的启用绑定集群交集，再过滤当前项目未授权或已停用的集群。
5. 候选集群恰好一个时自动使用；候选为空或多于一个时拒绝请求并要求显式选择。
6. 运行阶段仍须检查目标 Worker、端点和内部认证是否可用。目标集群不可运行时直接返回错误，不自动改投其他集群。

`runtimeClusterId` 可以在请求层表现为“条件必填”，但在资源持久化、Dispatch 入队和 Worker 路由前必须解析为非空值。

## 当前已经实现资源继承的接口

以下接口已经可以在不传手动覆盖值时，从已保存资源继承运行集群。

| 所属模块 | 方法和路径 | 集群来源 | 当前行为说明 |
|---|---|---|---|
| 数据开发 | `POST /api/v1/data-development/scripts/{id}/execute` | 已保存脚本 | 请求可选覆盖值；未覆盖时使用脚本保存值。SQL 脚本仍应走 SQL 执行接口。 |
| 采集任务 | `POST /api/v1/collection-tasks/{id}/trigger` | 已保存采集任务 | 未覆盖时使用任务保存值；覆盖时校验手动覆盖权限和数据源适用性。 |
| 数据质量 | `POST /api/v1/quality-tasks/{id}/trigger` | 已保存质量任务 | 未覆盖时使用任务保存值。 |
| 工作流 | `POST /api/v1/workflows/{id}/trigger` | 已保存工作流 | 未覆盖时使用工作流保存值，节点必须与工作流集群一致。 |
| 数据服务 | `POST /api/v1/data-services/{id}/debug` | 已保存数据服务 | REST 调试路由到服务保存的集群。 |
| 数据服务 | `POST /api/v1/data-services/{id}/webservice/debug` | 已保存数据服务 | SOAP 调试路由到服务保存的集群。 |
| 数据接入 | `POST /api/v1/data-ingestion-services/{id}/debug` | 已保存接入服务 | REST 调试路由到服务保存的集群。 |
| 数据接入 | `POST /api/v1/data-ingestion-services/{id}/webservice/debug` | 已保存接入服务 | SOAP 调试路由到服务保存的集群。 |
| 协议转换 | `POST /api/v1/protocol-conversions/{id}/debug` | 已保存协议转换服务 | 调试路由到服务保存的集群。 |
| 数据服务开放调用 | `GET/POST /openapi/data-services/{serviceCode}/{serviceKey}` | 已发布数据服务 | 根据服务标识读取保存集群后路由。 |
| 数据服务开放调用 | `POST /openapi/ws/data-services/{serviceCode}/{serviceKey}` | 已发布数据服务 | SOAP 调用使用服务保存集群。 |
| 数据接入开放调用 | `POST /openapi/data-ingestion-services/{serviceCode}/{serviceKey}` | 已发布接入服务 | 使用服务保存集群。 |
| 数据接入开放调用 | `POST /openapi/ws/data-ingestion-services/{serviceCode}/{serviceKey}` | 已发布接入服务 | SOAP 调用使用服务保存集群。 |
| 协议转换开放调用 | `GET/POST/PUT/PATCH /openapi/protocol-conversions/{serviceCode}/{serviceKey}` | 已发布协议转换服务 | 使用服务保存集群。 |
| 协议转换开放调用 | `POST /openapi/ws/protocol-conversions/{serviceCode}/{serviceKey}` | 已发布协议转换服务 | SOAP 调用使用服务保存集群。 |

采集任务、质量任务和工作流的定时调度不是独立的浏览器调用接口，但执行时同样使用资源保存的集群，不接受临时覆盖。

## 可新增数据源唯一集群推断的接口

以下接口具有明确的数据源、模型或待保存数据源表单，可以在请求未提供集群时尝试唯一集群推断。当前实现仍要求显式集群。

| 所属模块 | 方法和路径 | 推断依据 | 失败条件 |
|---|---|---|---|
| 数据源管理 | `POST /api/v1/datasources/{id}/test` | 路径中的数据源 ID | 数据源没有可用集群或存在多个候选。 |
| 数据源管理 | `POST /api/v1/datasources/{id}/discover` | 路径中的数据源 ID | 同上。 |
| 数据源管理 | `POST /api/v1/datasources/{id}/discover-options` | 路径中的数据源 ID | 同上。 |
| 数据源管理 | `POST /api/v1/datasources/test` | 表单中的 `applicableClusterIds`；编辑时也可参考已有数据源绑定 | 当前表单未形成唯一候选。 |
| 模型管理 | `GET /api/v1/models/{modelId}/preview` | 模型所属数据源 | 数据源候选不唯一。 |
| 模型管理 | `POST /api/v1/models/datasource/{datasourceId}/sync` | 路径中的数据源 ID | 数据源候选不唯一。 |
| 模型管理 | `POST /api/v1/models/datasource/{datasourceId}/sync-selected` | 路径中的数据源 ID | 数据源候选不唯一。 |
| 模型同步任务 | `POST /api/v1/model-sync-tasks` | Body 中的 `datasourceId` | 数据源候选不唯一。 |

模型发现、模型同步和模型预览是多集群改造后最容易因缺少显式参数而被拒绝的接口。它们已有确定数据源，应优先纳入统一推断服务。

## 可新增业务配置推断的接口

以下保存、预览、校验或临时执行接口可以从请求中的业务绑定计算集群。编辑已有资源时，应先允许沿用资源保存值，再对本次请求中的数据源进行适用性校验。

| 所属模块 | 方法和路径 | 推断依据 | 条件和限制 |
|---|---|---|---|
| 采集任务 | `POST /api/v1/collection-tasks` | 全部来源数据源和目标数据源的适用集群交集 | 所有数据源必须存在唯一公共集群。 |
| 采集任务 | `POST /api/v1/collection-tasks/preview` | 全部来源数据源和目标数据源的适用集群交集 | 纯配置预览不调用 Worker；唯一时可补充集群校验，不唯一时也可以返回未定向的装配结果。 |
| 数据质量 | `POST /api/v1/quality-tasks` | 检查模型所属数据源 | 模型必须能定位数据源且候选唯一。 |
| 数据质量 | `POST /api/v1/quality-tasks/preview` | 检查模型所属数据源 | 只生成 SQL 预览，不执行 SQL；无法唯一推断时仍可返回 SQL 和告警。 |
| 数据质量 | `POST /api/v1/quality-tasks/validate` | 检查模型所属数据源 | 同上。 |
| 数据开发 | `POST /api/v1/data-development/scripts` | SQL 脚本的数据源，或 Flink 问数脚本所选模型的数据源交集 | 无数据源的新建 Java/Python 脚本无法推断；编辑时可沿用保存值。 |
| 数据开发 | `POST /api/v1/data-development/scripts/execute` | SQL 数据源或 Flink 模型对应数据源 | 只允许当前支持的内联 SQL 类型；候选必须唯一。 |
| 数据开发 | `POST /api/v1/data-development/sql/execute` | 请求中的 `datasourceId` | 数据源候选必须唯一。 |
| 数据服务 | `POST /api/v1/data-services` | 服务来源数据源 | 编辑时优先沿用保存值并重新校验来源。 |
| 数据服务 | `POST /api/v1/data-services/resolve-fields` | 请求中的来源数据源 | 候选必须唯一。 |
| 数据接入 | `POST /api/v1/data-ingestion-services` | 全部来源绑定数据源的适用集群交集 | 多来源必须存在唯一公共集群。 |
| 数据接入 | `POST /api/v1/data-ingestion-services/resolve-fields` | 请求中的数据源绑定 | 候选必须唯一。 |
| 协议转换 | `POST /api/v1/protocol-conversions` | 目标数据源 | 候选必须唯一。 |
| Flink 智能问数 | `POST /api/flink/sql/execute` | 所有 `modelIds` 对应数据源的适用集群交集 | `modelIds` 为空时没有可靠依据。 |
| Flink 智能问数 | `POST /api/flink/question/ask` | 请求中的 `datasourceIds`、`modelIds`，或规划结果中的模型 | 无任何限定条件时不能在规划前安全推断；若使用规划结果推断，需要同步调整请求校验顺序。 |
| 工作流 | `POST /api/v1/workflows` | 采集任务、质量任务和脚本节点已经保存的集群，以及直接数据源节点 | 所有资源节点必须属于同一集群；空工作流无法推断。 |

## 无法安全推断时的约束分类

“无法唯一推断”不等于“接口一定不能返回数据”。应先判断集群 ID 在该接口中是筛选条件、资源放置属性，还是 Worker 执行目标。

### A. 业务约束型：不是 Worker 硬依赖

#### A1. 候选筛选或只读生成：技术上可缺省，是否必填由业务契约决定

这类接口只查询 Studio 数据库中的可访问候选项，或只生成不需要执行的配置。`runtimeClusterId` 的作用是按适用集群缩小范围或提前校验，不是调用 Worker 的前置条件。缺失时技术上可以返回未按集群过滤的结果；产品仍可要求用户先选择集群，此时“必填”属于业务契约，而不是运行条件。

| 所属模块 | 方法和路径 | 当前代码依据 | 业务含义和建议 |
|---|---|---|---|
| 数据源管理 | `GET /api/v1/datasources/options` | `DataSourceService.listBasicOptions(null)` 会跳过集群过滤；但 Controller 当前用 `@RequestParam("runtimeClusterId")` 和 `assertExplicitSelection` 提前拦截。 | 集群只是数据源候选过滤条件。若业务要求“先选集群再选数据源”，可继续定义必填；否则可以改为可选并返回全部可访问启用数据源。 |
| 数据开发 | `GET /api/v1/data-development/datasources` | `listBasicOptionsByTypes(..., null)` 可以返回全部 SQL 能力数据源；Service 当前额外调用 `assertExplicitSelection`。 | 集群用于筛选 SQL 数据源，不是查询执行。当前必填属于业务限制；放开后可以返回未过滤候选。 |
| 数据开发 | `GET /api/v1/data-development/datasource-options` | 同上，底层 `filterByRuntimeCluster` 在集群为 `null` 时不筛选。 | 建议允许缺失；前端确定集群后再重新按集群刷新。 |
| 模型管理 | `GET /api/v1/models/selector-options`，未传 `datasourceId` | Service 仅在 `runtimeClusterId != null` 时按适用数据源过滤；当前 Controller 仍把 Query 声明为必填。 | 未传数据源时可以查询跨数据源模型，但结果不代表某个集群可运行。用于选择时应显示或补充集群上下文。 |
| 采集任务 | `GET /api/v1/collection-tasks/workflow-options` | Service 使用 `.eq(runtimeClusterId != null, ...)`，可无集群查询在线任务；Controller 当前声明必填。 | 集群只是工作流节点候选过滤。缺失时可以返回全部在线任务，工作流保存时再校验节点集群一致性。 |
| 数据质量 | `GET /api/v1/quality-tasks/workflow-options` | Service 同样只在集群非空时增加过滤条件；Controller 当前声明必填。 | 与采集任务相同，属于工作流绑定候选查询，不是运行调用。 |
| 工作流 | `GET /api/v1/workflows/options` | Controller 和 Service 已将 `runtimeClusterId` 设计为可选过滤项。 | 该接口已经体现正确语义，不应再强制推断或阻塞；需要筛选时显式传集群即可。 |
| 数据源管理 | `GET /api/v1/datasources/{id}/connection-history` | 历史查询是数据库读取，不调用 Worker；但当前空值查询只匹配历史记录中的空集群值。 | 它不是技术运行阻塞型接口。若要支持无集群查看，应明确改成“查询全部适用集群历史”或继续把集群作为业务筛选必填，不能只放开 Controller 参数而不修改查询语义。 |
| 采集任务 | `POST /api/v1/collection-tasks/preview` | `CollectionTaskAssemblerService.assemblePreview()` 只组装并脱敏任务配置，不调用 Worker。 | 可以在没有集群时生成未定向预览；集群仅用于提前校验数据源适用性。 |
| 数据质量 | `POST /api/v1/quality-tasks/preview` | `QualityTaskExecutionPlanService.preview()` 只生成解析后的 SQL 和告警，不执行 SQL。 | 可以无集群返回 SQL 预览；与实际执行 SQL 的 `/validate` 必须区分。 |

这类接口不应“自动猜一个集群”。可以选择继续按业务流程要求集群必填，也可以允许不带集群查询；若允许缺省，必须明确返回结果是“未按集群过滤”的候选数据。调用方一旦进入保存或运行流程，仍必须重新解析和校验集群。

#### A2. 资源放置：业务上定义为必填

这类接口不一定在保存瞬间调用 Worker，但当前资源模型要求每个可运行资源持久化一个明确的运行集群，因此缺少集群会被业务校验拒绝。

| 所属模块 | 方法和场景 | 无法推断原因 | 建议行为 |
|---|---|---|---|
| 数据开发 | `POST /api/v1/data-development/scripts` 新建无数据源的 Java/Python 脚本 | 没有数据源、模型或已有资源集群作为证据，脚本仍需要保存运行放置属性。 | 保持业务必填；编辑已有脚本时可沿用保存值。若未来支持“未放置草稿”，应允许保存空值，但发布和执行前必须补齐。 |
| 工作流 | `POST /api/v1/workflows` 新建且没有任务、脚本或数据源节点 | 空工作流没有节点集群，无法计算统一运行集群。 | 保持业务必填；有资源节点时可从节点集群推断，空工作流只能显式指定。 |

这里的拒绝属于资源配置策略，不应在错误文案中描述成“目标 Worker 不可用”。

### B. 运行阻塞型：缺少集群无法得到有效结果

这类接口需要把请求路由到特定集群的 Worker，或需要在目标数据源上实际执行探测、同步和校验。没有集群即使数据源适用集群集合已知，也不能在多个候选中安全选择。

| 所属模块 | 方法和路径 | 运行依赖 | 无法推断时的处理 |
|---|---|---|---|
| 数据开发 | `GET /api/v1/data-development/java/import-hints` | 目标集群的 Java 类路径和脚本环境 | 保持集群必填；缺失应在路由前返回 `Runtime cluster is required`。 |
| 数据开发 | `GET /api/v1/data-development/java/member-hints` | 目标集群的 Java 类定义 | 同上。 |
| 数据源管理 | `POST /api/v1/datasources/{id}/test` | 目标集群执行数据源连接探测 | 只有适用集群唯一时才可自动下推；多候选或无候选直接要求选择。 |
| 数据源管理 | `POST /api/v1/datasources/test` | 当前表单在目标集群执行连接探测 | 表单适用集群唯一时可推断，否则阻塞。 |
| 数据源管理 | `POST /api/v1/datasources/{id}/discover` | 目标集群访问数据源并发现模型 | 同上。 |
| 数据源管理 | `POST /api/v1/datasources/{id}/discover-options` | 目标集群访问数据源并发现轻量模型选项 | 同上。 |
| 模型管理 | `GET /api/v1/models/{modelId}/preview` | 目标集群读取模型数据 | 模型数据源适用集群唯一时可推断，否则阻塞。 |
| 模型管理 | `POST /api/v1/models/datasource/{datasourceId}/sync` | 目标集群执行模型同步 | 数据源适用集群唯一时可推断，否则阻塞。 |
| 模型管理 | `POST /api/v1/models/datasource/{datasourceId}/sync-selected` | 目标集群执行选定模型同步 | 同上。 |
| 模型同步任务 | `POST /api/v1/model-sync-tasks` | 创建并启动目标集群同步任务 | 数据源适用集群唯一时可推断，否则不能创建可运行任务。 |
| 数据质量 | `POST /api/v1/quality-tasks/validate` | 目标集群实际执行质量 SQL 校验 | 模型数据源适用集群唯一时可推断，否则阻塞。 |
| 数据服务 | `POST /api/v1/data-services/resolve-fields` | 无模型字段元数据时，目标集群执行字段探测 | 有完整模型元数据时可只读返回；需要实际探测时集群必填。 |
| 数据接入 | `POST /api/v1/data-ingestion-services/resolve-fields` | 目标集群读取模型字段或执行字段探测 | 有缓存元数据时可能不需要 Worker；进入探测路径时集群必填。 |
| 数据开发 | `POST /api/v1/data-development/sql/execute` | 目标集群执行 SQL | 请求数据源适用集群唯一时可推断，否则阻塞。 |
| Flink | `POST /api/flink/sql/execute` | 目标集群执行 Flink SQL | `modelIds` 对应数据源适用集群唯一时可推断；没有模型范围时不能安全选择。 |
| Flink | `POST /api/flink/question/ask` | 规划后的 SQL 最终在目标集群执行 | 有明确 `datasourceIds/modelIds` 或规划结果可得到唯一交集时可推断；否则规划或执行阶段必须阻塞。 |

运行阻塞型接口的错误应区分“缺少集群无法路由”和“集群存在但 Worker 离线”。前者是请求解析错误，后者是目标运行时不可用，不能使用同一默认集群兜底。

## 判定依据和当前差距

当前公共选择逻辑仍采用“缺失即拒绝”，但底层代码已经表现出不同语义：

- 查询类 Service 普遍使用“集群非空才增加过滤条件”，或在 `null` 时跳过数据源适用性过滤。
- Java 提示路由器在准备 Worker 请求时明确检查集群、在线实例、端点和内部认证，属于真实运行依赖。
- 保存 Service 使用 `resolveForResourceSave` 将集群作为资源放置属性，属于业务配置约束。
- 数据源连接探测、模型发现和同步通过 `RuntimeDatasourceProbeRouter` 或同步任务进入 Worker，属于运行阻塞约束。

因此，后续调整不能简单地全局删除 `runtimeClusterId` 必填：

1. 查询筛选接口应允许缺省，并在文档中说明“缺省表示不按集群过滤”。
2. 资源保存接口应只在有数据源或已有资源集群时尝试推断；没有证据时继续按业务规则要求填写。
3. Worker 执行接口应在唯一适用集群时自动下推，候选不唯一或为空时阻塞并要求选择。

代码依据：

- `DataSourceService.listBasicOptions()` 和 `filterByRuntimeCluster()`
- `DataDevelopmentService.listSqlCapableDatasources()` / `listSqlCapableDatasourceOptions()`
- `DataModelService.listSelectorOptions()`
- `CollectionTaskService.listWorkflowOptions()`
- `QualityTaskService.listWorkflowOptions()`
- `ScriptEnvironmentHintRuntimeRouter.request()`
- `RuntimeClusterSelectionService.resolveForResourceSave()` / `validateExplicitDatasourceSelection()`

## 后续实现同步范围

后续落地时至少需要同步检查：

1. `RuntimeClusterSelectionService` 的资源继承和数据源交集解析逻辑。
2. 相关请求 DTO 的 `@NotNull` 和 OpenAPI `required` 声明，使请求层支持条件必填。
3. Controller Query 参数的 `required` 声明。
4. 各业务 Service 收集的数据源 ID 是否完整，特别是多来源、目标数据源、模型所属数据源和工作流节点。
5. 前端 API SDK、页面调用、AI 助手工具定义及 `data-aggregation-skills` 脚本和说明。
6. 各模块接口文档、错误说明和示例请求。
7. 单数据源唯一、多数据源唯一交集、无交集、多候选、编辑继承、显式覆盖、未授权和目标 Worker 离线等测试。

## 代码依据

- `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/RuntimeClusterSelectionService.java`
- `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DatasourceClusterBindingService.java`
- `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DispatchService.java`
- `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataDevelopmentService.java`
- `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/WorkflowService.java`
- `backend/studio-server/src/main/java/com/jdragon/studio/server/web/service/RuntimeInvocationRouter.java`
- `backend/studio-server/src/main/java/com/jdragon/studio/server/web/service/RuntimeFlinkExecutionRouter.java`

## 接口数量口径

- 当前已经按保存资源下推集群：15 个外部接口路径模式，另有采集、质量和工作流定时调度链路。
- 当前分析表中具备新增推断条件：24 个业务接口路径；另有筛选查询和只读预览不需要通过推断才能返回数据。
- 无法唯一推断后的行为分为业务约束型和运行阻塞型；同一路径可能因具体请求是否需要 Worker 而落入不同分支，不能与 24 个接口直接相加。
