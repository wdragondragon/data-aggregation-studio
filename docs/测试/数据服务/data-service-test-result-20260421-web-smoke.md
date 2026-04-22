# 数据服务模块 Web 冒烟结果

## 1. 结论

结论：通过。

本轮只做 Web 首屏与新建编辑页的轻量冒烟，不替代已有的详细接口与交互专项测试。

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
| 关联用例文档 | `data-service-test-cases.md` |

## 3. 执行结果

| 用例 | 验证点 | 结果 |
| --- | --- | --- |
| DS-UI-SMOKE-001 | `/data-services` 页面正常进入，查询区与服务列表渲染正常 | 通过 |
| DS-UI-SMOKE-002 | 无服务数据时页面展示“暂无数据”，无白屏 | 通过 |
| DS-UI-SMOKE-003 | 点击“新建服务”可进入 `/data-services/new` | 通过 |
| DS-UI-SMOKE-004 | 新建页四步向导、基础信息表单、“保存/发布”按钮正常显示 | 通过 |
| DS-UI-SMOKE-005 | 浏览器控制台未发现 `error` / `warn` | 通过 |

## 4. 现场记录

新建页已验证以下关键区域：

| 区域 | 状态 |
| --- | --- |
| 四步向导 | 已显示 |
| 服务 Code | 已显示 |
| 服务名称 | 已显示 |
| 服务类型 | 已显示，默认 `模型发布` |
| 状态 | 已显示，默认 `未保存` |
| 上一步 / 下一步 | 已显示 |

## 5. 本轮未覆盖

以下能力不在本轮 Web 冒烟范围内：

| 项目 | 说明 |
| --- | --- |
| 服务保存、发布、订阅、开放调用 | 已由 [data-service-test-cases.md](C:/dev/ideaProject/DataAggregation/data-aggregation-studio/docs/测试/数据服务/data-service-test-cases.md) 与既有专项结果覆盖 |
| 调试模板、cURL 生成、监控与访问日志 | 本轮未准备专项业务数据 |

## 6. 说明

本轮的目标是确认数据服务 Web 主页面和编辑入口在迁移后仍可正常进入，不对专项数据服务能力验证形成替代。
