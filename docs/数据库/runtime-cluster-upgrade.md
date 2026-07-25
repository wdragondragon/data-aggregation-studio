# Studio 多运行集群升级说明

## 交付物

- MySQL 增量：`backend/studio-server/src/main/resources/update/20260720/20260720-runtime-cluster.sql`
- 同步调用幂等增量：`backend/studio-server/src/main/resources/update/20260722/20260722-runtime-invocation-idempotency.sql`
- Dispatch 短期密文增量：`backend/studio-server/src/main/resources/update/20260723/20260723-dispatch-protected-payload.sql`
- MySQL 全量结构：`backend/studio-server/src/main/resources/schema-mysql.sql`
- SQLite 全量结构：`backend/studio-desktop-runtime/src/main/resources/schema-sqlite.sql`
- 启动升级：`StudioSchemaUpgradeService`
- 独立升级入口：`backend/scripts/upgrade-studio-schema.ps1`
- 单集群历史回填入口：`backend/scripts/backfill-runtime-cluster.ps1`
- 离线加密密钥轮换入口：`backend/scripts/rotate-studio-encryption-key.ps1`

在线版 `studio-server` 和 `studio-worker` 默认配置 `studio.schema.auto-upgrade-on-startup=false`，普通重启不会自动执行升级。选择应用升级路径时，应在维护窗口为升级进程提供目标数据库连接环境变量并执行 `backend/scripts/upgrade-studio-schema.ps1`；只有显式开启 `studio.schema.auto-upgrade-on-startup=true` 的受控启动才会自动运行同一升级服务。生产环境不要把“服务已重启”等同于“结构已升级”。

增量创建五张表：

| 表 | 用途 |
|---|---|
| `studio_runtime_cluster` | 集群代码、名称、启停、状态、版本和实例摘要。 |
| `studio_runtime_endpoint` | Worker HTTP/SLB 端点及加密的 URL、Header、Token 和测试结果；旧 `LOCAL` 值仅用于迁移清理。 |
| `studio_project_runtime_cluster` | 项目可用集群、首选集群和手动运行覆盖许可。 |
| `datasource_cluster_binding` | 数据源和人工确认适用集群的多对多绑定。 |
| `studio_runtime_validation` | 资源运行配置失效的原因和最近校验结果。 |

并为连接健康、连接测试历史、各类可执行资源、任务分发、Worker lease、运行记录、告警实例以及三类服务访问日志增加集群字段和查询索引。`dispatch_task` 同时增加 `resource_revision/claim_token/worker_boot_id`，`run_record` 增加 `worker_boot_id`，用于资源快照校验和 Worker 重启后的 CAS 防覆盖。`20260722` 增加同步调用幂等表，`20260723` 增加 `dispatch_task.protected_payload_ciphertext`，用于加密短期保存即席脚本正文和运行参数。连接健康唯一维度由 `(tenant_id, connection_fingerprint)` 升级为 `(tenant_id, runtime_cluster_id, connection_fingerprint)`；可空的历史记录通过 `legacy_connection_fingerprint` 生成列及独立唯一索引继续防重。告警实例增加请求/实际集群和 `idx_alert_incident_cluster`。

## 执行步骤

1. 备份数据库并记录当前 Server、Worker 版本和正在运行的任务。
2. 在维护窗口由 DBA 按 `20260720`、`20260722`、`20260723` 顺序执行独立增量 SQL，或执行 `backend/scripts/upgrade-studio-schema.ps1` 让兼容版本的 `StudioSchemaUpgradeService` 完成等价升级。独立 SQL 按数据库变更单一次性执行，重复执行前必须先核对结构；升级服务会先做结构探测并可幂等重跑。执行记录必须明确写清实际采用哪一种，不能把“应用升级成功”表述成“独立 SQL 已手工执行”。
3. 确认 `dispatch_task.protected_payload_ciphertext` 已存在后，才能启动包含短期 Dispatch 密文逻辑的新 Server 和 Worker。若已由 DBA 执行脚本，启动升级应只做幂等核验。
4. 排空并停止旧 Worker，先部署新版 Worker，再部署会生成短期密文 Dispatch 的新版 Server。新版 Worker 可以继续消费旧 Server 产生的公开 Payload；旧 Worker 无法解密新版 Server 产生的即席脚本任务，因此新版 Server 对外开放后禁止旧 Worker 继续领取任务。本次变更不支持新旧 Worker 无约束滚动混跑。
5. 核验五张新表、两列唯一索引 `uk_ds_conn_health_legacy_fp`、三列唯一索引 `uk_ds_conn_health_fp`，以及 `idx_dispatch_task_cluster_status_created`、`idx_run_record_project_cluster_created`、`idx_worker_lease_cluster_status`、`idx_ds_conn_record_cluster_lookup` 和 `idx_alert_incident_cluster`。
6. 按部署说明创建集群、授权项目、回填数据源适用范围和资源运行集群，再发布统一 Worker 执行版本。

