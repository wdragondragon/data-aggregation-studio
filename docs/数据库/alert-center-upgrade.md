# 统一告警中心数据库升级说明

## 交付物

- MySQL 增量脚本：`backend/studio-server/src/main/resources/update/20260713/20260713-alert-center.sql`
- eLink 通道增量脚本：`backend/studio-server/src/main/resources/update/20260716/20260716-elink-alert-channel.sql`
- eLink 规则接收人手机号兜底脚本：`backend/studio-server/src/main/resources/update/20260717/20260717-elink-recipient-mobile.sql`
- eLink 用户绑定唯一索引脚本：`backend/studio-server/src/main/resources/update/20260718/20260718-elink-binding-unique-index.sql`
- MySQL 全量结构：`backend/studio-server/src/main/resources/schema-mysql.sql`
- SQLite 全量结构：`backend/studio-desktop-runtime/src/main/resources/schema-sqlite.sql`
- 启动升级：`StudioSchemaUpgradeService` 会补建告警表、eLink 通道配置列、`sys_user.mobile_phone` 和 `(provider_code, studio_user_id)` 唯一索引。

增量脚本创建以下五张表：

- `studio_alert_rule`
- `studio_alert_incident`
- `studio_alert_event`
- `studio_alert_channel`
- `studio_alert_delivery`

2026-07-16 的 eLink 增量不新增表，只新增通道配置列：

```sql
alter table studio_alert_channel
    add column config_json json after signing_secret_ciphertext;
```

`config_json` 只保存最小 eLink 接收目标，例如：

```json
{"targetType":"PERSONAL","userIds":["zhangsan","lisi"]}
```

或：

```json
{"targetType":"GROUP","groupId":123456}
```

该列不保存 SLB 地址、Header、手机号、模板、消息类型或幂等 generation。现有 Webhook 行可以保持 `config_json` 为空，不需要数据回填。eLink Manager 生产库和 `mock-server-boot` 均不在本次 Studio 数据库升级范围内；mock 服务不依赖数据库。

“跟随规则接收人”不新增告警表；2026-07-17 的手机号兜底只为 Studio 用户增加一个可空字段：

```sql
alter table sys_user add column mobile_phone varchar(32) after display_name;
```

2026-07-18 增量为外部账号绑定补充 Studio 用户维度的唯一索引。该约束单独交付，避免已执行旧版 2026-07-16 脚本的环境漏建索引：

```sql
alter table studio_external_user_binding
    add unique key uk_studio_external_user_binding_provider_user
    (provider_code, studio_user_id);
```

- eLink 通道的 `recipientMode` 和固定目标显示名称快照继续保存在 `studio_alert_channel.config_json`。
- Studio 用户与 eLink `userId` 的绑定复用现有 `studio_external_user_binding`，`provider_code=ELINK`。
- 2026-07-18 脚本增加的 `(provider_code, studio_user_id)` 与既有 `(provider_code, external_user_id)` 双向唯一索引共同保证一对一绑定。
- 动态目标按“eLink `userId` 绑定优先、`sys_user.mobile_phone` 兜底”解析。账号或手机号快照保存在对应 `studio_alert_delivery.payload_json`，投递查询接口不返回原始内部 payload，只返回经过白名单提取和脱敏的规则、对象、消息内容与 `recipientDisplay` 审计字段。
- 已有快照的自动重试和人工重试不重新解析目标；此前没有账号和手机号的投递，在补齐任一目标后人工重试时生成首个快照。
- 旧 eLink 通道没有 `recipientMode` 时按 `FIXED` 读取，不需要数据回填。

## 升级步骤

1. 备份当前数据库并记录应用版本。
2. 尚未安装告警中心时依次执行 2026-07-13、2026-07-16、2026-07-17 和 2026-07-18 脚本；已有 eLink 告警通道但未安装规则接收人增量时，补执行 2026-07-17 和 2026-07-18；已执行 2026-07-17 时仍需单独执行 2026-07-18。2026-07-16 至 2026-07-18 脚本都会通过 `information_schema` 检查结构是否已存在，可与启动升级交叉执行或安全重跑。
3. 启动新版本应用，确认启动升级没有 DDL 异常。
4. 核验五张表、`studio_alert_channel.config_json`、`sys_user.mobile_phone`、`uk_studio_external_user_binding_provider_user` 外部账号绑定索引、规则名称唯一索引、事件来源去重索引、实例签名唯一索引和 outbox 目标唯一索引。
5. 登录后调用 `/api/v1/alerts/options` 与 `/api/v1/alerts/summary`，确认当前租户和项目上下文正常。
6. 新建一个个人账号或群组 eLink 通道，确认配置写入 `config_json`，原有 Webhook 查询和测试不受影响。

首次升级不会创建默认规则，也不会追溯历史终态失败。状态型规则启用后会立即参与周期评估，事件型规则只处理启用时间之后的新事件。

## 数据保留

- 告警事件默认保留 180 天。
- `SUCCEEDED`、`DEAD`、`SKIPPED` 等终态投递默认保留 30 天。
- 清理任务每日使用集群锁执行；历史告警实例不会随规则禁用自动删除。

保留期通过 `STUDIO_ALERT_EVENT_RETENTION_DAYS` 和 `STUDIO_ALERT_DELIVERY_RETENTION_DAYS` 调整。缩短保留期前应先评估审计要求。

## 回滚

1. 先设置 `STUDIO_ALERT_ENABLED=false`，停止评估与投递。
2. 回退应用版本。
3. 默认保留五张告警历史表，不执行删表。
4. 默认保留 `studio_alert_channel.config_json`、`sys_user.mobile_phone` 和外部账号绑定唯一索引；旧版本不会读取新增列，且不依赖同一用户的重复 provider 绑定，常规应用回退不需要逆向 DDL。
5. 若后续重新启用，先确认旧版本没有写入不兼容结构，再恢复告警总开关。

仅在明确放弃告警历史且已完成备份时才考虑人工删表；常规回滚不需要逆向 DDL。
