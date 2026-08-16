# Studio 非结构化文件传输测试用例

> 基线日期：2026-08-09。
>
> 覆盖范围：DataAggregation 传输内核、CLI、文件源插件、Studio Server/Worker、预设任务、工作流、指标、非结构化管理和 Web。

## 1. 测试环境

- Java 17。
- MySQL 生产结构测试与 SQLite 内存结构测试。
- 一个启用的 Runtime Cluster 和一个实际运行的 Worker。
- Local、FTP、SFTP、MinIO 和 OSS 测试数据源。
- FTP 测试源使用数据源管理中已创建的测试连接；OSS 使用现有测试源。测试报告只记录脱敏后的数据源名称和操作结果，不记录连接参数。
- 测试文件包含空文件、中文名、超长名、深目录、小文件集合和至少 10 GiB 大文件。

## 2. 内核与 CLI

| ID | 场景 | 操作 | 预期 |
|---|---|---|---|
| FT-CORE-001 | 配置校验 | `validate` 合法任务 JSON | 输出 JSON Line 成功事件，退出码 `0` |
| FT-CORE-002 | 非法配置 | 缺少端点或策略必填字段 | 不创建目标，退出码 `2` |
| FT-CORE-003 | 文件浏览 | `browse endpoint.json --path / --page-size 200` | 返回目录元数据、游标和截断标记 |
| FT-CORE-004 | 选择计划 | `plan` 执行 glob、正则和排除规则 | 计划只包含符合条件的文件并保持相对路径 |
| FT-CORE-005 | 空文件 | 传输 0 Byte 文件 | 临时写、摘要、提交成功，无无进展循环 |
| FT-CORE-006 | 多文件并行 | 并发度为 4，传输多文件 | 最多四个活动文件，单文件内部顺序读取 |
| FT-CORE-007 | 同摘要跳过 | 目标正式文件 SHA-256 与源一致 | 文件状态 `SKIPPED`，不覆盖目标，不执行错误重试 |
| FT-CORE-008 | 同大小不同内容 | 源目标大小相同、摘要不同 | 不误判完成，按冲突策略处理 |
| FT-CORE-009 | 覆盖提交 | 目标不同内容，策略 `OVERWRITE` | 先写临时目标，摘要通过后原子/幂等提交 |
| FT-CORE-010 | 备份后覆盖 | 策略 `BACKUP_THEN_OVERWRITE` | 旧目标备份后提交新目标，结果可审计 |
| FT-CORE-011 | 断点恢复 | 中断后用 checkpoint `resume` | 校验源快照和临时目标后从有效偏移继续 |
| FT-CORE-012 | 无效断点 | 源大小/修改时间或临时目标变化 | 拒绝旧断点或从安全位置重建，不拼接错误内容 |
| FT-CORE-013 | 提前 EOF | 源流在期望大小前结束 | 明确失败，不进入无进展循环，不提交正式目标 |
| FT-CORE-014 | 取消 | 运行中请求取消 | 状态 `CANCELED`，关闭流并按策略清理临时数据 |
| FT-CORE-015 | 源端删除 | 目标成功后动作 `DELETE` | 只在摘要和正式提交成功后删除源 |
| FT-CORE-016 | 后处理失败 | 目标成功、源端删除/备份失败 | 状态 `POST_ACTION_FAILED`，重试时不重复写目标 |
| FT-CORE-017 | CLI 退出码 | 部分成功、失败、取消 | 分别返回 `3`、`4`、`5` |
| FT-CORE-018 | 历史策略兼容 | 读取缺少分级校验字段的任务和运行快照 | 配置模式和有效模式均为 `STRONG`，行为与升级前一致 |
| FT-CORE-019 | 校验参数边界 | 提交帧数 `0/65`、未按 64 KiB 对齐或超出范围的帧大小、`NONE + DELETE/BACKUP` | 构造阶段拒绝，不创建目标或破坏源文件 |
| FT-CORE-020 | 稳定分层采样 | 同一运行多次生成计划，再更换运行 ID | 同一运行帧位置稳定、每区段一帧且互不重叠；不同运行位置变化 |
| FT-CORE-021 | 部分校验与自动提升 | 传输大文件，再传输 `n * frameSize >= fileSize` 的小文件 | 大文件生成 `PARTIAL-SHA256-V1:<hex>`；小文件有效模式为 `STRONG` |
| FT-CORE-022 | 三档冲突语义 | 对相同正式目标分别执行 `STRONG/PARTIAL/NONE` | 强校验和部分校验一致时 `SKIPPED`；不校验不按内容跳过，直接执行冲突策略 |
| FT-CORE-023 | 分级断点安全 | 分别在三档模式中中断并恢复 | 所有模式验证文件头和确认边界指纹；仅强校验恢复完整 SHA-256 状态；确认偏移不回退 |

