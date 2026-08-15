# Studio 非结构化文件传输数据库升级指南

> 适用基线：2026-08-07 文件传输三阶段实现、2026-08-09 单集群/非结构化管理/ACL 改造、2026-08-12 MySQL Outbox 高可用事件架构，以及 2026-08-16 队列可见性与运行历史分离。

## 1. 交付物

MySQL 全量结构：

`backend/studio-server/src/main/resources/schema-mysql.sql`

MySQL 增量脚本，按日期和下列顺序执行：

`backend/studio-server/src/main/resources/update/20260807/20260807-file-transfer.sql`

`backend/studio-server/src/main/resources/update/20260809/20260809-single-cluster-file-transfer-fields.sql`

`backend/studio-server/src/main/resources/update/20260809/20260809-unstructured-management.sql`

`backend/studio-server/src/main/resources/update/20260811/20260811-unstructured-op-audit-message.sql`

`backend/studio-server/src/main/resources/update/20260812/20260812-file-transfer-event-outbox.sql`

`backend/studio-server/src/main/resources/update/20260816/20260816-file-transfer-queue-visibility.sql`

SQLite 全量结构：

`backend/studio-desktop-runtime/src/main/resources/schema-sqlite.sql`

启动时结构升级：

`StudioSchemaUpgradeService` 对 MySQL 和 SQLite 按表、列和索引进行存在性检查，负责旧环境的幂等补齐。
对于历史 MySQL 库，启动升级还会把 `unstructured_op_audit.message` 从旧的 `VARCHAR` 定义升级为 `TEXT`，避免远端文件操作异常消息写审计时触发截断。

2026-08-13 Outbox 运行环境验证：Server `18080` 的 `/actuator/health/studio-ha` 返回 `fileTransferOutbox=available`、七个 Outbox/游标索引完整、`fileTransferOutboxCursorLag=0`；Worker `18081` 健康为 `UP`。Outbox 不引入新中间件，由现有 MySQL 提供可靠存储。2026-08-16 队列清理修复另行增加 `queue_visible` 字段，但不新增表或索引。

## 2. 结构变化

既有表新增字段：

| 表 | 字段 | 用途 |
|---|---|---|
| `dispatch_task` | `file_transfer_task_id` | 绑定预设任务 |
| `dispatch_task` | `file_transfer_run_id` | 绑定文件传输运行 |
| `run_record` | `file_transfer_task_id` | 通用运行指标关联 |
| `run_record` | `file_transfer_run_id` | 通用运行与文件运行一一关联 |
| `datasource_definition` | `created_by` | 数据源创建者和 ACL 管理边界 |
| `file_transfer_task_definition` | `runtime_cluster_id` | 新任务的规范单集群身份 |
| `file_transfer_run` | `runtime_cluster_id` | 新运行的规范单集群身份 |
| `file_transfer_run` | `queue_visible` | 传输中心队列可见性；移出队列时保留运行历史 |
| `file_transfer_run_item` | `runtime_cluster_id` | 新文件项的规范单集群身份 |

原 `source_runtime_cluster_id`、`target_runtime_cluster_id`、`direction` 和 `channel` 字段不删除，只保留历史查询兼容。只有源/目标集群相同的历史记录会回填规范 `runtime_cluster_id`；历史跨集群记录保持为空且不可重试、恢复或再次执行。

新增表：

| 表 | 用途 |
|---|---|
| `file_transfer_task_definition` | 预设任务、发布版本、选择/映射/策略/运行/调度配置 |
| `file_transfer_run` | 运行级状态、文件与字节汇总、速度、重试和解析快照 |
| `file_transfer_run_item` | 单文件状态、路径、临时文件、检查点、摘要和错误 |
| `file_transfer_metric_sample` | 文件传输趋势采样 |
| `file_transfer_event_outbox` | 不可变的文件传输状态事件；使用 MyBatis-Plus `ASSIGN_ID` 生成的 ID 作为 SSE 事件 ID |
| `file_transfer_event_consumer_cursor` | 每个 Server 实例、租户和项目的独立 Outbox 扫描游标 |
| `unstructured_source_acl` | 数据源级项目/用户权限 |
| `unstructured_path_acl` | 目录递归或文件精确路径权限 |
| `unstructured_op_audit` | mkdir、rename、move、delete 成功和失败审计 |

