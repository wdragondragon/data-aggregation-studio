# 统一告警中心数据库升级说明

## 交付物

- MySQL 增量脚本：`backend/studio-server/src/main/resources/update/20260713/20260713-alert-center.sql`
- MySQL 全量结构：`backend/studio-server/src/main/resources/schema-mysql.sql`
- SQLite 全量结构：`backend/studio-desktop-runtime/src/main/resources/schema-sqlite.sql`
- 启动升级：`StudioSchemaUpgradeService` 会补建告警表和索引。

增量脚本创建以下五张表：

- `studio_alert_rule`
- `studio_alert_incident`
- `studio_alert_event`
- `studio_alert_channel`
- `studio_alert_delivery`

## 升级步骤

1. 备份当前数据库并记录应用版本。
2. 在维护窗口执行增量 SQL；不要修改脚本中的唯一索引、逻辑删除和乐观锁字段。
3. 启动新版本应用，确认启动升级没有 DDL 异常。
4. 核验五张表、规则名称唯一索引、事件来源去重索引、实例签名唯一索引和 outbox 目标唯一索引。
5. 登录后调用 `/api/v1/alerts/options` 与 `/api/v1/alerts/summary`，确认当前租户和项目上下文正常。

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
4. 若后续重新启用，先确认旧版本没有写入不兼容结构，再恢复告警总开关。

仅在明确放弃告警历史且已完成备份时才考虑人工删表；常规回滚不需要逆向 DDL。