单集群历史库使用独立维护命令预演，不在 Server 正常启动时自动回填：

运行前提：

- 在仓库根目录或能够访问 `backend/scripts` 的源码目录执行。
- 安装与项目一致的 JDK 和 Maven，并能够完成 `studio-server -am` 构建；该脚本不是无需构建工具的生产二进制。
- 通过安全环境变量提供 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`，必要时同时提供 JDBC Driver 和 profile 配置。
- 先完成数据库备份，停止调度和配置写入，并确认当前使用的是准备升级的数据库，而不是本地默认连接。

```powershell
backend\scripts\backfill-runtime-cluster.ps1 -DryRun
```

预演只输出候选数量。确认后执行：

```powershell
backend\scripts\backfill-runtime-cluster.ps1
```

该命令使用与升级脚本相同的数据库环境变量，幂等创建或复用唯一 `DEFAULT-LOCAL` 集群，并回填项目授权、数据源绑定和空资源集群。若租户已经存在多条集群记录，或唯一集群不是有效的 `DEFAULT-LOCAL`，该租户会被跳过，必须人工迁移。工具不是应用启动逻辑，执行结束后进程退出。

脚本会先执行 Maven 构建，再启动一次性 Java 进程。标准输出中的 `BackfillReport` 是验收依据：

- `status='DRY_RUN'` 只表示已完成预演，没有写库。
- `status='COMPLETED'` 表示事务已完成，还必须继续执行下方空值和绑定核验 SQL。
- `status='SKIPPED:*'` 表示前置结构或租户条件不满足，不能当成迁移成功。
- PowerShell `$LASTEXITCODE` 非 `0`、Maven 构建失败或 Java 异常均视为失败；不得仅凭脚本窗口关闭判断成功。

失败后保持调度和新 Worker 停止，保存完整脱敏输出并核对备份。工具对其支持的创建和空值回填是幂等的，定位并修复原因后可以重新 dry-run；不得在结果不明时手工重复插入授权、绑定或集群记录。

### 活动 Dispatch 处置

一次性回填不会修改 `dispatch_task.target_cluster_id`。发布统一 Worker 版本前，空目标任务按状态处理：

| 状态 | 处理方式 |
| --- | --- |
| `QUEUED` | 新 Worker 不会领取。先通过现有运行停止/清理能力将旧记录置为终态；没有业务入口时由 DBA 按变更单处置并保留原因，再根据资源保存的运行集群通过业务入口重新触发。只有租户、项目、资源和数据源适用关系均无歧义且有 DBA 变更单时，才允许显式回填。 |
| `RUNNING` | 保留兼容 Worker 等待完成；失联任务先走现有 stale recovery 或人工终止流程进入终态，禁止直接改写已领取记录的目标集群。 |
| `SUCCESS/FAILED/STOPPED` 等终态 | 保留为空作为历史记录，不会被新 Worker 领取；不要改写历史实际执行位置。 |

切换证明 SQL：

```sql
select status, count(*) as task_count
from dispatch_task
where deleted = 0
  and target_cluster_id is null
group by status
order by status;

select count(*) as blocking_dispatch_count
from dispatch_task
where deleted = 0
  and status in ('QUEUED', 'RUNNING')
  and target_cluster_id is null;
