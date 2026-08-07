# Studio 完整部署指南

更新时间：2026-08-02

本文是当前 Studio 在线版的可执行部署基线。按本文完成 MySQL、Nacos、对象存储、Server、Worker、可选 `studio-flink`、Flink 集群和 Web 前端部署后，系统可以从登录、项目管理、数据源探测、模型、SQL/脚本、采集、工作流、质量、数据服务、数据接入到 Flink SQL 完整运行。

## 1. 目标拓扑

```text
浏览器/开放调用方
        |
        v
Nginx(/dfs/data-aggregation-studio/) -> studio-server:18080
                                             |       |
                                             |       +-> MySQL 元数据库
                                             |       +-> Redis（共享缓存，可选但多副本推荐）
                                             |       +-> studio-flink:18084（仅智能问数）
                                             v
                                      Worker HTTP/SLB:18081
                                             |
                                             +-> 业务数据源、脚本运行环境
                                             +-> OSS：运行日志 + aggregation 插件仓库
                                             +-> Flink SQL Gateway:8083（gateway 模式）

Flink JobManager/TaskManager
        |
        +-> Flink lib/studio-flink-*-connector-remote-upload.jar
        +-> Worker 短期 capability 获取固定版本插件（不直连 OSS）
```

职责边界必须保持：

- `studio-server` 是纯控制面，不挂载 `aggregation`，不连接业务数据源，不执行用户任务。
- `studio-worker` 是唯一数据执行面，必须加入一个数据库中登记的运行集群。
- `studio-flink` 只负责智能问数的计划生成；真实 Flink SQL 仍由目标 Worker 通过 Gateway/TaskManager 执行。
- 独立 DataAggregation、Server 和 `studio-flink` 不注册 OSS 插件 resolver；只有 Worker 按需加载插件。

## 2. 版本与网络前置条件

### 2.1 软件

- JDK 17（编译和运行必须使用同一主版本；当前 Maven 构建目标为 Java 17）。
- Maven 3.9 或项目已验证的兼容版本。
- MySQL 8.0，字符集使用 `utf8mb4`。
- Nacos 与应用网络互通；生产固定 namespace、group 和 profile。
- Redis 仅在启用共享缓存或多 Worker 副本时必需。
- Node.js 20 LTS+、npm 10+ 用于构建 Web 前端；生产只部署构建后的静态文件。
- 若使用 Flink SQL：Flink 2.3.0、Flink SQL Gateway，以及可写的 Flink `lib` 和 TaskManager remote cache。

### 2.2 网络放行

- Server -> MySQL、Nacos、Redis、OSS、Worker HTTP/SLB、可选 `studio-flink`。
- Worker -> MySQL、Nacos、Redis、OSS、业务数据源、脚本依赖对象存储、Flink SQL Gateway。
- TaskManager -> Worker 的内部 artifact endpoint；TaskManager 不需要 OSS 凭证。
- 浏览器只访问 Nginx/Server；Worker 内部端口不得暴露到用户网络。
- 跨主机注册发现时，Nacos 注册 IP 必须是调用方可达的业务网卡地址，不能使用容器回环地址。

## 3. 构建交付物

### 3.1 构建 DataAggregation 依赖

```bash
cd DataAggregation
mvn -DskipTests install
```

这一步至少安装 `commons`、`core`、`data-source-handler-abstract` 和 `plugins-loader-center`，供 Studio Worker 编译和打包。

### 3.2 构建 Studio 后端

```bash
cd DataAggregation/data-aggregation-studio/backend
mvn -pl studio-server,studio-worker,studio-flink -am -DskipTests package
```

生产交付使用对应的 `*-exec.jar`：

```text
studio-server/target/studio-server-*-exec.jar
studio-worker/target/studio-worker-*-exec.jar
studio-flink/target/studio-flink-*-exec.jar
```

发布前至少执行：

```bash
mvn -pl studio-worker -am test
mvn -pl studio-flink -am test
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/test-check-studio-deployment.ps1
```

### 3.3 构建 Web 前端

```bash
cd DataAggregation/data-aggregation-studio/frontend
npm ci
npm run build:web
```