关键唯一约束：

- `file_transfer_task_definition(tenant_id, project_id, code)`。
- `file_transfer_run(run_record_id)`。
- `file_transfer_run_item(run_id, core_item_id)`。
- `file_transfer_event_consumer_cursor(instance_id, tenant_id, project_id)`。
- `unstructured_source_acl(tenant_id, datasource_id, principal_type, user_id, permission)`。
- `unstructured_path_acl(tenant_id, datasource_id, path, principal_type, user_id, permission)`，MySQL 对路径索引使用前缀长度。

## 3. 升级前检查

1. 备份 `dispatch_task`、`run_record` 和 Studio 业务库。
2. 确认当前数据库类型、字符集、时区和 MySQL JSON 类型支持情况。
3. 确认没有同名业务表或索引由其他分支提前创建。
4. 记录当前正在运行的 Dispatch；升级窗口内停止新增调度，避免结构变更与高频写入竞争。
5. 确认 Server 使用的数据库账号具备 `ALTER`、`CREATE` 和 `INDEX` 权限，或由 DBA 预执行增量脚本。

检查示例：

```sql
show columns from dispatch_task like 'file_transfer_run_id';
show columns from run_record like 'file_transfer_run_id';
show columns from datasource_definition like 'created_by';
show columns from file_transfer_run like 'runtime_cluster_id';
show columns from file_transfer_run like 'queue_visible';
show tables like 'file_transfer_%';
show tables like 'unstructured_%';
show columns from file_transfer_event_outbox;
show columns from file_transfer_event_consumer_cursor;
```

## 4. MySQL 升级方式

推荐二选一，不要重复执行同一批非幂等 DDL。

### 4.1 由启动升级服务补齐

适用于允许应用账号变更结构的环境。先部署新版本 Server，启动日志中确认 `StudioSchemaUpgradeService` 完成文件传输、单集群字段、ACL 和审计表的列/索引检查，再启动 Worker 和开放 Web。

该路径会在执行前检查对象是否存在，适合重复启动和滚动发布。

### 4.2 由 DBA 执行增量脚本

适用于生产库禁止应用账号执行 DDL 的环境。按顺序执行：

```text
backend/studio-server/src/main/resources/update/20260807/20260807-file-transfer.sql
backend/studio-server/src/main/resources/update/20260809/20260809-single-cluster-file-transfer-fields.sql
backend/studio-server/src/main/resources/update/20260809/20260809-unstructured-management.sql
backend/studio-server/src/main/resources/update/20260811/20260811-unstructured-op-audit-message.sql
backend/studio-server/src/main/resources/update/20260812/20260812-file-transfer-event-outbox.sql
backend/studio-server/src/main/resources/update/20260816/20260816-file-transfer-queue-visibility.sql
```

`20260807-file-transfer.sql` 是一次性交付脚本；两个 `20260809` 脚本、`20260811` 审计字段脚本、`20260812` Outbox 脚本和 `20260816` 队列可见性脚本通过 `information_schema` 与 `create table if not exists` 保护新增列、表和索引。`20260812` 可以重复执行：仅包含主键且没有数据的 Outbox/游标半成品表会先删除再按完整定义重建。`20260816` 可以重复执行，既有运行和新运行的 `queue_visible` 默认均为 `1`。非空表若缺少 `tenant_id`、`project_id`、运行/事件身份或游标身份等关键列，脚本和启动升级都会拒绝继续，绝不填充猜测的历史事件归属；必须先备份，再由 DBA 人工回填为完整结构或在确认可丢弃历史事件后重建空表。若环境已经由启动升级服务部分创建 20260807 结构，DBA 必须先按实际结构裁剪旧脚本，不能直接整文件重放。

