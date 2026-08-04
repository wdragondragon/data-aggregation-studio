# 统一告警中心接口

覆盖 `/alerts` 页面使用的告警规则、告警事件、Webhook/eLink 通道、投递记录和租户汇总接口。所有接口均使用 `com.jdragon.studio.dto.common.Result<T>`，分页数据位于 `data.items`。

## 调用约定

- API 根路径：`/api/v1/alerts`
- 项目接口必须携带 `Authorization`、`X-Tenant-Id`、`X-Project-Id`。
- 项目成员可查看并确认、关闭事件；规则、通道和投递重试仅允许项目管理员、租户管理员或超级管理员。
- 规则、事件、通道和投递严格按当前项目隔离；`tenant-summary/query` 仅返回当前租户的项目摘要。

## 2026-07-20 多集群增量契约

- `AlertIncidentQueryRequest` 增加 `requestedClusterId/actualClusterId`，用于区分规则目标网络位置和真正产生证据的执行位置。
- `AlertIncidentView` 返回同名两个字段；事件详情、时间线和投递审计应直接展示或关联运行集群，不从 Worker 组名称反推集群。
- 任务、服务、队列、Worker 和日志信号会尽量携带请求/实际集群。历史事件或项目级对象可能为空，前端必须兼容。
- 集群筛选仍受当前项目隔离约束；租户摘要不会因此放宽跨项目事件明细权限。
- 告警证据、投递内容和错误摘要不得记录运行端点认证、内部认证或数据源秘密。

## SDK 与接口

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 |
|---|---|---|---|---|
| `alerts.options()` | GET | `/alerts/options` | - | `AlertOptionsView` |
| `alerts.subjects()` | GET | `/alerts/subjects` | params: { subjectType: string; keyword?: string; pageNo?: number; pageSize?: number } | `PageResult<AlertSelectOptionView>` |
| `alerts.recipientOptions()` | GET | `/alerts/recipient-options` | params?: { keyword?: string; pageNo?: number; pageSize?: number } | `PageResult<AlertSelectOptionView>` |
| `alerts.summary()` | GET | `/alerts/summary` | - | `AlertSummaryView` |
| `alerts.tenantSummary()` | POST | `/alerts/tenant-summary/query` | payload?: { keyword?: string; pageNo?: number; pageSize?: number }<br>body: `payload` | `PageResult<AlertTenantProjectSummaryView>` |
| `alerts.queryRules()` | POST | `/alerts/rules/query` | payload?: AlertRuleQueryRequest<br>body: `payload` | `PageResult<AlertRuleView>` |
| `alerts.getRule()` | GET | ``/alerts/rules/${id}`` | id: EntityId | `AlertRuleView` |
| `alerts.saveRule()` | POST | `/alerts/rules` | payload: AlertRuleSaveRequest<br>body: `payload` | `AlertRuleView` |
| `alerts.deleteRule()` | DELETE | ``/alerts/rules/${id}`` | id: EntityId | `void` |
| `alerts.enableRule()` | POST | ``/alerts/rules/${id}/enable`` | id: EntityId | `AlertRuleView` |
| `alerts.disableRule()` | POST | ``/alerts/rules/${id}/disable`` | id: EntityId | `AlertRuleView` |
| `alerts.testRule()` | POST | ``/alerts/rules/${id}/test`` | id: EntityId | `AlertEventView` |
| `alerts.queryIncidents()` | POST | `/alerts/incidents/query` | payload?: AlertIncidentQueryRequest<br>body: `payload` | `PageResult<AlertIncidentView>` |
| `alerts.getIncident()` | GET | ``/alerts/incidents/${id}`` | id: EntityId | `AlertIncidentView`，含最近事件和投递 |
| `alerts.incidentEvents()` | GET | ``/alerts/incidents/${id}/events`` | id: EntityId<br>params?: { pageNo?: number; pageSize?: number } | `PageResult<AlertEventView>` |
| `alerts.acknowledgeIncident()` | POST | ``/alerts/incidents/${id}/acknowledge`` | id: EntityId<br>payload?: { comment?: string }<br>body: `payload` | `AlertIncidentView` |
| `alerts.closeIncident()` | POST | ``/alerts/incidents/${id}/close`` | id: EntityId<br>payload?: { comment?: string }<br>body: `payload` | `AlertIncidentView` |
| `alerts.queryChannels()` | POST | `/alerts/channels/query` | payload?: AlertChannelQueryRequest<br>body: `payload` | `PageResult<AlertChannelView>` |
| `alerts.getChannel()` | GET | ``/alerts/channels/${id}`` | id: EntityId | `AlertChannelView` |
| `alerts.saveChannel()` | POST | `/alerts/channels` | payload: AlertChannelSaveRequest<br>body: `payload` | `AlertChannelView` |
| `alerts.deleteChannel()` | DELETE | ``/alerts/channels/${id}`` | id: EntityId | `void` |
| `alerts.enableChannel()` | POST | ``/alerts/channels/${id}/enable`` | id: EntityId | `AlertChannelView` |
| `alerts.disableChannel()` | POST | ``/alerts/channels/${id}/disable`` | id: EntityId | `AlertChannelView` |
| `alerts.testChannel()` | POST | ``/alerts/channels/${id}/test`` | id: EntityId | `AlertEventView` |
| `alerts.queryDeliveries()` | POST | `/alerts/deliveries/query` | payload?: AlertDeliveryQueryRequest<br>body: `payload` | `PageResult<AlertDeliveryView>` |
| `alerts.retryDelivery()` | POST | ``/alerts/deliveries/${id}/retry`` | id: EntityId | `AlertDeliveryView` |
| `elink.queryUsers()` | GET | `/elink/users` | params?: { keyword?: string; pageNo?: number; pageSize?: number } | `PageResult<ElinkUserOptionView>` |
| `elink.queryGroups()` | GET | `/elink/groups` | params?: { keyword?: string; pageNo?: number; pageSize?: number } | `PageResult<ElinkGroupOptionView>` |

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
- `POST /alerts/deliveries/query` 和 `GET /alerts/incidents/{id}` 的 `recentDeliveries` 都返回同一套投递审计字段。除状态、尝试次数、响应摘要和脱敏错误外，还包括规则、触发事件、告警对象、摘要、实际消息标题/正文、接收目标快照和事件时间。

