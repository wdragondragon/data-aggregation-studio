# Studio 模块细测长期主计划

## 计划背景

- 编制日期：2026-06-20
- 测试入口：`http://127.0.0.1:8000/dfs/data-aggregation-studio/dashboard`
- 默认账号：`admin / admin123`
- 长期测试项目：`长期回归测试项目`
- 长期 MySQL 库：`studio_longterm_regression`
- 长期数据策略：保留测试数据，不做清理；每轮追加记录，便于追溯、重测和迭代回归。

本计划基于 2026-06-19 全覆盖前端测试、2026-06-20 二次验证修复结果、当前前端路由表和后端 Controller 边界编制。后续每个模块细测都以本文为主索引，再在 `records/` 下追加执行记录。

## 本轮代码与页面校验

### 前端路由来源

- 路由文件：`frontend/apps/web/src/router/index.ts`
- 页面目录：`frontend/apps/web/src/views`
- 主菜单组：工作台、数据资产、数据采集、数据开发、数据服务、数据质量、系统管理、运维中心
- 公开路由：`/login`、`/register`
- 支持路由：`/notifications`、各详情页、各 `/new` 与 `/:id/edit` 编辑页

### 后端接口来源

- Controller 目录：`backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller`
- 关键接口域：
  - 认证与访问：`AuthController`、`WorkspaceAccessController`
  - 目录与资产：`CatalogController`、`MetaSchemaController`、`DataSourceController`、`ModelController`、`ModelSyncTaskController`、`StatisticsController`
  - 数据开发：`DataDevelopmentController`
  - 数据采集：`FieldMappingRuleController`、`CollectionTaskController`、`RunController`、`RunMetricsController`
  - 数据服务：`DataServiceController`、`DataServiceMetricsController`、`OpenDataServiceController`、`OpenWebServiceDataServiceController`
  - 数据接入：`DataIngestionServiceController`、`DataIngestionMetricsController`、`OpenDataIngestionServiceController`、`OpenWebServiceDataIngestionController`、`HttpWriterSinkController`
  - 协议转换：`ProtocolConversionServiceController`、`ProtocolConversionMetricsController`、`OpenProtocolConversionController`、`OpenWebServiceProtocolConversionController`
  - 质量：`QualityRuleController`、`QualityTaskController`、`QualityMetricsController`
  - 工作流与运维：`WorkflowController`、`WorkflowRunController`、`ScheduleController`、`OpsCenterController`、`InvocationLogController`
  - 通知、关注、导入导出：`NotificationController`、`FollowController`、`ImportExportController`
  - 系统管理：`SystemManagementController`、`UserController`、`RoleController`、`PermissionController`

### 页面抽样结果

2026-06-20 使用浏览器登录 `admin/admin123` 后，对主菜单路由与主要 `/new` 编辑页做了快速可达性校验。以下页面均能渲染主内容，且未被重定向到登录页：

| 类型 | 路由 |
|---|---|
| 主菜单 | `/dashboard`、`/catalog`、`/metadata`、`/datasources`、`/models`、`/statistics` |
| 主菜单 | `/field-mapping-rules`、`/collection-tasks`、`/collection-task-runs`、`/run-metrics` |
| 主菜单 | `/data-development`、`/workflows`、`/runs` |
| 主菜单 | `/data-services`、`/data-ingestion-services`、`/protocol-conversions`、`/data-ingestion-metrics`、`/data-service-metrics` |
| 主菜单 | `/quality-rules`、`/quality-tasks`、`/quality-task-runs`、`/quality-metrics` |
| 主菜单 | `/ops-center`、`/notifications`、`/system` |
| 编辑入口 | `/data-services/new`、`/data-ingestion-services/new`、`/protocol-conversions/new`、`/field-mapping-rules/new`、`/collection-tasks/new`、`/quality-rules/new`、`/quality-tasks/new`、`/workflows/new` |

控制台观察到 Element Plus 废弃 API warning：

