# Studio 第二阶段深度测试策略

## 背景

- 编制日期：2026-06-21
- 适用范围：Studio 长期测试项目、长期 MySQL 库和既有长期测试数据。
- 目标：把 M00-M10 的一次性细测成果沉淀为可重复、可追溯、可回归的专项测试体系。
- 固定记录目录：`DataAggregation/data-aggregation-studio/docs/测试/长期跟踪`

## 固定环境

| 类型 | 值 |
|---|---|
| Studio | `http://127.0.0.1:8000/dfs/data-aggregation-studio/dashboard` |
| API | `http://127.0.0.1:18080/api/v1` |
| 账号 | `admin / admin123` |
| 长期项目 | `长期回归测试项目` / `2068077680446365698` |
| 接收项目 | `长期回归-资源接收项目` / `2068301893337849857` |
| MySQL | `8.140.247.113:13309 / root`，库 `studio_longterm_regression` |
| Python | `C:\dev\python-envs\studio-longterm-conda311\python.exe` |
| Nacos | IDEA run configuration：`NACOS_SERVER=127.0.0.1:8848` |
| Server/Worker | IDEA `StudioServerApplication`、`StudioWorkerApplication` |

长期数据策略不变：除非用户明确要求，禁止清理长期库、长期项目和长期测试对象。

## 回归层级

### smoke

触发时机：

- 每次确认真实缺陷并完成修复后。
- 每次 Server/Worker/Nacos 环境切换后。
- 每次提交前的最低验证门槛。

覆盖内容：

| 域 | 固定检查 |
|---|---|
| 运行态 | Nacos readiness、Server health、Worker health |
| 认证与上下文 | admin 登录、长期项目 `X-Project-Id`、未登录保护 |
| 权限 | 普通成员访问角色/权限接口应返回 403 |
| 数据资产 | 长期 MySQL 数据源测试连接、模型列表可查 |
| 数据服务 | 长期客户订单查询服务开放调用、Token 禁用/启用核心路径 |
| 数据接入 | 长期接入服务调试写入或重复主键错误提示 |
| 采集 | 增量采集二次运行不报 DATETIME 类型错误 |
| 质量 | 质量指标接口返回长期质量基线 |
| 工作流 | 长期工作流详情、最近成功运行与日志可查 |
| 导出隔离 | 接收项目 `/workflows` 可见共享工作流，但 `/exports/project` 不包含源项目工作流 |

### module-regression

触发时机：

- 修改某个 Controller、Service、前端页面、SDK 或调度/权限公共逻辑后。
- 用户要求对某个模块快速迭代测试后。

执行方式：

- 只跑受影响模块和其上下游模块。
- 必须复用长期数据。
- 必须读取对应代码边界，不能只点页面。
- 至少包含 API 探针和浏览器页面验证各一次。

模块映射：

| 模块 | 必跑历史回归 |
|---|---|
| 数据开发 | BUG-M03-001 |
| 数据服务 | BUG-M04-001、BUG-M04-002 |
| 数据接入/协议转换 | BUG-M05-001、BUG-M05-002、BUG-S05-001、BUG-S06-001、BUG-S07-001、BUG-S12-001 |
| 数据采集 | BUG-M06-001、BUG-M06-002 |
| 运维中心 | FIX-M08-001 |
| 系统管理/权限 | M09-FIX-01 至 M09-FIX-04 |
| 导入导出/日志 | M10-FIX-01、BUG-S13-001、BUG-S15-001 |
| 协议转换 Trace/日志 | BUG-S14-001、BUG-S15-001 |
| 关注/通知 | BUG-S18-001、BUG-S19-001、BUG-S20-001、BUG-S20-002 |

### nightly-deep

触发时机：

- 阶段性测试、合并前深度验证、重大运行环境变化后。

覆盖内容：

- M00-M10 主链路。
- 三个专项首轮测试的全部用例。
- 长期数据正确性对账。
- 权限矩阵。
- 故障注入中可安全执行的场景。

## 第二阶段专项

### S01 权限隔离与多项目数据泄漏

优先级：最高。

覆盖用户：

- admin。
- 长期项目普通成员。
- 接收项目成员。
- 禁用用户。
- 未登录用户。
- 跨项目成员。

覆盖入口：

- 前端菜单、直接路由。
- API 直接调用。
- 列表、详情、下载、导出、日志、触发、删除。

重点风险：

- 菜单隐藏但接口可越权。
- 共享资源被导出或删除。
- 日志下载跨项目泄漏。
- 工作流、运行记录、质量问题、服务日志跨项目可见。

首轮验收：

