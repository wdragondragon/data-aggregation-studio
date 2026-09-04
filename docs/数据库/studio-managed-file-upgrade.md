# Studio 托管文件升级说明

## 目标

Kafka、TBDS HDFS/HDFS3、Hive3 的 Keytab、krb5.conf 和 Hadoop site XML 改为 Studio 托管文件。数据源元数据只保存：

```text
managed-file://<fileId>
```

文件由 Studio 加密写入内置 OSS/MinIO。连接测试、模型发现、预览、批任务、流任务、数据开发、文件传输和 Flink 运行时在加载 DataAggregation 插件前下载到本地缓存，并把普通本地路径传给插件。数据源插件不读取 `managed-file://`，也不连接 Studio OSS。

## 数据库变更

执行：

```text
backend/studio-server/src/main/resources/update/20260902/20260902-managed-files.sql
```

脚本新增：

- `meta_field_definition.file_policy_code`
- `so_pf_managed_file`
- `so_pf_managed_file_ref`
- `so_pf_managed_file_lease`
- `so_pf_managed_file_audit`

并把 Kafka、TBDS HDFS/HDFS3、Hive3 的认证文件字段改成 `MANAGED_FILE` 元模型组件。

## 发布顺序

1. 保持 `STUDIO_MANAGED_FILE_ENABLED=false`，先部署 Server、Worker、Flink 和 DataAggregation 插件兼容代码。
2. 执行 `20260902-managed-files.sql`。
3. 在 Server、全部 Worker和 Flink 运行节点配置相同的 `STUDIO_ENCRYPTION_SECRET`。
4. 在上述节点配置相同的 `STUDIO_OBJECT_*` 内置 OSS/MinIO 参数，并验证存储桶访问。
5. 管理员调用迁移检查接口，逐个重新上传历史本地认证文件。
6. 所有节点同时设置 `STUDIO_MANAGED_FILE_ENABLED=true` 并滚动重启。

功能关闭时历史本地路径保持兼容；关闭状态下如果运行配置已经包含 `managed-file://`，运行节点会在插件加载前明确失败。功能开启后，历史本地路径不能再保存、测试或运行。

## 关键配置

```text
STUDIO_MANAGED_FILE_ENABLED=true
STUDIO_ENCRYPTION_SECRET=<所有运行节点一致的非默认密钥>
STUDIO_MANAGED_FILE_OBJECT_PREFIX=studio/managed-files
STUDIO_MANAGED_FILE_CACHE_DIR=./runtime/managed-files
STUDIO_MANAGED_FILE_CACHE_MAX_BYTES=1073741824
STUDIO_MANAGED_FILE_CACHE_IDLE_HOURS=24
STUDIO_MANAGED_FILE_UNBOUND_RETENTION_HOURS=24
STUDIO_MANAGED_FILE_LEASE_TTL_SECONDS=300
STUDIO_MANAGED_FILE_LEASE_HEARTBEAT_SECONDS=60
STUDIO_OBJECT_PROVIDER=MINIO
STUDIO_OBJECT_ENDPOINT=<内置对象存储地址>
STUDIO_OBJECT_ACCESS_KEY=<访问凭据>
STUDIO_OBJECT_SECRET_KEY=<访问凭据>
STUDIO_OBJECT_BUCKET=<存储桶>
```

Keytab 本地缓存权限为 Unix `0600`；目录为 `0700`。Windows 使用当前运行用户的单一 ACL。敏感文件权限无法收口时任务失败，不把文件交给插件。

## 回滚

关闭 `STUDIO_MANAGED_FILE_ENABLED` 可恢复历史本地路径兼容，但已保存为 `managed-file://` 的数据源不能在关闭托管文件的运行节点执行。数据库表和对象文件不删除；完成问题修复后重新启用即可。
