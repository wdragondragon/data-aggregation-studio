# 数据质量指标测试用例

生成日期：2026-04-17  
文档性质：测试用例设计文档，仅生成用例，不执行真实测试  
覆盖范围：数据质量 > 质量指标，包含接口测试、用户界面测试、问题生命周期联动测试  
基础来源：合并 `quality-metrics-ui-test-cases.md` 既有界面用例，并结合前后端源码继续扩展

## 参考范围

- 前端页面：`frontend/apps/web/src/views/QualityMetricsView.vue`
- 前端路由：`frontend/apps/web/src/router/index.ts`
- 前端 SDK：`frontend/packages/api-sdk/src/client.ts`
- 后端接口：`backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/QualityMetricsController.java`
- 指标服务：`backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/QualityMetricsService.java`
- 问题服务：`backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/QualityIssueService.java`
- 数据表结构：`backend/studio-server/src/main/resources/schema-mysql.sql`

## 测试目标

- 验证质量指标页面能正确展示当前项目内已纳管质量任务的健康、风险、资产和问题信息。
- 验证质量指标接口在筛选、空数据、异常参数、跨项目隔离和问题处置下行为稳定。
- 验证问题中心的认领、状态、严重级别、评论、恢复信号和 reopen 聚合符合产品语义。
- 验证总览、资产洞察、问题中心、质量任务、质量任务日志之间的跳转和抽屉行为一致。
- 验证大数据量、边界时间、无权限、无项目上下文等情况下不会出现 500 或页面不可用。

## 核心接口清单

| 接口 | 方法 | 目标 |
| --- | --- | --- |
| `/api/v1/quality-metrics/options` | GET | 获取筛选项 |
| `/api/v1/quality-metrics/dashboard/query` | POST | 查询质量指标总览 |
| `/api/v1/quality-metrics/assets/query` | POST | 查询资产风险列表 |
| `/api/v1/quality-metrics/assets/{assetId}` | GET | 查询资产详情 |
| `/api/v1/quality-metrics/issues/query` | POST | 查询问题列表 |
| `/api/v1/quality-metrics/issues/{id}` | GET | 查询问题详情 |
| `/api/v1/quality-metrics/issues/{id}/assign` | POST | 分配或清空负责人 |
| `/api/v1/quality-metrics/issues/{id}/status` | POST | 更新问题状态 |
| `/api/v1/quality-metrics/issues/{id}/severity` | POST | 更新严重级别 |
| `/api/v1/quality-metrics/issues/{id}/comment` | POST | 添加问题评论 |

## 指标口径检查点

- 指标只统计当前项目内已经创建质量任务的资产；未创建质量任务的资产不纳入总览和资产列表计算。
- 执行健康分取值 0-100，当前页面公式为：`45% 任务通过率 + 30% 告警未触发率 + 25% 稳定性分`。
- 治理风险指数取值 0-100，当前页面公式为：`50% 未解决问题加权分 + 25% SLA 超时占比 + 15% 平均存续时长 + 10% Reopen 比例`。
- 问题活跃状态包含 `OPEN`、`ACKNOWLEDGED`、`INVESTIGATING`、`MITIGATED`；`RESOLVED` 和 `FALSE_POSITIVE` 不计入活跃问题。
- 同一问题按签名聚合，签名包含项目、质量任务、规则、数据源、模型、字段、输出字段、粒度和问题类型。
- 执行成功仅写入恢复信号，不自动关闭问题；问题关闭仍需人工处理。
- 连续失败达到阈值或 SLA 超时时，系统严重级别会自动升级，但不超过 `CRITICAL`。
- 手工严重级别优先展示；系统严重级别仍保留用于追踪。

## 测试数据建议

| 数据集 | 用途 | 建议准备 |
| --- | --- | --- |
| 空项目 | 验证空态 | 当前项目无质量任务、无运行记录、无问题 |
| 已纳管无运行项目 | 验证默认分数 | 有质量任务，但未运行 |
| 成功运行项目 | 验证健康分和趋势 | 多个质量任务运行成功，`alertCount=0` |
| 告警命中项目 | 验证问题生成和风险 | 质量任务成功但 `alertCount>0`，包含表级和字段级告警 |
| 执行失败项目 | 验证失败类问题 | 质量任务运行状态为 `FAILED`，包含错误信息和 SQL 片段 |
| 恢复信号项目 | 验证不自动关闭 | 已有活跃问题后再次成功运行且不再命中告警 |
| Reopen 项目 | 验证重复出现 | 问题被关闭后再次由相同签名触发 |
| 超时 SLA 项目 | 验证超时和升级 | 构造 `sla_due_at` 早于当前时间的问题 |
| 多项目隔离数据 | 验证权限隔离 | 不同项目存在相同数据源、模型、任务、问题 |
| 大数据量项目 | 验证性能和分页 | 100+ 资产、1000+ 问题、1000+ 时间线事件 |

## 接口测试用例

### A. 认证、项目上下文与通用行为

| 编号 | 优先级 | 接口 | 场景 | 请求要点 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-API-AUTH-001 | P0 | 全部 | 未登录访问质量指标接口 | 不携带登录态调用任一接口 | 返回未认证响应，不返回业务数据 |
| QM-API-AUTH-002 | P0 | 全部 | 当前用户无项目上下文 | 登录但未选择项目调用总览/资产/问题接口 | 不出现 500；总览、资产、问题返回空结果或明确业务错误 |
| QM-API-AUTH-003 | P0 | 全部 | 当前项目隔离 | A 项目登录态查询，数据库中存在 B 项目问题 | 响应只包含 A 项目数据 |
| QM-API-AUTH-004 | P1 | 全部 | 租户隔离 | 当前租户查询，数据库中存在其他租户同 ID 数据 | 响应不包含其他租户数据 |
| QM-API-AUTH-005 | P1 | 全部 | 已删除数据隔离 | 构造 `deleted=1` 的问题、事件、快照 | 正常列表和详情不展示已删除数据 |
| QM-API-AUTH-006 | P1 | 全部 | 空请求体兼容 | POST 接口请求体为空 | 接口按默认筛选执行，不出现反序列化错误 |
| QM-API-AUTH-007 | P1 | 全部 | 无效 JSON | POST 接口传入非法 JSON | 返回 400 类错误，不写入任何问题处置数据 |
| QM-API-AUTH-008 | P2 | 全部 | 并发查询稳定性 | 多用户同时查询总览、资产、问题 | 接口响应稳定，无明显串数据或 500 |

### B. 筛选项接口