## 3. 文件源插件

| ID | 场景 | 预期 |
|---|---|---|
| FT-PLUGIN-001 | Local 根路径与越界 | 规范化分隔符；拒绝逃逸配置根目录 |
| FT-PLUGIN-002 | FTP 流生命周期 | 每次写入后完成 pending command；重试不复用损坏流 |
| FT-PLUGIN-003 | SFTP 空文件和不存在 | 正确区分空文件、目录和不存在，不把异常当普通文件 |
| FT-PLUGIN-004 | MinIO 分页 | 游标稳定，无重复/丢失对象，目录前缀语义一致 |
| FT-PLUGIN-005 | OSS 分页 | marker/continuation 正确，etag 和修改时间可用 |
| FT-PLUGIN-006 | 对象存储临时提交 | multipart/临时 key 完成后才暴露正式 key |
| FT-PLUGIN-007 | 中文和超长路径 | 五类源均保持路径与对象 key，不发生乱码或错误截断 |
| FT-PLUGIN-008 | 连接并发 | FTP/SFTP 会话不跨线程共享，连接数不超过并发设计 |
| FT-PLUGIN-009 | 五类非连续范围读取 | Local、FTP、SFTP、MinIO、OSS 分别读取多个随机 offset/length | 每帧内容准确且后续读取可继续；FTP 每帧关闭后正确结束或中止 pending command |

## 4. Studio 任务、Dispatch 与恢复

