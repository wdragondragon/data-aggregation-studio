# 运行调用幂等保护

## 适用范围

幂等保护用于 OMS 公开入口转发到 `studio-worker` 的同步写请求：

| 业务 | REST | SOAP |
| --- | --- | --- |
| 数据接入 | `POST /openapi/data-ingestion-services/{serviceCode}/{serviceKey}` | `POST /openapi/ws/data-ingestion-services/{serviceCode}/{serviceKey}` |
| 协议转换 | `POST/PUT/PATCH /openapi/protocol-conversions/{serviceCode}/{serviceKey}` | `POST /openapi/ws/protocol-conversions/{serviceCode}/{serviceKey}` |

GET、HEAD、OPTIONS、WSDL 查询、数据服务查询和管理端 Debug 不进入本机制。

## 调用方式

调用方在写请求中增加标准 HTTP Header：

```http
Idempotency-Key: 018fb443-f7b6-7b73-8d85-4de81975f828
```

- Key 在“租户 + 项目 + 资源类型 + 资源 ID”内唯一，区分大小写。
- 应使用 UUID、ULID 等高熵全局唯一值；不要使用手机号、账号、时间秒数等可预测值。
- 同一次业务写入的网络重试必须复用同一个 Key；新的业务写入必须生成新 Key。
- 长度为 1 至 255 个可打印字符，不允许重复传多个同名 Header。

示例：

```bash
curl -X POST \
  -H "Idempotency-Key: 018fb443-f7b6-7b73-8d85-4de81975f828" \
  -H "X-Data-Ingestion-Token: <subscription-token>" \
  -H "Content-Type: application/json" \
  -d '{"id":1001,"name":"sample"}' \
  "https://studio.example/openapi/data-ingestion-services/orders/public"
```

## 模式

OMS 与 Worker 必须使用相同配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `STUDIO_RUNTIME_INVOCATION_IDEMPOTENCY_MODE` | `OPTIONAL` | `OPTIONAL` 兼容未传 Key 的历史调用；`REQUIRED_WRITE` 拒绝上述写入口中未传 Key 的请求。 |
| `STUDIO_RUNTIME_INVOCATION_IDEMPOTENCY_MAX_RESPONSE_BYTES` | `10485760` | 可回放响应上限；代码硬上限 16 MiB。超过上限的结果转为 `UNKNOWN`，不允许自动重试。 |
| `STUDIO_RUNTIME_INVOCATION_IDEMPOTENCY_COMPLETED_RETENTION_DAYS` | `7` | `COMPLETED` 记录保留天数，期满后物理清理；清理后相同 Key 可被视为新请求。 |
| `STUDIO_RUNTIME_INVOCATION_IDEMPOTENCY_RUNNING_UNKNOWN_AFTER_HOURS` | `24` | 崩溃遗留的 `RUNNING` 超时后只转 `UNKNOWN`，不会接管或重新执行。 |
| `STUDIO_RUNTIME_INVOCATION_IDEMPOTENCY_CLEANUP_ENABLED` | `true` | 是否启用集群锁保护的清理任务。 |
| `STUDIO_RUNTIME_INVOCATION_IDEMPOTENCY_CLEANUP_BATCH_SIZE` | `1000` | 单批物理清理数量，上限 5000。 |
| `STUDIO_RUNTIME_INVOCATION_IDEMPOTENCY_CLEANUP_MAX_BATCHES` | `20` | 单轮最大批次数，上限 100。 |

建议先以 `OPTIONAL` 上线，完成调用方改造后再将 OMS 和所有 Worker 一次性切换为 `REQUIRED_WRITE`。

## 判定与响应

OMS 不把明文 Key 转发给 Worker。OMS 计算：

- `keyHash = SHA-256(Idempotency-Key)`；
- `fingerprint = SHA-256(方法、服务路径、原始 Query、Content-Type、请求体、稳定业务 Header 名和值)`。

fingerprint 包括订阅 Token、业务 `Authorization` 和显式业务 Header；排除代理 IP、`X-Forwarded-*`、User-Agent、链路追踪 Header、连接 Header 以及 SLB/运行端点配置的传输认证 Header。因此同一请求经不同 OMS、代理或客户端地址重试不会误判，但更换业务凭证或业务 Header 会被识别为不同请求。

| 场景 | HTTP | code/行为 |
| --- | ---: | --- |
| `REQUIRED_WRITE` 未传 Key | 400 | `IDEMPOTENCY_KEY_REQUIRED` |
| Key 格式或内部摘要非法 | 400 | `IDEMPOTENCY_KEY_INVALID` |
| 同 Key、不同 fingerprint | 409 | `IDEMPOTENCY_CONFLICT`，不执行业务 |
| 同 Key仍处于 `RUNNING` | 409 | `IDEMPOTENCY_CONFLICT`，不等待、不接管 |
| 同 Key处于 `UNKNOWN` | 409 | `IDEMPOTENCY_CONFLICT`，不自动重试 |
| 同 Key已 `COMPLETED` | 原状态码 | 原 Content-Type 和原响应体回放，不重复执行业务 |
| 业务已执行但回放结果无法可靠保存 | 409 | `IDEMPOTENCY_RESULT_UNKNOWN` |

确定性的 4xx 业务错误也保存为 `COMPLETED` 并原样回放。未确定的 5xx、进程异常或结果持久化失败进入 `UNKNOWN`。因此数据写入、访问日志、计数器和告警信号仅在第一次实际执行时产生，回放不会再次进入业务 Service。

## 状态与并发

- `RUNNING`：唯一 guard 已成功插入，当前 Worker 是唯一执行者。
- `COMPLETED`：HTTP 状态、Content-Type 和加密响应体已通过 owner CAS 保存，可安全回放。
- `UNKNOWN`：副作用结果无法确认，禁止自动接管、重开或重试。

唯一索引为：

```text
tenant_id + project_id + resource_type + resource_id + key_hash
```

执行所有权使用 `owner_token_hash + owner_instance_id + owner_boot_id + version` CAS。Worker 崩溃后，其他实例不能把遗留 `RUNNING` 当作可领取任务；超过保守阈值后只能转为 `UNKNOWN`。

## 数据与安全

- 表：`studio_runtime_idempotency`。
- 不保存明文 `Idempotency-Key`、请求体、订阅 Token、Cookie、Authorization 或任意 Header 值。
- 表中仅保存 Key SHA-256、包含业务输入的 fingerprint，以及 Studio `EncryptionService` 加密后的回放响应。
- 明文 Key 不进入 Worker 的业务 Header、访问日志、计数器证据或告警证据。
- `UNKNOWN` 不自动删除；需要运维人员依据下游事实人工确认。`COMPLETED` 按保留期清理以控制表增长。

## 升级

1. 执行 `backend/studio-server/src/main/resources/update/20260722/20260722-runtime-invocation-idempotency.sql`。
2. 先用 `OPTIONAL` 同时发布 OMS 与 Worker。
3. 验证数据接入和协议转换 REST/SOAP 的首次执行、并发 409 与响应回放。
4. 所有调用方均已发送 Key 后，再统一切换为 `REQUIRED_WRITE`。

MySQL、桌面 SQLite baseline 和 `StudioSchemaUpgradeService` 已同步该表、唯一索引及状态索引。