| 编号 | 优先级 | 接口 | 场景 | 请求要点 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-API-OPT-001 | P0 | GET `/options` | 获取数据源和模型选项 | 当前项目有数据源、模型 | 返回 `datasources`、`models`，字段至少包含 id/name/label |
| QM-API-OPT-002 | P1 | GET `/options` | 无数据源无模型 | 空项目调用 | 返回空数组，不返回 null，不出现 500 |
| QM-API-OPT-003 | P1 | GET `/options` | 数据源名称为空 | 构造名称为空但 ID 存在的数据源 | 选项仍可返回，前端可 fallback 到 id |
| QM-API-OPT-004 | P1 | GET `/options` | 模型名称为空 | 构造名称为空但 ID 存在的模型 | 选项仍可返回，前端可 fallback 到 id |
| QM-API-OPT-005 | P2 | GET `/options` | 大量筛选项 | 当前项目 500+ 模型 | 响应可用，前端下拉可搜索，不阻塞页面 |

### C. 总览接口

| 编号 | 优先级 | 接口 | 场景 | 请求要点 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-API-DASH-001 | P0 | POST `/dashboard/query` | 默认查询 | 请求体为空或 `{}` | 返回 summary、趋势、分布、矩阵、TopN 字段，结构完整 |
| QM-API-DASH-002 | P0 | POST `/dashboard/query` | 无质量任务项目 | 当前项目无质量任务 | 总览为空态，summary 为 0 或空 map，列表与图表为空 |
| QM-API-DASH-003 | P0 | POST `/dashboard/query` | 未创建任务资产不纳入计算 | 项目有 10 个模型，仅 2 个模型绑定质量任务 | 已纳管资产数为 2，高风险资产只出现这 2 个模型 |
| QM-API-DASH-004 | P0 | POST `/dashboard/query` | 全部成功运行 | 多个质量任务在窗口内成功且无告警 | 执行健康分接近 100，治理风险指数为 0 或低值 |
| QM-API-DASH-005 | P0 | POST `/dashboard/query` | 执行失败影响健康分 | 窗口内部分运行 `FAILED` | 健康分下降，维度分布失败占比增加，高噪声任务出现对应任务 |
| QM-API-DASH-006 | P0 | POST `/dashboard/query` | 告警命中影响健康分 | 窗口内运行成功但 `resultJson.alertCount>0` | 该运行按风险/失败类运行处理，健康分下降，问题中心有告警问题 |
| QM-API-DASH-007 | P0 | POST `/dashboard/query` | 活跃问题影响风险指数 | 构造不同严重级别活跃问题 | 治理风险指数随严重级别权重提升 |
| QM-API-DASH-008 | P0 | POST `/dashboard/query` | 已解决问题不计入活跃风险 | 将问题状态改为 `RESOLVED` | 活跃问题数下降，治理风险指数相应下降 |
| QM-API-DASH-009 | P1 | POST `/dashboard/query` | 误报问题不计入活跃风险 | 将问题状态改为 `FALSE_POSITIVE` | 活跃问题数下降，治理风险指数相应下降 |
| QM-API-DASH-010 | P1 | POST `/dashboard/query` | SLA 超时统计 | 构造 `slaDueAt < now` 的活跃问题 | `overdueIssueCount` 增加，风险指数上升 |
| QM-API-DASH-011 | P1 | POST `/dashboard/query` | 受影响资产去重 | 同一资产存在多个活跃问题 | `affectedAssetCount` 按资产去重 |
| QM-API-DASH-012 | P1 | POST `/dashboard/query` | 时间范围过滤运行记录 | 只查询最近 24h | 只统计窗口内 `endedAt` 在范围内的运行 |
| QM-API-DASH-013 | P1 | POST `/dashboard/query` | 时间范围过滤问题事件 | 只查询最近 7d | 问题趋势只统计窗口内事件 |
| QM-API-DASH-014 | P1 | POST `/dashboard/query` | 数据源筛选 | 指定 `datasourceId` | 总览只统计该数据源下质量任务、运行和问题 |
| QM-API-DASH-015 | P1 | POST `/dashboard/query` | 模型筛选 | 指定 `modelId` | 总览只统计该模型对应资产 |
| QM-API-DASH-016 | P1 | POST `/dashboard/query` | 规则维度筛选 | 指定 `ruleDimension=COMPLETENESS` | 只统计完整性质量任务及其问题 |
| QM-API-DASH-017 | P1 | POST `/dashboard/query` | 粒度筛选 | 指定 `granularity=COLUMN` | 只统计字段级质量任务及其问题 |
| QM-API-DASH-018 | P1 | POST `/dashboard/query` | 任务状态筛选 | 指定 `taskStatus=ONLINE` | 草稿任务不纳入结果 |
| QM-API-DASH-019 | P1 | POST `/dashboard/query` | TopN 默认值 | 不传 `topN` | 高风险资产和高噪声对象默认最多 10 条 |
| QM-API-DASH-020 | P1 | POST `/dashboard/query` | TopN 上限 | 传 `topN=999` | 返回最多 50 条 TopN 数据 |
| QM-API-DASH-021 | P1 | POST `/dashboard/query` | TopN 非正数 | 传 `topN=0` 或负数 | 按默认 10 条处理，不报 500 |
| QM-API-DASH-022 | P1 | POST `/dashboard/query` | 双分趋势日期补齐 | 查询 7 天窗口，其中只有 2 天有运行 | `scoreTrend` 覆盖窗口内每天，缺运行日期仍有点位 |
| QM-API-DASH-023 | P1 | POST `/dashboard/query` | 问题趋势事件类型 | 构造 CREATED、DETECTED、REOPENED、STATUS_CHANGED 事件 | 新增和解决趋势按事件类型归类 |
| QM-API-DASH-024 | P1 | POST `/dashboard/query` | 规则维度分布固定维度 | 当前只存在一个维度运行 | 返回六个固定维度，未运行维度失败占比为 0 |
| QM-API-DASH-025 | P1 | POST `/dashboard/query` | 覆盖矩阵资产去重 | 同一数据源类型和维度下多个任务绑定同一模型 | 覆盖矩阵按模型去重计数 |
| QM-API-DASH-026 | P1 | POST `/dashboard/query` | 高风险资产排序 | 构造风险指数不同的资产 | 按治理风险指数降序，再按活跃问题数和最近运行排序 |
| QM-API-DASH-027 | P1 | POST `/dashboard/query` | 高噪声任务排序 | 构造失败次数不同的任务 | 按失败次数降序，再按 reopen 次数排序 |
| QM-API-DASH-028 | P2 | POST `/dashboard/query` | 无效枚举筛选 | 传入不存在的维度或粒度字符串 | 返回空结果或明确业务错误，不出现 500 |
| QM-API-DASH-029 | P2 | POST `/dashboard/query` | 开始时间晚于结束时间 | `startTime > endTime` | 返回空结果或参数错误，不出现 500 |
| QM-API-DASH-030 | P2 | POST `/dashboard/query` | 快照写入 | 查询总览后检查快照表 | 当前项目当前日期生成项目级与资产级快照，不重复污染同日快照 |