- `type.text is about to be deprecated in version 3.0.0`
- `el-radio label act as value is about to be deprecated in version 3.0.0`

该类 warning 暂定为可暂缓风险；若后续升级 Element Plus 或 warning 影响测试稳定性，再提升为修复项。

## 长期基础数据

### MySQL 库与表

长期库：`studio_longterm_regression`

后续模块细测优先复用以下业务表；若不存在，在模块首轮测试时创建并保留：

| 表名 | 业务含义 | 用途 |
|---|---|---|
| `lt_reg_customer_profile` | 客户经营画像 | 数据源发现、模型同步、数据服务查询、质量规则 |
| `lt_reg_customer_order` | 客户订单明细 | SQL 服务、采集源、统计分析 |
| `lt_reg_order_payment` | 订单支付流水 | 双表融合采集、工作流多节点 |
| `lt_reg_customer_tag_result` | 客户标签结果表 | 采集写入、接入服务写入 |
| `lt_reg_quality_exception_sample` | 质量异常样例 | 完整性、唯一性、行数和异常问题生成 |
| `lt_reg_api_ingestion_result` | 开放接入落地表 | 数据接入服务调试与开放 API 调用 |
| `lt_reg_protocol_forward_log` | 协议转换转发日志 | 协议转换目标与调用证据 |

### Studio 对象命名

| 类型 | 展示名示例 | 技术编码示例 |
|---|---|---|
| 数据源 | `长期回归-客户经营画像数据源` | `lt_reg_customer_profile_ds` |
| 元模型 | `长期回归-客户资产元模型` | `lt_reg_customer_asset_schema` |
| 模型 | `长期回归-客户画像模型` | `lt_reg_customer_profile_model` |
| 脚本目录 | `长期回归-经营分析脚本目录` | `lt_reg_analysis_scripts` |
| 数据服务 | `长期回归-客户订单查询服务` | `lt_reg_customer_order_query_api` |
| 接入服务 | `长期回归-客户标签接入服务` | `lt_reg_customer_tag_ingestion_api` |
| 协议转换 | `长期回归-订单状态协议转换` | `lt_reg_order_status_protocol_api` |
| 采集任务 | `长期回归-客户订单全量采集任务` | `lt_reg_customer_order_full_collect` |
| 质量规则 | `长期回归-客户手机号完整性规则` | `lt_reg_customer_mobile_completeness_rule` |
| 工作流 | `长期回归-客户经营数据日处理流程` | `lt_reg_customer_daily_workflow` |

## 模块划分与细测计划

### M00 长期环境与基础夹具

状态：已完成，执行记录见 `records/20260620-M00长期环境与基础夹具执行记录.md`。

覆盖目标：

- 创建或确认 `studio_longterm_regression`。
- 创建或确认 `长期回归测试项目`。
- 创建或确认长期 MySQL 数据源及基础业务表。
- 确认 Worker 授权状态，记录是否仍阻断运行链路。

页面入口：

- `/system`
- `/datasources`
- `/ops-center`

接口边界：

- `/api/v1/system/projects`
- `/api/v1/system/project-workers`
- `/api/v1/datasources`
- `/api/v1/datasources/test`
- `/api/v1/datasources/{id}/discover`

重点用例：

| 编号 | 用例 |
|---|---|
| M00-01 | 用 UI 确认长期项目存在，不存在则创建并保留 |
| M00-02 | 用 MySQL 创建长期库和业务表，重复执行应幂等 |
| M00-03 | 在数据源中心创建长期 MySQL 数据源，测试连接并保存 |
| M00-04 | 数据源发现表，确认长期业务表能被识别 |
| M00-05 | 运维中心确认 Worker 在线与项目授权状态 |

阻断条件：

- MySQL 不可连接。
- 无权限创建项目或数据源。
- Worker 缺失只阻断运行链路，不阻断资产、服务配置和质量规则配置。

### M01 认证、工作台与访问中心

状态：已完成，执行记录见 `records/20260620-M01认证工作台访问中心执行记录.md`。

