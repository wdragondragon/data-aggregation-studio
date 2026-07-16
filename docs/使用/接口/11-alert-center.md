# 统一告警中心接口

覆盖 `/alerts` 页面使用的告警规则、告警事件、Webhook 通道、投递记录和租户汇总接口。所有接口均使用 `com.jdragon.studio.dto.common.Result<T>`，分页数据位于 `data.items`。

## 调用约定

- API 根路径：`/api/v1/alerts`
- 项目接口必须携带 `Authorization`、`X-Tenant-Id`、`X-Project-Id`。
- 项目成员可查看并确认、关闭事件；规则、通道和投递重试仅允许项目管理员、租户管理员或超级管理员。
- 规则、事件、通道和投递严格按当前项目隔离；`tenant-summary/query` 仅返回当前租户的项目摘要。

## SDK 与接口

| SDK | 方法 | 路径 | 返回 |
|---|---|---|---|
| `alerts.options()` | GET | `/alerts/options` | `AlertOptionsView` |
| `alerts.subjects()` | GET | `/alerts/subjects` | `PageResult<AlertSelectOptionView>` |
| `alerts.recipientOptions()` | GET | `/alerts/recipient-options` | `PageResult<AlertSelectOptionView>` |
| `alerts.summary()` | GET | `/alerts/summary` | `AlertSummaryView` |
| `alerts.tenantSummary()` | POST | `/alerts/tenant-summary/query` | `PageResult<AlertTenantProjectSummaryView>` |
| `alerts.queryRules()` | POST | `/alerts/rules/query` | `PageResult<AlertRuleView>` |
| `alerts.getRule()` | GET | `/alerts/rules/{id}` | `AlertRuleView` |
| `alerts.saveRule()` | POST | `/alerts/rules` | `AlertRuleView` |
| `alerts.deleteRule()` | DELETE | `/alerts/rules/{id}` | `void` |
| `alerts.enableRule()` / `disableRule()` / `testRule()` | POST | `/alerts/rules/{id}/enable|disable|test` | 规则或测试事件 |
| `alerts.queryIncidents()` | POST | `/alerts/incidents/query` | `PageResult<AlertIncidentView>` |
| `alerts.getIncident()` | GET | `/alerts/incidents/{id}` | `AlertIncidentView`，含最近事件和投递 |
| `alerts.incidentEvents()` | GET | `/alerts/incidents/{id}/events` | `PageResult<AlertEventView>` |
| `alerts.acknowledgeIncident()` / `closeIncident()` | POST | `/alerts/incidents/{id}/acknowledge|close` | `AlertIncidentView` |
| `alerts.queryChannels()` | POST | `/alerts/channels/query` | `PageResult<AlertChannelView>` |
| `alerts.getChannel()` / `saveChannel()` / `deleteChannel()` | GET/POST/DELETE | `/alerts/channels...` | 通道详情或 `void` |
| `alerts.enableChannel()` / `disableChannel()` / `testChannel()` | POST | `/alerts/channels/{id}/enable|disable|test` | 通道或测试事件 |
| `alerts.queryDeliveries()` | POST | `/alerts/deliveries/query` | `PageResult<AlertDeliveryView>` |
| `alerts.retryDelivery()` | POST | `/alerts/deliveries/{id}/retry` | `AlertDeliveryView` |

## 权限能力与租户摘要

- `GET /alerts/options` 通过 `canManage`、`canHandleIncidents` 和 `canViewTenantSummary` 返回当前用户的前端操作能力。
- `canViewTenantSummary` 仅对当前租户的租户管理员和超级管理员为 `true`；前端据此显示“租户告警摘要”入口，普通项目成员和项目管理员不显示该入口。
- `POST /alerts/tenant-summary/query` 请求体支持 `keyword`、`pageNo`、`pageSize`，按项目名称搜索并分页返回当前租户内项目汇总。
- 租户摘要每个项目返回 `enabledRuleCount`、`openIncidentCount`、`criticalIncidentCount` 和 `failedDeliveryCount`，不返回规则条件、事件证据或通道秘密。
- `openIncidentCount` 只统计 `OPEN`；`ACKNOWLEDGED` 不计入该字段。`criticalIncidentCount` 统计 `OPEN` 与 `ACKNOWLEDGED` 中的严重告警。

## 保存规则

```json
{
  "name": "采集任务连续失败",
  "ruleType": "CONSECUTIVE_FAILURES",
  "subjectType": "COLLECTION_TASK",
  "subjectId": 123,
  "severity": "CRITICAL",
  "enabled": true,
  "condition": { "consecutiveCount": 3 },
  "silenceMinutes": 30,
  "recoveryNotificationEnabled": true,
  "inAppEnabled": true,
  "recipientUserIds": [1, 2],
  "notifyResourceOwner": true,
  "notifyProjectAdmins": true,
  "webhookChannelIds": [9001]
}
```

- `id` 为空时新增，存在时更新；同一项目内有效规则名称不可重复。
- 更新时省略 `enabled` 会保留原启停状态，不会因为局部编辑意外启用或停用规则。
- `subjectId` 为空表示监控该项目下同类型全部资源；项目队列和日志存储不接收 `subjectId`。
- `condition` 字段和默认值以 `GET /alerts/options` 返回的 schema 为准；每类规则的推荐初始级别由 `defaultSeverity` 返回。
- 必须存在一个有效站内信接收来源或 Webhook 通道。
- 删除规则前必须关闭该规则的 `OPEN`、`ACKNOWLEDGED` 事件。
- 工作流连续失败按已完成的 `workflow_run_id` 聚合计算；同一次工作流的多个节点记录只计为一次结果，仍在运行的工作流不参与连续失败判断。

