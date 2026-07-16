# 统一告警中心接口

覆盖 `/alerts` 页面使用的告警规则、告警事件、Webhook/eLink 通道、投递记录和租户汇总接口。所有接口均使用 `com.jdragon.studio.dto.common.Result<T>`，分页数据位于 `data.items`。

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

- `GET /alerts/options` 通过 `canManage`、`canHandleIncidents`、`canViewTenantSummary` 和 `elinkChannelEnabled` 返回当前用户及当前运行形态的前端能力。`elinkChannelEnabled=false` 时页面不提供新建 eLink 通道，直接调用保存接口也会被拒绝。
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
- 必须存在一个有效站内信接收来源或已启用通知通道。
- 为保持现有规则接口兼容，字段名仍为 `webhookChannelIds`，但列表现在可以同时包含 `WEBHOOK` 和 `ELINK` 通道 ID。
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
  "channelType": "WEBHOOK",
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

## 保存 eLink 通道

保存接口仍为 `POST /api/v1/alerts/channels`。个人账号和群组是两种互斥配置。

个人账号示例：

```json
{
  "name": "生产值班账号",
  "channelType": "ELINK",
  "elinkTargetType": "PERSONAL",
  "elinkUserIds": ["zhangsan", "lisi"],
  "enabled": true
}
```

群组示例：

```json
{
  "name": "生产运维群",
  "channelType": "ELINK",
  "elinkTargetType": "GROUP",
  "elinkGroupId": 123456,
  "enabled": true
}
```

`AlertChannelView` 的 eLink 数据片段如下：

```json
{
  "id": "9002",
  "name": "生产运维群",
  "channelType": "ELINK",
  "elinkTargetType": "GROUP",
  "elinkUserIds": [],
  "elinkGroupId": "123456",
  "enabled": true,
  "lastTestStatus": "SUCCEEDED"
}
```

- `channelType` 新增时应显式传 `ELINK`；通道创建后不能在 `WEBHOOK` 和 `ELINK` 之间改类型。
- `PERSONAL` 必须传至少一个 `elinkUserIds`，Studio 会去重；不接收手机号、`@all` 或包含 `|` 的组合值。
- `GROUP` 必须传正整数 `elinkGroupId`，它表示 eLink Manager 已存在的本地群组 ID。
- eLink 通道不接收 `endpointUrl`、`headers`、`signingSecret` 或 `clearSigningSecret`。
- eLink 配置只包含账号列表或群组 ID，不包含 SLB 地址、认证 Header、手机号、自定义模板或自定义消息类型。

## eLink Manager 调用协议

Studio 不修改 eLink Manager 生产发送代码，只通过当前应用所在的同一 Nacos `namespace` 和 `group` 发现服务。服务名由服务端统一配置，默认 `elink-message-integration`，不能按通道设置地址或服务名。

个人消息请求：

```http
POST /elink/messages
Content-Type: application/json; charset=UTF-8
```

```json
{
  "msgType": "text",
  "content": "[CRITICAL] 采集任务连续失败\n对象：订单同步\n订单同步连续失败 3 次\n时间：2026-07-16T10:20:00",
  "userIds": ["zhangsan", "lisi"]
}
```

群组消息请求：

```http
POST /elink/groups/123456/messages
Content-Type: application/json; charset=UTF-8
```

```json
{
  "msgType": "text",
  "content": "[CRITICAL] 采集任务连续失败\n对象：订单同步\n订单同步连续失败 3 次\n时间：2026-07-16T10:20:00"
}
```