页面入口：

- `/login`
- `/register`
- `/dashboard`
- `/access-center`
- `/notifications`

接口边界：

- `/api/v1/auth/login`
- `/api/v1/auth/me`
- `/api/v1/auth/gateway/exchange`
- `/api/v1/auth/register-requests`
- `/api/v1/access/overview`
- `/api/v1/access/project-requests`
- `/api/v1/notifications`

重点用例：

| 编号 | 用例 |
|---|---|
| M01-01 | 正确账号登录进入工作台，展示当前租户/项目/用户 |
| M01-02 | 错误密码、空用户名、空密码校验 |
| M01-03 | 退出登录后访问受保护路由重定向登录 |
| M01-04 | 直接访问不存在路由回 dashboard |
| M01-05 | 中英文切换、菜单收起展开、站内信入口 |
| M01-06 | 无项目上下文时进入访问中心，项目申请/取消申请 |
| M01-07 | 注册登记提交、审核入口展示和重复账号提示 |

隐藏风险探针：

- token 清除不彻底。
- 登录态和项目上下文不一致。
- 404 兜底把无权限路由伪装成可达页面。

### M02 数据资产、目录与模型

状态：已完成，执行记录见 `records/20260620-M02数据资产目录与模型执行记录.md`。

页面入口：

- `/catalog`
- `/metadata`
- `/datasources`
- `/models`
- `/models/:modelId`
- `/models/sync-tasks/:taskId`
- `/statistics`

接口边界：

- `/api/v1/catalog/*`
- `/api/v1/meta-schemas/*`
- `/api/v1/datasources/*`
- `/api/v1/models/*`
- `/api/v1/model-sync-tasks/*`
- `/api/v1/statistics/*`
- `/api/v1/follows/*`

重点用例：

| 编号 | 用例 |
|---|---|
| M02-01 | 数据源表单必填、无效连接、有效连接、保存后测试连接 |
| M02-02 | 连接历史记录展示，失败原因可读 |
| M02-03 | 发现长期库表，表字段、主键、类型映射正确 |
| M02-04 | 模型同步任务创建、详情、成功/失败状态 |
| M02-05 | 模型列表筛选、详情、样例数据预览 |
| M02-06 | 元模型增删改查、字段配置和动态表单关联 |
| M02-07 | 统计分析筛选、趋势/分布/排行图表展示 |
| M02-08 | 目录资产关注、取消关注、血缘入口可达 |

隐藏风险探针：

- MySQL 类型映射丢精度，例如 decimal、datetime、json。
- 发现表时重复同步产生脏模型。
- 软删除数据在列表、详情、统计中不一致。

### M03 数据开发

状态：已完成，执行记录见 `records/20260620-M03数据开发执行记录.md`。

页面入口：

- `/data-development`

接口边界：

- `/api/v1/data-development/tree`
- `/api/v1/data-development/directories`
- `/api/v1/data-development/scripts`
- `/api/v1/data-development/scripts/execute`
- `/api/v1/data-development/sql/execute`

重点用例：

| 编号 | 用例 |
|---|---|
| M03-01 | 创建 `长期回归-经营分析脚本目录`，刷新后仍存在 |
| M03-02 | 创建 SQL、模型 Flink SQL、Java、Python 四类脚本并保存 |
| M03-03 | SQL 查询长期客户表，校验返回列、行数、分页 |
| M03-04 | 错误 SQL 返回可读错误，不白屏 |
| M03-05 | 目录和脚本移动、删除确认、刷新 |
| M03-06 | 空目录、空脚本、未保存离开提示 |

隐藏风险探针：

- SQL 执行使用了错误数据源或错误项目上下文。
- 脚本类型切换导致内容丢失。
- 删除目录时子脚本处理不一致。

### M04 数据服务

状态：已完成，执行记录见 `records/20260620-M04数据服务执行记录.md`。

页面入口：

