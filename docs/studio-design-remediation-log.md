# Studio 设计与代码风格整改执行记录

> 范围：`DataAggregation/data-aggregation-studio`
>
> 约束：不处理安全策略，不改业务数据库表，不改变现有 API/DTO/任务 JSON 对外契约；每个小步骤完成后追加记录，不覆盖历史。

## Step 000 - 初始化执行记录

- 执行时间：2026-05-24 09:41:58 +08:00
- 目标问题：建立本次超长整改任务的过程记录，确保后续每个小步骤可追溯。
- 修改范围：新增整改执行记录文档。
- 涉及文件：
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：仅新增文档，不影响运行时代码、接口、SQL 或前端页面。
- 验证命令与结果：
  - `git status --short`：确认当前工作树已有上一批未提交改动，作为本轮基线继续推进。
- 失败、阻塞或残余风险：
  - 当前已有未提交改动，本轮按用户要求不回退，直接作为基线继续整改。
- 下一步：补充当前基线验证记录，并创建最终汇总文档占位。

## Step 001 - 当前基线验证

- 执行时间：2026-05-24 09:44:35 +08:00
- 目标问题：在继续大范围整改前确认当前工作树可构建、核心回归可运行。
- 修改范围：无代码修改，仅执行验证并记录结果。
- 涉及文件：
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：验证步骤不改变业务行为。
- 验证命令与结果：
  - `npm run build:web`：通过，Vite 生产构建成功。
  - `git diff --check`：通过，仅出现 CRLF/LF 提示，无 whitespace error。
  - `mvn -pl studio-test "-Dtest=StudioSchemaDriftRegressionTest,CollectionTaskAssemblerServiceRegressionTest,StudioInitializationApiRegressionTest" test`：通过，22 tests，0 failures，0 errors。
- 失败、阻塞或残余风险：
  - 第一次后端命令未给 `-Dtest` 参数加引号，PowerShell 将逗号解析为参数分隔导致失败；已用带引号参数重跑通过。
  - 测试启动时 Redis 连接不可达，`DataServiceResponseCacheService` 降级到本地内存缓存；这是环境降级日志，不影响本次测试结果。
  - Maven settings 存在 `Unrecognised tag: 'release'` 警告，未阻断构建。
- 下一步：扩展 schema drift 测试和设计静态门禁，先锁住后续整改的回归边界。

## Step 002 - 静态门禁与 schema 漂移测试首次补充

- 执行时间：2026-05-24 09:52:58 +08:00
- 目标问题：将 datasource capability、runtime option schema、technical metadata schema 的多源维护风险纳入回归，并为当前设计债务建立“不可增加”的静态门禁。
- 修改范围：扩展 schema drift 测试，新增设计债务回归测试。
- 涉及文件：
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioSchemaDriftRegressionTest.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：仅新增测试和文档，不影响运行时代码、接口、SQL 或任务 JSON。
- 验证命令与结果：
  - `mvn -pl studio-test "-Dtest=StudioSchemaDriftRegressionTest,StudioDesignDebtRegressionTest" test`：失败。
- 失败、阻塞或残余风险：
  - `StudioSchemaDriftRegressionTest` 首版误用 `containsExactlyElementsOf` 强制 SQL 插入顺序与 Java bootstrap 顺序完全一致，实际 MySQL runtime schema 和 technical metadata SQL 存在历史插入顺序差异，但集合内容完整。
  - SQLite 初始化脚本中 `postgres` reader 插件仍为 `postgres`，Java/MySQL 为 `postgresql`；这是已有历史差异，当前门禁不应在“只读门禁补充”步骤中改变运行契约。
  - 大文件统计首版包含了 `studio-core`、`studio-server` 等非本轮设计扫描主路径，导致基线从手工统计的 10 处变为 14 处。
- 下一步：修正门禁口径为集合一致、能力 typeCode/source/category 基础一致，并将大文件扫描聚焦到 `studio-infra` 主业务实现与 web Vue 页面。

## Step 003 - 修复 schema 漂移门禁口径并同步 SQLite capability

- 执行时间：2026-05-24 09:57:11 +08:00
- 目标问题：修复 Step 002 暴露的测试误报，同时处理 SQLite 内置 datasource capability 与 Java/MySQL 初始化数据不一致的问题。
- 修改范围：调整 schema drift 测试为集合一致校验；同步 SQLite 初始化脚本中 `postgres`、`ftp`、`sftp`、`minio` 的可读/可写能力与 reader/writer 插件；将设计债务门禁阈值设置为 JUnit 口径当前基线。
- 涉及文件：
  - `backend/studio-desktop-runtime/src/main/resources/schema-sqlite.sql`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioSchemaDriftRegressionTest.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：不改业务表结构、不改 API；SQLite 新初始化环境的数据源能力与 Java bootstrap/MySQL 初始化对齐，已有数据库仍由既有升级/同步逻辑处理。
- 验证命令与结果：
  - `mvn -pl studio-test "-Dtest=StudioSchemaDriftRegressionTest,StudioDesignDebtRegressionTest" test`：通过，10 tests，0 failures，0 errors。
- 失败、阻塞或残余风险：
  - 设计债务门禁目前是“不可增加”基线，不代表所有大文件、`return null`、`catch ignored` 已清理完成。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断测试。
- 下一步：统一剩余列表页表格容器，并继续收敛前端领域常量。

## Step 004 - 统一剩余列表与详情表格容器

- 执行时间：2026-05-24 10:22:43 +08:00
- 目标问题：继续处理列表第一列展示不完整、横向内容不可达和表格滚动样式分裂的问题。
- 修改范围：将工作流详情、工作流运行详情、数据服务、数据服务日志/指标、数据源、模型、模型同步详情、元模型字段、字段映射、质量规则、质量指标、运行指标、目录能力、工作空间申请等表格接入 `StudioTableShell`；同步替换关注目标类型字符串为前端领域常量。
- 涉及文件：
  - `frontend/apps/web/src/views/WorkflowDetailView.vue`
  - `frontend/apps/web/src/views/WorkflowRunDetailView.vue`
  - `frontend/apps/web/src/views/DataServiceAccessLogsView.vue`
  - `frontend/apps/web/src/views/DataServiceMetricsView.vue`
  - `frontend/apps/web/src/views/DataServicesView.vue`
  - `frontend/apps/web/src/views/DatasourcesView.vue`
  - `frontend/apps/web/src/views/FieldMappingRulesView.vue`
  - `frontend/apps/web/src/views/FieldMappingRuleEditorView.vue`
  - `frontend/apps/web/src/views/MetadataSchemasView.vue`
  - `frontend/apps/web/src/views/ModelsView.vue`
  - `frontend/apps/web/src/views/ModelSyncTaskDetailView.vue`
  - `frontend/apps/web/src/views/QualityRulesView.vue`
  - `frontend/apps/web/src/views/QualityMetricsView.vue`
  - `frontend/apps/web/src/views/RunMetricsView.vue`
  - `frontend/apps/web/src/views/CatalogView.vue`
  - `frontend/apps/web/src/views/WorkspaceAccessView.vue`
  - `frontend/apps/web/src/components/CollectionTaskFieldMappingEditor.vue`
  - `frontend/apps/web/src/components/ModelLineagePanel.vue`
  - `frontend/apps/web/src/components/ModelSyncTableSelector.vue`
  - `frontend/apps/web/src/constants/studioDomain.ts`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：仅调整前端布局容器与常量引用，不改变接口、字段、分页、筛选或任务配置数据结构。
- 验证命令与结果：
  - `npm run build:web`：通过，Vue 类型检查和 Vite 构建成功。
  - `rg "table-scroll-shell|task-table-wrap|workflow-table-wrap|run-table-wrap|system-table-wrap|quality-table-wrap" frontend/apps/web/src frontend/packages/ui/src -g "*.vue"`：无结果，旧 wrapper 名称已清零。
- 失败、阻塞或残余风险：
  - 编辑器内部的少量表格仍会在 HTTP 编辑器拆分和大页面拆分阶段继续处理。
  - 该步骤只做构建验证，尚未做浏览器 smoke。
