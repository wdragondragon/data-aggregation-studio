# Studio 生产运行面验收

## 1. 用途与边界

`backend/scripts/test-studio-production-runtime.ps1` 用于生成单节点、单作用域的 P0-MC-02 目标环境证据，`backend/scripts/confirm-studio-production-acceptance.ps1` 用于汇总这些脱敏报告并做最终完整性校验，覆盖：

- OMS 到真实 Worker HTTP/SLB 端点的正向认证标记。
- SLB 传输 Header 与 Studio 内部 Token 的隔离。
- OMS 到 Worker 新增 HTTP 跳转的 p50、p95、p99 和最大延迟基线。
- 响应体和响应 Header 不回显内部 Token、Cookie 或访问令牌。
- OMS Pod 到业务数据源端口不可达，目标 Worker Pod 到同一端口可达。
- 真实对象存储上的跨节点归档、读取、临时对象清理、故障记录和恢复上传。
- 对象存储网络 ACL、容量、生命周期和高可用的人工确认状态。

脚本不会查询或解密运行端点配置。Token、Cookie、Header 值和对象存储凭据必须由目标环境的 Secret 以进程环境变量注入，不能放在命令参数、仓库文件或验收文档中。报告只保存 Header 名、HTTP 状态、脱敏地址哈希和非秘密状态。

该工具不替代业务功能回归，也不能在本地 Mock 上生成生产通过结论。每个 Pod 只能证明自身网络视角；OMS 和 Worker 的网络结果必须分别执行，最终必须由汇总脚本按运行集群和相同目标哈希配对，不能只手工勾选完成。

## 2. 检查作用域

| `-Checks` | 执行位置 | 自动证据 |
| --- | --- | --- |
| `RuntimeEndpoint` | 能访问生产 SLB 的受控发布执行器或 OMS Pod | 真实 Token 请求为带 Worker 标记的 `2xx`；错误 Token 请求为带内部认证错误标记的 `401`；禁止重定向；响应最大读取 16 KB；秘密不回显；认证成功样本的 p95 不超过配置阈值。 |
| `Network` + `Denied` | 最终 OMS Server Pod | 指定业务数据源 TCP 端口不可达。 |
| `Network` + `Allowed` | 目标 Worker Pod | 同一业务数据源 TCP 端口可达。 |
| `ObjectStorage` | 具有仓库源码、Maven、JDK 和生产对象存储测试权限的受控执行器 | 显式执行 `SharedObjectStorageRuntimeIT`，必须为 `1/1`、无失败、错误或跳过；Maven 输出和带唯一验收后缀的 Surefire 报告执行后删除。 |

`ObjectStorage` 使用唯一 `studio-it/run-logs/<uuid>` 前缀并由测试删除对象。仍必须使用独立测试 bucket，不要对生产业务前缀执行验收。测试显式支持 `MINIO` 和阿里云 `OSS`；二者使用同一套跨节点归档、读取、失败恢复和清理断言。

每次调用必须显式传入 `-NodeRole OMS|WORKER|RELEASE_RUNNER`。`Network + Denied` 只允许 `OMS`，`Network + Allowed` 只允许 `WORKER`，对象存储只允许 `WORKER` 或 `RELEASE_RUNNER`。报告固定记录非秘密节点角色；运行端点、Worker 网络和 Worker 对象存储报告同时记录数字 `runtimeClusterId`。

## 3. 环境变量

### 3.1 SLB 与内部认证

| 变量 | 是否必填 | 说明 |
| --- | --- | --- |
| `STUDIO_ACCEPTANCE_RUNTIME_BASE_URL` | 是 | Worker 或 SLB 基础地址；脚本追加 `/internal/runtime/health`。生产要求 HTTPS，不允许 URL 内凭据、query 或 fragment。 |
| `STUDIO_ACCEPTANCE_RUNTIME_CLUSTER_ID` | 按作用域必填 | 运行集群数据库数字 ID，不是集群编码；`RuntimeEndpoint`、Worker 侧 `Network` 和 Worker Pod 内 `ObjectStorage` 必填。 |
| `STUDIO_INTERNAL_API_TOKEN` | 是 | 与 Server/Worker 实际配置相同，通过 Secret 注入。 |
| `STUDIO_ACCEPTANCE_RUNTIME_HEADERS_JSON` | 是 | 真实 SLB 传输 Header JSON 对象。值只从 Secret 注入；报告只保留 Header 名。 |
| `STUDIO_ACCEPTANCE_ALLOW_HTTP` | 否 | 仅隔离本地测试可设为 `true`；生产不得设置。 |
| `STUDIO_ACCEPTANCE_RUNTIME_PERFORMANCE_SAMPLES` | 否 | 认证成功延迟样本数，默认 `20`，允许 `5-200`。生产应保持稳定样本量，并在相同发布执行器和网络位置重复执行。 |
| `STUDIO_ACCEPTANCE_RUNTIME_MAX_P95_MS` | 否 | p95 验收上限，默认 `1000` ms，允许 `1-60000` ms。生产应依据既有基线和 SLO 明确设置，不能为通过验收临时放宽。 |