- `/data-services`
- `/data-services/new`
- `/data-services/:serviceId/edit`
- `/data-service-metrics`
- `/data-service-metrics/access-logs`

接口边界：

- `/api/v1/data-services/*`
- `/api/v1/data-service-metrics/*`
- `/api/v1/open/data-services/*`
- `/api/v1/open/webservice/data-services/*`
- `/api/v1/invocation-logs/*`

重点用例：

| 编号 | 用例 |
|---|---|
| M04-01 | 基于模型发布 `长期回归-客户画像查询服务` |
| M04-02 | 基于自定义 SQL 发布 `长期回归-客户订单查询服务` |
| M04-03 | 字段解析、请求参数、返回参数、分页参数配置 |
| M04-04 | GET 与 POST 发布、下线、重新发布 |
| M04-05 | 调试模板生成、成功调用、错误参数调用 |
| M04-06 | 订阅 Token 新建、轮换、禁用、启用 |
| M04-07 | 开放 API 未授权、授权成功、禁用后拒绝 |
| M04-08 | 监控统计、访问日志、日志详情和下载 |

隐藏风险探针：

- Token 禁用后仍可调用。
- SQL 参数注入或未绑定参数直接拼接。
- 下线服务仍有开放 API 可用。

### M05 数据接入与协议转换

状态：已完成，执行记录见 `records/20260620-M05数据接入与协议转换执行记录.md`。

页面入口：

- `/data-ingestion-services`
- `/data-ingestion-services/new`
- `/data-ingestion-services/:serviceId/edit`
- `/data-ingestion-metrics`
- `/data-ingestion-metrics/access-logs`
- `/protocol-conversions`
- `/protocol-conversions/new`
- `/protocol-conversions/:serviceId/edit`
- `/protocol-conversions/access-logs`

接口边界：

- `/api/v1/data-ingestion-services/*`
- `/api/v1/data-ingestion-metrics/*`
- `/api/v1/open/data-ingestion-services/*`
- `/api/v1/open/webservice/data-ingestion-services/*`
- `/api/v1/http-writer-sink/*`
- `/api/v1/protocol-conversions/*`
- `/api/v1/protocol-conversion-metrics/*`
- `/api/v1/open/protocol-conversions/*`
- `/api/v1/open/webservice/protocol-conversions/*`

重点用例：

| 编号 | 用例 |
|---|---|
| M05-01 | 创建 `长期回归-客户标签接入服务`，目标写入长期表 |
| M05-02 | JSON、表单、批量行写入调试 |
| M05-03 | 发布、订阅 Token、开放 API 授权调用 |
| M05-04 | 字段缺失、类型错误、重复主键错误提示 |
| M05-05 | 接入监控、访问日志、调用详情 |
| M05-06 | 创建 `长期回归-订单状态协议转换` |
| M05-07 | HTTP 到 HTTP 转换、SOAP/WebService 预览和调试 |
| M05-08 | 协议转换订阅、发布、下线、删除保护 |

隐藏风险探针：

- 接入写入部分成功时页面没有说明。
- 协议转换转发失败但调用日志标记成功。
- WebService 预览与真实调用参数结构不一致。

### M06 数据采集与指标

状态：已完成，执行记录见 `records/20260620-M06数据采集与指标执行记录.md`。

页面入口：

- `/field-mapping-rules`
- `/field-mapping-rules/new`
- `/collection-tasks`
- `/collection-tasks/new`
- `/collection-tasks/:taskId/edit`
- `/collection-task-runs`
- `/run-metrics`

接口边界：

- `/api/v1/field-mapping-rules/*`
- `/api/v1/collection-tasks/*`
- `/api/v1/runs/*`
- `/api/v1/run-metrics/*`

重点用例：

| 编号 | 用例 |
|---|---|
| M06-01 | 创建可复用字段映射规则，覆盖参数校验 |
| M06-02 | 单表全量采集任务：预览、保存、上线 |
| M06-03 | SQL/增量采集任务：游标字段、重置游标、重复运行 |
| M06-04 | 双表融合采集任务：join 关系、字段映射、目标写入 |
| M06-05 | 手动触发、运行列表、运行日志、失败重试 |
| M06-06 | 指标监控筛选、趋势和 TopN 排行 |