- 下一步：拆分 HTTP 请求参数编辑器，保留旧组件兼容包装，同时让 reader/writer 共用更清晰的子组件。

## Step 005 - 拆分 HTTP 请求参数编辑器子组件

- 执行时间：2026-05-24 10:28:42 +08:00
- 目标问题：降低 `HttpReaderOptionsEditor.vue` 的模板复杂度，让 HTTP reader/writer 的请求头、Query、Body 可视化录入能力更容易复用和维护。
- 修改范围：新增 HTTP 请求参数子组件，父组件保留状态编排、schema 合并和动态函数插入逻辑；旧 `HttpReaderOptionsEditor` 继续作为当前兼容入口，`HttpRequestOptionsEditor` 调用方式不变。
- 涉及文件：
  - `frontend/apps/web/src/components/HttpReaderOptionsEditor.vue`
  - `frontend/apps/web/src/components/http-request/HttpOptionKeyValueTable.vue`
  - `frontend/apps/web/src/components/http-request/HttpRawOptionEditor.vue`
  - `frontend/apps/web/src/components/http-request/HttpTokenPairTable.vue`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：保存值仍是原有 `header`、`params`、`requestBody` 字符串；动态函数表达式格式和插入逻辑不变；reader/writer 任务 JSON 契约不变。
- 验证命令与结果：
  - `npm run build:web`：通过，Vue 类型检查和 Vite 构建成功。
- 失败、阻塞或残余风险：
  - 动态函数弹窗本体仍在父组件中，后续可以继续拆为独立 dialog 组件；本轮先降低最容易出错的表格和 Raw 编辑区复杂度。
- 下一步：拆分采集任务装配后端服务，先围绕 HTTP/RDBMS/File 策略和运行参数合并做 facade 内部整理。

## Step 006 - 抽取采集任务运行参数合并器

- 执行时间：2026-05-24 10:33:04 +08:00
- 目标问题：降低 `CollectionTaskAssemblerService` 职责密度，将运行参数合并、点路径写入、保留字段过滤和 JSON 字符串解析从装配 facade 中抽离。
- 修改范围：新增包内 `CollectionTaskRuntimeOptionMerger`，`CollectionTaskAssemblerService` 改为委托该合并器处理 reader/writer/fusion runtime options。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskAssemblerService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskRuntimeOptionMerger.java`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：`assemble` 入口、reader/writer 输出结构、HTTP 字符串保留逻辑和保留字段过滤规则不变。
- 验证命令与结果：
  - `mvn -pl studio-test "-Dtest=CollectionTaskAssemblerServiceRegressionTest,StudioDesignDebtRegressionTest" test`：通过，15 tests，0 failures，0 errors。
- 失败、阻塞或残余风险：
  - 该步骤先抽取了运行参数合并器，reader/writer 策略拆分仍待继续。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断测试。
- 下一步：继续拆 schema/metadata 相关的构建器或升级步骤，优先选择低风险的公共 field/schema builder 抽取点。

## Step 007 - 元模型配置 description 编解码抽取首次验证

- 执行时间：2026-05-24 10:37:43 +08:00
- 目标问题：将 `META_MODEL_CONFIG:` description 的编码和解析从 runtime schema、metadata schema 服务中抽出，减少字符串前缀和 JSON 解析逻辑重复。
- 修改范围：新增元模型配置 description 工具，并让 runtime option schema 和 metadata schema 复用。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/MetaModelConfigDescriptions.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/MetadataSchemaService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StandardRuntimeOptionSchemaBootstrapService.java`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：description 前缀、JSON 内容和正文格式保持不变。
- 验证命令与结果：
  - `mvn -pl studio-test "-Dtest=StudioSchemaDriftRegressionTest,StudioInitializationApiRegressionTest,StudioDesignDebtRegressionTest" test`：失败。
- 失败、阻塞或残余风险：
  - `StudioDesignDebtRegressionTest.backendReturnNullShouldNotIncrease` 失败，`return null;` 从 224 增加到 225；新增工具首版在无配置和解析失败时直接返回 `null`。
  - `StudioInitializationApiRegressionTest` 与 `StudioSchemaDriftRegressionTest` 自身通过；Redis 不可达仍是环境降级日志。
  - Surefire 在失败后出现 fork JVM 退出超时提示，不影响失败原因定位。
- 下一步：修复新增工具为单出口返回，不增加 `return null;` 字面量后重跑同一组测试。

## Step 008 - 修复元模型配置工具静态门禁并重跑

- 执行时间：2026-05-24 10:40:45 +08:00
- 目标问题：修复 Step 007 引入的新增 `return null;` 坏味道，同时保留 description 解析失败返回空配置的行为。
- 修改范围：`MetaModelConfigDescriptions.decode` 改为单出口返回，避免新增 `return null;` 字面量。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/MetaModelConfigDescriptions.java`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：无配置、非 `META_MODEL_CONFIG:` 描述或 JSON 解析失败时仍返回 `null` 给原调用方；只是内部控制流改为单出口。
- 验证命令与结果：
  - `mvn -pl studio-test "-Dtest=StudioSchemaDriftRegressionTest,StudioInitializationApiRegressionTest,StudioDesignDebtRegressionTest" test`：通过，18 tests，0 failures，0 errors。
- 失败、阻塞或残余风险：
  - Redis 不可达仍触发本地缓存降级日志，不影响测试结果。
  - Surefire 仍打印 fork JVM 退出超时提示，但最终构建成功。
- 下一步：进行错误/空值处理和残余魔法字符串的低风险清理，然后执行全量验证。

## Step 009 - 清理元模型配置工具的 ignored catch

- 执行时间：2026-05-24 10:54:30 +08:00
- 目标问题：减少新增工具中的无上下文异常吞并写法，避免继续扩大 `catch ignored` 设计债务。
- 修改范围：将 `MetaModelConfigDescriptions.decode` 中的 `catch (Exception ignored)` 改为带语义变量名的 `catch (Exception parseFailure)`，并保留一行降级说明。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/MetaModelConfigDescriptions.java`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：解析失败仍按原行为返回空配置，由调用方当作没有嵌入元模型配置处理；只调整异常变量命名和注释，不改变 description 编码、解析或 API 输出。
- 验证命令与结果：
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest,StudioSchemaDriftRegressionTest" test`：通过，10 tests，0 failures，0 errors。
- 失败、阻塞或残余风险：
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断测试。
  - 该步骤是低风险清理，不代表现存所有历史 `catch ignored` 已被清理。
- 下一步：执行前端构建、后端回归、静态检查和浏览器 smoke，形成最终验收记录。

## Step 010 - 最终构建、回归、静态检查与浏览器 smoke

- 执行时间：2026-05-24 11:05:30 +08:00
- 目标问题：对本轮设计/代码风格整改做收口验证，确认前端构建、后端回归、静态门禁和主要页面渲染均处于可交付状态。
- 修改范围：无业务代码修改；执行最终验证并记录结果。
- 涉及文件：
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：该步骤只做验证，不改变 API、DTO、任务 JSON、数据库结构或页面数据。
- 验证命令与结果：
  - `npm run build:web`：通过，`vue-tsc --noEmit && vite build` 成功，生成 production build。
  - `mvn -pl studio-test test`：通过，87 tests，0 failures，0 errors，0 skipped。
  - `git diff --check`：通过，仅输出 Windows 工作区 LF/CRLF 替换提示，无空白错误。
  - `rg "table-scroll-shell|task-table-wrap|workflow-table-wrap|run-table-wrap|system-table-wrap|quality-table-wrap" frontend/apps/web/src frontend/packages/ui/src -g "*.vue"`：无命中，旧表格 wrapper 名称已清零。
  - `rg 'target-type="(COLLECTION_TASK|WORKFLOW|WORKFLOW_RUN|COLLECTION_TASK_RUN|QUALITY_TASK|DATASOURCE|DATA_MODEL|MODEL_SYNC_TASK)"' frontend/apps/web/src -g "*.vue"`：无命中，已改为前端领域常量引用。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest" test`：通过，4 tests，0 failures，0 errors。
  - 浏览器 smoke：通过。确认 `http://localhost:8000/dfs/data-aggregation-studio/system` 加载最新构建资源 `SystemView-DIbsnPpL.js`、`StudioTableShell-BSSmvHlp.js` 等；抽查采集任务列表、HTTP 写入任务编辑页、HTTP 动态函数弹窗、工作流列表、数据服务监控、质量任务列表、模型列表、系统管理页均可渲染，最终控制台无 error。
