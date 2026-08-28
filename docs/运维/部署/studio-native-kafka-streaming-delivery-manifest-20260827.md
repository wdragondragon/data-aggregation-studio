# Studio Native Kafka 发布交付白名单

**计划编号：** P0-KS-01
**生成时间：** 2026-08-28 02:45 +08:00
**用途：** 生产前交付审计，不替代代码评审、制品签名或生产环境验收。

## 1. Git 边界

本次实现跨两个独立 Git 仓库：

| 仓库 | 路径 | 当前分支 | 交付方式 |
| --- | --- | --- | --- |
| DataAggregation | `C:/dev/ideaProject/cloud-parent-springboot3/DataAggregation` | `jdk17` | 单独提交 DataAggregation 源码、测试和插件构建配置；外层仓库只更新子仓库指针。 |
| Studio | `C:/dev/ideaProject/cloud-parent-springboot3/DataAggregation/data-aggregation-studio` | `jdk17` | 单独提交 Studio 后端、前端、SQL、测试和文档；不要把运行产物提交到外层仓库。 |

## 2. DataAggregation 交付白名单

### 2.1 已修改的跟踪文件

```text
pom.xml
core/src/main/java/com/jdragon/aggregation/core/job/JobContainer.java
core/src/main/java/com/jdragon/aggregation/core/plugin/spi/reporter/JobPointReporter.java
core/src/main/java/com/jdragon/aggregation/core/statistics/communication/Communication.java
core/src/main/java/com/jdragon/aggregation/core/taskgroup/runner/AbstractRunner.java
core/src/main/java/com/jdragon/aggregation/core/taskgroup/runner/ReaderRunner.java
core/src/main/java/com/jdragon/aggregation/core/taskgroup/runner/WriterRunner.java
core/src/main/java/com/jdragon/aggregation/core/transport/channel/memory/MemoryChannel.java
core/src/test/java/com/jdragon/aggregation/core/transport/channel/ChannelStatisticsTest.java
job-plugins/reader/kafkareader/pom.xml
job-plugins/reader/kafkareader/src/main/java/com/jdragon/aggregation/kafka/KafkaReader.java
```

### 2.2 新增流式源码和测试

```text
core/src/main/java/com/jdragon/aggregation/core/streaming/job/*.java
core/src/test/java/com/jdragon/aggregation/core/streaming/job/StreamingJobContainerTest.java
job-plugins/reader/kafkareader/src/main/java/com/jdragon/aggregation/kafka/KafkaReaderOptions.java
job-plugins/reader/kafkareader/src/main/java/com/jdragon/aggregation/kafka/KafkaRecordParser.java
job-plugins/reader/kafkareader/src/main/java/com/jdragon/aggregation/kafka/KafkaStreamingSession.java
job-plugins/reader/kafkareader/src/test/java/com/jdragon/aggregation/kafka/KafkaStreamingSessionTest.java
job-plugins/reader/kafkareader/src/test/java/com/jdragon/aggregation/kafka/KafkaStreamingSessionIT.java
```

`core/src/main/java/com/jdragon/aggregation/core/streaming/*.java` 下已经存在的流式基础类属于同一功能集；提交时需逐个核对 `git status`，不得用目录级无差别添加覆盖用户文件。

## 3. Studio 交付白名单

### 3.1 后端源码、SQL 和测试

