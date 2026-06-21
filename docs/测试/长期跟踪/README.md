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