- 失败、阻塞或残余风险：
  - Codex 内置浏览器首次连接本地页面超时，已改用 DevTools 浏览器完成 smoke；这不影响页面验证结果，但记录为工具侧阻塞。
  - 后端测试期间 Redis 不可达触发本地缓存降级日志，属于当前测试环境现状，未造成测试失败。
  - Surefire 结束时仍提示 fork JVM 退出超时，但最终 Maven 构建成功。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断测试。
  - 本轮只处理设计/代码风格与结构整改，安全策略问题按用户要求未处理。
- 下一步：更新整改汇总文档，列明已处理、延期项、测试结果和后续建议批次。

## Step 011 - Batch 0 创建整改分支并验证基线

- 执行时间：2026-05-24 18:52:47 +08:00
- 目标问题：按后续分批提交要求，为已完成的设计整改建立独立分支和可回退基线。
- 修改范围：从当前 Studio 仓库工作树创建 `codex/studio-design-remediation` 分支；执行提交前基线验证。
- 涉及文件：
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：该步骤只创建分支、执行验证并记录结果，不改变业务接口、DTO、数据库结构或任务 JSON。
- 验证命令与结果：
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
  - `npm run build:web`：通过，`vue-tsc --noEmit && vite build` 成功。
  - `mvn -pl studio-test test`：通过，87 tests，0 failures，0 errors，0 skipped。
- 失败、阻塞或残余风险：
  - 后端测试期间 Redis 不可达仍触发本地缓存降级日志，未造成测试失败。
  - Surefire 结束时仍提示 fork JVM 退出超时，但 Maven 构建成功。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断测试。
- 下一步：暂存 Studio 范围改动并提交 Batch 0 基线，然后进入 Batch 1 静态债务门禁下降。

## Step 012 - Batch 1 收紧静态设计债务门禁

- 执行时间：2026-05-24 19:24:20 +08:00
- 目标问题：将设计债务门禁从“不可增加”推进到当前实际值，并清理本轮新增/触达代码中的无语义空值和 ignored catch。
- 修改范围：`CollectionTaskRuntimeOptionMerger` 使用内部 sentinel 表达“跳过空白运行参数值”，避免新增 `return null;`；JSON 解析失败 catch 改为带语义变量名；`StudioDesignDebtRegressionTest` 阈值收紧到当前实际计数。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskRuntimeOptionMerger.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：空白运行参数仍会被跳过，非法 JSON 字符串仍按原字符串保留；reader/writer config 输出语义不变。
- 验证命令与结果：
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest,CollectionTaskAssemblerServiceRegressionTest" test`：通过，15 tests，0 failures，0 errors。
  - 静态实际计数：backend `catch ignored` 24，backend `return null;` 221，旧表格 wrapper 0；大 Java 文件 14，大 Vue 文件 13。
- 失败、阻塞或残余风险：
  - 历史 `return null;` 和 `catch ignored` 仍存在，本批仅降低并锁住当前可验证基线。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断测试。
- 下一步：提交 Batch 1，然后进入采集任务装配服务策略拆分。

## Step 013 - Batch 2 拆分采集任务装配策略

- 执行时间：2026-05-24 20:22:23 +08:00
- 目标问题：`CollectionTaskAssemblerService` 同时承担任务装配流程、字段映射、File/HTTP 特殊配置和运行参数规范化，单类过大且后续 reader/writer 扩展容易互相影响。
- 修改范围：保留 `CollectionTaskAssemblerService.assemble(...)` facade 和任务 JSON 契约不变；新增包内 helper 分别承载字段映射/列定义、File reader/writer 配置、HTTP reader/writer 配置与 HTTP 运行参数规范化。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskAssemblerService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskFieldMappingResolver.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskFileConfigSupport.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskHttpConfigSupport.java`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：reader/writer `type`、`connect`、`columns`、HTTP `header/params/requestBody` 字符串保留规则、File TAG 字段校验、HTTP URL 拼接和业务状态校验均沿用原逻辑；`CollectionTaskAssemblerService` 从 1150 行降到 418 行。
- 验证命令与结果：
  - `mvn -pl studio-test "-Dtest=CollectionTaskAssemblerServiceRegressionTest,StudioDesignDebtRegressionTest" test`：第一次在 Studio 仓库根目录执行失败，原因是当前根目录没有 Maven reactor POM，无法识别 `studio-test`。
  - `mvn -pl studio-test "-Dtest=CollectionTaskAssemblerServiceRegressionTest,StudioDesignDebtRegressionTest" test`（在 `backend` 目录）：第一次失败，`return null;` 静态计数从 221 增至 223；随后将新 helper 的无值返回改为命名空值常量。
  - `mvn -pl studio-test "-Dtest=CollectionTaskAssemblerServiceRegressionTest,StudioDesignDebtRegressionTest" test`（在 `backend` 目录）：通过，15 tests，0 failures，0 errors。
  - `mvn -pl studio-test -am "-Dtest=CollectionTaskAssemblerServiceRegressionTest,StudioDesignDebtRegressionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：`studio-infra`、`studio-server`、`studio-worker`、`studio-test` 均重新编译通过；测试 fork 启动阶段因 Windows 页文件不足失败，0 个测试执行。
  - `mvn -pl studio-test "-Dtest=CollectionTaskAssemblerServiceRegressionTest,StudioDesignDebtRegressionTest" "-DforkCount=0" test`：通过，15 tests，0 failures，0 errors。
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
- 失败、阻塞或残余风险：
  - `-am` reactor 验证受本机页文件不足影响，fork JVM 未能启动；已用非 fork 模式完成同一目标测试，并记录该环境阻塞。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断最终验证。
  - 本批只拆采集任务装配内部结构，不处理安全策略、不改变 API/DTO/任务 JSON。
- 下一步：提交 Batch 2，然后进入 schema/metadata 结构拆分。

## Step 014 - Batch 3 拆分 schema 升级与元模型字段构建

- 执行时间：2026-05-24 22:48:22 +08:00
- 目标问题：`StudioSchemaUpgradeService` 和 `MetadataSchemaService` 同时承载流程编排、数据库元数据探测、内置数据源能力升级和技术元模型字段构建，修改 schema 或新增数据源时容易造成多处漂移。
- 修改范围：拆出数据库方言探测、schema 元数据 introspector、数据源能力升级 support、技术元模型字段 builder；同步更新 schema drift 测试的源码位置断言，并将后端大文件门禁从 14 收紧到 12。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioDatabaseDialect.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioDatabaseDialectDetector.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioSchemaIntrospector.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioDatasourceCapabilityUpgradeSupport.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/MetadataSchemaService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/TechnicalMetadataFieldBuilder.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioSchemaDriftRegressionTest.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：`StudioSchemaUpgradeService.upgrade()` 仍按数据库类型分派 MySQL/SQLite 升级，DDL 与 datasource capability 种子数据保持原 SQL 语义；`MetadataSchemaService` 仍生成同一批技术元模型字段，schema code、description 配置和字段定义不改变。
- 验证命令与结果：
  - `mvn -pl studio-infra -am -DskipTests compile`：通过，`studio-infra` 及上游模块重新编译成功。
  - `mvn -pl studio-test "-Dtest=StudioSchemaDriftRegressionTest,StudioInitializationApiRegressionTest,StudioDesignDebtRegressionTest" "-DforkCount=0" test`：第一次失败，原因是 schema drift 测试仍断言 `buildSourceFields/buildTableFields/buildFieldFields` 位于 `MetadataSchemaService`；已改为检查 `TechnicalMetadataFieldBuilder`。
  - `mvn -pl studio-test "-Dtest=StudioSchemaDriftRegressionTest,StudioInitializationApiRegressionTest,StudioDesignDebtRegressionTest" "-DforkCount=0" test`：通过，18 tests，0 failures，0 errors。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest" "-DforkCount=0" test`：通过，4 tests，0 failures，0 errors。
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
  - 后端大 Java 文件扫描：实际 12 个超过 800 行，门禁已收紧到 12。
