# MySQL -> FTP 与 aliyun OSS/MinIO EFile UI 测试结果

测试日期：2026-08-24  
测试入口：Data Aggregation Studio 页面  
测试上下文：Default Tenant / Default Project，运行集群为本地集群（DEFAULT-LOCAL）

## 1. 范围与边界

本轮补测覆盖 FTP 和 aliyun oss 两类目标源。`aliyun oss` 在 Studio 页面显示为 `minio` 数据源，任务运行消息中的 Writer 类型为 `minio`，因此本文将其记为“MinIO/OSS”。

所有模型创建、任务配置、上线、触发、运行记录查看、目录浏览和文件下载均通过 Studio 页面完成。没有调用 REST API，没有使用数据库客户端、FTP/SFTP/OSS/MinIO 客户端或 SDK，没有修改 MySQL 源表数据。异常数据均由只读自定义 SQL 派生。

## 2. 测试资产

目标目录：

- FTP：`/upload/codex/mysql-efile-ui-ftp-20260824`
- aliyun oss / MinIO：`/upload/codex/mysql-efile-ui-minio-20260824`

每类目标均保留以下模型和任务：

| 用途 | FTP 模型 | MinIO/OSS 模型 | 文件 |
| --- | --- | --- | --- |
| 单表正常 | `UI_EFILE_FTP_SINGLE_20260824` | `UI_EFILE_MINIO_SINGLE_20260824` | `single.efile` |
| JOIN | `UI_EFILE_FTP_JOIN_20260824` | `UI_EFILE_MINIO_JOIN_20260824` | `join.efile` |
| NULL 空列 | `UI_EFILE_FTP_NULL_20260824` | `UI_EFILE_MINIO_NULL_20260824` | `null_value.efile` |
| 普通 TAG | `UI_EFILE_FTP_TAG_20260824` | `UI_EFILE_MINIO_TAG_20260824` | `tag_conflict.efile` |
| TAG 提供 planDate | `UI_EFILE_FTP_PLAN_TAG_20260824` | `UI_EFILE_MINIO_PLAN_TAG_20260824` | `plan_tag.efile` |
| planDate 冲突 | `UI_EFILE_FTP_PLAN_CONFLICT_20260824` | `UI_EFILE_MINIO_PLAN_CONFLICT_20260824` | `plan_conflict.efile` |
| 无 planDate | `UI_EFILE_FTP_NO_PLAN_20260824` | `UI_EFILE_MINIO_NO_PLAN_20260824` | `no_plan.efile` |

模型字段均按目标顺序配置，字段下标从 0 连续递增，模型详情页可看到字段备注“测试标识”“测试参数”“计划日期”。

## 3. 用例结果

### 3.1 FTP

| 用例 | 任务 | 实际状态 | 指标 | 日志摘要 |
| --- | --- | --- | --- | --- |
| TC01 | `UI_EFILE_FTP_TC01_20260824` | 成功 | 采集 1，成功 1，失败 0 | `mysql8 -> ftp`，完成 |
| TC02 | `UI_EFILE_FTP_TC02_20260824` | 成功 | 采集 1，成功 1，失败 0 | `mysql8 -> ftp`，完成 |
| TC03 | `UI_EFILE_FTP_TC03_20260824` | 成功 | 采集 1，成功 1，失败 0 | `mysql8 -> ftp`，完成 |
| TC04 | `UI_EFILE_FTP_TC04_20260824` | 失败（符合预期） | 采集 1，成功 0，失败 1 | `Target file already exists` |
| TC05 | `UI_EFILE_FTP_TC05_20260824` | 失败（符合预期） | 采集 2，成功 0，失败 2 | `EFile tag column has inconsistent values: tag_code` |
| TC06 | `UI_EFILE_FTP_TC06_20260824` | 失败（符合预期） | 采集 0，成功 0，失败 0 | `Append writeMode only supports csv and jsonl.` |
| TC07 | 未保存任务 | 页面报错（符合预期） | 不适用 | `SQL execution failed: Unknown column 'definitely_missing_column' in 'field list'` |
| TC08 | `UI_EFILE_FTP_TC08_20260824` | 成功 | 采集 1，成功 1，失败 0 | `mysql8 -> ftp`，完成 |
| TC09 | `UI_EFILE_FTP_TC09_20260824` | 成功（不符合预期） | 采集 1，成功 1，失败 0 | 未触发模型/TAG 冲突失败 |
| TC10 | `UI_EFILE_FTP_TC10_20260824` | 成功（不符合预期） | 采集 1，成功 1，失败 0 | 未省略空 planDate，仍使用运行日 |

