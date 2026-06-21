# Studio 缺陷回归索引

## 使用规则

- 本文件维护长期测试中已经确认并修复的真实缺陷。
- 每次新增缺陷修复后，必须补充本索引。
- 高风险缺陷必须纳入 `smoke` 或 `module-regression`。
- 当前批量提交号：`b59e8d5`，提交信息 `test(studio): 完成长期全模块细测与修复`。

## 固定回归索引

| 编号 | 模块 | 级别 | 问题摘要 | 修复文件 | 回归入口 | 层级 | 最近通过 | 提交号 |
|---|---|---|---|---|---|---|---|---|
| BUG-M03-001 | 数据开发 | 中 | 切换到已保存 Java/Python 脚本时内容被默认模板覆盖 | `frontend/apps/web/src/views/DataDevelopmentView.vue` | `DataDevelopmentApiRegressionTest`；浏览器加载 Java/Python 脚本确认保存内容 | module-regression | 2026-06-20 | `b59e8d5` |
| BUG-M04-001 | 数据服务 | 中 | 列表页点击调试未直接进入接口调试步骤 | `frontend/apps/web/src/views/DataServiceEditorView.vue` | 浏览器访问 `/data-services/{id}/edit?debug=1`，确认进入接口调试步骤 | module-regression | 2026-06-20 | `b59e8d5` |
| BUG-M04-002 | 数据服务 | 高 | 免 Token WebService 样例仍生成假 token，按样例调用失败 | `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/WebServiceSupport.java` | `WebServiceSupportTest`；开放 SOAP 调用无 Fault | smoke | 2026-06-20 | `b59e8d5` |
| BUG-M05-001 | 数据接入 | 高 | MySQL writer 默认 insert 模式实际表现为 upsert | `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataIngestionExecutionSupport.java`；DataAggregation writer 相关代码 | 接入服务重复主键写入应失败，不应 upsert；`studio_s02_data_correctness_probe.py` | smoke | 2026-06-21 | `b59e8d5` |
| BUG-M05-002 | 数据接入 | 高 | 数据接入写入失败时开放 API 暴露 Java 堆栈和内部路径 | `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataIngestionExecutionSupport.java`；`GlobalExceptionHandler` | `DataIngestionInvocationLogSupportTest`；开放 API 错误提示不含内部路径/堆栈；`studio_s02_data_correctness_probe.py` | smoke | 2026-06-21 | `b59e8d5` |
| BUG-M06-001 | 数据采集 | 高 | 增量 DATETIME 游标二次运行类型比较失败 | `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskAssemblerService.java`；`CollectionTaskFieldMappingResolver.java` | `CollectionTaskAssemblerServiceRegressionTest`；增量采集二次运行成功；`studio_s02_data_correctness_probe.py` | smoke | 2026-06-21 | `b59e8d5` |
| BUG-M06-002 | 数据采集 | 高 | 融合任务未把 join key 纳入每个源查询字段，导致融合失败 | `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskAssemblerService.java` | `CollectionTaskAssemblerServiceRegressionTest`；融合目标表保持 6 行基线；`studio_s02_data_correctness_probe.py` | smoke | 2026-06-21 | `b59e8d5` |
| FIX-M08-001 | 运维中心 | 中 | 工作流节点筛选结果被不相关服务/接入历史异常拉成告警 | `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/OpsCenterService.java` | `OpsCenterServiceRegressionTest`；UI 筛选 `工作流节点 + 成功 + Worker` 显示健康 | module-regression | 2026-06-20 | `b59e8d5` |
| M09-FIX-01 | 系统管理/权限 | 高 | 项目普通成员可读写角色/权限接口 | `RoleController.java`；`PermissionController.java` | `StudioInitializationApiRegressionTest`；普通成员访问 `/roles`、`/permissions` 返回 403；`studio_s01_permission_probe.py` | smoke | 2026-06-21 | `b59e8d5` |
| M09-FIX-02 | 系统管理/注册 | 中 | 注册页/API 允许对已存在用户名提交注册申请 | `UserRegistrationRequestService.java` | `StudioInitializationApiRegressionTest`；重复 `admin` 注册返回 400 | module-regression | 2026-06-20 | `b59e8d5` |
| M09-FIX-03 | 认证 | 中 | 禁用用户登录返回 HTTP 500 | `GlobalExceptionHandler.java` | `StudioInitializationApiRegressionTest`；禁用用户登录返回 401；`studio_s01_permission_probe.py` | smoke | 2026-06-21 | `b59e8d5` |
| M09-FIX-04 | 前端权限 | 中 | `/system` 直接路由缺少角色 meta | `frontend/apps/web/src/router/index.ts`；`frontend/apps/web/src/stores/auth.ts` | `npm run build:web`；admin 浏览器访问 `/system` 正常，低权限应被路由守卫拦截；S01 浏览器路由验证 | module-regression | 2026-06-21 | `b59e8d5` |
| M10-FIX-01 | 导入导出 | 高 | 接收项目导出包包含源项目共享工作流，存在跨项目泄漏风险 | `WorkflowService.java`；`ImportExportController.java` | `StudioInitializationApiRegressionTest#projectExportShouldNotIncludeSharedWorkflowsFromOtherProjects`；接收项目 `/exports/project` 返回 `workflows=[]`；`studio_s01_permission_probe.py` | smoke | 2026-06-21 | `b59e8d5` |
| BUG-S03-001 | 数据源/故障恢复 | 中 | 数据源连接测试失败和连接历史暴露 Java 异常类前缀 | `AggregationSourceCapabilityProvider.java`；`DatasourceConnectionHealthService.java` | `AggregationSourceCapabilityProviderRegressionTest`；`DatasourceConnectionHealthServiceTest`；`studio_s03_fault_recovery_probe.py` 的 `S03-MYSQL-001`；浏览器数据源页无内部异常标记 | smoke | 2026-06-21 | 本次提交 |
| BUG-S03-002 | 运维中心 | 中 | 运维中心历史运行异常暴露 Java 异常类前缀 | `OpsCenterService.java` | `OpsCenterServiceRegressionTest`；`studio_s03_fault_recovery_probe.py` 的 `S03-OPS-001`；浏览器运维中心无内部异常标记 | smoke | 2026-06-21 | 本次提交 |
| BUG-S04-001 | 系统管理/资源共享 | 中 | 协议转换服务已有共享资源类型常量和接收项目访问能力，但系统资源共享后端和前端未接入该类型 | `SystemResourceShareSupport.java`；`SystemManagementService.java`；`SystemView.vue`；`systemViewSupport.ts` | `ResourceShareServiceTypeRegressionTest#shouldShareProtocolConversionService`；`npm run build:web`；`/system` 资源共享弹窗；接收项目 `/protocol-conversions` 可见共享服务 | module-regression | 2026-06-21 | 本次提交 |
| BUG-S05-001 | 协议转换/订阅 | 中 | 协议转换订阅停用后可创建同名新订阅，但重新启用旧订阅时未检查同名启用订阅，导致同一服务出现多个同名启用订阅 | `ProtocolConversionService.java` | `SubscriptionTokenRotationRegressionTest#shouldRejectProtocolConversionEnableWhenSameNameAlreadyEnabled`；协议转换订阅 API 重复启用探针；浏览器 `/protocol-conversions` 订阅弹窗 | module-regression | 2026-06-21 | 本次提交 |
| BUG-S06-001 | 数据服务/数据接入/协议转换订阅 | 高 | 三类服务订阅同名并发创建只有应用层查重，缺少启用状态持久层唯一约束，可形成多条同名启用订阅 | `DataServiceService.java`；`DataIngestionService.java`；`ProtocolConversionService.java`；`StudioSchemaUpgradeService.java`；`schema-mysql.sql`；`schema-sqlite.sql` | `studio_s06_subscription_race_probe.py`；`SubscriptionTokenRotationRegressionTest`；`StudioSchemaDriftRegressionTest`；浏览器 `/protocol-conversions` 订阅弹窗 | smoke | 2026-06-21 | 本次提交 |
| BUG-S07-001 | 数据服务/数据接入/协议转换订阅 | 高 | 开放调用刷新订阅 `lastUsedAt` 使用整行 `updateById`，并发轮换/禁用时可能写回旧 token 或启用状态 | `DataServiceService.java`；`DataIngestionService.java`；`ProtocolConversionService.java` | `studio_s07_subscription_token_lifecycle_probe.py`；`SubscriptionTokenRotationRegressionTest#shouldRefresh*LastUsedAtWithoutOverwritingTokenState` | smoke | 2026-06-21 | 本次提交 |
| BUG-S08-001 | 调度/手动触发并发 | 高 | 采集任务、质量任务、工作流手动触发缺少同实例并发互斥，已有集群锁允许同一实例重入，可产生重复调度和重复运行记录 | `DispatchService.java`；`ClusterLockService.java` | `studio_s08_manual_trigger_race_probe.py`；`DispatchServiceOverlapRegressionTest`；`ClusterLockServiceRegressionTest`；浏览器 `/runs` 工作流日志页 | smoke | 2026-06-21 | 本次提交 |
| BUG-S09-001 | 运行记录/结构变更错误消息 | 中 | 采集任务结构变更失败运行记录暴露 Java 异常类、内部类名、源码文件名和堆栈，且修复前历史记录在列表/API 中继续暴露 | `RunRecordMessageSanitizer.java`；`ExecutionEventService.java`；`RunService.java`；`WorkflowRunService.java` | `studio_s09_schema_drift_probe.py`；`ExecutionEventServiceRegressionTest`；`RunServiceRegressionTest`；浏览器 `/collection-task-runs` 两个 S09 失败任务；API `/runs/{historicalRunId}` | smoke | 2026-06-21 | 本次提交 |
| BUG-S10-001 | 运行记录/质量任务错误消息 | 中 | 质量任务字段改名失败时 `message` 已清洗，但 `payloadJson.stackTrace` 与 `resultJson.stackTrace` 继续暴露 Java 堆栈、内部类名和源码行号 | `RunRecordMessageSanitizer.java`；`ExecutionEventServiceRegressionTest.java`；`RunServiceRegressionTest.java` | `studio_s10_schema_drift_service_quality_probe.py`；`ExecutionEventServiceRegressionTest`；`RunServiceRegressionTest`；浏览器 `/quality-task-runs?qualityTaskId=2068704774037667842` | smoke | 2026-06-21 | 本次提交 |
| BUG-S12-001 | 数据接入/慢写一致性 | 高 | 500 行批量接入慢写超过 10 秒时 API 返回 500 超时，但底层 writer 继续提交并最终写入成功，造成响应结果与数据库副作用不一致 | `DataIngestionExecutionSupport.java`；`DataIngestionInvocationLogSupportTest.java` | `studio_s12_capacity_boundary_probe.py`；`DataIngestionInvocationLogSupportTest#shouldWaitForSlowSuccessfulWriteBeyondThreshold`；浏览器 `/data-ingestion-services/2068717572302008321/edit?debug=1` 和接入指标/日志页 | smoke | 2026-06-21 | 本次提交 |
| BUG-S13-001 | 开放调用日志/敏感信息脱敏 | 高 | 开放调用完整日志只覆盖固定敏感字段名，`secretToken`、`clientSecret`、`api_key` 等字段名变体会在日志查看和下载中泄露原文 | `OpenServiceInvocationLogSupport.java`；`DataIngestionInvocationLogSupportTest.java` | `studio_s13_log_download_security_probe.py`；`DataIngestionInvocationLogSupportTest#shouldMaskSensitiveInvocationLogFieldNameVariants`；浏览器 `/data-service-metrics/access-logs`、`/data-ingestion-metrics/access-logs` 和 `/quality-task-runs` | smoke | 2026-06-22 | 本次提交 |
| BUG-S14-001 | 协议转换/Trace 脱敏 | 高 | 协议转换 debug/Trace 对 Header 和 Body 做了脱敏，但 `sourceRequest.query`、`sourceRequest.form` 直接返回原始 `api_key`、`clientSecret` 等敏感字段值 | `ProtocolConversionService.java`；`WebServiceSupportTest.java` | `studio_s14_protocol_conversion_trace_security_probe.py`；`WebServiceSupportTest#shouldMaskSensitiveProtocolConversionTraceDiagnostics`；浏览器 `/protocol-conversions/access-logs?serviceId=2068145027555180546` 详情 Trace 和完整日志 | smoke | 2026-06-22 | 本次提交 |
| BUG-S15-001 | SOAP/开放调用日志脱敏 | 高 | 开放调用日志统一脱敏工具覆盖 JSON 字段和 `key=value`，但未覆盖 XML/SOAP 敏感元素与属性，SOAP 协议转换完整日志和 Trace 可泄露订阅 token、`clientSecret`、`password`、`apiKey` 原文 | `OpenServiceInvocationLogSupport.java`；`DataIngestionInvocationLogSupportTest.java` | `studio_s15_soap_trace_security_probe.py`；`DataIngestionInvocationLogSupportTest#shouldMaskSensitiveSoapElementAndAttributeValues`；`WebServiceSupportTest`；浏览器 `/protocol-conversions/access-logs?serviceId=2068748392102285314` 详情和完整日志 | smoke | 2026-06-22 | 本次提交 |
| BUG-S16-001 | 元模型/ACL | 高 | 项目普通成员可直接调用全局元模型写接口，创建、发布、同步、删除业务/技术/运行参数元模型；前端 `/metadata` 同时暴露写按钮 | `MetaSchemaController.java`；`MetadataSchemasView.vue`；`StudioInitializationApiRegressionTest.java` | `studio_s16_acl_deep_probe.py`；`StudioInitializationApiRegressionTest#metadataSchemaWriteApisShouldRejectProjectMember`；`studio_s01_permission_probe.py`；浏览器 `/metadata` 管理员/普通成员按钮权限 | smoke | 2026-06-22 | 本次提交 |

