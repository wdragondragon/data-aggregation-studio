# Studio 前端全覆盖测试结果 2026-06-19

## 执行摘要

- 执行时间：2026-06-19
- 入口：`http://127.0.0.1:8000/dfs/data-aggregation-studio/dashboard`
- 账号：`admin / admin123`
- 当前上下文：`Default Tenant / Default Project`
- 测试数据：展示名使用中文业务名，技术编码使用 `e2e_20260619_` / `test_20260619_` 前缀。
- 清理状态：Studio 测试对象、系统管理测试对象、MySQL 测试库、本地 mock 端口均已清理。
- 控制台：页面探测期间未发现 Studio 应用 `error/warn`；Browser 运行时自身的外部 Statsig 超时不计入 Studio 缺陷。

## 需要修复的问题

| 编号 | 严重级别 | 模块 | 问题 | 可复现步骤 | 实际结果 | 是否需要修复 |
|---|---|---|---|---|---|---|
| BUG-001 | 高 | 认证 | 点击“退出登录”未退出 | 登录后在 dashboard 点击页头“退出登录” | 点击成功但仍停留在 `/dashboard`，页面仍显示 `admin`、`Default Project` | 是 |
| BUG-002 | 高 | 系统管理 | 删除项目成员后审批同一用户邀请返回 500 | 新建测试用户 -> 添加为项目成员 -> 删除该项目成员 -> 新建同用户项目邀请 -> 审批通过 | 返回 500，错误包含 `SQLIntegrityConstraintViolationException: Duplicate entry ... uk_studio_project_member_user` | 是 |

## 阻断项

| 编号 | 模块 | 阻断原因 | 影响范围 | 是否需要修复 |
|---|---|---|---|---|
| BLOCK-001 | Worker/调度 | 当前项目没有授权在线 Worker，接口返回 `No authorized online worker is available for the current project`；运维中心提示“当前项目未下发任何 Worker 组，任务不会被 Worker 领取” | 采集任务手动触发、质量任务手动触发、工作流手动触发、运行详情/日志下载 | 若测试环境应具备 Worker，则需要补环境；产品本身提示清晰 |
| BLOCK-002 | 质量问题处理 | 质量任务无法进入运行阶段，未生成质量问题 | 问题详情、指派、状态/严重度变更、评论 | 依赖 BLOCK-001 |

## 执行明细

