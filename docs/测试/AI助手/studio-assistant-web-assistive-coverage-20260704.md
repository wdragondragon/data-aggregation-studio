# Studio AI 助手 Web 端协助覆盖测试记录

- 测试时间：2026-07-04 23:48-23:58（北京时间）
- 测试范围：仅 Web 前端 + `studio-server` Web 后端，不使用 `studio-desktop-runtime`
- Web UI：`http://127.0.0.1:5176/dfs/data-aggregation-studio/`
- Web 后端：`http://127.0.0.1:18082`
- 数据库：MySQL `jdbc:mysql://8.140.247.113:13309/data_aggregation_studio`
- 覆盖脚本：`frontend/apps/web/scripts/assistant-ui-real-assistive-coverage.mjs`
- 机器报告：`frontend/apps/web/target/assistant-assistive-coverage/assistant-assistive-coverage-report.md`
- JSON 证据：`frontend/apps/web/target/assistant-assistive-coverage/assistant-assistive-coverage-report.json`
- 截图：`frontend/apps/web/target/assistant-assistive-coverage/assistant-ui-real-assistive-coverage.png`
- 最新报告时间戳：`2026-07-04T15:48:24.568Z`

## 结论

本轮 Web-only 覆盖通过：27 个顶层功能读取、8 个非变更动作、16 个确认型变更动作全部通过真实 Web 助手输入、后端动作网关和 MySQL 业务数据校验。

动作目录当前暴露 113 个 `studio.feature.action` 动作，其中 105 个为变更动作、8 个为非变更动作。本轮真实执行 24 个动作，剩余 89 个动作需要按模块准备专用种子业务对象或隔离项目后继续覆盖，不能直接强行执行发布、触发、调度、订阅轮换和系统管理等高风险动作。

“小助手一直超时”的直接原因在 Web 助手链路，不是 desktop runtime：planner 曾在明确 `payload 为 {...}` 时把 JSON 平铺到顶层参数，或在 payload 文本包含 `datasource-save`、`saved_model` 等词时错选相近动作，导致后端动作网关校验失败或执行了非目标动作，Web 用例等待目标结果直到超时。修复后，本轮覆盖脚本没有再出现助手等待超时。

复测还发现一个测试盲点：只检查外层 HTTP 200 / `response.success=true` 会漏掉底层业务错误。覆盖脚本已改为创建真实 MySQL 测试表、写入完整数据源连接元数据，并对动作返回业务数据做语义断言，避免 `jdbc:mysql://null:null/null` 这类错误被误判为通过。

## 修复点

1. `AssistantLlmPlanner` 增加显式 `payload 为/=: {...}` 识别：当动作目录要求 `payload` 且用户明确给出 payload JSON 时，保留为 `params.payload`，不再平铺到顶层。
2. `AssistantLlmPlanner` 增强显式路径和动作评分：优先匹配 `/{path} 的 {action} 动作` 这类明确表达，避免 `/datasources:testCurrent` 被 payload 中的 `datasource-save` 误导成 `save`，也避免 `/models:syncSelected` 被 `saved_model` 误导。
3. `AssistantStudioOperationRegistry` 修正 `/models:syncSelected` 的 required values：从 `datasourceId,payload` 调整为 `datasourceId,physicalLocators`，与后端执行入口保持一致。
4. `AssistantLlmPlannerTest` 增加 `/data-services:resolveFields`、`/data-ingestion-services:resolveFields`、`/quality-tasks:preview`、`/datasources:testCurrent`、`/models:syncSelected` 的回归断言。
5. `AssistantStudioOperationRegistryTest` 增加 `/models:syncSelected` required values 断言。
6. `assistant-ui-real-assistive-coverage.mjs` 将覆盖种子 ID 改为 `BigInt -> String`，避免 Snowflake/大整数在 JS 期望值和后端字符串 ID 之间误报。
7. 覆盖脚本为安全动作种子补齐真实 MySQL `host/port/database/userName/password/jdbcUrl/driverClassName`，并创建唯一物理表 `assistant_coverage_model_*`，用于数据源发现、模型同步和质量任务 SQL 校验。
8. 覆盖脚本新增动作结果语义断言：检查发现模型、字段列表、采集预览 reader/writer、质量任务 `resolvedSql`、`valid=true`、`total_count=1`、写动作落库状态和版本递增。
9. 覆盖报告新增动作返回摘要，并对 `password`、`token`、`secret`、`accessKey` 等敏感字段脱敏。
10. 复测时后端必须显式使用 Web MySQL datasource；仅设置覆盖脚本 DB 环境变量不等于设置 `studio-server` 数据源。错误 datasource 会导致登录 readiness 或动作结果等待超时。