运行记录页显示 FTP 任务均由 `studio-online-worker-01` 在 `本地集群 (DEFAULT-LOCAL)` 执行。页面只显示序号、任务名、时间和指标，不展示可复制的运行 ID；因此本次以任务名、开始时间和页面序号作为运行记录索引。

关键时间：TC01 09:01:49，TC02 09:02:18，TC03 09:02:24，TC04 09:02:31，TC05 09:02:36，TC06 09:02:41，TC08 09:02:45，TC09 09:02:52，TC10 09:02:58。

### 3.2 aliyun oss / MinIO

| 用例 | 任务 | 实际状态 | 指标 | 日志摘要 |
| --- | --- | --- | --- | --- |
| TC01 | `UI_EFILE_MINIO_TC01_20260824` | 成功 | 采集 1，成功 1，失败 0 | `mysql8 -> minio`，完成 |
| TC02 | `UI_EFILE_MINIO_TC02_20260824` | 成功 | 采集 1，成功 1，失败 0 | `mysql8 -> minio`，完成 |
| TC03 | `UI_EFILE_MINIO_TC03_20260824` | 成功 | 采集 1，成功 1，失败 0 | `mysql8 -> minio`，完成 |
| TC04 | `UI_EFILE_MINIO_TC04_20260824` | 失败（符合预期） | 采集 1，成功 0，失败 1 | `Target file already exists` |
| TC05 | `UI_EFILE_MINIO_TC05_20260824` | 失败（符合预期） | 采集 2，成功 0，失败 2 | `EFile tag column has inconsistent values: tag_code` |
| TC06 | `UI_EFILE_MINIO_TC06_20260824` | 失败（符合预期） | 采集 0，成功 0，失败 0 | `Append writeMode only supports csv and jsonl.` |
| TC07 | 未保存任务 | 页面报错（符合预期） | 不适用 | `SQL execution failed: Unknown column 'definitely_missing_column' in 'field list'` |
| TC08 | `UI_EFILE_MINIO_TC08_20260824` | 成功 | 采集 1，成功 1，失败 0 | `mysql8 -> minio`，完成 |
| TC09 | `UI_EFILE_MINIO_TC09_20260824` | 成功（不符合预期） | 采集 1，成功 1，失败 0 | 未触发模型/TAG 冲突失败 |
| TC10 | `UI_EFILE_MINIO_TC10_20260824` | 成功（不符合预期） | 采集 1，成功 1，失败 0 | 未省略空 planDate，仍使用运行日 |

MinIO/OSS 任务同样由 `studio-online-worker-01` 在 `本地集群 (DEFAULT-LOCAL)` 执行。关键时间：TC01 09:03:13，TC02 09:03:19，TC03 09:03:23，TC04 09:03:28，TC05 09:03:35，TC06 09:03:40，TC08 09:03:47，TC09 09:03:51，TC10 09:03:56。

## 4. 目标文件验收

### 4.1 Studio 文件列表

FTP 页面在 `/upload/codex/mysql-efile-ui-ftp-20260824` 显示：

| 文件 | 大小 | 页面修改时间 |
| --- | ---: | --- |
| `single.efile` | 189 B | 2026-08-24 09:01:00 |
| `join.efile` | 221 B | 2026-08-24 09:02:00 |
| `null_value.efile` | 214 B | 2026-08-24 09:02:00 |
| `plan_tag.efile` | 203 B | 2026-08-24 09:02:00 |
| `plan_conflict.efile` | 203 B | 2026-08-24 09:02:00 |
| `no_plan.efile` | 190 B | 2026-08-24 09:03:00 |