部署 `frontend/apps/web/dist` 到 Nginx 的 `/dfs/data-aggregation-studio/` 路径。Vite 已固定 `base` 为 `/dfs/data-aggregation-studio/`，不要把静态文件部署到根路径后再改写 base。

Nginx 至少需要：

```text
/dfs/data-aggregation-studio/       -> frontend/apps/web/dist
/dfs/data-aggregation-studio/api/   -> studio-server:18080/api/
/dfs/data-aggregation-studio/openapi/、/v3/、/swagger-ui/ -> studio-server:18080 对应路径
```

### 3.4 同源系统无感换票

Studio 与宿主系统经同一 Nginx Origin 暴露时，宿主不需要自行调用换票接口或注入 Studio Header。宿主只需在缺少 Studio 会话或收到 Studio `401` 后跳转：

```text
/dfs/data-aggregation-studio/auth/handoff?returnPath=%2Fother-system%2Foriginal-page
```

`handoff` 路由会使用平台 `access-token` 登录态调用 `POST /api/v1/auth/gateway/exchange`。换票成功后，Server 返回：

```http
Set-Cookie: studio-token=<studio-jwt>; Path=/; HttpOnly; SameSite=Lax
```

随后页面通过 `location.replace` 返回同源 `returnPath`。浏览器访问 `/dfs/data-aggregation-studio/api/**` 时自动携带 `studio-token` Cookie，宿主前端不需要 Axios/Fetch token 拦截器。`returnPath`、兼容参数 `redirect` 和历史拼写 `redict` 都只接受解析后仍属于当前 Origin 的相对路径；不得传入完整外部 URL、`//host/path` 或 token 查询参数。

Studio 认证凭证解析顺序固定为：

```text
X-Studio-Token -> studio-token Cookie -> Authorization: Bearer <legacy-studio-jwt>
```

找到高优先级凭证后即以该凭证完成校验；凭证无效时返回 `401`，不再降级尝试低优先级凭证。显式 `X-Studio-Token` 保留给 CLI、自动化和无 Cookie 客户端；原 `Authorization` 入口继续兼容现有 Studio Web/Desktop 客户端。

Server 相关环境变量：

```text
STUDIO_AUTH_TOKEN_EXPIRATION_SECONDS=43200
STUDIO_AUTH_COOKIE_PATH=/
STUDIO_AUTH_COOKIE_SAME_SITE=Lax
STUDIO_AUTH_COOKIE_SECURE=true
STUDIO_AUTH_COOKIE_CSRF_ENABLED=false
```

生产 HTTPS 必须设置 `STUDIO_AUTH_COOKIE_SECURE=true`。Server 也会在 `X-Forwarded-Proto=https` 时自动写入 `Secure`，因此 Nginx 必须透传真实协议和 Host，且不能丢弃 `Set-Cookie`：

```nginx
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host $http_host;
proxy_set_header Host $http_host;
```

Cookie 认证会由浏览器自动携带。Cookie 写请求保护由 `STUDIO_AUTH_COOKIE_CSRF_ENABLED` 控制，默认关闭；设置为 `true` 后，对使用 `studio-token` Cookie 的 `POST/PUT/PATCH/DELETE` 增加同源 `Origin` 和 Fetch Metadata 校验，跨站写请求返回 `403`。退出必须调用 `POST /api/v1/auth/logout`，由 Server 使用相同 Path 清除 HttpOnly Cookie。

## 4. MySQL 初始化与升级

### 4.1 全新数据库

创建独立数据库和最小账号后，在空库按以下顺序执行，不能跳过运行参数种子：

```text
studio-server/src/main/resources/schema-mysql.sql
studio-server/src/main/resources/data-mysql-base.sql
studio-server/src/main/resources/data-mysql-builtin.sql
studio-server/src/main/resources/data-mysql-runtime-options.sql
```

等价命令示例（将账号、主机和库名替换为实际值）：

```bash
mysql -h <mysql-host> -P 3306 -u <db-user> -p <database> < studio-server/src/main/resources/schema-mysql.sql
mysql -h <mysql-host> -P 3306 -u <db-user> -p <database> < studio-server/src/main/resources/data-mysql-base.sql
mysql -h <mysql-host> -P 3306 -u <db-user> -p <database> < studio-server/src/main/resources/data-mysql-builtin.sql
mysql -h <mysql-host> -P 3306 -u <db-user> -p <database> < studio-server/src/main/resources/data-mysql-runtime-options.sql
```