- 产出 `records/20260621-S01权限隔离与多项目数据泄漏执行记录.md`。
- 每个高风险入口至少有 admin 正向、普通成员或跨项目负向验证。
- 如发现真实缺陷，修复、回归、提交，并写入缺陷索引。

### S02 数据正确性与幂等性

覆盖链路：

- 数据源发现、模型同步、SQL、数据服务、接入写入、采集、质量、工作流。

校验维度：

- 行数、主键、字段值、金额精度、时间字段、空值、重复键、增量游标、融合 join、重复运行幂等性。

对账方式：

- MySQL 源表 SQL 对账。
- 关键结果抽样 hash。
- 目标表和运行记录双向校验。

首轮验收：

- 产出 `records/20260621-S02数据正确性与幂等性执行记录.md`。
- 至少覆盖采集增量、融合 join、数据服务查询、接入写入、质量问题生成。

### S03 故障注入与恢复

覆盖场景：

- Worker 下线/恢复。
- Nacos 短暂不可用。
- Redis 不可用。
- MySQL 断连/慢查询。
- 对象存储日志失败。

判定规则：

- 可安全注入则执行。
- 不适合当前环境执行则标记 `BLOCKED`，写明缺失前置、风险和建议执行方式。

首轮验收：

- 产出 `records/20260621-S03故障注入与恢复执行记录.md`。
- 至少完成 Worker、Redis、MySQL 三类场景的可执行性评估，其中安全场景必须实际验证。

### S05 协议转换订阅状态一致性

覆盖场景：

- 协议转换订阅创建、停用、重新启用的状态一致性。
- 同服务同名订阅在“停用旧订阅后创建新订阅，再启用旧订阅”路径下的唯一启用约束。
- API 状态、浏览器订阅弹窗、长期保留订阅数据的一致性。

验收：

- 产出 `records/20260621-S05协议转换订阅状态一致性深挖执行记录.md`。
- 如发现真实缺陷，修复、补充协议转换订阅回归测试，并写入缺陷索引。

### S06 订阅并发创建竞态

覆盖场景：

- 数据服务、数据接入、协议转换三类订阅的同名并发创建。
- 应用层查重与数据库唯一约束在并发下的一致性。
- 修复前重复启用历史数据的保留与规整。

验收：

- 产出 `records/20260621-S06订阅并发创建竞态深挖执行记录.md`。
- 沉淀 `scripts/studio_s06_subscription_race_probe.py`，包含运行态 health 与 16 并发创建探针。
- 如发现真实缺陷，修复 schema upgrade、初始化 SQL、服务层唯一键异常兜底，并写入缺陷索引。

### S07 订阅 Token 生命周期并发一致性

覆盖场景：

- 数据服务、数据接入、协议转换三类订阅 token 创建、轮换、禁用、启用。
- 开放调用刷新 `lastUsedAt` 与 token 轮换/禁用之间的并发状态一致性。
- 旧 token 失效、新 token 生效、禁用 token 拒绝、重新启用恢复调用。

验收：

- 产出 `records/20260621-S07订阅Token生命周期并发一致性深挖执行记录.md`。
- 沉淀 `scripts/studio_s07_subscription_token_lifecycle_probe.py`。
- 如发现真实缺陷，修复服务层状态刷新，补充订阅回归测试，并写入缺陷索引。

### S08 手动触发并发去重与运行记录一致性

覆盖场景：

- 采集任务、质量任务、工作流三类手动触发/立即执行入口。
- 同一业务对象 8 路并发触发时的 API 响应、`dispatch_task`、`run_record`、工作流运行单元一致性。
- 同一 Server 实例内锁重入、快速双击、短任务快速完成后的重复触发风险。

验收：

- 产出 `records/20260621-S08手动触发并发去重与运行记录一致性深挖执行记录.md`。
- 沉淀 `scripts/studio_s08_manual_trigger_race_probe.py`。
- 如发现真实缺陷，修复调度服务和锁服务，补充调度重叠回归测试，并写入缺陷索引。

### S09 数据结构变更扰动

覆盖场景：

- MySQL 源表/目标表加列、目标表新增未映射 NOT NULL 列、源字段改名缺失。
- 模型同步后的字段可见性，采集任务保存/发布/触发后的目标表写入和失败不写入半成品。
- 运行记录 `message/payloadJson/resultJson` 在新写入和历史读取两条路径上的错误消息清洗。

验收：

- 产出 `records/20260621-S09数据结构变更扰动深挖执行记录.md`。
- 沉淀 `scripts/studio_s09_schema_drift_probe.py`。
- 如发现真实缺陷，修复运行记录写入/读取路径，补充事件持久化和历史读取回归测试，并写入缺陷索引。