阻断条件：

- 若 `长期回归测试项目` 没有授权在线 Worker，触发、日志和指标生成标记 `BLOCKED`，配置、预览和上线继续执行。

隐藏风险探针：

- Reader/Writer 字段映射在重复编辑后错位。
- 增量游标重置后重复或漏采。
- 任务上线状态和可被工作流引用状态不一致。

### M07 数据质量

状态：已完成，执行记录见 `records/20260620-M07数据质量执行记录.md`。

页面入口：

- `/quality-rules`
- `/quality-rules/new`
- `/quality-rules/:ruleId/edit`
- `/quality-tasks`
- `/quality-tasks/new`
- `/quality-tasks/:taskId/edit`
- `/quality-task-runs`
- `/quality-metrics`

接口边界：

- `/api/v1/quality-rules/*`
- `/api/v1/quality-tasks/*`
- `/api/v1/quality-metrics/*`

重点用例：

| 编号 | 用例 |
|---|---|
| M07-01 | 表级行数规则、唯一性规则创建和启停 |
| M07-02 | 列级完整性、格式合法性规则创建和参数解析 |
| M07-03 | 规则编辑、批量删除、删除被任务引用的规则 |
| M07-04 | 质量任务预览、校验、保存、上线 |
| M07-05 | 手动执行、任务运行日志、日志下载 |
| M07-06 | 质量指标筛选、资产详情、问题详情 |
| M07-07 | 问题指派、状态变更、严重度变更、评论 |

阻断条件：

- 若 Worker 不可用，质量任务执行和问题生成标记 `BLOCKED`，规则和任务配置继续执行。

隐藏风险探针：

- 质量 SQL 参数解析与运行绑定参数不一致。
- 同一问题重复生成无法去重。
- 指派/状态/严重度变更没有权限控制。

### M08 工作流、调度与运维

状态：已完成，执行记录见 `records/20260620-M08工作流调度与运维执行记录.md`。

页面入口：

- `/workflows`
- `/workflows/new`
- `/workflows/:workflowId`
- `/workflows/:workflowId/edit`
- `/runs`
- `/runs/:workflowRunId`
- `/ops-center`

接口边界：

- `/api/v1/workflows/*`
- `/api/v1/workflow-runs/*`
- `/api/v1/schedules`
- `/api/v1/ops-center/*`
- `/api/v1/invocation-logs/*`

重点用例：

| 编号 | 用例 |
|---|---|
| M08-01 | 创建 `长期回归-客户经营数据日处理流程` 草稿 |
| M08-02 | 画布节点配置：采集任务、质量任务、数据脚本 |
| M08-03 | DAG 连线、节点参数保存、重新打开不丢失 |
| M08-04 | 发布、触发、终止、运行详情 |
| M08-05 | 节点日志查看、下载、失败节点定位 |
| M08-06 | 调度列表、队列、Worker、服务事件筛选 |

阻断条件：

- 若没有授权在线 Worker，发布可测，触发与运行详情标记 `BLOCKED`。

隐藏风险探针：

- 画布保存后节点坐标或连线丢失。
- 运行详情 DAG 状态和列表状态不一致。
- 终止运行后节点仍继续执行。

### M09 系统管理与权限

状态：已完成，执行记录见 `records/20260620-M09系统管理与权限执行记录.md`。

页面入口：

- `/system`
- `/access-center`
- `/register`

接口边界：

- `/api/v1/system/tenants`
- `/api/v1/system/projects`
- `/api/v1/system/tenant-members`
- `/api/v1/system/project-members`
- `/api/v1/system/project-member-requests`
- `/api/v1/system/project-workers`
- `/api/v1/system/resource-shares`
- `/api/v1/system/user-registration-requests`
- `/api/v1/users`
- `/api/v1/roles`
- `/api/v1/permissions`