| 编号 | 模块 | 前置数据 | 步骤摘要 | 预期 | 实际 | 结果 | 证据 | 是否需要修复 |
|---|---|---|---|---|---|---|---|---|
| AUTH-UI-001 | 认证 | 无 | 使用 `admin/admin123` 登录 | 进入 dashboard | 登录成功，显示总览 | PASS | URL `/dashboard`，页头 `admin / Default Project` | 否 |
| AUTH-UI-002 | 认证 | 已登录 | 读取页头上下文 | 显示租户/项目 | 显示 `Default Tenant / Default Project` | PASS | Browser DOM | 否 |
| AUTH-UI-003 | 认证 | 已登录 | 点击 English/中文 | 不白屏 | 页面保持可用，中文文案仍可见 | PASS | Browser DOM | 否 |
| AUTH-UI-004 | 路由兜底 | 已登录 | 访问 `/unknown-e2e-route` | 回 dashboard | 重定向到 `/dashboard` | PASS | Browser URL | 否 |
| AUTH-UI-005 | 鉴权 | 无 Token | 访问 `/api/v1/models` | 拒绝访问 | HTTP 401，`Authentication required` | PASS | API 响应 | 否 |
| AUTH-UI-006 | 认证 | 已登录 | 点击“退出登录” | 退出并到登录页 | 仍在 dashboard，仍显示登录用户 | FAIL | Browser DOM/URL | 是 |
| ROUTE-001 | 路由 | 已登录 | 访问资产域页面 | 页面可达 | dashboard、datasources、models、metadata、statistics 渲染；catalog 实际标题为“能力矩阵” | PASS | Browser 路由探测，复查无空白 | 否 |
| ROUTE-002 | 路由 | 已登录 | 访问开发域页面 | 页面可达 | data-development、workflows、runs、run-metrics 可渲染 | PASS | Browser DOM | 否 |
| ROUTE-003 | 路由 | 已登录 | 访问数据服务页面 | 页面可达 | data-services、新建页、服务监控、访问日志可渲染 | PASS | Browser DOM/API | 否 |
| ROUTE-004 | 路由 | 已登录 | 访问数据接入页面 | 页面可达 | data-ingestion-services、新建页、监控、日志可渲染 | PASS | Browser 复查 | 否 |
| ROUTE-005 | 路由 | 已登录 | 访问协议转换页面 | 页面可达 | protocol-conversions、新建页、调用日志可渲染；日志页标题为“协议转换调用日志” | PASS | Browser 复查 | 否 |
| ROUTE-006 | 路由 | 已登录 | 访问字段映射和采集页面 | 页面可达 | field-mapping-rules/new、collection-tasks、collection-task-runs 可渲染 | PASS | Browser DOM/API | 否 |
| ROUTE-007 | 路由 | 已登录 | 访问质量页面 | 页面可达 | quality-rules、quality-tasks/new、quality-task-runs、quality-metrics 可渲染 | PASS | Browser DOM/API | 否 |
| ROUTE-008 | 路由 | 已登录 | 访问运维、通知、系统 | 页面可达 | ops-center、notifications、system 可渲染；system 标签页完整 | PASS | Browser DOM，系统页 body 长度 537 | 否 |
| DS-001 | 数据源 | MySQL 库 | 新建主数据源并表单测试 | 成功 | `华东客户画像 MySQL 数据源` 连接成功 | PASS | API/页面 |
| DS-002 | 数据源 | 已保存数据源 | 保存后测试连接 | 成功并有历史 | 连接成功，连接历史 1 条 | PASS | API `connection-history` | 否 |
| DS-003 | 数据源 | 错误库名 | 测试无效连接 | 清晰失败 | 返回 `UNAVAILABLE`，说明未知库 | PASS | API 响应 | 否 |
| DS-004 | 数据源 | 停用源 | 保存禁用/未纳管源 | 状态可见 | `华东客户画像停用展示源` 保存成功 | PASS | API/列表 | 否 |
| DS-005 | 数据发现 | 主数据源 | 发现表 | 发现测试表 | 发现 5 张表 | PASS | API `/datasources/{id}/discover` | 否 |
| DS-006 | 模型 | 主数据源 | 同步模型 | 生成 5 个模型 | 生成客户、订单、目标、接入目标、质量样例 5 个模型 | PASS | API `/models/datasource/{id}/sync` | 否 |
| DS-007 | 模型 | 已同步模型 | 预览模型 | 返回样例 | 客户表、质量样例表元数据和行可读 | PASS | API `/models/{id}` / preview | 否 |
| DS-008 | 统计/目录/血缘 | 已同步模型 | 打开统计、目录、血缘页面 | 页面可达 | 页面可达；标题存在与预期文案有差异但非功能失败 | PASS | Browser DOM | 否 |
| DEV-001 | 数据开发 | 已登录 | 新建目录 | 保存成功 | `客户画像分析脚本目录` id `2067987509457674242` | PASS | API | 否 |
| DEV-002 | 数据开发 | 目录 | 创建 SQL 脚本 | 保存成功 | `客户活跃清单查询.sql` 保存成功 | PASS | API | 否 |
| DEV-003 | 数据开发 | 目录 | 创建 Java/Python 脚本 | 保存成功 | `客户分层评分清洗.java`、`客户画像标签生成.py` 保存成功 | PASS | API | 否 |
| DEV-004 | 数据开发 | SQL 脚本 | 执行查询 | 返回数据 | ACTIVE 客户查询返回 8 行 | PASS | API 执行结果 | 否 |
| DEV-005 | 数据开发 | SQL 编辑器 | 执行错误 SQL | 清晰错误 | HTTP 400，错误 SQL 提示可理解 | PASS | API 响应 | 否 |
| SVC-001 | 数据服务 | 客户模型 | 创建模型发布服务 | 保存成功 | `客户画像明细查询服务` id `2067988070722658306` | PASS | API | 否 |
| SVC-002 | 数据服务 | 模型发布服务 | 字段解析 | 成功 | 字段解析成功 | PASS | API `/resolve-fields` | 否 |
| SVC-003 | 数据服务 | 模型发布服务 | 发布和订阅 Token 操作 | 成功 | 发布 ONLINE；Token 新建/轮换/禁用/启用成功 | PASS | API | 否 |
| SVC-004 | 数据服务 | 模型发布服务 | 调试调用 | 成功 | 调试成功并返回数据 | PASS | API debug | 否 |
| SVC-005 | 数据服务 | 主数据源 | 创建 SQL 服务 | 保存成功 | `客户消费金额筛选服务` id `2067988122799136770` | PASS | API | 否 |
| SVC-006 | 数据服务 | SQL 服务 | 发布并调试 | 成功 | 发布 ONLINE，调试成功 | PASS | API debug | 否 |
| SVC-007 | 开放 API | 发布服务 | 未授权/授权调用 | 拒绝/通过 | 未授权拒绝，授权 Token 调用通过 | PASS | API 摘要 | 否 |
| SVC-008 | 数据服务 | 发布服务 | 下线、删除 | 成功 | 下线 OFFLINE，清理阶段删除成功 | PASS | API cleanup | 否 |
| SVC-009 | 服务监控 | 调试日志 | 查询监控和访问日志 | 可用 | `/data-service-metrics/dashboard/query`、`access-logs/query` 成功 | PASS | API | 否 |
| ING-001 | 数据接入 | 主数据源 | 创建 REST 接入服务 | 保存成功 | `客户画像实时接入服务` id `2067988490337607682` | PASS | API | 否 |
| ING-002 | 数据接入 | 接入服务 | 发布和订阅 Token 操作 | 成功 | 发布 ONLINE；Token 新建/轮换/禁用/启用成功 | PASS | API | 否 |
| ING-003 | 数据接入 | 接入服务 | 调试写入 | 成功 | `receivedCount=1`、`successCount=1` | PASS | API debug，目标表写入 | 否 |
| ING-004 | 接入监控 | 接入服务 | 查询监控和日志 | 可用 | metrics 和 access logs 查询成功 | PASS | API | 否 |
| ING-005 | 数据接入 | 接入服务 | 下线、删除 | 成功 | 下线 OFFLINE，清理阶段删除成功 | PASS | API cleanup | 否 |
| PROTO-001 | 协议转换 | HTTP 目标源 | 创建协议转换服务 | 保存成功 | `客户资料协议转换服务` id `2067989223040573441` | PASS | API | 否 |
| PROTO-002 | 协议转换 | 协议服务 | 发布和订阅 Token 操作 | 成功 | 发布 ONLINE；Token 操作成功 | PASS | API | 否 |
| PROTO-003 | 协议转换 | JSON echo 下游 | 调试 | 成功 | target HTTP 200，`status=SUCCESS`，`successCount=1` | PASS | API debug | 否 |
| PROTO-004 | 协议转换日志 | 协议服务 | 查询调用日志 | 可达 | 调用日志页面/接口可达 | PASS | Browser/API | 否 |
| PROTO-005 | 协议转换 | 协议服务 | 下线、删除 | 成功 | 下线 OFFLINE，清理阶段删除成功 | PASS | API cleanup | 否 |
| COL-001 | 数据采集 | 主数据源 | 创建全量采集 | 预览、保存、上线 | `客户明细全量采集任务` ONLINE | PASS | API | 否 |
| COL-002 | 数据采集 | 主数据源 | 创建 SQL/增量采集 | 预览、保存、上线 | `客户增量筛选采集任务` ONLINE | PASS | API | 否 |
| COL-003 | 数据采集 | 客户+订单 | 创建融合采集 | 预览、保存、上线 | `客户订单融合采集任务` ONLINE | PASS | API | 否 |
| COL-004 | 数据采集 | 增量任务 | 重置增量游标 | 成功 | reset cursor 成功 | PASS | API | 否 |
| COL-005 | 数据采集 | 三类任务 | 手动触发 | 入队运行 | 均返回 `No authorized online worker...` | BLOCKED | API 400，运维中心同样提示 | 否，需补 Worker |
| COL-006 | 运行日志 | 触发后 | 查看采集运行记录 | 有运行日志 | 无 Worker，未生成运行记录 | BLOCKED | `/runs` 无记录 | 否，需补 Worker |
| DQ-001 | 数据质量 | 主数据源 | 创建行数规则 | 成功 | 规则 id `2067993160598642689`，SQL 语义校验通过 | PASS | API parse/validate/save | 否 |
| DQ-002 | 数据质量 | 质量样例表 | 创建唯一性规则 | 成功 | 规则 id `2067993217301438465`，预览 SQL 正确 | PASS | API | 否 |
| DQ-003 | 数据质量 | 质量样例表 | 创建非空规则 | 成功 | 规则 id `2067993274339778562`，预览 SQL 正确 | PASS | API | 否 |
| DQ-004 | 数据质量 | 三条规则 | 启禁用和批量删除 | 成功 | 启禁用成功；清理阶段 batch-delete 成功 | PASS | API | 否 |
| DQ-005 | 数据质量 | 三条规则 | 创建任务、预览、连库校验 | 成功 | 行数 30；重复数 1；空值数 1；均 `Query executed successfully` | PASS | API validate | 否 |
| DQ-006 | 数据质量 | 三条任务 | 发布和手动触发 | 发布并触发 | 发布 ONLINE；触发被 Worker 前置阻断 | BLOCKED | API 400 | 否，需补 Worker |
| DQ-007 | 质量指标 | 质量任务 | 查询指标、资产风险、问题列表 | 可用 | 指标可查，覆盖矩阵包含 COMPLETENESS/UNIQUENESS；问题列表空 | PASS | API dashboard/query | 否 |
| DQ-008 | 质量问题 | 有运行结果 | 详情/指派/状态变更 | 成功 | 无运行结果和问题记录，无法执行 | BLOCKED | 依赖 Worker | 否，需补 Worker |
| WF-001 | 工作流 | 采集/质量/脚本 | 创建三节点 DAG | 保存成功 | `客户画像采集质量联动工作流` id `2067993380485029890`，3 节点 2 边 | PASS | API | 否 |
| WF-002 | 工作流 | 草稿 | 发布 | 成功 | `published=true` | PASS | API | 否 |
| WF-003 | 工作流 | 已发布 | 手动触发 | 生成运行 | 返回 `No authorized online worker...` | BLOCKED | API 400 | 否，需补 Worker |
| WF-004 | 工作流日志 | 运行结果 | 查看详情/日志 | 可查看 | 未生成运行，无法查看日志 | BLOCKED | `/workflow-runs` total 0 | 否，需补 Worker |
| OPS-001 | 运维中心 | 已登录 | 查看队列、Worker、服务事件 | 页面可达并展示风险 | 页面展示严重风险：当前项目未下发 Worker 组 | PASS | Browser DOM `/ops-center` | 否 |
| SYS-001 | 系统管理 | 超管 | 打开系统页 | 标签完整 | 用户管理、注册登记、租户、项目、成员、Worker 下发、资源共享均可见 | PASS | Browser DOM `/system` | 否 |
| SYS-002 | 系统管理 | 缺少租户名 | 提交无效租户 | 业务错误 | HTTP 400，`Tenant code and name are required` | PASS | API | 否 |
| SYS-003 | 系统管理 | 无 | 创建/编辑/删除用户 | 成功 | `客户画像运营测试用户（已编辑）` 创建编辑成功，清理删除成功 | PASS | API | 否 |
| SYS-004 | 系统管理 | 无 | 创建/编辑/删除租户 | 成功 | `客户画像测试租户` 创建编辑成功，清理删除成功 | PASS | API | 否 |
| SYS-005 | 系统管理 | 当前租户 | 创建/编辑/删除项目 | 成功 | `客户画像共享演练项目` 创建编辑成功，清理删除成功 | PASS | API | 否 |
| SYS-006 | 系统管理 | 测试用户 | 租户成员、项目成员增删 | 成功 | 直接新增/删除成功 | PASS | API | 否 |
| SYS-007 | 系统管理 | 干净测试用户 | 项目邀请审批 | 成功 | `客户画像邀请审批用户` 邀请审批 APPROVED，生成项目成员 | PASS | API | 否 |
| SYS-008 | 系统管理 | 当前项目 | Worker 组下发增改删 | 成功 | `e2e_20260619_customer_worker_group` 新增、禁用、删除成功 | PASS | API | 否 |
| SYS-009 | 系统管理 | 目标项目+数据源 | 资源共享增改删 | 成功 | 数据源共享到“客户画像共享演练项目”，禁用、删除成功 | PASS | API | 否 |
| SYS-010 | 注册登记 | 注册接口 | 提交通过/驳回 | 状态正确 | 通过请求 APPROVED 并生成用户；驳回请求 REJECTED | PASS | API | 否 |
| SYS-011 | 系统管理 | 曾删除项目成员 | 删除成员后审批同用户邀请 | 不返回 500 | 返回 500 和数据库唯一键冲突 | FAIL | API 响应 | 是 |
| CLEAN-001 | 清理 | Studio 测试对象 | 删除所有 Studio 前缀对象 | 无残留 | workflows、services、quality、collection、scripts、models、datasources 全部无残留 | PASS | 清理脚本复查 | 否 |
| CLEAN-002 | 清理 | MySQL 库 | 删除 `studio_e2e_20260619` | 不存在 | `show databases like 'studio_e2e_20260619'` 为空 | PASS | pymysql 复查 | 否 |
| CLEAN-003 | 清理 | 本地 mock | 停止 18089/18090 | 端口关闭 | 两个 Python 进程停止，端口不监听 | PASS | PowerShell `Get-NetTCPConnection` | 否 |