`tag_conflict.efile` 未出现，符合 TC05 失败后不应留下有效文件的预期。TC04 失败后 `single.efile` 仍为原 189 B 和原成功运行时间。

aliyun oss / MinIO 页面在 `/upload/codex/mysql-efile-ui-minio-20260824` 显示同样的 6 个对象，大小分别为 189 B、221 B、214 B、203 B、203 B、190 B；对象时间约为 09:03:14 至 09:03:57。`tag_conflict.efile` 未出现。TC04 失败后 `single.efile` 内容和大小未改变。

文件均通过页面“下载”操作取得，下载产物从浏览器 Downloads 目录读取。多选下载由 Studio 页面打包为 ZIP，ZIP 内的 EFile 内容已逐个展开核对。

### 4.2 内容核对

FTP 与 MinIO/OSS 的单表、JOIN、NULL 文件内容一致，差异仅在目标适配器和对象元数据。

正常单表文件：

```text
<! Entity='DA_STUDIO' dataTime='20260824_00:00:00' type='MYSQL_SINGLE' !>
<AGG_TEST1::agg_test1 planDate='20260824' >
@ id test_param
// id test_param
# 1 123
</AGG_TEST1::agg_test1>
```

JOIN 数据行为 `# 1 123 123`，字段顺序为 `left_id、left_value、right_value`；NULL 文件数据行为 `# 1  123`，中间空列位置未偏移。

TAG/planDate 相关文件实际内容：

```text
<AGG_PLAN::agg_plan planDate='20260824' planDate='20260824' >
```

`plan_tag.efile` 出现重复 `planDate`，TC08 未达到预期。

```text
<AGG_PLAN::agg_plan planDate='20260824' planDate='20260823' >
```

`plan_conflict.efile` 同时写入模型值和 TAG 值，TC09 未失败，且生成了可被误认为成功的文件。

```text
<AGG_TEST1::agg_test1 planDate='20260824' >
```

`no_plan.efile` 的任务预览中 `efile` 没有 `planDate`，但 Writer 仍填充了运行日 `20260824`，TC10 未达到“完全省略 planDate”的预期。

所有成功文件的 `tableCode::tableName` 均正确，例如 `AGG_TEST1::agg_test1`、`AGG_JOIN::agg_join`、`AGG_PLAN::agg_plan`。TAG 字段未进入 `@`、`//` 或 `#` 数据列；普通 TAG 不一致用例按预期失败。

## 5. 目标模型回读

分别在 FTP 和 MinIO/OSS 目标模型详情页选择本地集群并刷新样例数据，Reader 均能解析 `single.efile`，样例数据为：

```text
id | test_param
1  | 123
```

进一步刷新了两类目标的 JOIN、NULL、PLAN_TAG 和 NO_PLAN 模型：

- JOIN 样例列为 `left_id、left_value、right_value`，数据为 `1、123、123`；
- NULL 样例列为 `id、nullable_value、test_param`，中间 `nullable_value` 单元格为空，后列仍为 `123`；
- PLAN_TAG 和 NO_PLAN 的数据列均能回读为 `id=1、test_param=123`，TAG 字段不作为数据列展示。

因此 FTP 和 MinIO/OSS Reader 的基本 EFile 解析、字段顺序和空列定位均通过；planDate 重复属性未阻止样例数据读取，但仍属于生成格式缺陷。

模型详情页的字段元数据中确实保存了中文备注“测试标识”“测试参数”。但是下载的 EFile `//` 说明行仍为 `// id test_param`，没有使用模型备注。该现象在 FTP 和 MinIO/OSS 两边一致。

TC09 编辑任务的 Studio 确认页还显示：

- Writer `efile.planDate` 为 `20260824`；
- 字段 `planDate` 以 `sourceKind=TAG` 进入 `columns`；
- Writer 预览没有丢失模型值或 TAG 字段。