```text
backend/studio-dto/src/main/java/com/jdragon/studio/dto/enums/CollectionTaskExecutionMode.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/enums/StreamingDesiredState.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/enums/StreamingObservedState.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/model/CollectionTaskStreamingOptions.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/model/CollectionTaskStreamingRuntimeView.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/model/RunLogChunkView.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/model/StreamingMetricBucketView.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/model/StreamingTaskAttemptView.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/model/StreamingTaskDeploymentView.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/model/StreamingTaskEventView.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/model/StreamingTaskRunView.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/entity/RunLogChunkEntity.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/entity/StreamMetricBucketEntity.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/entity/StreamTaskAttemptEntity.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/entity/StreamTaskDeployEntity.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/entity/StreamTaskEventEntity.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/entity/StreamTaskRunEntity.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/mapper/RunLogChunkMapper.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/mapper/StreamMetricBucketMapper.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/mapper/StreamTaskAttemptMapper.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/mapper/StreamTaskDeployMapper.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/mapper/StreamTaskEventMapper.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/mapper/StreamTaskRunMapper.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StreamingHistoryCleanupService.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StreamingTaskCoordinatorService.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StreamingTaskRuntimeService.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioStreamingSchemaUpgradeSupport.java
backend/studio-server/src/main/java/com/jdragon/studio/server/web/scheduler/StreamingTaskScheduleRunner.java
backend/studio-server/src/main/java/com/jdragon/studio/server/web/config/FileTransferOutboxServerConfiguration.java
backend/studio-server/src/main/resources/update/20260827/20260827-native-kafka-streaming.sql
backend/studio-worker/src/main/java/com/jdragon/studio/worker/runtime/streaming/StreamingMetricsAccumulator.java
backend/studio-worker/src/main/java/com/jdragon/studio/worker/runtime/streaming/StreamingTaskWorkerExecutor.java
```

已修改的 Studio 跟踪文件（除上面单独列出的新增文件外）如下，必须与新增文件一起复核：

```text
.gitignore
backend/studio-desktop-runtime/src/main/resources/application.yml
backend/studio-desktop-runtime/src/main/resources/schema-sqlite.sql
backend/studio-dto/src/main/java/com/jdragon/studio/dto/enums/CollectionTaskStatus.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/enums/DispatchExecutionType.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/model/CollectionTaskDefinitionView.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/model/CollectionTaskListView.java
backend/studio-dto/src/main/java/com/jdragon/studio/dto/model/request/CollectionTaskSaveRequest.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/config/StudioPlatformProperties.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/entity/CollectionTaskDefinitionEntity.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CloudObjectStorageService.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/CollectionTaskService.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DispatchService.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/MinioRunLogObjectStore.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/RunLogObjectStore.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/RunLogStorageService.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/RunTerminationService.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioInitializationService.java
backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java
backend/studio-infra/src/test/java/com/jdragon/studio/infra/service/DataIngestionAccessLogSupportTest.java
backend/studio-infra/src/test/java/com/jdragon/studio/infra/service/DispatchServiceRuntimeSnapshotTest.java
backend/studio-infra/src/test/java/com/jdragon/studio/infra/service/RunTerminationSqliteIntegrationTest.java
backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/CollectionTaskController.java
backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RunController.java
backend/studio-server/src/main/java/com/jdragon/studio/server/web/service/RunLogProxyService.java
backend/studio-server/src/main/resources/application.yml
backend/studio-server/src/main/resources/schema-mysql.sql
backend/studio-server/src/test/java/com/jdragon/studio/server/bootstrap/StudioServerPluginlessStartupTest.java
backend/studio-test/src/test/java/com/jdragon/studio/test/RunLogStorageServiceRegressionTest.java
backend/studio-worker/src/main/java/com/jdragon/studio/worker/runtime/config/WorkerSchedulingConfig.java
backend/studio-worker/src/main/java/com/jdragon/studio/worker/runtime/log/RunLogFileService.java
backend/studio-worker/src/main/java/com/jdragon/studio/worker/runtime/runner/WorkerLifecycleRunner.java
backend/studio-worker/src/main/java/com/jdragon/studio/worker/web/controller/InternalRunLogController.java
backend/studio-worker/src/main/resources/application.yml
backend/studio-worker/src/test/java/com/jdragon/studio/worker/RunLogFileServiceSecurityTest.java
backend/studio-worker/src/test/java/com/jdragon/studio/worker/config/WorkerSchedulingConfigurationTest.java
frontend/apps/web/package.json
frontend/apps/web/src/components/CollectionTaskFieldMappingEditor.vue
frontend/apps/web/src/components/HttpReaderOptionsEditor.vue
frontend/apps/web/src/components/HttpRequestOptionsEditor.vue
frontend/apps/web/src/components/HttpWebServiceOptionsEditor.vue
frontend/apps/web/src/components/collection-task/CollectionTaskBindingSection.vue
frontend/apps/web/src/components/collection-task/CollectionTaskEditorFooter.vue
frontend/apps/web/src/components/collection-task/CollectionTaskEditorHeader.vue
frontend/apps/web/src/components/collection-task/CollectionTaskMappingSection.vue
frontend/apps/web/src/components/collection-task/CollectionTaskReviewSection.vue
frontend/apps/web/src/components/collection-task/CollectionTaskStepIndicator.vue
frontend/apps/web/src/components/collection-task/collectionTaskEditorSupport.ts
frontend/apps/web/src/constants/studioDomain.ts
frontend/apps/web/src/utils/studio.ts
frontend/apps/web/src/views/CollectionTaskEditorView.vue
frontend/apps/web/src/views/CollectionTasksView.vue
frontend/packages/api-sdk/src/client.ts
frontend/packages/api-sdk/src/types.ts
frontend/packages/i18n/src/messages/web.ts
frontend/packages/meta-form/src/MetaFormRenderer.vue
docs/README.md
docs/使用/接口/03-collection-and-field-mapping.md
```

