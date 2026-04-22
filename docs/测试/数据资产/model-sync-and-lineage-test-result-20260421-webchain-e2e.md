# 数据资产模块 WebChain 全链路实测结果

## 1. 结论

结论：通过。

本轮完成了 `mock_data` / `mock_data_target` 两套测试库的模型同步、mock 数据准备与模型血缘验证。自动血缘能够按单表采集任务的真实源目标关系连通；手动血缘新增后可立即体现在血缘统计与图谱中。

## 2. 执行环境

| 项目 | 信息 |
| --- | --- |
| 执行日期 | 2026-04-21 |
| 前端 | `http://127.0.0.1:5173` |
| Studio Server | `http://127.0.0.1:18080` |
| Studio Worker | `http://127.0.0.1:18081` |
| Studio 元数据库 | `192.168.188.129:3306/data_aggregation_studio_webchain_20260421` |
| 源测试库 | `192.168.188.129:3306/mock_data` |
| 目标测试库 | `192.168.188.129:3306/mock_data_target` |
| 登录账号 | `admin / admin123` |
| 当前租户 / 项目 | `Default Tenant / Default Project` |
| mock 脚本 | `data-aggregation-studio/scripts/seed-web-chain-mock-data.py` |

## 3. 模型同步与 mock 数据

模型同步后，当前项目内可见以下模型：

| 数据源 | 模型 |
| --- | --- |
| `webchain-src-20260421` | `web_chain_orders_src` |
| `webchain-src-20260421` | `web_chain_customers_src` |
| `webchain-tgt-20260421` | `web_chain_orders_agg` |
| `webchain-tgt-20260421` | `web_chain_orders_report` |
| `webchain-tgt-20260421` | `web_chain_orders_snapshot` |
| `webchain-tgt-20260421` | `web_chain_customer_ref` |

mock 脚本执行后的初始数据如下：

| 数据库 | 表 | 行数 |
| --- | --- | --- |
| `mock_data` | `web_chain_orders_src` | `6` |
| `mock_data` | `web_chain_customers_src` | `6` |
| `mock_data_target` | `web_chain_orders_agg` | `0` |
| `mock_data_target` | `web_chain_orders_report` | `0` |
| `mock_data_target` | `web_chain_orders_snapshot` | `4` |
| `mock_data_target` | `web_chain_customer_ref` | `6` |

## 4. 自动血缘验证

聚焦模型：`web_chain_orders_agg`

### 4.1 库级血缘

| 验证点 | 结果 |
| --- | --- |
| 上游数据库 | `mock_data` |
| 下游数据库 | `mock_data_target` |
| 创建方式 | `自动` |
| 结果 | 通过 |

### 4.2 表级血缘

| 验证点 | 结果 |
| --- | --- |
| 上游表 | `web_chain_orders_src` |
| 下游表 | `web_chain_orders_agg` |
| 创建方式 | `自动` |
| 直接上游数 | `1` |
| 结果 | 通过 |

### 4.3 字段级血缘

字段级血缘页面可见 `10` 条直接上游字段映射，均为 `web_chain_orders_src` 到 `web_chain_orders_agg` 的同名字段自动连接：

`order_id`、`order_no`、`customer_id`、`customer_name`、`amount`、`status`、`created_at`、`updated_at`、`event_date`、`source_flag`

| 验证点 | 结果 |
| --- | --- |
| 全部上游字段数 | `10` |
| 直接上游字段数 | `10` |
| 创建方式 | `自动` |
| 结果 | 通过 |

## 5. 手动血缘验证

在 `web_chain_orders_agg` 的表级血缘页，手动新增了如下关系：

| 源模型 | 目标模型 | 级别 | 创建方式 |
| --- | --- | --- | --- |
| `web_chain_orders_snapshot` | `web_chain_orders_agg` | 表级 | 手动 |

保存后的页面反馈与统计变化：

| 验证点 | 结果 |
| --- | --- |
| 页面提示 | `手动血缘已保存` |
| 表级全部上游数 | `1 -> 2` |
| 表级直接上游数 | `1 -> 2` |
| 图谱中新增 `手动` 标识 | 是 |
| 结果 | 通过 |

## 6. 说明

本轮血缘验证全部通过 Web UI 完成。自动血缘与单表采集链路一致，手动血缘新增后立即可见，满足本轮链路测试要求。
