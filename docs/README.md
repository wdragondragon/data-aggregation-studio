# 项目文档导航

当前项目文档已按使用用途集中到 `docs/` 目录下，便于交接、测试、运维和数据库结构管理。

## 目录分类

- `使用/`
  - 面向业务使用者的系统使用说明、操作白皮书和正式分发版本。
  - 当前包含：
    - [studio-user-whitepaper.md](./使用/studio-user-whitepaper.md)
    - [studio-user-whitepaper.docx](./使用/studio-user-whitepaper.docx)

- `需求/`
  - 面向产品、开发、测试和交付的需求规约、页面反向规约和可见点追踪矩阵。
  - 当前包含：
    - [DataAggregation-Studio-需求规格说明书.docx](./需求/DataAggregation-Studio-%E9%9C%80%E6%B1%82%E8%A7%84%E6%A0%BC%E8%AF%B4%E6%98%8E%E4%B9%A6.docx)
    - [studio-reverse-requirements.md](./需求/studio-reverse-requirements.md)
    - [studio-reverse-requirements.docx](./需求/studio-reverse-requirements.docx)

- `交接/`
  - 交接类文档，面向项目接手人和维护人。
  - 当前包含：[tenant-project-handover.md](./交接/tenant-project-handover.md)

- `运维/`
  - 运维、监控、排障和运行策略说明。
  - 当前包含：
    - [环境初始化说明.md](./运维/部署/环境初始化说明.md)
    - [data-service-metrics-retention-impact.md](./运维/监控/data-service-metrics-retention-impact.md)

- `测试/`
  - 测试用例、测试结果和复测报告。
  - 当前包含：
    - 数据服务：
      - [data-service-test-cases.md](./测试/数据服务/data-service-test-cases.md)
      - [data-service-test-result-20260416.md](./测试/数据服务/data-service-test-result-20260416.md)
      - [data-service-test-result-20260416-二轮复测.md](./测试/数据服务/data-service-test-result-20260416-%E4%BA%8C%E8%BD%AE%E5%A4%8D%E6%B5%8B.md)
    - 质量指标：
      - [quality-metrics-ui-test-cases.md](./测试/质量指标/quality-metrics-ui-test-cases.md)
      - [quality-metrics-test-cases.md](./测试/质量指标/quality-metrics-test-cases.md)
      - [quality-metrics-test-result-20260417.md](./测试/质量指标/quality-metrics-test-result-20260417.md)

- `数据库/`
  - 数据库结构快照、恢复参考和结构校验资料。
  - 当前包含：
    - [MySQL Schema Snapshots](./数据库/结构快照/mysql/README.md)

- `规范/`
  - 文档规范、归档约定、模板和团队协作标准。
  - 当前包含：
    - [document-naming-and-archiving.md](./规范/document-naming-and-archiving.md)

## 说明

- 根目录 [README.md](../README.md) 继续作为项目总说明入口保留。
- 后续新增文档时，优先按“用途”归档到对应目录，避免再次散落到模块目录中。