### D. 资产接口

| 编号 | 优先级 | 接口 | 场景 | 请求要点 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-API-ASSET-001 | P0 | POST `/assets/query` | 查询资产列表 | 当前项目有质量任务 | 返回资产数组，字段包含 assetId、数据源、模型、双分、覆盖维度、最近运行 |
| QM-API-ASSET-002 | P0 | POST `/assets/query` | 未纳管模型不展示 | 当前项目存在未绑定质量任务模型 | 未纳管模型不出现在资产列表 |
| QM-API-ASSET-003 | P0 | POST `/assets/query` | 仅看有问题资产 | `onlyProblemAssets=true` | 只返回活跃问题数大于 0 的资产 |
| QM-API-ASSET-004 | P1 | POST `/assets/query` | 仅看低覆盖资产 | `onlyLowCoverageAssets=true` | 只返回覆盖率小于 50% 的资产 |
| QM-API-ASSET-005 | P1 | POST `/assets/query` | 同时启用快捷过滤 | 两个布尔值均为 true | 返回既有活跃问题又低覆盖的资产 |
| QM-API-ASSET-006 | P1 | POST `/assets/query` | 数据源筛选 | 指定数据源 | 只返回该数据源下资产 |
| QM-API-ASSET-007 | P1 | POST `/assets/query` | 模型筛选 | 指定模型 | 只返回该模型资产 |
| QM-API-ASSET-008 | P1 | POST `/assets/query` | 维度筛选影响覆盖维度 | 指定规则维度 | 资产覆盖维度和任务集合按筛选收敛 |
| QM-API-ASSET-009 | P1 | POST `/assets/query` | 最近运行状态为告警 | 最新运行成功但 alertCount>0 | `latestRunStatus` 展示为失败类状态 |
| QM-API-ASSET-010 | P1 | POST `/assets/query` | 无运行但有问题 | 资产已有活跃问题但窗口无运行 | 资产仍可展示最新证据，最近运行为空 |
| QM-API-ASSET-011 | P1 | GET `/assets/{assetId}` | 查询资产详情 | 使用列表返回的 `assetId` | 返回摘要、趋势、活跃问题、绑定任务、失败证据、字段分组 |
| QM-API-ASSET-012 | P1 | GET `/assets/{assetId}` | assetId URL 编码 | `assetId` 形如 `1:2` 并经过编码 | 后端可正确解析，不因冒号失败 |
| QM-API-ASSET-013 | P1 | GET `/assets/{assetId}` | 非法 assetId | `assetId=abc` | 不出现 500，返回空详情或明确 not found |
| QM-API-ASSET-014 | P1 | GET `/assets/{assetId}` | 不存在资产 | 使用不存在的数据源和模型组合 | 返回空详情或明确 not found，不串出其他资产 |
| QM-API-ASSET-015 | P1 | GET `/assets/{assetId}` | 资产详情时间范围 | 传入 startTime/endTime | 趋势、失败证据和相关运行按窗口过滤 |
| QM-API-ASSET-016 | P1 | GET `/assets/{assetId}` | 绑定任务列表 | 资产绑定多个质量任务 | `relatedTasks` 包含任务、规则、维度、粒度、最近运行记录 |
| QM-API-ASSET-017 | P1 | GET `/assets/{assetId}` | 最近失败证据上限 | 构造 10 条失败/告警运行 | `latestFailures` 最多返回 5 条且按最近优先 |
| QM-API-ASSET-018 | P1 | GET `/assets/{assetId}` | 字段级问题分组 | 同资产不同字段有活跃问题 | `fieldIssueGroups` 按字段聚合展示问题 |
| QM-API-ASSET-019 | P2 | GET `/assets/{assetId}` | 日期格式错误 | query 中日期不符合 `yyyy-MM-dd HH:mm:ss` | 返回 400 类参数错误，不出现 500 |
| QM-API-ASSET-020 | P2 | POST `/assets/query` | 大量资产 | 100+ 已纳管资产 | 接口响应可接受，排序稳定，前端分页可展示 |

### E. 问题查询与详情接口

| 编号 | 优先级 | 接口 | 场景 | 请求要点 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-API-ISSUE-001 | P0 | POST `/issues/query` | 查询问题列表 | 当前项目存在问题 | 返回问题数组，字段完整 |
| QM-API-ISSUE-002 | P0 | POST `/issues/query` | 无问题项目 | 当前项目无问题 | 返回空数组，不报错 |
| QM-API-ISSUE-003 | P0 | POST `/issues/query` | 活跃问题排序 | 同时存在活跃和关闭问题 | 活跃问题优先展示 |
| QM-API-ISSUE-004 | P1 | POST `/issues/query` | 严重级别排序 | 构造 LOW/MEDIUM/HIGH/CRITICAL | 同状态下高严重级别优先 |
| QM-API-ISSUE-005 | P1 | POST `/issues/query` | 超时排序 | 同级别问题一条超时一条未超时 | 超时问题优先 |
| QM-API-ISSUE-006 | P1 | POST `/issues/query` | 最近出现排序 | 多条相同级别问题 | `lastSeenAt` 新的优先 |
| QM-API-ISSUE-007 | P1 | POST `/issues/query` | 数据源筛选 | 指定 `datasourceId` | 只返回对应数据源问题 |
| QM-API-ISSUE-008 | P1 | POST `/issues/query` | 模型筛选 | 指定 `modelId` | 只返回对应模型问题 |
| QM-API-ISSUE-009 | P1 | POST `/issues/query` | 维度筛选 | 指定 `ruleDimension` | 只返回该质量维度问题 |
| QM-API-ISSUE-010 | P1 | POST `/issues/query` | 粒度筛选 | 指定 `granularity=TABLE/COLUMN` | 只返回对应粒度问题 |
| QM-API-ISSUE-011 | P1 | POST `/issues/query` | 任务状态筛选 | 指定 `taskStatus=ONLINE` | 只返回匹配质量任务状态的问题 |
| QM-API-ISSUE-012 | P1 | POST `/issues/query` | 严重级别筛选 | 指定 `severity=HIGH` | 只返回 HIGH 问题 |
| QM-API-ISSUE-013 | P1 | POST `/issues/query` | 状态筛选 | 指定 `status=INVESTIGATING` | 只返回排查中问题 |
| QM-API-ISSUE-014 | P1 | POST `/issues/query` | 负责人筛选 | 指定 `assigneeUserId` | 只返回该负责人问题 |
| QM-API-ISSUE-015 | P1 | POST `/issues/query` | 时间范围筛选 | 指定 start/end | 按 `lastSeenAt` 在窗口内过滤 |
| QM-API-ISSUE-016 | P1 | POST `/issues/query` | 任务过滤无匹配 | 指定不存在的模型或维度组合 | 返回空数组，不扫描出全部问题 |
| QM-API-ISSUE-017 | P1 | GET `/issues/{id}` | 查询问题详情 | 使用问题列表中的 id | 返回问题详情、当前证据、时间线 |
| QM-API-ISSUE-018 | P1 | GET `/issues/{id}` | 不存在问题 | 使用不存在 id | 返回 not found，不出现 500 |
| QM-API-ISSUE-019 | P1 | GET `/issues/{id}` | 跨项目问题详情 | A 项目访问 B 项目问题 id | 返回 not found 或无权限 |
| QM-API-ISSUE-020 | P1 | GET `/issues/{id}` | 时间线倒序 | 同一问题有多条事件 | 详情中时间线按创建时间/ID 倒序返回 |
| QM-API-ISSUE-021 | P2 | POST `/issues/query` | 无效枚举值 | 传不存在的 severity/status | 返回空结果或参数错误，不报 500 |
| QM-API-ISSUE-022 | P2 | POST `/issues/query` | 大量问题 | 1000+ 问题 | 查询可用，前端分页不丢数据 |