TC10 确认页显示 Writer `efile` 完全没有 `planDate`，但实际文件仍出现运行日 `planDate='20260824'`，可定位为 Writer 运行时兜底行为。

## 6. 缺陷与影响

### D1：Writer 未统一消费 planDate，导致重复、冲突未失败和空值兜底

复现步骤：

1. 使用 `UI_EFILE_*_PLAN_TAG_20260824`，模型 planDate 为空，SQL 返回 `planDate='20260824'`。
2. 使用 `UI_EFILE_*_PLAN_CONFLICT_20260824`，模型 planDate 为 `20260824`，SQL 返回 `planDate='20260823'`。
3. 使用 `UI_EFILE_*_NO_PLAN_20260824`，模型和 SQL 均不提供 planDate。
4. 分别在 FTP 和 aliyun oss/MinIO 触发任务并从 Studio 页面下载文件。

实际结果：两类适配器均分别生成重复 planDate、冲突 planDate 文件，并在无值时填充运行日。预期应为只输出一次、冲突失败、两侧为空时省略属性。影响范围是公共 EFile Writer 逻辑，不是 FTP 或 MinIO 连接适配器。

建议修复方向：在 `CommonFileTableWriter` 统一解析模型值和已校验的 TAG 值，消费后从普通 TAG 集合排除 `planDate`；冲突时抛出包含 model/tag 值的异常；删除当前运行日隐式兜底；空值时不要调用生成 `planDate` 属性。

### D2：EFile `//` 说明行未使用模型字段备注

复现步骤：模型详情页将 `id`、`test_param` 的备注配置为“测试标识”“测试参数”，分别通过 FTP 和 MinIO/OSS 生成单表 EFile。

实际结果：模型详情页可见中文备注，但文件内容为 `// id test_param`。预期应为 `// 测试标识 测试参数`。该现象跨 FTP 和 MinIO/OSS 一致，属于公共 Writer/字段装配问题。

### D3：运行记录页不展示可复制的运行 ID

运行记录页能够查看任务名、时间、Worker、状态、指标和消息，但表格 DOM 未提供运行 ID；任务名和时间可作为复查索引。若交付要求必须记录运行 ID，建议在运行记录详情或日志抽屉增加明确 ID 字段。

## 7. 结论

- FTP：TC01、TC02、TC03、TC04、TC05、TC06、TC07、TC08 按预期；TC09、TC10 失败于计划中的 EFile 行为约定；D2 备注问题存在。
- aliyun oss/MinIO：TC01、TC02、TC03、TC04、TC05、TC06、TC07、TC08 按预期；TC09、TC10 与 FTP 相同；D2 备注问题存在。
- FTP 与 MinIO/OSS 的文件内容和错误行为一致，说明本轮主要问题位于公共 EFile Writer/字段装配层，而不是某一文件存储适配器。
- `tableCode::tableName` 约定已正确保留，未删除模型属性；目标模型 Reader 在两类目标源上均可回读成功文件。
- 本轮创建的模型、采集任务、运行记录和目标文件均保留在 Studio 中，未清理既有 SFTP 测试产物。

## 8. 20260829 修复后补测

本节记录动态函数实现及 Server 运行依赖修复后的 Studio UI 补测，覆盖 FTP 和 aliyun oss（页面插件类型为 minio）。所有操作仍通过 Studio 页面完成。

### 8.1 动态函数组合预览

保留任务：`Codex动态函数验证20260828`（`codex_dynfn_0828`）。通过编辑页验证并预览以下表达式：

- 源根路径：`/upload/codex/native-streaming-m7-$getCurrentTime('yyyyMMdd','-2d')`
- 包含 Glob：`**/*.efile`
- 包含正则：`.*/NativeStreaming-M7-KafkaToOss-$getCurrentTime('yyyyMMdd','-2d')\\.efile$`
- 目标根路径：`/upload/codex/dynfn-minio-$getCurrentTime('yyyyMMdd')`
- 目标相对路径模板：`$getCurrentTime('yyyyMM')/${relativePath}/${fileName}`