Header JSON 支持字符串或字符串数组值。禁止 `Host`、连接级 Header、长度 Header和任意 `X-Studio-*` Header，防止验收输入覆盖 Studio 内部协议。

`RuntimeEndpoint` 复用同一个 `HttpClient`，把首次认证成功探测计入样本，并补足配置数量；任一样本不是带认证标记的 `2xx` 即失败。报告只记录样本数、错误数、p50、p95、p99、最大值和 p95 上限，不记录目标地址或 Header 值。最终汇总要求样本数至少为 `5`、错误数为 `0`、百分位顺序合法且 p95 不超过该报告声明的上限。

### 3.2 数据源网络

| 变量 | 是否必填 | 说明 |
| --- | --- | --- |
| `STUDIO_ACCEPTANCE_DATASOURCE_HOST` | 是 | 选取一个目标 Worker 确认可达、OMS 明确禁止访问的代表性业务数据源主机。 |
| `STUDIO_ACCEPTANCE_DATASOURCE_PORT` | 是 | 代表性业务数据源 TCP 端口。 |

报告不会保存主机名，只保存主机 SHA-256 前 12 位与端口。OMS 与 Worker 必须使用完全相同的目标进行一拒一通验证。

### 3.3 对象存储

| 变量 | 是否必填 | 说明 |
| --- | --- | --- |
| `STUDIO_ACCEPTANCE_OBJECT_PROVIDER` | 是 | `MINIO` 或 `OSS`；阿里云环境必须显式填写 `OSS`，不能依赖 MinIO 默认值。 |
| `STUDIO_ACCEPTANCE_OBJECT_ENDPOINT` | 是 | 目标对象存储 HTTP(S) 地址。 |
| `STUDIO_ACCEPTANCE_OBJECT_ACCESS_KEY` | 是 | 独立测试身份 Access Key，通过 Secret 注入。 |
| `STUDIO_ACCEPTANCE_OBJECT_SECRET_KEY` | 是 | 独立测试身份 Secret Key，通过 Secret 注入。 |
| `STUDIO_ACCEPTANCE_OBJECT_BUCKET` | 是 | 独立测试 bucket。 |
| `STUDIO_ACCEPTANCE_OBJECT_REGION` | 否 | 区域标识；OSS 建议填写实际 region，客户端连接仍以 endpoint 为准。 |
| `STUDIO_ACCEPTANCE_OBJECT_CREATE_BUCKET` | 否 | 默认 `false`。生产 OSS 保持 `false`并预建测试 bucket；只有隔离本地 MinIO 可以显式设为 `true`。 |
| `STUDIO_ACCEPTANCE_MAVEN_COMMAND` | 否 | Maven 可执行文件，默认 `mvn`。 |
| `STUDIO_ACCEPTANCE_OBJECT_NETWORK_ACL_STATUS` | 完整验收必填 | 只有网络 ACL 已经由运维核对时设为 `PASS`。 |
| `STUDIO_ACCEPTANCE_OBJECT_NETWORK_ACL_EVIDENCE_ID` | 状态为 PASS 时必填 | 非秘密网络策略变更单或验收记录编号，格式仅允许字母、数字、点、下划线、冒号、斜杠和连字符。 |
| `STUDIO_ACCEPTANCE_OBJECT_CAPACITY_STATUS` | 完整验收必填 | 只有容量和增长预算已核对时设为 `PASS`。 |
| `STUDIO_ACCEPTANCE_OBJECT_CAPACITY_EVIDENCE_ID` | 状态为 PASS 时必填 | 非秘密容量评审记录编号。 |
| `STUDIO_ACCEPTANCE_OBJECT_LIFECYCLE_STATUS` | 完整验收必填 | 只有生命周期、保留期和清理策略已核对时设为 `PASS`。 |
| `STUDIO_ACCEPTANCE_OBJECT_LIFECYCLE_EVIDENCE_ID` | 状态为 PASS 时必填 | 非秘密生命周期策略记录编号。 |
| `STUDIO_ACCEPTANCE_OBJECT_HA_STATUS` | 完整验收必填 | 只有高可用、故障域和恢复方案已核对时设为 `PASS`。 |
| `STUDIO_ACCEPTANCE_OBJECT_HA_EVIDENCE_ID` | 状态为 PASS 时必填 | 非秘密 HA 评审或演练记录编号。 |

