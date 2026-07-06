# Studio AI 助手上下文记忆与工具路由修复记录

- 验证时间：2026-07-05 03:35:50 +08:00
- Web 入口：http://127.0.0.1:8000/dfs/data-aggregation-studio/
- 后端入口：http://127.0.0.1:18080
- 元数据库：`jdbc:mysql://8.140.247.113:13309/data_aggregation_studio`
- 约束：仅验证 Web 端构建产物，不使用 desktop runtime。

## 问题

用户先查看数据源列表，再选中某个数据源，随后问“当前数据源有什么表”时，助手曾把请求误路由到数据源列表或模型列表，而不是数据源真实物理表发现。

本轮继续暴露出更系统的问题：数据源选择逻辑先于通用业务对象选择执行，且名称匹配过宽。用户说“默认使用模型/规则/任务/服务/运行记录 ...”时，如果对象名称和当前数据源共享 `assistant_coverage_*` 这类 token，数据源选择会抢先命中，导致非数据源对象也被错误解释成“默认使用数据源”。

这类问题不是单个关键词缺失，而是多轮对话中的工作记忆、实体引用解析、候选排序和意图路由不够严格：

- “数据源列表”里的“表”曾被误判为“表发现”。
- 数据源名称中的数字曾被误判为“第 N 个”序号引用。
- “查看当前可用数据源”曾被通用“查看当前对象详情”规则拦截。
- 非数据源对象选择曾被数据源选择抢路由。
- 只有数据源一个多轮用例不足以证明助手具备通用业务对象引用能力。

## 修复

- 前端助手新增结构化会话工作记忆，保存最近列表和已选对象：
  - 数据源、真实物理表、模型。
  - 通用业务对象：任务、服务、规则、运行记录、工作流、脚本、通知等。
- 增加通用实体引用解析：
  - 支持“当前/这个/刚才/第 N 个/名称片段”。
  - 序号引用必须出现“第/使用第/用第”，避免把对象名称中的数字当序号。
- 增加本地高置信意图帧：
  - “当前数据源有什么表/有哪些真实表/物理表/库表/表发现”直接映射到 `/datasources:discover`。
  - 未选中数据源时输出选择控件，不猜测。
- 收紧选择意图：
  - 只有“默认使用/选中/选择/就用/使用第 N 个”等明确选择信号才更新选中对象。
  - 明确提到模型、质量规则、采集任务、数据服务、运行记录等非数据源对象时，不进入数据源选择分支。
  - 没有“数据源”上下文时，数据源选择必须精确命中数据源名称或 ID，不能仅凭共享 token 抢路由。
- 更新 operation catalog / planner 语义：
  - `/datasources:discover` 表达为真实物理表/视图发现。
  - `/models` 表达为已登记模型列表/详情。
  - `models.datasourceOptions` 不再作为真实表发现的优先入口。
- 真实表发现意图支持名称片段：
  - 用户在“真实表/物理表/表发现”诉求中使用“名为/包含/搜索”、引号内容，或独立英文/下划线物理表标识符时，会将该片段传入 `/datasources:discover` 的 `keyword`。
  - 覆盖脚本对 JDBC 元数据库查询增加瞬时连接失败重试，避免外部 MySQL `CommunicationsException` 抖动造成非业务失败。
  - 覆盖脚本种子数据按业务列表排序分别处理：数据源名称使用 `assistant_coverage_ds_0000_*` 进入名称升序第一页；模型、规则、任务、服务和运行记录使用雪花同量级 ID 与本地时间，进入默认列表页。

## 回归覆盖

新增多轮 Web 覆盖 8 个场景：

| 场景 | 链路 | 关键断言 |
| --- | --- | --- |
| 数据源真实表发现 | `/datasources` 列表 -> 默认使用数据源 -> 当前数据源里名为某表的真实表 | 调用 `studio.feature.action`，`path=/datasources`，`action=discover`，`keyword=seededPhysicalLocator`，不重查 `/datasources`，不误调 `/models` |
| 模型歧义澄清 | `/models` 列表 -> 默认使用这个模型 | 多个模型候选存在时输出选择控件，不调用后端详情或动作接口猜测执行 |
| 模型序号引用 | `/models` 列表 -> 查看第 1 个模型详情 | “第 1 个”解析为最近模型列表第一条，调用 `studio.feature.get`，不重查 `/models`，不误调 `/datasources` |
| 模型详情引用 | `/models` 列表 -> 默认使用模型 -> 这个模型详情 | 调用 `studio.feature.get`，`path=/models`，`id=seededModelId` |
| 质量规则详情引用 | `/quality-rules` 列表 -> 默认使用质量规则 -> 这个质量规则详情 | 调用 `studio.feature.get`，`path=/quality-rules`，`id=seededQualityRuleId` |
| 采集任务详情引用 | `/collection-tasks` 列表 -> 默认使用采集任务 -> 这个采集任务详情 | 调用 `studio.feature.get`，`path=/collection-tasks`，`id=seededCollectionTaskId` |
| 数据服务详情引用 | `/data-services` 列表 -> 默认使用数据服务 -> 这个数据服务详情 | 调用 `studio.feature.get`，`path=/data-services`，`id=seededDataServiceId` |
| 运行记录详情引用 | `/runs` 列表 -> 默认使用运行记录 -> 这个运行记录详情 | 调用 `studio.feature.get`，`path=/runs`，`id=seededRunRecordId` |

