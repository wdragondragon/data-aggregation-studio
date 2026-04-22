# DataAggregation / Studio 迁移执行日志（2026-04-21）

## 1. 任务目标

目标：完成主仓 `DataAggregation`、`studio backend`、`studio web`、`studio desktop` 在 `JDK 17 + Spring Boot 3` 体系下的迁移收口验证，并将执行过程、错误、修复与结论完整落档。

## 2. 固定环境

| 项目 | 信息 |
| --- | --- |
| 执行日期 | `2026-04-21` |
| 仓库根目录 | `C:\dev\ideaProject\DataAggregation` |
| Studio 子仓 | `C:\dev\ideaProject\DataAggregation\data-aggregation-studio` |
| JDK | `C:\dev\Java\jdk-17.0.12` |
| Maven | `C:\dev\apache-maven-3.8.8\bin\mvn.cmd` |
| 测试数据库（源） | `192.168.188.129:3306/mock_data` |
| 测试数据库（目标） | `192.168.188.129:3306/mock_data_target` |

## 3. 初始状态

| 检查项 | 结果 |
| --- | --- |
| 主仓分支 | `jdk17` |
| 子仓分支 | `jdk17` |
| 主仓工作区 | 脏工作区，存在既有未提交改动 |
| 子仓工作区 | 脏工作区，存在既有未提交改动 |
| 既有 Web 测试历史 | 已存在 2026-04-21 Web 实链路测试结果文档 |

## 4. 执行记录

### 4.1 建立台账

| 时间 | 目录 | 动作 | 结果 | 备注 |
| --- | --- | --- | --- | --- |
| `2026-04-21 21:22` | `data-aggregation-studio/docs/测试/迁移执行` | 创建执行日志、错误台账、验收报告骨架 | `完成` | 后续持续回填 |

### 4.2 后续执行记录