人工确认变量只接受 `PASS`。未设置状态时报告为 `PENDING`；状态为 `PASS` 但缺少合法证据编号时报告为 `FAIL`。配合 `-RequireComplete` 均返回非零，避免只做一次上传或只写一个无来源的 `PASS` 就误判生产对象存储全部验收完成。证据编号会写入脱敏报告，因此只能填写变更单、评审或演练编号；完整 URL、URL 参数、账号或秘密均不允许，带 URI scheme 的值会被拒绝且不会写入报告。

当前 OSS 客户端使用静态 Access Key/Secret Key。若生产只允许 RAM Role、STS SecurityToken 或其它无长期密钥认证，现有配置模型尚未覆盖，不能把 Access Key 留空后继续验收。测试身份至少需要检查 bucket 存在以及对随机验收前缀执行 Put/Get/Delete 的权限。

## 4. 执行顺序

以下命令假设环境变量已由 Pod Secret 或受控执行器注入。不要把秘密值写在命令历史中。

### 4.1 OMS Server Pod

```powershell
powershell -ExecutionPolicy Bypass -File backend/scripts/test-studio-production-runtime.ps1 `
  -Checks Network `
  -NetworkExpectation Denied `
  -NodeRole OMS `
  -NodeLabel oms-server
```

### 4.2 目标 Worker Pod

```powershell
powershell -ExecutionPolicy Bypass -File backend/scripts/test-studio-production-runtime.ps1 `
  -Checks Network `
  -NetworkExpectation Allowed `
  -NodeRole WORKER `
  -NodeLabel worker-target-cluster
```

每个具备不同数据源网络范围的运行集群都要执行 Worker 侧检查，不能用一个集群的成功替代其它集群。

### 4.3 SLB 与对象存储

阿里云 OSS 环境先由 Secret 注入 `STUDIO_ACCEPTANCE_OBJECT_PROVIDER=OSS`、OSS endpoint、测试 AK/SK 和预建测试 bucket；`STUDIO_ACCEPTANCE_OBJECT_CREATE_BUCKET` 保持 `false`。

```powershell
& backend/scripts/test-studio-production-runtime.ps1 `
  -Checks @('RuntimeEndpoint', 'ObjectStorage') `
  -NodeRole RELEASE_RUNNER `
  -NodeLabel controlled-release-runner `
  -RequireComplete
```

对象存储执行器必须位于与生产 Server/Worker 等价的网络策略内。若两个角色使用不同网络 ACL，应分别验证访问，并把结果作为人工 ACL 证据，不能只依赖 Maven 执行器。

`RuntimeEndpoint` 部分必须对每个承担同步执行的运行集群分别设置 `STUDIO_ACCEPTANCE_RUNTIME_CLUSTER_ID` 后执行；对象存储协议只需在生产等价执行器执行一次。也可以将 `ObjectStorage` 从上述组合命令拆出单独运行，但最终汇总必须同时找到全部集群的运行端点报告和一份完整对象存储报告。

### 4.4 本地 IDEA 配置复验约定

当前开发机复验优先读取项目 `.idea/workspace.xml` 中 `StudioWorkerApplication` 已配置的 `STUDIO_RUN_LOG_OBJECT_*` 环境变量，并仅在测试进程内映射为 `STUDIO_ACCEPTANCE_OBJECT_*`。不得把 AK/SK复制到命令参数、临时脚本、报告或文档。复验固定使用项目 SDK Java 17；PowerShell 系统默认 Java 8 不符合 Studio 构建要求。阿里云 OSS 测试必须显式覆盖 `provider=OSS`、`region=cn-guangzhou` 和 `createBucket=false`。

本地 IDEA 配置复验只证明真实 OSS SDK 协议、权限和随机前缀清理可用。开发机当前使用公网 endpoint，因此该结果不能填写生产 Pod 网络 ACL、容量、生命周期或高可用状态；这些项目仍须在目标环境独立确认。

2026-07-23 使用同一 IDEA Worker 配置和 Java 17 对本地真实 Worker 执行 `RuntimeEndpoint`：20 个样本、错误 `0`，p50 `50.135 ms`、p95 `57.589 ms`、p99/max `265.623 ms`，低于默认 p95 上限 `1000 ms`。该结果只证明工具能在真实进程上采样且本地链路无明显回归；本地 HTTP 直连不等同于生产 HTTPS SLB，不能替代每个生产集群的最终报告。

