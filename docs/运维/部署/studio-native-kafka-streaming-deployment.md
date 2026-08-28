# Studio Native Kafka 长期运行部署与回滚

**计划编号：** P0-KS-01
**适用版本：** 2026-08-27 本地实施基线
**范围：** DataAggregation Native Streaming + Studio Server/Worker/Web

## 1. 发布顺序

1. 备份并执行数据库增量；确认 `collection_task_definition.execution_mode`、`streaming_options_json` 和流式表结构可用。
2. 发布与任务配置兼容的 DataAggregation core、Kafka datasource、Kafka Reader/Writer 插件包。
3. 发布 Worker；确认 Worker 能识别流式 Dispatch、独立 streaming slots 和正式 `cancel()`。
4. 发布 Server；确认状态机、CAS、generation、lease、online/offline/recover API 可用。
5. 发布 Web/Desktop；确认 SDK 类型和页面只在 Server/Worker 均支持后开放 STREAMING 操作。

不要用 Liquibase 替代项目原始 SQL 交付。版本化增量脚本位于：

`backend/studio-server/src/main/resources/update/20260827/20260827-native-kafka-streaming.sql`

SQLite 新建库和升级路径由 `studio-desktop-runtime/src/main/resources/schema-sqlite.sql` 与 `StudioSchemaUpgradeService` 维护；MySQL 新建库由 `studio-server/src/main/resources/schema-mysql.sql` 维护。升级脚本必须可重复执行，历史任务回填 `BATCH`，不得根据 Reader 类型自动推断或启动流式任务。

## 2. 运行配置

### 2.1 Kafka 与任务

- Kafka 数据源只保存 Broker、协议和认证配置；Topic 来自源模型的 `physicalLocator`。
- STREAMING 任务的 `groupId` 属于任务运行配置，未填写时由服务端生成 `studio.<tenantId>.<taskId>`；运行期间不可修改。
- Kafka Source 使用 `enable.auto.commit=false`；默认 `pollTimeoutMs=1000`、`maxBatchRecords=1000`、`maxBatchBytes=16MB`。
- 第一版交付语义为 `AT_LEAST_ONCE`。目标批次成功后才提交下一 offset，commit 失败允许重放。
- 一个任务只允许一个 active attempt；Worker 重启从 committed offset 恢复，已下线任务不得恢复。

### 2.2 Server/Worker

- Worker 使用独立 streaming executor，默认 `studio.worker.streaming.max-concurrent=2`，不占用普通批任务槽位。
- attempt 失败按 5 秒至 5 分钟指数退避；连续 10 个失败 attempt 后进入 `FAILED`，由人工 `/recover` 恢复。
- 下线默认等待当前批次 60 秒；超时取消且不提交当前批次。
- 若必须重启本地服务，只复用 IDEA 配置：
  - Server Main class：`com.jdragon.studio.server.bootstrap.StudioServerApplication`，Module：`studio-server`，VM：`-Xms256m -Xmx512m`。
  - Worker Main class：`com.jdragon.studio.worker.bootstrap.StudioWorkerApplication`，Module：`studio-worker`，VM：`-Xms256m -Xmx512m`，环境变量 `STUDIO_CLUSTER_CODE=default-local`。
  - 重启后检查 Server `18080`、Worker `18081` 和 `DEFAULT-LOCAL` 运行时状态。

### 2.3 日志与指标

- 流式 attempt 使用动态 RollingFileAppender，按小时或 128MB 滚动。
- 关闭分片 gzip 后只上传一次；活动分片约每 60 秒同步一次。
- `/log` 查询单页最多 512KB；ZIP 归档按分片流式写出，不整份加载进 JVM 堆。
- Kafka 空 poll 使用 DEBUG 或周期汇总日志。
- Worker 每分钟写入读取/写入/失败/dirty/字节/批次/重试/lag/checkpoint 等指标；任务级查询跨 attempt 聚合，避免重复累计。
- 流式历史清理默认关闭，以保留测试和故障证据。生产如需开启，设置 `STUDIO_STREAMING_HISTORY_CLEANUP_ENABLED=true`，并按容量评估设置 `STUDIO_STREAMING_HISTORY_RETENTION_DAYS`（默认 30）、`STUDIO_STREAMING_HISTORY_CLEANUP_INTERVAL_MILLIS`（默认 3600000）、`STUDIO_STREAMING_HISTORY_CLEANUP_BATCH_SIZE`（默认 1000）和 `STUDIO_STREAMING_HISTORY_CLEANUP_MAX_BATCHES`（默认 20）。清理由 Server 持有集群锁执行，先清理非活动 run/attempt 的指标、事件和日志分片，再删除超过保留期且无日志引用的终态 attempt，最后删除已无 attempt 的终态 run；活动 deployment、活动 attempt 和仍有日志分片引用的记录均受保护。对象删除失败会保留元数据并阻止对应 attempt/run 删除，等待下一周期重试。

