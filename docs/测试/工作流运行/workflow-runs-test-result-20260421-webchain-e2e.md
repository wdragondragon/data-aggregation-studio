# 工作流运行模块 WebChain 全链路实测结果

## 1. 结论

结论：通过。

本轮完成了采集任务 + SQL + JAVA + PYTHON 四节点 DAG 工作流的创建、发布、重跑与成功验证。修复 Python 解释器配置后，整条工作流链路已稳定跑通。

## 2. 执行环境

| 项目 | 信息 |
| --- | --- |
| 执行日期 | 2026-04-21 |
| 前端 | `http://127.0.0.1:5173` |
| Studio Server | `http://127.0.0.1:18080` |
| Studio Worker | `http://127.0.0.1:18081` |
| Worker Code | `studio-online-worker-01` |
| Python 解释器 | `C:\\Users\\jdrag\\.cache\\codex-runtimes\\codex-primary-runtime\\dependencies\\python\\python.exe` |
| 登录账号 | `admin / admin123` |
| 当前租户 / 项目 | `Default Tenant / Default Project` |

## 3. 工作流对象

| 项目 | 值 |
| --- | --- |
| 工作流名称 | `WebChain Full DAG 20260421` |
| 工作流编码 | `webchain_full_dag_20260421` |
| 工作流定义 ID | `2046522723995230210` |
| 发布状态 | `已发布` |

节点顺序：

1. `webchain-orders-collect-20260421`
2. `webchain_sql_report`
3. `webchain_java_verify`
4. `webchain_python_probe`

## 4. 执行结果

最新成功运行：

| 项目 | 值 |
| --- | --- |
| 工作流运行 ID | `2046524040436260866` |
| 开始时间 | `2026-04-21 17:38:50` |
| 结束时间 | `2026-04-21 17:39:01` |
| 运行时长 | `11.0 s` |
| 运行摘要 | `4/4 node(s) completed` |
| 成功节点 | `4` |
| 失败节点 | `0` |

节点结果：

| 节点 | 状态 | 消息 |
| --- | --- | --- |
| `webchain-orders-collect-20260421` | 成功 | `COLLECTION_TASK node completed in 996 ms (mysql8 -> mysql8)` |
| `webchain_sql_report` | 成功 | `webchain_sql_report completed in 179 ms with 6 row(s) returned` |
| `webchain_java_verify` | 成功 | `Java verified report rows = 6` |
| `webchain_python_probe` | 成功 | `Python script executed successfully` |

## 5. 历史说明

页面保留了一次早期失败运行：

| 运行 ID | 原因 | 处理结果 |
| --- | --- | --- |
| `2046523008922689538` | Python 节点缺少 `studio.python.executable` 配置 | 已修复 server/worker 配置，后续重跑成功 |

## 6. 说明

本轮工作流验证全部通过 Web UI 完成。最终 DAG 已完成从采集到脚本处理的全链路串联，可作为后续迁移回归的基准样例。