## 覆盖结果

| 类型 | 数量 | 结果 |
| --- | ---: | --- |
| Web 助手输入用例 | 51 | 全部通过 |
| 顶层功能读取 | 27 | 全部通过 |
| 非变更动作 | 8 | 全部通过 |
| 变更动作 | 16 | 全部通过 |
| 动作目录审计 | 113 | 24 个已执行，89 个待专用种子数据扩展 |
| 变更动作目录 | 105 | 16 个已执行 |
| 非变更动作目录 | 8 | 8 个已执行 |
| 前端降级调用 | 0 | 未发现 |
| 报告敏感字段检查 | 0 | 未发现明文密码、`null:null` 或 SQL 连接错误 |

## 非变更动作证据

| 动作 | 接口 | 执行方 | 语义证据 |
| --- | --- | --- | --- |
| `/quality-rules:parse` | `studio.feature.action` | `backend` | `inputParams[0].paramName=Schema_Table`，`outputParams[0].resultField=total_count` |
| `/quality-rules:validate` | `studio.feature.action` | `backend` | `valid=true`，`message=SQL 语义校验通过` |
| `/datasources:discover` | `studio.feature.action` | `backend` | 发现 `assistant_coverage_model_1783180171302_84`，`models=1` |
| `/data-services:resolveFields` | `studio.feature.action` | `backend` | 返回字段 `id`, `name`, `amount` |
| `/data-ingestion-services:resolveFields` | `studio.feature.action` | `backend` | 返回字段 `id`, `name`, `amount` 和默认字段映射 |
| `/collection-tasks:preview` | `studio.feature.action` | `backend` | 返回 `reader` / `writer` 配置，表名为种子物理表 |
| `/quality-tasks:preview` | `studio.feature.action` | `backend` | `resolvedSql=select count(*) as total_count from assistant_coverage_model_1783180171302_84` |
| `/quality-tasks:validate` | `studio.feature.action` | `backend` | `valid=true`，`rows[0].total_count=1` |

## 变更动作数据库证据