| ID | 场景 | 操作 | 预期 |
|---|---|---|---|
| FT-STUDIO-001 | Worker-only 浏览 | 调用公共浏览 API | Server 校验后路由 Worker，Server 无插件访问 |
| FT-STUDIO-002 | 集群授权 | 使用未授权 Runtime Cluster | 请求失败，不产生运行或内部路由 |
| FT-STUDIO-003 | 数据源适用范围 | 数据源未绑定所选集群 | 浏览、保存和触发均失败 |
| FT-STUDIO-004 | 即时传输队列 | 选择一个运行集群、源数据源、目标数据源和文件后发起传输 | 入队后自动开始；队列不显示方向字段、集群名称或 ID，统一按“从：[数据源名称] 源路径”“到：[数据源名称] 目标路径”展示 |
| FT-STUDIO-005 | 任务生命周期 | 创建、更新、校验、预览、发布、下线 | 状态和版本符合定义，预览由源 Worker 真实扫描 |
| FT-STUDIO-006 | 定时触发 | 到达 cron 时间 | 生成一次目标集群固定的 Dispatch 与运行快照 |
| FT-STUDIO-007 | 工作流触发 | `FILE_TRANSFER` 节点引用在线任务 | 复用相同运行链路，写入工作流结果和通用指标 |
| FT-STUDIO-008 | 资源修订保护 | 发布后修改任务，再执行旧 Dispatch | 旧修订不静默执行新配置，返回明确错误 |
| FT-STUDIO-009 | Worker 重启恢复 | Worker 在运行中重启 | 领取中的任务标记中断失败，文件运行可基于有效 checkpoint 恢复 |
| FT-STUDIO-010 | 精确文件重试 | 运行含一个失败文件 | 只重试指定文件项，不重复成功/跳过项 |
| FT-STUDIO-011 | 仅后处理重试 | 文件为 `POST_ACTION_FAILED` | 不重传、不重提交目标，只执行源端动作 |
| FT-STUDIO-012 | 取消竞争 | Worker 提交目标前后并发取消 | CAS 保持运行、Dispatch、文件项和目标可见性一致 |
| FT-STUDIO-013 | 手工运行 Dispatch 身份 | 创建无预设任务的即时运行并自动开始 | `fileTransferRunId` 必填、`fileTransferTaskId` 可为空，Worker 正常进入文件传输执行器 |
| FT-STUDIO-014 | 前置失败终态回写 | 在 Worker 进入文件执行器前制造 Dispatch/身份校验失败 | 通用运行记录和 `file_transfer_run` 均进入 `FAILED`，不会残留 `QUEUED` 队列项 |
| FT-STUDIO-015 | 即时队列逻辑删除 | 删除 `QUEUED`、终态运行及非活动文件项 | 仅即时运行可删除；排队 Dispatch 取消；运行、文件项逻辑删除，指标/审计保留；活动运行要求先取消 |
| FT-STUDIO-016 | 运行名称解析 | 查询运行列表、文件项并接收 SSE 更新 | 数据源返回名称字段；列表不以 ID 作为展示回退；批量列表和 SSE 不产生逐项名称查询 |
| FT-STUDIO-017 | 删除与 Dispatch 领取竞争 | Worker 与用户同时领取/删除同一 `QUEUED` 即时运行 | 删除先原子取消未领取 Dispatch；若 Dispatch 已为 `RUNNING` 则拒绝删除，运行和文件项保持可追踪 |
| FT-STUDIO-018 | 即时单文件目标路径 | 选择单文件并提交完整目标文件路径，再执行传输 | 目标文件恰好写入请求的 `targetPath`，不重复拼接源文件名；目录选择仍保留相对路径 |
| FT-STUDIO-019 | 即时目录零文件保护 | 手工选择只包含空目录、没有叶子文件的目录并执行 | 运行进入 `FAILED`，返回 `FILE_TRANSFER_NO_FILES_DISCOVERED`；不得以零文件 `SUCCESS` 结束 |
| FT-STUDIO-020 | 最终目标校验进度 | 传输一个校验耗时可观察的大文件并进入 `VERIFYING` | SSE 持续返回 `verificationPhase=TARGET_CHECKSUM`、递增的校验字节和校验速度；`transferredBytes` 与 checkpoint 保持文件写入完成值，校验完成后进入 `COMMITTING` |
| FT-STUDIO-021 | 逻辑根目录目标路径 | 将单文件写入 FTP 或其他数据源逻辑根目录 | 传输计划、数据库目标路径、临时路径和插件入参均为 `/file`，不得出现 `//file` |
| FT-STUDIO-022 | 策略规范化与快照 | 从即时运行和预设任务分别保存、发布、触发三档策略 | `policy_json/resolved_spec_json` 保存模式、帧数和帧大小；历史缺失字段补为强校验；不新增表字段 |
| FT-STUDIO-023 | 部分校验实时进度 | 运行有效模式为 `PARTIAL` 的大文件 | SSE 返回 `TARGET_SAMPLE`、配置/有效模式和采样字节；迟到事件不能覆盖 `COMMITTING` 或终态 |
| FT-STUDIO-024 | 分级后处理重试 | 制造 `PARTIAL/STRONG` 的 `POST_ACTION_FAILED` 后重试 | 使用原运行稳定采样计划或完整摘要重新确认；不重传目标；`NONE` 无法创建破坏性后处理 |
| FT-STUDIO-025 | 范围读取能力拒绝 | 为未来不支持 `rangeRead` 的源或目标执行 `PARTIAL` | Worker 返回 `FILE_TRANSFER_PARTIAL_VERIFY_UNSUPPORTED`，不降级、不重试 |

## 5. 单集群安全边界