## 汇总钻取筛选

- `POST /alerts/incidents/query` 支持 `activeOnly=true`，仅返回 `OPEN`、`ACKNOWLEDGED`，用于严重告警汇总钻取。
- `POST /alerts/deliveries/query` 支持 `failedOnly=true`，仅返回 `RETRY`、`DEAD`，用于失败投递汇总钻取。
- `GET /alerts/incidents/{id}` 的 `recentDeliveries` 返回该事件最近投递记录，前端可直接展示状态、尝试次数、响应摘要和脱敏错误。

## 保存 Webhook

```json
{
  "name": "统一运维 Webhook",
  "endpointUrl": "https://hooks.example.com/studio-alert",
  "headers": { "Authorization": "Bearer value" },
  "signingSecret": "optional-hmac-secret",
  "enabled": true
}
```

- URL、Header 值和签名密钥使用 `EncryptionService` 加密保存。
- 查询只返回 `endpointMasked`、`headerNames` 和 `hasSigningSecret`，不会返回明文秘密。
- 更新时 `endpointUrl`、`headers`、`signingSecret` 留空即保留旧值；`clearSigningSecret=true` 明确清除签名密钥，且不能同时提交新的 `signingSecret`。
- 更新时省略 `enabled` 会保留原启停状态。
- URL 最长 2048 字符且不允许 fragment；默认仅允许 HTTPS，并拒绝回环、私网、链路本地、IPv6 过渡地址和云元数据地址；内部地址必须在服务端 `studio.alert.webhook.allowed-hosts` 中显式允许。
- 自定义 Header 最多 32 个，名称最长 128 字符，单值最长 4000 字符，总大小最多 16 KB；禁止重复名称和连接级、代理认证、内容类型及 `X-Studio-*` Header。
- 禁止自动重定向；连接超时 3 秒、请求超时 5 秒，最多读取 16 KB 响应。
- HTTP 客户端关闭隐式自动重试，单次 outbox 尝试最多发起一次 HTTP 请求，避免 `429`、网络抖动等场景绕过统一退避策略。

## Webhook 消息

固定协议为 `studio.alert.webhook.v1`：

```json
{
  "schemaVersion": "studio.alert.webhook.v1",
  "eventId": "10001",
  "eventType": "TRIGGERED",
  "occurredAt": "2026-07-13T22:00:00",
  "tenantId": "default",
  "projectId": "1",
  "rule": { "id": "10", "name": "采集失败", "type": "EXECUTION_FAILED", "severity": "WARNING" },
  "incident": { "id": "20", "status": "OPEN", "occurrenceCount": 1 },
  "subject": { "type": "COLLECTION_TASK", "id": "30", "key": "30", "name": "订单同步", "path": "/collection-task-runs?collectionTaskId=30" },
  "summary": "订单同步执行失败",
  "evidence": { "runRecordId": "40", "status": "FAILED" }
}
```

- `eventId`、`projectId` 以及规则、事件、对象和证据中的 Long 标识符按字符串发送，避免 JavaScript 消费方发生 64 位整数精度丢失。

配置签名密钥后额外发送：

- `X-Studio-Event-Id`
- `X-Studio-Timestamp`
- `X-Studio-Signature-SHA256 = hex(HMAC-SHA256(secret, timestamp + "." + body))`

## 投递与重试

- 站内信和 Webhook 都先写入 `studio_alert_delivery` outbox，业务事务不等待网络调用。
- 408、429、5xx 和网络异常按 1、5、15、60 分钟重试，最多 5 次；其他 4xx 直接进入 `DEAD`。
- `PENDING / PROCESSING / RETRY / SUCCEEDED / DEAD / SKIPPED` 为完整投递状态。
- 服务端关闭 `studio.alert.webhook.enabled` 时，待处理 Webhook 保持原状态并暂停发送，重新开启后继续处理。
- 通道被禁用或站内信接收用户被禁用时，对应投递进入 `SKIPPED`，不计入失败投递；通道测试仍允许验证禁用通道的连通性。
- 手工重试支持 `DEAD / RETRY / SKIPPED`，会清空旧错误并重新从第一次尝试开始。
- Webhook 投递关联的通道必须仍然存在；通道已删除时重试接口返回 `BUSINESS_ERROR`，不会把必然失败的投递重新放回队列。

## 升级与配置

- MySQL 增量 SQL：`backend/studio-server/src/main/resources/update/20260713/20260713-alert-center.sql`
- 全量 schema 已同步 MySQL 和 SQLite；`StudioSchemaUpgradeService` 也会补建五张告警表和索引。
- 首次升级不创建默认规则，不扫描历史终态失败。
- 总开关：`studio.alert.enabled`；评估、投递和 Webhook 分别有独立子开关。
- Webhook 安全与内网白名单配置见 `docs/运维/监控/alert-webhook-security.md`。
- 数据库升级与回滚说明见 `docs/数据库/alert-center-upgrade.md`。