| 动作 | 接口 | 执行方 | 数据库证据 |
| --- | --- | --- | --- |
| `/quality-rules:save` | `studio.feature.action` | `backend` | `quality_rule` 写入 `2073434283022102530`，`enabled=0`，输入/输出参数各 1 条 |
| `/quality-rules:enable` | `studio.feature.action` | `backend` | `quality_rule.enabled` 从 `0` 更新为 `1` |
| `/quality-rules:disable` | `studio.feature.action` | `backend` | `quality_rule.enabled` 从 `1` 更新为 `0` |
| `/datasources:save` | `studio.feature.action` | `backend` | `datasource_definition` 种子数据源更新为 `assistant_coverage_ds_saved_1783180251376_666`，`enabled=1`，`executable=1` |
| `/datasources:testCurrent` | `studio.feature.action` | `backend` | 使用已保存连接信息返回 `AVAILABLE`，报告未暴露密码 |
| `/datasources:test` | `studio.feature.action` | `backend` | `connectionStatus=AVAILABLE`，`lastConnectionTestAt=2026-07-04T23:51:04` |
| `/models:save` | `studio.feature.action` | `backend` | `data_model` 写入 `2073434469538607106`，物理名 `assistant_coverage_saved_model_1783180267181_417` |
| `/models:syncSelected` | `studio.feature.action` | `backend` | 对种子物理表执行同步，`modelCount=1` |
| `/models:rebuildIndex` | `studio.feature.action` | `backend` | `data_model_search_index` 返回正向可访问模型数量 |
| `/field-mapping-rules:save` | `studio.feature.action` | `backend` | `field_mapping_rule` 写入 `2073434535519203330`，参数行数 `1` |
| `/script-environments:save` | `studio.feature.action` | `backend` | `so_pf_script_env` 写入 `2073434570969460737`，`enabled=0`，`environmentVersion=1` |
| `/script-environments:enable` | `studio.feature.action` | `backend` | `enabled=1`，`environmentVersion=2` |
| `/script-environments:disable` | `studio.feature.action` | `backend` | `enabled=0`，`environmentVersion=3` |
| `/script-environments:refresh` | `studio.feature.action` | `backend` | `environmentVersion` 从 `3` 递增为 `4` |
| `/notifications:markRead` | `studio.feature.action` | `backend` | 插入 1 条未读通知后，执行后未读数 `0`，`read_at=2026-07-04T23:52:14` |
| `/notifications:markAllRead` | `studio.feature.action` | `backend` | 插入 1 条未读通知后，执行后未读数 `0`，`read_at=2026-07-04T23:52:33` |

本轮安全动作种子数据：

| 对象 | 值 |
| --- | --- |
| `projectId` | `2047489207831650317` |
| `datasourceId` | `1783180171302011` |
| `modelId` | `1783180171302012` |
| `qualityRuleId` | `1783180171302013` |
| `physicalLocator` | `assistant_coverage_model_1783180171302_84` |
| 物理表行数 | 1 |
| 数据源 | `8.140.247.113:13309/data_aggregation_studio` |
| 字段 | `id`, `name`, `amount` |

## 关键问答用例

