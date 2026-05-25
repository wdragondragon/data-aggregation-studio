---
name: studio-design-code-standards-zh
description: 当在 DataAggregation Studio 中新增功能、修复问题、重构前后端代码、维护 HTTP reader/writer、schema、采集任务、工作流、质量、数据服务、模型中心等模块时使用。要求遵循当前设计整改沉淀的编码规范：保持契约兼容、避免维护性债务回潮、按职责拆分而不是按行数硬拆、补充回归测试和文档记录。
---

# Studio 设计整改编码规范

## 适用范围

- 仅适用于 `DataAggregation/data-aggregation-studio`。
- 适用于功能开发、缺陷修复、重构、前端页面调整、schema/bootstrap/SQL 同步、HTTP reader/writer 配置维护。
- 安全策略、权限收口、SSRF、密钥脱敏等安全类改动不在本技能默认范围内；除非用户明确要求，不要借设计整改改变安全行为。

## 总原则

- 保持公开契约稳定：不随意改 API/DTO 字段、任务 JSON 字段、运行参数 schema key、数据库业务表结构和已有枚举字符串。
- 优先复用现有 schema-driven、runtime option schema、动态元模型、`StudioTableShell`、领域常量、composable 和支撑组件。
- 重构采用 facade + 内部 helper/strategy/support 的方式，外部 Controller、Service 方法、前端路由和保存 payload 默认保持不变。
- 不把“800 行”当硬规则。类或页面过大时先判断职责是否真的混杂、测试保护是否足够、拆分边界是否自然；`StudioSchemaUpgradeService` 这类 schema/升级编排类允许作为已审查例外暂留。
- 不为降行数拆出无语义文件。拆分必须降低真实维护成本，例如隔离纯函数、状态管理、运行参数合并、字段映射、视图区块、弹窗、图表 option、schema builder。

## 后端规范

- 采集任务装配继续保持 `CollectionTaskAssemblerService` facade，对内按 reader/writer、HTTP/File/RDBMS 支撑、字段映射、运行参数合并、增量游标等职责扩展。
- 新增数据源、reader、writer、runtime option 时，同步检查 Java bootstrap、MySQL 初始化 SQL、SQLite 初始化 SQL、升级 SQL、前端 schema/API 类型和 drift 回归测试。
- `header`、`params`、`requestBody` 这类需要按插件契约保持字符串的字段，不要被通用运行参数合并逻辑解析成 Map。
- 核心业务链路不要新增无语义 `return null;`。优先使用空集合、`Optional`、明确结果对象或业务异常。
- 不新增 `catch ignored`。确需容错解析时，用带语义的异常变量名，并在局部注释说明降级原因。
- 常量和状态值优先从统一领域常量/枚举获取；接口返回值仍保持历史字符串，不因内部常量化改变对外语义。

## 前端规范

- 列表和横向内容优先使用 `StudioTableShell`，避免页面级 `overflow-x: hidden` 导致首列或操作列不可达。
- 页面超过合理规模时，优先拆出 section 组件、dialog 组件、support 纯函数或 composable；不要把一个大页面机械拆成多个同样混乱的小页面。
- 已有 `useAsyncAction`、`usePageQuery`、`useDialogForm`、`useTableSelection` 时，在触达页面中逐步复用，统一 loading、错误消息、分页、弹窗和选择状态；不为了统一而做大范围机械迁移。
- HTTP 请求参数编辑统一走 `HttpRequestOptionsEditor` 组件族；旧 `HttpReaderOptionsEditor` 只作为兼容入口。动态函数保存格式仍是插件支持的 `{dyn_*}` 字符串表达式。
- 质量动态函数优先复用既有函数 catalog 和 dialog，不要在页面内重新内嵌一套函数目录、参数表单和样式。
- 前端领域状态、关注目标、节点类型、标签样式优先复用 `studioDomain` 等共享常量和格式化工具，避免继续散落硬编码字符串。

## Schema 与 SQL 同步

- Java bootstrap 是 datasource capability、technical metadata schema、runtime option schema 的事实源。
- 修改 datasource/plugin/schema 时必须同步：
  - MySQL 初始化 SQL
  - SQLite 初始化 SQL
  - 升级 SQL 或升级服务
  - 前端表单/schema 使用点
  - `StudioSchemaDriftRegressionTest`
- 不引入 Liquibase；沿用项目当前原始 SQL 和升级服务交付方式。

## 测试与门禁

- 小改动至少运行相关 targeted test 或前端构建；重构必须先保证已有回归覆盖，再拆内部结构。
- 常用命令：
  - 前端：在 `frontend` 目录运行 `npm run build:web`
  - 后端静态门禁：在 `backend` 目录运行 `mvn -pl studio-test -am "-Dtest=StudioDesignDebtRegressionTest" "-DforkCount=0" "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - 后端全量：在 `backend` 目录运行 `mvn -pl studio-test -am "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - 空白检查：在 Studio 根目录运行 `git diff --check`
- 全量后端测试不要加 `-DforkCount=0`，包含假 Python 子进程的用例在该参数下会出现 classpath 假失败。
- 本地缺少 DataAggregation 快照依赖时，先在 `DataAggregation` 根目录执行 `mvn -pl commons,core,data-source-plugins/data-source-handler-abstract,plugins-loader-center -am -DskipTests install`。

## 长任务记录

- 设计整改或大范围重构必须维护 `docs/studio-design-remediation-log.md`。
- 每个小步骤记录目标问题、修改范围、涉及文件、行为兼容说明、验证命令与结果、失败/阻塞/残余风险和下一步。
- 阶段收口时更新 `docs/studio-design-remediation-summary.md`，明确已处理、未处理、延期原因和测试结果。

## 待处理维护债务

- 安全问题仍是独立 backlog，不在本规范默认修复范围内。
- 历史 `catch ignored`、`return null;` 仍需要按业务语义分批清理；门禁要求至少不能回升。
- 前端 composable 尚未覆盖所有页面，后续只在触达页面时逐步替换重复 loading、分页、弹窗和选择逻辑。
- `StudioSchemaUpgradeService` 暂留为已审查例外；后续仅在 schema drift/init 回归保护下，按具体升级步骤继续拆分。