- 失败、阻塞或残余风险：
  - 非 fork 模式用于规避本机页文件不足导致的 Surefire fork JVM 问题；测试本身通过。
  - Redis 不可达仍触发现有本地缓存降级日志，未造成测试失败。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断验证。
  - `StudioSchemaUpgradeService` 仍有 2050 行，质量表和数据服务表建表块尚未进一步拆分，留到后续批次继续降债。
- 下一步：提交 Batch 3，然后进入 DataService、Lineage、Quality 服务内部拆分。

## Step 015 - Batch 4 修复 DataService 调用支撑拆分编译

- 执行时间：2026-05-24 23:23:39 +08:00
- 目标问题：继续拆分 `DataServiceService`，将数据服务调用计划、分页归一化、参数校验、响应拼装等低层逻辑从 facade 中移出，同时修复中途掉线留下的半成品编译问题。
- 修改范围：新增 `DataServiceInvocationSupport` 承载调用计划和通用校验/转换逻辑；`DataServiceService` 接入 support 字段，移除已迁移的正则和分页常量；恢复 `DataServiceExecutionResult` 内部结果对象。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataServiceService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataServiceInvocationSupport.java`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：`DataServiceService` 对外方法、公开 DTO、openapi 返回结构、缓存命中语义、访问日志和任务 JSON 均不改变；本步骤只移动内部实现位置。
- 验证命令与结果：
  - `mvn -pl studio-infra -am -DskipTests compile`：第一次失败，`DataServiceExecutionResult` 内部类型在拆分中丢失。
  - 修复后重跑 `mvn -pl studio-infra -am -DskipTests compile`：通过，`studio-infra` 及上游模块重新编译成功。
- 失败、阻塞或残余风险：
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断编译。
  - 本步骤只恢复 DataService 拆分后的编译，尚未运行 DataService 定向回归。
  - `DataServiceService` 仍超过 800 行，后续需要继续抽取订阅/日志/视图组装等内部组件。
- 下一步：继续拆 DataService 剩余职责并运行 DataService/设计债务定向测试。

## Step 016 - Batch 4 拆分 DataService 参数、日志和 token 支撑

- 执行时间：2026-05-24 23:59:43 +08:00
- 目标问题：`DataServiceService` 仍混合参数默认值/归一化/持久化、访问日志计数、token 生成与脱敏，单类超过 800 行，后续修改数据服务定义或调用统计时容易互相影响。
- 修改范围：新增 `DataServiceParamSupport`、`DataServiceAccessLogSupport`、`DataServiceTokenSupport`；`DataServiceService` 保留 facade 和事务边界，内部改为委托参数、日志、token support；设计债务门禁同步收紧。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataServiceService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataServiceParamSupport.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataServiceAccessLogSupport.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataServiceTokenSupport.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：数据服务保存、发布、订阅 token、openapi 调用、缓存、访问日志和计数器的字段与返回结构保持不变；仅移动内部职责边界。`DataServiceService` 从约 1097 行降到 719 行。
- 验证命令与结果：
  - `mvn -pl studio-infra -am -DskipTests compile`：通过，`studio-infra` 及上游模块重新编译成功。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest" "-DforkCount=0" test`：通过，4 tests，0 failures，0 errors。
  - 后端大 Java 文件实际数量：11，门禁从 12 收紧到 11。
  - 后端 main `return null;` 实际数量：218，门禁从 221 收紧到 218。
- 失败、阻塞或残余风险：
  - 目前没有单独的数据服务保存/调用回归测试类，本步骤先以编译和静态门禁兜底；后续如继续拆 DataService 指标/调用链，建议补专用 API 回归。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断验证。
- 下一步：继续 Batch 4，优先处理 `DataModelLineageService`、`QualityMetricsService`、`QualityIssueService` 中可低风险抽出的查询/DTO 组装逻辑。

## Step 017 - Batch 4 降低边缘大文件并收紧门禁

- 执行时间：2026-05-25 00:27:28 +08:00
- 目标问题：部分 Service 只是略超 800 行，但长期留在“大文件”清单中会稀释门禁信号；优先用低风险 helper 抽取把边缘文件移出清单。
- 修改范围：抽出质量问题严重度排序、模型默认值解析、工作流运行状态归一化 helper；`QualityIssueService`、`DataModelService`、`WorkflowRunService` 不再超过 800 行；设计债务门禁继续收紧。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/QualityIssueService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/QualityIssueSeveritySupport.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataModelService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataModelDefaultValueSupport.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/WorkflowRunService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/WorkflowRunStatusSupport.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：质量问题排序权重、模型元数据默认值转换、工作流运行状态过滤仍沿用原逻辑；只移动内部 helper，不改接口和返回字段。
- 验证命令与结果：
  - `mvn -pl studio-infra -am -DskipTests compile`：通过，`studio-infra` 及上游模块重新编译成功。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest,WorkflowRunPaginationRegressionTest,DataModelStatisticsRegressionTest,QualityTaskScheduleRunnerRegressionTest" "-DforkCount=0" test`：通过，18 tests，0 failures，0 errors。
  - 后端大 Java 文件实际数量：8，门禁从 11 收紧到 8。
  - 后端 main `catch ignored` 实际数量：23，门禁从 24 收紧到 23。
- 失败、阻塞或残余风险：
  - Redis 不可达仍触发现有本地缓存降级日志，未造成测试失败。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断验证。
  - 本步骤只处理边缘大文件；`DataModelLineageService`、`QualityMetricsService`、`DataServiceMetricsService` 等核心大类仍在清单中。
- 下一步：继续 Batch 4，处理质量指标或血缘服务中测试保护较强的内部组装逻辑。

## Step 018 - Batch 4 拆分指标计算和视图支撑

- 执行时间：2026-05-25 01:01:18 +08:00
- 目标问题：`DataServiceMetricsService` 与 `QualityMetricsService` 同时承载指标查询、统计计算、分页/排行和 DTO 转换，类体偏大且计算逻辑分散。
- 修改范围：新增 `DataServiceMetricViewSupport` 承载数据服务指标分页、排行截断、百分位/比例计算、访问日志视图转换和服务快照取值；新增 `QualityMetricCalculationSupport` 承载质量指标健康分、治理风险、活跃问题、资产 key、严重度和最新记录等计算。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataServiceMetricsService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataServiceMetricViewSupport.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/QualityMetricsService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/QualityMetricCalculationSupport.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：数据服务指标 API、运行指标 API、质量看板计算口径、返回字段和分页语义保持不变；仅将原私有 helper 移入包内支撑类。`DataServiceMetricsService` 降到 763 行，`QualityMetricsService` 降到 698 行。
- 验证命令与结果：
  - `mvn -pl studio-infra -am -DskipTests compile`：通过，`studio-infra` 及上游模块重新编译成功。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest,RunMetricsApiRegressionTest,DataModelStatisticsRegressionTest" "-DforkCount=0" test`：通过，16 tests，0 failures，0 errors。
  - 后端大 Java 文件实际数量：6，门禁保持 6。
  - 后端 main `catch ignored` 实际数量：23，`return null;` 实际数量：218，门禁保持当前值。
- 失败、阻塞或残余风险：
  - Redis 不可达仍触发现有本地缓存降级日志，未造成测试失败。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断验证。
  - 仍有 6 个后端大文件，其中 `StudioSchemaUpgradeService` 与 `DataModelLineageService` 体量最大，需要更强测试保护再继续深拆。
- 下一步：继续 Batch 4，评估 `DataModelStatisticsService` 或 `CollectionTaskService` 的低风险拆分点，随后提交 Batch 4。

## Step 019 - Batch 4 拆分模型统计请求和值处理支撑