| ID | 场景 | 操作 | 预期 |
|---|---|---|---|
| FT-CLUSTER-001 | 跨集群请求拒绝 | 提交不同 source/target 集群的手工运行、预设任务、工作流节点 | 返回 `FILE_TRANSFER_CROSS_CLUSTER_DISABLED`，不创建可执行运行 |
| FT-CLUSTER-002 | 历史跨集群记录 | 查询旧记录并尝试恢复、重试、再次执行 | 可查询但所有执行入口拒绝 |
| FT-CLUSTER-003 | Worker-only | 检查 Server Bean、日志、运行调用链 | Server 无文件插件、无 Relay 调用；文件访问只发生在所选集群 Worker |
| FT-CLUSTER-004 | 任意 URL 注入 | 请求中提交自定义 Worker 地址或 Relay 参数 | 字段不被接受，路由只使用受管运行集群端点 |
| FT-CLUSTER-005 | 路径安全 | `../`、NUL、根目录删除、目标冲突、非空目录未确认 | 明确拒绝，不访问越界路径或覆盖目标 |
| FT-ACL-001 | ACL 默认权限 | 普通项目成员浏览/下载新数据源 | `BROWSE`、`DOWNLOAD` 允许，`EDIT`、`DELETE` 拒绝 |
| FT-ACL-002 | ACL 继承与拒绝 | 设置目录 ALLOW、子路径 DENY、用户覆盖 | 最具体路径生效，目录规则递归，DENY 优先 |
| FT-ACL-003 | 管理员和创建者绕过 | 以创建者、管理员执行全部操作 | 全部权限允许，审计记录完整 |
| FT-ACL-004 | 项目成员边界 | 使用非项目成员保存用户 ACL | 请求拒绝，不创建授权记录 |

## 6. 指标与数据库

| ID | 场景 | 预期 |
|---|---|---|
| FT-METRIC-001 | 运行汇总 | 文件成功、跳过、失败、冲突、续传后，运行汇总与文件项一致 |
| FT-METRIC-002 | 趋势采样 | 按采样点写累计字节、速度、完成/失败文件、活动数和重试，不逐字节写库 |
| FT-METRIC-003 | 专用指标 | 任务、运行集群、数据源、状态、时间、TopN 筛选正确 |
| FT-METRIC-004 | 通用指标 | `executionType=FILE_TRANSFER` 能查询 `run_record` 指标 |
| FT-METRIC-005 | 指标时间格式 | 分别提交 `yyyy-MM-dd HH:mm:ss` 和 ISO `yyyy-MM-ddTHH:mm:ss` | 两种请求均可反序列化并进入指标查询，页面默认提交空格分隔格式 |
| FT-METRIC-006 | 数据源 TopN 文件口径 | 构造“大文件少文件”和“小文件多文件”两组运行，并包含摘要一致跳过项 | 来源/目标 TopN 按成功文件数排序，摘要跳过计入成功文件数，`count` 不使用传输字节数 |
| FT-SCHEMA-001 | MySQL 全量结构 | 新库一次建成四张传输表、三张 ACL/审计表、规范集群/创建者字段和全部索引 |
| FT-SCHEMA-002 | MySQL 幂等升级 | 旧库由启动升级服务重复检查无报错、不重复建索引 |
| FT-SCHEMA-003 | SQLite 升级 | 旧桌面库补齐表、列、部分唯一索引和普通索引 |

## 7. 前端与可访问性