本次完整验收的种子数据：

- 数据源：`assistant_coverage_ds_0000_1783193787348_622`，ID：`1700193787349093`
- 模型：`assistant_coverage_model_1783193787348_622`，ID：`2073491145509318834`
- 真实物理表：`assistant_coverage_model_1783193787348_622`，行数：1
- 质量规则：`AI助手覆盖预览规则 1783193787348_622`，ID：`2073491145509318835`
- 采集任务：`AI助手覆盖采集任务 1783193787348_622`，ID：`2073491145509318838`
- 数据服务：`AI助手覆盖数据服务 1783193787348_622`，ID：`2073491145509318839`
- 运行记录 ID：`2073491145509318840`
- 数据源真实表发现请求参数：`path=/datasources`，`action=discover`，`id=1700193787349093`，`keyword=assistant_coverage_model_1783193787348_622`，`pageNo=1`，`pageSize=20`

## 验证命令

### 追加：in-app browser 真实提问验证

- 验证时间：2026-07-05 22:05 +08:00
- 启动方式：

```powershell
cd C:\dev\ideaProject\cloud-parent-springboot3\DataAggregation\data-aggregation-studio\frontend
$env:STUDIO_ASSISTANT_WEB_SMOKE_KEEP_ALIVE='true'
npm run test:assistant-ui-web-server -w @studio/web
```

- keep-alive Web 入口：`http://127.0.0.1:5177/dfs/data-aggregation-studio/`
- 后端入口：`http://127.0.0.1:18181`
- 验证浏览器：Codex in-app browser
- 操作链路：
  1. 使用 `admin/admin123` 登录 Web 端。
  2. 打开 `/datasources`。
  3. 点击真实种子数据源 `assistant_web_smoke_ds`。
  4. 打开 AI 助手，切换 `Goal` + `中文`。
  5. 输入模糊提示：`把当前这个数据源的所有真实表都列出来。`
- 可见结果：
  - 助手先说明“我会使用当前页面选中的数据源读取真实物理表。”
  - 最终回答包含 `orders_all`、`customers_all`、`payments_all`。
  - 可见聊天内容不包含 `studio-assistant-protocol`。
  - 可见聊天内容不包含 `pageNo` / `pageSize`。
  - 过程面板显示 `Loop 已由 LLM 结束`，说明结束判断仍由 LLM 完成。

这次验证复用 Web-only 真后端 smoke 的临时 SQLite、真实 `studio-server`、mock OpenAI-compatible LLM、Vite Web 和临时 source plugin，不启动、不依赖 `studio-desktop-runtime` 进程。

```powershell
cd C:\dev\ideaProject\cloud-parent-springboot3\DataAggregation\data-aggregation-studio\frontend
npm run build -w @studio/web
```

结果：通过。

```powershell
cd C:\dev\ideaProject\cloud-parent-springboot3\DataAggregation\data-aggregation-studio\backend
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl studio-infra -am "-Dtest=AssistantLlmPlannerTest,AssistantStudioOperationRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：28 个后端定向测试通过。

```powershell
cd C:\dev\ideaProject\cloud-parent-springboot3\DataAggregation\data-aggregation-studio\frontend
$env:STUDIO_ASSISTANT_WEB_BASE_URL='http://127.0.0.1:8000/dfs/data-aggregation-studio/'
$env:STUDIO_ASSISTANT_BACKEND_BASE_URL='http://127.0.0.1:18080'
$env:STUDIO_ASSISTANT_COVERAGE_START_VITE='false'
$env:STUDIO_ASSISTANT_DB_URL='jdbc:mysql://8.140.247.113:13309/data_aggregation_studio?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true'
$env:STUDIO_ASSISTANT_DB_USERNAME='root'
$env:STUDIO_ASSISTANT_DB_PASSWORD='951753'
npm run test:assistant-assistive-coverage -w @studio/web
```

结果：

- 读取链路：27/27 通过。
- 多轮记忆链路：8/8 通过。
- 非变更动作链路：8/8 通过。
- 变更确认链路：16/16 通过。
- 动作目录真实执行覆盖：24/113。

报告：

- `frontend/apps/web/target/assistant-assistive-coverage/assistant-assistive-coverage-report.md`
- `frontend/apps/web/target/assistant-assistive-coverage/assistant-assistive-coverage-report.json`
- `frontend/apps/web/target/assistant-assistive-coverage/assistant-ui-real-assistive-coverage.png`
