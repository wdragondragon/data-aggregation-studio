# 数据采集模块 WebChain 全链路实测结果

## 1. 结论

结论：通过。

本轮基于真实 MySQL 测试库完成单表采集任务创建、发布、执行与运行日志验证，`web_chain_orders_src -> web_chain_orders_agg` 链路正常。

## 2. 执行环境

| 项目 | 信息 |
| --- | --- |
| 执行日期 | 2026-04-21 |
| 前端 | `http://127.0.0.1:5173` |
| Studio Server | `http://127.0.0.1:18080` |
| Studio Worker | `http://127.0.0.1:18081` |
| 源测试库 | `192.168.188.129:3306/mock_data` |
| 目标测试库 | `192.168.188.129:3306/mock_data_target` |
| 登录账号 | `admin / admin123` |
| 当前租户 / 项目 | `Default Tenant / Default Project` |

## 3. 测试对象

| 项目 | 值 |
| --- | --- |
| 源数据源 | `webchain-src-20260421` |
| 目标数据源 | `webchain-tgt-20260421` |
| 采集任务名称 | `webchain-orders-collect-20260421` |
| 采集任务 ID | `2046505490006941698` |
| 源模型 | `web_chain_orders_src` |
| 目标模型 | `web_chain_orders_agg` |

## 4. 执行结果

最新成功运行记录如下：

| 项目 | 值 |
| --- | --- |
| 开始时间 | `2026-04-21 17:38:50` |
| 结束时间 / 耗时 | `2026-04-21 17:38:51 / 996 ms` |
| Worker | `studio-online-worker-01` |
| 采集数 | `6` |
| 成功数 | `6` |
| 失败数 | `0` |
| 过滤数 | `0` |
| 运行消息 | `COLLECTION_TASK node completed in 996 ms (mysql8 -> mysql8)` |

## 5. 历史记录

当前页面共显示 `4` 次运行：

| 状态 | 次数 | 说明 |
| --- | --- | --- |
| 成功 | `3` | 链路修复后重复执行均成功 |
| 失败 | `1` | 早期运行因 worker 依赖错位触发 `AggregationException`，已定位并修复 |

## 6. 说明

本轮单表采集验证全部通过 Web UI 完成，未直接调用业务接口。后续若继续扩展采集任务，可直接复用同一组真实测试库与数据源配置。
