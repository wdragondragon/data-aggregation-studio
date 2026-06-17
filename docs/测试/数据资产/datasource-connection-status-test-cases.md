# 数据源连接状态测试用例

## API 用例

| 编号 | 场景 | 前置条件 | 操作 | 预期 |
|---|---|---|---|---|
| DS-CS-API-001 | 新建数据源默认状态 | 当前项目可新建数据源 | 调用 `POST /api/v1/datasources` 保存数据源 | 返回 `connectionStatus=UNKNOWN`，最近测试时间、消息和耗时为空 |
| DS-CS-API-002 | 已保存数据源测试成功 | 数据源已启用且纳入管理，连接参数可用 | 调用 `POST /api/v1/datasources/{id}/test` | 返回 `status=AVAILABLE`、`durationMs`，再次查询列表可见 `connectionStatus=AVAILABLE` |
| DS-CS-API-003 | 已保存数据源测试失败 | 数据源已启用且纳入管理，连接参数不可用 | 调用 `POST /api/v1/datasources/{id}/test` | 返回 `status=UNAVAILABLE` 和失败消息，再次查询列表可见失败快照 |
| DS-CS-API-004 | 当前表单测试不写库 | 编辑抽屉存在未保存表单 | 调用 `POST /api/v1/datasources/test` | 返回测试结果，但 `GET /api/v1/datasources` 中原数据源状态不变化 |
| DS-CS-API-005 | 连接参数变更重置状态 | 数据源已有 `AVAILABLE` 或 `UNAVAILABLE` 快照 | 修改 `typeCode`、`schemaVersionId` 或 `technicalMetadata` 后保存 | 返回和列表均展示 `connectionStatus=UNKNOWN` |
| DS-CS-API-006 | 非连接字段变更保留状态 | 数据源已有连接状态快照 | 仅修改名称、业务元数据、启用状态或纳管状态后保存 | 最近连接测试快照保持不变 |

## UI 用例

| 编号 | 场景 | 前置条件 | 操作 | 预期 |
|---|---|---|---|---|
| DS-CS-UI-001 | 列表展示未检测 | 数据源从未执行保存后测试 | 打开数据源中心 | 连接状态列展示“未检测” |
| DS-CS-UI-002 | 列表原地测试 | 数据源已启用且纳入管理 | 点击列表行“测试” | 不打开编辑抽屉，测试完成后当前行状态、时间和消息刷新 |
| DS-CS-UI-003 | 不可测试展示 | 数据源禁用或未纳入管理 | 打开数据源中心 | 连接状态列展示“不可测试”，测试动作不可点击 |
| DS-CS-UI-004 | 失败消息可查看 | 数据源最近测试失败且有错误消息 | 悬停连接状态列的消息文本 | 展示完整失败消息 |
