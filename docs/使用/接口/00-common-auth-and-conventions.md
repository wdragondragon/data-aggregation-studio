# 公共约定、认证与 cURL 模板

本文件描述所有 Studio 界面接口共用的请求前缀、认证头、响应包裹、登录和访问申请链路。

## 界面范围

- `/login` -> `frontend/apps/web/src/views/LoginView.vue`
- `/register` -> `frontend/apps/web/src/views/RegisterView.vue`

## 调用前置条件

- 管理端基础地址：`${BASE_URL}`，默认在线环境为 `http://127.0.0.1:18080/api/v1`。
- 认证头：`Authorization: Bearer ${TOKEN}`。
- 租户和项目上下文：大多数项目内接口需要 `X-Tenant-Id: ${TENANT_ID}` 与 `X-Project-Id: ${PROJECT_ID}`。
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页通常为 `PageResult<T>`：`{ pageNo, pageSize, total, items }`。

## 典型调用链

1. 登录链路：POST /auth/login -> 从 data.token 保存 TOKEN -> 从 data.tenants/data.projects 选择 TENANT_ID 和 PROJECT_ID -> GET /auth/me 校验上下文。
2. 网关单点登录链路：POST /auth/gateway/exchange -> 返回 LoginResponse 后与普通登录一样设置 Authorization、X-Tenant-Id、X-Project-Id。
3. 无项目访问申请链路：GET /access/overview -> 选择 tenantGroups[].projects[] 中目标 projectId -> POST /access/project-requests -> 等待管理员在系统管理中审批 -> GET /auth/me 刷新项目列表。

## 前端 SDK 接口清单

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 | 前端调用点 | 源码 |
|---|---|---|---|---|---|---|
| `access.overview()` | GET | `/access/overview` | - | `WorkspaceAccessOverviewView` | `frontend/apps/web/src/views/WorkspaceAccessView.vue:145` | `frontend/packages/api-sdk/src/client.ts:422` |
| `access.apply()` | POST | `/access/project-requests` | payload: WorkspaceAccessApplyRequest<br>body: `payload` | `WorkspaceAccessRequestView` | `frontend/apps/web/src/views/WorkspaceAccessView.vue:165` | `frontend/packages/api-sdk/src/client.ts:425` |
| `auth.gatewayExchange()` | POST | `/auth/gateway/exchange` | - | `LoginResponse` | `frontend/apps/web/src/stores/auth.ts:108` | `frontend/packages/api-sdk/src/client.ts:411` |
| `auth.login()` | POST | `/auth/login` | payload: LoginRequest<br>body: `payload` | `LoginResponse` | `frontend/apps/web/src/stores/auth.ts:99` | `frontend/packages/api-sdk/src/client.ts:408` |
| `auth.me()` | GET | `/auth/me` | - | `AuthProfile` | `frontend/apps/web/src/stores/auth.ts:62`<br>`frontend/apps/web/src/stores/auth.ts:69` | `frontend/packages/api-sdk/src/client.ts:414` |
| `auth.submitRegisterRequest()` | POST | `/auth/register-requests` | payload: UserRegistrationRequestCreateRequest<br>body: `payload` | `void` | `frontend/apps/web/src/views/RegisterView.vue:64` | `frontend/packages/api-sdk/src/client.ts:417` |
| `access.cancel()` | POST | ``/access/project-requests/${requestId}/cancel`` | requestId: EntityId | `WorkspaceAccessRequestView` | `frontend/apps/web/src/views/WorkspaceAccessView.vue:192` | `frontend/packages/api-sdk/src/client.ts:428` |


## 后端 Controller 对照

