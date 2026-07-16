# 告警 Webhook 安全与运维说明

## 安全边界

- 默认只允许 HTTPS；生产环境不要开启 `STUDIO_ALERT_WEBHOOK_ALLOW_HTTP`。
- 默认拒绝回环、链路本地、私网、共享地址空间、组播地址、IPv4 映射/NAT64/6to4/Teredo/ISATAP 过渡地址和云元数据地址。
- DNS 在发送前解析并校验，HTTP 客户端使用已校验地址，避免 DNS 重绑定绕过。
- 禁止重定向和客户端隐式重试；单次 outbox 尝试最多发起一次 HTTP 请求。
- URL 最长 2048 字符且不允许 fragment；需要访问内部或过渡网络目标时必须通过 `allowed-hosts` 精确放行。
- URL、Header 值和 HMAC 密钥使用 `EncryptionService` 加密保存，查询接口仅返回脱敏 URL、Header 名称和是否配置签名。
- 生产环境必须通过 `STUDIO_ENCRYPTION_SECRET` 覆盖默认加密密钥，并将其作为部署秘密管理。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `STUDIO_ALERT_WEBHOOK_ENABLED` | `true` | Webhook 服务端发送开关；关闭时保留待投递记录并暂停发送。 |
| `STUDIO_ALERT_WEBHOOK_ALLOW_HTTP` | `false` | 仅本地测试环境可开启 HTTP。 |
| `STUDIO_ALERT_WEBHOOK_ALLOWED_HOSTS` | 空 | 显式允许内部目标，支持精确主机和 `*.example.internal`。 |
| `STUDIO_ALERT_WEBHOOK_CONNECT_TIMEOUT_SECONDS` | `3` | TCP/TLS 连接超时。 |
| `STUDIO_ALERT_WEBHOOK_REQUEST_TIMEOUT_SECONDS` | `5` | 请求和响应读取超时。 |
| `STUDIO_ALERT_WEBHOOK_MAX_RESPONSE_BYTES` | `16384` | 最多读取并保存的响应字节数。 |

`allowed-hosts` 是对网络地址限制的显式放行，不是普通域名列表。生产环境应使用最小范围的精确主机，避免配置过宽的通配符。

## Header 与签名

- 自定义 Header 名必须符合 HTTP token 语法，名称最长 128 字符，值不得包含 CR/LF，单值最长 4000 字符。
- 每个通道最多配置 32 个 Header，名称和值合计最多 16 KB；Header 名按大小写不敏感规则去重。
- 禁止覆盖 `Host`、`Content-Length`、`Connection`、`Transfer-Encoding`、`Keep-Alive`、`TE`、`Trailer`、代理认证、内容类型和 `X-Studio-*` 签名 Header。
- 配置签名密钥后发送：

```text
X-Studio-Event-Id: <event id>
X-Studio-Timestamp: <UTC epoch seconds>
X-Studio-Signature-SHA256: hex(HMAC-SHA256(secret, timestamp + "." + rawBody))
```

接收方应校验时间窗口、对原始请求体计算签名，并按 `eventId` 做幂等处理。密钥轮换时更新通道即可；更新请求省略 `signingSecret` 会保留旧值，`clearSigningSecret=true` 才会清除。清除和提交新密钥不能在同一次请求中同时出现。

## 投递处置

- `408`、`429`、`5xx` 和网络异常按 1、5、15、60 分钟退避，最多尝试 5 次。
- 其他 `4xx` 直接进入 `DEAD`；管理员修正目标后可人工重试。
- Webhook 通道已删除时人工重试会被拒绝，不会重新入队；需要重新发送时应先创建有效通道并通过新的规则事件生成投递。
- 通道禁用后，尚未发送的正常投递进入 `SKIPPED`；通道测试不受通道启停状态限制。
- 错误、响应摘要和证据会经过脱敏与截断，包含短路径在内的 URL 路径、查询参数、自定义 Header 值、动态签名和签名密钥均不会明文保存；接收方仍不应回显凭据或完整请求体。

## 上线核验

1. 确认生产环境 `ALLOW_HTTP=false`，内部目标均有最小化白名单。
2. 确认 `STUDIO_ENCRYPTION_SECRET` 已使用部署秘密覆盖默认值。
3. 使用通道测试验证 TLS、签名和接收方幂等处理。
4. 分别模拟 `200`、`400`、`429`、`500` 和超时，核对终止或重试状态。
5. 检查告警投递记录和应用日志中不存在明文 URL 路径/查询参数、Token、Header 值或签名密钥。
6. 需要紧急停止外发时关闭 `STUDIO_ALERT_WEBHOOK_ENABLED`，不要删除历史 outbox。
