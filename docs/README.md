# 项目文档导航

当前项目文档已按使用用途集中到 `docs/` 目录下，便于交接、测试、运维和数据库结构管理。

## 目录分类

- `使用/`
  - 面向业务使用者的系统使用说明、操作白皮书和正式分发版本。
  - 当前包含：
    - [功能模块文档](./使用/功能模块/README.md)：按业务能力拆分的模块使用说明。
    - [统一告警中心使用指南](./使用/功能模块/13-统一告警中心.md)：九类告警、状态流转、通知投递、权限和安全边界的图文操作说明。
    - [Studio 界面接口文档](./使用/接口/README.md)：按界面模块拆分的接口、DTO、Controller 对照和 cURL 调用链模板。
    - [studio-user-whitepaper.md](./使用/studio-user-whitepaper.md)
    - [studio-user-whitepaper.docx](./使用/studio-user-whitepaper.docx)

- `需求/`
  - 面向产品、开发、测试和交付的需求规约、页面反向规约和可见点追踪矩阵。
  - 当前包含：
    - [DataAggregation-Studio-需求规格说明书.docx](./需求/DataAggregation-Studio-%E9%9C%80%E6%B1%82%E8%A7%84%E6%A0%BC%E8%AF%B4%E6%98%8E%E4%B9%A6.docx)
    - [studio-reverse-requirements.md](./需求/studio-reverse-requirements.md)
    - [studio-reverse-requirements.docx](./需求/studio-reverse-requirements.docx)

- `规划/`
  - 面向产品规划、阶段建设和后续立项的路线图文档。
  - 当前包含：
    - [studio-future-roadmap.md](./规划/studio-future-roadmap.md)

- `交接/`
  - 交接类文档，面向项目接手人和维护人。
  - 当前包含：[tenant-project-handover.md](./交接/tenant-project-handover.md)

- `运维/`
  - 运维、监控、排障和运行策略说明。
  - 当前包含：
    - [环境初始化说明.md](./运维/部署/环境初始化说明.md)
    - [studio-server-worker-configuration.md](./运维/部署/studio-server-worker-configuration.md)：server/worker、运行日志对象存储、Worker 组和 ODPS 集成配置说明。
    - [data-service-metrics-retention-impact.md](./运维/监控/data-service-metrics-retention-impact.md)
    - [alert-webhook-security.md](./运维/监控/alert-webhook-security.md)：告警 Webhook 的 SSRF、签名、秘密与投递安全配置。

- `测试/`
  - 测试用例、测试结果和复测报告。
  - 当前包含：
    - 数据服务：
      - [data-service-test-cases.md](./测试/数据服务/data-service-test-cases.md)
      - [data-service-test-result-20260416.md](./测试/数据服务/data-service-test-result-20260416.md)
      - [data-service-test-result-20260416-二轮复测.md](./测试/数据服务/data-service-test-result-20260416-%E4%BA%8C%E8%BD%AE%E5%A4%8D%E6%B5%8B.md)
    - 数据资产：
      - [datasource-connection-status-test-cases.md](./测试/数据资产/datasource-connection-status-test-cases.md)
    - 质量指标：
      - [quality-metrics-ui-test-cases.md](./测试/质量指标/quality-metrics-ui-test-cases.md)
      - [quality-metrics-test-cases.md](./测试/质量指标/quality-metrics-test-cases.md)
      - [quality-metrics-test-result-20260417.md](./测试/质量指标/quality-metrics-test-result-20260417.md)

- `数据库/`
  - 数据库结构快照、恢复参考和结构校验资料。
  - 当前包含：
    - [MySQL Schema Snapshots](./数据库/结构快照/mysql/README.md)
    - [alert-center-upgrade.md](./数据库/alert-center-upgrade.md)：统一告警中心增量升级、核验和回滚说明。

- `规范/`
  - 文档规范、归档约定、模板和团队协作标准。
  - 当前包含：
    - [document-naming-and-archiving.md](./规范/document-naming-and-archiving.md)

## Studio 界面接口文档

| 模块 | 文档 |
|---|---|
| 公共约定、认证与 cURL 模板 | [00-common-auth-and-conventions.md](./使用/接口/00-common-auth-and-conventions.md) |
| 工作台与目录能力 | [01-dashboard-catalog.md](./使用/接口/01-dashboard-catalog.md) |
| 数据资产、数据源、元模型与模型中心 | [02-assets-datasources-models.md](./使用/接口/02-assets-datasources-models.md) |
| 字段映射规则、采集任务和采集运行 | [03-collection-and-field-mapping.md](./使用/接口/03-collection-and-field-mapping.md) |
| 数据开发、工作流与运行日志 | [04-data-development-workflows-runs.md](./使用/接口/04-data-development-workflows-runs.md) |
| 数据服务开放 | [05-open-data-services.md](./使用/接口/05-open-data-services.md) |
| 数据接入服务 | [06-data-ingestion-services.md](./使用/接口/06-data-ingestion-services.md) |
| 协议转换服务 | [07-protocol-conversions.md](./使用/接口/07-protocol-conversions.md) |
| 数据质量规则、任务、指标和问题 | [08-quality.md](./使用/接口/08-quality.md) |
| 系统管理、权限、访问申请、通知和关注 | [09-system-access-notifications.md](./使用/接口/09-system-access-notifications.md) |
| 运维中心、运行时、导入导出和 AI 助手 | [10-ops-center-runtime-import-export-assistant.md](./使用/接口/10-ops-center-runtime-import-export-assistant.md) |
| 统一告警中心 | [11-alert-center.md](./使用/接口/11-alert-center.md) |

## 功能模块文档

| 模块 | 文档 |
|---|---|
| 工作台与项目上下文 | [01-工作台与项目上下文.md](./使用/功能模块/01-工作台与项目上下文.md) |
| 数据源管理 | [02-数据源管理.md](./使用/功能模块/02-数据源管理.md) |
| 模型中心与模型同步 | [03-模型中心与模型同步.md](./使用/功能模块/03-模型中心与模型同步.md) |
| 数据采集任务 | [04-数据采集任务.md](./使用/功能模块/04-数据采集任务.md) |
| 数据开发 | [05-数据开发.md](./使用/功能模块/05-数据开发.md) |
| 工作流编排 | [06-工作流编排.md](./使用/功能模块/06-工作流编排.md) |
| 数据服务 | [07-数据服务.md](./使用/功能模块/07-数据服务.md) |
| 数据接入服务 | [08-数据接入服务.md](./使用/功能模块/08-数据接入服务.md) |
| 协议转换服务 | [09-协议转换服务.md](./使用/功能模块/09-协议转换服务.md) |
| 数据质量 | [10-数据质量.md](./使用/功能模块/10-数据质量.md) |
| 系统管理 | [11-系统管理.md](./使用/功能模块/11-系统管理.md) |
| 运维中心与日志 | [12-运维中心与日志.md](./使用/功能模块/12-运维中心与日志.md) |
| 统一告警中心 | [13-统一告警中心.md](./使用/功能模块/13-统一告警中心.md) |

## 说明

- 根目录 [README.md](../README.md) 继续作为项目总说明入口保留。
- 后续新增文档时，优先按“用途”归档到对应目录，避免再次散落到模块目录中。