投递审计字段：

| 字段 | 说明 |
| --- | --- |
| `ruleId / ruleName / ruleType / severity` | 产生本次投递的规则快照。 |
| `eventType / occurredAt` | `TRIGGERED / REPEATED / RECOVERED / TEST` 等事件类型及事件时间。 |
| `subjectType / subjectId / subjectName / targetPath` | 实际告警对象及业务跳转地址。 |
| `summary` | 当次告警摘要。 |
| `messageFormat / messageTitle / messageContent` | 按通道保存或重建的实际发送内容；格式为 `TEXT` 或 `JSON`。 |
| `recipientDisplay` | 脱敏后的接收目标快照；旧记录缺少快照时返回明确的降级说明。 |
| `status / attemptCount / lastAttemptAt / nextAttemptAt` | outbox 状态和重试进度。 |
| `httpStatus / responseExcerpt / errorMessage` | 下游响应与失败原因，返回前继续执行敏感文本脱敏。 |

接口不会返回原始 `payloadJson`，也不会暴露内部 `_elinkTargetUserId`、`_elinkTargetMobile` 或 `_deliveryAudit` 字段。手机号只允许以掩码形式出现在 `recipientDisplay`；Webhook URL、Header 值、Token、Cookie 和签名密钥仍不进入投递查询响应。

典型投递项：

