# DataAggregation / Studio 迁移错误台账（2026-04-21）

## 1. 使用规则

- 每个错误至少记录一次原始现象、一次定位判断、一次修复动作、一次重试结果。
- 不以终端临时输出代替台账。
- 同一阻塞点如发生多轮重试，按时间顺序持续追加。

## 2. 错误记录

| 编号 | 时间 | 阶段 | 现象 | 初步判断 | 修复动作 | 重试结果 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `E-001` | `2026-04-21 21:24` | 主仓根 Reactor / `core` 编译 | `FilterTransformer.java` 编译失败，`Record` 同时匹配 `com.jdragon.aggregation.commons.element.Record` 与 `java.lang.Record` | JDK17 引入 `java.lang.Record` 后，`import com.jdragon.aggregation.commons.element.*;` 的通配导入不再安全 | 已将 `FilterTransformer`、相关示例类和 `GroovyTransformer` 动态生成代码中的 `Record` 导入改为显式类型，再执行 `mvn -pl core -am clean test` | `2026-04-21 21:27` 重试通过 | `已关闭` |
| `E-002` | `2026-04-21 21:3x` | 主仓根 Reactor / 旧时间处理工具类 | JDK17 下编译报 `javax.xml.bind.DatatypeConverter` 不存在 | JAXB 从较新的 JDK 中移除，旧实现直接依赖已失效 | 已将 InfluxDB v1 读写相关时间解析逻辑切到 `java.time` 实现，再回归主仓构建 | 主仓后续全量 `clean install` 通过 | `已关闭` |
| `E-003` | `2026-04-21 21:4x` | 主仓根 Reactor / transformer 模块 | JDK17 下编译报 `com.sun.*` 内部 JDK 包不可见 | 旧代码引用了 JDK 内部实现类，JDK17 模块封装后不可再直接访问 | 已删除无效内部包导入，保留标准 Java API 实现，再回归主仓构建 | 主仓后续全量 `clean install` 通过 | `已关闭` |
| `E-004` | `2026-04-21 23:04` | `studio-desktop-runtime` 启动后首轮调度 | 日志报 `no such table: quality_task_schedule`，来源为 `QualityTaskScheduleRunner` 定时任务查询 SQLite | 不是 schema 最终缺失，而是 `StudioSchemaAutoUpgradeRunner` 与 `@Scheduled(fixedDelay=30000L)` 首轮执行存在启动并发，调度器在质量任务表创建前抢跑 | 已核对 SQLite 文件确认质量任务表最终存在，并在三个周期调度器上补充 `initialDelay=30000L` 以避开升级窗口 | `2026-04-22 00:28` 在 `studio-desktop-runtime-env6.out.log` 中验证通过，完整 30 秒周期未再复现 | `已关闭` |
| `E-005` | `2026-04-21 23:16` | `studio backend` 受影响模块重打包 | Maven `clean` 阶段无法删除 `studio-server\\target\\studio-server-0.1.0-SNAPSHOT-exec.jar` | Windows 文件锁，运行中的 `studio-server` 进程占用了可执行 jar | 停止占用进程后继续使用更新后的构件启动 `studio-server` / `studio-desktop-runtime` | 后续基于修复后构件完成 desktop runtime 稳定启动、文档页验证和真实采集链路验证 | `已关闭` |
| `E-006` | `2026-04-21 23:4x` | Desktop fresh SQLite 首次登录 | Electron 桌面端以 `admin / admin123` 登录时返回 `500`，实际业务消息为“用户名或密码错误” | fresh SQLite 只有 schema，没有默认 `admin`、租户、项目等 bootstrap 数据；不是前端登录页问题 | 先使用 `PropertiesLauncher` 执行 `StudioDesktopDataInitializerApplication` 初始化桌面端基础数据，后续再通过新增 `DesktopRuntimeBootstrapRunner` 把该动作固化到 desktop runtime 启动流程 | 初始化后 SQLite 中 `sys_user`、`studio_tenant`、`studio_project` 均有默认记录；`2026-04-22 00:47` fresh DB 自动启动验证也已通过 | `已关闭` |
| `E-007` | `2026-04-22 00:19` | Desktop 采集任务手动触发 | 桌面端 UI 触发单表采集任务返回 `400`，浏览器响应为 `No authorized online worker is available for the current project` | `worker_lease` 中已有在线 `studio-desktop-worker`，但 `studio_project_worker_binding` 为空，当前项目未授权任何 worker | 先通过桌面端“系统管理 -> Worker 下发”给默认项目绑定 `studio-desktop-worker` 并保持启用，随后将默认项目 worker 绑定逻辑固化到 `TenantProjectFoundationService` 的 desktop 模式初始化中 | `2026-04-22 00:25` 二次触发返回 `200`，`run_record` 记录 `SUCCESS`；`2026-04-22 00:47` fresh DB 自动启动后也能直接生成默认绑定 | `已关闭` |