| ID | 视口/场景 | 预期 |
|---|---|---|
| FT-WEB-001 | `1440x900` 传输中心 | 左右双栏、方向按钮、队列和长文件名正常，无页面水平溢出 |
| FT-WEB-002 | `390x844` 传输中心 | 左右文件源上下堆叠，四页签可达，无控件遮挡 |
| FT-WEB-003 | 策略弹窗 | 可选择不校验、部分校验、强校验；部分校验可设置 `1..64` 帧和六档帧大小；切换不校验后源动作固定为保留 |
| FT-WEB-004 | 源删除确认 | 选择 `DELETE` 时提交前出现明确确认，取消不入队 |
| FT-WEB-005 | 七步任务编辑器 | 七步均可导航，校验、真实预览、草稿、发布可用 |
| FT-WEB-006 | 运行详情 | 显示长源/目标路径、进度、速度、续传、摘要、错误和精确重试 |
| FT-WEB-007 | 指标页面 | 指标卡、趋势 ECharts 和来源/目标 TopN 非空且响应式 |
| FT-WEB-008 | 从/到展示 | 查看传输队列、运行列表和运行文件明细 | 不显示方向列或集群名称；每个端点按 `[数据源名称] 路径` 展示；运行标题使用 `MM-dd HH:mm · 触发类型` |
| FT-WEB-009 | 刷新恢复 | 刷新或重新登录后从后端恢复最近运行和文件项，不依赖前端内存 |
| FT-WEB-010 | 状态操作 | 暂停、恢复、取消和重试按钮只在合法状态可用 |
| FT-WEB-011 | API 契约 | SDK 只调用 `/api/v1`，分页读取 `PageView.items` |
| FT-WEB-012 | SSE 队列更新 | 打开传输中心并让 Worker 更新运行/文件项 | 仅建立 `/file-transfer/runs/events` 长连接，事件驱动更新列表；无前端定时轮询；断线持续封顶退避重连 |
| FT-WEB-013 | SSE 正常断连 | 建立队列长连接后离开页面并跨过服务端心跳周期 | Server 不写 `HttpMessageNotWritableException` 或断连 `ERROR`，普通 REST I/O 异常仍返回统一 500 |
| FT-WEB-014 | 队列手动删除 | 对排队、终态运行和非活动文件项点击移出队列 | 有确认弹窗；活动运行不能直接删除；运行级移除使用 `/runs/{runId}/queue`，成功后依赖 SSE 移除本地行 |
| FT-WEB-015 | 非结构化管理入口 | 从数据资产菜单进入非结构化管理 | 首次不自动选择集群/数据源/目录；选择集群后加载数据源，选择数据源后加载目录；无权限操作隐藏或禁用 |
| FT-WEB-016 | 队列状态 Tab | 打开传输中心并让文件项进入终态 | 活动文件显示在“传输中”；成功、跳过、失败、冲突、取消和源端处理失败自动进入“已完成”；状态变化只由 SSE 驱动，不增加列表轮询，也不强制切换用户当前 Tab |
| FT-WEB-017 | 最终校验展示 | 分别让强校验和部分校验文件保持在 `VERIFYING` | 队列和运行详情分别显示“目标校验”和“目标采样”进度及校验速度；完成或进入后续状态后不被迟到事件覆盖 |
| FT-WEB-018 | 配置与有效模式展示 | 查看历史任务、部分校验任务、自动提升文件和不校验文件 | 历史显示强校验；任务列表显示 `n 帧 × 帧大小`；自动提升显示“强校验（采样范围已覆盖文件）”；不校验不展示摘要 |
| FT-WEB-019 | 清理已完成保留历史 | 在传输中心点击“清理已完成”，然后分别刷新传输中心和运行记录 | 已结束运行不再出现在传输中心；运行记录、运行详情、文件项、指标和日志仍可查询；前端不调用 `DELETE /runs/{runId}` |
| FT-WEB-020 | 非结构化多选删除 | 在同一目录勾选多个文件和目录，确认一次删除，并让其中一个操作失败 | 对选择快照中的每个路径顺序调用删除接口；目录携带递归确认；单项失败不阻止后续项；整批只刷新一次并展示成功/失败数量 |

## 8. OSS 与 FTP 真实操作验收矩阵

以下验收必须在已配置的测试数据源上执行，源名、路径和账号只保留在运行时上下文，不写入代码、日志或报告。OSS 和 FTP 各执行一遍：

| ID | 操作 | 预期 |
|---|---|---|
| FT-REAL-001 | 浏览根目录 | Worker 返回根目录分页列表，Server 无文件插件访问日志 |
| FT-REAL-002 | 新建目录 | 目录创建成功，可再次浏览看到目录 |
| FT-REAL-003 | 文件/目录重命名 | 同一父目录下重命名成功，目标冲突被拒绝 |
| FT-REAL-004 | 同源移动 | 同一数据源内移动成功，移动到自身子目录被拒绝 |
| FT-REAL-005 | 文件下载 | 仅文件可下载，响应为流式二进制，内容摘要与源一致 |
| FT-REAL-006 | 文件删除 | 文件删除成功，审计记录可查询 |
| FT-REAL-007 | 非空目录递归删除 | 未确认时拒绝；二次确认后目录及后代删除成功 |
| FT-REAL-008 | 路径穿越 | `../`、反斜杠越界和 NUL 路径均拒绝 |
| FT-REAL-009 | ACL 分层 | 普通成员、数据源创建者、管理员分别验证浏览、下载、编辑、删除 |
| FT-REAL-010 | 单集群实际传输 | OSS→FTP、FTP→OSS 仅在同一运行集群内执行；跨集群请求拒绝 |
| FT-REAL-011 | 失败操作审计 | 制造目标冲突或 Worker 业务失败 | 返回原始业务错误，并以独立提交语义保留 `FAILED` 审计 |
| FT-REAL-012 | FTP 根目录传输与校验 | 将可观察校验进度的文件传输到 FTP 逻辑根目录 | 页面和运行记录显示 `/文件名`；FTP 实际文件只生成一份；`VERIFYING` 进度递增并在目标 SHA-256 一致后提交成功 |
| FT-REAL-013 | 五类部分校验互传 | Local、FTP、SFTP、MinIO、OSS 至少各作为一次源和一次目标执行 `PARTIAL` | 采样指纹一致后提交成功；FTP 记录多次范围读取成本；MinIO multipart 临时对象可被采样 |