文件作用：

- `schema-mysql.sql`：表、索引和基础结构。
- `data-mysql-base.sql`：管理员、租户、默认项目、角色和权限。
- `data-mysql-builtin.sql`：技术元模型、内置映射和质量规则。
- `data-mysql-runtime-options.sql`：Reader/Writer 等插件运行参数元模型；没有它，运行参数页面会缺少插件字段定义。

应用默认关闭 `spring.sql.init`，只启动服务不会自动建表或灌种子。

首次登录使用 `admin` 初始账号后应立即修改密码；不要把默认口令作为生产凭据保留。

### 4.2 已有数据库升级

1. 先做 MySQL 全量备份，停止调度和写入，记录当前 Server/Worker 版本及活动任务。
2. 在维护窗口执行 `backend/scripts/upgrade-studio-schema.ps1`，或由 DBA 按版本顺序执行增量 SQL。当前运行集群和短期 Dispatch 相关增量至少按 `20260720`、`20260722`、`20260723` 顺序完成。
3. 告警中心未升级的库还需按 `20260713`、`20260716`、`20260717`、`20260718` 顺序处理。
4. 升级后核验 `studio_runtime_cluster`、`studio_runtime_endpoint`、`studio_project_runtime_cluster`、`datasource_cluster_binding`、`studio_runtime_validation`、`studio_runtime_idempotency` 及 `dispatch_task.protected_payload_ciphertext`。
5. 对历史空 `runtime_cluster_id/target_cluster_id` 先执行 `backfill-runtime-cluster.ps1 -DryRun`，确认无歧义后再 Apply；`QUEUED/RUNNING` 且目标为空的 Dispatch 数必须为 0，才能切换到只领取显式集群目标的新 Worker。

升级脚本和回填工具都必须使用目标数据库环境变量，不能依赖本机 `application.yml` 默认连接。

## 5. Nacos 初始化

### 5.1 固定约定

每个应用使用相同的：

```text
profile: prod
namespace: <生产 namespace>
group: <生产 group>
```

推荐 Data ID：

```text
studio-server-prod.yaml
studio-worker-prod.yaml
studio-flink-prod.yaml
```

进程启动环境只负责 Nacos 引导：

```text
APP_ACTIVE=prod
NACOS_SERVER=<nacos-host>:8848
NACOS_NAMESPACE=<namespace>
NACOS_GROUP=<group>
NACOS_USER=<nacos-user>
NACOS_PASSWORD=<nacos-password>
```

Nacos 配置正文和 Secret 注入策略由运维平台管理；密钥、Token、OSS AK/SK 不提交仓库、不打印日志。应用在启动时从 Nacos 导入同名 profile 配置。

当前本地/验收基线为 `NACOS_SERVER=127.0.0.1:8848`、namespace `ZCYY`、group `ZCYY_GROUP`、profile `prod`；生产可以替换为其它值，但三个应用必须完全一致。

必须注入的进程级变量（无默认值也不应依赖源码默认值）：

| 进程 | 必须项 |
| --- | --- |
| Server | `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`、`NACOS_SERVER`、`NACOS_NAMESPACE`、`NACOS_GROUP`、`NACOS_USER`、`NACOS_PASSWORD`、`STUDIO_ENCRYPTION_SECRET`、`STUDIO_INTERNAL_API_TOKEN`、`STUDIO_GATEWAY_TRUST_ENABLED=false`；同源无感换票再配置 `STUDIO_AUTH_COOKIE_PATH`、`STUDIO_AUTH_COOKIE_SECURE`、`STUDIO_AUTH_COOKIE_SAME_SITE`、`STUDIO_AUTH_COOKIE_CSRF_ENABLED` |
| Worker | Server 全部共享项、`STUDIO_CLUSTER_CODE`、`STUDIO_AGGREGATION_HOME`、`STUDIO_INSTANCE_ID`、`STUDIO_WORKER_API_BASE_URL`、`STUDIO_RUNTIME_VERSION`；Lazy 模式再加 OSS provider/endpoint/AK/SK/bucket、`STUDIO_PLUGIN_RUNTIME_MODE=LAZY_OBJECT_STORAGE`、prefix 和 channel |
| studio-flink | Server 的数据库/Nacos/共享密钥项、`STUDIO_GATEWAY_TRUST_ENABLED=false`；启用问数时再加 LLM provider、模型和 API key |