## 3. 上线前检查

```text
[ ] Java 17.0.12、Maven 3.8.8 与发布制品版本正确
[ ] 数据库增量 SQL 已备份、执行并重复校验
[ ] 历史任务 executionMode 全部为 BATCH，未自动创建 RUNNING deployment
[ ] Kafka broker、Topic、分区和 ACL 可达
[ ] Server/Worker/DEFAULT-LOCAL 健康
[ ] Worker streaming slots、对象存储日志权限和容量已核对
[ ] 流式任务的 source physicalLocator、groupId、目标模型主键/幂等策略已审查
[ ] 不在配置、Dispatch、日志和文档中携带密码、Token 或完整秘密
[ ] 先用 NativeStreaming-* 隔离 Topic/任务完成短批验证
[ ] 发布前执行 DataAggregation Kafka 门禁：`mvn -pl job-plugins/reader/kafkareader -am -Pkafka-it test`
[ ] 发布前从干净检出构建并启动正式制品；不得用脏工作区 classpath 作为唯一制品证据
[ ] 测试环境保持 `STUDIO_STREAMING_HISTORY_CLEANUP_ENABLED=false`，不删除既有测试任务、Topic、run、日志和指标
```

## 4. 运行监控与故障处置

优先查看 `streaming-runtime`、`streaming-metrics`、`streaming-events`、`streaming-log-chunks` 和 `/runs/{id}/log`。按以下顺序判断：

1. `desiredState` 是否仍为 `RUNNING`；若为 `STOPPED`，不得人工重启 Worker 代替上线。
2. `observedState`、当前 attempt heartbeat、retryAfter 和连续失败计数是否一致。
3. Kafka log end offset、group committed offset、lag 和最近 checkpoint 是否同步。
4. Writer 目标是否成功；commit 后的 Studio checkpoint/指标上报失败不得触发 Writer 重跑或 abort。
5. 若 Worker 重启，确认旧 attempt 为中断、新 attempt 从 committed offset 续读，逻辑 run 未被错误重建。
6. 若连续失败达到 10 次，修复根因后显式调用 `/recover`，并保留故障事件和重放证据。

目标端故障注入必须使用隔离目标；不要停止承载 Studio 业务库的共享 MySQL 容器来模拟 Writer 不可用，否则会同时阻断协调、状态和指标数据库。

## 5. 回滚

- 回滚范围只包括 Web、Server、Worker 和 DataAggregation 插件版本；先停止新增版本的 STREAMING deployment，再恢复应用。
- 不删表、不清 Kafka offset、不删除 Topic、不删除既有任务、模型、run、attempt、checkpoint、日志和指标。
- 新增表列保留；旧应用忽略新增字段。回滚后流式 deployment 保持 `STOPPED`，待兼容版本恢复后由用户重新上线。
- 回滚顺序与发布顺序相反：Web -> Server -> Worker -> 插件；数据库结构不回退。
- 回滚完成后检查普通 BATCH 任务、旧 Kafka Reader/Writer、Server/Worker 健康和日志脱敏。

## 6. 验收与证据

本地验收使用：

- 主计划：`docs/规划/studio-native-kafka-streaming-plan-20260827.md`
- 测试用例：`docs/测试/数据采集/studio-native-kafka-streaming-test-cases-20260827.md`
- 接口文档：`docs/使用/接口/03-collection-and-field-mapping.md`

每次发布或回滚记录制品版本、数据库脚本、任务/run/attempt、Topic/group offset、状态、日志位置和 `git diff --check` 结果。生产环境仍需独立验证网络、容量、对象存储、高可用、监控告警和发布窗口回滚，不得用本机 Kafka 证据替代生产结论。
