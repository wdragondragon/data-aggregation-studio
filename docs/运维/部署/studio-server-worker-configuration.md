# Studio Server / Worker 配置说明

更新时间：2026-07-24

本文按 P0-MC-02 的统一运行模型说明部署配置：`studio-server` 是纯控制面，`studio-worker` 是唯一执行面。单集群与多集群使用相同应用和配置语义，差别只有运行集群记录、Worker 数量和数据源适用范围。

## 1. 进程职责

| 模块 | 默认端口 | 职责 | 不应承担 |
| --- | ---: | --- | --- |
| `studio-server` | `18080` | 用户 API、配置管理、调度入队、运行集群管理、Worker 路由、统计、告警 | 插件加载、业务数据源连接、SQL/脚本/任务执行 |
| `studio-worker` | `18081` | 定向 Dispatch、数据源探测、模型数据访问、SQL/Flink/Java/Python、采集、质量和同步服务执行 | 用户控制面和跨集群自动故障转移 |
| `studio-flink` | `18084` | 智能问数的 SQL 计划生成；启用该功能时由 OMS 侧部署 | 代替 Worker 执行真实 Flink SQL，或作为运行集群路由目标 |

部署不再配置 `LEGACY/ENFORCED` 模式：

- 所有资源和运行操作都必须有真实 `runtimeClusterId`。
- `DEFAULT-LOCAL` 只是普通 Worker 集群编码，不表示 Server 进程内执行。
- Server 不配置 `STUDIO_CLUSTER_CODE`，也不挂载 DataAggregation 插件目录。
- Worker 离线时，同步请求返回 `503`，异步任务保持排队；禁止回退到 Server 或其他集群。

## 2. 共享配置

Server 与全部 Worker 必须连接同一 Studio 元数据库，并使用相同的加密密钥和内部调用 Token。

| 环境变量 | 必填 | 说明 |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | 是 | Studio 元数据库 JDBC URL。 |
| `SPRING_DATASOURCE_USERNAME` | 是 | 元数据库账号。 |
| `SPRING_DATASOURCE_PASSWORD` | 是 | 元数据库密码。 |
| `STUDIO_ENCRYPTION_SECRET` | 是 | 加解密数据源、运行端点 URL/Header/Token 和短期 Dispatch 执行输入；所有进程必须一致。 |
| `STUDIO_INTERNAL_API_TOKEN` | 是 | Server 调用 Worker 内部接口的共享认证 Token；所有进程必须一致。 |
| `NACOS_SERVER` | 按需 | Nacos 地址。运行集群路由本身以数据库中的 HTTP/SLB 端点为准。 |
| `NACOS_NAMESPACE` | 按需 | Nacos namespace。 |
| `NACOS_GROUP` | 按需 | Nacos group。 |
| `REDIS_HOST/PORT/PASSWORD` | 按需 | 启用共享缓存时使用；多集群不能使用实例本地响应缓存代替共享 Redis。 |