### S10 结构扰动扩展：服务、接入、质量

覆盖场景：

- 数据服务自定义 SQL 依赖字段被底层表改名。
- 数据接入目标表新增未映射 `NOT NULL` 列。
- 数据质量任务绑定字段被底层表改名。
- 运行记录 `message/payloadJson/resultJson` 中 `stackTrace` 等结构化错误字段在新写入和历史读取路径上的清洗。

验收：

- 产出 `records/20260621-S10结构扰动扩展服务接入质量执行记录.md`。
- 沉淀 `scripts/studio_s10_schema_drift_service_quality_probe.py`。
- 如发现真实缺陷，补充运行记录结构化字段清洗回归测试，并写入缺陷索引。

### S11 生命周期并发交错

覆盖场景：

- 数据服务发布、下线和开放 API 调用交错并发。
- 数据接入发布、下线和开放 API 写入交错并发，校验目标表写入数和成功调用数一致。
- 数据质量任务上线和触发交错并发，校验运行记录最终收敛。

验收：

- 产出 `records/20260621-S11生命周期并发交错执行记录.md`。
- 沉淀 `scripts/studio_s11_lifecycle_race_probe.py`。
- 如发现真实缺陷，补充对应状态机或开放调用回归测试，并写入缺陷索引。

### S12 容量与边界慢写一致性

覆盖场景：

- 数据服务 1206 行大结果集、分页上限、第二页余量和长文本字段。
- 数据接入 500 行批量边界、501 行上限拒绝、重复主键、非法金额转换。
- 中文特殊字符、NULL、空字符串、金额精度、DATE/DATETIME/TIMESTAMP。
- 数据接入 writer 超过慢阈值后的 API 响应与数据库副作用一致性。

验收：

- 产出 `records/20260621-S12容量边界执行记录.md`。
- 沉淀 `scripts/studio_s12_capacity_boundary_probe.py`。
- 如发现真实缺陷，补充接入执行支持回归测试，并写入缺陷索引。

### S13 日志下载、分页、并发与敏感信息脱敏

覆盖场景：

- 数据服务、数据接入开放调用完整日志的查看、下载、小分页和并发下载。
- 运行记录对象日志的分页、下载和并发读取。
- Header、请求体、assignment 风格日志中的 `password`、`token`、`secret`、`api_key`、`access-key`、`authorization`、`cookie`、`credential` 等敏感字段脱敏。
- 非法 domain、跨项目调用日志和运行日志下载隔离。

验收：

- 产出 `records/20260622-S13日志下载分页并发与脱敏执行记录.md`。
- 沉淀 `scripts/studio_s13_log_download_security_probe.py`。
- 如发现真实缺陷，补充开放调用日志脱敏回归测试，并写入缺陷索引。

### S14 协议转换 Trace/日志脱敏与日志隔离

覆盖场景：

- 协议转换 debug 返回的 `conversionTrace.sourceRequest`，覆盖 Header、Query、Form、Body 敏感字段。
- 协议转换开放调用后的访问日志 Trace、完整日志查看、分页、下载和并发下载。
- 协议转换完整日志和 Trace 的跨项目读取隔离。
- debug `targetRequest` 快照中的 URL、Header、Body 诊断输出脱敏。

验收：

- 产出 `records/20260622-S14协议转换Trace日志脱敏执行记录.md`。
- 沉淀 `scripts/studio_s14_protocol_conversion_trace_security_probe.py`。
- 如发现真实缺陷，补充协议转换 Trace 脱敏单测，并写入缺陷索引。

### S15 SOAP 协议转换 Trace/日志脱敏

覆盖场景：

- SOAP WebService 协议转换开放入口、WSDL、订阅 Token、SOAP Header 和 SOAP Body。
- XML/SOAP 敏感元素值与属性值：`protocolConversionToken`、`clientSecret`、`password`、`apiKey` 等。
- 协议转换访问日志 Trace、完整日志查看和下载。
- 与 S13/S14 共享的开放调用日志统一脱敏工具。

验收：

- 产出 `records/20260622-S15SOAP协议转换Trace日志脱敏执行记录.md`。
- 沉淀 `scripts/studio_s15_soap_trace_security_probe.py`。
- 如发现真实缺陷，补充开放调用日志 XML/SOAP 脱敏单测，并写入缺陷索引。

### S16 全量 ACL 深挖

覆盖场景：