### 5.2 Server Data ID 最小内容

```yaml
server:
  port: 18080
studio:
  encryption-secret: ${STUDIO_ENCRYPTION_SECRET}
  internal-api-token: ${STUDIO_INTERNAL_API_TOKEN}
  gateway:
    trust-enabled: false
  runtime-endpoint:
    allowed-hosts: worker-svc.internal,worker-slb.internal
  object-storage:
    provider: OSS
    endpoint: <aliyun-oss-endpoint>
    access-key: ${OSS_ACCESS_KEY}
    secret-key: ${OSS_SECRET_KEY}
    bucket: <shared-bucket>
    region: <oss-region>
    create-bucket: false
  run-log:
    storage-type: OBJECT_STORAGE
    object-prefix: studio/run-logs
  flink:
    client:
      service-name: studio-flink
      base-url: http://studio-flink:18084
```

Server 必须同时注入 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`。Server 不得配置 `STUDIO_CLUSTER_CODE`、`STUDIO_AGGREGATION_HOME`、`STUDIO_WORKER_CODE` 或插件目录。

### 5.3 Worker Data ID 最小内容

```yaml
server:
  port: 18081
studio:
  runtime-cluster-code: <registered-cluster-code>
  aggregation-home: /opt/studio/aggregation-lazy
  runtime-version: <worker-runtime-version>
  worker-api-base-url: http://<worker-pod-ip>:18081
  plugin-runtime:
    mode: LAZY_OBJECT_STORAGE
    bucket: <plugin-bucket>
    prefix: aggregation-plugins
    channel: production
    refresh-interval-seconds: 30
    refresh-jitter-seconds: 10
    cold-load-timeout-seconds: 300
    max-artifact-bytes: 536870912
    max-extracted-bytes: 1073741824
    max-entry-count: 5000
    cache-max-bytes: 10737418240
    retained-releases: 2
  object-storage:
    provider: OSS
    endpoint: <aliyun-oss-endpoint>
    access-key: ${OSS_ACCESS_KEY}
    secret-key: ${OSS_SECRET_KEY}
    bucket: <shared-bucket>
    region: <oss-region>
    create-bucket: false
  run-log:
    storage-type: OBJECT_STORAGE
    object-prefix: studio/run-logs
```

所有 Worker 还必须使用与 Server 相同的 `studio.encryption-secret` 和 `studio.internal-api-token`，并显式设置：

```text
STUDIO_CLUSTER_CODE=<数据库中的集群编码>
STUDIO_WORKER_GROUP_CODE=<技术池编码>
STUDIO_INSTANCE_ID=<每副本唯一值>
STUDIO_WORKER_API_BASE_URL=http://<worker-address>:18081
STUDIO_RUNTIME_VERSION=<与 current.json runtimeVersion 相同>
STUDIO_GATEWAY_TRUST_ENABLED=false
```

Worker 的 `aggregation-home` 只需要可写目录。Lazy 模式启动时会创建 `conf/core.json`、`plugin/source`、`plugin/reader`、`plugin/writer`、`plugin/transformer`、`plugin/report`、`.staging`、`.state` 和 `cache`；不携带完整 `aggregation/plugin`。

生产可选配置：Python 任务需要 `STUDIO_PYTHON_EXECUTABLE`；私网 HTTP 脚本制品需要把真实主机加入 `STUDIO_RUNTIME_ENDPOINT_ALLOWED_HOSTS`；本地脚本文件保持 `STUDIO_SCRIPT_ARTIFACT_ALLOW_LOCAL_FILES=false`。

若选择 `EAGER_LOCAL`，必须把完整 `package_all/aggregation`（含 `conf/core.json` 和所有插件分类）挂载到 `STUDIO_AGGREGATION_HOME`；OSS 插件仓库参数不会替代本地目录校验。生产建议显式配置模式，不依赖应用默认值。

### 5.4 studio-flink Data ID（启用智能问数时）

```yaml
server:
  port: 18084
