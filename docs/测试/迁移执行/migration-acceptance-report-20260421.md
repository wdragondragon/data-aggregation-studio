# DataAggregation / Studio 迁移验收报告（2026-04-21）

## 1. 验收范围

- 主仓 `DataAggregation` 根 Reactor 构建、测试、本地安装
- `studio backend` 全 Reactor 测试与打包
- `studio-server`、`studio-worker`、`studio-desktop-runtime` 启动与联通验证
- `frontend` 的 `lint`、`build:web`、`build:desktop`
- Web 补充回归与 Desktop 联调
- OpenAPI / Swagger / Knife4j / `doc.html` / Nacos 配置 / 重启补测

## 2. 当前状态总览

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 主仓根 Reactor | `已通过` | `2026-04-21 22:37` 全量 `clean install` 成功，日志见 `logs/root-reactor-clean-install-20260421-pass2.log` |
| Studio backend | `已通过` | `2026-04-21 22:47` 全 Reactor `clean package` 成功，`studio-test=56/0/0` |
| 三个后端进程启动 | `修复后通过` | `studio-server:18080`、`studio-worker:18081`、`studio-desktop-runtime:18180` 均已稳定启动；desktop runtime 冷启动调度竞态经 `initialDelay=30000L` 修复后不再复现 |
| 前端构建 | `已通过` | `npm run lint`、`npm run build:web`、`npm run build:desktop` 全部通过，日志分别见 `logs/frontend-lint-20260421.log`、`logs/frontend-build-web-20260421.log`、`logs/frontend-build-desktop-20260421.log` |
| Web 补测 | `已通过` | 继续沿用 `docs/测试/数据采集/collection-task-test-result-20260421-webchain-e2e.md`、`docs/测试/数据资产/model-sync-and-lineage-test-result-20260421-webchain-e2e.md`、`docs/测试/数据开发/data-development-test-result-20260421-webchain-e2e.md` 作为历史基线；本轮额外确认 JDK17 + Boot3 启动、文档页与 API docs 正常 |
| Desktop 联调 | `修复后通过` | Electron + Vite 已启动；已完成 `admin` 登录、两个 MySQL 数据源连接、源/目标模型同步、单表采集任务创建/上线/触发、目标模型样例数据验证；随后又通过 fresh SQLite 启动验证了自动 bootstrap 与默认 worker 绑定 |
| 文档页补测 | `已通过` | `studio-server` 与 `studio-desktop-runtime` 的 `doc.html`、Swagger UI、`/v3/api-docs` 已通过 Chrome DevTools MCP 验证 |
| Nacos / 配置模式补测 | `遗留但不阻塞` | `studio-server` 与 `studio-worker` 配置仍保持 `spring.config.import=nacos:...`，代码未回退到 `bootstrap.yml`；本轮本地验收未连接独立 Nacos 环境做额外联机演练 |

## 3. 最终结论

### 3.1 已通过

- 主仓 `DataAggregation` 已在 `JDK 17 + Maven 3.8.8` 下完成可复现的根 Reactor `clean install`。
- `studio backend` 全 Reactor 已通过，且 `studio-test` 保持 `56 tests, 0 failures, 0 errors`。
- `studio-server`、`studio-worker`、`studio-desktop-runtime` 三个进程都已在当前迁移版本上启动并完成基本联通验证。
- `frontend` 的 `lint`、`build:web`、`build:desktop` 全部通过。
- Web 侧历史实链路测试文档可继续作为迁移基线，且本轮已补证文档页、OpenAPI 页面与 Boot3/JDK17 启动态。
- Desktop 侧已完成真实 UI 闭环：登录、数据源连接、模型同步、单表采集任务保存/上线/触发、目标模型样例数据验证。
- Desktop 首次运行所需的默认用户/租户/项目数据和默认项目 worker 绑定已通过代码固化，fresh SQLite 启动时无需再手工执行 initializer 或 UI 补绑。

### 3.2 修复后通过

- Desktop runtime 首轮调度与 schema 初始化并发导致的 `quality_task_schedule` 抢跑问题已通过调度器 `initialDelay=30000L` 修复。
- Desktop fresh SQLite 缺少 bootstrap 用户/租户/项目数据，已先通过 `StudioDesktopDataInitializerApplication` 验证补救，再通过 `DesktopRuntimeBootstrapRunner` 固化为启动期幂等初始化。
- Desktop 采集任务首次触发因项目未绑定 worker 返回 `400`，已先通过“系统管理 -> Worker 下发”完成项目级授权后重新触发成功，再将默认项目 worker 绑定固化到 desktop 模式初始化流程。

### 3.3 遗留但不阻塞

- 当前本地收口验证没有外部独立 Nacos 环境参与，因此未单独补做一次 `spring.config.import=nacos:...` 的联机拉配置演练；但配置文件中该模式保持不变，未回退到 `bootstrap.yml`。

### 3.4 关键证据

- 根构建日志：`logs/root-reactor-clean-install-20260421-pass2.log`
- Backend 构建日志：`logs/studio-backend-clean-package-20260421-pass.log`
- Frontend 构建日志：`logs/frontend-lint-20260421.log`、`logs/frontend-build-web-20260421.log`、`logs/frontend-build-desktop-20260421.log`
- Desktop 初始化日志：`runtime-logs/studio-desktop-initializer-env1.out.log`
- Desktop runtime 稳定运行日志：`runtime-logs/studio-desktop-runtime-env6.out.log`
- Desktop 自动 bootstrap 验证日志：`runtime-logs/studio-desktop-runtime-autobootstrap.out.log`
- Web 历史实链路文档：
  - `docs/测试/数据采集/collection-task-test-result-20260421-webchain-e2e.md`
  - `docs/测试/数据资产/model-sync-and-lineage-test-result-20260421-webchain-e2e.md`
  - `docs/测试/数据开发/data-development-test-result-20260421-webchain-e2e.md`

### 3.5 当前判断

在“本地 JDK17 + Spring Boot 3 收口验收”这一范围内，当前迁移可以认为已经取得成功。

判断依据是：

- 构建链路已闭环。
- 三个后端进程与桌面前端都已跑起。
- Web 侧有既有真实链路基线文档作为覆盖证据。
- Desktop 侧已经补齐至少一条真实数据采集链路，并拿到 UI、runtime 日志、SQLite `run_record` 三重证据。

如果后续要把“外部独立 Nacos 联机环境演练”也纳入严格最终验收，则还需要在可用 Nacos 环境中补一轮环境级验证；除此之外，本轮计划内的本地迁移收口已经基本完成。