### F. 问题处置接口

| 编号 | 优先级 | 接口 | 场景 | 请求要点 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-API-ACT-001 | P0 | POST `/issues/{id}/assign` | 分配负责人 | body `{ "assigneeUserId": 用户ID }` | 问题负责人更新，时间线新增 `ASSIGNED` 类事件 |
| QM-API-ACT-002 | P1 | POST `/issues/{id}/assign` | 清空负责人 | body 为空或 `assigneeUserId=null` | 负责人被清空，时间线记录变更 |
| QM-API-ACT-003 | P1 | POST `/issues/{id}/assign` | 指派不存在用户 | body 使用不存在用户 ID | 不出现 500；按产品约定失败或保存空名称 |
| QM-API-ACT-004 | P0 | POST `/issues/{id}/status` | 更新为排查中 | body `{ "status": "INVESTIGATING" }` | 状态更新，时间线新增状态变更 |
| QM-API-ACT-005 | P0 | POST `/issues/{id}/status` | 更新为已解决 | body `{ "status": "RESOLVED" }` | 问题变为非活跃，连续失败清零，最近运行状态标记恢复 |
| QM-API-ACT-006 | P0 | POST `/issues/{id}/status` | 更新为误报 | body `{ "status": "FALSE_POSITIVE" }` | 问题变为非活跃，不计入总览活跃问题 |
| QM-API-ACT-007 | P1 | POST `/issues/{id}/status` | 已关闭问题 reopen | 将 RESOLVED 问题更新为 OPEN | 状态变为 OPEN，reopenCount 增加，SLA 重新计算 |
| QM-API-ACT-008 | P1 | POST `/issues/{id}/status` | 状态变更备注 | body 带 comment | 时间线消息包含备注 |
| QM-API-ACT-009 | P1 | POST `/issues/{id}/status` | 状态为空 | body `{ "status": "" }` | 返回 400，问题状态不变 |
| QM-API-ACT-010 | P1 | POST `/issues/{id}/status` | 状态非法 | body `{ "status": "DONE" }` | 返回业务错误或 400，问题状态不变 |
| QM-API-ACT-011 | P0 | POST `/issues/{id}/severity` | 手工调整严重级别 | body `{ "severity": "CRITICAL" }` | 展示严重级别更新，manualSeverity 有值，时间线记录 |
| QM-API-ACT-012 | P1 | POST `/issues/{id}/severity` | 严重级别备注 | body 带 comment | 时间线消息包含备注 |
| QM-API-ACT-013 | P1 | POST `/issues/{id}/severity` | 严重级别为空 | body `{ "severity": "" }` | 返回 400，严重级别不变 |
| QM-API-ACT-014 | P1 | POST `/issues/{id}/severity` | 严重级别非法 | body `{ "severity": "BLOCKER" }` | 返回业务错误或 400，严重级别不变 |
| QM-API-ACT-015 | P0 | POST `/issues/{id}/comment` | 添加评论 | body `{ "content": "处理说明" }` | 评论保存，时间线新增 COMMENT 事件 |
| QM-API-ACT-016 | P1 | POST `/issues/{id}/comment` | 评论为空 | body `{ "content": "" }` | 返回 400，不写入评论 |
| QM-API-ACT-017 | P1 | POST `/issues/{id}/comment` | 评论仅空格 | body `{ "content": "   " }` | 返回 400 或前端拦截，不写入评论 |
| QM-API-ACT-018 | P1 | POST `/issues/{id}/comment` | 超长评论 | body content 超过常规长度 | 不出现 500；按字段容量保存或返回明确错误 |
| QM-API-ACT-019 | P1 | 全部处置接口 | 跨项目处置 | A 项目处置 B 项目问题 | 返回 not found 或无权限，不写入事件 |
| QM-API-ACT-020 | P1 | 全部处置接口 | 并发处置 | 两个用户同时修改同一问题 | 最终状态一致，时间线可追溯，不出现 500 |

### G. 问题生成与生命周期联动

