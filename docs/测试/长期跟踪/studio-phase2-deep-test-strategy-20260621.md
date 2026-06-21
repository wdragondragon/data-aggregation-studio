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
| 数据接入/协议转换 | BUG-M05-001、BUG-M05-002、BUG-S05-001、BUG-S06-001、BUG-S07-001 |
| 数据采集 | BUG-M06-001、BUG-M06-002 |
| 运维中心 | FIX-M08-001 |
| 系统管理/权限 | M09-FIX-01 至 M09-FIX-04 |
| 导入导出/日志 | M10-FIX-01 |

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

S03 新增的 `BUG-S03-001`、`BUG-S03-002` 均纳入 `smoke`：后续涉及数据源连接错误、连接历史、运行异常、运维中心异常列表或错误消息展示的修改，必须复跑 S03 探针并做数据源/运维中心浏览器复核。

S04 新增的 `BUG-S04-001` 纳入 `module-regression`：后续涉及系统资源共享、资源类型枚举、协议转换服务列表或共享资源接收项目可见性的修改，必须复跑协议转换共享单测、前端构建和 `/system` 资源共享弹窗验证。

S05 新增的 `BUG-S05-001` 纳入 `module-regression`：后续涉及协议转换订阅、Token 轮换、启用/禁用、开放 API 订阅校验或监控订阅方筛选的修改，必须复跑协议转换订阅单测和订阅弹窗/API 状态验证。

S06 新增的 `BUG-S06-001` 纳入 `smoke`：后续涉及数据服务、数据接入、协议转换订阅创建/启用、schema upgrade、订阅表初始化 SQL 或唯一约束的修改，必须复跑 S06 并发探针和订阅回归单测。

S07 新增的 `BUG-S07-001` 纳入 `smoke`：后续涉及数据服务、数据接入、协议转换开放调用、订阅 Token 轮换/禁用/启用或 `lastUsedAt` 刷新的修改，必须复跑 S07 生命周期探针和订阅回归单测。

S08 新增的 `BUG-S08-001` 纳入 `smoke`：后续涉及 `DispatchService`、`ClusterLockService`、采集/质量/工作流手动触发、运行记录、Worker 调度锁或活跃运行判定的修改，必须复跑 S08 并发触发探针、`DispatchServiceOverlapRegressionTest` 和 `ClusterLockServiceRegressionTest`。

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