studio:
  encryption-secret: ${STUDIO_ENCRYPTION_SECRET}
  internal-api-token: ${STUDIO_INTERNAL_API_TOKEN}
  gateway:
    trust-enabled: false
  assistant:
    llm:
      enabled: true
      base-url: <llm-base-url>
      api-key: ${LLM_API_KEY}
      model: <model-name>
      timeout-seconds: 60
```

`studio-flink`必须连接同一 Studio 元数据库，且不得设置 `STUDIO_CLUSTER_CODE`、`STUDIO_AGGREGATION_HOME`、`STUDIO_WORKER_CODE` 或 Worker 插件运行目录。关闭该服务只影响智能问数，不影响采集、质量、工作流和普通数据服务。

## 6. OSS 插件仓库初始化

### 6.1 OSS 权限与目录

为 Worker 配置一个预创建的 Aliyun OSS bucket，至少允许：

- `GetObject`、`HeadObject`：Worker 读取 `current.json` 和 `plugin.zip`。
- 发布账号额外允许 `PutObject`；发布账号不应与 Worker 运行账号共用。
- Worker 不需要删除 release；release 永不覆盖、永不复用。

对象键固定为：

```text
<prefix>/<channel>/<type>/<name>/current.json
<prefix>/<channel>/<type>/<name>/releases/<release>/plugin.zip
```

生产通道使用 `production`。例如，`source/mysql8` 的最终对象键是：

```text
aggregation-plugins/production/source/mysql8/current.json
aggregation-plugins/production/source/mysql8/releases/<release>/plugin.zip
```

不要把本地输出目录名 `production` 或 `aggregation-plugins` 再重复拼进 OSS prefix。

`current.json` 必须包含 `schemaVersion`、`type`、`name`、`release`、`artifact`、`sha256`、`size`、`runtimeVersion` 和 `updatedAt`；`artifact` 必须指向同坐标的 `releases/<release>/plugin.zip`。

### 6.2 生成本地仓库

生成必须从包含完整插件的构建机目录执行，不能从 `aggregation_lazy` 的空缓存目录生成：

```bash
cd DataAggregation
bash package_all/build_plugin_repository.sh \\
  --all \\
  --aggregation-home package_all/aggregation \\
  --output-root /tmp/release/aggregation-plugins \\
  --channel production \\
  --release <immutable-release> \\
  --runtime-version <worker-runtime-version>
```

单插件发布：

```bash
bash package_all/build_plugin_repository.sh \\
  --type source --name mysql8 \\
  --aggregation-home package_all/aggregation \\
  --output-root /tmp/release/aggregation-plugins \\
  --channel production \\
  --release <immutable-release> \\
  --runtime-version <worker-runtime-version>