| 用例 | 用户问题 | 期望助手链路 | 结果 |
| --- | --- | --- | --- |
| Dashboard 读取 | `请读取 Project dashboard /dashboard 的列表或概览，必须通过 Studio 助手受控读取接口返回。` | `assistant.fast-path` -> `studio.feature.list` -> `/dashboard` | 通过 |
| 数据源发现 | `请通过 Studio 助手受控动作接口执行 /datasources 的 discover 动作，参数为 {...}。必须走后端动作网关。` | `assistant.fast-path` -> `studio.feature.action` -> `/datasources:discover`，发现种子物理表 | 通过 |
| Data service 字段解析 | `请通过 Studio 助手受控动作接口执行 /data-services 的 resolveFields 动作，payload 为 {...}。必须走后端动作网关。` | `assistant.fast-path` -> `studio.feature.action` -> `/data-services:resolveFields`，`payload` 嵌套传入 | 通过 |
| Data ingestion 字段解析 | `请通过 Studio 助手受控动作接口执行 /data-ingestion-services 的 resolveFields 动作，payload 为 {...}。必须走后端动作网关。` | `assistant.fast-path` -> `studio.feature.action` -> `/data-ingestion-services:resolveFields`，`payload` 嵌套传入 | 通过 |
| 数据源保存 | `请通过 Studio 助手受控动作接口执行 /datasources 的 save 动作，payload 为 {...}。必须先确认再走后端动作网关。` | 确认控件 -> `studio.feature.action` -> `datasource_definition` 更新 | 通过 |
| 当前数据源测试 | `请通过 Studio 助手受控动作接口执行 /datasources 的 testCurrent 动作，payload 为 {...}。必须先确认再走后端动作网关。` | 确认控件 -> `studio.feature.action` -> 返回连接可用 | 通过 |
| 已保存数据源测试 | `请通过 Studio 助手受控动作接口执行 /datasources 的 test 动作，参数为 {...}。必须先确认再走后端动作网关。` | 确认控件 -> `studio.feature.action` -> `connectionStatus=AVAILABLE` | 通过 |
| 模型保存 | `请通过 Studio 助手受控动作接口执行 /models 的 save 动作，payload 为 {...}。必须先确认再走后端动作网关。` | 确认控件 -> `studio.feature.action` -> `data_model` 落库 | 通过 |
| 指定模型同步 | `请通过 Studio 助手受控动作接口执行 /models 的 syncSelected 动作，参数为 {...}。必须先确认再走后端动作网关。` | 确认控件 -> `studio.feature.action` -> 按 `physicalLocators` 同步 | 通过 |
| 字段映射规则保存 | `请通过 Studio 助手受控动作接口执行 /field-mapping-rules 的 save 动作，payload 为 {...}。必须先确认再走后端动作网关。` | 确认控件 -> `studio.feature.action` -> 规则主表和参数表落库 | 通过 |
| 脚本环境启停刷新 | `请通过 Studio 助手受控动作接口执行 /script-environments 的 enable/disable/refresh 动作...` | 确认控件 -> `studio.feature.action` -> 状态更新和版本递增 | 通过 |
| 质量规则保存 | `请通过 Studio 助手受控动作接口执行 /quality-rules 的 save 动作，payload 为 {...}。必须先确认再走后端动作网关。` | 确认控件 -> `studio.feature.action` -> `quality_rule` 主表和参数子表落库 | 通过 |
| 通知全部已读 | `请把所有通知全部标记为已读，必须通过 Studio 助手确认后执行。` | 确认控件 -> `studio.feature.action` -> MySQL `read_at` 更新 | 通过 |

## 执行命令

```powershell
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

Push-Location backend
mvn -pl studio-infra,studio-test -am "-Dtest=AssistantLlmPlannerTest,AssistantStudioOperationRegistryTest,AssistantStudioToolExecutionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl studio-server -am -DskipTests package
Pop-Location

$env:SPRING_DATASOURCE_URL='jdbc:mysql://8.140.247.113:13309/data_aggregation_studio?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true'
$env:SPRING_DATASOURCE_USERNAME='root'
$env:SPRING_DATASOURCE_PASSWORD='<redacted>'
java -Dserver.port=18082 `
  -Dspring.cloud.nacos.discovery.enabled=false `
  -Dspring.cloud.nacos.config.enabled=false `
  -Dstudio.worker.lifecycle.enabled=false `
  -Daggregation.home=C:\dev\ideaProject\cloud-parent-springboot3\DataAggregation\package_all\aggregation `
  -jar C:\dev\ideaProject\cloud-parent-springboot3\DataAggregation\data-aggregation-studio\backend\studio-server\target\studio-server-0.1.0-SNAPSHOT-exec.jar

Push-Location frontend
$env:STUDIO_ASSISTANT_BACKEND_BASE_URL='http://127.0.0.1:18082'
$env:STUDIO_ASSISTANT_DB_URL='jdbc:mysql://8.140.247.113:13309/data_aggregation_studio?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true'
$env:STUDIO_ASSISTANT_DB_USERNAME='root'
$env:STUDIO_ASSISTANT_DB_PASSWORD='<redacted>'
$env:STUDIO_ASSISTANT_CASE_TIMEOUT_MS='45000'
npm run test:assistant-assistive-coverage -w @studio/web
Pop-Location
```

本轮已通过的验证：

| 验证 | 结果 |
| --- | --- |
| `node --check frontend/apps/web/scripts/assistant-ui-real-assistive-coverage.mjs` | 通过 |
| `mvn -pl studio-infra,studio-test -am "-Dtest=AssistantLlmPlannerTest,AssistantStudioOperationRegistryTest,AssistantStudioToolExecutionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | 通过，`AssistantLlmPlannerTest` 16 个、`AssistantStudioOperationRegistryTest` 11 个、`AssistantStudioToolExecutionServiceTest` 61 个 |
| `mvn -pl studio-server -am -DskipTests package` | 通过 |
| `npm run test:assistant-assistive-coverage -w @studio/web` | 通过，27 读、8 非变更动作、16 变更动作 |