## 9. 性能验收

1. 传输单个 10 GiB 文件，采集 Server/Worker RSS、堆、线程、连接和带宽曲线。
2. 分别使用 1、4、8、16 个并发文件，观察吞吐、错误率和普通控制接口 P95。
3. 让 Worker 文件并发达到上限，验证限流和重试不会形成请求风暴。
4. 中途重启 Worker 和 Server，验证单集群状态一致性。
5. 执行 10 万小文件发现与传输，验证游标、数据库写入量和指标采样开销。

性能通过线由部署环境基线确定，但内存不得随单文件大小线性增长，心跳、健康检查和普通 Dispatch 不得被文件操作长时间阻塞。

## 10. 日志与诊断验收

| ID | 场景 | 操作 | 预期 |
|---|---|---|---|
| FT-LOG-001 | 并行运行 MDC 隔离 | 同时执行两个运行，每个运行包含两个并行文件 | 子线程插件日志进入各自 `runRecordId` 运行日志；线程结束后恢复原 MDC，不串入其他运行或 Worker 主日志 |
| FT-LOG-002 | 生命周期日志 | 完成一次成功运行并制造一次规划阶段失败 | 包含 dispatch、plan、run 开始/完成事件；失败带固定 `phase`、异常类型和脱敏堆栈，完整堆栈只在生命周期入口打印一次 |
| FT-LOG-003 | 文件进度限频 | 让单文件持续传输超过 65 秒并高频产生进度事件 | `FT_ITEM_PROGRESS` 每个文件最多 30 秒一条，`FT_RUN_PROGRESS` 每个运行最多 60 秒一条；终态不被限频 |
| FT-LOG-004 | 进度字段 | 执行新传输、断点恢复和摘要重建 | 日志分别显示 `confirmedBytes`、`observedBytes`、`activityBytes`、`resumedBytes`、`currentBps`，零字节与摘要重建不混淆 |
| FT-LOG-005 | 恢复与终态 | 覆盖恢复接受、回零、暂停/恢复、重试、冲突、校验、提交、成功、跳过、取消、后置动作失败和最终失败 | 对应稳定事件码和正确级别完整；普通重试前的临时失败不误报为最终 `FT_ITEM_FAILED` |
| FT-LOG-006 | checkpoint 安全 | 保存、加载、删除断点并制造反序列化失败 | 只在 `DEBUG` 记录 run/item、确认偏移和 schema 版本；不包含 checkpoint JSON、checksum state 或临时物理定位 |
| FT-LOG-007 | 非结构化跨进程关联 | 对 OSS、FTP、SFTP 分别浏览并执行 mkdir、rename、move、delete、upload、download | Server 和 Worker 日志具有相同 `operationId`；请求结束和异步流结束后 MDC 被恢复 |
| FT-LOG-008 | 预期拒绝分级 | 制造 ACL 拒绝、目标冲突、路径不存在、非空目录未确认和远端无权限，并让插件异常消息携带伪造堆栈 | 使用 `UF_ACL_DENIED` 或 `UF_OPERATION_REJECTED` 的 `WARN`；业务消息单行、脱敏、有界，不打印 `UF_WORKER_FAILED` 的 `ERROR` 堆栈 |
| FT-LOG-009 | 非预期故障分级 | 制造网络断开、协议协商或插件运行异常 | Server `UF_ROUTE_FAILED` 与 Worker `UF_WORKER_FAILED` 为 `ERROR`，带 operationId、阶段/操作、异常类型、脱敏消息和有界有用堆栈 |
| FT-LOG-010 | operationId 输入安全 | 内部头为空、合法 UUID、包含换行或超过 64 字符 | 合法值透传；不合法值被忽略且不污染 MDC、日志行或认证结果 |
| FT-LOG-011 | Outbox/SSE 静默规则 | 观察空轮询、非空批次、首次快照、有效 replay、过期/超限 replay、发送失败、heartbeat 和清理 | 空轮询与 heartbeat 无日志；非空批次/首次快照为 `DEBUG`；有效 replay 和实际清理为 `INFO`；过期、超限、缺口和发送失败为 `WARN` |
| FT-LOG-012 | 敏感信息回归 | 在异常消息或模拟配置中放入 password、Token、AccessKey、PrivateKey、Authorization、Cookie、JDBC URL 和 checkpoint state | 应用日志和运行日志不出现明文；Server 异常消息不超过 2 KiB、异常栈不超过 12 KiB；Outbox/SSE/审计不新增敏感字段 |
| FT-LOG-013 | 统一入口真实验收 | 从 `http://127.0.0.1:8000/dfs/data-aggregation-studio/` 启动超过 65 秒的 SFTP 传输并执行暂停、恢复直至终态 | 运行日志抽屉可见开始、30 秒文件进度、60 秒运行汇总、checkpoint 恢复、暂停/恢复、校验、提交和终态；队列仍只使用 SSE，无新增轮询 |