## 关键执行证据

| 链路 | 证据摘要 |
|---|---|
| MySQL 数据准备 | 创建 5 张测试表，客户 30 行、订单 40 行、质量样例 4 行 |
| 数据源发现/同步 | 发现 5 张表，同步 5 个模型 |
| 数据开发 SQL | ACTIVE 客户查询返回 8 行；错误 SQL 返回 HTTP 400 |
| 数据服务 | 模型服务和 SQL 服务均发布、订阅、调试成功 |
| 数据接入 | 调试写入 `receivedCount=1`、`successCount=1` |
| 协议转换 | JSON echo 目标返回 HTTP 200，转换调试成功 |
| 采集任务 | 全量、SQL/增量、融合三类任务均预览、保存、上线成功 |
| 数据质量 | 行数 30、重复数 1、空值数 1；指标页覆盖矩阵显示 COMPLETENESS/UNIQUENESS |
| 工作流 | 三节点 DAG 保存/发布成功，触发被 Worker 前置阻断 |
| 系统管理 | 用户、租户、项目、成员、注册审批、Worker、资源共享均覆盖；发现 SYS-011 缺陷 |
| 清理 | Studio 无残留；MySQL 库已删除；18089/18090 已停止 |

## 清理明细

- 删除工作流：`客户画像采集质量联动工作流`
- 删除服务：`客户画像明细查询服务`、`客户消费金额筛选服务`、`客户画像实时接入服务`、`客户资料协议转换服务`
- 删除质量任务/规则：3 个质量任务、3 个质量规则，规则使用批量删除接口
- 删除采集任务：`客户明细全量采集任务`、`客户增量筛选采集任务`、`客户订单融合采集任务`
- 删除脚本/目录：`客户活跃清单查询.sql`、`客户分层评分清洗.java`、`客户画像标签生成.py`、`客户画像分析脚本目录`
- 删除模型和数据源：5 个 `e2e_20260619_` 模型，5 个测试数据源
- 删除系统管理对象：测试用户、注册登记、租户、项目、成员、Worker 绑定、资源共享均无残留
- 删除 MySQL 库：`studio_e2e_20260619`
- 停止 mock 服务：`18089`、`18090`
