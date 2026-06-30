# Studio 长期测试跟踪记事

## 固定约定

- 本目录是当前会话后续 Studio 测试的长期记事目录。
- 每次执行新的 Studio 测试任务前，先回顾本目录，再决定本轮测试数据、范围、风险和回归点。
- 从 2026-06-20 起，长期测试不再采用“测试后清理所有数据”的策略。
- 长期测试数据必须保留，便于追溯、复测、对比和快速迭代验证。
- 除非用户明确要求清理，禁止删除长期测试库、长期测试项目和长期测试对象。

## 长期测试环境

| 类型 | 固定标识 | 说明 |
|---|---|---|
| Studio 入口 | `http://127.0.0.1:8000/dfs/data-aggregation-studio/dashboard` | 后续 UI 测试默认入口 |
| 登录账号 | `admin / admin123` | 后续测试默认账号 |
| 默认租户 | `Default Tenant` | 作为长期测试项目所在租户 |
| 长期测试项目 | `长期回归测试项目` | 后续模块测试与迭代测试优先使用该项目；若不存在，测试前创建并保留 |
| 长期 MySQL 测试库 | `studio_longterm_regression` | 后续涉及数据源、采集、服务、质量、工作流时优先使用；若不存在，测试前创建并保留 |
| MySQL 连接 | `8.140.247.113:13309 / root` | 仅用于 Studio 长期测试数据准备 |
| Nacos 配置中心 | `127.0.0.1:8848` | 2026-06-20 起 Server/Worker 使用 IDEA run configuration 中的 `NACOS_SERVER` 指向本地 Nacos |
| Studio 运行时 Python | `C:\dev\python-envs\studio-longterm-conda311\python.exe` | IDEA 中 `StudioServerApplication` 与 `StudioWorkerApplication` 均已切换到该解释器，Server/Worker 临时目录分别为 `tmp/server`、`tmp/worker` |
| 测试客户端 Python | `C:\dev\python-envs\studio-longterm-conda311\python.exe` | 后续本会话的数据准备、MySQL 校验和半自动测试脚本优先使用；已验证 `pymysql/requests/websocket-client/pytest/cryptography` 可用 |

## 环境工具约定

- IDEA 运行配置中的 `StudioServerApplication` 和 `StudioWorkerApplication` 使用同一个 Studio 运行时 Python，不直接作为测试客户端安装依赖。
- 当前 `conda` 主命令未加入 PATH；`C:\dev\anaconda3\Scripts\conda.exe` 因 base 环境缺少 `ipaddress` 启动失败。
- `C:\dev\anaconda3\_conda.exe` 可用，已用于创建专用长期测试 prefix：`C:\dev\python-envs\studio-longterm-conda311`。
- 已发现 `C:\dev\python-envs\studio-e2e-python` 与 `C:\dev\python-envs\studio-longterm-test` 均来源于 `canyon-video`，缺少 `_ctypes` 且无 `pip`，不再作为长期测试环境。
- `studio-longterm-conda311` 已验证可导入 `ctypes/pip/pymysql/requests`，连接 `studio_longterm_regression` 返回 MySQL `8.0.21`，请求 Server health 返回 `UP`。

## 命名规则

- 展示名尽量使用有业务意义的中文名称，例如 `长期回归-客户经营画像数据源`、`长期回归-客户订单融合采集任务`。
- 技术编码使用可追踪前缀，不使用无意义随机英文：
  - 长期测试对象：`lt_reg_`
  - 具体批次：`lt_reg_YYYYMMDD_`
  - 快速迭代验证：`lt_reg_iter_YYYYMMDD_`
- 不再使用一次性 `e2e_YYYYMMDD_` 后清理策略，除非用户明确要求一次性隔离测试。

## 记录规则

每轮测试至少记录：

- 测试日期和触发任务。
- 使用的长期库、长期项目和关键测试对象。
- 覆盖范围。
- 新增或复用的数据。
- 测试结果：`PASS / FAIL / BLOCKED`。
- 发现问题、修复状态、回归验证结论。
- 未清理数据说明，方便后续复测。

## 长期索引

| 文档 | 用途 |
|---|---|
| `studio-module-detailed-test-master-plan-20260620.md` | M00-M10 模块切分、页面/API 边界和首轮细测计划 |
| `studio-phase2-deep-test-strategy-20260621.md` | 第二阶段深度测试策略、回归层级和专项验收标准 |
| `studio-defect-regression-index.md` | 已修真实缺陷、固定回归入口、最近通过时间和提交号 |
| `records/` | 每轮长期测试执行记录 |

## 当前基线

