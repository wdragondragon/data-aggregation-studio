# Studio Server / Worker 配置说明

更新时间：2026-05-31

本文档说明 `data-aggregation-studio` 中 `studio-server` 与 `studio-worker` 的主要可配置项。配置项来源包括：

- `backend/studio-server/src/main/resources/application.yml`
- `backend/studio-worker/src/main/resources/application.yml`
- `StudioPlatformProperties` 中 `studio.*` 配置绑定

生产环境建议通过环境变量、启动参数或 Nacos 配置覆盖默认值，不建议直接修改仓库内 `application.yml`。

## 1. 配置优先级与模块边界

| 模块 | 默认端口 | 主要职责 | 关键配置重点 |
| --- | --- | --- | --- |
| `studio-server` | `18080` | Web API、开放服务、调度扫描、运行日志读取、系统管理 | 数据库、Redis、Nacos、集群锁、运行日志对象存储、开放接口安全 |
| `studio-worker` | `18081` | Worker 心跳、任务领取、任务执行、运行日志采集与上传 | 数据库、Redis、Worker 组、实例身份、运行日志目录、对象存储、任务租约 |

配置建议：

| 场景 | 建议 |
| --- | --- |
| 本地开发 | 可以使用 `application.yml` 默认值，但 Redis、数据库、Nacos 建议通过环境变量覆盖。 |
| 服务器部署 | 使用环境变量或外部配置中心覆盖数据库、Redis、Nacos、Token、对象存储等配置。 |
| K8s 多副本 | 使用环境变量注入 `POD_UID`、`POD_NAME`、`NODE_NAME`、`STUDIO_WORKER_GROUP_CODE`。 |
| 敏感配置 | 数据库密码、Redis 密码、Nacos 密码、内部 Token、对象存储密钥不应提交到代码仓库。 |

## 2. 通用 Spring 配置

以下配置 server 与 worker 都存在。

