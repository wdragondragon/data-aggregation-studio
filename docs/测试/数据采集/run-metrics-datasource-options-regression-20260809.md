# 指标监控数据源选项回归记录

> 日期：2026-08-09
> 状态：已完成
> 页面：`/run-metrics`
> 接口：`GET /api/v1/run-metrics/options`

## 问题与修复

指标监控的数据源选项原先只从 `collection_task_metric_binding` 的源/目标绑定快照组装。当前项目存在数据源、但没有可解析任务绑定时，接口返回空 `datasources`，导致页面数据源下拉没有值。

修复后，`datasources` 来自 `DataSourceService.listMetricFilterOptions()` 的当前租户/项目可访问轻量选项；任务绑定继续补充历史快照并提供 `sourceModels`、`targetModels`。接口路径和响应字段不变，不读取完整数据源定义、连接配置或健康记录。

## 回归用例

| 编号 | 场景 | 预期结果 | 状态 |
|---|---|---|---|
| RM-DS-001 | 项目存在数据源且没有采集任务绑定 | `data.datasources` 返回可访问数据源，模型选项为空 | 通过 |
| RM-DS-002 | 数据源同时出现在可访问列表和任务绑定快照 | 按 ID 去重，优先显示数据源管理中的当前名称和类型 | 通过 |
| RM-DS-003 | 项目存在采集任务源/目标模型绑定 | `sourceModels`、`targetModels` 继续正常返回 | 通过 |
| RM-DS-004 | 不同租户、项目或未授权共享数据源 | 不进入当前项目的数据源选项 | 通过 |
| RM-DS-005 | 浏览器打开数据采集 → 指标监控 | 数据源下拉有值，页面无控制台错误 | 通过 |

## 验证结果

- `MetricsSourceSlimmingRegressionTest` 与 `DataSourceListSourceSlimmingRegressionTest`：16 项通过，0 失败，覆盖无任务绑定、按 ID 去重、当前名称优先、任务模型选项和轻量字段查询。
- `FileTransferMetricServiceTest`：2 项通过，确认公共运行指标与文件传输指标组合未回归。
- `RunMetricsApiRegressionTest`：1 项通过，确认 `/run-metrics/options` 和 `/run-metrics/query` 原有 API 契约不变。首次与运行中的 Server 争用 Arthas 端口，测试进程关闭 Arthas 后重跑通过。
- 真实页面 `Default Tenant / Default Project`：数据源下拉返回 `aliyun oss / minio`、`ftp测试源 / ftp`、`测试库 / mysql8` 共 3 项，控制台无错误。
- Studio backend 已重启，`http://127.0.0.1:18080/actuator/health` 返回 `UP`。
- 本次无前端代码、数据库字段、DDL 或升级脚本变化；Worker 不需要重启。