- 在 S01 权限隔离首轮基础上，扩展到更多 Controller/method 的低权限和跨项目访问。
- 覆盖列表、详情、创建、编辑、删除、发布、同步、触发、下载、导出等接口。
- 前端按钮权限与后端接口权限必须一致，避免“页面隐藏但接口越权”或“接口拒绝但页面仍暴露写入口”。
- 首轮聚焦 `MetaSchemaController` 全局元模型写接口和 `/metadata` 元模型中心写按钮。

验收：

- 产出 `records/20260622-S16全量ACL深挖执行记录.md`。
- 沉淀 `scripts/studio_s16_acl_deep_probe.py`。
- 如发现真实缺陷，补充权限回归测试，并写入缺陷索引。

### S17 数据源当前表单 ACL 深挖

覆盖场景：

- 数据源保存态连接测试、模型发现和当前表单连接测试的项目边界一致性。
- 编辑态保留已加密敏感字段时，`request.id` 必须只引用当前项目可写数据源。
- 接收项目成员构造源项目数据源 `id`、省略 `password` 的负向探针。
- 本项目管理员编辑数据源时省略 `password` 的正向兼容性。

验收：

- 产出 `records/20260622-S17数据源当前表单ACL深挖执行记录.md`。
- 沉淀 `scripts/studio_s17_datasource_current_form_acl_probe.py`。
- 如发现真实缺陷，补充数据源 API 回归测试和服务层回归测试，并写入缺陷索引。

### S18 关注目标 ACL 深挖

覆盖场景：

- 关注接口 `status/follow/unfollow` 的目标存在性、当前租户、当前项目和资源共享可读边界。
- 共享工作流可关注，但不可读的源项目工作流运行、采集运行和不存在目标不可关注。
- 修复前遗留或分享撤销后的个人关注记录仍能通过 `unfollow` 幂等关闭。
- Browser 复核消息中心和工作流日志页，确认关注/通知相关入口无新前端错误。

验收：

- 产出 `records/20260622-S18关注目标ACL深挖执行记录.md`。
- 沉淀 `scripts/studio_s18_follow_target_acl_probe.py`。
- 如发现真实缺陷，补充关注 API 回归测试，并写入缺陷索引。

### S19 通知 Fanout 禁用用户边界

覆盖场景：

- 资源共享、注册/访问申请、模型同步、采集/工作流运行等通知 fan-out 的收件人启用状态。
- 项目成员 `ACTIVE` 但 `sys_user.enabled=0` 的用户不应收到新的站内通知。
- `activeProjectMemberUserIds`、`superAdminUserIds` 和 `notifyUsers` 的禁用用户过滤一致性。
- Browser 复核消息中心和工作流日志页，确认通知相关入口无新前端错误。

验收：

- 产出 `records/20260622-S19通知Fanout禁用用户边界执行记录.md`。
- 沉淀 `scripts/studio_s19_notification_fanout_acl_probe.py`。
- 如发现真实缺陷，补充通知服务回归测试，并写入缺陷索引。

### S20 共享关注通知 Fanout

覆盖场景：

- 接收项目成员关注源项目共享工作流后的运行通知 fan-out。
- 关注记录所在项目与运行记录源项目不一致时，通知收件人解析必须参考当前启用资源共享。
- 共享关注者收到通知后的点击目标必须保留接收项目上下文，并指向接收项目可读的共享定义页；不得把接收项目成员切换到不可访问的源项目运行详情。
- 共享禁用或撤销后，历史关注记录不应继续收到源项目新的运行通知。
- 同类逻辑后续扩展到共享采集任务。

验收：

- 产出 `records/20260622-S20共享关注通知Fanout执行记录.md`。
- 沉淀 `scripts/studio_s20_shared_follow_notification_probe.py`。
- 如发现真实缺陷，补充关注 API 回归测试，并写入缺陷索引。

### S21 共享采集关注通知与采集指标

覆盖场景：

- 接收项目成员关注源项目共享采集任务后的运行通知 fan-out。
- 通知目标必须保留接收项目上下文，并指向接收项目可读的共享采集任务定义页。
- 共享禁用或撤销后，历史关注记录不应继续收到源项目新的采集运行通知。
- 采集成功运行的 `collected/write_succeed/write_failed/failed` 指标必须与目标表事实一致，不得把 `TerminateRecord` 计入 writer received。
- MySQL writer `writeMode=insert` 二次写入重复主键必须失败，失败行应进入 `write_failed_records/failed_records`；`writeMode=update` 才允许 upsert。

验收：

- 产出 `records/20260622-S21共享采集关注通知与采集指标执行记录.md`。
- 沉淀 `scripts/studio_s21_shared_collection_follow_notification_probe.py`。
- 如发现真实缺陷，补充 DataAggregation 核心和 writer 回归测试，并写入缺陷索引。