```

`blocking_dispatch_count` 必须为 `0`。处置前应导出空目标活动 Dispatch 的 ID、租户、项目、资源类型、资源 ID 和状态，作为变更与回滚附件，但不得导出 Payload 中的敏感配置。回滚时先停调度和新 Worker，再回退应用；默认保留已经确认的显式目标，不自动迁移到其它集群。

建议核验 SQL：

```sql
show tables like '%runtime_cluster%';
show create table datasource_connection_health;
show index from datasource_connection_health where Key_name in ('uk_ds_conn_health_legacy_fp', 'uk_ds_conn_health_fp');
show index from dispatch_task where Key_name = 'idx_dispatch_task_cluster_status_created';
show index from worker_lease where Key_name = 'idx_worker_lease_cluster_status';
show index from studio_alert_incident where Key_name = 'idx_alert_incident_cluster';
```

发布统一 Worker 执行版本前还应核对：

```sql
select count(*) as missing_target_cluster
from dispatch_task
where deleted = 0
  and status in ('QUEUED', 'RUNNING')
  and target_cluster_id is null;

select count(*) as missing_runtime_cluster
from collection_task_definition
where deleted = 0 and runtime_cluster_id is null;
```

其它可执行资源表应执行同类空值检查。多集群环境不能因为唯一交集不明确而批量猜测目标值。

## 加密密钥迁移边界

新部署必须在首次保存数据源或运行端点前配置最终的非默认 `STUDIO_ENCRYPTION_SECRET`。Server、Worker 及所有需要读取这些密文的进程必须使用相同值。

已有环境不能通过“替换环境变量并重启”完成轮换。当前版本提供停机使用的离线轮换工具，但没有在线双密钥、滚动轮换或应用启动时自动重加密能力。工具覆盖当前由 `EncryptionService` 持久化的全部位置：

- `datasource_definition.technical_metadata`、`data_model.technical_metadata` 和 `collection_task_definition.source_bindings_json` 中的全部 `ENC(...)`，包括嵌入 JSON/XML 字符串内部的标记。
- `studio_runtime_endpoint` 的 URL、受管 Header 和 Token 密文。
- `studio_alert_channel` 的 Webhook URL、Header 和签名密钥密文。
- `dispatch_task.protected_payload_ciphertext` 中尚未被 Worker 消费的短期脚本执行输入。
- `studio_runtime_idempotency` 的响应体密文。

### 离线轮换前提

1. 停止外部流量、调度、全部 Studio Server、Worker、Desktop Runtime 和其它可能读写上述表的进程。工具的 CAS 只能检测已读值被并发修改，不能把在线轮换变成受支持场景。
2. 完成数据库全量备份并验证可恢复；备份是 Apply 后回退旧密钥的唯一可靠手段。
3. 在数据库副本上先演练 Dry Run 和 Apply，并确认数据量、维护窗口和逐集群验证步骤。
4. 从凭据系统向当前 PowerShell 进程注入数据库连接、旧密钥和新密钥。旧、新密钥不支持命令行参数，不得写入脚本、SQL、日志、工单或验收文档。
5. 新密钥必须为最终生产密钥且不能使用已知默认值；旧密钥在 Apply 完成和备份保留期结束前不得销毁。

该脚本从源码目录执行，需要 Maven 使用 JDK 17 或更高版本；低版本会在构建和数据库连接前被明确拒绝。数据库连接沿用 Spring 标准变量；如 JDBC URL 不能自动识别驱动，可额外设置 `SPRING_DATASOURCE_DRIVER_CLASS_NAME`。

```powershell
$env:SPRING_DATASOURCE_URL='<jdbc-url>'
$env:SPRING_DATASOURCE_USERNAME='<db-user>'
$env:SPRING_DATASOURCE_PASSWORD='<db-password>'
$env:STUDIO_ENCRYPTION_OLD_SECRET='<old-secret-from-secret-store>'
$env:STUDIO_ENCRYPTION_NEW_SECRET='<new-secret-from-secret-store>'