生产必须使用非默认、足够长且不可提交到仓库的 `STUDIO_ENCRYPTION_SECRET` 和 `STUDIO_INTERNAL_API_TOKEN`。该加密密钥也保护排队中的即席脚本正文和运行参数。修改加密密钥前，必须按[运行集群升级说明](../../数据库/runtime-cluster-upgrade.md#加密密钥迁移边界)停止全部 Studio 进程、备份数据库，并使用 `backend/scripts/rotate-studio-encryption-key.ps1` 先 Dry Run、再双确认 Apply。不能直接替换环境变量后重启，也不支持新旧密钥实例滚动混跑。

### 2.1 可信网关配置

`STUDIO_GATEWAY_*` 用于上游可信网关把已经认证的身份兑换为 Studio JWT，与 Server 调用 Worker 的 `STUDIO_INTERNAL_API_TOKEN` 不是同一条认证链。

| 环境变量 | 生产要求 | 说明 |
| --- | --- | --- |
| `STUDIO_GATEWAY_TRUST_ENABLED` | 必须显式配置 | 未接入可信网关时设为 `false`；只有确认上游签名方和网络边界后才设为 `true`。 |
| `STUDIO_GATEWAY_SHARED_SECRET` | 启用可信网关时必填 | 上游网关和 `studio-server` 使用同一强随机密钥，禁止使用应用默认值 `change-me`。 |
| `STUDIO_GATEWAY_SIGNATURE_EXPIRE_SECONDS` | 建议保持较短窗口 | 签名有效期，默认 `300` 秒；需要配合网关和 Server 的时钟同步。 |

Worker 和独立 `studio-flink` 不承担用户身份兑换，生产应显式设置 `STUDIO_GATEWAY_TRUST_ENABLED=false`。可信网关密钥不得与内部 API Token、数据源密码或端点 Token 复用，也不得写入镜像、日志、工单或本文档。

### 2.2 本地 IDEA 配置启动

仓库 `scripts/dev-services.ps1` 在本地存在根项目 `.idea/workspace.xml` 时，会分别读取 `StudioServerApplication` 和 `StudioWorkerApplication` 的环境变量。IDEA 中的 Redis、Nacos、Python和对象存储配置只通过子进程环境继承，不拼入 PowerShell 或 Java 命令行，也不打印变量值。脚本仍会覆盖本地必需的共享内部 Token/加密密钥并强制关闭可信网关；Server 子进程会清除集群编码、插件目录和其它 Worker 身份变量，Worker 则补齐显式集群编码与插件目录。

该行为只服务本机开发和验收，不表示生产部署读取 `.idea`。生产仍必须通过 Secret、ConfigMap或受控启动环境显式注入本文件列出的变量。

## 3. Server 配置

### 3.1 必要配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `18080` | 控制面 HTTP 端口。 |
| `STUDIO_INSTANCE_ID` | Pod UID/主机名 | Server 实例标识，用于集群锁和观测。 |
| `STUDIO_RUNTIME_INVOCATION_MAX_BODY_BYTES` | `10485760` | Server 接收并代理到 Worker 的最大请求体。 |
| `STUDIO_RUNTIME_ENDPOINT_MAX_RESPONSE_BYTES` | `10485760` | Server 从 Worker 接收的响应体上限。 |
| `STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS` | 本地配置默认允许 `127.0.0.1` | Server 显式放行可使用 HTTP 或解析到内网地址的 Worker/SLB 主机；Worker 下载历史 HTTP 脚本制品时复用同一精确主机允许列表。 |
| `STUDIO_SCHEDULER_BATCH_SIZE` | `500` | 每轮调度扫描最大入队数量。 |
| `STUDIO_CLUSTER_LOCK_LEASE_SECONDS` | `120` | 控制面集群锁租约秒数。 |

`STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS` 是 Spring Boot 字符串列表，环境变量使用逗号分隔：

```bash
STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS=studio-worker.default.svc,worker-slb.internal.example,127.0.0.1
```

主机名必须精确填写，不支持 `*` 通配符。云元数据地址始终拒绝。允许列表只放行地址安全策略，不代替内部 Token、SLB Header 或网络 ACL。仅在 Worker 仍需读取私网 HTTP/HTTPS 脚本环境制品时才把相应制品主机加入 Worker 配置；新部署优先使用受管 `oss://bucket/object-key` 地址。

`STUDIO_RUNTIME_ENDPOINT_MAX_RESPONSE_BYTES` 需要同时配置到 Server 和全部 Worker，并保持一致：Server 用它限制从 Worker 读取的响应体；Worker 用它限制协议转换访问下游 HTTP 服务时读取的响应体。若响应声明的 `Content-Length` 或实际读取字节数超过限制，请求会失败并返回 `Target response exceeds the configured limit`，不会继续把超限正文读入内存。

`STUDIO_RUNTIME_INVOCATION_MAX_BODY_BYTES` 也需要在 Server 和全部 Worker 保持一致。Server 在代理前限制用户请求体，Worker 内部入口再次按声明长度和实际读取字节数独立限制；Server、Worker 和 Desktop 均关闭 Spring 的表单预解析过滤器，JSON、XML/SOAP 和 `application/x-www-form-urlencoded` 会由运行路由在有界读取后处理，分块表单不会在限流前被消费。超限返回 HTTP `413` 和 `PAYLOAD_TOO_LARGE`，不会进入业务执行或写入幂等状态。双端校验用于防止错误代理配置或持有内部 Token 的直连请求绕过 OMS 限制。

### 3.2 Server 禁止配置

以下变量不属于 Server：

```text
STUDIO_AGGREGATION_HOME
STUDIO_CLUSTER_CODE
STUDIO_WORKER_GROUP_CODE
STUDIO_WORKER_CODE
STUDIO_PLUGIN_FINGERPRINT
STUDIO_PYTHON_EXECUTABLE
STUDIO_PYTHON_TEMP_DIR
STUDIO_PYTHON_TIMEOUT_SECONDS
STUDIO_SCRIPT_ARTIFACT_CONNECT_TIMEOUT_SECONDS
STUDIO_SCRIPT_ARTIFACT_READ_TIMEOUT_SECONDS
STUDIO_SCRIPT_ARTIFACT_MAX_BYTES
STUDIO_SCRIPT_ARTIFACT_ALLOW_LOCAL_FILES
STUDIO_SCRIPT_ARTIFACT_ALLOWED_LOCAL_ROOTS
```

Server 镜像和 Pod 不需要挂载：

```text
aggregation/conf/core.json
aggregation/plugin/**
```

Server 也不能通过旧 JVM 参数 `-Daggregation.home=...` 间接携带插件目录。应用启动守门会同时拒绝 `STUDIO_AGGREGATION_HOME` 和 JVM `aggregation.home`；不要用系统属性绕过角色配置边界。

Server 所在网络可以禁止访问业务数据库、消息队列、文件系统和对象源网段；它只需要访问 Studio 元数据库、共享基础设施和 Worker HTTP/SLB 端点。

## 4. Worker 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `18081` | Worker 内部 HTTP 端口。 |
| `STUDIO_CLUSTER_CODE` | 空 | Worker 所属运行集群的稳定编码，必须显式配置并与控制面登记值一致，例如 `DEFAULT-LOCAL`。 |
| `STUDIO_AGGREGATION_HOME` | `../../package_all/aggregation` | DataAggregation 运行目录，必须包含该 Worker 的完整插件。 |
| `STUDIO_WORKER_GROUP_CODE` | 由 `worker-code` 回退 | 集群内部技术池和观测标识，不替代集群编码。 |
| `STUDIO_INSTANCE_ID` | Pod UID/主机名 | Worker 实例标识。多副本必须唯一。 |
| `STUDIO_WORKER_API_BASE_URL` | 当前 Worker 地址 | Worker 上报的诊断地址。生产填写 Pod 可达地址或内部服务地址。 |
| `STUDIO_RUNTIME_VERSION` | 空 | 运行版本观测值。 |
| `STUDIO_PLUGIN_FINGERPRINT` | 空 | 插件集合指纹，建议发布时固定生成。 |
| `STUDIO_WORKER_SCHEDULER_POOL_SIZE` | `4` | 异步 Dispatch 执行线程池大小。 |
| `STUDIO_DISPATCH_LEASE_MINUTES` | `10` | 已领取任务租约。 |
| `STUDIO_WORKER_OFFLINE_GRACE_MINUTES` | `120` | 离线实例保留窗口。 |
| `STUDIO_RUNTIME_INVOCATION_MAX_CONCURRENCY` | `32` | 单个 Worker 同时接受的数据面 HTTP 执行数；超限立即返回 `503`，不会占用异步 Dispatch 执行线程。 |
| `STUDIO_RUNTIME_INVOCATION_MAX_BODY_BYTES` | `10485760` | Worker 内部同步入口独立接受的最大请求体；应与 Server 配置保持一致。 |
| `STUDIO_RUNTIME_ENDPOINT_MAX_RESPONSE_BYTES` | `10485760` | 协议转换 Worker 从下游 HTTP 服务读取的最大响应体；应与 Server 配置保持一致。 |
| `STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS` | 本地配置默认允许 `127.0.0.1` | Worker 下载私网 HTTP/HTTPS 脚本环境制品时精确放行主机；公网 HTTPS 和 `oss://` 不依赖该项。 |
| `STUDIO_RUNTIME_LOG_DIR` | `./runtime/run-logs` | Worker 本地运行日志目录。 |
| `STUDIO_PYTHON_EXECUTABLE` | 空 | Python 脚本执行器路径；使用 Python 能力时必填。 |
| `STUDIO_PYTHON_TIMEOUT_SECONDS` | `120` | Python 执行超时秒数。 |
| `STUDIO_SCRIPT_ARTIFACT_CONNECT_TIMEOUT_SECONDS` | `5` | Worker 下载 HTTP/HTTPS Java 环境依赖的连接超时，范围收敛到 1 至 60 秒。 |
| `STUDIO_SCRIPT_ARTIFACT_READ_TIMEOUT_SECONDS` | `30` | Worker 下载 HTTP/HTTPS Java 环境依赖的读取超时，范围收敛到 1 至 60 秒。 |
| `STUDIO_SCRIPT_ARTIFACT_MAX_BYTES` | `67108864` | 单个 JAR/ZIP、对象存储对象、本地文件及 ZIP 解压后 JAR 总量上限，最大 64 MiB。 |
| `STUDIO_SCRIPT_ARTIFACT_ALLOW_LOCAL_FILES` | `false` | 是否允许脚本环境依赖读取 Worker 本地文件；生产保持关闭。 |
| `STUDIO_SCRIPT_ARTIFACT_ALLOWED_LOCAL_ROOTS` | 空 | 开启本地文件后允许访问的真实根目录列表，逗号分隔；目标会经 `toRealPath()` 校验，不能目录逃逸。 |
| `STUDIO_FLINK_ENABLED` | `true` | 是否注册 Worker 内的 Flink SQL 执行能力。 |
| `STUDIO_FLINK_EXECUTION_MODE` | `embedded` | Worker 的 Flink SQL 执行模式；使用 Gateway 时必须同步配置其连接参数。 |
| `STUDIO_FLINK_RUNTIME_ENDPOINT` | 当前 Worker 地址 | Flink Gateway 运行时回调到当前 Worker 的内部地址，不能填写 Server 地址。 |

同一运行集群可部署多个 Worker，所有实例使用相同 `STUDIO_CLUSTER_CODE`，但 `STUDIO_INSTANCE_ID` 不同。Worker 只领取 `target_cluster_id` 指向自身集群的任务；没有目标集群的历史任务不会被领取。

Worker 内部 HTTP 接口不应暴露给用户网络。Server 到 Worker 的路由端点可以是单 Worker 地址、Kubernetes Service 或 SLB；多实例负载均衡由 Service/SLB 完成，Studio 不在应用内轮询多个端点。

脚本环境依赖推荐先上传到受管对象存储并保存 `oss://<bucket>/<object-key>`。HTTP/HTTPS 下载固定 DNS 解析结果、禁止重定向和自动重试，并限制超时与响应大小；私网主机必须精确加入 Worker 的 `STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS`。本地路径和 `file:` 默认拒绝，只有确有历史兼容需求时才同时开启本地文件并配置允许根目录。

## 5. studio-flink 规划服务

`studio-flink` 只在启用智能问数时需要。它读取 Studio 模型上下文并生成受约束的 SQL 计划，最终 Flink SQL 仍由 `studio-server` 按 `runtimeClusterId` 转发到目标 Worker 执行。停止 `studio-flink` 不影响采集、质量、工作流和普通数据服务，但 `/question/plan`、`/question/ask` 等智能问数入口应明确失败，不得回退到 Server 本地执行。

Server 侧路由配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `STUDIO_FLINK_SERVICE_NAME` | `studio-flink` | 使用 Nacos 服务发现时的服务名。 |
| `STUDIO_FLINK_BASE_URL` | `http://127.0.0.1:18084` | 固定 HTTP 地址；使用 Nacos 发现时应留空，并确认 service name、namespace 和 group 一致。 |
| `STUDIO_FLINK_CONTEXT_PATH` | 空 | 规划服务存在统一 context path 时填写。 |

规划服务至少需要连接同一 Studio 元数据库，使用与 Server 相同的 `STUDIO_ENCRYPTION_SECRET` 和 `STUDIO_INTERNAL_API_TOKEN`，并配置实际使用的 LLM Provider。加密密钥保证规划服务能够读取同一控制面元数据，内部 Token仅保持共享运行配置一致，不能把规划服务变成 Worker内部接口。它不应作为运行集群端点，不配置 `STUDIO_CLUSTER_CODE`、`STUDIO_AGGREGATION_HOME`或其它 Worker身份变量，并且必须显式设置 `STUDIO_GATEWAY_TRUST_ENABLED=false`。若部署 Flink Gateway，Worker 还需按环境配置 `STUDIO_FLINK_GATEWAY_BASE_URL`、连接/获取超时和回调地址；这些地址只属于 Worker 执行面。

## 6. 运行集群模块配置

运行集群、端点和项目授权保存在数据库，不写死在应用环境变量中。

每个可执行集群需要：

1. 一条启用的运行集群记录，编码与 Worker 的 `STUDIO_CLUSTER_CODE` 一致。
2. 至少一个在线 Worker 心跳。
3. 项目对该集群的授权。
4. 数据源对该集群的适用绑定。
5. 需要同步探测或服务调用时，一条启用的 `HTTP` Worker 端点。

旧 `LOCAL` 端点不再具有执行语义，只能在迁移期查看、获得固定测试失败提示、停用和删除。新建或编辑端点只允许 `HTTP`。

端点可保存额外 SLB Header 和 Token，值由 Server 加密保存。运行时会隔离内部认证 Header 与业务 Header，禁止重定向和自动业务重试。

## 7. 单集群部署

单集群就是只登记一个运行集群，例如 `DEFAULT-LOCAL`：

1. 启动 Studio 元数据库和 `studio-server`。
2. 在“运行集群”中创建或保留 `DEFAULT-LOCAL`，为项目授权。
3. 启动一个完整能力 Worker，设置 `STUDIO_CLUSTER_CODE=DEFAULT-LOCAL`，等待实例心跳在线。
4. 配置 Worker HTTP 端点，例如 `http://studio-worker:18081`，并完成端点测试。
5. 将数据源绑定到该集群，并测试连接。
6. 创建资源时保存真实 `runtimeClusterId`。

项目只有一个授权集群时，前端自动选择或只读显示，但请求 DTO 仍提交集群 ID。以后新增第二个集群只需新增记录、Worker、端点、项目授权和数据源绑定，不需要切换应用模式或改变 Server 启动参数。

## 8. 多集群部署

OMS 集群只部署 Server 也可以；需要在 OMS 执行任务时，再额外部署属于 OMS 运行集群的 Worker。其他执行网络通常只部署 Worker，不需要部署第二套 Server。

多集群共同要求：

- 所有 Server/Worker 连接同一 Studio 元数据库。
- 每个 Worker 使用自身集群编码。
- 每个集群部署相同的完整能力版本，不配置任务类型能力矩阵。
- 数据源适用范围反映网络可达性，不由健康检查自动修改。
- 多集群生产使用共享对象存储保存运行日志。
- 启用数据服务缓存时使用共享 Redis。
- 不实现跨集群自动故障转移；目标离线时保持原目标。

## 9. 运行日志

| `STUDIO_RUN_LOG_STORAGE_TYPE` | 说明 |
| --- | --- |
| `LOCAL` | 仅适合本地或单 Worker 开发；历史日志依赖原 Worker 文件。 |
| `OBJECT_STORAGE` | 生产推荐；Worker 上传，Server 按 bucket/key 读取。 |

对象存储核心变量。以下为阿里云 OSS 示例，Server 和全部 Worker 必须使用相同配置：

```bash
STUDIO_RUN_LOG_STORAGE_TYPE=OBJECT_STORAGE
STUDIO_OBJECT_PROVIDER=OSS
STUDIO_OBJECT_ENDPOINT=https://oss-<region>-internal.aliyuncs.com
STUDIO_OBJECT_ACCESS_KEY=<secret-injected-access-key>
STUDIO_OBJECT_SECRET_KEY=<secret-injected-secret-key>
STUDIO_OBJECT_BUCKET=<pre-created-bucket>
STUDIO_OBJECT_REGION=<region>
STUDIO_OBJECT_CREATE_BUCKET=false
STUDIO_RUN_LOG_OBJECT_PREFIX=studio/run-logs
```

Server 与 Worker 必须使用相同的 provider、endpoint、bucket 和 prefix。`OSS` 是规范提供方值；代码也兼容 `ALIYUN/ALIYUN_OSS/ALIYUN-OSS`，但部署清单统一使用 `OSS`。若使用公网 endpoint，将示例中的 internal endpoint 换成实际受控地址；不要把真实地址和凭据提交到仓库。MinIO 部署则将 provider 改为 `MINIO`并配置对应 endpoint。

当前实现使用静态 Access Key/Secret Key，不支持额外的 STS SecurityToken 或 RAM Role 凭据链。生产 OSS bucket 应预先创建，应用保持 `STUDIO_OBJECT_CREATE_BUCKET=false`。

发布前可用显式集成测试验证 Worker 归档、跨节点读取和存储故障恢复。该测试不进入默认 Surefire；测试对象会在用例内删除，但仍应使用独立测试 bucket：

```powershell
$env:STUDIO_IT_OBJECT_PROVIDER='OSS'
$env:STUDIO_IT_OBJECT_ENDPOINT='https://oss-<region>-internal.aliyuncs.com'
$env:STUDIO_IT_OBJECT_ACCESS_KEY='<test-access-key>'
$env:STUDIO_IT_OBJECT_SECRET_KEY='<test-secret-key>'
$env:STUDIO_IT_OBJECT_BUCKET='<pre-created-test-bucket>'
$env:STUDIO_IT_OBJECT_REGION='<region>'
$env:STUDIO_IT_OBJECT_CREATE_BUCKET='false'
mvn -pl studio-test -am "-Dtest=SharedObjectStorageRuntimeIT" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

验收通过必须同时满足：Worker A 归档为 `AVAILABLE`、独立 Server 和 Worker B 可读、不可达端点产生 `FAILED` 且保留本地日志、恢复端点后 `syncExistingLog()` 重新上传为 `AVAILABLE`。用例会删除随机前缀下的测试对象，但不会删除 bucket。不要在命令、日志或验收文档中记录生产对象存储凭据。

## 10. 最小生产示例

### Server

```bash
APP_ACTIVE=prod
SERVER_PORT=18080
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/data_aggregation_studio?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
SPRING_DATASOURCE_USERNAME=<db-user>
SPRING_DATASOURCE_PASSWORD=<db-password>
STUDIO_ENCRYPTION_SECRET=<shared-encryption-secret>
STUDIO_INTERNAL_API_TOKEN=<shared-internal-token>
STUDIO_INSTANCE_ID=${POD_UID}
STUDIO_RUNTIME_INVOCATION_MAX_BODY_BYTES=10485760
STUDIO_RUNTIME_ENDPOINT_MAX_RESPONSE_BYTES=10485760
STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS=studio-worker.default.svc,worker-slb.internal.example
STUDIO_GATEWAY_TRUST_ENABLED=false
```

接入可信网关时，将最后一项改为 `true`，并通过 Secret 注入非默认的 `STUDIO_GATEWAY_SHARED_SECRET`；不要把密钥直接写入部署清单。

### Worker

```bash
APP_ACTIVE=prod
SERVER_PORT=18081
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/data_aggregation_studio?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
SPRING_DATASOURCE_USERNAME=<db-user>
SPRING_DATASOURCE_PASSWORD=<db-password>
STUDIO_ENCRYPTION_SECRET=<shared-encryption-secret>
STUDIO_INTERNAL_API_TOKEN=<shared-internal-token>
STUDIO_GATEWAY_TRUST_ENABLED=false
STUDIO_RUNTIME_INVOCATION_MAX_BODY_BYTES=10485760
STUDIO_RUNTIME_ENDPOINT_MAX_RESPONSE_BYTES=10485760
STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS=artifact-store.internal.example
STUDIO_CLUSTER_CODE=C50
STUDIO_AGGREGATION_HOME=/opt/data-aggregation/aggregation
STUDIO_WORKER_GROUP_CODE=default-pool
STUDIO_INSTANCE_ID=${POD_UID}
STUDIO_WORKER_API_BASE_URL=http://${POD_IP}:18081
STUDIO_RUNTIME_VERSION=<release-version>
STUDIO_PLUGIN_FINGERPRINT=<plugin-fingerprint>
STUDIO_SCRIPT_ARTIFACT_CONNECT_TIMEOUT_SECONDS=5
STUDIO_SCRIPT_ARTIFACT_READ_TIMEOUT_SECONDS=30
STUDIO_SCRIPT_ARTIFACT_MAX_BYTES=67108864
STUDIO_SCRIPT_ARTIFACT_ALLOW_LOCAL_FILES=false
STUDIO_SCRIPT_ARTIFACT_ALLOWED_LOCAL_ROOTS=
```

### studio-flink（启用智能问数时）

```bash
APP_ACTIVE=prod
SERVER_PORT=18084
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/data_aggregation_studio?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
SPRING_DATASOURCE_USERNAME=<db-user>
SPRING_DATASOURCE_PASSWORD=<db-password>
STUDIO_ENCRYPTION_SECRET=<same-as-server-and-worker>
STUDIO_INTERNAL_API_TOKEN=<same-as-server-and-worker>
STUDIO_GATEWAY_TRUST_ENABLED=false
STUDIO_ASSISTANT_LLM_ENABLED=true
STUDIO_ASSISTANT_LLM_BASE_URL=<llm-base-url>
STUDIO_ASSISTANT_LLM_API_KEY=<llm-api-key>
STUDIO_ASSISTANT_LLM_MODEL=<model-name>
```

`studio-server` 还需把 `STUDIO_FLINK_BASE_URL` 指向该服务；若走 Nacos，则留空固定 URL 并配置相同的 Nacos namespace/group。

普通后端和 Worker 构建不会再把 DataAggregation 插件压入 `studio-flink` 主制品；Worker 始终使用 `STUDIO_AGGREGATION_HOME` 中的外部插件目录。只有需要生成上传到 Flink 集群的 Connector 制品时，才显式启用打包 profile：

```bash
mvn -pl studio-flink -Pflink-connector-bundle -DskipTests package
```

该命令会生成 `studio-flink-*-connector.jar` 和 `studio-flink-*-connector-upload.jar`，并要求相关 `data-source-plugins/*/target/*.zip` 已提前构建。日常 `mvn package`、Server 构建和 Worker 构建不读取这些 Reactor 外部 `target` 目录，避免控制面/Worker 制品被内嵌插件放大。

## 11. 插件要求

插件只安装在 Worker。以 ODPS 为例，Worker 的 `STUDIO_AGGREGATION_HOME` 需要包含：

```text
plugin/source/odps
plugin/reader/odpsreader
plugin/writer/odpswriter
```

`source` 插件用于数据源测试、模型发现、预览和 SQL；`reader/writer` 插件用于采集、接入及其他任务执行。Server 不需要这些目录，也不应拥有业务数据源网络权限。

“完整插件”不能只靠目录存在来判断。每个发布版本必须随部署制品交付一份插件 manifest，至少记录：

- Studio/Worker 发布版本和 manifest 生成时间。
- 全部 `source/reader/writer/transformer` 插件的名称、版本和文件 SHA-256。
- Java、Python、Flink 运行依赖及其版本要求。
- 该 manifest 的整体 fingerprint。

`STUDIO_PLUGIN_FINGERPRINT` 应填写发布 manifest 的整体 fingerprint。同一发布版本、同一运行集群以及要求能力等价的不同集群必须使用相同 manifest；运维中心显示的实例 fingerprint 不一致、为空或与发布记录不符时，不得通过“全能力 Worker”生产验收。首版由发布和验收流程人工核对，不能把“应用成功启动”视为插件完整性已经自动校验。

## 12. 已废弃配置

以下配置不再参与正常启动和运行，不应出现在新部署清单或 Nacos 配置中：

```text
STUDIO_RUNTIME_CLUSTER_MODE
STUDIO_RUNTIME_CLUSTER_BACKFILL_ENABLED
STUDIO_RUNTIME_CLUSTER_BACKFILL_DRY_RUN
```

历史空集群数据必须在升级窗口使用一次性迁移工具处理。迁移完成后，应用运行时不会推断默认集群，也不会领取空 `targetClusterId` 的任务。

## 13. 上线检查

在最终容器或 Pod 已注入环境变量、挂载插件卷之后，先执行部署预检。脚本只检查当前进程环境和本地挂载，不读取或输出秘密值：

```powershell
# OMS 控制面
powershell -ExecutionPolicy Bypass -File backend/scripts/check-studio-deployment.ps1 -Role Server

# Worker；多集群生产同时检查共享对象存储变量
powershell -ExecutionPolicy Bypass -File backend/scripts/check-studio-deployment.ps1 -Role Worker -RequireSharedObjectStorage

# 智能问数规划服务；只检查控制面身份和秘密边界，不要求插件目录
powershell -ExecutionPolicy Bypass -File backend/scripts/check-studio-deployment.ps1 -Role Flink
```

预检会拒绝废弃模式变量、默认 Token/密钥、Server或`studio-flink`携带集群代码/插件目录/Worker实例身份变量、Worker缺少集群编码和完整插件目录、`studio-flink`启用可信网关，以及多集群生产缺少或未显式声明 `MINIO/OSS` 的共享对象存储配置。它不能替代数据库升级、SLB 实际请求、对象存储连通性/容量/生命周期/高可用和网络 ACL 验收。目标环境必须继续执行[生产运行面验收](./studio-production-runtime-acceptance.md)：分别在 OMS 与 Worker Pod 验证同一业务数据源端口的一拒一通，并通过真实 SLB Header、内部认证标记和生产对象存储证据。通过 Nacos 注入的值需要在渲染部署配置时等价检查；不要为了让脚本通过而把秘密写入仓库文件。

应用自身也有失败关闭守门：`studio-server`或独立`studio-flink`一旦绑定 Worker集群身份或插件目录会拒绝就绪；`studio-flink`和独立 Worker启用可信网关也会拒绝就绪；独立 Worker和Desktop Worker平面缺少`conf/core.json`、`plugin/source`、`plugin/reader`、`plugin/writer`或`plugin/transformer`时会拒绝就绪。独立 Worker及`studio-flink`的`application.yml`均不再提供隐式插件目录默认值。

```text
[ ] 新版 Server/Worker 启动前已完成 20260720、20260722、20260723 数据库结构升级
[ ] 旧 Worker 已排空并停止；新版 Worker 先于会写短期 Dispatch 密文的新版 Server 上线
[ ] Server 未挂载 aggregation/conf/core.json 和 aggregation/plugin/**
[ ] Server 未配置 STUDIO_CLUSTER_CODE、STUDIO_AGGREGATION_HOME 或 Worker 身份变量
[ ] STUDIO_GATEWAY_TRUST_ENABLED 已显式配置；启用时 shared secret 不是 change-me 且由 Secret 注入
[ ] Server/Worker及已启用的studio-flink使用相同且非默认的 STUDIO_INTERNAL_API_TOKEN 和 STUDIO_ENCRYPTION_SECRET
[ ] Server/Worker 的 STUDIO_RUNTIME_INVOCATION_MAX_BODY_BYTES 已按业务请求规模设置并保持一致
[ ] Server/Worker 的 STUDIO_RUNTIME_ENDPOINT_MAX_RESPONSE_BYTES 已按业务响应规模设置并保持一致
[ ] 若修改过加密密钥，已停机备份并完成离线 Dry Run/Apply；全部进程统一使用新密钥，数据源、运行端点、告警通道、短期 Dispatch 输入和同步服务已逐集群验证
[ ] 启用智能问数时 studio-flink 已部署，Server 的固定地址或 Nacos 服务发现已验证
[ ] 每个 Worker 的版本、插件 manifest 和 STUDIO_PLUGIN_FINGERPRINT 与发布记录一致
[ ] 每个集群已登记、授权、绑定数据源，并且 Worker 心跳与 HTTP/SLB 端点测试正常
[ ] 多集群生产已配置共享对象存储；启用响应缓存时已配置共享 Redis
[ ] 阿里云环境已显式配置 provider=OSS、预建 bucket、create-bucket=false，且认证方式为当前支持的静态 AK/SK
[ ] `test-studio-production-runtime.ps1` 的 SLB、OMS 网络、Worker 网络和对象存储报告已归档，且 `confirm-studio-production-acceptance.ps1` 对全部生产集群的最终汇总为 `PASS`
```