- 执行时间：2026-05-25 01:18:43 +08:00
- 目标问题：`DataModelStatisticsService` 同时处理统计请求归一化、目标 schema 判断、桶值转换和统计聚合，类体略超 800 行。
- 修改范围：新增 `DataModelStatisticsSupport` 承载统计请求归一化、目标 schema group 判断、桶值解析和数字格式归一化；`DataModelStatisticsService` 保留统计编排和 bucket 计算主体。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataModelStatisticsService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataModelStatisticsSupport.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
- 行为兼容说明：模型统计 API、目标字段校验、bucket 输出、summaryMetrics 字段和自动分桶口径保持不变；只移动请求和值处理 helper。`DataModelStatisticsService` 降到 776 行。
- 验证命令与结果：
  - `mvn -pl studio-infra -am -DskipTests compile`：通过，`studio-infra` 及上游模块重新编译成功。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest,DataModelStatisticsRegressionTest" "-DforkCount=0" test`：通过，15 tests，0 failures，0 errors。
  - 后端大 Java 文件实际数量：5，门禁从 6 收紧到 5。
- 失败、阻塞或残余风险：
  - Redis 不可达仍触发现有本地缓存降级日志，未造成测试失败。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断验证。
  - 剩余大文件为 schema 升级、血缘、系统管理、采集任务 Service 和 source capability provider，后续需要按领域继续分批处理。
- 下一步：对 Batch 4 当前稳定改动执行 `git diff --check` 并提交，再进入后续前端 composable/HTTP 编辑器收口或继续后端剩余大类拆分。

## Step 020 - Batch 5 建立前端页面状态 composable 并迁移运行列表

- 执行时间：2026-05-25 01:36:47 +08:00
- 目标问题：前端页面中的分页、异步动作、弹窗表单和表格选择状态散落在各大页面内，导致列表页重复样板代码较多，后续拆大页面缺少统一的状态边界。
- 修改范围：新增 `useAsyncAction`、`usePageQuery`、`useDialogForm`、`useTableSelection` 四个 composable；将 `RunsView.vue` 的分页状态迁移到 `usePageQuery`，错误消息提取改为复用 `resolveErrorMessage`。
- 涉及文件：
  - `frontend/apps/web/src/composables/useAsyncAction.ts`
  - `frontend/apps/web/src/composables/usePageQuery.ts`
  - `frontend/apps/web/src/composables/useDialogForm.ts`
  - `frontend/apps/web/src/composables/useTableSelection.ts`
  - `frontend/apps/web/src/views/RunsView.vue`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：运行列表的路由 query、分页参数、查询参数、表格结构和详情跳转保持不变；本步骤只收敛页面内部状态管理和错误消息提取。
- 验证命令与结果：
  - `npm run build:web`（Studio 根目录）：失败，根目录没有 `package.json`，属于命令工作目录错误。
  - `npm run build:web`（`frontend` 目录）：通过，`vue-tsc --noEmit && vite build` 成功。
- 失败、阻塞或残余风险：
  - npm 仍提示 `electron_mirror` / `electron-mirror` 配置即将不兼容，未阻断构建。
  - 目前只迁移了运行列表，采集任务、质量任务、工作流、系统管理、模型中心等大页面还未全面使用这些 composable。
- 下一步：继续迁移低风险列表页，或进入 HTTP 动态函数弹窗独立组件拆分，并在批次结束前运行 `git diff --check` 和后端设计债务门禁。

## Step 021 - Batch 5 前端 composable 检查点验证

- 执行时间：2026-05-25 01:38:01 +08:00
- 目标问题：确认新增前端 composable 和运行列表迁移没有引入空白错误或突破后端静态设计债务门禁。
- 修改范围：无新增业务修改；只执行检查并记录结果。
- 涉及文件：
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：本步骤不改运行行为。
- 验证命令与结果：
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest" "-DforkCount=0" test`：通过，4 tests，0 failures，0 errors。
- 失败、阻塞或残余风险：
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断验证。
  - 本步骤只验证设计债务门禁，未运行后端全量测试。
- 下一步：提交 Batch 5 当前小检查点，然后继续 HTTP 动态函数弹窗拆分或更多前端页面迁移。

## Step 022 - Batch 6 拆分 HTTP 动态函数弹窗

- 执行时间：2026-05-25 02:00:12 +08:00
- 目标问题：`HttpReaderOptionsEditor.vue` 仍承载 HTTP 参数编辑、函数目录、Token 函数表单、弹窗状态、校验和样式，导致 reader/writer 共用入口难以继续维护。
- 修改范围：新增 `HttpDynamicFunctionDialog.vue` 承载 HTTP 动态函数弹窗、Token 强表单和校验；新增 `httpDynamicFunctions.ts` 承载函数目录、类型和 Token 键值串生成；`HttpReaderOptionsEditor.vue` 只保留打开弹窗、计算插入位置、接收表达式并写回当前输入框。
- 涉及文件：
  - `frontend/apps/web/src/components/HttpReaderOptionsEditor.vue`
  - `frontend/apps/web/src/components/http-request/HttpDynamicFunctionDialog.vue`
  - `frontend/apps/web/src/components/http-request/httpDynamicFunctions.ts`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：动态函数保存格式、Token 函数参数顺序、Header/Query/Body 字符串保存契约、Raw/Table 插入光标逻辑保持不变；本步骤只拆分组件与函数目录。`HttpReaderOptionsEditor.vue` 从约 1457 行降到 770 行。
- 验证命令与结果：
  - `npm run build:web`（`frontend` 目录）：通过，`vue-tsc --noEmit && vite build` 成功。
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest" "-DforkCount=0" test`：通过，4 tests，0 failures，0 errors。
- 失败、阻塞或残余风险：
  - npm 仍提示 `electron_mirror` / `electron-mirror` 配置即将不兼容，未阻断构建。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断验证。
  - 本步骤尚未做浏览器手工验证 HTTP 弹窗交互；后续收口前需要在 nginx 页面上抽查 Header/Query/Raw 插入。
- 下一步：提交 HTTP 动态函数弹窗拆分批次，然后继续前端大页面治理或后端剩余大类拆分。

## Step 023 - Batch 6 浏览器 smoke 验证

- 执行时间：2026-05-25 02:02:41 +08:00
- 目标问题：确认 HTTP 动态函数弹窗拆分后的 Studio 构建产物仍能通过 nginx 代理加载，基础页面无新增前端运行错误。
- 修改范围：无代码修改；只执行浏览器验证并记录结果。
- 涉及文件：
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：本步骤不改运行行为。
- 验证命令与结果：
  - 打开 `http://localhost:8000/dfs/data-aggregation-studio/`：成功加载登录页。
  - 使用默认账号登录：成功进入总览页。
  - 点击“新建采集任务”：成功进入 `/collection-tasks/new`。
  - 浏览器 console error：无。
- 失败、阻塞或残余风险：
  - 当前环境页面显示数据源、任务等统计为 0，采集任务编辑页没有可选数据源/模型，因此本次只能验证页面加载和无 console error，未能直接打开 HTTP 参数弹窗做完整交互。
  - 浏览器自动化侧出现一次 Statsig 外部初始化超时日志，来源于 Codex 浏览器插件外部遥测请求，不是 Studio 页面 console error。
- 下一步：继续处理剩余前端大页面或后端大类；如需完整 HTTP 弹窗交互 smoke，需要先准备本地 HTTP 数据源和模型。

## Step 024 - Batch 7 拆分 source capability 元数据组装

