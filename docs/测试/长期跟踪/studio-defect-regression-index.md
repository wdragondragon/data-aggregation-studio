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