```

构建机需要 `bash`、`jq`、`zip` 和 `sha256sum` 或 `shasum`。脚本会检查插件坐标、`plugin.json/transformer.json`、JAR、ZIP 大小、SHA-256，并生成固定字段的 `current.json`。不能覆盖已有 release。

### 6.3 上传与验收

验收环境可使用仓库内的 Java `PluginRepositoryObjectStorageTool`，因为它复用 Worker 的 `CloudObjectStorageService`、Nacos 生效配置和 CAS 保护；该工具当前绑定本机验收用的 Nacos 目标（`127.0.0.1:8848`、固定 namespace/group 和 Worker Data ID），不能直接当作任意生产环境发布器。生产应使用同一 `CloudObjectStorageService` 语义的受控 Java OSS SDK 发布器或经审计的 OSS 工具；不使用图形界面直接编辑指针。操作顺序：

1. `backup`：读取每个坐标原有 `current.json`，保存到只读备份目录和 state 文件；不存在的 pointer 也记录。
2. `publish`：先以不可变方式上传 `releases/<release>/plugin.zip`，校验大小和 SHA-256，再原子覆盖 `current.json`。
3. `verify`：重新读取远端 ZIP 和 pointer，校验坐标、release、runtimeVersion、大小和摘要。
4. 出现并发 pointer 变化时立即停止，不覆盖其他发布者的版本。

如果部署环境没有该 Java operator，才使用受控 OSS SDK/OSS Browser 完成同样的“先 ZIP、后 pointer”顺序；禁止直接替换正在使用的 release。测试和回滚完成后，仅当 pointer 仍是本次发布 revision 才执行 restore。

### 6.4 Worker 首次启动验证

空目录启动后核对：

```text
GET /actuator/health -> UP
心跳 capabilities.pluginRuntime.mode -> LAZY_OBJECT_STORAGE
active revision map / pluginFingerprint 非空
aggregation-home/conf/core.json 已创建
cache、.state、.staging 可写
```

首次业务动作读取 `current.json`、流式下载 ZIP、校验 SHA-256、解压安全边界并原子发布到：

```text
cache/<type>/<name>/<release>-<sha256>/
```

下载失败、404、超时或摘要错误时保留最后有效版本；没有有效缓存时本次实例按 `cold-load-timeout-seconds` 失败，后台继续退避预热。Worker 重启后从 `.state` 恢复 active revision，不应重新下载已有缓存。

## 7. Flink 集群与 studio-flink 前置条件

### 7.1 Flink 集群

1. 启动 Flink JobManager、TaskManager 和 SQL Gateway（默认 HTTP 8083）。
2. 由项目要求的 JDK 运行 Flink；确认 JobManager、TaskManager 和 SQL Gateway 之间网络正常。
3. 构建轻量 remote connector：

```bash
cd DataAggregation/data-aggregation-studio/backend
mvn -pl studio-flink -Pflink-connector-remote -DskipTests package
```

4. 替换前备份 Flink `lib` 中旧 connector，记录 SHA-256 和大小；把 `studio-flink-*-connector-remote-upload.jar` 放入 Flink `lib`，删除同版本冲突的旧 connector，重启 JobManager/TaskManager/SQL Gateway。
5. 执行 `scripts/tests/test-flink-remote-connector-artifact.ps1`，确认制品不含完整 aggregation runtime、具体 source 插件类或打包插件目录。
6. TaskManager 必须能访问每个目标 Worker 的内部 artifact endpoint；不要在 Flink `flink-conf.yaml`、DDL、日志或 TaskManager 环境变量中配置 OSS AK/SK 或 capability token。

### 7.2 Worker Flink Gateway 配置

Worker 使用外部 SQL Gateway 时：

```text
STUDIO_FLINK_ENABLED=true
STUDIO_FLINK_EXECUTION_MODE=gateway
STUDIO_FLINK_RUNTIME_ENDPOINT=http://<worker-internal-address>:18081
STUDIO_FLINK_GATEWAY_BASE_URL=http://<sql-gateway-address>:8083
STUDIO_FLINK_GATEWAY_CONNECT_TIMEOUT_SECONDS=10
STUDIO_FLINK_GATEWAY_FETCH_TIMEOUT_SECONDS=120
STUDIO_FLINK_QUERY_TIMEOUT_SECONDS=120
```

`STUDIO_FLINK_RUNTIME_ENDPOINT` 是 Gateway/TaskManager 回调 Worker 的地址；`STUDIO_FLINK_GATEWAY_BASE_URL` 是 Worker 主动访问 SQL Gateway 的地址，二者不能互换。Server 不配置这两个 Worker 变量。

### 7.3 studio-flink 规划服务

启用智能问数时启动 `studio-flink`，并在 Server 通过 `STUDIO_FLINK_BASE_URL` 或同 namespace/group 的 Nacos 服务发现找到它。它不应作为运行集群端点，也不替代 Worker 的 Flink Gateway 执行链路。

## 8. 启动进程

以下示例使用构建出的 executable JAR；生产将环境变量交给 systemd、容器 Secret/ConfigMap 或等价的受控启动器，不把秘密写入命令历史。

```bash
# Server：只注入数据库、Nacos、共享密钥和控制面配置
java -Xms256m -Xmx512m -Dfile.encoding=UTF-8 \\
  -jar studio-server/target/studio-server-*-exec.jar

# Worker：额外注入集群编码、可写 aggregation home、OSS 插件配置和 Worker 地址
java -Xms256m -Xmx512m -Dfile.encoding=UTF-8 \\
  -jar studio-worker/target/studio-worker-*-exec.jar

# studio-flink：仅在启用智能问数时启动，不注入 Worker 身份和 aggregation home
java -Xms256m -Xmx512m -Dfile.encoding=UTF-8 \\
  -jar studio-flink/target/studio-flink-*-exec.jar