## 自动化沉淀规则

- 每个真实缺陷至少补一个可重复验证入口：单测、集成测试、API 探针或脚本。
- 高风险缺陷优先纳入 `smoke`。
- 自动化记录必须包含命令、依赖、预期结果和最近通过时间。
- 不要求一次性搭建完整 E2E 框架，但禁止只保留无法复跑的口头结论。

## 执行状态

| 日期 | 专项 | 状态 | 自动化入口 | 固定回归 |
|---|---|---|---|---|
| 2026-06-21 | S01 权限隔离与多项目数据泄漏 | 首轮完成，`25 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s01_permission_probe.py` | M09-FIX-01、M09-FIX-04、M10-FIX-01 |
| 2026-06-21 | S02 数据正确性与幂等性 | 首轮完成，`17 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s02_data_correctness_probe.py` | BUG-M05-001、BUG-M05-002、BUG-M06-001、BUG-M06-002 |
| 2026-06-21 | S03 故障注入与恢复 | 首轮完成，`9 PASS / 0 FAIL / 3 BLOCKED` | `scripts/studio_s03_fault_recovery_probe.py` | M09-FIX-03、FIX-M08-001、BUG-S03-001、BUG-S03-002 |
| 2026-06-21 | S04 资源共享 ACL 与协议转换共享深挖 | 完成，`6 PASS / 2 FAIL(已修复) / 0 BLOCKED` | `ResourceShareServiceTypeRegressionTest#shouldShareProtocolConversionService`；浏览器 `/system` 资源共享弹窗 | BUG-S04-001 |
| 2026-06-21 | S05 协议转换订阅状态一致性深挖 | 完成，`4 PASS / 2 FAIL(已修复) / 0 BLOCKED` | `SubscriptionTokenRotationRegressionTest#shouldRejectProtocolConversionEnableWhenSameNameAlreadyEnabled`；浏览器 `/protocol-conversions` 订阅弹窗 | BUG-S05-001 |
| 2026-06-21 | S06 订阅并发创建竞态深挖 | 完成，`4 PASS / 4 FAIL(已修复) / 0 BLOCKED` | `scripts/studio_s06_subscription_race_probe.py`；`SubscriptionTokenRotationRegressionTest`；`StudioSchemaDriftRegressionTest` | BUG-S06-001 |
| 2026-06-21 | S07 订阅 Token 生命周期并发一致性深挖 | 完成，`4 PASS / 1 FAIL(已修复) / 0 BLOCKED` | `scripts/studio_s07_subscription_token_lifecycle_probe.py`；`SubscriptionTokenRotationRegressionTest` | BUG-S07-001 |
| 2026-06-21 | S08 手动触发并发去重与运行记录一致性深挖 | 完成，`6 PASS / 4 FAIL(已修复) / 0 BLOCKED`，最终脚本 `4 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s08_manual_trigger_race_probe.py`；`DispatchServiceOverlapRegressionTest`；`ClusterLockServiceRegressionTest` | BUG-S08-001 |
| 2026-06-21 | S09 数据结构变更扰动深挖 | 完成，修复前 `4 PASS / 2 FAIL / 0 BLOCKED`，最终脚本 `6 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s09_schema_drift_probe.py`；`ExecutionEventServiceRegressionTest`；`RunServiceRegressionTest`；浏览器 `/collection-task-runs` | BUG-S09-001 |
| 2026-06-21 | S10 结构扰动扩展服务接入质量深挖 | 完成，修复前 `8 PASS / 1 FAIL / 0 BLOCKED`，最终脚本批次 `20260621225707`：`9 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s10_schema_drift_service_quality_probe.py`；`ExecutionEventServiceRegressionTest`；`RunServiceRegressionTest`；浏览器 `/quality-task-runs` | BUG-S10-001 |
| 2026-06-21 | S11 生命周期并发交错深挖 | 完成，脚本批次 `20260621230737`：`7 PASS / 0 FAIL / 0 BLOCKED`，未发现真实缺陷 | `scripts/studio_s11_lifecycle_race_probe.py`；浏览器数据服务/接入服务编辑页和质量任务日志 | 无新增 |
| 2026-06-21 | S12 容量与边界慢写一致性深挖 | 完成，修复前批次 `S12-TIMEOUT-VERIFY-20260621B` 和 `20260621235340` 复现慢写响应/副作用不一致，最终批次 `20260621235740`：`10 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s12_capacity_boundary_probe.py`；`DataIngestionInvocationLogSupportTest#shouldWaitForSlowSuccessfulWriteBeyondThreshold`；浏览器数据服务/接入服务调试、指标与日志页 | BUG-S12-001 |
| 2026-06-22 | S13 日志下载分页并发与敏感信息脱敏深挖 | 完成，修复前复现开放调用完整日志敏感字段名变体泄漏，最近通过批次 `20260622003735`：`9 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s13_log_download_security_probe.py`；`DataIngestionInvocationLogSupportTest#shouldMaskSensitiveInvocationLogFieldNameVariants`；浏览器数据服务/接入服务/质量任务日志页 | BUG-S13-001 |
| 2026-06-22 | S14 协议转换 Trace/日志脱敏与日志隔离深挖 | 完成，修复前复现协议转换 debug Trace Query/Form 敏感字段泄漏，最近通过批次 `20260622010840`：`7 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s14_protocol_conversion_trace_security_probe.py`；`WebServiceSupportTest#shouldMaskSensitiveProtocolConversionTraceDiagnostics`；浏览器协议转换访问日志详情和完整日志页 | BUG-S14-001 |
| 2026-06-22 | S15 SOAP 协议转换 Trace/日志脱敏深挖 | 完成，修复前复现 SOAP XML 元素/属性敏感值泄漏，最近通过批次 `20260622014445`：`4 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s15_soap_trace_security_probe.py`；`DataIngestionInvocationLogSupportTest#shouldMaskSensitiveSoapElementAndAttributeValues`；`WebServiceSupportTest`；浏览器协议转换访问日志页 | BUG-S15-001 |
| 2026-06-22 | S16 全量 ACL 深挖：元模型管理接口 | 完成，修复前复现普通项目成员可写全局元模型，最近通过批次 `20260622030400`：`11 PASS / 0 FAIL / 0 BLOCKED`，S01 权限探针 `25 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s16_acl_deep_probe.py`；`StudioInitializationApiRegressionTest#metadataSchemaWriteApisShouldRejectProjectMember`；浏览器 `/metadata` 管理员/普通成员按钮权限 | BUG-S16-001 |
| 2026-06-22 | S17 数据源当前表单 ACL 深挖 | 完成，修复前批次 `20260622033528` 复现跨项目当前表单连接测试借用源项目密码，修复后最近通过批次 `20260622034448`：`7 PASS / 0 FAIL / 0 BLOCKED`，S01 权限探针 `25 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s17_datasource_current_form_acl_probe.py`；`DataSourceApiRegressionTest#currentFormConnectionTestShouldRejectCrossProjectDatasourceId`；`DataSourceServiceRegressionTest`；浏览器 `/datasources` | BUG-S17-001 |
| 2026-06-22 | S18 关注目标 ACL 深挖 | 完成，修复前复现接收项目成员可关注不可读工作流运行和不存在工作流，修复后批次 `20260622042659`：`9 PASS / 0 FAIL / 0 BLOCKED`，S01 权限探针 `25 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s18_follow_target_acl_probe.py`；`FollowApiRegressionTest#followApisShouldValidateTargetExistenceAndReadableScope`；浏览器 `/notifications` 和 `/runs` | BUG-S18-001 |
| 2026-06-22 | S19 通知 Fanout 禁用用户边界深挖 | 完成，修复前批次 `20260622050311` 复现禁用用户收到资源共享通知，修复后批次 `20260622050552`：`6 PASS / 0 FAIL / 0 BLOCKED`，S18 探针 `9 PASS / 0 FAIL / 0 BLOCKED`，S01 权限探针 `25 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s19_notification_fanout_acl_probe.py`；`NotificationServiceRegressionTest`；`NotificationStreamSecurityRegressionTest`；浏览器 `/notifications` 和 `/runs` | BUG-S19-001 |
| 2026-06-22 | S20 共享关注通知 Fanout 深挖 | 完成，修复前批次 `20260622052317` 复现共享工作流关注者收不到源项目运行通知；补充复现共享关注者通知目标指向不可访问源项目运行详情；最终批次 `20260622060425`：`15 PASS / 0 FAIL / 0 BLOCKED`，S18/S19/S01 探针均通过，浏览器点击通知可进入接收项目共享工作流详情 | `scripts/studio_s20_shared_follow_notification_probe.py`；`FollowApiRegressionTest#sharedWorkflowFollowersShouldFanOutOnlyWhileShareEnabled`；`ExecutionEventServiceRegressionTest#sharedWorkflowFollowerNotificationShouldTargetReadableProject`；浏览器 `/notifications` 点击通知 | BUG-S20-001、BUG-S20-002 |
| 2026-06-22 | S21 共享采集关注通知与采集指标深挖 | 完成，修复前复现采集成功指标多算 1 条终止记录、重复主键路径因 MySQL insert/upsert 回归和逐行失败未收集脏数据导致状态/指标错误；最终批次 `20260622150929`：`18 PASS / 0 FAIL / 0 BLOCKED` | `scripts/studio_s21_shared_collection_follow_notification_probe.py`；`ChannelStatisticsTest`；`Mysql8WriterTest`；浏览器采集任务编辑页观察 | BUG-S21-001、BUG-S21-002、BUG-M05-001 回归防护 |

