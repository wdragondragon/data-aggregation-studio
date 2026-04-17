# 数据服务模块二轮复测结果

## 1. 复测结论

本次根据首轮报告 `data-service-test-result-20260416.md` 中的问题完成修复并进行二轮回归。

复测结论：通过。

| 首轮问题 | 修复状态 | 复测结论 |
| --- | --- | --- |
| `DS-API-001`：Header 参数名不支持连字符，`X-City` 无法配置 | 已修复 | 通过 |
| `DS-UI-001`：调试页返回结果只展示业务 data，与“统一 Result 包装”文案不一致 | 已修复 | 通过 |

## 2. 修复内容

### 2.1 Header 参数名校验

后端保存发布参数映射时，按参数位置区分校验规则：

| 参数位置 | 校验规则 |
| --- | --- |
| `HEADER` | 使用 HTTP Header token 规则，支持 `X-City`、`X-Request-Id` 等常见 Header 名。 |
| `QUERY` / `BODY` | 继续使用普通标识符规则，避免放宽 Query/Body 入参命名边界。 |

涉及文件：

| 文件 | 位置 |
| --- | --- |
| `backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataServiceService.java` | 新增 `HTTP_HEADER_NAME`，并新增 `validateFrontendParamName`。 |

### 2.2 调试响应展示

前端 SDK 的数据服务 `debug` 方法改为返回完整后端 `Result`，不再只解包 `data`。因此编辑页第四步“接口调试”的返回结果区域现在展示：

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "pageNum": 1,
    "pageSize": 10,
    "pages": "1",
    "table": {
      "bodies": []
    }
  }
}
```

涉及文件：

| 文件 | 位置 |
| --- | --- |
| `frontend/packages/api-sdk/src/client.ts` | `dataServices.debug` 保留完整 `Result<DataServiceInvokeResponse>`。 |

## 3. 复测环境

| 项目 | 信息 |
| --- | --- |
| 执行时间 | 2026-04-17 00:29 至 2026-04-17 00:45 |
| Studio Server | `http://127.0.0.1:18080` |
| Studio Worker | `http://127.0.0.1:18081` |
| 前端页面 | `http://127.0.0.1:5173` |
| Redis | `REDIS_HOST=192.168.188.129` |
| 登录用户 | `admin` |
| 租户 | `default` |
| 项目 | `2041806351881003009` |
| 测试模型 | `2044807484626960386` |
| 原始复测 JSON | `runtime/test-logs/data-service-regression-test-result-20260417.json` |
| 复测脚本 | `runtime/test-logs/data-service-regression-20260417.ps1` |

说明：首次启动后端时未显式带 `REDIS_HOST`，健康检查因默认 Redis `172.16.20.19` 不可达显示 DOWN；随后使用 `REDIS_HOST=192.168.188.129` 重启，健康检查恢复 UP。

## 4. 回归汇总

| 测试类型 | 总数 | 通过 | 失败 | 阻塞 |
| --- | ---: | ---: | ---: | ---: |
| API 自动化复测 | 13 | 13 | 0 | 0 |
| UI 手工复测 | 3 | 3 | 0 | 0 |
| 编译验证 | 2 | 2 | 0 | 0 |

## 5. API 自动化复测明细

本轮创建了新的复测服务：

| 项目 | 值 |
| --- | --- |
| 服务 Code | `ds_reg_header_20260416_20260417_004320` |
| 服务 ID | `2044818938784772097` |
| 服务地址 | `/openapi/data-services/ds_reg_header_20260416_20260417_004320/8Ufue66tA-LCba2q` |
| 订阅 ID | `2044818939824959489` |
| 开放调用返回行数 | `5` |

| 用例 | 名称 | 结果 |
| --- | --- | --- |
| `REG-ENV-001` | Studio Server 健康检查 | 通过 |
| `REG-ENV-002` | Studio Worker 健康检查 | 通过 |
| `REG-ENV-003` | 登录并获取上下文 | 通过 |
| `REG-API-001` | 保存包含 `X-City` Header 映射的数据服务 | 通过 |
| `REG-API-002` | 详情接口回显 `X-City` Header 映射 | 通过 |
| `REG-API-003` | 发布包含 `X-City` Header 映射的数据服务 | 通过 |
| `REG-API-004` | 创建订阅 Token | 通过 |
| `REG-API-005` | 开放 API 使用 `X-City` Header 成功调用 | 通过 |
| `REG-API-006` | 后台调试接口使用 `X-City` Header 成功调用 | 通过 |
| `REG-API-007` | Query 参数名使用 `bad-name` 仍被拦截 | 通过 |
| `REG-API-008` | Body 参数名使用 `bad-name` 仍被拦截 | 通过 |
| `REG-API-009` | 开放调用进入服务监控统计 | 通过 |
| `REG-API-010` | 开放调用进入访问日志 | 通过 |

## 6. UI 手工复测明细

| 用例 | 验证点 | 结果 |
| --- | --- | --- |
| `UI-REG-001` | 编辑页第四步可打开复测服务的“接口调试” | 通过 |
| `UI-REG-002` | 点击“生成调试模板”后可生成 Query/Header/Body 模板 | 通过 |
| `UI-REG-003` | 点击“调试”后，返回结果框展示完整 `success/code/message/data` Result 包装 | 通过 |

UI 实测返回框中已出现：

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "pageNum": 1,
    "pageSize": 10,
    "pages": "1",
    "table": {
      "bodies": [
        {
          "id": "1",
          "name": "Alpha Customer 01",
          "status": "ACTIVE",
          "city": "Shanghai"
        }
      ]
    }
  }
}
```

## 7. 编译验证

| 类型 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `mvn -pl studio-server -am -DskipTests compile` | 通过 |
| 前端 | `npm run build:web` | 通过 |

补充：为启动服务时确保 `studio-infra` 修复后的 jar 被 `studio-server` 使用，本轮还执行了 `mvn -pl studio-server -am -DskipTests install`，结果通过。

编译过程仍存在首轮已记录的非阻断提示：Maven 私服 metadata 超时警告、npm `electron_mirror` 配置警告、Vite chunk 体积提示。这些提示不影响本轮修复项。

## 8. 遗留情况

本轮未发现新的数据服务功能缺陷。

复测创建的数据服务、订阅和访问日志均保留，便于后续查看现场。

结论：首轮报告中的 `DS-API-001` 与 `DS-UI-001` 均已修复并通过回归。