| 编号 | 优先级 | 场景 | 操作要点 | 预期结果 |
| --- | --- | --- | --- | --- |
| QM-API-LIFE-001 | P0 | 执行失败生成问题 | 触发质量任务执行失败 | 生成 `EXECUTION_FAILURE` 问题，基础严重级别为 HIGH |
| QM-API-LIFE-002 | P0 | 表级告警生成问题 | 表级质量任务成功但命中告警 | 生成 `ALERT` 问题，基础严重级别为 HIGH |
| QM-API-LIFE-003 | P0 | 字段级告警生成问题 | 字段级质量任务成功但命中告警 | 生成 `ALERT` 问题，基础严重级别为 MEDIUM |
| QM-API-LIFE-004 | P0 | 同签名重复失败聚合 | 同一任务、规则、字段、输出字段重复失败 | 不重复创建问题；occurrenceCount、consecutiveFailureCount、lastSeenAt 更新 |
| QM-API-LIFE-005 | P0 | 不同输出字段分别成单 | 同一任务多个输出字段命中告警 | 按输出字段生成不同问题 |
| QM-API-LIFE-006 | P0 | 执行失败和告警类型分离 | 同一任务既发生执行失败又历史有告警 | `issueType` 不同，签名不同，不互相覆盖 |
| QM-API-LIFE-007 | P1 | 第三次连续失败升级 | 同签名连续触发 3 次 | 系统严重级别自动升级一级 |
| QM-API-LIFE-008 | P1 | SLA 超时升级 | 活跃问题超过 SLA 后再次触发 | 系统严重级别升级一级，不超过 CRITICAL |
| QM-API-LIFE-009 | P1 | 手工严重级别优先 | 手工设为 LOW 后系统再次计算为 HIGH | 展示严重级别仍按 manualSeverity，系统严重级别保留 |
| QM-API-LIFE-010 | P0 | 成功运行写恢复信号 | 活跃问题后续运行成功且不再命中 | 写入 `RECOVERY_DETECTED` 时间线，问题状态不自动关闭 |
| QM-API-LIFE-011 | P1 | 部分恢复 | 同一任务有两个活跃告警，本次只剩一个告警 | 未再命中的问题写恢复信号，仍命中的问题继续 DETECTED |
| QM-API-LIFE-012 | P1 | 关闭后再次触发 reopen | RESOLVED 问题被同签名再次触发 | 原问题变 OPEN，reopenCount 增加，不新建重复问题 |
| QM-API-LIFE-013 | P1 | 误报后再次触发 reopen | FALSE_POSITIVE 问题被同签名再次触发 | 原问题变 OPEN，reopenCount 增加 |
| QM-API-LIFE-014 | P1 | 任务不存在时忽略事件 | 执行事件质量任务 ID 找不到 | 不生成问题，不影响其他问题 |
| QM-API-LIFE-015 | P1 | 无质量任务 ID 时忽略事件 | 执行事件无 qualityTaskId | 不生成质量问题 |
| QM-API-LIFE-016 | P2 | 证据 JSON 可展示 | alertDetails 包含 resultField、message、resolvedSql | 问题详情 currentEvidence 可读且不丢关键字段 |

## 用户界面测试用例

### A. 菜单、路由与入口

| 编号 | 优先级 | 模块 | 场景 | 操作步骤 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-UI-NAV-001 | P0 | 菜单与路由 | 打开质量指标页面 | 登录后点击左侧 `数据质量 > 质量指标` | URL 进入 `/quality-metrics`，页面标题为“质量指标”，无控制台报错 |
| QM-UI-NAV-002 | P0 | 菜单与路由 | 菜单位置 | 查看左侧菜单 | `质量指标` 位于数据质量菜单组最后一个入口 |
| QM-UI-NAV-003 | P1 | 菜单与路由 | 无项目不可见或不可用 | 退出项目上下文后查看菜单 | 需要项目上下文的质量指标入口不可直接使用，或进入后显示明确提示 |
| QM-UI-NAV-004 | P1 | 顶部入口 | 跳转质量任务 | 点击页面右上 `质量任务` | 跳转 `/quality-tasks` |
| QM-UI-NAV-005 | P1 | 顶部入口 | 跳转运行日志 | 点击页面右上 `运行日志` | 跳转 `/quality-task-runs` |
| QM-UI-NAV-006 | P1 | 外部入口 | 从质量任务列表进入 | 进入 `/quality-tasks` 点击“质量指标” | 跳转 `/quality-metrics` |
| QM-UI-NAV-007 | P1 | 外部入口 | 从质量任务日志进入 | 进入 `/quality-task-runs` 点击“质量指标” | 跳转 `/quality-metrics` |
| QM-UI-NAV-008 | P2 | 浏览器导航 | 前进后退 | 在质量任务、质量指标、日志页面之间使用浏览器后退/前进 | 页面状态正常，不出现白屏 |

### B. 全局筛选

| 编号 | 优先级 | 模块 | 场景 | 操作步骤 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-UI-FLT-001 | P0 | 全局筛选 | 默认时间窗 | 首次进入页面观察筛选区 | 时间范围默认为最近 7 天，其他筛选为空 |
| QM-UI-FLT-002 | P0 | 全局筛选 | 切换 24 小时 | 点击 `24 小时` 后查询 | 三个 Tab 使用同一时间上下文刷新 |
| QM-UI-FLT-003 | P0 | 全局筛选 | 切换 30 天 | 点击 `30 天` 后查询 | 三个 Tab 使用同一时间上下文刷新 |
| QM-UI-FLT-004 | P0 | 全局筛选 | 自定义时间 | 选择自定义开始和结束时间后查询 | timePreset 变为自定义，接口带入自定义时间 |
| QM-UI-FLT-005 | P1 | 全局筛选 | 日期控件对齐 | 查看时间范围和其他筛选框 | 筛选项高度和顶部对齐，不错位、不缺角 |
| QM-UI-FLT-006 | P1 | 全局筛选 | 小屏换行 | 缩小窗口宽度 | 一行放不下时筛选区自然换行，不遮挡按钮 |
| QM-UI-FLT-007 | P0 | 全局筛选 | 数据源筛选 | 选择任一数据源并查询 | 总览、资产、问题同步刷新 |
| QM-UI-FLT-008 | P0 | 全局筛选 | 模型筛选 | 选择任一模型并查询 | 只展示该模型相关指标 |
| QM-UI-FLT-009 | P1 | 全局筛选 | 数据源与模型组合无数据 | 选择不匹配组合后查询 | 页面展示空态，不报错 |
| QM-UI-FLT-010 | P1 | 全局筛选 | 维度筛选 | 选择一致性、准确性等维度查询 | 图表和列表按维度刷新 |
| QM-UI-FLT-011 | P1 | 全局筛选 | 粒度筛选 | 选择表级或字段级查询 | 图表和列表按粒度刷新 |
| QM-UI-FLT-012 | P1 | 全局筛选 | 任务状态筛选 | 选择草稿或已发布查询 | 结果按任务状态刷新 |
| QM-UI-FLT-013 | P0 | 全局筛选 | 查询按钮 | 修改多个筛选后点击查询 | loading 展示，接口完成后页面更新 |
| QM-UI-FLT-014 | P0 | 全局筛选 | 重置按钮 | 修改筛选后点击重置 | 时间回到 7 天，筛选清空，三个 Tab 重新加载 |
| QM-UI-FLT-015 | P1 | 问题筛选 | 问题 Tab 扩展筛选 | 切换到问题中心 Tab | 额外显示严重级别、问题状态、负责人 |
| QM-UI-FLT-016 | P1 | 问题筛选 | 切回总览隐藏扩展筛选 | 从问题中心切回总览 | 严重级别、问题状态、负责人筛选隐藏 |
| QM-UI-FLT-017 | P1 | 问题筛选 | 负责人选项加载失败兜底 | 模拟项目成员接口失败 | 页面仍可用，可尝试用户列表兜底或显示空负责人选项 |