| 配置项 | 环境变量 | 默认/示例 | 说明 |
| --- | --- | --- | --- |
| `spring.profiles.active` | `APP_ACTIVE` | `prod` | Spring profile。 |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:mysql://<host>:3306/data_aggregation_studio?...` | Studio 元数据库连接。server 与 worker 必须指向同一库。 |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | 按环境填写 | 数据库用户名。 |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | 按环境填写 | 数据库密码。生产必须覆盖。 |
| `spring.datasource.driver-class-name` | `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | `com.mysql.cj.jdbc.Driver` | 数据库驱动。 |
| `spring.data.redis.host` | `REDIS_HOST` | 按环境填写 | Redis 地址。建议通过启动变量指定，不改配置文件。 |
| `spring.data.redis.port` | `REDIS_PORT` | `6379` | Redis 端口。 |
| `spring.data.redis.database` | `REDIS_DATABASE` | `1` | Redis database。 |
| `spring.data.redis.password` | `REDIS_PASSWORD` | 按环境填写 | Redis 密码。生产必须覆盖。 |
| `spring.cloud.nacos.username` | `NACOS_USER` | `nacos` | Nacos 用户名。 |
| `spring.cloud.nacos.password` | `NACOS_PASSWORD` | 按环境填写 | Nacos 密码。生产必须覆盖。 |
| `spring.cloud.nacos.discovery.server-addr` | `NACOS_SERVER` | `127.0.0.1:8848` | Nacos 服务发现地址。 |
| `spring.cloud.nacos.discovery.namespace` | `NACOS_NAMESPACE` | 按环境填写 | Nacos namespace。 |
| `spring.cloud.nacos.discovery.group` | `NACOS_GROUP` | 按环境填写 | Nacos group。 |
| `spring.cloud.nacos.config.server-addr` | `NACOS_SERVER` | `127.0.0.1:8848` | Nacos 配置中心地址。 |
| `spring.cloud.nacos.config.namespace` | `NACOS_NAMESPACE` | 按环境填写 | 配置中心 namespace。 |
| `spring.cloud.nacos.config.group` | `NACOS_GROUP` | 按环境填写 | 配置中心 group。 |
| `management.endpoints.web.exposure.include` | 启动参数或配置中心 | `health,info` | 暴露 actuator 健康检查和 info。 |

说明：

- `studio-server` 的 `spring.sql.init.mode` 当前为 `never`，MySQL 首次建表应按 SQL 文件手工初始化。
- `mybatis-plus.configuration.map-underscore-to-camel-case` 当前固定为 `true`。

## 3. Studio 通用配置

以下配置由 `studio.*` 绑定，server 与 worker 均可使用。

| 配置项 | 环境变量 | 默认/示例 | 说明 |
| --- | --- | --- | --- |
| `studio.aggregation-home` | `STUDIO_AGGREGATION_HOME` 或 `-Daggregation.home` | `../../package_all/aggregation` | DataAggregation 插件与运行资源目录。 |
| `studio.encryption-secret` | `STUDIO_ENCRYPTION_SECRET` | 按环境填写 | 加密密钥。当前 yml 有默认值，生产建议覆盖。 |
| `studio.timezone` | `STUDIO_TIMEZONE` | `Asia/Shanghai` | Studio 时间处理时区。 |
| `studio.scan-plugins-on-startup` | `STUDIO_SCAN_PLUGINS_ON_STARTUP` | `true` | 启动时是否扫描插件。 |
| `studio.instance-id` | `STUDIO_INSTANCE_ID` / `POD_UID` | 自动推导 | 当前 server/worker 实例 ID。K8s 推荐使用 `POD_UID`。 |
| `studio.pod-name` | `POD_NAME` / `HOSTNAME` | 自动推导 | Pod 名称或主机名。 |
| `studio.node-name` | `NODE_NAME` | 空 | K8s 节点名称。 |
| `studio.worker-group-code` | `STUDIO_WORKER_GROUP_CODE` | 按部署组填写 | Worker 组编码。worker 多副本接单按该组授权。 |
| `studio.worker-code` | `STUDIO_WORKER_CODE` | 按环境填写 | 历史兼容字段。新部署优先使用 `worker-group-code`。 |
| `studio.internal-api-token` | `STUDIO_INTERNAL_API_TOKEN` | 按环境填写 | server 与 worker 内部调用鉴权 Token。生产必须覆盖。 |
| `studio.desktop-runtime` | `STUDIO_DESKTOP_RUNTIME` | `false` | 是否桌面运行时。服务端部署保持 `false`。 |

### Gateway 信任配置

| 配置项 | 环境变量 | 默认/示例 | 说明 |
| --- | --- | --- | --- |
| `studio.gateway.trust-enabled` | `STUDIO_GATEWAY_TRUST_ENABLED` | `true` | 是否信任网关下发的上下文。 |
| `studio.gateway.shared-secret` | `STUDIO_GATEWAY_SHARED_SECRET` | 按环境填写 | 网关签名共享密钥。生产必须覆盖。 |
| `studio.gateway.signature-expire-seconds` | `STUDIO_GATEWAY_SIGNATURE_EXPIRE_SECONDS` | `300` | 网关签名有效期，单位秒。 |

### Python 执行配置

| 配置项 | 环境变量 | 默认/示例 | 说明 |
| --- | --- | --- | --- |
| `studio.python.executable` | `STUDIO_PYTHON_EXECUTABLE` | 空 | Python 可执行文件路径。 |
| `studio.python.executable-args` | 配置中心或启动参数 | 空数组 | Python 启动附加参数。 |
| `studio.python.execution-timeout-seconds` | `STUDIO_PYTHON_TIMEOUT_SECONDS` | `120` | Python 执行超时。 |
| `studio.python.temp-dir` | `STUDIO_PYTHON_TEMP_DIR` | 空 | Python 临时目录。 |

## 4. Server 专属配置

| 配置项 | 环境变量 | 默认/示例 | 说明 |
| --- | --- | --- | --- |
| `server.port` | `SERVER_PORT` 或启动参数 | `18080` | server HTTP 端口。 |
| `springdoc.api-docs.enabled` | 配置中心或启动参数 | `true` | 是否启用 OpenAPI 文档。 |
| `springdoc.swagger-ui.enabled` | 配置中心或启动参数 | `true` | 是否启用 Swagger UI。 |
| `knife4j.enable` | 配置中心或启动参数 | `true` | 是否启用 Knife4j。 |
| `studio.model-sync-task.max-concurrency` | `STUDIO_MODEL_SYNC_TASK_MAX_CONCURRENCY` | `1` | 模型同步任务最大并发。 |
| `studio.dispatch.scheduler-batch-size` | `STUDIO_SCHEDULER_BATCH_SIZE` | `500` | server 每轮调度扫描最大入队数量。 |
| `studio.dispatch.cluster-lock-lease-seconds` | `STUDIO_CLUSTER_LOCK_LEASE_SECONDS` | `120` | server 调度集群锁租约秒数。 |

Server 多副本部署要求：

- 多个 server 可以同时启动，但调度扫描器必须依赖数据库集群锁防重。
- 所有 server 必须连接同一 Studio 元数据库。
- 运行日志对象存储配置需要与 worker 一致，否则 server 无法读取 worker 上传的对象日志。

## 5. Worker 专属配置

| 配置项 | 环境变量 | 默认/示例 | 说明 |
| --- | --- | --- | --- |
| `server.port` | `SERVER_PORT` 或启动参数 | `18081` | worker HTTP 端口。 |
| `studio.runtime-log-dir` | `STUDIO_RUNTIME_LOG_DIR` | `./runtime/run-logs` | worker 本地运行日志目录。对象存储开启后仍会先写本地再上传。 |
| `studio.worker-api-base-url` | `STUDIO_WORKER_API_BASE_URL` | `http://127.0.0.1:${server.port}` | worker 内部 API 访问地址。K8s 对象存储模式下历史日志不依赖该地址。 |
| `studio.dispatch.worker-scheduler-pool-size` | `STUDIO_WORKER_SCHEDULER_POOL_SIZE` | `4` | worker 调度线程池大小。 |
| `studio.dispatch.dispatch-lease-minutes` | `STUDIO_DISPATCH_LEASE_MINUTES` | `10` | worker 已领取任务租约分钟数。 |
| `studio.dispatch.worker-offline-grace-minutes` | `STUDIO_WORKER_OFFLINE_GRACE_MINUTES` | `120` | worker 离线保留窗口，用于页面展示近期实例。 |