| 时间 | 目录 | 动作 | 结果 | 备注 |
| --- | --- | --- | --- | --- |
| `2026-04-21 21:24` | `C:\dev\ideaProject\DataAggregation` | 校验 `JDK 17 + Maven 3.8.8` 环境并执行主仓根 Reactor `clean install` | `失败` | 完整输出见 `logs/root-reactor-clean-install-20260421.log` |
| `2026-04-21 21:24` | `core` | 定位首个阻塞点 | `完成` | `FilterTransformer` 在 JDK17 下触发 `com.jdragon...Record` 与 `java.lang.Record` 歧义 |
| `2026-04-21 21:27` | `core` | 修复 `Record` 歧义并执行 `-pl core -am clean test` | `成功` | 完整输出见 `logs/core-pl-am-clean-test-20260421-2128.log` |
| `2026-04-21 22:37` | `C:\dev\ideaProject\DataAggregation` | 主仓根 Reactor 在 JDK17 下完成全量 `clean install` | `成功` | 完整输出见 `logs/root-reactor-clean-install-20260421-pass2.log` |
| `2026-04-21 22:47` | `C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend` | `studio backend` 全 Reactor 执行 `clean package` | `成功` | 完整输出见 `logs/studio-backend-clean-package-20260421-pass.log`；`studio-test=56 tests, 0 failures, 0 errors` |
| `2026-04-21 22:54` | `studio-server` | 以环境变量注入数据源方式启动 `studio-server` | `成功` | 端口 `18080` 监听；日志见 `runtime-logs/studio-server-env.out.log` |
| `2026-04-21 22:55` | `studio-worker` | 以环境变量注入数据源方式启动 `studio-worker` | `成功` | 端口 `18081` 监听；日志见 `runtime-logs/studio-worker-env.out.log` |
| `2026-04-21 23:04` | `studio-desktop-runtime` | 使用独立 SQLite 路径并关闭 Nacos 注册相关能力启动 runtime | `部分成功` | 端口 `18180` 监听，但首轮启动出现调度器查询 `quality_task_schedule` 的瞬时报错；日志见 `runtime-logs/studio-desktop-runtime-env4.out.log` |
| `2026-04-21 23:12` | `backend/studio-server` | 定位 desktop runtime 首轮异常并核对 SQLite 文件 | `完成` | 确认 `quality_task_schedule` 等质量任务表最终已创建，问题为 schema auto-upgrade 与首轮 `@Scheduled` 并发竞态，而非缺表持久化失败 |
| `2026-04-21 23:16` | `backend/studio-server` | 对三个 `fixedDelay` 调度器补充 `initialDelay=30000L` | `完成` | 目标是消除 schema 升级完成前的首次调度抢跑 |
| `2026-04-21 23:16` | `C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend` | 重新执行 `-pl studio-server,studio-desktop-runtime -am clean package` | `失败` | Windows 文件锁阻止删除 `studio-server\\target\\studio-server-0.1.0-SNAPSHOT-exec.jar`，需先停止占用进程再重试 |
| `2026-04-21 23:28` | `C:\dev\ideaProject\DataAggregation\data-aggregation-studio\frontend` | 执行 `npm run lint` | `成功` | 日志见 `logs/frontend-lint-20260421.log`；仅有 `electron_mirror` / `electron-mirror` 旧配置 warning |
| `2026-04-21 23:29` | `C:\dev\ideaProject\DataAggregation\data-aggregation-studio\frontend` | 执行 `npm run build:web` | `成功` | 日志见 `logs/frontend-build-web-20260421.log` |
| `2026-04-21 23:31` | `C:\dev\ideaProject\DataAggregation\data-aggregation-studio\frontend` | 执行 `npm run build:desktop` | `成功` | 日志见 `logs/frontend-build-desktop-20260421.log` |
| `2026-04-21 23:34` | `C:\dev\ideaProject\DataAggregation\data-aggregation-studio\frontend` | 启动 `npm run dev:desktop` 并拉起 Electron + Vite dev server | `成功` | `5174` 已监听，Electron 进程存在；日志见 `runtime-logs/desktop-electron-dev-20260421.out.log` |
| `2026-04-21 23:35` | `studio-server` / `studio-desktop-runtime` | 通过 Chrome DevTools MCP 验证文档页 | `成功` | `doc.html`、Swagger UI、`/v3/api-docs` 在 `18080` 与 `18180` 均可访问 |
| `2026-04-21 23:43` | `studio-desktop-runtime` | 对 fresh SQLite 执行 `StudioDesktopDataInitializerApplication` | `成功` | 已补齐 `admin`、默认租户、默认项目等 bootstrap 数据；日志见 `runtime-logs/studio-desktop-initializer-env1.out.log` |
| `2026-04-22 00:19` | `desktop renderer + runtime` | 首次通过 UI 触发采集任务 `desktop-orders-collect-20260421` | `失败` | 浏览器 `POST /api/v1/collection-tasks/{id}/trigger` 返回 `400`，错误为当前项目无可授权在线 worker；详见错误台账 `E-007` |
| `2026-04-22 00:24` | `http://localhost:5174/#/system?tab=workers` | 通过桌面端“系统管理 -> Worker 下发”绑定 `studio-desktop-worker` 到默认项目 | `成功` | UI 状态从“已下发=否”变为“已下发=是”；SQLite `studio_project_worker_binding` 新增绑定记录 |
| `2026-04-22 00:25` | `http://localhost:5174/#/collection-tasks` | 在桌面端 UI 中新建、保存、上线并重新触发采集任务 `desktop-orders-collect-20260421` | `成功` | 二次触发请求 `POST /api/v1/collection-tasks/2046624665744445441/trigger` 返回 `200`；前端提示“采集任务触发请求已提交” |
| `2026-04-22 00:25` | `studio-desktop-runtime` | 回看 runtime 执行日志与 SQLite 运行记录 | `成功` | `JobContainer` 记录 `SUCCEEDED`；`run_record` 新增 `collection_task_id=2046624665744445441`、`status=SUCCESS`、`worker_code=studio-desktop-worker`，耗时 `957 ms`，`collectedRecords=6` |
| `2026-04-22 00:26` | `http://localhost:5174/#/models/2046623195183714305` | 打开目标模型 `web_chain_orders_agg` 详情并查看样例数据 | `成功` | UI 可见 `ORD-202604-001`、`ORD-202604-002`、`ORD-202604-DUP` 等样本记录，证明桌面端真实采集链路已落到目标库 |
| `2026-04-22 00:28` | `studio-desktop-runtime` | 回看修复后的冷启动调度窗口 | `成功` | `runtime-logs/studio-desktop-runtime-env6.out.log` 在完整 30 秒周期内未再出现 `quality_task_schedule` / `no such table` / `Unexpected error occurred in scheduled task` |
| `2026-04-22 00:43` | `backend/studio-infra` / `backend/studio-desktop-runtime` | 固化 desktop 首次运行补救逻辑 | `完成` | 新增 `DesktopRuntimeBootstrapRunner`，并在 desktop 模式下由 `TenantProjectFoundationService` 自动给默认项目绑定 `studio.worker-code` |
| `2026-04-22 00:45` | `C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend` | 执行定向回归 `StudioInitializationServiceRegressionTest` + `DesktopRuntimeInitializationRegressionTest` | `成功` | 命令：`mvn -q -pl studio-test -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=StudioInitializationServiceRegressionTest,DesktopRuntimeInitializationRegressionTest test`；Redis 仍走 fallback warning，但测试结果为通过 |
| `2026-04-22 00:46` | `C:\dev\ideaProject\DataAggregation\data-aggregation-studio\backend` | 重新执行 `-pl studio-desktop-runtime -am -DskipTests package` | `成功` | 新增 desktop bootstrap runner 后相关模块重新打包通过 |
| `2026-04-22 00:47` | `studio-desktop-runtime`（独立临时实例） | 以全新空 SQLite + 独立端口 `18181` 启动 fresh runtime，验证自动 bootstrap | `成功` | 日志 `runtime-logs/studio-desktop-runtime-autobootstrap.out.log` 显示 `DesktopRuntimeBootstrapRunner` 执行；随后查询 fresh SQLite，`sys_user=1`、`studio_tenant=1`、`studio_project=1`、`studio_project_worker_binding=1`，无需再手工执行 initializer 或 UI 绑 worker |