```json
{
  "id": "2078013628529917953",
  "eventId": "2078013625749094402",
  "incidentId": "2077706416750260225",
  "ruleId": "2077700148333572098",
  "ruleName": "P0-02 Worker 离线验收 20260716",
  "ruleType": "WORKER_OFFLINE",
  "severity": "WARNING",
  "eventType": "REPEATED",
  "occurredAt": "2026-07-17 15:07:10",
  "subjectType": "WORKER_GROUP",
  "subjectId": "2070373554523758594",
  "subjectName": "studio-online-worker-01",
  "targetPath": "/ops-center?section=workers",
  "summary": "Worker 组 studio-online-worker-01 已离线",
  "messageFormat": "TEXT",
  "messageTitle": null,
  "messageContent": "[WARNING] P0-02 Worker 离线验收 20260716\n对象：studio-online-worker-01\nWorker 组 studio-online-worker-01 已离线\n时间：2026-07-17T15:07:10",
  "channelType": "ELINK",
  "channelName": "P0-02 eLink 模拟通知 20260717",
  "recipientDisplay": "账号：Mock user-1",
  "status": "SUCCEEDED",
  "attemptCount": 1,
  "httpStatus": 200,
  "responseExcerpt": "{\"success\":true,\"errcode\":0,\"errmsg\":\"ok\"}"
}
```

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

## 查询 eLink 候选

前端不直接调用 eLink Manager。Studio 使用当前 Nacos `namespace/group` 发现 Manager，获取全量结果后执行关键字过滤和内存分页：

```http
GET /api/v1/elink/users?keyword=zhang&pageNo=1&pageSize=50
GET /api/v1/elink/groups?keyword=ops&pageNo=1&pageSize=50
```

- 账号来源：Manager `GET /elink/app/allow-users`。
- 群组来源：Manager `GET /elink/groups`。
- 账号返回字段为 `userId/name/enabled`；群组返回字段为 `id/name/memberCount`。
- 上述 eLink 候选接口不向浏览器透传 Manager 成员手机号、邮箱、远端 `chatId` 或 Manager 内部地址；系统用户管理中的 `mobilePhone` 是 Studio 自己维护的用户资料字段。
- Manager 没有服务端搜索和分页，Studio 对结果使用短期缓存，避免输入每个字符都触发 Manager 及其上游请求。
- 两个接口只允许告警规则/通道管理员调用；Manager 的业务错误经过敏感信息脱敏后保真返回。

系统用户保存接口 `POST /api/v1/users` 支持 `mobilePhone`、`elinkUserId` 和 `clearElinkUserBinding`。`mobilePhone` 接受中国大陆手机号码以及带 `+86`、空格或短横线的常见格式，服务端统一保存为 11 位号码；传空字符串表示清除，省略表示保留。`elinkUserId` 必须来自上述账号候选；`clearElinkUserBinding=true` 表示显式解除绑定。eLink 绑定字段均省略时保留已有绑定。Studio 用户和 eLink 账号是一对一绑定，任一侧都不能重复绑定。网关登录用户还会从可信用户信息的 `mobilePhone/phoneNumber` 自动同步手机号：优先采用有效的 `mobilePhone`，否则采用有效的 `phoneNumber`；两个字段都未提供时保留旧值，任一字段已明确提供但都为空或无效时清除旧值，避免继续向已解绑号码发送告警。

```json
{
  "id": "2047489207831650305",
  "username": "zhangsan",
  "displayName": "张三",
  "mobilePhone": "13800000001",
  "elinkUserId": null,
  "enabled": 1
}
```

用户列表和保存接口仅允许超级管理员调用。完整手机号不会进入告警事件证据或投递查询响应，只作为内部 eLink 目标快照使用；Manager 错误若回显手机号，返回前会遮蔽中间四位。

## 保存 eLink 通道

保存接口仍为 `POST /api/v1/alerts/channels`。`elinkRecipientMode` 支持 `FIXED` 和 `RULE_RECIPIENTS`；旧通道缺少该字段时按 `FIXED` 兼容读取。

个人账号示例：