## 动作目录缺口审计

- 动作目录总数：113
- 变更动作：105
- 非变更动作：8
- 已通过真实助手输入执行：24
- 未执行动作：89
- `*` 标记为变更动作，必须经过确认控件并准备可回滚或可隔离的业务数据。

| 路径 | 未执行动作 |
| --- | --- |
| `/access-center` | `apply*` |
| `/metadata` | `saveDraft*`, `publish*`, `syncTechnical*`, `syncAllTechnical*`, `syncStandardRuntimeOptions*` |
| `/models` | `sync*` |
| `/collection-tasks` | `save*`, `publish*`, `trigger*`, `schedule*`, `resetIncrementalCursor*` |
| `/data-development` | `directories:saveDirectory*`, `directories:moveDirectory*`, `scripts:saveScript*`, `scripts:moveScript*`, `sql:executeSql*`, `scripts:executeScript*`, `scripts:executeSavedScript*` |
| `/workflows` | `save*`, `publish*`, `trigger*`, `schedule*` |
| `/data-services` | `save*`, `publish*`, `offline*`, `debug*`, `webservice:debugWebService*`, `subscriptions:createSubscription*`, `subscriptions:enableSubscription*`, `subscriptions:disableSubscription*`, `subscriptions:rotateSubscription*` |
| `/data-ingestion-services` | `save*`, `publish*`, `offline*`, `debug*`, `webservice:debugWebService*`, `subscriptions:createSubscription*`, `subscriptions:enableSubscription*`, `subscriptions:disableSubscription*`, `subscriptions:rotateSubscription*` |
| `/protocol-conversions` | `save*`, `publish*`, `offline*`, `debug*`, `subscriptions:createSubscription*`, `subscriptions:enableSubscription*`, `subscriptions:disableSubscription*`, `subscriptions:rotateSubscription*` |
| `/quality-tasks` | `save*`, `publish*`, `trigger*`, `schedule*` |
| `/quality-metrics` | `issues:assignIssue*`, `issues:updateIssueStatus*`, `issues:updateIssueSeverity*`, `issues:addIssueComment*` |
| `/system` | `users:saveUser*`, `users:deleteUser*`, `userRegistrationRequests:approve*`, `userRegistrationRequests:reject*`, `userRegistrationRequests:deleteRegistrationRequest*`, `registrationRequests:approve*`, `registrationRequests:reject*`, `registrationRequests:deleteRegistrationRequest*`, `tenants:saveTenant*`, `tenants:deleteTenant*`, `projects:saveProject*`, `projects:deleteProject*`, `tenantMembers:saveTenantMember*`, `tenantMembers:deleteTenantMember*`, `projectMembers:saveProjectMember*`, `projectMembers:deleteProjectMember*`, `projectMemberRequests:saveProjectMemberRequest*`, `projectMemberRequests:approve*`, `projectMemberRequests:reject*`, `projectMemberRequests:deleteProjectMemberRequest*`, `requests:saveProjectMemberRequest*`, `requests:approve*`, `requests:reject*`, `requests:deleteProjectMemberRequest*`, `projectWorkers:saveProjectWorker*`, `projectWorkers:deleteProjectWorker*`, `workers:saveProjectWorker*`, `workers:deleteProjectWorker*`, `resourceShares:saveResourceShare*`, `resourceShares:deleteResourceShare*`, `shares:saveResourceShare*`, `shares:deleteResourceShare*` |

