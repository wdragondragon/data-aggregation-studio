# 数据开发模块 WebChain 全链路实测结果

## 1. 结论

结论：通过。

本轮在真实 Web 环境下完成了 SQL、JAVA、PYTHON 三类数据开发脚本的创建与执行，均能正常运行并输出预期结果。

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

## 3. 测试脚本

| 脚本类型 | 脚本名称 | 脚本 ID | 结果 |
| --- | --- | --- | --- |
| SQL | `webchain_sql_report` | `2046518560695525377` | 通过 |
| JAVA | `webchain_java_verify` | `2046519647909453825` | 通过 |
| PYTHON | `webchain_python_probe` | `2046521271163822081` | 通过 |

## 4. 现场结果

### 4.1 SQL 脚本

`webchain_sql_report` 执行成功，结果页显示：

| 项目 | 值 |
| --- | --- |
| 语句数 | `3` |
| 返回行数 | `6` |
| 结果表 | `web_chain_orders_report` |

结果样例：

| `order_id` | `amount_bucket` | `report_tag` |
| --- | --- | --- |
| `1001` | `MEDIUM` | `WEBCHAIN-PAID` |
| `1004` | `HIGH` | `WEBCHAIN-PAID` |
| `1005` | `MISSING` | `WEBCHAIN-PAID` |
| `1006` | `LOW` | `WEBCHAIN-UNKNOWN` |

### 4.2 JAVA 脚本

`webchain_java_verify` 执行成功，执行结果摘要：

| 项目 | 值 |
| --- | --- |
| 校验消息 | `Java verified report rows = 6` |
| 结果 | 通过 |

### 4.3 PYTHON 脚本

`webchain_python_probe` 执行成功，执行结果摘要：

| 项目 | 值 |
| --- | --- |
| `tenantId` | `default` |
| `datasourceCount` | `2` |
| 结果 | 通过 |

## 5. 说明

本轮三类脚本均通过 Web UI 执行完成。SQL 结果已成功落地到目标测试库，JAVA 与 PYTHON 运行时链路也已验证可用。