S03 新增的 `BUG-S03-001`、`BUG-S03-002` 均纳入 `smoke`：后续涉及数据源连接错误、连接历史、运行异常、运维中心异常列表或错误消息展示的修改，必须复跑 S03 探针并做数据源/运维中心浏览器复核。

S04 新增的 `BUG-S04-001` 纳入 `module-regression`：后续涉及系统资源共享、资源类型枚举、协议转换服务列表或共享资源接收项目可见性的修改，必须复跑协议转换共享单测、前端构建和 `/system` 资源共享弹窗验证。

S05 新增的 `BUG-S05-001` 纳入 `module-regression`：后续涉及协议转换订阅、Token 轮换、启用/禁用、开放 API 订阅校验或监控订阅方筛选的修改，必须复跑协议转换订阅单测和订阅弹窗/API 状态验证。

S06 新增的 `BUG-S06-001` 纳入 `smoke`：后续涉及数据服务、数据接入、协议转换订阅创建/启用、schema upgrade、订阅表初始化 SQL 或唯一约束的修改，必须复跑 S06 并发探针和订阅回归单测。

S07 新增的 `BUG-S07-001` 纳入 `smoke`：后续涉及数据服务、数据接入、协议转换开放调用、订阅 Token 轮换/禁用/启用或 `lastUsedAt` 刷新的修改，必须复跑 S07 生命周期探针和订阅回归单测。