backend\scripts\rotate-studio-encryption-key.ps1
```

默认只执行 Dry Run，不写数据库。输出中的 `Values requiring rotation` 和 `Candidate row/column updates` 必须与清点结果一致；`Schema targets not present` 只允许对应当前数据库版本尚不存在的表或列。任一密文无法由旧/新密钥解密、`ENC(...)` 损坏或未闭合，或同一值同时被两把密钥识别为有效密文时，工具会失败关闭，不能忽略后继续 Apply。

完成备份、Dry Run 审批后，在同一个受控进程环境中增加双确认并执行：

```powershell
$env:STUDIO_ENCRYPTION_ROTATION_CONFIRM='ROTATE'
backend\scripts\rotate-studio-encryption-key.ps1 -Apply -SkipBuild
```

`-Apply` 和 `STUDIO_ENCRYPTION_ROTATION_CONFIRM=ROTATE` 缺一不可。首次 Dry Run 若没有产生并保留已验证的构建产物，应省略 `-SkipBuild`。Apply 在单个数据库事务中按主键分页扫描，以原值 CAS 更新；任一损坏密文、并发修改或更新数量异常都会回滚整个事务。工具支持旧、新密钥数据混存和重复执行，已经使用新密钥的值保持不变。

### 轮换后验证

1. 确认 Apply 退出码为 `0`，`Updated row/column values` 与 Dry Run 候选数一致，再执行一次 Dry Run，候选数必须为 `0`。
2. 将全部 Server、Worker、Desktop Runtime 和需要解密 Studio 配置的进程统一切换到新的 `STUDIO_ENCRYPTION_SECRET`；不能出现新旧密钥实例混跑。
3. 先启动受限 Server，再逐集群启动一个 Worker，验证每个数据源适用集群、每个启用运行端点、告警 Webhook、同步服务和即席脚本；确认 Worker 解密后、执行前已清空 `protected_payload_ciphertext`，运行记录、访问日志和告警投递正常后再恢复其余实例、调度和外部流量。
4. 保留脱敏计数、退出码、备份标识和逐集群验证结果。工具不会输出密钥、明文或解密后的端点值。

若 Apply 后需要回退，先停止全部 Studio 进程并恢复轮换前数据库备份，再把所有进程统一恢复为旧密钥。禁止只把环境变量改回旧密钥，也不要通过手工 SQL 修改部分密文。查询接口返回的脱敏值不能用于恢复原始秘密。

## 当前验证记录

- 2026-07-20 已使用真实 MySQL 启动 Studio Server，`StudioSchemaUpgradeService` 完成运行集群结构升级且后端全量测试通过。
- 当前记录只能证明“应用启动升级已执行”。独立文件 `20260720-runtime-cluster.sql` 是否由 DBA 单独执行，应以数据库变更单或命令记录为准；没有证据时不得写成已手工执行。
- 2026-07-21 已验证默认本地、`C46`、`C50` 三个集群共享数据库定向领取；`C46/C50` Python 任务只由目标 Worker 领取，运行记录中的请求集群与实际集群一致。
- P0-MC-02 已取消正常部署中的 `LEGACY/ENFORCED` 分叉；真实环境仍需完成非默认密钥下的数据源与端点密文处置、Server 无插件部署和单/多集群回滚验收，完成前不把目标标记为已完成。

## 数据迁移原则

- 首轮增量字段为兼容滚动升级保持可空，但统一运行版本会明确拒绝空集群运行数据。
- 单集群环境创建并回填 `default-local`；多集群环境不能根据连接测试推断数据源适用范围，必须由管理员确认。
- 一次性回填工具不覆盖非空 `runtimeClusterId`，不恢复已删除授权/绑定，也不会处理存在多条集群记录的租户；正常 Server 启动永不调用该工具。
- 数据源绑定使用 `enabled` 软停用，保留审计线索；移除绑定不会中止已经开始的运行。
- 不引入数据库外键，以兼容现有分布式 ID、逻辑删除和滚动升级；关系完整性由应用层的租户、项目和启停校验保证。

## 回滚

先停止新调度，排空或保留目标集群队列，然后回退应用版本。默认不删除五张表、列或索引，旧版会忽略这些增量结构。只有在已备份且明确放弃运行历史时，才由 DBA 单独制定逆向 DDL；常规回滚不执行删表。