| Controller | 方法 | 路径 | Java 方法 | 参数来源 | 源码 |
|---|---|---|---|---|---|
| WorkspaceAccessController | GET | `/api/v1/access/overview` | `overview()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkspaceAccessController.java:29` |
| WorkspaceAccessController | POST | `/api/v1/access/project-requests` | `apply()` | body: `@RequestBody WorkspaceAccessApplyRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkspaceAccessController.java:35` |
| WorkspaceAccessController | POST | `/api/v1/access/project-requests/{id}/cancel` | `cancel()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkspaceAccessController.java:41` |
| AuthController | POST | `/api/v1/auth/gateway/exchange` | `gatewayExchange()` | implicit: `HttpServletRequest servletRequest` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AuthController.java:83` |
| AuthController | POST | `/api/v1/auth/login` | `login()` | body: `@Valid @RequestBody LoginRequest request`<br>implicit: `HttpServletRequest servletRequest` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AuthController.java:52` |
| AuthController | GET | `/api/v1/auth/me` | `me()` | implicit: `Authentication authentication`<br>implicit: `HttpServletRequest servletRequest` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AuthController.java:69` |
| AuthController | POST | `/api/v1/auth/register-requests` | `submitRegistrationRequest()` | body: `@Valid @RequestBody UserRegistrationRequestCreateRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/AuthController.java:95` |


## cURL 模板

以下模板按前端 SDK 自动生成，动态路径中的 `{id}`、`{serviceId}` 等需要替换为上游接口返回值。出现 `?<query>` 时按表格中的 query 参数追加。

如果接口清单的“参数/请求体”列没有显示 `body:`，则自动化调用时删除模板中的 `-H "Content-Type: application/json"` 和 `-d` 行；只有列出 `body:` 的接口才发送 JSON 请求体。

### access.overview()

```bash
curl -X GET "${BASE_URL}/access/overview" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### access.apply()

```bash
curl -X POST "${BASE_URL}/access/project-requests" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### auth.gatewayExchange()

```bash
curl -X POST "${BASE_URL}/auth/gateway/exchange" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### auth.login()

```bash
curl -X POST "${BASE_URL}/auth/login" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### auth.me()

```bash
curl -X GET "${BASE_URL}/auth/me" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### auth.submitRegisterRequest()

```bash
curl -X POST "${BASE_URL}/auth/register-requests" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### access.cancel()

```bash
curl -X POST "${BASE_URL}/access/project-requests/{requestId}/cancel" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```


## 开放访问 cURL 补充

开放访问接口不走管理端 `${BASE_URL}` 前缀，使用 `${STUDIO_ORIGIN}`，例如 `http://127.0.0.1:18080`。订阅令牌来自对应服务的订阅创建/轮换接口。

本模块无额外开放访问 Controller 模板。


## 主要 DTO 字段

### AuthProfile

来源：`frontend/packages/api-sdk/src/types.ts:57`

```ts
interface AuthProfile
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| userId | 否 | `EntityId` | |
| username | 是 | `string \| null` | |
| displayName | 否 | `string \| null` | |
| currentTenantId | 否 | `string \| null` | |
| currentProjectId | 否 | `EntityId \| null` | |
| systemRoleCodes | 是 | `string[]` | |
| effectiveRoleCodes | 是 | `string[]` | |
| tenants | 是 | `AuthTenant[]` | |
| projects | 是 | `AuthProject[]` | |

### LoginRequest

来源：`frontend/packages/api-sdk/src/types.ts:18`

```ts
interface LoginRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| username | 是 | `string` | |
| password | 是 | `string` | |

### LoginResponse

来源：`frontend/packages/api-sdk/src/types.ts:115`

```ts
interface LoginResponse extends AuthProfile
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| token | 是 | `string` | |

### UserRegistrationRequestCreateRequest

来源：`frontend/packages/api-sdk/src/types.ts:23`

```ts
interface UserRegistrationRequestCreateRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| username | 是 | `string` | |
| password | 是 | `string` | |
| displayName | 否 | `string` | |
| reason | 否 | `string` | |

### WorkspaceAccessApplyRequest

来源：`frontend/packages/api-sdk/src/types.ts:34`

```ts
interface WorkspaceAccessApplyRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| projectId | 是 | `EntityId` | |
| reason | 否 | `string` | |

### WorkspaceAccessOverviewView

来源：`frontend/packages/api-sdk/src/types.ts:103`

```ts
interface WorkspaceAccessOverviewView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| tenantGroups | 是 | `WorkspaceAccessTenantGroupView[]` | |
| requests | 是 | `WorkspaceAccessRequestView[]` | |

### WorkspaceAccessRequestView

来源：`frontend/packages/api-sdk/src/types.ts:89`

```ts
interface WorkspaceAccessRequestView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| requestId | 是 | `EntityId` | |
| projectId | 是 | `EntityId` | |
| tenantId | 是 | `string` | |
| tenantName | 否 | `string` | |
| projectName | 否 | `string` | |
| requestType | 否 | `string` | |
| status | 否 | `string` | |
| reason | 否 | `string` | |
| reviewComment | 否 | `string` | |
| createdAt | 否 | `string` | |
| reviewedAt | 否 | `string \| null` | |