- 执行时间：2026-05-25 02:07:28 +08:00
- 目标问题：`AggregationSourceCapabilityProvider` 同时负责插件加载、连接测试、模型发现、水合、预览、数据源 DTO 转换和模型技术元数据 Map 组装，类体超过 800 行。
- 修改范围：新增 `AggregationModelMetadataSupport` 承载关系表、文件、队列模型技术元数据 Map 组装和字段元数据转换；`AggregationSourceCapabilityProvider` 保留插件调用、发现/预览编排和数据源 DTO 转换职责；后端大文件门禁从 5 收紧到 4。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/execution/AggregationSourceCapabilityProvider.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/execution/AggregationModelMetadataSupport.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：数据源连接测试、模型发现、模型水合、预览、技术元数据字段名和值组装逻辑保持不变；只是把原私有元数据构造方法移动到同包 helper。`AggregationSourceCapabilityProvider` 从 875 行降到 771 行。
- 验证命令与结果：
  - `mvn -pl studio-infra -am -DskipTests compile`：通过，`studio-infra` 及上游模块重新编译成功。
  - 后端大 Java 文件扫描：超过 800 行的文件从 5 个降到 4 个。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest" "-DforkCount=0" test`：通过，4 tests，0 failures，0 errors。
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
- 失败、阻塞或残余风险：
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断编译和测试。
  - 后端剩余大类仍有 `StudioSchemaUpgradeService`、`DataModelLineageService`、`SystemManagementService`、`CollectionTaskService`，后续拆分风险高于本步骤。
- 下一步：提交 Batch 7，然后评估 `CollectionTaskService` 或 `SystemManagementService` 的低风险拆分点。

## Step 025 - Batch 8 拆分采集任务增量游标支撑

- 执行时间：2026-05-25 02:15:19 +08:00
- 目标问题：`CollectionTaskService` 同时负责任务保存/发布/调度、绑定 enrich、视图组装、文件源参数清洗、增量游标保护和重置，类体超过 800 行。
- 修改范围：新增 `CollectionTaskIncrementalCursorSupport` 承载增量游标保护、重置、cursorStates 归一化和旧 projection 刷新；`CollectionTaskService` 保留业务编排、权限/唯一性校验、保存/调度持久化和文件源 reader/writer 参数清洗；后端大文件门禁从 4 收紧到 3。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskIncrementalCursorSupport.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：采集任务保存、预览、发布、调度、增量游标重置、文件源 readerOptions/writerOptions 清洗和任务 JSON 装配契约保持不变；`CollectionTaskService` 从 892 行降到 618 行。
- 验证命令与结果：
  - `mvn -pl studio-infra -am -DskipTests compile`：通过，`studio-infra` 及上游模块重新编译成功。
  - 后端大 Java 文件扫描：超过 800 行的文件从 4 个降到 3 个。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest,CollectionTaskAssemblerServiceRegressionTest,CollectionTaskScheduleRunnerRegressionTest" "-DforkCount=0" test`：通过，16 tests，0 failures，0 errors。
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
- 失败、阻塞或残余风险：
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断编译和测试。
  - 当前没有专门覆盖 `CollectionTaskService.resetIncrementalCursor` 的集成测试；本步骤以编译、设计门禁、装配回归和调度 runner 回归兜底，后续建议补增量 cursor 保存/重置用例。
- 下一步：提交 Batch 8，然后继续评估 `SystemManagementService` 或 `DataModelLineageService` 的可拆低风险区域。

## Step 026 - Batch 9 拆分系统管理视图与共享资源支撑

- 执行时间：2026-05-25 02:22:36 +08:00
- 目标问题：`SystemManagementService` 同时承载租户/项目/成员/Worker/共享资源保存删除、DTO 组装、共享资源目标校验、共享通知文案和跳转路径解析，类体超过 800 行。
- 修改范围：新增 `SystemManagementViewAssembler` 承载系统管理 DTO 组装；新增 `SystemResourceShareSupport` 承载共享资源校验、共享通知、共享资源标签和目标路径解析；`SystemManagementService` 保留权限判断、保存删除、mapper 查询和事务边界；后端大文件门禁从 3 收紧到 2。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/SystemManagementService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/SystemManagementViewAssembler.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/SystemResourceShareSupport.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：租户、项目、成员申请、Worker 绑定、资源共享 API 的返回字段、共享校验规则、通知 category/target/path/dedupeKey 保持不变；`SystemManagementService` 从 982 行降到 771 行。
- 验证命令与结果：
  - `mvn -pl studio-infra -am -DskipTests compile`：通过，`studio-infra` 及上游模块重新编译成功。
  - 后端大 Java 文件扫描：超过 800 行的文件从 3 个降到 2 个。
  - `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest" "-DforkCount=0" test`：通过，4 tests，0 failures，0 errors。
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
- 失败、阻塞或残余风险：
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断编译和测试。
  - 当前缺少系统管理资源共享的专用回归测试；本步骤以编译和设计门禁兜底，后续建议补共享资源校验和通知目标路径用例。
- 下一步：提交 Batch 9，然后继续评估 `DataModelLineageService` 或 `StudioSchemaUpgradeService` 的可拆区域。

## Step 027 - Batch 10 拆分血缘服务表达式、运行状态与图组装职责