### 4.5 本地应用级回滚冒烟

`backend/scripts/test-studio-local-rollback.ps1` 用于发布前的本地兼容冒烟。它在隔离 MySQL 8.0.21中导入当前 Schema，写入运行集群和 Dispatch定向哨兵，再构建并启动指定的上一版本 Server，验证旧插件挂载、当前增量 Schema、健康状态和定向数据不变，最后删除容器和临时 worktree。

执行前需要本机 Docker、Maven、项目 JDK 17和上一版本使用的 `DataAggregation/package_all/aggregation`插件目录。应显式传入实际上一发布标签或提交；不传时默认使用当前 `HEAD`，只适合当前改动尚未提交的开发工作区。

```powershell
powershell -ExecutionPolicy Bypass -File backend/scripts/test-studio-local-rollback.ps1 `
  -PreviousRevision '<上一发布标签或提交>'
```

结果 JSON 必须为 `studio.local-rollback-drill.v1 / PASS`，并同时满足上一版本健康为 `UP`、当前 Schema兼容、旧执行依赖存在、集群/Dispatch指纹不变和临时资源清理完成。本地演练不读取 OSS凭据，也不生成生产报告。

该冒烟不能替代生产等价预发或发布窗口中的真实镜像、Kubernetes卷挂载、Worker内部接口兼容和运维步骤演练。生产证据仍使用第 6.2 节“应用级回滚演练”条目归档变更单或演练编号。

## 5. 报告与退出状态

默认报告目录为 `backend/target/studio-production-acceptance`，也可用 `-ResultDirectory` 指向受保护的发布证据目录。每次执行生成 `studio.production-runtime.acceptance.v2` JSON 和同名 Markdown，固定包含节点角色、适用时的运行集群 ID及下列状态：

- `PASS`：本次请求的自动检查通过，且没有待确认项。
- `INCOMPLETE`：自动检查通过，但对象存储人工证据仍为 `PENDING`。
- `FAIL`：网络预期、SLB 协议、认证标记、HTTP 跳转 p95、秘密扫描或对象存储 IT 任一失败。

`FAIL` 始终返回非零；`INCOMPLETE` 在使用 `-RequireComplete` 时返回非零。错误报告不保存响应体、异常消息、生产地址或秘密值，只提供失败阶段和异常类型。实际报告存入受控发布证据系统，不提交到 Git。

## 6. 最终验收汇总

### 6.1 自动汇总校验

收集所有节点的 JSON 报告后执行：

```powershell
$reports = Get-ChildItem -LiteralPath '<受控报告目录>' -Filter '*.json' |
  Select-Object -ExpandProperty FullName
& backend/scripts/confirm-studio-production-acceptance.ps1 `
  -ReportPaths $reports `
  -RuntimeClusterIds @('46', '50') `
  -ExpectedObjectProvider OSS `
  -MaxReportAgeHours 72 `
  -RollbackStatus PASS `
  -RollbackEvidenceId '<回滚演练或变更单编号>' `
  -RollbackCompletedAt '<ISO-8601 UTC 完成时间>'
```

`RuntimeClusterIds` 填生产启用且承担同步执行的运行集群数据库 ID。汇总器只接受新版本、完整、未声明保存秘密且未超过时效的 `PASS` 报告，并验证：

- 每个集群都有 HTTPS SLB 正向/错误 Token、真实传输 Header和秘密扫描证据。
- 每个集群都有至少 `5` 个无错误延迟样本，p50/p95/p99/max 顺序合法，且 p95 不超过该环境声明的验收阈值。
- 每个集群的 Worker `Allowed` 报告都能找到目标哈希完全相同的 OMS `Denied` 报告。
- 对象存储协议为 `1/1`，生产不创建 bucket，OSS endpoint 为 HTTPS。
- 网络 ACL、容量、生命周期和高可用四项均有合法证据编号。
- 生产等价预发或发布窗口应用级回滚演练显式为 `PASS`，证据编号合法，且完成时间没有超过 `MaxReportAgeHours`。
- 来源报告必须严格匹配固定检查项和结果 Schema；未知或重复检查、未知或重复结果 ID、缺字段、非脱敏地址、非法 Header 名、对象未清理或 `overallStatus` 与结果不一致时均失败关闭。
- 重复报告、旧 Schema、过期报告、缺失集群、目标哈希不一致或任一来源非 `PASS` 时汇总失败；失败摘要只写固定校验阶段和安全错误码，不回显来源内容。