“校验配置”提示任务配置校验通过；“预览选中文件”得到 1 个文件、187 B，目标路径解析为：

```text
/upload/codex/dynfn-minio-20260829/202608/NativeStreaming-M7-KafkaToOss-20260827.efile
```

这确认了源端日期函数先于文件发现执行，包含 Glob 与包含正则同时生效（AND），正则中的点号转义未被破坏，目标端函数先于 `${relativePath}`/`${fileName}` 替换执行。

### 8.2 FTP 目标运行与文件验收

运行记录：

- 运行 ID：`2093376789411467265`
- 开始/结束：`2026-08-29 00:34:55` / `2026-08-29 00:34:59`
- Worker：`studio-online-worker-01`，实际集群：`DEFAULT-LOCAL`
- 状态：`SUCCESS`；总数 1，成功 1，跳过 0，失败 0；传输 187 B
- 日志摘要：`File transfer completed with status SUCCESS`
- 日志中的目标路径：`/upload/codex/dynfn-20260829/202608/NativeStreaming-M7-KafkaToOss-20260827.efile`

在 Studio“非结构化管理”中选择 `ftp测试源`，浏览目录 `/upload/codex/dynfn-20260829/202608`，看到：

```text
NativeStreaming-M7-KafkaToOss-20260827.efile  187 B  2026-08-29 00:34:00
```

通过页面下载后，文件 SHA-256 为：

```text
7D9DA4C082D6EAD04DDFA5C3AB81F0EBE5B635923D477C6E67AE3299EA1F8FF5
```

内容与源端对象逐字节一致：

```text
<! Entity='demo' dataTime='20260827_12:07:00' type='test' !>
<T01::Data planDate='20260827' >
@ id test_param
// id test_param
# 117 native-streaming-117-server-outage
</T01::Data>
```

此前“动态目录页面为空”的暂定异常已排除；重新在 FTP 页面输入完整目标路径并刷新后文件可浏览、可下载，当前没有证据表明是 FTP Writer 写入失败。

### 8.3 aliyun oss / MinIO 目标运行与文件验收

运行记录：

- 运行 ID：`2093383772273315842`
- 开始/结束：`2026-08-29 01:02:40` / `2026-08-29 01:02:42`
- Worker：`studio-online-worker-01`，实际集群：`DEFAULT-LOCAL`
- 状态：`SUCCESS`；总数 1，成功 1，跳过 0，失败 0；传输 187 B
- 日志摘要：`File transfer completed with status SUCCESS`
- 日志中的源/目标：`sourceType=minio`、`targetType=minio`，目标路径为 `/upload/codex/dynfn-minio-20260829/202608/NativeStreaming-M7-KafkaToOss-20260827.efile`

在 Studio“非结构化管理”中选择 `aliyun oss`，浏览目录 `/upload/codex/dynfn-minio-20260829/202608`，看到对象：

```text
NativeStreaming-M7-KafkaToOss-20260827.efile  187 B  2026-08-29 01:02:41
```

通过页面下载后，源对象与目标对象 SHA-256 均为：

```text
7D9DA4C082D6EAD04DDFA5C3AB81F0EBE5B635923D477C6E67AE3299EA1F8FF5
```

目标对象内容与源对象逐字节一致，`EFile` 头、`tableCode::tableName`、`planDate`、字段行、说明行、数据行和结束标签均保持不变。

### 8.4 补测结论

- FTP：动态源路径、Glob/正则筛选、目标日期目录、`${relativePath}`/`${fileName}` 模板和文件写入均通过；运行 ID `2093376789411467265`。
- aliyun oss/MinIO：同一组合表达式和目标对象写入、Studio 浏览、页面下载及 SHA-256 比对均通过；运行 ID `2093383772273315842`。
- 两类目标的文件内容完全一致，未观察到适配器层差异。
- 20260824 文档中的 D1 是修复前历史记录；本次补测未重新执行 planDate 冲突、空 planDate 和 TAG 多行不一致场景，需以修复后的自动化测试结果和后续专项 UI 回归作为最终判定依据。