### C. 总览 Tab

| 编号 | 优先级 | 模块 | 场景 | 操作步骤 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-UI-OV-001 | P0 | 总览 | KPI 和算法卡片 | 进入总览 Tab | 展示执行健康分、健康分算法、治理风险指数、风险指数算法 |
| QM-UI-OV-002 | P0 | 总览 | 二级指标 | 查看算法卡片下方 | 展示活跃问题数、SLA 超时问题数、受影响资产数、已纳管资产数 |
| QM-UI-OV-003 | P1 | 总览 | 算法说明准确 | 查看健康分算法文案 | 文案为 `45% 任务通过率 + 30% 告警未触发率 + 25% 稳定性分` |
| QM-UI-OV-004 | P1 | 总览 | 风险说明准确 | 查看风险指数算法文案 | 文案包含未解决问题加权分、SLA 超时占比、平均存续时长、Reopen 比例 |
| QM-UI-OV-005 | P0 | 总览 | 双分趋势图有数据 | 当前项目有运行记录 | 显示执行健康分和治理风险指数双折线 |
| QM-UI-OV-006 | P1 | 总览 | 双分趋势图空态 | 无趋势数据 | 显示“暂无趋势数据” |
| QM-UI-OV-007 | P0 | 总览 | 问题趋势图有数据 | 当前项目有问题事件 | 显示新增和解决趋势 |
| QM-UI-OV-008 | P1 | 总览 | 问题趋势图空态 | 无问题事件 | 显示“暂无问题趋势” |
| QM-UI-OV-009 | P0 | 总览 | 规则维度分布有数据 | 当前窗口有质量任务运行 | 显示失败占比柱图 |
| QM-UI-OV-010 | P1 | 总览 | 规则维度分布空态 | 无运行数据 | 显示“暂无维度分布” |
| QM-UI-OV-011 | P0 | 总览 | 覆盖矩阵有数据 | 当前项目有不同数据源类型和维度任务 | 显示热力图，颜色深浅可区分 |
| QM-UI-OV-012 | P1 | 总览 | 覆盖矩阵空态 | 无覆盖数据 | 显示“暂无覆盖矩阵” |
| QM-UI-OV-013 | P0 | 总览 | 高风险资产表 | 查看高风险资产 TopN | 表格展示资产、健康分、风险指数、活跃问题、最后执行 |
| QM-UI-OV-014 | P1 | 总览 | 高风险资产下钻 | 点击资产名称 | 打开资产抽屉并加载详情 |
| QM-UI-OV-015 | P0 | 总览 | 高噪声任务/规则表 | 查看高噪声任务/规则 TopN | 表格展示对象、失败次数、Reopen、最近出现 |
| QM-UI-OV-016 | P1 | 总览 | 高噪声任务跳转 | 点击任务对象 | 跳转质量任务编辑页 |
| QM-UI-OV-017 | P1 | 总览 | 高噪声规则跳转 | 点击规则对象 | 跳转质量规则编辑页 |
| QM-UI-OV-018 | P1 | 总览 | 表格实体样式 | 查看资产/对象列 | 主标题和副信息上下布局自然，左下角不突兀 |
| QM-UI-OV-019 | P2 | 总览 | 图表 tooltip | 鼠标悬停图表 | tooltip 内容可读，不遮挡关键点 |
| QM-UI-OV-020 | P2 | 总览 | 数字格式 | 大数字展示 | 数字使用本地化格式，不出现 NaN 或 undefined |

### D. 资产洞察 Tab

| 编号 | 优先级 | 模块 | 场景 | 操作步骤 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-UI-ASSET-001 | P0 | 资产洞察 | 资产列表加载 | 切换到资产洞察 Tab | 展示资产名称、数据源、双分、活跃/超时、覆盖维度、最近执行 |
| QM-UI-ASSET-002 | P0 | 资产洞察 | 客户端分页 | 资产超过 10 条 | 显示分页组件，可切换页码和 pageSize |
| QM-UI-ASSET-003 | P1 | 资产洞察 | 仅看有问题资产 | 勾选快捷过滤 | 列表刷新，分页回到第一页 |
| QM-UI-ASSET-004 | P1 | 资产洞察 | 仅看低覆盖资产 | 勾选快捷过滤 | 列表刷新，分页回到第一页 |
| QM-UI-ASSET-005 | P1 | 资产洞察 | 双快捷过滤 | 同时勾选两个快捷过滤 | 列表显示交集结果 |
| QM-UI-ASSET-006 | P1 | 资产洞察 | 覆盖维度标签 | 资产有多个覆盖维度 | 标签换行自然，不挤压操作区 |
| QM-UI-ASSET-007 | P1 | 资产洞察 | 无覆盖维度 | 资产无覆盖维度 | 显示“未覆盖” |
| QM-UI-ASSET-008 | P1 | 资产洞察 | 最近执行状态 | 资产有最近运行 | 状态 pill 和时间展示正确 |
| QM-UI-ASSET-009 | P1 | 资产洞察 | 无运行资产 | 质量任务未运行 | 最近执行显示“无运行”或空时间 |
| QM-UI-ASSET-010 | P0 | 资产抽屉 | 打开资产抽屉 | 点击资产名称 | 抽屉标题为资产洞察，展示双分、活跃问题、覆盖率等摘要 |
| QM-UI-ASSET-011 | P1 | 资产抽屉 | 资产趋势 | 抽屉中查看资产趋势 | 有数据展示图表，无数据展示空态 |
| QM-UI-ASSET-012 | P1 | 资产抽屉 | 维度覆盖与风险 | 查看覆盖维度和风险维度 | 覆盖维度来自绑定任务，风险维度来自活跃问题 |
| QM-UI-ASSET-013 | P0 | 资产抽屉 | 当前活跃问题 | 抽屉中查看活跃问题表 | 展示问题、级别、状态、最近出现 |
| QM-UI-ASSET-014 | P1 | 资产抽屉 | 从资产打开问题 | 点击活跃问题 | 打开问题处置抽屉 |
| QM-UI-ASSET-015 | P0 | 资产抽屉 | 已绑定质量任务 | 查看已绑定质量任务表 | 展示任务、规则、维度/粒度、最近运行 |
| QM-UI-ASSET-016 | P1 | 资产抽屉 | 质量任务跳转 | 点击任务名称 | 跳转对应质量任务编辑页 |
| QM-UI-ASSET-017 | P1 | 资产抽屉 | 质量规则跳转 | 点击规则名称 | 跳转对应质量规则编辑页 |
| QM-UI-ASSET-018 | P0 | 资产抽屉 | 最近运行日志 | 点击最近运行状态 | 打开统一质量任务日志抽屉 |
| QM-UI-ASSET-019 | P0 | 资产抽屉 | 最近失败证据 | 查看最近失败证据表 | 展示任务/规则、摘要、结束时间、日志入口 |
| QM-UI-ASSET-020 | P1 | 资产抽屉 | 字段级问题分组 | 存在字段级问题 | 按字段折叠分组，可展开查看问题 |
| QM-UI-ASSET-021 | P1 | 资产抽屉 | 无字段级问题 | 不存在字段级问题 | 显示“暂无字段级问题” |
| QM-UI-ASSET-022 | P2 | 资产抽屉 | 抽屉宽度 | 窄屏打开抽屉 | 内容可滚动，关键按钮不丢失 |

