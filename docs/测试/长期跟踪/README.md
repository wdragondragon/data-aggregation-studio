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