执行完成后可收回应用账号 DDL 权限，启动升级服务只应做存在性核验。

## 5. SQLite 升级

桌面运行时的新库直接使用 `schema-sqlite.sql`。旧库由 `StudioSchemaUpgradeService` 使用 `create table/index if not exists` 和列存在性检查补齐，不应手工用 MySQL 增量脚本处理 SQLite。对 Outbox/消费者游标，空的残缺表允许删除并重建；非空残缺表会以明确错误停止，必须从 SQLite 冷备恢复、人工回填，或确认可丢弃历史事件后重建。

升级前复制 SQLite 数据库文件作为冷备份，并在桌面进程完全退出后执行升级。

## 6. 升级后验证

```sql
select count(*) from file_transfer_task_definition;
select count(*) from file_transfer_run;
select count(*) from file_transfer_run_item;
select count(*) from file_transfer_metric_sample;
select count(*) from file_transfer_event_outbox;
select count(*) from file_transfer_event_consumer_cursor;
select count(*) from unstructured_source_acl;
select count(*) from unstructured_path_acl;
select count(*) from unstructured_op_audit;
```

还需确认：

- `dispatch_task` 存在 `idx_dispatch_task_file_transfer_run`。
- `run_record` 存在 `idx_run_record_file_transfer_run`。
- 九张新表的租户、项目、状态、路径和时间索引均已创建。
- `file_transfer_event_outbox` 存在 `idx_ft_outbox_scope_id`、`idx_ft_outbox_run_id`、`idx_ft_outbox_created` 和 `idx_ft_outbox_event_type`。
- `file_transfer_event_consumer_cursor` 存在唯一索引 `uk_ft_event_cursor_scope`，以及 `idx_ft_event_cursor_seen`、`idx_ft_event_cursor_position`。
- Outbox 事件类型只包含 `RUN_CREATED`、`RUN_CHANGED`、`ITEM_CHANGED`、`RUN_REMOVED`、`ITEM_REMOVED`；`payload_json` 不包含密码、Token、Worker 地址或完整任务快照。
- `datasource_definition.created_by` 以及任务、运行、文件项的 `runtime_cluster_id` 均存在。
- `file_transfer_run.queue_visible` 为非空整数字段，默认值为 `1`；传输中心清理后的运行值为 `0`，运行历史查询仍可读取该行。
- `unstructured_path_acl.directory` 存在；旧路径规则的空值按递归目录规则兼容。
- `unstructured_op_audit.message` 的 MySQL 数据类型为 `text`；应用仍将单条审计错误消息限制为 1,800 个字符，以保留错误摘要且避免异常内容无限增长。
- Server 能启动且不加载文件数据源插件。
- 创建一个草稿任务后，任务表产生记录；触发后运行、运行项和 `run_record` 关联一致。

## 7. 回滚

代码回滚时，新表和 `queue_visible` 等新增字段可以保留，旧版本不会读取这些对象。不要在存在文件传输运行或审计要求时直接删除表或字段。

如必须结构回滚：

1. 停止文件传输入口、调度和 Worker 领取新任务。
2. 等待或取消活动运行并归档运行证据。
3. 备份九张新表及既有表新增字段。
4. 先回滚应用，再由 DBA 在确认无依赖后删除索引、字段和表。

结构删除不可依赖自动启动服务完成，必须单独评审和执行。

Outbox 代码回滚不删除两张事件表。先停止新增文件传输，将 Server 的 `STUDIO_FILE_TRANSFER_EVENT_MODE` 改为 `LEGACY_SCAN` 并重启；修复后再切回 `OUTBOX`。只有在确认没有版本依赖且完成事件数据备份后，才能单独评审删除新增表。
