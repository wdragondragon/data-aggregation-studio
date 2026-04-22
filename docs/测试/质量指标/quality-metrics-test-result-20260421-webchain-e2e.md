# 质量规则与质量指标模块 WebChain 全链路实测结果

## 1. 结论

结论：通过。

本轮使用 Web UI 将 `17` 条内置质量规则全部应用到目标测试库，逐条完成语义校验、发布和手动执行。质量任务日志汇总为 `17` 成功、`0` 失败；质量指标页已正确纳管 `2` 个资产。

## 2. 执行环境

| 项目 | 信息 |
| --- | --- |
| 执行日期 | 2026-04-21 |
| 前端 | `http://127.0.0.1:5173` |
| Studio Server | `http://127.0.0.1:18080` |
| Studio Worker | `http://127.0.0.1:18081` |
| 目标测试库 | `192.168.188.129:3306/mock_data_target` |
| 登录账号 | `admin / admin123` |
| 当前租户 / 项目 | `Default Tenant / Default Project` |

## 3. 运行汇总

质量任务日志页汇总结果：

| 项目 | 值 |
| --- | --- |
| 质量任务运行次数 | `17` |
| 成功 | `17` |
| 失败 | `0` |
| 运行中 | `0` |

质量指标总览页汇总结果：

| 项目 | 值 |
| --- | --- |
| 执行健康分 | `100` |
| 治理风险指数 | `0` |
| 活跃问题数 | `0` |
| SLA 超时问题数 | `0` |
| 受影响资产数 | `0` |
| 已纳管资产数 | `2` |

已纳管资产：

| 资产 | 健康分 | 风险指数 | 最后执行 |
| --- | --- | --- | --- |
| `web_chain_orders_report` | `100` | `0` | `2026-04-21 18:09:54` |
| `web_chain_orders_agg` | `100` | `0` | `2026-04-21 18:05:23` |

## 4. 质量规则数量指标

| 规则编码 | 规则名称 | 目标模型 / 字段 | 指标值 |
| --- | --- | --- | --- |
| `DQ_TABLE_ROW_COUNT` | 表行数统计 | `web_chain_orders_report` | `6` |
| `DQ_COLUMN_NULL_COUNT` | 字段空值数统计 | `web_chain_orders_report.amount` | `1` |
| `DQ_COLUMN_FILL_RATE` | 字段填充率统计 | `web_chain_orders_report.amount` | `0.83333` |
| `DQ_STRING_BLANK_COUNT` | 字符串空白值统计 | `web_chain_orders_report.customer_name` | `0` |
| `DQ_COLUMN_DISTINCT_COUNT` | 字段去重值数量统计 | `web_chain_orders_report.order_no` | `5` |
| `DQ_COLUMN_DUPLICATE_GROUP_COUNT` | 字段重复分组数统计 | `web_chain_orders_report.order_no` | `1` |
| `DQ_COMPOSITE_KEY_DUPLICATE_GROUP_COUNT` | 组合键重复分组数统计 | `web_chain_orders_agg`，参数 `order_no,event_date` | `1` |
| `DQ_NEGATIVE_VALUE_COUNT` | 负数值数量统计 | `web_chain_orders_report.amount` | `0` |
| `DQ_ZERO_OR_NULL_VALUE_COUNT` | 零值或空值数量统计 | `web_chain_orders_report.amount` | `1` |
| `DQ_FIELD_EQUALITY_INCONSISTENT_COUNT` | 字段间取值不一致数统计 | `web_chain_orders_report.status` 对比 `report_tag` | `6` |
| `DQ_FIELD_ORDER_INVALID_COUNT` | 字段间顺序异常数统计 | `web_chain_orders_agg.created_at` 对比 `updated_at` | `1` |
| `DQ_STALE_TIME_COUNT` | 时间新鲜度过期数统计 | `web_chain_orders_agg.updated_at`，阈值 `'2026-04-20 00:00:00'` | `1` |
| `DQ_FUTURE_TIME_COUNT` | 未来时间值数量统计 | `web_chain_orders_agg.created_at` | `0` |
| `DQ_NUMERIC_RANGE_INVALID_COUNT` | 数值范围异常数统计 | `web_chain_orders_report.amount`，范围 `50~150` | `1` |
| `DQ_ENUM_INVALID_COUNT` | 枚举值异常数统计 | `web_chain_orders_report.status`，枚举 `'PAID','SETTLED'` | `1` |
| `DQ_STRING_LENGTH_INVALID_COUNT` | 字符串长度异常数统计 | `web_chain_orders_report.status`，长度 `4~4` | `1` |
| `DQ_LIKE_PATTERN_INVALID_COUNT` | LIKE 格式异常数统计 | `web_chain_orders_report.report_tag`，模式 `'WEBCHAIN-PAID'` | `1` |

## 5. 说明

本轮质量测试全部通过 Web UI 完成，未直接调用业务接口。由于本轮未开启告警条件，质量指标页呈现为高健康、零风险状态，但规则执行、数量输出与资产纳管均已验证正常。