定向测试白名单：

```text
backend/studio-infra/src/test/java/com/jdragon/studio/infra/service/CollectionTaskAssemblerServiceKafkaConfigTest.java
backend/studio-infra/src/test/java/com/jdragon/studio/infra/service/CollectionTaskStreamingModeServiceTest.java
backend/studio-infra/src/test/java/com/jdragon/studio/infra/service/NativeStreamingSchemaIntegrationTest.java
backend/studio-infra/src/test/java/com/jdragon/studio/infra/service/RuntimeDatasourceProbeExecutorKafkaHydrationTest.java
backend/studio-infra/src/test/java/com/jdragon/studio/infra/service/StreamingHistoryCleanupServiceTest.java
backend/studio-infra/src/test/java/com/jdragon/studio/infra/service/StreamingTaskCoordinatorServiceIntegrationTest.java
backend/studio-infra/src/test/java/com/jdragon/studio/infra/service/StreamingTaskRuntimeServiceIntegrationTest.java
backend/studio-server/src/test/java/com/jdragon/studio/server/web/controller/CollectionTaskStreamingRouteContractTest.java
backend/studio-worker/src/test/java/com/jdragon/studio/worker/runtime/runner/WorkerStreamingDispatchTest.java
backend/studio-worker/src/test/java/com/jdragon/studio/worker/runtime/streaming/StreamingMetricsAccumulatorTest.java
backend/studio-worker/src/test/java/com/jdragon/studio/worker/runtime/streaming/StreamingTaskWorkerExecutorTest.java
```

### 3.2 前端和文档

```text
frontend/apps/web/scripts/collection-task-streaming-lock.test.mjs
frontend/apps/web/src/components/collection-task/StreamingTaskMonitorDrawer.vue
frontend/apps/web/src/components/collection-task/*.vue  # 仅提交本次 diff 中的文件
frontend/apps/web/src/views/CollectionTaskEditorView.vue
frontend/apps/web/src/views/CollectionTasksView.vue
frontend/packages/api-sdk/src/client.ts
frontend/packages/api-sdk/src/types.ts
frontend/packages/i18n/src/messages/web.ts
frontend/packages/meta-form/src/MetaFormRenderer.vue
docs/规划/studio-native-kafka-streaming-plan-20260827.md
docs/测试/数据采集/studio-native-kafka-streaming-test-cases-20260827.md
docs/运维/部署/studio-native-kafka-streaming-deployment.md
docs/运维/部署/studio-native-kafka-streaming-delivery-manifest-20260827.md
docs/使用/接口/03-collection-and-field-mapping.md
docs/README.md
```

## 4. 明确排除项

以下内容不属于 Git 交付集，即使 `git status` 显示为未跟踪，也必须保留在本机供回顾但不得提交：

```text
**/target/**
**/node_modules/**
**/dist/**
**/coverage/**
**/applogs/**
**/reports/**
**/runtime/**
**/temp/**
**/tmp/**
**/*.log
**/*.pid
**/*.class
**/*jackson-tree.txt
**/effective-worker-pom.xml
*.db
*.sqlite
```