## 历史缺陷参考

| 编号 | 模块 | 问题摘要 | 当前状态 | 记录 |
|---|---|---|---|---|
| BUG-001 | 认证 | 点击退出登录未退出，仍停留 dashboard | 已修复 | `records/20260620-首次长期跟踪记录.md` |
| BUG-002 | 系统管理 | 删除项目成员后审批同一用户邀请返回 500，唯一键冲突 | 已修复 | `records/20260620-首次长期跟踪记录.md` |
| BUG-M02-001 | 数据资产 | 同步失败任务没有暴露数据库根因 | 已修复 | `records/20260620-M02数据资产目录与模型执行记录.md` |
| BUG-M02-002 | 数据资产 | 同步失败项耗时不等于实际开始/结束时间 | 已修复 | `records/20260620-M02数据资产目录与模型执行记录.md` |

## 待专项重点复核

| 专项 | 必含缺陷 |
|---|---|
| S01 权限隔离与多项目数据泄漏 | M09-FIX-01、M09-FIX-04、M10-FIX-01 |
| S02 数据正确性与幂等性 | BUG-M05-001、BUG-M06-001、BUG-M06-002 |
| S03 故障注入与恢复 | BUG-M05-002、FIX-M08-001、M09-FIX-03、BUG-S03-001、BUG-S03-002 |
| S04 资源共享 ACL 与前后端一致性 | BUG-S04-001 |
| S05 协议转换订阅状态一致性 | BUG-S05-001 |
| S06 订阅并发创建竞态 | BUG-S06-001 |
| S07 订阅 Token 生命周期并发一致性 | BUG-S07-001 |
| S08 手动触发并发去重与运行记录一致性 | BUG-S08-001 |
| S09 数据结构变更扰动 | BUG-S09-001 |
| S10 结构扰动扩展服务接入质量 | BUG-S10-001 |
| S12 容量与边界慢写一致性 | BUG-S12-001 |
| S13 日志下载分页并发与脱敏 | BUG-S13-001 |
| S14 协议转换 Trace/日志脱敏与日志隔离 | BUG-S14-001 |
| S15 SOAP 协议转换 Trace/日志脱敏 | BUG-S15-001 |
| S16 全量 ACL 深挖 | BUG-S16-001 |
