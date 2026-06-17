# 数据源连接状态测试用例

## API 用例

| 编号 | 场景 | 前置条件 | 操作 | 预期 |
|---|---|---|---|---|
| DS-CS-API-001 | 新建数据源默认状态 | 当前项目可新建数据源 | 调用 `POST /api/v1/datasources` 保存数据源 | 返回 `connectionStatus=UNKNOWN`，最近测试时间、消息和耗时为空 |
| DS-CS-API-002 | 已保存数据源测试成功 | 数据源已启用且纳入管理，连接参数可用 | 调用 `POST /api/v1/datasources/{id}/test` | 返回 `status=AVAILABLE`、`durationMs`，再次查询列表可见 `connectionStatus=AVAILABLE` |
| DS-CS-API-003 | 已保存数据源测试失败 | 数据源已启用且纳入管理，连接参数不可用 | 调用 `POST /api/v1/datasources/{id}/test` | 返回 `status=UNAVAILABLE` 和失败消息，再次查询列表可见失败快照 |
| DS-CS-API-004 | 当前表单测试不写库 | 编辑抽屉存在未保存表单 | 调用 `POST /api/v1/datasources/test` | 返回测试结果，但 `GET /api/v1/datasources` 中原数据源状态不变化 |
| DS-CS-API-005 | 连接参数变更切换指纹 | 数据源已有 `AVAILABLE` 或 `UNAVAILABLE` 快照 | 修改 `typeCode` 或 `technicalMetadata` 后保存 | 返回新的 `connectionFingerprint`，无对应历史时展示 `connectionStatus=UNKNOWN` |
| DS-CS-API-006 | 非连接字段变更保留状态 | 数据源已有连接状态快照 | 仅修改名称、业务元数据、启用状态或纳管状态后保存 | 最近连接测试快照保持不变 |
| DS-CS-API-007 | 相同连接去重 | 同租户两个项目配置相同物理连接 | 分别查询两个数据源列表项 | 两者 `connectionFingerprint` 相同，连接状态和 `recentConnectionTests` 一致 |
| DS-CS-API-008 | 同连接检测中 | 某 fingerprint 已有手动或定时检测运行中 | 调用另一个同 fingerprint 数据源的 `POST /test` | 最多等待后返回 `testing=true`，不新增重复历史 |
| DS-CS-API-009 | 历史明细查询 | 已保存数据源最近 7 天有多次检测 | 调用 `GET /api/v1/datasources/{id}/connection-history?days=7&limit=1000` | 按检测结束时间倒序返回状态、时间、触发方式、耗时和消息 |
| DS-CS-API-010 | 当前表单测试不写历史 | 编辑抽屉使用当前表单测试 | 调用 `POST /api/v1/datasources/test` 后查询历史 | 历史记录不新增 |
| DS-CS-API-011 | 超时覆盖校验 | 数据源填写检测超时 | 保存 `manualConnectionTestTimeoutSeconds` 或 `scheduledConnectionTestTimeoutSeconds` | 1 到 120 秒可保存，超出范围返回校验错误 |

## UI 用例

| 编号 | 场景 | 前置条件 | 操作 | 预期 |
|---|---|---|---|---|
| DS-CS-UI-001 | 列表展示未检测 | 数据源从未执行保存后测试 | 打开数据源中心 | 连接状态列展示“未检测” |
| DS-CS-UI-002 | 列表原地测试 | 数据源已启用且纳入管理 | 点击列表行“测试” | 不打开编辑抽屉，测试完成后当前行状态、时间和消息刷新 |
| DS-CS-UI-003 | 不可测试展示 | 数据源禁用或未纳入管理 | 打开数据源中心 | 连接状态列展示“不可测试”，测试动作不可点击 |
| DS-CS-UI-004 | 失败消息可查看 | 数据源最近测试失败且有错误消息 | 悬停连接状态列的消息文本 | 展示完整失败消息 |
| DS-CS-UI-005 | 最近 10 次趋势 | 数据源存在成功和失败检测历史 | 打开数据源中心 | 连接状态列展示 10 个点，不足 10 条以灰点补齐，绿色代表可用、红色代表不可用 |
| DS-CS-UI-006 | 历史弹窗 | 数据源存在历史记录 | 点击连接状态列的点状趋势 | 弹窗展示最近 7 天具体检测时间、状态、触发方式、耗时和消息 |
| DS-CS-UI-007 | 检测中展示 | 同 fingerprint 正在检测 | 打开数据源中心或手动测试等待超时 | 状态展示“检测中”，不把历史快照覆盖为失败 |
| DS-CS-UI-008 | 超时配置 | 打开数据源编辑抽屉 | 修改手动/定时检测超时 | 表单限制 1 到 120 秒，保存后列表数据源返回对应配置 |