Worker 组化部署要求：

- 项目绑定的是 `workerGroupCode`，不是 Pod IP、Pod 名或 `POD_UID`。
- 同一 `workerGroupCode` 下允许多个 Pod 并发接单。
- 不同 Worker 组建议使用不同 Deployment 或 StatefulSet 区分。
- `workerInstanceId` 推荐使用 `POD_UID`，用于区分同组下的具体 Pod。

K8s 推荐变量：

```yaml
STUDIO_WORKER_GROUP_CODE: default-pool
STUDIO_INSTANCE_ID: ${POD_UID}
POD_NAME: ${metadata.name}
NODE_NAME: ${spec.nodeName}
```

## 6. 运行日志存储配置

运行日志支持两种存储模式。

| `studio.run-log.storage-type` | 说明 |
| --- | --- |
| `LOCAL` | 默认模式。日志保存在 worker 本地目录，历史日志依赖 worker 本地文件和 worker 可访问性。 |
| `OBJECT_STORAGE` | 对象存储模式。worker 本地写日志后同步到 S3/MinIO，server 通过 bucket/key 读取历史日志。 |

当前 `storage-type` 在代码中是字符串约定，可填值只有 `LOCAL` 和 `OBJECT_STORAGE`。如果填错，当前不会在配置绑定阶段按枚举直接失败，后续建议改成 Java enum。

### 对象存储配置

| 配置项 | 环境变量 | 默认/示例 | 说明 |
| --- | --- | --- | --- |
| `studio.run-log.object-storage.endpoint` | `STUDIO_RUN_LOG_OBJECT_ENDPOINT` | `http://<minio-host>:9000` | S3/MinIO endpoint。 |
| `studio.run-log.object-storage.access-key` | `STUDIO_RUN_LOG_OBJECT_ACCESS_KEY` | 按环境填写 | 对象存储 access key。生产必须覆盖。 |
| `studio.run-log.object-storage.secret-key` | `STUDIO_RUN_LOG_OBJECT_SECRET_KEY` | 按环境填写 | 对象存储 secret key。生产必须覆盖。 |
| `studio.run-log.object-storage.bucket` | `STUDIO_RUN_LOG_OBJECT_BUCKET` | 按环境填写 | 运行日志 bucket。`OBJECT_STORAGE` 模式下必填。 |
| `studio.run-log.object-storage.region` | `STUDIO_RUN_LOG_OBJECT_REGION` | 空 | S3 region，可按对象存储要求填写。 |
| `studio.run-log.object-storage.prefix` | `STUDIO_RUN_LOG_OBJECT_PREFIX` | `studio/run-logs` | 对象 key 前缀。 |
| `studio.run-log.object-storage.create-bucket` | `STUDIO_RUN_LOG_OBJECT_CREATE_BUCKET` | `true` | bucket 不存在时是否尝试创建。生产可按权限策略设为 `false`。 |

对象日志同步行为：

