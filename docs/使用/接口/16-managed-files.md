# 托管文件接口

接口根路径：`/api/v1/managed-files`。除文件下载外均使用 Studio `Result<T>`；分页结果位于 `data.items`，并包含 `pageNo`、`pageSize`、`total`。

## 接口清单

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/upload` | `multipart/form-data` 上传，参数为 `file`、`policyCode` |
| POST | `/queryPage?pageNum=&pageSize=&policyCode=&status=` | 查询当前项目文件 |
| GET | `/{id}` | 查询文件元数据 |
| GET | `/{id}/references` | 查询业务引用 |
| GET | `/{id}/download` | 管理员下载明文文件 |
| DELETE | `/{id}` | 请求删除无引用文件 |
| POST | `/audits/queryPage?pageNum=&pageSize=&fileId=` | 查询审计记录 |
| GET | `/migration-issues` | 管理员查询仍使用历史本地路径的数据源字段 |

可用策略：

```text
KERBEROS_KEYTAB
KERBEROS_KRB5_CONF
HADOOP_SITE_XML
GENERAL_CONFIG
GENERAL_ATTACHMENT
```

上传成功后，前端向数据源字段写入：

```text
managed-file://<返回的 id>
```

`GET /migration-issues` 返回示例：

```json
{
  "success": true,
  "data": [
    {
      "datasourceId": 101,
      "datasourceName": "Kafka Kerberos",
      "datasourceTypeCode": "kafka",
      "fieldKeys": ["kerberosKeytabFilePath", "krb5Conf"]
    }
  ]
}
```

响应不会返回旧路径、对象桶、对象键、加密 IV、本地缓存路径或 OSS 凭据。

只有 `SUPER_ADMIN`、`TENANT_ADMIN`、`PROJECT_ADMIN` 可以下载认证文件或查询迁移问题。存在业务引用时删除返回冲突并包含引用摘要；替换文件应先上传新文件，再保存数据源完成引用切换。