### E. 问题中心 Tab

| 编号 | 优先级 | 模块 | 场景 | 操作步骤 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-UI-ISSUE-001 | P0 | 问题中心 | 问题列表加载 | 切换到问题中心 Tab | 展示问题编号、严重级别、状态、资产、规则/任务、首次/最近、负责人/SLA、最近证据、操作 |
| QM-UI-ISSUE-002 | P0 | 问题中心 | 问题分页 | 问题超过 10 条 | 分页显示，可切换页码和 pageSize |
| QM-UI-ISSUE-003 | P1 | 问题中心 | 严重级别筛选 | 选择严重级别查询 | 列表只展示对应级别 |
| QM-UI-ISSUE-004 | P1 | 问题中心 | 状态筛选 | 选择已解决、误报等状态查询 | 列表只展示对应状态 |
| QM-UI-ISSUE-005 | P1 | 问题中心 | 负责人筛选 | 选择负责人查询 | 列表只展示对应负责人问题 |
| QM-UI-ISSUE-006 | P1 | 问题中心 | SLA 超时样式 | 存在超时问题 | SLA 截止时间使用风险样式展示 |
| QM-UI-ISSUE-007 | P1 | 问题中心 | 最近证据溢出 | 最近证据很长 | 表格 tooltip 可查看完整内容，不撑破表格 |
| QM-UI-ISSUE-008 | P0 | 问题抽屉 | 打开问题抽屉 | 点击问题编号或“处置” | 抽屉显示摘要、处置动作、最近证据、时间线 |
| QM-UI-ISSUE-009 | P0 | 问题抽屉 | 更新负责人 | 选择负责人并保存 | 成功提示，详情和列表刷新，时间线新增事件 |
| QM-UI-ISSUE-010 | P1 | 问题抽屉 | 清空负责人 | 清空负责人并保存 | 成功提示，负责人显示未认领 |
| QM-UI-ISSUE-011 | P0 | 问题抽屉 | 更新状态 | 修改状态为排查中并更新 | 成功提示，状态更新，时间线新增事件 |
| QM-UI-ISSUE-012 | P0 | 问题抽屉 | 标记已解决 | 修改状态为已解决 | 问题从活跃统计中移除，时间线记录 |
| QM-UI-ISSUE-013 | P0 | 问题抽屉 | 标记误报 | 修改状态为误报 | 问题从活跃统计中移除，时间线记录 |
| QM-UI-ISSUE-014 | P1 | 问题抽屉 | 状态备注 | 填写状态变更说明 | 时间线消息包含备注 |
| QM-UI-ISSUE-015 | P0 | 问题抽屉 | 更新严重级别 | 修改严重级别并保存 | 成功提示，严重级别更新，时间线记录 |
| QM-UI-ISSUE-016 | P1 | 问题抽屉 | 严重级别备注 | 填写级别变更说明 | 时间线消息包含备注 |
| QM-UI-ISSUE-017 | P0 | 问题抽屉 | 新增评论 | 输入评论并提交 | 成功提示，时间线新增评论 |
| QM-UI-ISSUE-018 | P1 | 问题抽屉 | 空评论拦截 | 不输入评论直接提交 | 前端提示“请先填写评论内容”，不调用或不成功写入 |
| QM-UI-ISSUE-019 | P0 | 问题抽屉 | 查看关联日志 | 点击查看关联日志 | 打开统一 RunLogDrawer，variant 为质量任务日志 |
| QM-UI-ISSUE-020 | P1 | 问题抽屉 | 无运行记录日志按钮 | 问题无 lastRunRecordId | 日志按钮禁用 |
| QM-UI-ISSUE-021 | P1 | 问题抽屉 | 当前证据 JSON | 查看最近证据区域 | JSON 格式缩进展示，异常 JSON 不导致页面崩溃 |
| QM-UI-ISSUE-022 | P1 | 问题抽屉 | 时间线展示 | 问题有多条时间线 | 时间线展示标题、消息、操作者、时间 |
| QM-UI-ISSUE-023 | P1 | 问题中心 | 从问题打开资产 | 点击资产名称 | 打开资产抽屉并加载该资产详情 |
| QM-UI-ISSUE-024 | P2 | 问题中心 | 列表固定操作列 | 横向滚动表格 | 操作列固定在右侧，处置和日志按钮可见 |

### F. 日志抽屉与跨模块一致性

| 编号 | 优先级 | 模块 | 场景 | 操作步骤 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-UI-LOG-001 | P0 | 日志 | 问题列表查看日志 | 点击问题行“日志” | 打开统一日志抽屉，不跳转新页面 |
| QM-UI-LOG-002 | P0 | 日志 | 问题抽屉查看日志 | 点击“查看关联日志” | 打开同一个日志抽屉组件 |
| QM-UI-LOG-003 | P0 | 日志 | 资产抽屉查看日志 | 点击最近运行或失败证据日志 | 打开同一个日志抽屉组件 |
| QM-UI-LOG-004 | P1 | 日志 | 下载入口 | 日志抽屉打开后查看操作 | 保留下载入口 |
| QM-UI-LOG-005 | P1 | 日志 | 不展示采集指标摘要 | 查看质量任务日志 | 不显示采集任务专属指标摘要 |
| QM-UI-LOG-006 | P1 | 日志 | 日志为空 | 运行记录无日志内容 | 展示空态或提示，不报错 |