1. worker 任务开始时先写本地日志文件。
2. `OBJECT_STORAGE` 模式下，worker 每 10 秒同步一次运行中的日志，状态为 `WRITING`。
3. 任务结束后 worker 再上传一次完整日志，成功后状态为 `AVAILABLE`。
4. server 读取日志时优先根据 `run_record.log_object_bucket` 和 `run_record.log_object_key` 从对象存储读取。

## 7. 推荐最小配置

### 7.1 Server 最小生产配置

```bash
APP_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:mysql://<mysql-host>:3306/data_aggregation_studio?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=<db-user>
SPRING_DATASOURCE_PASSWORD=<db-password>
REDIS_HOST=<redis-host>
REDIS_PORT=6379
REDIS_PASSWORD=<redis-password>
NACOS_SERVER=<nacos-host>:8848
NACOS_NAMESPACE=<namespace>
NACOS_GROUP=<group>
STUDIO_AGGREGATION_HOME=/opt/data-aggregation/package_all/aggregation
STUDIO_INSTANCE_ID=${POD_UID}
STUDIO_INTERNAL_API_TOKEN=<internal-token>
```

### 7.2 Worker 最小生产配置

```bash
APP_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:mysql://<mysql-host>:3306/data_aggregation_studio?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=<db-user>
SPRING_DATASOURCE_PASSWORD=<db-password>
REDIS_HOST=<redis-host>
REDIS_PORT=6379
REDIS_PASSWORD=<redis-password>
NACOS_SERVER=<nacos-host>:8848
NACOS_NAMESPACE=<namespace>
NACOS_GROUP=<group>
STUDIO_AGGREGATION_HOME=/opt/data-aggregation/package_all/aggregation
STUDIO_WORKER_GROUP_CODE=default-pool
STUDIO_INSTANCE_ID=${POD_UID}
POD_NAME=${POD_NAME}
NODE_NAME=${NODE_NAME}
STUDIO_INTERNAL_API_TOKEN=<internal-token>
```

### 7.3 启用对象存储日志

server 与 worker 都需要配置同一组对象存储参数：

```bash
STUDIO_RUN_LOG_STORAGE_TYPE=OBJECT_STORAGE
STUDIO_RUN_LOG_OBJECT_ENDPOINT=http://<minio-host>:9000
STUDIO_RUN_LOG_OBJECT_ACCESS_KEY=<access-key>
STUDIO_RUN_LOG_OBJECT_SECRET_KEY=<secret-key>
STUDIO_RUN_LOG_OBJECT_BUCKET=studio-run-logs
STUDIO_RUN_LOG_OBJECT_PREFIX=studio/run-logs
STUDIO_RUN_LOG_OBJECT_CREATE_BUCKET=true
```

## 8. 常见配置问题

### 8.1 修改 Redis 地址应该改哪里？

优先通过启动环境变量设置：

```bash
REDIS_HOST=<redis-host>
REDIS_PORT=6379
```

不建议直接改仓库内 `application.yml`。

### 8.2 Worker 多副本时 `workerCode` 是否必须不同？

不要求。当前推荐使用：

- `workerGroupCode`：稳定调度池，用于项目绑定。
- `workerInstanceId`：具体 Pod 实例，推荐 `POD_UID`。

同一 `workerGroupCode` 下多个 Pod 可以并发接单，任务领取通过数据库原子更新防重复。

### 8.3 `storageType` 可以填哪些值？

当前可填：

```text
LOCAL
OBJECT_STORAGE
```

生产 K8s 环境推荐使用 `OBJECT_STORAGE`，否则 worker 重启或 Pod IP 变化后，历史本地日志可能不可访问。

### 8.4 server 和 worker 哪些配置必须一致？

必须保持一致或指向同一资源：

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `STUDIO_INTERNAL_API_TOKEN`
- `STUDIO_RUN_LOG_STORAGE_TYPE`
- `STUDIO_RUN_LOG_OBJECT_*`
- `STUDIO_AGGREGATION_HOME` 对应的插件能力版本

### 8.5 哪些配置不应该提交明文？

以下配置应通过环境变量、K8s Secret 或配置中心安全管理：

- `SPRING_DATASOURCE_PASSWORD`
- `REDIS_PASSWORD`
- `NACOS_PASSWORD`
- `STUDIO_INTERNAL_API_TOKEN`
- `STUDIO_GATEWAY_SHARED_SECRET`
- `STUDIO_RUN_LOG_OBJECT_ACCESS_KEY`
- `STUDIO_RUN_LOG_OBJECT_SECRET_KEY`