```json
{
  "name": "生产值班账号",
  "channelType": "ELINK",
  "elinkRecipientMode": "FIXED",
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
  "elinkRecipientMode": "FIXED",
  "elinkTargetType": "GROUP",
  "elinkGroupId": 123456,
  "enabled": true
}
```

跟随规则接收人示例：

```json
{
  "name": "任务创建人 eLink 通知",
  "channelType": "ELINK",
  "elinkRecipientMode": "RULE_RECIPIENTS",
  "enabled": true
}
```

`AlertChannelView` 的 eLink 数据片段如下：

```json
{
  "id": "9002",
  "name": "生产运维群",
  "channelType": "ELINK",
  "elinkRecipientMode": "FIXED",
  "elinkTargetType": "GROUP",
  "elinkUserIds": [],
  "elinkUserNames": [],
  "elinkGroupId": "123456",
  "elinkGroupName": "生产运维群",
  "enabled": true,
  "lastTestStatus": "SUCCEEDED"
}
```

- `channelType` 新增时应显式传 `ELINK`；通道创建后不能在 `WEBHOOK` 和 `ELINK` 之间改类型。
- `FIXED + PERSONAL` 必须传至少一个从 Manager 选择的 `elinkUserIds`，Studio 会去重并校验当前候选；不接收手机号、`@all` 或包含 `|` 的组合值。
- `FIXED + GROUP` 必须传从 Manager 选择的正整数 `elinkGroupId`，它表示 Manager 已存在的本地群组 ID。
- `RULE_RECIPIENTS` 不接收 `elinkTargetType`、`elinkUserIds` 或 `elinkGroupId`，实际目标来自规则接收人：优先使用 Studio 用户的 eLink 绑定，未绑定时使用有效 `mobilePhone`。
- eLink 通道不接收 `endpointUrl`、`headers`、`signingSecret` 或 `clearSigningSecret`。
- 固定目标的显示名称会作为快照保存，Manager 暂时不可用时编辑页仍可回显历史选择。
- eLink 通道配置不包含 SLB 地址、认证 Header、手工手机号、自定义模板或自定义消息类型；动态手机号来自 Studio 用户资料，不属于通道配置。
- `RULE_RECIPIENTS` 没有独立规则上下文，因此 `POST /alerts/channels/{id}/test` 会拒绝该模式；使用 `POST /alerts/rules/{id}/test` 验证。

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

未绑定 eLink 账号但存在有效用户手机号时，请求改为：

```json
{
  "msgType": "text",
  "content": "[CRITICAL] 采集任务连续失败\n对象：订单同步\n订单同步连续失败 3 次\n时间：2026-07-16T10:20:00",
  "mobiles": ["13800000001"]
}
```

Studio 对单个规则接收人的请求不会同时发送 `userIds` 和 `mobiles`。Manager 负责把手机号反查为 eLink `userId`；无法反查时按 Manager 返回结果将该次投递记为 `DEAD`。

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

`RULE_RECIPIENTS` 会为每个有效 Studio 接收人创建独立 eLink outbox。目标按“eLink `userId` 绑定优先、有效 `mobilePhone` 兜底”解析，内部投递负载保存触发时的账号或手机号快照，`recipientUserId` 保存 Studio 用户 ID。两种目标都不存在时仅该条投递进入 `DEAD`，不会阻断其他接收人。已有快照的自动重试和人工重试继续使用原目标；此前没有目标快照的投递，在管理员补齐绑定或手机号后人工重试时重新解析并写入首个快照。投递查询接口不返回内部 payload，错误和响应摘要中的完整手机号会脱敏。

当前 Manager 不会把 eLink 上游的 `invaliduser` 写入消息历史响应；因此上游返回 `errcode=0` 但仅部分账号无效时，Studio 只能依据 Manager 的 `success=true` 记为成功。该限制属于现有 Manager 响应边界，本轮不修改 Manager 生产代码。