```

应用代码变更重启不承诺保留进程内任务：先停调度和新任务，等待已有任务结束，再按“Worker -> Server -> studio-flink”对应版本重新部署；普通插件 release 更新不重启 Worker。

## 9. 启动顺序

1. MySQL、Nacos、Redis（如启用）、OSS bucket、Flink 集群/SQL Gateway。
2. 完成数据库初始化或增量升级并做结构核验。
3. 发布并验证 OSS 插件仓库；先上传 ZIP，再上传 `current.json`。
4. 启动 `studio-server`，确认 `/actuator/health` 为 `UP`，Nacos 注册成功。
5. 在 Studio UI 创建或确认运行集群、项目授权、数据源适用集群和 Worker HTTP 端点。
6. 启动 `studio-worker`，确认集群编码、心跳、Lease、Lazy 守门和对象存储配置均通过。
7. 配置并测试 Worker 端点和数据源连接；先做一次模型发现/预览以验证 `source` 插件冷加载。
8. 启动可选 `studio-flink` 并验证 Server -> studio-flink 规划调用。
9. 启动或重启 Flink SQL Gateway 相关组件后，从 Studio UI 执行一次 Flink SQL，验证 TaskManager 从 Worker 获取插件制品。
10. 最后开放 Nginx 和外部流量。

应用代码变更重启时，先停调度、排空任务并停止旧进程；新版 Worker 先于新版 Server 上线。普通插件 release 更新不重启 Worker，后台刷新成功后下一任务实例使用新 identity。

## 10. 首次验收清单

```text
[ ] JDK 17、Maven、Node、MySQL、Nacos、Redis（按需）和 Flink 版本符合要求
[ ] 四个 MySQL 初始化 SQL 已按顺序执行，或增量升级已留存变更记录
[ ] Nacos 三个 Data ID、namespace、group、prod profile 可读取
[ ] Server 健康、注册、登录和数据库访问通过
[ ] Server 未配置 aggregation home、集群编码或 Worker 身份
[ ] Worker 使用正确集群编码，健康、心跳、Lease 和 Worker endpoint 通过
[ ] Lazy Worker 目录为空时能创建 core.json、cache、state 和 staging
[ ] OSS 每个 current.json 的坐标、runtimeVersion、release、size、SHA 与 ZIP 一致
[ ] Worker 首次使用至少一个未缓存插件成功下载并执行
[ ] Worker 重启后缓存/state 恢复，已缓存插件不重复下载
[ ] Flink remote connector 制品门禁通过，Flink lib 不含完整 aggregation
[ ] TaskManager 能访问 Worker artifact endpoint，Flink SQL UI 查询通过
[ ] studio-flink（若启用）只能生成计划，Server 能发现且不携带 Worker 身份
[ ] Nginx 基座路径和 API 反向代理正确，浏览器可登录并完成业务冒烟
[ ] 备份、日志、OSS 生命周期、Secret 轮换和回滚责任人已确认
```

## 11. 更新、回滚与故障策略

- 插件更新：新 release 不可变；先上传 ZIP、校验，再切换 `current.json`。回滚只切换 pointer，不删除旧 release。
- 运行中任务：固定旧 identity；新任务使用 active 新 identity。不能通过删除缓存目录强制切换。
- OSS 暂时不可用：有缓存继续执行，无缓存按冷加载超时失败；恢复后后台自动重试。
- Worker 重启：保留 `cache`、`.state` 和 `.staging`；不要把它们作为临时目录清空。
- 应用版本回滚：先停止调度和新 Worker，保留数据库和插件 release，回退 JAR 后按同一配置重新启动；常规回滚不删新增表/列。
- 密钥轮换：停全部 Studio 进程、备份数据库，使用 `rotate-studio-encryption-key.ps1` Dry Run/Apply；不支持新旧密钥实例混跑。

## 12. 相关仓库文档

- [环境初始化说明](./环境初始化说明.md)
- [Server/Worker 配置说明](./studio-server-worker-configuration.md)
- [多运行集群部署说明](./studio-runtime-cluster-deployment.md)
- [生产运行面验收](./studio-production-runtime-acceptance.md)
- [DataAggregation README](../../../../README.md)