自动化至少执行：

```powershell
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd DataAggregation/data-aggregation-studio/backend
mvn -pl studio-worker -am `
  "-Dtest=FileTransferNodeExecutorRetryTest,StudioTransferEventListenerTest,FileTransferTaskMdcTest,WorkerFileTransferDispatchTest,RuntimeDatasourceProbeExecutorFileOperationTest,UnstructuredManagementOperationAuditTest,UnstructuredManagementAclDecisionTest,UnstructuredManagementAclScopeTest,RuntimeDatasourceProbeRouterSecurityTest,InternalDatasourceProbeControllerTest,FileTransferEventServiceOutboxTest,FileTransferOutboxCleanupTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

### 10.1 2026-08-15 执行结果

- 自动化：SFTP 插件 7 项、Studio Commons 3 项、Studio Infra 27 项、Worker 内部 Controller 12 项，共 49 项通过，0 失败、0 错误；此前日志广覆盖回归 Infra 64 项、Worker 33 项和 `GlobalExceptionHandlerTest` 11 项通过。
- `FT-LOG-001` 至 `FT-LOG-012`：MDC 隔离、限频、恢复阶段、checkpoint 安全、operationId、异常分级、Outbox 静默规则和敏感信息边界均已由定向测试覆盖。
- `FT-LOG-013`：真实长传输已观察到 30 秒文件进度、60 秒运行汇总、暂停/恢复、Worker 重启后的 checkpoint 接管、恢复接受、SSE 更新和终态事件；文件传输前端未增加 `setInterval`。
- 真实 SFTP 权限拒绝：接口保持 `400/BUSINESS_ERROR`；Server/Worker 的 `operationId` 一致，均记录 `UF_OPERATION_REJECTED/WARN`，插件记录无堆栈权限 `WARN`，没有 `UF_WORKER_FAILED` 或 Java 堆栈。
- 运行环境：Server、Worker 为 `UP`，在线 Worker 数为 1，统一入口 `http://127.0.0.1:8000/dfs/data-aggregation-studio/` 返回 200；文件传输页面租户/项目与 API 上下文一致，控制台无新增错误或警告。
- 安全扫描：从应用日志抽取 228 条 `FT_*`/`UF_*` 事件，凭据赋值、checkpoint JSON、checksum state、内部认证头、技术元数据和物理 endpoint 均为 0 命中。

本日志工作包不要求数据库升级、前端构建产物变更或公共接口字段变更。Studio Server 和 Studio Worker 已重启并完成验收；回滚时只需回滚本工作包 Java 代码并重启，不处理数据库、审计或历史运行记录。