### G. 空态、异常与响应式

| 编号 | 优先级 | 模块 | 场景 | 操作步骤 | 预期结果 |
| --- | --- | --- | --- | --- | --- |
| QM-UI-STATE-001 | P0 | 空态 | 无质量任务 | 当前项目无质量任务进入页面 | 总览、资产、问题均显示空态，不白屏 |
| QM-UI-STATE-002 | P1 | 空态 | 筛选无结果 | 选择不存在匹配组合查询 | 图表和表格显示空态，无 JS 错误 |
| QM-UI-STATE-003 | P1 | 异常 | 总览接口失败 | 模拟 dashboard 接口 500 | 显示错误提示，其他区域不崩溃 |
| QM-UI-STATE-004 | P1 | 异常 | 资产接口失败 | 模拟 assets 接口 500 | 显示错误提示，页面可继续操作 |
| QM-UI-STATE-005 | P1 | 异常 | 问题接口失败 | 模拟 issues 接口 500 | 显示错误提示，页面可继续操作 |
| QM-UI-STATE-006 | P1 | 异常 | 处置接口失败 | 模拟状态保存失败 | 显示错误提示，抽屉原数据不被错误覆盖 |
| QM-UI-STATE-007 | P1 | 响应式 | 中等宽度 | 浏览器宽度约 1180px | 图表和算法卡自然变为单列 |
| QM-UI-STATE-008 | P1 | 响应式 | 手机宽度 | 浏览器宽度约 760px | 筛选区、按钮、抽屉内容不遮挡 |
| QM-UI-STATE-009 | P1 | 表格 | 横向滚动 | 小屏查看问题表格 | 不出现双横向滚动条，固定列可用 |
| QM-UI-STATE-010 | P2 | 可用性 | 快速重复点击刷新 | 连续点击刷新 | loading 正常，不出现重复异常提示 |
| QM-UI-STATE-011 | P2 | 可用性 | 页面语言 | 查看所有标题、按钮、提示 | 文案为中文，未出现未翻译 key |
| QM-UI-STATE-012 | P2 | 可用性 | 输入框/下拉浮动提示 | 查看筛选和抽屉表单 | 已有 label 的位置不冲突，placeholder/floating label 不遮挡内容 |

## 端到端联动测试用例

| 编号 | 优先级 | 场景 | 操作步骤 | 预期结果 |
| --- | --- | --- | --- | --- |
| QM-E2E-001 | P0 | 新告警进入指标页 | 创建质量任务，配置告警，手动运行命中告警，进入质量指标 | 总览活跃问题增加，问题中心出现告警问题，资产列表风险上升 |
| QM-E2E-002 | P0 | 无告警运行不生成问题 | 创建质量任务，运行成功且不触发告警 | 不新增问题，健康分保持高值 |
| QM-E2E-003 | P0 | 执行失败进入问题中心 | 构造 SQL 错误质量任务并运行 | 问题中心新增执行失败问题，严重级别为高 |
| QM-E2E-004 | P0 | 重复运行同一告警不重复造单 | 同一质量任务连续触发相同告警 | 问题列表仍为一条，出现次数和最近出现更新 |
| QM-E2E-005 | P0 | 恢复不自动关闭 | 先触发告警，再修复数据并运行成功 | 问题时间线出现恢复信号，但状态仍需人工关闭 |
| QM-E2E-006 | P1 | 人工关闭后再次告警 reopen | 将问题标记已解决，再触发相同告警 | 原问题重新打开，reopenCount 增加 |
| QM-E2E-007 | P1 | 资产详情联动 | 告警问题生成后打开对应资产 | 资产抽屉展示活跃问题、失败证据、字段分组和日志入口 |
| QM-E2E-008 | P1 | 处置后总览刷新 | 在问题抽屉将问题标记已解决 | 总览活跃问题、受影响资产、风险指数刷新 |
| QM-E2E-009 | P1 | 质量任务日志联动 | 从问题中心打开日志 | 日志内容对应问题最近运行记录 |
| QM-E2E-010 | P1 | 多维度过滤一致性 | 同时设置时间、数据源、维度、粒度筛选 | 总览、资产、问题使用相同查询上下文 |
| QM-E2E-011 | P1 | 跨项目隔离 | A 项目触发问题，切换 B 项目 | B 项目质量指标不展示 A 项目问题 |
| QM-E2E-012 | P2 | 快照沉淀 | 查询总览后再次查询 | 快照表同一天不重复膨胀，页面指标不受重复查询污染 |

## 性能与稳定性测试用例

| 编号 | 优先级 | 场景 | 操作步骤 | 预期结果 |
| --- | --- | --- | --- | --- |
| QM-PERF-001 | P1 | 总览大数据量 | 当前项目 1000+ 运行记录、1000+ 问题 | 总览接口在可接受时间内返回，页面不卡死 |
| QM-PERF-002 | P1 | 问题列表大数据量 | 当前项目 1000+ 问题 | 前端分页可操作，滚动和切页稳定 |
| QM-PERF-003 | P1 | 资产列表大数据量 | 当前项目 100+ 已纳管资产 | 表格渲染稳定，分页正确 |
| QM-PERF-004 | P2 | 大时间窗口 | 查询 30 天窗口 | 趋势图点位可读，不出现图表异常 |
| QM-PERF-005 | P2 | 多事件时间线 | 单个问题 100+ 时间线事件 | 问题抽屉可打开，时间线可滚动 |
| QM-PERF-006 | P2 | 频繁切换 Tab | 快速切换总览、资产、问题 | loading 状态正确，不显示错位数据 |
| QM-PERF-007 | P2 | 并发处置和刷新 | 一边处置问题，一边刷新总览 | 页面最终展示最新状态，不出现未捕获异常 |

## 通过标准

- P0 用例必须全部通过。
- P1 用例不允许出现数据错误、权限越界、页面白屏、接口 500。
- P2 用例若不通过，需要记录影响范围、复现条件和是否影响主流程。
- 所有空态必须有明确提示，不能出现 `undefined`、`NaN`、未翻译 key 或控制台致命错误。
- 所有问题处置动作必须写入时间线，并在总览、资产、问题列表中保持一致。
- 日志查看行为必须与采集任务日志保持一致，使用统一抽屉并保留下载入口。