- 2026-06-19 已完成一次全覆盖前端测试，原策略为一次性数据并已清理。
- 2026-06-20 已对全覆盖测试发现的两个真实问题完成二次验证和修复。
- 从下一次测试任务开始，使用长期测试库和长期测试项目作为默认追踪载体。
- 2026-06-20 已完成 M00-M05：长期环境、认证工作台访问中心、数据资产目录与模型、数据开发、数据服务、数据接入与协议转换。
- M03 修复了已保存 Java/Python 脚本切换时被默认模板覆盖的问题；当时建立的 `studio-e2e-python` 已在 M05 环境校准后废弃。
- M04 修复了数据服务调试入口未消费 `debug=1`、免 Token WebService 样例仍生成假 token 两个问题；保留 `长期回归-客户画像查询服务` 与 `长期回归-客户订单查询服务` 作为数据服务回归基线。
- M05 修复了 MySQL writer 默认 insert 被错误转成 upsert、数据接入写入失败暴露 Java 堆栈两个问题；保留 `长期回归-Studio健康检查协议转换` 作为协议转换成功基线。
- 2026-06-20 已将长期测试 Python 统一切换为 `studio-longterm-conda311`，后续不再使用缺 `_ctypes`/`pip` 的 `studio-e2e-python`。
- M06 修复了增量 DATETIME 游标二次运行类型比较失败、融合采集未把 join key 纳入源查询字段两个问题；保留 `长期回归-客户订单全量采集任务`、`长期回归-客户订单增量采集任务`、`长期回归-订单支付融合采集任务` 和对应运行记录作为采集与指标回归基线。
- 当前长期采集目标表基线：`lt_reg_order_incremental_result` 6 行，`lt_reg_order_payment_fusion_result` 6 行；保留探针订单 `ORD-M06-20260620-001`、`ORD-M06-FIX-20260620-002`。
- 2026-06-20 远程 Nacos 不可访问后，已将运行基线切换为本地 `127.0.0.1:8848`；Server/Worker 已通过 IDEA `StudioServerApplication`、`StudioWorkerApplication` run configuration 启动并验证 health 为 `UP`。
- Nacos 本地化只通过 IDEA run configuration 的 `NACOS_SERVER=127.0.0.1:8848` 生效，不把本地地址作为根模块源码默认值提交。
- M07 已完成数据质量细测：保留 3 条质量规则、3 条质量任务、3 条质量运行记录和 2 条活跃质量问题；表级规则误用 `${Column}` 的疑点经代码和接口二次验证为已正确拒绝，未触发修复。
- 当前长期质量基线：`lt_reg_quality_exception_sample` 共 8 行，其中 M07 样例 4 行；M07 重复键计数 1、手机号缺失计数 2；质量指标接口和 UI 稳定态显示执行健康分 76、治理风险指数 50、活跃问题数 2。
- M08 已完成工作流调度与运维细测：保留工作流 `长期回归-客户经营数据日处理流程` / `2068289624298184705`，最新运行 `2068295084980002817` 成功，3 个节点均有可查看日志。
- M08 修复了运维中心运行域筛选语义问题：筛选 `工作流节点 + 成功 + studio-online-worker-01` 时，不再被不相关的数据服务/接入历史异常拉成告警；已补 `OpsCenterServiceRegressionTest` 并完成 UI/API 回归。
- M09 已完成系统管理与权限细测：修复低权限用户可读写角色/权限接口、重复用户名注册申请提交阶段未拒绝、禁用用户登录返回 500、`/system` 直接路由缺少角色 meta 四个问题；已补 `StudioInitializationApiRegressionTest` 并完成实服 API、前端构建和 admin 浏览器回归。
- M09 保留 `lt_reg_m09_api_member_live`、`lt_reg_m09_disabled_login_live`、`长期回归-业务协作租户`、`长期回归-资源接收项目`、`lt_reg_m09_backup_worker_group` 和资源共享 `2068302026771243009` 作为后续系统管理与权限回归基线。
- M10 已完成通知、关注、导入导出与日志下载细测：修复接收项目导出包包含源项目共享工作流的跨项目泄漏风险；保留 M10 注册登记通知、工作流关注状态、工作流资源共享和日志下载样例作为后续回归基线。
- 当前 M10 导出隔离基线：接收项目 `/workflows` 可见共享工作流 `2068289624298184705`，但 `/exports/project` 返回 `workflows=[]`；数据服务、接入服务、协议转换调用日志在接收项目重复下载均返回 `404 NOT_FOUND`。
- 当前 Server/Worker 最终复查 health 均为 `UP`；后续每轮测试开始前仍需先确认运行态健康、IDEA run configuration 的 `NACOS_SERVER=127.0.0.1:8848` 和长期项目上下文。
- 2026-06-21 已完成 S01 权限隔离与多项目数据泄漏首轮：API 探针 `25 PASS / 0 FAIL / 0 BLOCKED`，浏览器验证未登录、admin、普通成员直接路由均通过；保留 S01 用户、共享边界探针工作流 `2068468596864704513`、分享 `2068468609984487426`、接收项目 Worker 绑定 `2068469152932945922` 和接收项目运行记录 `2068469278833307649`、`2068471073076867074`。
- S01 自动化入口：`docs/测试/长期跟踪/scripts/studio_s01_permission_probe.py`。后续权限、导出、日志、共享资源相关修改至少复跑该脚本和对应浏览器路由验证。
- 2026-06-21 已完成 S02 数据正确性与幂等性首轮：API/MySQL 探针 `17 PASS / 0 FAIL / 0 BLOCKED`，浏览器验证 dashboard、数据源、模型、数据服务、数据接入、采集运行、质量运行、质量指标、工作流运行、运维中心均通过；保留接入流水 `LT-S02-20260621072628`、`LT-S02-20260621072708`、`LT-S02-20260621072856` 和本轮新增采集/质量/工作流运行记录。
- S02 自动化入口：`docs/测试/长期跟踪/scripts/studio_s02_data_correctness_probe.py`。后续涉及数据写入、采集、融合、质量、工作流或数据服务查询的修改，至少复跑该脚本并进行关键页面浏览器复核。
- 2026-06-21 已完成 S03 故障注入与恢复首轮：最终 API 探针 `9 PASS / 0 FAIL / 3 BLOCKED / needFix 0`，浏览器验证数据源中心、采集任务日志、运维中心均通过；保留故障数据源 `长期回归-S03不可达MySQL故障数据源` / `2068481810725855233`、接入流水 `LT-S03-20260621082327` 和最终恢复运行 `2068489863365869569`。
- S03 修复了数据源连接测试/连接历史暴露 Java 异常类前缀、运维中心历史运行异常暴露 Java 异常类前缀两个问题；`BUG-S03-001`、`BUG-S03-002` 已进入缺陷回归索引。
- S03 自动化入口：`docs/测试/长期跟踪/scripts/studio_s03_fault_recovery_probe.py`。后续涉及数据源连接错误、运行/运维错误消息、Worker 调度恢复、接入失败提示的修改，至少复跑该脚本并进行数据源与运维中心浏览器复核。
- 2026-06-21 已完成 S04 资源共享 ACL 与协议转换共享深挖：确认并修复协议转换服务无法通过系统资源共享发布给接收项目的问题，`BUG-S04-001` 已进入缺陷回归索引。
- S04 保留资源共享 `2068643969963360257`：将协议转换服务 `长期回归-Studio健康检查协议转换` / `2068145027555180546` 从长期项目共享到接收项目；接收项目成员查询 `/protocol-conversions` 可见该共享服务。
- S04 回归入口：`ResourceShareServiceTypeRegressionTest#shouldShareProtocolConversionService`、`npm run build:web`、`/system` 资源共享弹窗，以及接收项目 `/protocol-conversions` 共享可见性 API 探针。后续涉及资源共享、协议转换服务、系统管理共享枚举或接收项目资源可见性的修改，至少复跑这些入口。
- 2026-06-21 已完成 S05 协议转换订阅状态一致性深挖：确认并修复协议转换订阅停用后同名新订阅启用，再重新启用旧订阅时可形成两个同名启用订阅的问题，`BUG-S05-001` 已进入缺陷回归索引。
- S05 保留订阅状态探针数据：修复前复现批次 `长期回归-S05协议转换重名防护-20260621185034`，修复后回归批次 `长期回归-S05协议转换重名修复回归-20260621185651`；长期协议转换服务仍为 `长期回归-Studio健康检查协议转换` / `2068145027555180546`。
- S05 回归入口：`SubscriptionTokenRotationRegressionTest#shouldRejectProtocolConversionEnableWhenSameNameAlreadyEnabled`、协议转换订阅 API 重复启用探针、浏览器 `/protocol-conversions` 订阅弹窗。后续涉及协议转换订阅、Token 轮换、启用/禁用或监控订阅方筛选的修改，至少复跑这些入口。
- 2026-06-21 已完成 S06 订阅并发创建竞态深挖：确认并修复数据服务、数据接入、协议转换三类订阅同名并发创建缺少持久层启用唯一约束的问题，`BUG-S06-001` 已进入缺陷回归索引。
- S06 保留并发订阅数据：修复前失败批次 `20260621191149`，修复后回归批次 `20260621193112`、`20260621193757`、`20260621193853`、`20260621194451`、`20260621200609`；修复前重复启用历史已通过 schema upgrade 规整为每组仅 1 条启用，历史记录不删除。
- S06 自动化入口：`docs/测试/长期跟踪/scripts/studio_s06_subscription_race_probe.py`。后续涉及数据服务/数据接入/协议转换订阅创建、启用唯一约束、schema upgrade 或初始化 SQL 的修改，至少复跑该脚本、`SubscriptionTokenRotationRegressionTest` 和 `StudioSchemaDriftRegressionTest`。
- 2026-06-21 已完成 S07 订阅 Token 生命周期并发一致性深挖：确认并修复三类开放调用刷新订阅 `lastUsedAt` 使用整行 `updateById`，并发轮换/禁用时可能写回旧 token 或启用状态的问题，`BUG-S07-001` 已进入缺陷回归索引。
- S07 保留 Token 生命周期数据：最终回归批次 `20260621200343`，以及脚本校准/基线批次 `20260621195808`、`20260621195908`、`20260621200009`；所有订阅和接入流水保留不清理。
- S07 自动化入口：`docs/测试/长期跟踪/scripts/studio_s07_subscription_token_lifecycle_probe.py`。后续涉及数据服务/数据接入/协议转换开放调用、订阅 Token 轮换、禁用、启用或访问时间刷新逻辑的修改，至少复跑该脚本和 `SubscriptionTokenRotationRegressionTest`。
- 2026-06-21 已完成 S08 手动触发并发去重与运行记录一致性深挖：确认并修复采集任务、质量任务、工作流手动触发缺少同实例并发互斥导致重复调度的问题，`BUG-S08-001` 已进入缺陷回归索引。
- S08 保留手动触发并发数据：修复前复现批次 `20260621201511` 附近，采集/质量/工作流均出现 8 个调度单元；修复后最终回归批次 `20260621210725`，三类对象均为 1 成功、7 个 `BAD_REQUEST`、1 个调度单元。
- S08 自动化入口：`docs/测试/长期跟踪/scripts/studio_s08_manual_trigger_race_probe.py`。后续涉及 `DispatchService`、`ClusterLockService`、采集/质量/工作流手动触发、运行记录或 Worker 调度锁的修改，至少复跑该脚本、`DispatchServiceOverlapRegressionTest` 和 `ClusterLockServiceRegressionTest`。
- 2026-06-21 已完成 S09 数据结构变更扰动深挖：确认并修复采集任务失败运行记录写入与历史读取路径暴露 Java 异常类、内部类名、源码文件名和堆栈的问题，`BUG-S09-001` 已进入缺陷回归索引。
- S09 保留结构扰动数据：`lt_reg_s09_contract_source`、`lt_reg_s09_contract_target`、`lt_reg_s09_required_target`、`lt_reg_s09_missing_source`、`lt_reg_s09_missing_target`；保留 3 个长期采集任务 `2068686558385119234`、`2068686612126736386`、`2068686667600601089` 和修复前/修复后失败运行记录。
- S09 最终回归批次 `20260621215742`：6 PASS / 0 FAIL / 0 BLOCKED；S02 smoke 批次 `20260621215321`：17 PASS / 0 FAIL / 0 BLOCKED。浏览器 `/collection-task-runs` 显示修复前历史失败记录也已通过读路径清洗，console error/warn 为空。
- S09 自动化入口：`docs/测试/长期跟踪/scripts/studio_s09_schema_drift_probe.py`。后续涉及 `ExecutionEventService`、`RunService`、`WorkflowRunService`、运行记录 message/payload/result、采集失败提示或结构变更错误展示的修改，至少复跑该脚本、`ExecutionEventServiceRegressionTest` 和 `RunServiceRegressionTest`。
- 2026-06-21 已完成 S10 结构扰动扩展深挖：覆盖数据服务自定义 SQL 字段改名、数据接入目标未映射必填列、数据质量任务绑定字段改名；确认并修复质量任务失败运行记录 `payloadJson.stackTrace` / `resultJson.stackTrace` 暴露 Java 堆栈的问题，`BUG-S10-001` 已进入缺陷回归索引。
- S10 保留结构扰动数据：`lt_reg_s10_contract_service`、`lt_reg_s10_ingestion_target`、`lt_reg_s10_quality_contract`；保留数据服务 `长期回归-S10合同结构扰动查询服务` / `2068704693544779778`、接入服务 `长期回归-S10目标必填列接入服务` / `2068704728974065665`、质量规则 `2068704763174420481` 和质量任务 `2068704774037667842`。
- S10 最终回归批次 `20260621225707`：9 PASS / 0 FAIL / 0 BLOCKED / needFix 0；Maven 定向回归 `ExecutionEventServiceRegressionTest`、`RunServiceRegressionTest` 共 4 PASS；浏览器 `/data-services/2068704693544779778/edit?debug=1`、`/data-ingestion-services/2068704728974065665/edit?debug=1`、`/quality-task-runs?qualityTaskId=2068704774037667842` 可达且 console error/warn 为空。
- S10 自动化入口：`docs/测试/长期跟踪/scripts/studio_s10_schema_drift_service_quality_probe.py`。后续涉及运行记录 `stackTrace`、质量任务执行错误、数据服务/数据接入结构变更错误展示或运行记录 payload/result 清洗的修改，至少复跑该脚本、`ExecutionEventServiceRegressionTest` 和 `RunServiceRegressionTest`。
- 2026-06-21 已完成 S11 生命周期并发交错深挖：覆盖数据服务/数据接入发布下线与开放调用交错、质量任务上线触发交错；最终批次 `20260621230737` 为 7 PASS / 0 FAIL / 0 BLOCKED / needFix 0，未发现需要修复的真实缺陷。
- S11 保留接入流水 `LT-S11-ING-20260621230542-*`、`LT-S11-ING-20260621230737-*`、`LT-S11-FINAL-20260621230737` 和质量运行 `2068712588646760450`；数据服务、数据接入服务、质量任务最终均恢复为 `ONLINE`。
- S11 自动化入口：`docs/测试/长期跟踪/scripts/studio_s11_lifecycle_race_probe.py`。后续涉及数据服务/接入服务发布下线、开放 API 调用、质量任务上线触发或状态机一致性修改，至少复跑该脚本，并用浏览器复核对应编辑页和质量运行页。
- 2026-06-21 已完成 S12 容量与边界慢写一致性深挖：确认并修复 500 行数据接入慢写超过 10 秒时 API 返回 500 超时但数据库最终写入成功的响应/副作用不一致问题，`BUG-S12-001` 已进入缺陷回归索引。
- S12 保留源表 `lt_reg_s12_boundary_source` 1206 行基线、接入目标表 `lt_reg_s12_boundary_ingest`、数据服务 `长期回归-S12客户边界大结果集查询服务` / `2068717536226799617`、接入服务 `长期回归-S12客户边界批量接入服务` / `2068717572302008321`，以及修复前/修复后 500 行接入流水。
- S12 最终回归批次 `20260621235740`：`10 PASS / 0 FAIL / 0 BLOCKED / needFix 0`；浏览器复核数据服务/接入服务调试页、指标页和日志页均可达，console warn/error 为空。
- S12 自动化入口：`docs/测试/长期跟踪/scripts/studio_s12_capacity_boundary_probe.py`。后续涉及 `DataIngestionExecutionSupport`、数据接入开放 API、批量写入、writer 慢写等待或接入调用日志的修改，至少复跑该脚本、`DataIngestionInvocationLogSupportTest`，并用浏览器复核接入调试和指标/日志页。
- 2026-06-22 已完成 S13 日志下载、分页、并发与敏感信息脱敏深挖：确认并修复开放调用完整日志对 `secretToken`、`clientSecret`、`api_key` 等敏感字段名变体脱敏不完整的问题，`BUG-S13-001` 已进入缺陷回归索引。
- S13 保留调用日志批次 `20260622002222` 和提交前复跑批次 `20260622003735`：数据服务调用日志 `2068731283720765441`、`2068735106325544962`，接入服务调用日志 `2068731310866300929`、`2068735128848957441`，接入流水 `LT-S13-LOG-20260622002222`、`LT-S13-LOG-20260622003735`，并复用运行记录 `2068712588646760450` 做对象日志分页/下载回归。
- S13 最近通过批次 `20260622003735`：`9 PASS / 0 FAIL / 0 BLOCKED / needFix 0`；浏览器复核数据服务访问日志、接入运行日志、质量任务日志三页均可打开日志抽屉，敏感值遮蔽，console warn/error 为空。
- S13 自动化入口：`docs/测试/长期跟踪/scripts/studio_s13_log_download_security_probe.py`。后续涉及 `OpenServiceInvocationLogSupport`、开放调用日志采集/下载、运行记录日志下载、日志分页、跨项目日志访问或敏感信息脱敏的修改，至少复跑该脚本、`DataIngestionInvocationLogSupportTest`，并用浏览器复核相关日志页。
- 2026-06-22 已完成 S14 协议转换 Trace/日志脱敏与日志隔离深挖：确认并修复协议转换 debug/Trace 的 Query/Form 敏感字段未脱敏问题，`BUG-S14-001` 已进入缺陷回归索引。
- S14 保留订阅 `2068742924231430145`（`长期回归-S14协议转换Trace日志脱敏订阅-20260622010840`）、协议转换访问日志 `2068742932208996354`、Debug 请求 `2068742927565901825`、Open 请求 `2068742929470115842` 和 Trace `LT-S14-TRACE-20260622010840-OPEN`。
- S14 最近通过批次 `20260622010840`：`7 PASS / 0 FAIL / 0 BLOCKED / needFix 0`；浏览器复核协议转换访问日志列表、详情 Trace 和完整日志抽屉均未出现 `LT-S14-RAW-*` 敏感原文，Query 显示 `api_key=******`。本轮观察到 Element Plus Pagination deprecated warning，记录为 `OBS-S14-001`，后续可做前端分页告警专项。
- S14 自动化入口：`docs/测试/长期跟踪/scripts/studio_s14_protocol_conversion_trace_security_probe.py`。后续涉及 `ProtocolConversionService`、协议转换 debug、协议转换开放调用、协议转换访问日志 Trace、完整日志下载或协议转换敏感信息脱敏的修改，至少复跑该脚本和 `WebServiceSupportTest`，并用浏览器复核 `/protocol-conversions/access-logs`。
- 2026-06-22 已完成 S15 SOAP 协议转换 Trace/日志脱敏深挖：确认并修复开放调用日志统一脱敏工具未覆盖 XML/SOAP 敏感元素和属性值的问题，`BUG-S15-001` 已进入缺陷回归索引。
- S15 保留 SOAP 源协议转换服务 `长期回归-S15客户SOAP协议转换脱敏服务` / `lt_reg_s15_customer_soap_trace` / `2068748392102285314`，修复前复现访问日志 `2068748407751233538`，修复后最终回归订阅 `2068752008343126017`、访问日志 `2068752012327714818`、Trace `LT-S15-SOAP-20260622014445`。
- S15 最近通过批次 `20260622014445`：`4 PASS / 0 FAIL / 0 BLOCKED / needFix 0`；浏览器复核 `/protocol-conversions/access-logs?serviceId=2068748392102285314` 在切换为“全部访问”后可见最新 SOAP 调用，详情和完整日志抽屉均未出现 `LT-S15-RAW` 敏感原文。`OBS-S14-001` 前端分页弃用告警仍存在，未纳入本次安全修复。
- S15 自动化入口：`docs/测试/长期跟踪/scripts/studio_s15_soap_trace_security_probe.py`。后续涉及 `OpenServiceInvocationLogSupport`、WebService/SOAP 开放调用、完整日志查看/下载、协议转换 Trace 或 XML/SOAP 脱敏的修改，至少复跑该脚本、`DataIngestionInvocationLogSupportTest` 和 `WebServiceSupportTest`，并用浏览器复核协议转换访问日志页。
- 2026-06-22 已完成 S16 全量 ACL 深挖首个缺陷闭环：确认并修复项目普通成员可创建、发布、同步、删除全局元模型的问题，`BUG-S16-001` 已进入缺陷回归索引。
- S16 保留修复前复现元模型 `business:lt_reg_s16_acl:20260622020421` / `2068756927502548994`，以及修复后最近通过元模型 `business:lt_reg_s16_acl:20260622030400` / `2068771952015409154`；所有 S16 元模型长期保留不清理。
- S16 最近通过批次 `20260622030400`：`11 PASS / 0 FAIL / 0 BLOCKED / needFix 0`；S01 权限探针同步复跑 `25 PASS / 0 FAIL / 0 BLOCKED`；浏览器复核普通成员 `/metadata` 仅保留 `刷新`，无同步/新建/编辑/发布/删除写入口。
- S16 自动化入口：`docs/测试/长期跟踪/scripts/studio_s16_acl_deep_probe.py`。后续涉及 `MetaSchemaController`、元模型服务、`MetadataSchemasView.vue`、角色权限、全局/租户级配置写接口或前端按钮权限的修改，至少复跑该脚本、`StudioInitializationApiRegressionTest`、`studio_s01_permission_probe.py`，并用浏览器复核 `/metadata` 管理员和普通成员视图。
- 2026-06-22 已完成 S17 数据源当前表单 ACL 深挖：确认并修复接收项目成员可通过 `POST /datasources/test` 构造源项目数据源 `id`，复用源项目已加密密码完成连接测试的问题，`BUG-S17-001` 已进入缺陷回归索引。
- S17 保留复现批次 `20260622033528` 和修复后最近通过批次 `20260622034448`：长期数据源 `长期回归-客户经营画像数据源` / `2068077811652583425`，接收项目成员不可读取/保存态测试/发现该私有数据源，当前表单测试修复后返回 `403 Resource belongs to another project`。
- S17 自动化入口：`docs/测试/长期跟踪/scripts/studio_s17_datasource_current_form_acl_probe.py`。后续涉及 `DataSourceService`、数据源编辑页当前表单测试、敏感字段保留、数据源共享、数据源连接测试或发现模型逻辑的修改，至少复跑该脚本、`DataSourceApiRegressionTest`、`DataSourceServiceRegressionTest`、`studio_s01_permission_probe.py`，并用浏览器复核 `/datasources`。
- 2026-06-22 已完成 S18 关注目标 ACL 深挖：确认并修复关注接口可关注不存在目标或不可读跨项目工作流运行的问题，`BUG-S18-001` 已进入缺陷回归索引。
- S18 保留长期工作流 `长期回归-S18关注目标ACL流程` / `lt_reg_s18_follow_acl_workflow` / `2068792835765424129`、资源共享 `2068792849027813378`、成功运行 `2068792869038837761`；修复后接收项目成员可关注共享工作流，但不可关注源项目不可读工作流运行或不存在目标，`unfollow` 对不存在目标保持幂等。
- S18 自动化入口：`docs/测试/长期跟踪/scripts/studio_s18_follow_target_acl_probe.py`。后续涉及 `FollowSubscriptionService`、关注按钮、通知 fan-out、工作流/采集运行详情、资源共享撤销、目标删除或消息中心的修改，至少复跑该脚本、`FollowApiRegressionTest`、`studio_s01_permission_probe.py`，并用浏览器复核 `/notifications` 和 `/runs`。
- 2026-06-22 已完成 S19 通知 Fanout 禁用用户边界深挖：确认并修复禁用用户仍为接收项目 ACTIVE 成员时，资源共享通知 fan-out 会向其写入 `studio_notification` 的问题，`BUG-S19-001` 已进入缺陷回归索引。
- S19 保留禁用用户 `长期回归-S19通知禁用账号收件边界` / `lt_reg_s19_disabled_notice_guard` / `2068798093069635586`、接收项目成员 `2068798097205219330`、修复前复现工作流 `2068801954836631553` 与通知 `2068801967083999233`、修复后通过工作流 `2068802632619335682` 与共享 `2068802640785645570`。
- S19 自动化入口：`docs/测试/长期跟踪/scripts/studio_s19_notification_fanout_acl_probe.py`。后续涉及 `NotificationService`、资源共享通知、注册/访问申请通知、模型同步/采集/工作流运行通知、SSE 通知流或用户启停用逻辑的修改，至少复跑该脚本、`NotificationServiceRegressionTest`、`NotificationStreamSecurityRegressionTest`、S18 关注探针和 S01 权限探针，并用浏览器复核 `/notifications`。
- 2026-06-22 已完成 S20 共享关注通知 Fanout 深挖：确认并修复接收项目成员可关注共享工作流，但源项目工作流运行结束通知只查询源项目关注记录，导致共享关注者收不到运行通知的问题，`BUG-S20-001` 已进入缺陷回归索引；补充确认并修复共享关注者收到的通知目标仍指向不可访问源项目运行详情，导致点击链路不可用的问题，`BUG-S20-002` 已进入缺陷回归索引。
- S20 保留修复前复现工作流 `长期回归-S20共享关注通知一致性流程-20260622052317` / `2068807004120096769`、共享 `2068807016879169537`、运行 `2068807038337228801`；补充修复后通过工作流 `长期回归-S20共享关注通知一致性流程-20260622060425` / `2068817360901083137`、共享 `2068817373890842625`、启用共享期间运行 `2068817394682007553`、通知 `2068817417817747458`、共享禁用后运行 `2068817456359247873`；点击链路验证保留共享工作流 `长期回归-S20通知点击链路验证流程-20260622060734` / `2068818143105224706`、共享 `2068818155168043010`、通知 `2068818187380256770`。
- S20 自动化入口：`docs/测试/长期跟踪/scripts/studio_s20_shared_follow_notification_probe.py`。后续涉及 `FollowSubscriptionService`、`ExecutionEventService`、资源共享撤销/禁用、共享工作流或共享采集任务通知 fan-out 的修改，至少复跑该脚本、`FollowApiRegressionTest`、S18 关注探针、S19 通知探针和 S01 权限探针，并用浏览器复核 `/notifications` 与 `/runs`。
- 2026-06-22 已完成 S21 共享采集关注通知与采集指标深挖：确认并修复采集成功运行指标把 `TerminateRecord` 多算为 writer received 的问题，`BUG-S21-001` 已进入缺陷回归索引；确认并修复 RDBMS 逐行写入失败未收集脏数据导致失败指标不增长的问题，`BUG-S21-002` 已进入缺陷回归索引；同时恢复并加强 `BUG-M05-001` 的 MySQL insert 非 upsert 回归防护。
- S21 保留最终通过批次 `20260622150929`：源表 `lt_reg_s21_src_20260622150929`、目标表 `lt_reg_s21_tgt_20260622150929`、采集任务 `长期回归-S21共享采集通知链路-20260622150929` / `2068954581974482945`、共享 `2068954600647524354`、成功运行 `2068954655354626050`、重复主键失败运行 `2068954758010216449`、通知 `2068954699550007298`。
- S21 自动化入口：`docs/测试/长期跟踪/scripts/studio_s21_shared_collection_follow_notification_probe.py`。后续涉及 `Channel`、`CommonRdbmsWriter`、MySQL writer 写入模式、采集运行指标、共享采集任务关注通知或共享撤销后旧关注隔离的修改，至少复跑该脚本、`ChannelStatisticsTest`、`Mysql8WriterTest` 和 S02 数据正确性探针。
- S21 观察项 `OBS-S21-001`：接收项目成员在共享禁用后直达已不可读采集任务编辑页时，API 已 404，但前端停留空编辑表单且出现 Element Plus radio deprecation warning；建议 S22 以前端错误态和组件弃用告警为专项继续验证。
- 2026-06-22 已完成 S22 前端错误态与组件告警专项：确认并修复不可读/不存在编辑详情直达页停留空表单或暴露写入口的问题，`BUG-S22-001` 已进入缺陷回归索引；确认并修复 `no-vue3-cron` 隐藏语言按钮触发 Element Plus `type.text` 弃用告警的问题，`BUG-S22-003` 已进入缺陷回归索引。
- S22 同时确认工作流详情缺失对象接口仍与其他详情接口不一致，运行中 Server 返回 `HTTP 200 / SUCCESS / data:null`；代码已修复为 `NOT_FOUND` 并补 `WorkflowServiceRegressionTest`，`BUG-S22-002` 已进入缺陷回归索引，但实服 HTTP 需重启 `StudioServerApplication` 后复核。
- S22 最近通过：前端直达错误态 9 页浏览器矩阵 `9 PASS / 0 FAIL / 0 BLOCKED`，toolbar 均只保留“返回列表/刷新”，console warn/error 为 0；缺失对象 API 7 个接口返回 404，工作流详情实服复核 `BLOCKED(待重启)`；`npm run build` 与 `WorkflowServiceRegressionTest` 通过。
- S22 回归入口：`records/20260622-S22前端错误态与组件告警执行记录.md`、9 个直达错误态 URL 浏览器矩阵、缺失对象 API 矩阵、`npm run build`、`WorkflowServiceRegressionTest`。后续涉及编辑/详情页直达、工作流详情接口、CronExpressionPicker、Element Plus 组件兼容或 Vite 构建配置的修改，至少复跑这些入口。
- 2026-06-23 已完成 S23 全量 ACL 扩展与分页契约告警：ACL 扩展脚本批次 `20260623091253` 为 `20 PASS / 0 FAIL / 0 BLOCKED / needFix 0`；确认并修复前端 SDK 多个分页接口绕过 `normalizePageResult`，导致后端 `total:"0"` 字符串传入 Element Plus Pagination 后触发弃用告警的问题，`BUG-S23-001` 已进入缺陷回归索引。
- S23 保留字段映射规则 `2069227175755743233`、数据开发目录 `2069227184236625921`、脚本 `2069227194298761217` 作为 ACL 扩展长期夹具；浏览器复核协议转换访问日志、数据服务访问日志、数据接入运行日志和通知页均加载新构建资源，分页可见且时间戳之后无新增 warn/error。
- S23 自动化入口：`docs/测试/长期跟踪/scripts/studio_s23_acl_expansion_probe.py`。后续涉及 `frontend/packages/api-sdk/src/client.ts`、分页 PageResult 契约、访问日志/指标页、通知页、运维分页列表或 ACL 扩展覆盖接口的修改，至少复跑该脚本、`npm run build:web`，并用浏览器复核协议转换/数据服务/数据接入访问日志页。
- 2026-06-23 已完成 S24 共享资源执行 ACL 深挖：确认并修复接收项目成员可手动触发源项目共享工作流和共享采集任务，并在接收项目产生调度/运行副作用的问题，`BUG-S24-001` 已进入缺陷回归索引。
- S24 保留修复前批次 `20260623094218` 和修复后通过批次 `20260623095618`：工作流 `长期回归-S24共享执行边界流程-20260623095618` / `2069238108755156994`，采集任务 `长期回归-S24共享执行边界采集-20260623095618` / `2069238183573151746`，共享 `2069238205219954690`、`2069238274497273857`，源表/目标表 `lt_reg_s24_src_20260623095618` / `lt_reg_s24_tgt_20260623095618`。
- S24 自动化入口：`docs/测试/长期跟踪/scripts/studio_s24_shared_execution_acl_probe.py`。后续涉及 `DispatchService`、资源共享、工作流/采集/质量手动触发、运行记录或接收项目共享资源可见性的修改，至少复跑该脚本、`DispatchServiceOverlapRegressionTest`、`ClusterLockServiceRegressionTest` 和 S01 权限探针。
- 2026-06-29 已完成 S81 运行指标 Dashboard 运行记录源头聚合：确认并修复 `/run-metrics/query` 按时间窗读取全部运行记录明细后在 Java 内存聚合趋势和 TopN 的问题，`FIX-S81-001` 已进入缺陷回归索引。
- 2026-06-29 已完成 S82 统计分析源头聚合：确认并修复 `/statistics` 执行分析重复读取目标字段索引明细，并在概览、柱图、饼图、TopN 和趋势图链路中 Java 内存聚合的问题，`FIX-S82-001` 已进入缺陷回归索引。
- S82 回归入口：`DataModelStatisticsRegressionTest`、`DataModelStatisticsSourceSlimmingRegressionTest`、`npm run build:web`、build-nginx `/statistics` 执行分析 smoke。后续涉及 `DataModelStatisticsService`、`DataModelStatisticsWorkspaceService`、`DataModelAttrIndexMapper`、模型统计页或统计图表接口的修改，至少复跑这些入口。
- 2026-06-29 已完成 S83 数据开发首屏候选项懒加载：确认并修复 `/data-development` 首屏未打开脚本编辑器时仍预取 SQL 数据源候选和 Java 运行环境候选的问题，`FIX-S83-001` 已进入缺陷回归索引。
- S83 回归入口：`npm run build:web`、build-nginx `/data-development` 首屏、新建脚本、打开长期 Java 脚本详情 smoke。后续涉及 `DataDevelopmentView.vue`、数据开发脚本树、脚本编辑器候选项或顶部刷新动作的修改，至少复跑这些入口。
- 2026-06-29 已完成 S84 访问中心源头瘦身与写后刷新收敛：确认并修复 `/access/overview` 读取项目成员、项目、租户、申请整实体，以及申请/取消后重拉整个 overview 的问题，`FIX-S84-001` 已进入缺陷回归索引。
- S84 保留无项目账号 `lt_reg_s84_access_center_reviewer / LtRegS84!2026`，显示名 `长期回归-S84访问中心无项目验证员`，用于后续访问中心复测；本轮申请 `长期回归测试项目` 后已取消，申请流水保留。
- S84 回归入口：`WorkspaceAccessSourceSlimmingRegressionTest`、`npm run build:web`、build-nginx `/access-center` 无项目账号申请/取消 smoke。后续涉及 `WorkspaceAccessService`、`WorkspaceAccessView.vue`、项目访问申请、无项目路由或访问中心写后刷新时，至少复跑这些入口。
- 2026-06-29 已完成 S85 开放调用日志指针与协议 Trace 源头瘦身：确认并修复三类“完整日志”抽屉按 id 读取访问日志整实体、对象归档可用时仍读取 `systemLog`，以及协议转换 Trace 前置查询读取整行的问题，`FIX-S85-001` 已进入缺陷回归索引。
- S85 本轮未新增长期数据；浏览器复核长期项目 `/protocol-conversions/access-logs` 可达且 console warn/error 为 0，但当前筛选结果为 `共 0 条`，详情/完整日志点开实服验证需后续保留协议转换访问日志样例并重启 `StudioServerApplication` 加载本轮后端类。
- S85 回归入口：`OpenServiceInvocationLogSourceSlimmingRegressionTest`、`ProtocolConversionTraceSourceSlimmingRegressionTest`、build-nginx `/protocol-conversions/access-logs`。后续涉及 `OpenServiceInvocationLogService`、开放调用完整日志、协议转换访问日志 Trace、对象归档或历史兜底日志的修改，至少复跑这些入口，并优先复用 S14/S15 协议转换访问日志数据做详情抽屉验证。
- 2026-06-29 已完成 S86 运行日志抽屉概要与日志指针源头瘦身：确认并修复 `RunLogDrawer` 打开时调用全量 `/runs/{id}`、运行日志分页/下载先读取整条运行记录的问题，`FIX-S86-001` 已进入缺陷回归索引。
- S86 本轮未新增长期数据，复用采集运行 `2068957692399820801` / `长期回归-订单支付融合采集任务`；已用 IDEA `StudioServerApplication` 重启 Server 加载本轮代码，build-nginx `/collection-task-runs` 首行日志抽屉打开成功，console warn/error 为 0。
- S86 回归入口：`RunListSourceSlimmingRegressionTest`、`RunServiceRegressionTest`、`RunLogProxySourceSlimmingRegressionTest`、`npm run build:web`、build-nginx `/collection-task-runs` 日志抽屉、build-nginx API `/runs/{id}/summary` 和 `/runs/{id}/log`。后续涉及 `RunLogDrawer`、`RunService`、`RunLogProxyService`、运行记录详情/概要、运行日志分页/下载时，至少复跑这些入口。
- 2026-06-29 已完成 S87 数据开发脚本执行轮询与日志预览源头瘦身：确认并修复保存 Java/Python 脚本执行等待轮询读取调度任务/运行记录整实体，以及执行结果区隐式下载完整日志的问题，`FIX-S87-001` 已进入缺陷回归索引。
- S87 本轮未清理长期数据，复用 `长期回归-客户经营Java脚本.java` 执行成功；已用 IDEA `StudioServerApplication` 重启 Server，新进程 `33400` 监听 `18080`，build-nginx `/data-development` 执行结果区显示执行状态、耗时、执行日志和结果 JSON，console warn/error 为 0。
- S87 回归入口：`DataDevelopmentWorkerExecutionServiceTest`、`npm run build:web`、build-nginx `/data-development` 打开长期 Java 脚本并执行、构建产物确认数据开发 chunk 使用 `runs.getLog(...pageSizeBytes...)` 且无 `/log/download`。后续涉及 `DataDevelopmentWorkerExecutionService`、`DataDevelopmentView.vue`、脚本执行等待、执行结果日志预览或运行日志下载入口时，至少复跑这些入口。
- 2026-06-29 已完成 S88 模型同步任务列表与明细源头瘦身：确认并修复 `/model-sync-tasks` 表格分页读取完整任务实体，以及 `/model-sync-tasks/{id}/items` 明细分页先完整读取任务再完整读取明细实体的问题，`FIX-S88-001` 已进入缺陷回归索引。
- S88 本轮未新增或清理长期数据，复用模型同步任务 `长期回归-客户经营画像数据源 第1批` / `2068089488800440322`；已用 IDEA `StudioServerApplication` 重启 Server，新进程 `45336` 监听 `18080`，health 为 `UP`，build-nginx 模型同步任务列表和详情明细筛选均可达，console warn/error 为 0。
- S88 回归入口：`ModelSyncTaskServiceRegressionTest`、`ModelSyncTaskApiRegressionTest`、`npm run build:web`、build-nginx `/models?tab=sync-tasks`、`/models/sync-tasks/2068089488800440322` 和详情明细关键字筛选。后续涉及 `ModelSyncTaskService`、模型同步任务列表、模型同步任务详情明细或同步任务写后刷新时，至少复跑这些入口。
- 2026-06-29 已完成 S89 协议转换列表源头瘦身与删除刷新收敛：确认并修复 `/protocol-conversions` 表格分页读取 token、默认订阅、方法、payload 等非表格字段，以及列表删除后无条件重拉整个列表的问题，`FIX-S89-001` 已进入缺陷回归索引。
- S89 本轮未新增或清理长期数据，未执行真实删除动作；已用 IDEA `StudioServerApplication` 重启 Server，新进程 `35196` 监听 `18080`，health 为 `UP`，build-nginx `/protocol-conversions` 在 `长期回归测试项目` 下显示 3 行，console warn/error 为 0。
- S89 回归入口：`ProtocolConversionServiceListSourceSlimmingRegressionTest`、`SubscriptionTokenRotationRegressionTest`、`npm run build:web`、build-nginx `/protocol-conversions`。后续涉及 `ProtocolConversionService`、协议转换列表字段、列表删除刷新或协议转换订阅时，至少复跑这些入口。
- 2026-06-29 已完成 S90 数据服务与数据接入列表源头瘦身与删除刷新收敛：确认并修复 `/data-services`、`/data-ingestion-services` 表格分页读取请求、缓存、Token、订阅、模型/数据源 id 等非表格字段，以及列表删除后无条件重拉整个列表的问题，`FIX-S90-001` 已进入缺陷回归索引。
- S90 本轮未新增或清理长期数据，未执行真实删除动作；已在后端重启后确认 health 为 `UP`，Nacos 为 `127.0.0.1:8848`，build-nginx `/data-services` 在 `长期回归测试项目` 下显示 4 行，`/data-ingestion-services` 显示 6 行，console warn/error 均为 0。
- S90 回归入口：`DataServiceListSourceSlimmingRegressionTest`、`DataIngestionServiceListSourceSlimmingRegressionTest`、`SubscriptionTokenRotationRegressionTest`、`npm run build:web`、build-nginx `/data-services` 与 `/data-ingestion-services`。后续涉及 `DataServiceService`、`DataIngestionService`、两类开放服务列表字段、列表删除刷新或订阅时，至少复跑这些入口。
- 2026-06-29 已完成 S91 采集任务列表删除刷新收敛：确认并修复 `/collection-tasks` 删除采集任务后无条件重拉整个列表的问题，`FIX-S91-001` 已进入缺陷回归索引；采集任务列表源头字段继续由 S40 既有回归保障。
- S91 本轮未新增或清理长期数据，未执行真实删除动作；build-nginx `/collection-tasks` 在 `长期回归测试项目` 下显示 13 条长期采集任务，console warn/error 为 0。
- S91 回归入口：`CollectionTaskListSourceSlimmingRegressionTest`、`RunListSourceSlimmingRegressionTest`、`MetricsSourceSlimmingRegressionTest`、`npm run build:web`、build-nginx `/collection-tasks`。后续涉及 `CollectionTasksView.vue`、采集任务列表字段、列表删除刷新、运行列表或采集指标时，至少复跑这些入口。
- 2026-06-30 已完成 S92 质量规则任务列表删除刷新收敛：确认并修复 `/quality-rules` 单删/批删和 `/quality-tasks` 单删后无条件重拉整个列表的问题，`FIX-S92-001` 已进入缺陷回归索引；质量规则/任务列表源头字段继续由 S39 既有回归保障。
- S92 本轮未新增或清理长期数据，未执行真实删除动作；build-nginx `/quality-rules` 与 `/quality-tasks` 在 `长期回归测试项目` 下均可达，当前筛选结果为空态，console warn/error 均为 0。
- S92 回归入口：`QualityListSourceSlimmingRegressionTest`、`QualityRuleOptionsSourceSlimmingRegressionTest`、`npm run build:web`、build-nginx `/quality-rules` 与 `/quality-tasks`。后续涉及 `QualityRulesView.vue`、`QualityTasksView.vue`、质量列表字段、列表删除刷新或质量规则选项时，至少复跑这些入口。
- 2026-06-30 已完成 S93 工作流列表删除刷新收敛：确认并修复 `/workflows` 删除工作流后无条件重拉整个列表的问题，`FIX-S93-001` 已进入缺陷回归索引；工作流列表源头字段继续由 S41 既有回归保障。
- S93 本轮未新增或清理长期数据，未执行真实删除动作；build-nginx `/workflows` 在 `长期回归测试项目` 下显示 17 行长期工作流，console warn/error 为 0。
- S93 回归入口：`WorkflowListSourceSlimmingRegressionTest`、`npm run build:web`、build-nginx `/workflows`。后续涉及 `WorkflowsView.vue`、工作流列表字段、列表删除刷新、工作流发布或工作流触发时，至少复跑这些入口。
- 2026-06-30 已完成 S94 模型同步任务写后刷新收敛：确认并修复 `/models?tab=sync-tasks` 停止/删除模型同步任务后无条件重拉整个列表的问题，`FIX-S94-001` 已进入缺陷回归索引；模型同步任务列表源头字段继续由 S88 既有回归保障。
- S94 本轮未新增或清理长期数据，未执行真实停止/删除动作；build-nginx `/models?tab=sync-tasks` 在 `长期回归测试项目` 下显示 4 条长期模型同步任务，console warn/error 为 0。
- S94 回归入口：`ModelSyncTaskServiceRegressionTest`、`ModelSyncTaskApiRegressionTest`、`npm run build:web`、build-nginx `/models?tab=sync-tasks`。后续涉及 `ModelsView.vue`、模型同步任务列表字段、停止/删除刷新或模型同步任务详情明细时，至少复跑这些入口。
- 2026-06-30 已完成 S95 系统管理写后刷新收敛：确认并修复 `/system` 多个保存、审核、删除动作后无条件重拉对应分页列表的问题，`FIX-S95-001` 已进入缺陷回归索引；系统管理分页源头字段继续由 S64/S66/S67 既有回归保障。
- S95 保留长期数据变更：通过 build-nginx UI 编辑 `长期回归-业务协作租户` 描述，追加 `S95 验证系统管理编辑后行级刷新。`，页面更新成功且 console warn/error 为 0。
- S95 回归入口：`SystemManagementPaginationSourceSlimmingRegressionTest`、`SystemProjectWorkerViewRegressionTest`、`ResourceShareServiceTypeRegressionTest`、`npm run build:web`、build-nginx `/system` 租户编辑 smoke、源码复核 `SystemView.vue` 中 `patchRowById/removePaginatedSystemRow` 路径。后续涉及 `SystemView.vue`、系统管理保存/审核/删除、资源共享写后刷新或系统管理分页列表时，至少复跑这些入口。
- 2026-06-30 已完成 S96 模型列表删除刷新收敛：确认并修复 `/models` 模型列表删除成功后无条件重新执行 `handleDatasourceChange()` 的问题，`FIX-S96-001` 已进入缺陷回归索引；模型摘要列表源头字段继续由 S73/S79 既有回归保障。
- S96 本轮未新增或清理长期数据，未执行真实删除动作；后端 health 为 `UP`，build-nginx `/models` 可达，模型列表与模型同步任务页签可见，console warn/error 为 0，页面资产加载 `ModelsView-BAhGb1Qm.js`。
- S96 回归入口：`DataModelSqlHintSourceSlimmingRegressionTest`、`npm run build:web`、build-nginx `/models`、源码复核 `ModelsView.vue` 中 `deleteModel/reloadModelPageAfterDelete/loadModelsForSelectedDatasource` 路径。后续涉及 `ModelsView.vue`、模型列表字段、模型删除刷新、模型摘要列表或模型动态筛选时，至少复跑这些入口。后续写后刷新边界按“分页列表可重拉当前页，避免跨上下文重载和详情过取”执行。
- 2026-06-30 已完成 S97 质量指标模型筛选数据源探针源头瘦身：确认并修复 `/quality-metrics/model-options` 带 datasourceId 查询时，为校验数据源可读而调用完整 `dataSourceService.get()` 的隐性过度读取问题，`FIX-S97-001` 已进入缺陷回归索引。
- S97 本轮未新增或清理长期数据，未改前端；定向后端回归 `DataSourceListSourceSlimmingRegressionTest`、`DataModelSqlHintSourceSlimmingRegressionTest`、`QualityMetricsOptionsSourceSlimmingRegressionTest` 共 15 tests 全部通过。后续涉及 `DataSourceService`、`DataModelService.listMetricFilterOptionPage`、质量指标模型筛选下拉或数据源可读探针时，至少复跑这些入口。
- 2026-06-30 已完成 S98 数据开发脚本详情数据源摘要源头瘦身：确认并修复 `/data-development/scripts/{id}` 为脚本详情回填数据源名称/类型时调用完整 `dataSourceService.get()` 的隐性过度读取问题，`FIX-S98-001` 已进入缺陷回归索引。
- S98 本轮未新增或清理长期数据，未改前端；复用长期 SQL 脚本 `长期回归-客户价值查询.sql` 与数据源 `长期回归-客户经营画像数据源`。定向后端回归 `DataDevelopmentSourceSlimmingRegressionTest` 3 tests 全部通过；已用 IDEA `StudioServerApplication` 重启 Server，新进程 `42492`，health 为 `UP`；build-nginx `/data-development` 切换到 `长期回归测试项目` 后打开长期 SQL 脚本详情成功，console warn/error 为 0。
- 2026-07-01 已完成 S99 开放服务分页写动作刷新边界修正：确认并修复数据服务、数据接入服务、协议转换服务列表在发布/下线/删除后只本地 patch/remove，可能导致按 `updatedAt` 倒序的分页列表状态、排序和补位不一致的问题，`FIX-S99-001` 已进入缺陷回归索引。
- S99 本轮未新增或清理长期数据；`npm run build:web` 通过，`DataIngestionServiceListSourceSlimmingRegressionTest` 2 tests 全部通过；build-nginx 在 `长期回归测试项目` 下复核 `/data-services` 4 条、`/data-ingestion-services` 6 条、`/protocol-conversions` 3 条，console warn/error 均为 0。后续写后刷新边界按“分页列表允许重拉当前页以对齐后端状态，避免跨上下文重载和详情过取”执行。
- 2026-07-01 已完成 S100 模型按数据源入口数据源校验源头瘦身：确认并修复 `/models/datasource/{datasourceId}`、`/summaries`、`/options`、`/sql-hints` 为校验数据源可读而调用完整 `dataSourceService.get()` 的隐藏详情读取问题，`FIX-S100-001` 已进入缺陷回归索引。
- S100 本轮未新增或清理长期数据，复用 `长期回归-客户经营画像数据源` / `2068077811652583425`；`DataModelSqlHintSourceSlimmingRegressionTest` 7 tests 全部通过；build-nginx API `/models/datasource/2068077811652583425/summaries`、`/options`、`/sql-hints` 均返回长期数据；build-nginx `/models`、`/data-services/new`、`/data-ingestion-services/new`、`/data-development` 长期项目 smoke 均无 console warn/error。后续分页列表可重拉当前页，重点继续排查隐藏详情读取、大 JSON 源头读取和跨上下文刷新。
- 2026-07-01 已完成 S101 工作流运行详情运行记录源头瘦身：确认并修复 `/workflow-runs/{workflowRunId}` 为展示运行摘要、DAG 和节点状态而读取完整 `run_record`、`dispatch_task`，包含 `payload_json`、`result_json` 等运行时大字段的问题，`FIX-S101-001` 已进入缺陷回归索引。
- S101 本轮未新增或清理长期数据，复用工作流运行 `2069333667478331393`；`WorkflowRunSourceSlimmingRegressionTest` 3 tests 全部通过；build-nginx API `/workflow-runs?pageNo=1&pageSize=5` 返回 `total=50`，详情返回 `status=SUCCESS`、`nodeRuns=2`；build-nginx `/runs` 与 `/runs/2069333667478331393` 长期项目 smoke 均无 console warn/error。后续分页列表可重拉当前页，重点继续排查隐藏详情读取、大 JSON 源头读取和跨上下文刷新。
- 2026-07-01 已完成 S102 手动触发响应尾部详情源头瘦身：确认并修复工作流、采集任务、质量任务三类手动触发接口在 dispatch 成功后读取完整定义并返回给前端的问题，`FIX-S102-001` 已进入缺陷回归索引。
- S102 本轮保留新增运行记录：工作流运行 `2072056315507302402`、采集运行 `2072056377197137922`、质量运行 `2072056401436020737`；`ManualTriggerResponseSlimmingRegressionTest` 3 tests 全部通过，`npm run build:web` 通过，三类 trigger API 均返回 `success=true/data=null`；build-nginx `/workflows`、`/collection-tasks`、`/quality-tasks` 长期项目 smoke 均无 console warn/error。运行态观察项：新 Server PID `25000` 业务 API 可用，但 `/actuator/health` 返回 `503/DOWN`，后续可单独排查运行基线。
- 2026-07-01 已完成 S103 工作流运行详情画布定义源头瘦身：确认并修复 `/workflow-runs/{workflowRunId}` 在 S101 之后仍为只读 DAG 调用完整 `workflowService.get()` 的残留问题，`FIX-S103-001` 已进入缺陷回归索引。
- S103 本轮未新增长期数据，复用工作流运行 `2069333667478331393`；`WorkflowRunSourceSlimmingRegressionTest` 3 tests 全部通过，`npm run build:web` 通过；API 返回画布节点 2、边 1、节点运行 2，节点 `config` 为空且 `fieldMappings` 为 0；build-nginx `/runs/2069333667478331393` 显示正常，console warn/error 为 0。运行态观察项：新 Server PID `8216` 业务 API 可用，但 `/actuator/health` 仍为 `503/DOWN`。
- 2026-07-01 已完成 S104 工作流运行终止前置校验源头瘦身：确认并修复 `/workflow-runs/{workflowRunId}/terminate` 在写操作前为存在性校验调用完整 `get(workflowRunId)` 的隐藏详情读取问题，`FIX-S104-001` 已进入缺陷回归索引；保留终止后返回详情给当前详情页刷新状态，符合“分页/状态该拉取仍拉取”的边界。
- S104 本轮未新增长期数据，复用工作流运行 `2069333667478331393`；`WorkflowRunSourceSlimmingRegressionTest` 4 tests 全部通过，`mvn -pl studio-server -am -DskipTests package` 通过；build-nginx API 详情返回节点 2、边 1、节点运行 2，缺失运行终止返回 `404/NOT_FOUND`；build-nginx `/runs/2069333667478331393` 显示正常，console warn/error 为 0。运行态观察项：新 Server PID `29368` 业务 API 可用，但 `/actuator/health` 仍为 `503/DOWN`。
- 2026-07-01 已完成 S105 系统管理 Worker 组下拉源头瘦身：确认并修复 `绑定 Worker 组` 弹窗候选复用 `/system/project-workers` 重接口读取实例明细的问题，`FIX-S105-001` 已进入缺陷回归索引；保留 `/system/project-workers/page` 当前页分页和状态刷新，符合“分页该拉取还是拉取刷新状态”的边界。
- S105 本轮未新增长期数据，复用 Worker 组 `studio-online-worker-01` 和 `lt_reg_m09_backup_worker_group`；`SystemProjectWorkerViewRegressionTest` 6 tests 全部通过，`npm run build:web` 与 `mvn -pl studio-server -am -DskipTests package` 通过；build-nginx API `/system/project-worker-options` 返回 2 条且不含 `instances/hostName/podName/nodeName`，`/system/project-workers/page` 返回 `total=2`；build-nginx `/system?tab=workers` 弹窗下拉显示两条 Worker 组选项，console warn/error 为 0。运行态观察项：新 Server PID `11036` 业务 API 可用，但 `/actuator/health` 仍为 `503/DOWN`。
- 2026-07-01 已完成 S106 系统管理用户下拉源头瘦身：确认并修复租户成员、项目成员、申请/邀请弹窗用户下拉复用 `/users` 用户列表视图接口的问题，`FIX-S106-001` 已进入缺陷回归索引；保留 `/users` 和 `/users/page` 用户表格视图语义。
- S106 本轮未新增长期数据，复用已有长期用户；`SystemManagementPaginationSourceSlimmingRegressionTest` 5 tests 全部通过，`npm run build:web` 与 `mvn -pl studio-server -am -DskipTests package` 通过；build-nginx API `/users/options` 返回 18 条且字段仅 `id/username/displayName`，`/users/page` 返回 `total=18` 且表格字段保留；build-nginx `/system?tab=projectMembers` 打开“新增项目成员”弹窗，用户下拉显示 `Studio Admin (admin)` 和中文长期用户选项，console warn/error 为 0。新 Server PID `13016` 业务 API 可用。
- 2026-07-01 已完成 S107 系统管理资源共享目标项目下拉源头瘦身：确认并修复资源共享弹窗“共享到项目”下拉复用 `/system/projects` 项目管理列表接口的问题，`FIX-S107-001` 已进入缺陷回归索引；保留 `/system/projects` 和 `/system/projects/page` 项目管理列表语义。
- S107 本轮未新增长期数据，复用长期项目、接收项目和已有资源共享记录；`SystemManagementPaginationSourceSlimmingRegressionTest` 6 tests 全部通过，`npm run build:web` 与 `mvn -pl studio-server -am -DskipTests package` 通过；build-nginx API `/system/project-options` 返回 4 条且字段仅 `id/projectName`，`/system/projects` 仍保留管理字段；build-nginx `/system?tab=shares` 打开“资源共享”弹窗，目标项目下拉显示中文项目选项，console warn/error 为 0。新 Server PID `29976` 业务 API 可用。后续分页列表可重拉当前页，重点继续排查隐藏详情读取、大 JSON 源头读取和跨上下文刷新。
- 2026-07-01 已完成 S108 系统管理资源共享保存校验源头瘦身：确认并修复 `POST /system/resource-shares` 保存校验和共享通知标签解析读取完整资源实体的问题，`FIX-S108-001` 已进入缺陷回归索引；候选下拉和分页表格原有语义不变。
- S108 本轮保留新增共享记录：`2072094621460852738` 将 `长期回归-S10合同结构扰动查询服务` / `2068704693544779778` 共享到 `长期回归-资源接收项目` / `2068301893337849857`；另保留 PowerShell 中文匹配失败产生的观察记录 `2072094476396654593` 共享到 `Default Project`。`ResourceShareServiceTypeRegressionTest` 4 tests 全部通过，组合回归 `SystemManagementPaginationSourceSlimmingRegressionTest,ResourceShareServiceTypeRegressionTest` 10 tests 全部通过，`mvn -pl studio-server -am -DskipTests package` 通过；build-nginx API 保存返回 `SUCCESS`，build-nginx `/system?tab=shares` 切换“数据服务”后显示新增共享，console warn/error 为 0。新 Server PID `12708` 业务 API 可用。
- 2026-07-01 已完成 S109 系统管理成员用户水合源头瘦身：确认并修复成员、租户成员、项目成员申请/邀请分页只需用户 `id/username/displayName` 却读取用户整行的问题，`FIX-S109-001` 已进入缺陷回归索引；保留分页当前页拉取和状态刷新，不把正常列表请求作为问题。
- S109 本轮未新增或清理长期数据，复用长期项目成员和申请记录；`SystemManagementPaginationSourceSlimmingRegressionTest` 7 tests 全部通过，`mvn -pl studio-server -am -DskipTests package` 通过；build-nginx API `/system/project-members/page` 返回 `total=5`、`/system/project-member-requests/page` 返回 `total=2`，均无密码/认证来源字段；build-nginx `/system?tab=projectMembers` 与 `/system?tab=requests` 分别显示 5 条、2 条，console warn/error 为 0。新 Server PID `21544` 业务 API 可用。后续分页列表可重拉当前页，重点继续排查隐藏详情读取、大 JSON 源头读取和跨上下文刷新。
- 2026-07-01 已完成 S110 系统管理资源共享项目名称水合源头瘦身：确认并修复资源共享分页只需源项目/目标项目名称却读取项目整行的问题，`FIX-S110-001` 已进入缺陷回归索引；资源共享分页当前页拉取、资源标签轻量水合和项目管理列表语义不变。
- S110 本轮未新增或清理长期数据，复用已有资源共享记录；`SystemManagementPaginationSourceSlimmingRegressionTest` 7 tests 全部通过，`mvn -pl studio-server -am -DskipTests package` 通过；build-nginx API `/system/resource-shares/page?resourceType=DATA_SERVICE` 返回 `total=2` 且无项目编码/描述等字段；build-nginx `/system?tab=shares` 显示 1 条数据源共享，console warn/error 为 0。新 Server PID `29940` 业务 API 可用。后续分页列表可重拉当前页，重点继续排查隐藏详情读取、大 JSON 源头读取和跨上下文刷新。