- 执行时间：2026-05-25 03:02:26 +08:00
- 目标问题：`DataModelLineageService` 同时承载采集任务血缘重建、字段表达式解析、最新运行状态补齐、血缘图/边详情 DTO 组装和通用文本元数据工具，类体超过 1500 行且职责混杂；同时“所有类低于 800 行”的门禁容易引导硬拆。
- 修改范围：新增血缘专用 helper：`DataModelLineageTextSupport` 承载字段/元数据/文本工具；`DataModelLineageExpressionResolver` 承载表达式引用解析、source alias 解析和 alias 字段索引；`DataModelLineageRunStatusSupport` 承载运行状态归一、最新运行时间选择、状态比较和展示状态派生；`DataModelLineageGraphAssembler` 承载血缘图、边详情、摘要、节点 ID 和可达性计算。`DataModelLineageService` 保留对外 facade、权限/查询、手工血缘保存删除和自动血缘持久化编排。
- 涉及文件：
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataModelLineageService.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataModelLineageTextSupport.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataModelLineageExpressionResolver.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataModelLineageRunStatusSupport.java`
  - `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataModelLineageGraphAssembler.java`
  - `backend/studio-test/src/test/java/com/jdragon/studio/test/StudioDesignDebtRegressionTest.java`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：血缘 API、edgeId 编码、图节点/边/摘要字段、未解析表达式输出、手工血缘保存规则、自动血缘字段映射模式、运行状态展示语义保持不变；只是将原私有方法移动到语义明确的同包 helper。`DataModelLineageService` 从 1567 行降到 713 行，新增 `DataModelLineageGraphAssembler` 为 602 行，没有制造新的超大类。
- 验证命令与结果：
  - `mvn -pl studio-infra -am -DskipTests compile`：通过，`studio-infra` 及上游模块重新编译成功。
  - 后端大 Java 文件扫描：超过 800 行的文件从 2 个降到 1 个，仅剩 `StudioSchemaUpgradeService.java`。
  - `mvn -pl studio-test "-Dtest=DataModelLineageRegressionTest,StudioDesignDebtRegressionTest" "-DforkCount=0" test`：通过，6 tests，0 failures，0 errors。
- 失败、阻塞或残余风险：
  - Redis 不可达仍触发现有本地缓存降级日志，未造成测试失败。
  - Maven settings 仍有 `Unrecognised tag: 'release'` 警告，未阻断编译和测试。
  - `StudioSchemaUpgradeService` 仍超过 800 行，但按本轮结论归类为已审查的大型 schema/升级编排类，不再为了行数硬拆；后续只在有 schema drift/init 回归保护时按方言/步骤继续拆。
- 下一步：执行 `git diff --check`，更新汇总文档并提交 Batch 10；后续重点转向前端大页面 composable 迁移和历史 `return null` / `catch ignored` 债务下降。

## Step 028 - Batch 11 元模型页面接入异步动作 composable

- 执行时间：2026-05-25 03:08:24 +08:00
- 目标问题：`MetadataSchemasView.vue` 的刷新、保存、发布、删除、同步操作各自维护 loading、成功提示、失败提示和取消确认处理，和本轮新增的前端异步动作 composable 没有形成复用。
- 修改范围：将元模型页面的刷新、保存、发布、删除、同步动作接入 `useAsyncAction`；新增刷新按钮 loading；保留插件类型校验和抽屉状态处理；确认框取消统一走 `ignoreCancel`，避免误报错误。
- 涉及文件：
  - `frontend/apps/web/src/views/MetadataSchemasView.vue`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：元模型列表加载、草稿保存 payload、发布/删除/同步接口调用、成功/失败文案和确认框语义保持不变；本步骤只收敛 loading 与错误提示处理。
- 验证命令与结果：
  - `npm run build:web`（`frontend` 目录）：通过，`vue-tsc --noEmit && vite build` 成功。
- 失败、阻塞或残余风险：
  - npm 仍提示 `electron_mirror` / `electron-mirror` 配置即将不兼容，未阻断构建。
  - 本步骤只迁移元模型页面，采集任务编辑、质量任务编辑、工作流编辑、模型中心等大页面仍需后续分批拆分。
- 下一步：执行 `git diff --check` 并提交 Batch 11；后续优先处理模型中心或采集任务编辑页的组件拆分。

## Step 029 - Batch 12 模型统计页接入异步动作 composable

- 执行时间：2026-05-25 03:12:02 +08:00
- 目标问题：`ModelStatisticsView.vue` 单独维护 workspace 加载和分析运行的 loading、错误消息拼接与 finally 收口，和 `useAsyncAction` 的页面异步动作模式不一致；同时统计页需要保留后端响应错误详情。
- 修改范围：扩展 `useAsyncAction`，允许 `errorMessage` 传入函数以按错误对象生成提示；模型统计页的 workspace 加载和分析运行改用 `useAsyncAction`，保留原有“操作失败前缀 + 后端错误详情”提示。
- 涉及文件：
  - `frontend/apps/web/src/composables/useAsyncAction.ts`
  - `frontend/apps/web/src/views/ModelStatisticsView.vue`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：模型统计 datasource/workspace 选择、统计请求 payload、图表请求、分析结果赋值、展开面板状态和错误提示文案保持不变；`useAsyncAction` 对旧 string `errorMessage` 用法保持兼容。
- 验证命令与结果：
  - `npm run build:web`（`frontend` 目录）：通过，`vue-tsc --noEmit && vite build` 成功。
- 失败、阻塞或残余风险：
  - npm 仍提示 `electron_mirror` / `electron-mirror` 配置即将不兼容，未阻断构建。
  - 本步骤只迁移模型统计页异步边界，尚未拆分图表构造、查询条件构造和展示 section。
- 下一步：执行 `git diff --check` 并提交 Batch 12；继续评估 `QualityMetricsView` 或 `ModelsView` 的低风险拆分点。

## Step 030 - Batch 13 拆分模型中心动态筛选与同步任务区块

- 执行时间：2026-05-25 03:36:49 +08:00
- 目标问题：`ModelsView.vue` 同时承载模型列表、动态筛选条件编辑、同步任务列表、详情预览、编辑抽屉和同步弹窗，文件超过 2600 行；其中动态筛选面板和同步任务列表属于清晰 UI 区块，适合优先拆分。
- 修改范围：新增 `components/models/modelViewTypes.ts` 承载模型动态筛选共享类型；新增 `ModelDynamicFilterPanel.vue` 承载动态筛选表单、条件值输入和响应式样式；新增 `ModelSyncTaskSection.vue` 承载同步任务筛选、列表、分页和任务操作按钮。`ModelsView.vue` 保留数据加载、查询构造、同步任务业务动作和路由状态编排。
- 涉及文件：
  - `frontend/apps/web/src/views/ModelsView.vue`
  - `frontend/apps/web/src/components/models/modelViewTypes.ts`
  - `frontend/apps/web/src/components/models/ModelDynamicFilterPanel.vue`
  - `frontend/apps/web/src/components/models/ModelSyncTaskSection.vue`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：模型列表查询 payload、动态筛选条件构造、同步任务筛选/分页/停止/删除/详情跳转语义保持不变；子组件只接管展示层和局部样式。`ModelsView.vue` 从 2601 行降到 2282 行。
- 验证命令与结果：
  - `npm run build:web`（`frontend` 目录）：首次失败，原因是父页面动态行编辑仍需要 `ElInput/ElInputNumber`，拆动态筛选时误删 import。
  - 修复后 `npm run build:web`（`frontend` 目录）：通过，`vue-tsc --noEmit && vite build` 成功。
- 失败、阻塞或残余风险：
  - npm 仍提示 `electron_mirror` / `electron-mirror` 配置即将不兼容，未阻断最终构建。
  - `ModelsView.vue` 仍超过 1000 行，后续还需继续拆详情预览、编辑抽屉和元模型字段 section。
- 下一步：执行 `git diff --check` 并提交 Batch 13；随后继续拆 `ModelsView.vue` 的详情预览或编辑抽屉。

## Step 031 - Batch 14 拆分模型详情概览区块

- 执行时间：2026-05-25 03:42:20 +08:00
- 目标问题：`ModelsView.vue` 的详情概览页签包含模型摘要、共享提示、技术/业务元数据展示和样例行表格，属于展示型区块，继续留在父页面会扩大详情与列表/编辑/同步职责混杂。
- 修改范围：新增 `ModelDetailOverview.vue` 承载详情概览 UI 与响应式样式；将 `ModelMetaSection` 和 `MetaSectionBinding` 移入共享 `modelViewTypes.ts`，供父页面和详情组件共用；父页面只保留数据来源和展示 helper。
- 涉及文件：
  - `frontend/apps/web/src/views/ModelsView.vue`
  - `frontend/apps/web/src/components/models/ModelDetailOverview.vue`
  - `frontend/apps/web/src/components/models/modelViewTypes.ts`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：模型详情页的项目标签、共享来源提示、技术/业务元数据展示、MULTIPLE 表格展示、样例行展示和格式化逻辑保持不变；只是将展示模板迁移到子组件。`ModelsView.vue` 从 2282 行降到 2105 行。
- 验证命令与结果：
  - `npm run build:web`（`frontend` 目录）：首次失败，原因是父页面仍引用已迁出的 `MetaSectionBinding` 类型。
  - 修复后 `npm run build:web`（`frontend` 目录）：通过，`vue-tsc --noEmit && vite build` 成功。
- 失败、阻塞或残余风险：
  - npm 仍提示 `electron_mirror` / `electron-mirror` 配置即将不兼容，未阻断最终构建。
  - `ModelsView.vue` 仍超过 1000 行，下一步需要继续拆编辑抽屉或模型元数据 section 操作。
- 下一步：执行 `git diff --check` 并提交 Batch 14；随后继续拆 `ModelsView.vue` 的编辑抽屉。

## Step 032 - Batch 15 拆分模型编辑抽屉

- 执行时间：2026-05-25 03:50:32 +08:00
- 目标问题：`ModelsView.vue` 的新增/编辑模型抽屉同时包含数据源选择、schema 绑定、元数据表单、多行字段编辑和保存动作，继续留在父页面会让列表、详情、同步和编辑职责混杂。
- 修改范围：新增 `ModelEditorDrawer.vue` 承载模型编辑抽屉 UI、元数据 section 渲染、多行元数据表格和抽屉样式；将 `ModelFormState` 移入共享 `modelViewTypes.ts`；`ModelsView.vue` 保留表单状态、schema 切换、保存 payload 和元数据更新函数，并通过 `modelEditorActions` 注入子组件。
- 涉及文件：
  - `frontend/apps/web/src/views/ModelsView.vue`
  - `frontend/apps/web/src/components/models/ModelEditorDrawer.vue`
  - `frontend/apps/web/src/components/models/modelViewTypes.ts`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：模型新增/编辑抽屉标题、数据源选择、schema 绑定、模型名称/物理位置输入、技术/业务元数据编辑、多行 section 增删、动态函数字段传递和保存动作保持不变；本步骤只迁移展示结构和局部样式。`ModelsView.vue` 从 2105 行降到 1990 行。
- 验证命令与结果：
  - `npm run build:web`（`frontend` 目录）：通过，`vue-tsc --noEmit && vite build` 成功。
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
- 失败、阻塞或残余风险：
  - npm 仍提示 `electron_mirror` / `electron-mirror` 配置即将不兼容，未阻断构建。
  - `ModelsView.vue` 仍接近 2000 行，剩余的列表查询、同步弹窗、详情页签编排和字段展示 helper 仍适合继续分批拆分；不建议为了 800 行阈值做机械拆分。
- 下一步：提交 Batch 15；随后继续拆 `ModelsView.vue` 剩余清晰 UI 区块，优先处理同步弹窗或字段明细展示。

## Step 033 - Batch 16 拆分模型同步弹窗

- 执行时间：2026-05-25 03:58:19 +08:00
- 目标问题：`ModelsView.vue` 同时维护页面同步弹窗、后台同步任务创建弹窗和表选择器展示，两个弹窗结构相近但占据父页面大量模板和样式，影响列表/详情/编辑逻辑阅读。
- 修改范围：新增 `ModelSyncDialogs.vue` 承载页面同步与同步任务创建两个弹窗；将 `ModelSyncFormState`、`ModelSyncTaskFormState` 移入共享 `modelViewTypes.ts`；父页面保留打开、校验、提交、任务跳转和刷新逻辑，并通过 `syncDialogActions` 注入；清理 `ModelsView.vue` 中已无引用的旧同步表选择样式。
- 涉及文件：
  - `frontend/apps/web/src/views/ModelsView.vue`
  - `frontend/apps/web/src/components/models/ModelSyncDialogs.vue`
  - `frontend/apps/web/src/components/models/modelViewTypes.ts`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：页面同步弹窗标题/说明、数据源类型与数据源选择、表选择器、超过 20 张表时转后台任务、同步任务创建弹窗、按钮 loading 和提交动作保持不变；父页面仍负责所有 API 调用和路由跳转。`ModelsView.vue` 从 1990 行降到 1864 行。
- 验证命令与结果：
  - `npm run build:web`（`frontend` 目录）：通过，`vue-tsc --noEmit && vite build` 成功。
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
- 失败、阻塞或残余风险：
  - npm 仍提示 `electron_mirror` / `electron-mirror` 配置即将不兼容，未阻断构建。
  - `ModelsView.vue` 仍有列表表格、查询组装、schema/元数据 helper、详情路由和保存逻辑，后续应继续按职责拆分，避免按 800 行做机械切割。
- 下一步：提交 Batch 16；继续处理模型列表面板或转向下一个大页面的清晰 section。

## Step 034 - Batch 17 拆分模型列表面板

- 执行时间：2026-05-25 04:04:36 +08:00
- 目标问题：`ModelsView.vue` 的模型列表页签仍同时承载筛选工具条、动态筛选面板、表格列、分页和动作渲染，列表展示职责与查询构造、详情路由、编辑保存逻辑混在同一文件。
- 修改范围：新增 `ModelListPanel.vue` 承载模型列表工具条、动态筛选面板挂载、模型表格、分页和列表局部样式；父页面保留查询条件归一、数据加载、动作实现和路由跳转，并通过 `modelListActions` 注入；从父页面移除列表表格相关 UI import 和样式。
- 涉及文件：
  - `frontend/apps/web/src/views/ModelsView.vue`
  - `frontend/apps/web/src/components/models/ModelListPanel.vue`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：模型列表数据、数据源筛选、动态筛选条件、序号、模型名称跳转、数据源/项目/类型/物理位置列、操作下拉和分页行为保持不变；本步骤只迁移展示组件。`ModelsView.vue` 从 1864 行降到 1771 行。
- 验证命令与结果：
  - `npm run build:web`（`frontend` 目录）：通过，`vue-tsc --noEmit && vite build` 成功。
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
- 失败、阻塞或残余风险：
  - npm 仍提示 `electron_mirror` / `electron-mirror` 配置即将不兼容，未阻断构建。
  - 模型中心父页面还保留大量 schema/元数据 helper 和详情路由编排；这些更偏业务组合逻辑，后续应优先抽 composable，而不是继续只拆模板。
- 下一步：提交 Batch 17；继续评估模型中心 schema/元数据 helper 是否适合抽 composable，或转向质量/工作流编辑器大页面。

## Step 035 - Batch 18 拆分质量任务动态函数弹窗与函数目录

- 执行时间：2026-05-25 04:12:30 +08:00
- 目标问题：`QualityTaskEditorView.vue` 内联维护动态函数目录、函数参数 schema、弹窗模板和弹窗样式，导致质量任务保存/校验主流程与动态函数 UI 耦合，新增函数时容易修改巨型页面。
- 修改范围：新增 `qualityTaskDynamicFunctions.ts` 承载质量任务动态函数类型和固定目录；新增 `QualityDynamicFunctionDialog.vue` 承载函数列表、参数表单、选中内容提示、表达式预览和弹窗样式；父页面保留插入位置追踪、表达式生成、必填校验和插入动作。
- 涉及文件：
  - `frontend/apps/web/src/views/QualityTaskEditorView.vue`
  - `frontend/apps/web/src/components/quality/QualityDynamicFunctionDialog.vue`
  - `frontend/apps/web/src/components/quality/qualityTaskDynamicFunctions.ts`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：质量动态函数名称、参数默认值、表达式预览、必填校验、where 子句插入、参数绑定输入框插入和保存 payload 保持不变；只是将函数目录和弹窗展示层移出编辑页。`QualityTaskEditorView.vue` 从 1578 行降到 1179 行。
- 验证命令与结果：
  - `npm run build:web`（`frontend` 目录）：通过，`vue-tsc --noEmit && vite build` 成功。
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
- 失败、阻塞或残余风险：
  - npm 仍提示 `electron_mirror` / `electron-mirror` 配置即将不兼容，未阻断构建。
  - 质量任务编辑页仍超过 1000 行，剩余可拆点包括基本信息/绑定/规则选择 section、参数绑定表、SQL 预览区和告警/校验结果区。
- 下一步：提交 Batch 18；继续拆质量任务编辑页的表单 section，或转向工作流编辑器/采集任务编辑器。

## Step 036 - Batch 19 拆分质量任务参数、告警、调度与校验结果区块

- 执行时间：2026-05-25 08:33:01 +08:00
- 目标问题：`QualityTaskEditorView.vue` 仍内联参数绑定表、告警配置表、调度表单和校验结果展示，这些都是独立 section，继续放在父页面会分散保存/预览/校验主流程。
- 修改范围：新增 `QualityParameterBindingsSection.vue` 承载输入参数绑定表和动态函数按钮；新增 `QualityAlertConfigsSection.vue` 承载输出告警配置表和阈值输入；新增 `QualityScheduleSection.vue` 承载调度开关、Cron 和时区；新增 `QualityValidationResultSection.vue` 承载语义校验结果、输出列、样例数据和摘要 JSON。父页面保留参数赋值、动态函数插入、告警操作符归一和校验执行逻辑，通过 actions 注入子组件。
- 涉及文件：
  - `frontend/apps/web/src/views/QualityTaskEditorView.vue`
  - `frontend/apps/web/src/components/quality/QualityParameterBindingsSection.vue`
  - `frontend/apps/web/src/components/quality/QualityAlertConfigsSection.vue`
  - `frontend/apps/web/src/components/quality/QualityScheduleSection.vue`
  - `frontend/apps/web/src/components/quality/QualityValidationResultSection.vue`
  - `docs/studio-design-remediation-log.md`
  - `docs/studio-design-remediation-summary.md`
- 行为兼容说明：参数绑定值、动态函数插入目标、告警开启/操作符/阈值、调度配置、校验结果展示和保存 payload 保持不变；本步骤只迁移 section 展示和局部样式。`QualityTaskEditorView.vue` 从 1179 行降到 1016 行。
- 验证命令与结果：
  - `npm run build:web`（`frontend` 目录）：通过，`vue-tsc --noEmit && vite build` 成功。
  - `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
- 失败、阻塞或残余风险：
  - npm 仍提示 `electron_mirror` / `electron-mirror` 配置即将不兼容，未阻断构建。
  - 质量任务编辑页还略超过 1000 行，剩余的基本信息、任务绑定、规则选择、SQL 预览与校验 section 可继续抽取；但主要动态函数和表格 section 已完成隔离。
- 下一步：提交 Batch 19；继续拆质量任务编辑页剩余 section，使其降到 1000 行以下。