- `msgType` 固定为 `text`；`content` 由告警级别、规则、对象、摘要和发生时间自动组装，并限制为 2048 个 UTF-8 字节。
- 规则触发、静默期后的重复通知和恢复后重开会创建 eLink 投递；规则启用 `recoveryNotificationEnabled` 时，`RECOVERED` 也会投递；规则测试和通道测试使用 `TEST` 事件。
- 不支持 `markdown`、`textcard`、自定义模板、Header 注入或自定义请求体。
- Manager 接口语义以上述两个现有接口为准；其下游发送响应参考 [eLink 消息发送文档](https://app.csg.cn:8801/api/doc#10167)，个人账号校验参考 [读取成员文档](https://app.csg.cn:8801/api/doc#10019)，并以用户补充的 [部门成员详情文档](https://app.csg.cn:8801/api/doc#10063) 作为成员响应结构补充。

Manager 响应到投递状态的映射：

| Manager/HTTP 结果 | Studio 投递状态 | 说明 |
|---|---|---|
| HTTP 2xx，`errcode=0` 且没有显式 `success=false` | `SUCCEEDED` | 兼容官方 `{"errcode":0,"errmsg":"ok"}`。 |
| HTTP 2xx，没有 `errcode` 且 `success=true` | `SUCCEEDED` | 兼容现有 Manager `success` 字段。 |
| HTTP 2xx，`errcode!=0` 或显式 `success=false` | `DEAD` | 保留响应中的 `errmsg` 或 `errorMessage` 供投递记录排查。 |
| Studio 调用 Manager 时无可用 Nacos 实例、连接失败或超时 | `RETRY` | 由统一 outbox 退避重试。 |
| HTTP 408、429 或 5xx | `RETRY` | 由统一 outbox 退避重试。 |
| 其他非 2xx | `DEAD` | 视为不可自动重试响应。 |

如果响应同时包含 `errcode` 和 `success`，任一字段明确表示失败都按失败处理，避免矛盾响应被误记为成功。`errmsg`、`errorMessage` 和响应摘要写入投递记录前仍会执行告警敏感文本脱敏。Manager 响应只决定本次投递记录状态，不改变告警事件的 `OPEN / ACKNOWLEDGED / RECOVERED / CLOSED` 状态。

当前 Manager 不会把 eLink 上游的 `invaliduser` 写入消息历史响应；因此上游返回 `errcode=0` 但仅部分账号无效时，Studio 只能依据 Manager 的 `success=true` 记为成功。该限制属于现有 Manager 响应边界，本轮不修改 Manager 生产代码。

如果 Manager 已把其下游网络错误转换为 HTTP 200、`success=false` 的消息历史响应，Studio 会按明确失败结果记为 `DEAD`；只有 Studio 到 Manager 的传输失败，或 Manager 直接返回 408、429、5xx，才进入自动重试。

当前 Manager 没有幂等发送接口，因此自动重试提供的是至少一次投递语义。若超时发生在 Manager 已接收消息之后，后续重试可能重复发送；Studio 当前不增加幂等 generation、消息模板 ID 或其他扩展协议。

## eLink mock 联调

当前环境无法请求真实 eLink 不阻塞本轮验收。可在 eLink 工程中启动不连接数据库、也不注册 Nacos 的 `elink-mock-server-boot`。它模拟 Manager 实际使用的 eLink 上游接口：`/cgi-bin/gettoken`、`/cgi-bin/user/get`、`/cgi-bin/message/send`、`/cgi-bin/appchat/create` 和 `/cgi-bin/appchat/send`。

联调链路固定为 `Studio -> eLink Manager -> eLink mock`：只有 Manager 以 `elink-message-integration` 注册到 Studio 相同的 Nacos `namespace/group`；Manager 通过 `ELINK_BASE_URL` 指向 mock。Studio 的 `POST /alerts/channels/{id}/test`、规则测试和告警投递都走这条链路。验收重点是请求结构、Manager 响应解析、投递状态和 outbox 重试，不要求真实 eLink 网络可达，也不要求修改 Manager 生产发送程序。

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

- 站内信、Webhook 和 eLink 都先写入 `studio_alert_delivery` outbox，业务事务不等待网络调用。
- 408、429、5xx 和网络异常按 1、5、15、60 分钟重试，最多 5 次；其他 4xx 直接进入 `DEAD`。
- `PENDING / PROCESSING / RETRY / SUCCEEDED / DEAD / SKIPPED` 为完整投递状态。
- 服务端关闭 `studio.alert.webhook.enabled` 或 `studio.alert.elink.enabled` 时，对应待处理记录保持原状态并暂停发送，重新开启后继续处理。
- 通道被禁用或站内信接收用户被禁用时，对应投递进入 `SKIPPED`，不计入失败投递；通道测试仍允许验证禁用通道的连通性。
- 手工重试支持 `DEAD / RETRY / SKIPPED`，会清空旧错误并重新从第一次尝试开始。
- Webhook/eLink 投递关联的通道必须仍然存在；通道已删除时重试接口返回 `BUSINESS_ERROR`，不会把必然失败的投递重新放回队列。

## 升级与配置

- MySQL 增量 SQL：`backend/studio-server/src/main/resources/update/20260713/20260713-alert-center.sql`
- eLink 通道增量 SQL：`backend/studio-server/src/main/resources/update/20260716/20260716-elink-alert-channel.sql`，仅新增 `studio_alert_channel.config_json`。
- 全量 schema 已同步 MySQL 和 SQLite；`StudioSchemaUpgradeService` 也会补建五张告警表、索引和 `config_json`。
- 首次升级不创建默认规则，不扫描历史终态失败。
- 总开关：`studio.alert.enabled`；评估、投递、Webhook 和 eLink 分别有独立子开关。
- eLink 服务名默认 `elink-message-integration`；Nacos `namespace/group` 复用 Studio 现有服务发现上下文。
- Webhook 安全与内网白名单配置见 `docs/运维/监控/alert-webhook-security.md`。
- 数据库升级与回滚说明见 `docs/数据库/alert-center-upgrade.md`。
