# 质量指标模块 Web 冒烟结果

## 1. 结论

结论：通过。

本轮确认质量指标页面的筛选区、Tab 切换和空态渲染在迁移后可正常工作。

## 2. 执行环境

| 项目 | 信息 |
| --- | --- |
| 执行日期 | 2026-04-21 |
| 前端 | `http://127.0.0.1:5173` |
| Studio Server | `http://127.0.0.1:18080` |
| Studio Worker | `http://127.0.0.1:18081` |
| 数据库 | SQLite：`backend/studio-test/target/test-runtime/web-smoke.db` |
| 登录账号 | `admin / admin123` |
| 当前租户 / 项目 | `Default Tenant / Default Project` |
| 关联用例文档 | `quality-metrics-ui-test-cases.md` |

## 3. 接口返回记录

本轮页面首屏相关请求返回均为 `200`：

| 接口 | 结果 |
| --- | --- |
| `GET /api/v1/auth/me` | `200` |
| `GET /api/v1/quality-metrics/options` | `200` |
| `POST /api/v1/quality-metrics/dashboard/query` | `200` |
| `POST /api/v1/quality-metrics/assets/query` | `200` |
| `POST /api/v1/quality-metrics/issues/query` | `200` |

## 4. 执行结果

| 用例 | 验证点 | 结果 |
| --- | --- | --- |
| QM-UI-SMOKE-001 | `/quality-metrics` 页面正常进入，默认时间窗为 `7 天` | 通过 |
| QM-UI-SMOKE-002 | 总览 Tab 正常加载，双分、风险、趋势、分布、覆盖矩阵空态正常 | 通过 |
| QM-UI-SMOKE-003 | 切换到“资产洞察”Tab 成功，资产列表空态正常 | 通过 |
| QM-UI-SMOKE-004 | 全局筛选区中的时间、数据源、模型、维度、粒度、状态控件完整显示 | 通过 |
| QM-UI-SMOKE-005 | 浏览器控制台未发现 `error` / `warn` | 通过 |

## 5. 现场记录

总览页关键展示：

| 项目 | 值 |
| --- | --- |
| 执行健康分 | `0` |
| 治理风险指数 | `0` |
| 活跃问题数 | `0` |
| SLA 超时问题数 | `0` |
| 受影响资产数 | `0` |
| 已纳管资产数 | `0` |

资产洞察页关键展示：

| 项目 | 值 |
| --- | --- |
| 资产列表总数 | `0` |
| 空态文案 | `暂无数据` |

## 6. 本轮未覆盖

以下能力本轮未继续深测：

| 项目 | 说明 |
| --- | --- |
| 问题中心处置动作 | 本轮无质量问题数据 |
| 资产抽屉、日志抽屉 | 本轮无质量资产与运行日志数据 |
| 质量任务跳转 | 本轮未准备质量任务样例数据 |

## 7. 说明

本轮属于迁移后的 Web 可用性冒烟，目标是确认首屏接口、筛选器、Tab 和空态没有被 Boot3/JDK17 迁移破坏。深度功能仍应结合既有专项用例继续验证。