重点用例：

| 编号 | 用例 |
|---|---|
| M09-01 | 用户创建、禁用/删除保护、重复账号校验 |
| M09-02 | 租户、项目创建与列表筛选 |
| M09-03 | 长期项目成员新增、删除、再次审批复活路径回归 |
| M09-04 | 成员申请、审批、拒绝、撤销 |
| M09-05 | Worker 授权到长期项目、取消授权 |
| M09-06 | 资源共享创建、删除、跨项目可见性 |
| M09-07 | 注册登记审核通过、拒绝、重复处理 |
| M09-08 | 不同角色访问菜单和接口权限 |

隐藏风险探针：

- 软删除成员再次审批触发唯一键冲突。
- 前端隐藏菜单但接口仍可越权调用。
- 删除租户/项目时未保护关联数据。

### M10 通知、关注、导入导出与日志下载

状态：已完成，执行记录见 `records/20260620-M10通知关注导入导出与日志下载执行记录.md`。

页面入口：

- `/notifications`
- 资产详情关注入口
- 各日志详情和下载入口

接口边界：

- `/api/v1/notifications/*`
- `/api/v1/follows/*`
- `/api/v1/exports/project`
- `/api/v1/imports/template`
- `/api/v1/invocation-logs/*`

重点用例：

| 编号 | 用例 |
|---|---|
| M10-01 | 站内信列表、未读数、已读状态 |
| M10-02 | 关注资产、取消关注、关注状态刷新 |
| M10-03 | 项目导出下载，文件可打开且不为空 |
| M10-04 | 导入模板下载，字段完整 |
| M10-05 | 服务、接入、协议转换日志详情和下载 |

隐藏风险探针：

- 下载文件名乱码。
- 未读数和列表状态不同步。
- 导出包含跨项目敏感数据。

## 执行顺序

1. M00 长期环境与基础夹具。
2. M01 认证、工作台与访问中心。
3. M02 数据资产、目录与模型。
4. M03 数据开发。
5. M04 数据服务。
6. M05 数据接入与协议转换。
7. M06 数据采集与指标。
8. M07 数据质量。
9. M08 工作流、调度与运维。
10. M09 系统管理与权限。
11. M10 通知、关注、导入导出与日志下载。

若 Worker 仍不可用，先完成所有配置、预览、保存、发布、日志空态和权限链路；运行链路保留 `BLOCKED`，待 Worker 可用后集中复测 M06、M07、M08。

## 每轮执行记录模板

后续每轮在 `records/` 新增记录，建议格式如下：

| 字段 | 内容 |
|---|---|
| 日期 | `YYYY-MM-DD` |
| 任务 | 用户触发语句或模块范围 |
| 使用项目 | 固定为 `长期回归测试项目`，若临时使用其他项目需说明 |
| 使用数据库 | 固定为 `studio_longterm_regression`，若临时使用其他库需说明 |
| 新增对象 | 展示名、技术编码、用途 |
| 复用对象 | 展示名、技术编码、上次来源 |
| 覆盖用例 | Mxx-xx 列表 |
| 结果 | PASS / FAIL / BLOCKED |
| 缺陷 | 编号、严重级别、复现步骤、是否已修复 |
| 未清理数据 | 所有新增对象默认保留 |
| 下轮建议 | 需要继续重测或补测的入口 |

## 修复判定规则

需要修复：

- 页面白屏、路由不可达、主流程保存/发布/执行失败。
- 数据写入错误、跨项目数据泄漏、权限绕过。
- 控制台出现业务错误或未处理异常。
- 错误提示不可理解，导致用户无法知道下一步。
- 长期测试数据无法复用或被异常清理。

可暂缓：

- 纯文案问题。
- 轻微布局问题，不影响操作。
- 第三方库废弃 API warning，但当前功能可用。
- 外部依赖缺失且页面提示明确，例如 Worker 未授权。