汇总结果使用 `studio.production-runtime.acceptance-summary.v2`，只保存来源报告 SHA-256、时间、角色、集群 ID、检查类型，以及格式受限的回滚证据编号和 UTC 完成时间，不保存源文件路径、生产地址或秘密。回滚证据必须指向包含上一版本真实镜像、插件卷挂载、当前 Schema 兼容、Worker 内部接口向前兼容、集群/Dispatch 定向不变和资源清理结果的受控演练或变更记录。缺少、过期或格式非法时汇总直接失败；只有汇总状态为 `PASS`，下表才允许进入人工复核。

汇总器提供结构、完整性和脱敏校验，不提供来源报告的密码学签名。生产报告必须由脚本直接生成并立即进入限制写权限、保留版本或具备审计能力的证据目录，禁止下载后手工编辑再参与汇总；来源真实性由发布流水线或受控证据系统的访问记录证明。

### 6.2 人工复核模板

| 证据 | 集群/节点 | 报告或变更单 | 状态 | 复核人 |
| --- | --- | --- | --- | --- |
| 真实 SLB Header 与内部认证隔离 | 每个同步运行集群 | 受控 JSON 报告编号 | `PASS/FAIL` | 待填写 |
| Worker HTTP 跳转延迟基线 | 每个同步运行集群 | 同一 `RuntimeEndpoint` 报告编号 | `PASS/FAIL` | 待填写 |
| OMS 到代表性业务数据源端口 | 每个 OMS 网络策略 | `Network + Denied` 报告编号 | `PASS/FAIL` | 待填写 |
| Worker 到适用数据源端口 | 每个数据源网络范围 | `Network + Allowed` 报告编号 | `PASS/FAIL` | 待填写 |
| 对象存储跨节点协议 | 生产等价执行器 | `ObjectStorage` 报告编号 | `PASS/FAIL` | 待填写 |
| 对象存储网络 ACL | Server 与 Worker | 网络策略变更单 | `PASS/FAIL` | 待填写 |
| 对象存储容量与增长预算 | 租户/环境 | 容量评审记录 | `PASS/FAIL` | 待填写 |
| 对象存储生命周期 | bucket | 生命周期策略记录 | `PASS/FAIL` | 待填写 |
| 对象存储高可用与恢复 | 存储服务 | HA/演练记录 | `PASS/FAIL` | 待填写 |
| 存量密钥轮换 | 仅确需换密钥的环境 | 维护窗口变更单 | `PASS/不适用` | 待填写 |
| 应用级回滚演练 | 生产等价预发或发布窗口 | 回滚演练/变更单编号 | `PASS/FAIL` | 待填写 |

只有自动汇总为 `PASS`、目标范围内所有人工条目通过或明确不适用，并完成业务回归后，P0-MC-02 才能结束。脚本自身的离线 Mock 测试只能证明验收工具行为，不能填写本表的生产状态。

## 7. 工具离线验证

```powershell
powershell -ExecutionPolicy Bypass -File backend/scripts/tests/test-check-studio-deployment.ps1
powershell -ExecutionPolicy Bypass -File backend/scripts/tests/test-studio-production-runtime.ps1
powershell -ExecutionPolicy Bypass -File backend/scripts/tests/test-confirm-studio-production-acceptance.ps1
powershell -ExecutionPolicy Bypass -File backend/scripts/tests/test-studio-local-rollback.ps1
```

2026-07-23 最终复验中相关 PowerShell文件语法为 `9/9`，部署预检为 `7/7`，汇总器为 `15/15`，本地回滚占用端口失败关闭为 `1/1`。2026-07-24 收紧证据编号后，单报告离线测试提升为 `7/7`，汇总器保持 `15/15`；新增覆盖证明不带查询参数的 URL 也会失败关闭，且非法值不会进入脱敏报告。

单报告离线测试覆盖：带正向标记的 Worker 响应、错误 Token 的独立认证标记、20 样本延迟字段、响应回显秘密时失败且报告不泄露、Worker 网络可达、OMS 网络不可达、人工 `PASS` 缺少证据编号或填写 URL 时失败，以及 `OSS` 参数向 Maven IT 的传递和凭据不落报告。汇总器覆盖完整双集群证据通过，以及缺少集群、OMS/Worker 目标哈希不一致、过期报告、重复来源、重复结果 ID、总体 PASS内嵌失败结果、HTTP 跳转 p95 超阈值、缺少协议字段、未知检查项、原始 endpoint、对象未清理和非法回滚证据 URL 的失败关闭；未知检查文本、原始 endpoint和非法证据值不会进入汇总。离线对象存储项使用假 Maven 报告，只验证工具编排；真实 OSS 协议仍由 `SharedObjectStorageRuntimeIT` 在目标阿里云环境显式执行负责。