S08 新增的 `BUG-S08-001` 纳入 `smoke`：后续涉及 `DispatchService`、`ClusterLockService`、采集/质量/工作流手动触发、运行记录、Worker 调度锁或活跃运行判定的修改，必须复跑 S08 并发触发探针、`DispatchServiceOverlapRegressionTest` 和 `ClusterLockServiceRegressionTest`。

S09 新增的 `BUG-S09-001` 纳入 `smoke`：后续涉及 `ExecutionEventService`、`RunService`、`WorkflowRunService`、运行记录 message/payload/result、采集失败提示、日志 fallback 或结构变更错误展示的修改，必须复跑 S09 结构扰动探针、`ExecutionEventServiceRegressionTest`、`RunServiceRegressionTest`，并用浏览器复核 `/collection-task-runs` 历史失败记录。

S10 新增的 `BUG-S10-001` 纳入 `smoke`：后续涉及运行记录 `stackTrace`、质量任务执行错误、数据服务/数据接入结构变更错误展示、运行记录 payload/result 读取或错误消息清洗逻辑的修改，必须复跑 S10 结构扰动扩展探针、`ExecutionEventServiceRegressionTest`、`RunServiceRegressionTest`，并用浏览器复核 `/quality-task-runs` 历史失败记录。

S11 未新增缺陷，但沉淀为状态机回归入口：后续涉及数据服务/数据接入发布下线、开放 API 调用、质量任务上线触发、开放调用成功/失败响应契约或状态机一致性的修改，必须复跑 S11 生命周期并发探针，并用浏览器复核对应编辑页和质量任务日志。

S12 新增的 `BUG-S12-001` 纳入 `smoke`：后续涉及 `DataIngestionExecutionSupport`、数据接入开放 API、writer 执行等待、批量写入、接入调用日志或 writer 线程中断/慢写处理的修改，必须复跑 S12 容量边界探针、`DataIngestionInvocationLogSupportTest`，并用浏览器复核数据接入调试页、接入指标页和接入运行日志页。

S13 新增的 `BUG-S13-001` 纳入 `smoke`：后续涉及 `OpenServiceInvocationLogSupport`、开放调用日志采集/查看/下载、日志分页、跨项目日志访问、运行记录对象日志下载或敏感信息脱敏的修改，必须复跑 S13 日志安全探针、`DataIngestionInvocationLogSupportTest`，并用浏览器复核数据服务访问日志、数据接入运行日志和至少一个运行记录日志页。

S14 新增的 `BUG-S14-001` 纳入 `smoke`：后续涉及 `ProtocolConversionService`、协议转换 debug、协议转换开放调用、协议转换访问日志 Trace、协议转换完整日志下载或协议转换敏感信息脱敏的修改，必须复跑 S14 协议转换 Trace 安全探针、`WebServiceSupportTest`，并用浏览器复核协议转换访问日志详情和完整日志抽屉。