如果 Manager 已把其下游网络错误转换为 HTTP 200、`success=false` 的消息历史响应，Studio 会按明确失败结果记为 `DEAD`；只有 Studio 到 Manager 的传输失败，或 Manager 直接返回 408、429、5xx，才进入自动重试。

当前 Manager 没有幂等发送接口，因此自动重试提供的是至少一次投递语义。若超时发生在 Manager 已接收消息之后，后续重试可能重复发送；Studio 当前不增加幂等 generation、消息模板 ID 或其他扩展协议。

## eLink mock 联调

当前环境无法请求真实 eLink 不阻塞本轮验收。可在 eLink 工程中启动不连接数据库、也不注册 Nacos 的 `elink-mock-server-boot`。它模拟 Manager 实际使用的 eLink 上游接口：`/cgi-bin/gettoken`、`/cgi-bin/agent/get`、`/cgi-bin/user/get`、`/cgi-bin/message/send`、`/cgi-bin/appchat/create` 和 `/cgi-bin/appchat/send`。

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
- 新投递会在内部 payload 中保存白名单审计快照，使规则或通道后续改名时仍可还原当次规则、接收目标和消息内容；Webhook 发送前会剥离所有 `_` 开头的内部审计字段。
- 站内信的 `messageTitle/messageContent` 与消息中心实际写入内容使用同一渲染器；eLink 的 `messageContent` 与发送给 Manager 的文本使用同一渲染器；Webhook 的 `messageContent` 是格式化后的 `studio.alert.webhook.v1` envelope。
- 408、429、5xx 和网络异常按 1、5、15、60 分钟重试，最多 5 次；其他 4xx 直接进入 `DEAD`。
- `PENDING / PROCESSING / RETRY / SUCCEEDED / DEAD / SKIPPED` 为完整投递状态。
- 服务端关闭 `studio.alert.webhook.enabled` 或 `studio.alert.elink.enabled` 时，对应待处理记录保持原状态并暂停发送，重新开启后继续处理。
- 通道被禁用或站内信接收用户被禁用时，对应投递进入 `SKIPPED`，不计入失败投递；通道测试仍允许验证禁用通道的连通性。
- 手工重试支持 `DEAD / RETRY / SKIPPED`，会清空旧错误并重新从第一次尝试开始。
- Webhook/eLink 投递关联的通道必须仍然存在；通道已删除时重试接口返回 `BUSINESS_ERROR`，不会把必然失败的投递重新放回队列。

## 升级与配置

- MySQL 增量 SQL：`backend/studio-server/src/main/resources/update/20260713/20260713-alert-center.sql`
- eLink 通道增量 SQL：`backend/studio-server/src/main/resources/update/20260716/20260716-elink-alert-channel.sql`，新增 `studio_alert_channel.config_json`。
- 规则接收人手机号兜底 SQL：`backend/studio-server/src/main/resources/update/20260717/20260717-elink-recipient-mobile.sql`，新增 `sys_user.mobile_phone`。
- eLink 用户绑定唯一索引 SQL：`backend/studio-server/src/main/resources/update/20260718/20260718-elink-binding-unique-index.sql`，新增 `uk_studio_external_user_binding_provider_user`。
- 全量 schema 已同步 MySQL 和 SQLite；`StudioSchemaUpgradeService` 也会补建五张告警表、索引、`config_json`、账号绑定约束和用户手机号列。
- 首次升级不创建默认规则，不扫描历史终态失败。
- 总开关：`studio.alert.enabled`；评估、投递、Webhook 和 eLink 分别有独立子开关。
- eLink 服务名默认 `elink-message-integration`；Nacos `namespace/group` 复用 Studio 现有服务发现上下文。
- eLink 候选响应上限默认 1 MB，可通过 `STUDIO_ALERT_ELINK_MAX_OPTION_RESPONSE_BYTES` 调整。
- Webhook 安全与内网白名单配置见 `docs/运维/监控/alert-webhook-security.md`。
- 数据库升级与回滚说明见 `docs/数据库/alert-center-upgrade.md`。