DataAggregation 外层仓库中的 `data-aggregation-studio` 只记录子仓库指针；不要在外层执行无差别 `git add .`，也不要把 Studio 的运行产物提升到外层仓库。

## 5. 构建证据

固定工具链：

```text
JDK C:\dev\Java\jdk-17.0.12
Maven C:\dev\apache-maven-3.8.8
```

已执行并通过：

```powershell
mvn -pl core,job-plugins/reader/kafkareader,job-plugins/writer/kafkawriter -am -DskipTests clean package
mvn -pl studio-server,studio-worker -am -DskipTests clean package
npm run build:web
npm run build:desktop
node apps/web/scripts/collection-task-streaming-lock.test.mjs
```

结果：DataAggregation 12 个 reactor 模块构建成功；Studio Server/Worker 及其 7 个依赖模块构建成功；Web 与 Desktop 生产构建成功；前端锁定测试通过。生成的制品路径为：

```text
DataAggregation/core/target/core-1.0_jdk17-SNAPSHOT.jar
DataAggregation/job-plugins/reader/kafkareader/target/kafkareader-1.0_jdk17-SNAPSHOT.jar
DataAggregation/job-plugins/reader/kafkareader/target/kafkareader.zip
DataAggregation/job-plugins/writer/kafkawriter/target/kafkawriter-1.0_jdk17-SNAPSHOT.jar
DataAggregation/job-plugins/writer/kafkawriter/target/kafkawriter.zip
DataAggregation/data-source-plugins/data-source-handler-queue-kafka/target/data-source-handler-queue-kafka-1.0_jdk17-SNAPSHOT.jar
DataAggregation/data-source-plugins/data-source-handler-queue-kafka/target/kafka.zip
DataAggregation/data-aggregation-studio/backend/studio-server/target/studio-server-0.1.0-SNAPSHOT-exec.jar
DataAggregation/data-aggregation-studio/backend/studio-worker/target/studio-worker-0.1.0-SNAPSHOT-exec.jar
DataAggregation/data-aggregation-studio/frontend/apps/web/dist
DataAggregation/data-aggregation-studio/frontend/apps/desktop/dist/renderer
```

以上是当前工作区的 `clean package` 证据，不等同于从远端干净 clone 复现，也不等同于正式制品已经在生产启动。生产发布前仍需使用 CI 或发布工作区重新构建、签名和启动检查。

## 6. 未闭合发布门禁

- 从无运行产物的干净 clone 恢复两个仓库并复现构建。
- 启动正式 Server/Worker/Web 制品，检查 18080、18081、`DEFAULT-LOCAL` 和插件目录装载。
- 生产 Kafka 多分区、rebalance、SASL/SSL/Kerberos、证书和 keytab 轮换。
- 多 Server、多 Worker、高可用、容量、告警、发布窗口和回滚演练。
- 生产 OSS Provider、区域、权限和生命周期策略。
- 正式浏览器复核归档 Blob 下载行为。

这些门禁不影响当前测试环境的数据保留；不得通过清空 Topic、消费组、任务、run、attempt、日志或指标来“修复”验收结果。

## 7. 2026-08-28 交付包增量

`package_all/release_0828/studio-native-kafka-upgrade-20260828-metrics-v2` 是本次指标监控补丁的最新本地交付包，配套插件仓库 release 为 `20260828-native-kafka-metrics-v2`。相较于此前包，新增或刷新内容包括：

- Server/Worker executable JAR：包含分钟指标聚合后分页、最新分钟优先和运行时修复。
- Web/Desktop renderer：包含监控页分页控件、当前页空闲 0 记录桶安全折叠和最新构建资源。
- Kafka source、reader、writer 三个插件：由 `package_all/build_plugin_repository.sh` 生成新的不可变 release，Kafka 客户端依赖统一为 `slf4j-api:1.7.36`。
- SQL、主计划、接口文档和流式测试用例：与当前代码和验收证据同步。

旧的 `20260828-native-kafka` 插件 release、`release_0824` 包、现有 Topic、消费组、任务、run、attempt、日志和指标均保留，未被覆盖或删除。