S15 新增的 `BUG-S15-001` 纳入 `smoke`：后续涉及 `OpenServiceInvocationLogSupport`、数据服务/数据接入/协议转换 WebService 开放入口、SOAP/XML Trace 预览、完整日志查看/下载或 XML/SOAP 敏感信息脱敏的修改，必须复跑 S15 SOAP Trace 安全探针、`DataIngestionInvocationLogSupportTest`、`WebServiceSupportTest`，并用浏览器复核协议转换访问日志详情和完整日志抽屉。

S16 新增的 `BUG-S16-001` 纳入 `smoke`：后续涉及 `MetaSchemaController`、`MetadataSchemaService`、`MetadataSchemasView.vue`、角色权限计算、全局/租户级配置写接口或元模型同步/发布/删除逻辑的修改，必须复跑 S16 ACL 探针、`StudioInitializationApiRegressionTest`、S01 权限探针，并用浏览器复核 `/metadata` 管理员和普通成员视图。

S17 新增的 `BUG-S17-001` 纳入 `smoke`：后续涉及 `DataSourceService`、数据源当前表单连接测试、敏感字段保留、数据源共享、数据源连接测试、模型发现或数据源编辑页的修改，必须复跑 S17 探针、`DataSourceApiRegressionTest`、`DataSourceServiceRegressionTest`、S01 权限探针，并用浏览器复核 `/datasources`。

S18 新增的 `BUG-S18-001` 纳入 `smoke`：后续涉及 `FollowSubscriptionService`、关注按钮、通知 fan-out、工作流/采集运行详情、资源共享撤销、目标删除或消息中心的修改，必须复跑 S18 关注目标 ACL 探针、`FollowApiRegressionTest`、S01 权限探针，并用浏览器复核 `/notifications` 和 `/runs`。

S19 新增的 `BUG-S19-001` 纳入 `smoke`：后续涉及 `NotificationService`、资源共享通知、注册/访问申请通知、模型同步/采集/工作流运行通知、SSE 通知流、用户启停用或项目成员状态的修改，必须复跑 S19 通知 fan-out 探针、`NotificationServiceRegressionTest`、`NotificationStreamSecurityRegressionTest`、S18 关注探针、S01 权限探针，并用浏览器复核 `/notifications`。

S20 新增的 `BUG-S20-001`、`BUG-S20-002` 纳入 `smoke`：后续涉及 `FollowSubscriptionService`、`ExecutionEventService`、资源共享启用/禁用/撤销、共享工作流或共享采集任务运行通知 fan-out、通知目标项目或通知点击跳转的修改，必须复跑 S20 共享关注通知探针、`FollowApiRegressionTest`、`ExecutionEventServiceRegressionTest`、S18 关注探针、S19 通知探针、S01 权限探针，并用浏览器复核 `/notifications` 点击通知后的目标页和项目上下文。

S21 新增的 `BUG-S21-001`、`BUG-S21-002` 纳入 `smoke`：后续涉及 `Channel`、`CommunicationTool`、`CommonRdbmsWriter`、MySQL writer 写入模式、采集运行指标、重复主键失败、共享采集任务关注通知或资源共享撤销后旧关注隔离的修改，必须复跑 S21 共享采集关注通知探针、`ChannelStatisticsTest`、`Mysql8WriterTest` 和 S02 数据正确性探针，并用浏览器复核采集任务编辑页、采集运行列表和消息中心。

S21 观察项 `OBS-S21-001`：共享禁用后接收项目成员直达已不可读采集任务编辑页时，后端 404 但前端停留空编辑表单，且存在 Element Plus radio deprecation warning。后续可作为 S22 前端错误态与组件弃用告警专项，不影响 S21 采集指标修复结论。

## 提交规则

- 真实缺陷修复通过验证后必须及时提交。
- 按一个缺陷或一组强相关缺陷提交。
- 多层仓库先提交实际修改仓库，再逐层提交子模块指针。
- 提交前检查状态，禁止纳入运行日志、临时文件、无关脏改动或用户已有改动。
- 若用户要求暂不提交，在执行记录中标注“已修复未提交”及原因。

## 阶段完成标准

- 本策略文档存在并被 README 引用。
- 缺陷回归索引存在并覆盖 M03-M10 已修问题。
- S01、S02、S03 三个专项均完成首轮执行记录。
- 所有真实缺陷均具备缺陷记录、代码修复、回归证据和提交号。
- 最终记录 Server、Worker、Nacos 健康状态。
