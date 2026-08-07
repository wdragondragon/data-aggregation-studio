# Studio 同源无感换票测试用例

## 1. 测试入口

- Studio：`http://127.0.0.1:8000/dfs/data-aggregation-studio/`
- 宿主示例：`http://127.0.0.1:8000/other-system/page`
- Handoff：`/dfs/data-aggregation-studio/auth/handoff?returnPath=%2Fother-system%2Fpage`

两个系统的协议、Host 和端口必须一致。平台登录态通过 `access-token` Header 或同源 Cookie进入 Gateway；浏览器顶层跳转场景推荐 Cookie。

## 2. 核心用例

| 编号 | 操作 | 预期结果 |
| --- | --- | --- |
| AUTH-HO-001 | 已存在有效平台登录态、无 Studio 会话，打开 Handoff | Gateway exchange 成功；响应写入 `studio-token`；页面返回 `/other-system/page` |
| AUTH-HO-002 | 返回宿主后直接调用 `/dfs/data-aggregation-studio/api/v1/**`，不添加 Studio Header | 浏览器自动携带 `studio-token` Cookie；Studio API 认证成功 |
| AUTH-HO-003 | 同时发送有效 `X-Studio-Token`、Cookie 和旧 `Authorization` | Studio 只使用 `X-Studio-Token` |
| AUTH-HO-004 | 发送无效 `X-Studio-Token`，同时存在有效 Cookie | 返回 `401`，不得降级使用 Cookie |
| AUTH-HO-005 | 不发送 Header，Cookie 有效，同时存在其他系统 `Authorization` | Studio 使用 `studio-token` Cookie，不占用宿主 `Authorization` |
| AUTH-HO-006 | 只发送旧 `Authorization: Bearer <studio-jwt>` | 原 Studio 客户端保持可用 |
| AUTH-HO-007 | `returnPath=https://evil.example/x`、`//evil.example/x` 或反斜线外域路径 | 拒绝外部地址并返回 Studio 导览页 |
| AUTH-HO-008 | 使用兼容参数 `redirect` 或 `redict` 传入同源相对路径 | 完成换票并返回对应同源路径 |
| AUTH-HO-009 | Cookie 认证发起同源 `POST/PUT/PATCH/DELETE` | 请求通过 Fetch Metadata 和 Origin 校验 |
| AUTH-HO-010 | Cookie 认证发起跨站写请求 | 返回 `403`，业务 Controller 不执行 |
| AUTH-HO-011 | 调用 `POST /api/v1/auth/logout` | 响应以相同 Path 写入 `Max-Age=0`，后续 Cookie 认证失败 |
| AUTH-HO-012 | HTTPS 经 Nginx 换票 | Cookie 包含 `Secure; HttpOnly; SameSite=Lax`，Path 为公开 Studio 基座 |
| AUTH-HO-013 | 升级前浏览器仅存在旧 `studio_token` 本地存储 | Studio Web 首次读取后迁移为 `studio-token` 并删除旧键，现有登录态继续可用 |

## 3. 自动化验证

```powershell
$env:JAVA_HOME='C:\dev\Java\jdk-17.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd DataAggregation\data-aggregation-studio\backend
mvn -pl studio-infra,studio-server -am '-Dtest=StudioTokenResolverTest,StudioAuthCookieServiceTest,StudioCookieCsrfFilterTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

cd ..\frontend
npm run test:auth-handoff -w @studio/web
npm run build -w @studio/web
```

后端定向测试覆盖凭证优先级、横线 Cookie 名、Path/HttpOnly/SameSite/Secure、退出清理和 Cookie 写请求同源限制。前端测试覆盖同源回跳、外域拒绝和三个查询参数名；Web 构建同时校验 `studio-token` 主键及旧键迁移实现可正常编译。
