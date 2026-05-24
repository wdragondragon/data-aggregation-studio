# Studio 设计与代码风格整改汇总

> 本文档在整改过程中持续更新，任务收尾时作为最终总结。

## 已处理问题

- 建立整改记录文档和静态回归门禁：新增 `StudioDesignDebtRegressionTest`，用“不可增加”口径守住大文件、旧表格 wrapper、后端 `catch ignored`、后端 `return null;` 等历史债务基线。
- 扩展 schema 漂移检查：`StudioSchemaDriftRegressionTest` 覆盖 datasource capability、runtime option schema、technical metadata schema；同步修正 SQLite 初始化脚本中 `postgres`、`ftp`、`sftp`、`minio` 的能力配置，使其与 Java/MySQL bootstrap 一致。
- 统一前端表格容器：新增并导出 `StudioTableShell`，将采集任务、运行实例、工作流、质量、数据服务、模型、数据源、系统管理等列表/详情表格逐步接入统一横向滚动和布局容器，清理旧 wrapper 名称。
- 收敛前端领域常量：新增 `frontend/apps/web/src/constants/studioDomain.ts`，将已改造页面中的关注目标类型、状态展示和节点类型判断逐步改为常量/工具函数引用，减少散落字符串。
- 拆分 HTTP 请求参数编辑器：从 `HttpReaderOptionsEditor.vue` 抽出键值参数表、Raw 编辑区、Token 参数表组件；`HttpRequestOptionsEditor.vue` 保持兼容入口，reader/writer 的 `header`、`params`、`requestBody` 字符串保存契约不变。
- 拆分采集任务装配运行参数合并逻辑：新增 `CollectionTaskRuntimeOptionMerger`，从 `CollectionTaskAssemblerService` 中抽离运行参数合并、点路径写入、保留字段过滤和 JSON 字符串保留逻辑，`assemble` 对外入口和任务 JSON 输出不变。
- 抽取元模型配置 description 工具：新增 `MetaModelConfigDescriptions`，让 `MetadataSchemaService` 和 `StandardRuntimeOptionSchemaBootstrapService` 复用 `META_MODEL_CONFIG:` 编解码逻辑，保持原 description 格式兼容。
- 清理新增代码中的错误/空值坏味道：修复元模型配置工具首版新增的 `return null;` 静态债务，并将 `catch ignored` 改为带语义变量和降级说明的写法。
- Batch 1 继续降低静态债务门禁：`catch ignored` 阈值收紧到 24，`return null;` 阈值收紧到 221，旧表格 wrapper 阈值收紧到 0。
- Batch 2 拆分采集任务装配策略：`CollectionTaskAssemblerService` 保留 facade，对内拆出 `CollectionTaskFieldMappingResolver`、`CollectionTaskFileConfigSupport`、`CollectionTaskHttpConfigSupport`，主类从 1150 行降到 418 行，reader/writer 任务 JSON 契约不变。
- 完成浏览器 smoke：确认 nginx 代理的 Studio 构建产物可加载，采集任务列表、HTTP 写入任务编辑页、HTTP 动态函数弹窗、工作流、数据服务监控、质量任务、模型、系统页均可渲染。

## 未处理或延期问题

- 安全问题按用户要求暂不处理，包括认证、越权、SSRF、密钥泄露、文件路径安全等。
- 巨型 Service 和巨型 Vue 页面没有一次性完全拆完。本轮优先拆了风险较低、测试保护较强的运行参数合并器、采集任务装配策略、HTTP 编辑器子组件和元模型 description 工具；`StudioSchemaUpgradeService`、`DataServiceService`、`DataModelLineageService`、质量服务、采集任务编辑页等仍建议后续按测试保护分批拆。
- 后端历史 `catch ignored`、`return null;`、大文件数量仍存在，本轮通过静态门禁确保不再增加，并清理本轮新增代码的坏味道；历史债务建议单独排期。
- 前端 composable 体系如 `usePageQuery`、`useAsyncAction`、`useTableSelection`、`useDialogForm` 尚未全面落地，当前以表格壳、常量收敛和局部组件拆分为主。
- 数据质量动态函数、HTTP 动态函数弹窗本体仍可继续拆为独立 dialog 组件；本轮先拆出键值表、Raw 编辑区和 Token 子表。

## 测试结果

- `npm run build:web`：通过，`vue-tsc --noEmit && vite build` 成功。
- `mvn -pl studio-test test`：通过，87 tests，0 failures，0 errors，0 skipped。
- `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest,StudioSchemaDriftRegressionTest" test`：通过，10 tests，0 failures，0 errors。
- `mvn -pl studio-test "-Dtest=CollectionTaskAssemblerServiceRegressionTest,StudioDesignDebtRegressionTest" test`：通过，15 tests，0 failures，0 errors。
- `mvn -pl studio-test "-Dtest=StudioSchemaDriftRegressionTest,StudioInitializationApiRegressionTest,StudioDesignDebtRegressionTest" test`：最终通过，18 tests，0 failures，0 errors；中间曾因新增 `return null;` 触发设计债务门禁失败，已记录并修复。
- `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest" test`：通过，4 tests，0 failures，0 errors。
- `mvn -pl studio-test "-Dtest=StudioDesignDebtRegressionTest,CollectionTaskAssemblerServiceRegressionTest" test`：通过，15 tests，0 failures，0 errors。
- `mvn -pl studio-test "-Dtest=CollectionTaskAssemblerServiceRegressionTest,StudioDesignDebtRegressionTest" "-DforkCount=0" test`：通过，15 tests，0 failures，0 errors。
- `mvn -pl studio-test -am "-Dtest=CollectionTaskAssemblerServiceRegressionTest,StudioDesignDebtRegressionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：上游模块和 Studio 后端相关模块重新编译通过，但 `studio-test` fork JVM 因 Windows 页文件不足未启动，0 tests executed；随后已用非 fork 模式完成目标测试。
- `git diff --check`：通过，仅有 Windows 工作区 LF/CRLF 替换提示，无空白错误。
- 旧表格 wrapper 搜索：无命中。
- 前端硬编码 `target-type="..."` 搜索：无命中。
- 浏览器 smoke：通过；最终控制台无 error，关键页面和 HTTP 动态函数弹窗可渲染。
- 环境提示：测试期间 Redis 不可达会触发现有本地缓存降级日志；Surefire 退出阶段偶发 fork JVM 超时提示；Maven settings 存在 `Unrecognised tag: 'release'` 警告。上述均未导致本轮验证失败。

## 建议后续批次

- Batch 1：继续把设计债务门禁从“不可增加”推进到“逐步下降”，每批清理一类历史 `catch ignored` 或 `return null;`，并为业务链路补明确异常/空集合/Optional 语义。
- Batch 2：已完成采集任务装配策略拆分；后续若新增源端/目标端，优先在现有 helper/strategy 边界扩展，不再回填到 facade。
- Batch 3：拆 `StudioSchemaUpgradeService` 为方言、MySQL 步骤、SQLite 步骤、内置数据升级步骤和升级编排器，并让 schema drift test 覆盖新增 datasource/plugin 的准入。
- Batch 4：拆 `DataServiceService`、`DataModelLineageService` 和质量服务的查询、聚合、DTO 组装逻辑；每拆一组先补回归测试。
- Batch 5：继续前端大页面治理，优先拆采集任务编辑、质量任务编辑、工作流编辑、模型中心为 composable + section components，并推广 `usePageQuery`、`useAsyncAction`、`useDialogForm`。
- Batch 6：将 HTTP 动态函数弹窗独立成可复用 dialog，配合可视化键值表组件形成统一请求参数编辑组件族。
