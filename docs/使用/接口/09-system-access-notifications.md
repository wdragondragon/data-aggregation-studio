# 系统管理、权限、访问申请、通知和关注接口

覆盖用户、角色、权限、租户项目、成员申请、Worker 绑定、资源共享、通知和关注。

## 界面范围

- `/access-center` -> `frontend/apps/web/src/views/WorkspaceAccessView.vue`
- `/notifications` -> `frontend/apps/web/src/views/NotificationsView.vue`
- `/system` -> `frontend/apps/web/src/views/SystemView.vue`

## 调用前置条件

- 管理端基础地址：`${BASE_URL}`，默认在线环境为 `http://127.0.0.1:18080/api/v1`。
- 认证头：`Authorization: Bearer ${TOKEN}`。
- 租户和项目上下文：大多数项目内接口需要 `X-Tenant-Id: ${TENANT_ID}` 与 `X-Project-Id: ${PROJECT_ID}`。
- 返回包裹：所有管理端 JSON 接口返回 `Result<T>`：`{ success, code, message, data, timestamp }`。分页通常为 `PageResult<T>`：`{ pageNo, pageSize, total, items }`。

## 典型调用链

1. 用户注册审批：POST /auth/register-requests -> 管理员 GET /system/user-registration-requests/page -> POST /system/user-registration-requests/{id}/approve 或 /reject。
2. 租户项目初始化：POST /system/tenants -> POST /system/projects -> POST /system/tenant-members -> POST /system/project-members。
3. 项目访问申请审批：普通用户 POST /access/project-requests -> 管理员 GET /system/project-member-requests/page -> POST /system/project-member-requests 保存审批/邀请状态。
4. Worker 绑定：GET /system/project-worker-options?projectId=<id> -> POST /system/project-workers -> 运维 GET /ops-center/workers/query 校验在线实例。
5. 资源共享：GET /system/resource-share-options?resourceType=<type>&projectId=<source> -> POST /system/resource-shares -> 目标项目查询对应资源列表。
6. 通知关注：POST /follows 关注目标 -> GET /notifications/snapshot 或 /notifications -> POST /notifications/{id}/read。

## 前端 SDK 接口清单

| SDK 调用 | 方法 | 路径 | 参数/请求体 | 返回类型 | 前端调用点 | 源码 |
|---|---|---|---|---|---|---|
| `access.overview()` | GET | `/access/overview` | - | `WorkspaceAccessOverviewView` | `frontend/apps/web/src/views/WorkspaceAccessView.vue:145` | `frontend/packages/api-sdk/src/client.ts:422` |
| `access.apply()` | POST | `/access/project-requests` | payload: WorkspaceAccessApplyRequest<br>body: `payload` | `WorkspaceAccessRequestView` | `frontend/apps/web/src/views/WorkspaceAccessView.vue:165` | `frontend/packages/api-sdk/src/client.ts:425` |
| `follows.follow()` | POST | `/follows` | payload: FollowRequest<br>body: `payload` | `FollowStatusView` | `frontend/apps/web/src/components/FollowToggleButton.vue:59` | `frontend/packages/api-sdk/src/client.ts:1719` |
| `follows.unfollow()` | DELETE | `/follows` | targetType: string<br>targetId: EntityId<br>query: `{ targetType, targetId }` | `void` | `frontend/apps/web/src/components/FollowToggleButton.vue:55` | `frontend/packages/api-sdk/src/client.ts:1722` |
| `follows.status()` | GET | `/follows/status` | targetType: string<br>targetId: EntityId<br>query: `{ targetType, targetId }` | `FollowStatusView` | `frontend/apps/web/src/components/FollowToggleButton.vue:40` | `frontend/packages/api-sdk/src/client.ts:1712` |
| `notifications.list()` | GET | `/notifications` | params?: NotificationQueryRequest | `NotificationView` | `frontend/apps/web/src/views/NotificationsView.vue:102` | `frontend/packages/api-sdk/src/client.ts:1695` |
| `notifications.markAllRead()` | POST | `/notifications/read-all` | - | `void` | `frontend/apps/web/src/stores/notifications.ts:49` | `frontend/packages/api-sdk/src/client.ts:1707` |
| `notifications.snapshot()` | GET | `/notifications/snapshot` | - | `NotificationSnapshotView` | `frontend/apps/web/src/stores/notifications.ts:33` | `frontend/packages/api-sdk/src/client.ts:1698` |
| `notifications.unreadCount()` | GET | `/notifications/unread-count` | - | `number` | - | `frontend/packages/api-sdk/src/client.ts:1701` |
| `permissions.list()` | GET | `/permissions` | - | `PermissionEntity[]` | - | `frontend/packages/api-sdk/src/client.ts:1774` |
| `permissions.save()` | POST | `/permissions` | payload: Partial<PermissionEntity><br>body: `payload` | `PermissionEntity` | - | `frontend/packages/api-sdk/src/client.ts:1777` |
| `roles.list()` | GET | `/roles` | - | `RoleEntity[]` | - | `frontend/packages/api-sdk/src/client.ts:1763` |
| `roles.save()` | POST | `/roles` | payload: Partial<RoleEntity><br>body: `payload` | `RoleEntity` | - | `frontend/packages/api-sdk/src/client.ts:1766` |
| `system.projectMemberRequests.list()` | GET | `/system/project-member-requests` | projectId?: EntityId<br>config?: StudioRequestConfig<br>query: `projectId == null ? undefined : { projectId }` | `SystemProjectMemberRequest[]` | - | `frontend/packages/api-sdk/src/client.ts:1850` |
| `system.projectMemberRequests.save()` | POST | `/system/project-member-requests` | payload: Partial<SystemProjectMemberRequest><br>body: `payload` | `SystemProjectMemberRequest` | `frontend/apps/web/src/views/SystemView.vue:1496`<br>`frontend/apps/web/src/views/SystemView.vue:1531`<br>`frontend/apps/web/src/views/SystemView.vue:1560` | `frontend/packages/api-sdk/src/client.ts:1861` |
| `system.projectMemberRequests.listPage()` | GET | `/system/project-member-requests/page` | params?: { projectId?: EntityId; pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `SystemProjectMemberRequest` | `frontend/apps/web/src/views/SystemView.vue:949` | `frontend/packages/api-sdk/src/client.ts:1858` |
| `system.projectMembers.list()` | GET | `/system/project-members` | projectId?: EntityId<br>config?: StudioRequestConfig<br>query: `projectId == null ? undefined : { projectId }` | `SystemProjectMember[]` | - | `frontend/packages/api-sdk/src/client.ts:1831` |
| `system.projectMembers.save()` | POST | `/system/project-members` | payload: Partial<SystemProjectMember><br>body: `payload` | `SystemProjectMember` | `frontend/apps/web/src/views/SystemView.vue:1479` | `frontend/packages/api-sdk/src/client.ts:1842` |
| `system.projectMembers.listPage()` | GET | `/system/project-members/page` | params?: { projectId?: EntityId; pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `SystemProjectMember` | `frontend/apps/web/src/views/SystemView.vue:931` | `frontend/packages/api-sdk/src/client.ts:1839` |
| `system.projects.options()` | GET | `/system/project-options` | config?: StudioRequestConfig | `SystemProjectOption[]` | `frontend/apps/web/src/views/SystemView.vue:984` | `frontend/packages/api-sdk/src/client.ts:1803` |
| `system.projectWorkers.options()` | GET | `/system/project-worker-options` | projectId?: EntityId<br>config?: StudioRequestConfig<br>query: `projectId == null ? undefined : { projectId }` | `SystemProjectWorkerOption[]` | `frontend/apps/web/src/views/SystemView.vue:1051` | `frontend/packages/api-sdk/src/client.ts:1880` |
| `system.projectWorkers.list()` | GET | `/system/project-workers` | projectId?: EntityId<br>config?: StudioRequestConfig<br>query: `projectId == null ? undefined : { projectId }` | `SystemProjectWorker[]` | - | `frontend/packages/api-sdk/src/client.ts:1869` |
| `system.projectWorkers.save()` | POST | `/system/project-workers` | payload: Partial<SystemProjectWorker><br>body: `payload` | `SystemProjectWorker` | `frontend/apps/web/src/views/SystemView.vue:1575` | `frontend/packages/api-sdk/src/client.ts:1888` |
| `system.projectWorkers.listPage()` | GET | `/system/project-workers/page` | params?: { projectId?: EntityId; pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `SystemProjectWorker` | `frontend/apps/web/src/views/SystemView.vue:967` | `frontend/packages/api-sdk/src/client.ts:1877` |
| `system.projects.list()` | GET | `/system/projects` | config?: StudioRequestConfig | `SystemProject[]` | - | `frontend/packages/api-sdk/src/client.ts:1800` |
| `system.projects.save()` | POST | `/system/projects` | payload: Partial<SystemProject><br>body: `payload` | `SystemProject` | `frontend/apps/web/src/views/SystemView.vue:1447` | `frontend/packages/api-sdk/src/client.ts:1809` |
| `system.projects.listPage()` | GET | `/system/projects/page` | params?: { pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `SystemProject` | `frontend/apps/web/src/views/SystemView.vue:903` | `frontend/packages/api-sdk/src/client.ts:1806` |
| `system.resourceShares.options()` | GET | `/system/resource-share-options` | params: { resourceType: string; projectId?: EntityId }<br>config?: StudioRequestConfig | `ShareResourceOption[]` | `frontend/apps/web/src/views/SystemView.vue:1016` | `frontend/packages/api-sdk/src/client.ts:1902` |
| `system.resourceShares.list()` | GET | `/system/resource-shares` | params?: { resourceType?: string; projectId?: EntityId }<br>config?: StudioRequestConfig | `ResourceShare[]` | - | `frontend/packages/api-sdk/src/client.ts:1896` |
| `system.resourceShares.save()` | POST | `/system/resource-shares` | payload: Partial<ResourceShare><br>body: `payload` | `ResourceShare` | `frontend/apps/web/src/views/SystemView.vue:1602` | `frontend/packages/api-sdk/src/client.ts:1905` |
| `system.resourceShares.listPage()` | GET | `/system/resource-shares/page` | params?: { resourceType?: string; projectId?: EntityId; pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `ResourceShare` | `frontend/apps/web/src/views/SystemView.vue:994` | `frontend/packages/api-sdk/src/client.ts:1899` |
| `system.tenantMembers.list()` | GET | `/system/tenant-members` | config?: StudioRequestConfig | `SystemTenantMember[]` | - | `frontend/packages/api-sdk/src/client.ts:1817` |
| `system.tenantMembers.save()` | POST | `/system/tenant-members` | payload: Partial<SystemTenantMember><br>body: `payload` | `SystemTenantMember` | `frontend/apps/web/src/views/SystemView.vue:1462` | `frontend/packages/api-sdk/src/client.ts:1823` |
| `system.tenantMembers.listPage()` | GET | `/system/tenant-members/page` | params?: { pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `SystemTenantMember` | `frontend/apps/web/src/views/SystemView.vue:914` | `frontend/packages/api-sdk/src/client.ts:1820` |
| `system.tenants.list()` | GET | `/system/tenants` | config?: StudioRequestConfig | `SystemTenant[]` | - | `frontend/packages/api-sdk/src/client.ts:1786` |
| `system.tenants.save()` | POST | `/system/tenants` | payload: Partial<SystemTenant><br>body: `payload` | `SystemTenant` | `frontend/apps/web/src/views/SystemView.vue:1412` | `frontend/packages/api-sdk/src/client.ts:1792` |
| `system.tenants.listPage()` | GET | `/system/tenants/page` | params?: { pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `SystemTenant` | `frontend/apps/web/src/views/SystemView.vue:892` | `frontend/packages/api-sdk/src/client.ts:1789` |
| `system.userRegistrationRequests.list()` | GET | `/system/user-registration-requests` | config?: StudioRequestConfig | `UserRegistrationRequestView[]` | - | `frontend/packages/api-sdk/src/client.ts:1913` |
| `system.userRegistrationRequests.listPage()` | GET | `/system/user-registration-requests/page` | params?: { pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `UserRegistrationRequestView` | `frontend/apps/web/src/views/SystemView.vue:881` | `frontend/packages/api-sdk/src/client.ts:1916` |
| `users.list()` | GET | `/users` | config?: StudioRequestConfig | `StudioUserListView[]` | - | `frontend/packages/api-sdk/src/client.ts:1746` |
| `users.save()` | POST | `/users` | payload: Partial<StudioUser><br>body: `payload` | `StudioUser` | `frontend/apps/web/src/views/SystemView.vue:1429` | `frontend/packages/api-sdk/src/client.ts:1755` |
| `users.options()` | GET | `/users/options` | config?: StudioRequestConfig | `StudioUserOption[]` | `frontend/apps/web/src/views/SystemView.vue:1028` | `frontend/packages/api-sdk/src/client.ts:1749` |
| `users.listPage()` | GET | `/users/page` | params?: { pageNo?: number; pageSize?: number }<br>config?: StudioRequestConfig | `StudioUserListView` | `frontend/apps/web/src/views/SystemView.vue:865` | `frontend/packages/api-sdk/src/client.ts:1752` |
| `access.cancel()` | POST | ``/access/project-requests/${requestId}/cancel`` | requestId: EntityId | `WorkspaceAccessRequestView` | `frontend/apps/web/src/views/WorkspaceAccessView.vue:192` | `frontend/packages/api-sdk/src/client.ts:428` |
| `notifications.markRead()` | POST | ``/notifications/${id}/read`` | id: EntityId | `void` | `frontend/apps/web/src/stores/notifications.ts:44` | `frontend/packages/api-sdk/src/client.ts:1704` |
| `permissions.delete()` | DELETE | ``/permissions/${id}`` | id: EntityId | `void` | - | `frontend/packages/api-sdk/src/client.ts:1780` |
| `roles.delete()` | DELETE | ``/roles/${id}`` | id: EntityId | `void` | - | `frontend/packages/api-sdk/src/client.ts:1769` |
| `system.projectMemberRequests.delete()` | DELETE | ``/system/project-member-requests/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/SystemView.vue:1668` | `frontend/packages/api-sdk/src/client.ts:1864` |
| `system.projectMembers.delete()` | DELETE | ``/system/project-members/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/SystemView.vue:1659` | `frontend/packages/api-sdk/src/client.ts:1845` |
| `system.projectWorkers.delete()` | DELETE | ``/system/project-workers/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/SystemView.vue:1676` | `frontend/packages/api-sdk/src/client.ts:1891` |
| `system.projects.delete()` | DELETE | ``/system/projects/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/SystemView.vue:1640` | `frontend/packages/api-sdk/src/client.ts:1812` |
| `system.resourceShares.delete()` | DELETE | ``/system/resource-shares/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/SystemView.vue:1685` | `frontend/packages/api-sdk/src/client.ts:1908` |
| `system.tenantMembers.delete()` | DELETE | ``/system/tenant-members/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/SystemView.vue:1650` | `frontend/packages/api-sdk/src/client.ts:1826` |
| `system.tenants.delete()` | DELETE | ``/system/tenants/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/SystemView.vue:1617` | `frontend/packages/api-sdk/src/client.ts:1795` |
| `system.userRegistrationRequests.approve()` | POST | ``/system/user-registration-requests/${id}/approve`` | id: EntityId<br>payload?: UserRegistrationRequestReviewRequest<br>body: `payload` | `UserRegistrationRequestView` | `frontend/apps/web/src/views/SystemView.vue:1699` | `frontend/packages/api-sdk/src/client.ts:1919` |
| `system.userRegistrationRequests.reject()` | POST | ``/system/user-registration-requests/${id}/reject`` | id: EntityId<br>payload?: UserRegistrationRequestReviewRequest<br>body: `payload` | `UserRegistrationRequestView` | `frontend/apps/web/src/views/SystemView.vue:1736` | `frontend/packages/api-sdk/src/client.ts:1926` |
| `system.userRegistrationRequests.delete()` | DELETE | ``/system/user-registration-requests/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/SystemView.vue:1758` | `frontend/packages/api-sdk/src/client.ts:1933` |
| `users.delete()` | DELETE | ``/users/${id}`` | id: EntityId | `void` | `frontend/apps/web/src/views/SystemView.vue:1628` | `frontend/packages/api-sdk/src/client.ts:1758` |


## 后端 Controller 对照

| Controller | 方法 | 路径 | Java 方法 | 参数来源 | 源码 |
|---|---|---|---|---|---|
| WorkspaceAccessController | GET | `/api/v1/access/overview` | `overview()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkspaceAccessController.java:29` |
| WorkspaceAccessController | POST | `/api/v1/access/project-requests` | `apply()` | body: `@RequestBody WorkspaceAccessApplyRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkspaceAccessController.java:35` |
| WorkspaceAccessController | POST | `/api/v1/access/project-requests/{id}/cancel` | `cancel()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/WorkspaceAccessController.java:41` |
| FollowController | POST | `/api/v1/follows` | `follow()` | body: `@RequestBody FollowRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/FollowController.java:36` |
| FollowController | DELETE | `/api/v1/follows` | `unfollow()` | query: `@RequestParam("targetType") String targetType`<br>query: `@RequestParam("targetId") Long targetId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/FollowController.java:42` |
| FollowController | GET | `/api/v1/follows/status` | `status()` | query: `@RequestParam("targetType") String targetType`<br>query: `@RequestParam("targetId") Long targetId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/FollowController.java:29` |
| NotificationController | GET | `/api/v1/notifications` | `list()` | implicit: `NotificationQueryRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/NotificationController.java:32` |
| NotificationController | POST | `/api/v1/notifications/{id}/read` | `read()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/NotificationController.java:50` |
| NotificationController | POST | `/api/v1/notifications/read-all` | `readAll()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/NotificationController.java:57` |
| NotificationController | GET | `/api/v1/notifications/snapshot` | `snapshot()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/NotificationController.java:38` |
| NotificationController | GET | `/api/v1/notifications/stream` | `stream()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/NotificationController.java:64` |
| NotificationController | GET | `/api/v1/notifications/unread-count` | `unreadCount()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/NotificationController.java:44` |
| PermissionController | GET | `/api/v1/permissions` | `list()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/PermissionController.java:43` |
| PermissionController | POST | `/api/v1/permissions` | `save()` | body: `@RequestBody PermissionEntity entity` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/PermissionController.java:51` |
| PermissionController | DELETE | `/api/v1/permissions/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/PermissionController.java:63` |
| RoleController | GET | `/api/v1/roles` | `list()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RoleController.java:48` |
| RoleController | POST | `/api/v1/roles` | `save()` | body: `@RequestBody RoleEntity entity` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RoleController.java:56` |
| RoleController | DELETE | `/api/v1/roles/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/RoleController.java:68` |
| SystemManagementController | GET | `/api/v1/system/project-member-requests` | `listProjectMemberRequests()` | query: `@RequestParam(value = "projectId"`<br>implicit: `required = false) Long projectId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:165` |
| SystemManagementController | POST | `/api/v1/system/project-member-requests` | `saveProjectMemberRequest()` | body: `@RequestBody ProjectMemberRequestEntity entity` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:179` |
| SystemManagementController | DELETE | `/api/v1/system/project-member-requests/{id}` | `deleteProjectMemberRequest()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:185` |
| SystemManagementController | GET | `/api/v1/system/project-member-requests/page` | `listProjectMemberRequestsPage()` | query: `@RequestParam(value = "projectId"`<br>implicit: `required = false) Long projectId`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:171` |
| SystemManagementController | GET | `/api/v1/system/project-members` | `listProjectMembers()` | query: `@RequestParam(value = "projectId"`<br>implicit: `required = false) Long projectId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:138` |
| SystemManagementController | POST | `/api/v1/system/project-members` | `saveProjectMember()` | body: `@RequestBody ProjectMemberEntity entity` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:152` |
| SystemManagementController | DELETE | `/api/v1/system/project-members/{id}` | `deleteProjectMember()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:158` |
| SystemManagementController | GET | `/api/v1/system/project-members/page` | `listProjectMembersPage()` | query: `@RequestParam(value = "projectId"`<br>implicit: `required = false) Long projectId`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:144` |
| SystemManagementController | GET | `/api/v1/system/project-options` | `listProjectOptions()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:86` |
| SystemManagementController | GET | `/api/v1/system/project-worker-options` | `listProjectWorkerOptions()` | query: `@RequestParam(value = "projectId"`<br>implicit: `required = false) Long projectId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:206` |
| SystemManagementController | GET | `/api/v1/system/project-workers` | `listProjectWorkers()` | query: `@RequestParam(value = "projectId"`<br>implicit: `required = false) Long projectId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:192` |
| SystemManagementController | POST | `/api/v1/system/project-workers` | `saveProjectWorkerBinding()` | body: `@RequestBody ProjectWorkerBindingEntity entity` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:212` |
| SystemManagementController | DELETE | `/api/v1/system/project-workers/{id}` | `deleteProjectWorkerBinding()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:218` |
| SystemManagementController | GET | `/api/v1/system/project-workers/page` | `listProjectWorkersPage()` | query: `@RequestParam(value = "projectId"`<br>implicit: `required = false) Long projectId`<br>query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:198` |
| SystemManagementController | GET | `/api/v1/system/projects` | `listProjects()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:80` |
| SystemManagementController | POST | `/api/v1/system/projects` | `saveProject()` | body: `@RequestBody ProjectEntity entity` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:99` |
| SystemManagementController | DELETE | `/api/v1/system/projects/{id}` | `deleteProject()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:105` |
| SystemManagementController | GET | `/api/v1/system/projects/page` | `listProjectsPage()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:92` |
| SystemManagementController | GET | `/api/v1/system/resource-share-options` | `listShareResourceOptions()` | query: `@RequestParam("resourceType") String resourceType`<br>query: `@RequestParam(value = "projectId"`<br>implicit: `required = false) Long projectId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:241` |
| SystemManagementController | GET | `/api/v1/system/resource-shares` | `listResourceShares()` | query: `@RequestParam(value = "resourceType"`<br>implicit: `required = false) String resourceType`<br>query: `@RequestParam(value = "projectId"`<br>implicit: `required = false) Long projectId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:225` |
| SystemManagementController | POST | `/api/v1/system/resource-shares` | `saveResourceShare()` | body: `@RequestBody ResourceShareEntity entity` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:248` |
| SystemManagementController | DELETE | `/api/v1/system/resource-shares/{id}` | `deleteResourceShare()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:254` |
| SystemManagementController | GET | `/api/v1/system/resource-shares/page` | `listResourceSharesPage()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize`<br>query: `@RequestParam(value = "resourceType"`<br>implicit: `required = false) String resourceType`<br>query: `@RequestParam(value = "projectId"`<br>implicit: `required = false) Long projectId` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:232` |
| SystemManagementController | GET | `/api/v1/system/tenant-members` | `listTenantMembers()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:112` |
| SystemManagementController | POST | `/api/v1/system/tenant-members` | `saveTenantMember()` | body: `@RequestBody TenantMemberEntity entity` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:125` |
| SystemManagementController | DELETE | `/api/v1/system/tenant-members/{id}` | `deleteTenantMember()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:131` |
| SystemManagementController | GET | `/api/v1/system/tenant-members/page` | `listTenantMembersPage()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:118` |
| SystemManagementController | GET | `/api/v1/system/tenants` | `listTenants()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:54` |
| SystemManagementController | POST | `/api/v1/system/tenants` | `saveTenant()` | body: `@RequestBody TenantEntity entity` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:67` |
| SystemManagementController | DELETE | `/api/v1/system/tenants/{id}` | `deleteTenant()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:73` |
| SystemManagementController | GET | `/api/v1/system/tenants/page` | `listTenantsPage()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:60` |
| SystemManagementController | GET | `/api/v1/system/user-registration-requests` | `listUserRegistrationRequests()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:261` |
| SystemManagementController | DELETE | `/api/v1/system/user-registration-requests/{id}` | `deleteUserRegistrationRequest()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:288` |
| SystemManagementController | POST | `/api/v1/system/user-registration-requests/{id}/approve` | `approveUserRegistrationRequest()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) UserRegistrationRequestReviewRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:274` |
| SystemManagementController | POST | `/api/v1/system/user-registration-requests/{id}/reject` | `rejectUserRegistrationRequest()` | path: `@PathVariable("id") Long id`<br>body: `@RequestBody(required = false) UserRegistrationRequestReviewRequest request` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:281` |
| SystemManagementController | GET | `/api/v1/system/user-registration-requests/page` | `listUserRegistrationRequestsPage()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/SystemManagementController.java:267` |
| UserController | GET | `/api/v1/users` | `list()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/UserController.java:34` |
| UserController | POST | `/api/v1/users` | `save()` | body: `@RequestBody StudioUserEntity entity` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/UserController.java:53` |
| UserController | DELETE | `/api/v1/users/{id}` | `delete()` | path: `@PathVariable("id") Long id` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/UserController.java:59` |
| UserController | GET | `/api/v1/users/options` | `listOptions()` | - | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/UserController.java:40` |
| UserController | GET | `/api/v1/users/page` | `listPage()` | query: `@RequestParam(value = "pageNo"`<br>implicit: `required = false) Integer pageNo`<br>query: `@RequestParam(value = "pageSize"`<br>implicit: `required = false) Integer pageSize` | `backend/studio-server/src/main/java/com/jdragon/studio/server/web/controller/UserController.java:46` |


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

### follows.follow()

```bash
curl -X POST "${BASE_URL}/follows" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### follows.unfollow()

```bash
curl -X DELETE "${BASE_URL}/follows?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### follows.status()

```bash
curl -X GET "${BASE_URL}/follows/status?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### notifications.list()

```bash
curl -X GET "${BASE_URL}/notifications" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### notifications.markAllRead()

```bash
curl -X POST "${BASE_URL}/notifications/read-all" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### notifications.snapshot()

```bash
curl -X GET "${BASE_URL}/notifications/snapshot" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### notifications.unreadCount()

```bash
curl -X GET "${BASE_URL}/notifications/unread-count" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### permissions.list()

```bash
curl -X GET "${BASE_URL}/permissions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### permissions.save()

```bash
curl -X POST "${BASE_URL}/permissions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### roles.list()

```bash
curl -X GET "${BASE_URL}/roles" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### roles.save()

```bash
curl -X POST "${BASE_URL}/roles" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### system.projectMemberRequests.list()

```bash
curl -X GET "${BASE_URL}/system/project-member-requests?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### system.projectMemberRequests.save()

```bash
curl -X POST "${BASE_URL}/system/project-member-requests" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### system.projectMemberRequests.listPage()

```bash
curl -X GET "${BASE_URL}/system/project-member-requests/page" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### system.projectMembers.list()

```bash
curl -X GET "${BASE_URL}/system/project-members?<query>" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### system.projectMembers.save()

```bash
curl -X POST "${BASE_URL}/system/project-members" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '<json-body>'
```

### system.projectMembers.listPage()

```bash
curl -X GET "${BASE_URL}/system/project-members/page" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```

### system.projects.options()

```bash
curl -X GET "${BASE_URL}/system/project-options" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-Tenant-Id: ${TENANT_ID}" \
  -H "X-Project-Id: ${PROJECT_ID}"
```


## 开放访问 cURL 补充

开放访问接口不走管理端 `${BASE_URL}` 前缀，使用 `${STUDIO_ORIGIN}`，例如 `http://127.0.0.1:18080`。订阅令牌来自对应服务的订阅创建/轮换接口。

本模块无额外开放访问 Controller 模板。


## 主要 DTO 字段

### FollowRequest

来源：`frontend/packages/api-sdk/src/types.ts:3139`

```ts
interface FollowRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| targetType | 是 | `FollowTargetType \| string` | |
| targetId | 是 | `EntityId` | |

### FollowStatusView

来源：`frontend/packages/api-sdk/src/types.ts:3144`

```ts
interface FollowStatusView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| targetType | 否 | `FollowTargetType \| string` | |
| targetId | 否 | `EntityId` | |
| following | 否 | `boolean` | |

### NotificationQueryRequest

来源：`frontend/packages/api-sdk/src/types.ts:3133`

```ts
interface NotificationQueryRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| pageNo | 否 | `number` | |
| pageSize | 否 | `number` | |
| unreadOnly | 否 | `boolean` | |

### NotificationSnapshotView

来源：`frontend/packages/api-sdk/src/types.ts:3128`

```ts
interface NotificationSnapshotView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| unreadCount | 否 | `number` | |
| recentNotifications | 是 | `NotificationView[]` | |

### NotificationView

来源：`frontend/packages/api-sdk/src/types.ts:3112`

```ts
interface NotificationView
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| id | 否 | `EntityId` | |
| category | 否 | `string` | |
| title | 否 | `string` | |
| content | 否 | `string` | |
| targetType | 否 | `string` | |
| targetId | 否 | `EntityId` | |
| targetPath | 否 | `string` | |
| targetTenantId | 否 | `string` | |
| targetProjectId | 否 | `EntityId` | |
| read | 否 | `boolean` | |
| readAt | 否 | `string` | |
| archivedAt | 否 | `string` | |
| createdAt | 否 | `string` | |

### PermissionEntity

来源：`frontend/packages/api-sdk/src/types.ts:3273`

```ts
interface PermissionEntity extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| code | 是 | `string` | |
| name | 是 | `string` | |
| httpMethod | 否 | `string` | |
| pathPattern | 否 | `string` | |

### ResourceShare

来源：`frontend/packages/api-sdk/src/types.ts:3244`

```ts
interface ResourceShare extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| sourceProjectId | 否 | `EntityId` | |
| sourceProjectName | 否 | `string` | |
| targetProjectId | 否 | `EntityId` | |
| targetProjectName | 否 | `string` | |
| resourceType | 否 | `string` | |
| resourceId | 否 | `EntityId` | |
| resourceLabel | 否 | `string` | |
| resourceName | 否 | `string` | |
| resourceCode | 否 | `string` | |
| resourceStatus | 否 | `string` | |
| sharedByUserId | 否 | `EntityId` | |
| enabled | 否 | `boolean \| number` | |

### RoleEntity

来源：`frontend/packages/api-sdk/src/types.ts:3267`

```ts
interface RoleEntity extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| code | 是 | `string` | |
| name | 是 | `string` | |
| description | 否 | `string` | |

### StudioUser

来源：`frontend/packages/api-sdk/src/types.ts:3078`

```ts
interface StudioUser extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| username | 是 | `string` | |
| displayName | 否 | `string` | |
| passwordHash | 否 | `string` | |
| enabled | 否 | `number \| boolean` | |
| authSource | 否 | `string` | |
| externalAccount | 否 | `string` | |

### StudioUserListView

来源：`frontend/packages/api-sdk/src/types.ts:3087`

```ts
interface StudioUserListView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| username | 是 | `string` | |
| displayName | 否 | `string` | |
| enabled | 否 | `number \| boolean` | |

### SystemProject

来源：`frontend/packages/api-sdk/src/types.ts:3157`

```ts
interface SystemProject extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| projectCode | 是 | `string` | |
| projectName | 是 | `string` | |
| description | 否 | `string` | |
| enabled | 否 | `boolean \| number` | |
| defaultProject | 否 | `boolean \| number` | |

### SystemProjectMember

来源：`frontend/packages/api-sdk/src/types.ts:3178`

```ts
interface SystemProjectMember extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| userId | 是 | `EntityId` | |
| username | 否 | `string` | |
| displayName | 否 | `string` | |
| projectName | 否 | `string` | |
| roleCode | 否 | `string` | |
| status | 否 | `string` | |

### SystemProjectMemberRequest

来源：`frontend/packages/api-sdk/src/types.ts:3187`

```ts
interface SystemProjectMemberRequest extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| userId | 是 | `EntityId` | |
| username | 否 | `string` | |
| displayName | 否 | `string` | |
| projectName | 否 | `string` | |
| requestType | 否 | `string` | |
| status | 否 | `string` | |
| inviterUserId | 否 | `EntityId` | |
| inviterUsername | 否 | `string` | |
| reviewerUserId | 否 | `EntityId` | |
| reviewerUsername | 否 | `string` | |
| reason | 否 | `string` | |
| reviewComment | 否 | `string` | |

### SystemProjectWorker

来源：`frontend/packages/api-sdk/src/types.ts:3202`

```ts
interface SystemProjectWorker extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| workerGroupCode | 否 | `string` | |
| workerCode | 否 | `string` | |
| workerInstanceId | 否 | `string` | |
| workerKind | 否 | `string` | |
| hostName | 否 | `string` | |
| podName | 否 | `string` | |
| nodeName | 否 | `string` | |
| onlineInstanceCount | 否 | `number` | |
| recentInstanceCount | 否 | `number` | |
| status | 否 | `string` | |
| displayStatus | 否 | `string` | |
| lastHeartbeatAt | 否 | `string` | |
| latestHeartbeatAt | 否 | `string` | |
| boundToProject | 否 | `boolean` | |
| enabled | 否 | `boolean \| number` | |
| instances | 否 | `SystemWorkerInstance[]` | |

### SystemTenant

来源：`frontend/packages/api-sdk/src/types.ts:3150`

```ts
interface SystemTenant extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| tenantCode | 是 | `string` | |
| tenantName | 是 | `string` | |
| description | 否 | `string` | |
| enabled | 否 | `boolean \| number` | |

### SystemTenantMember

来源：`frontend/packages/api-sdk/src/types.ts:3170`

```ts
interface SystemTenantMember extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| userId | 是 | `EntityId` | |
| username | 否 | `string` | |
| displayName | 否 | `string` | |
| roleCode | 否 | `string` | |
| status | 否 | `string` | |

### UserRegistrationRequestReviewRequest

来源：`frontend/packages/api-sdk/src/types.ts:30`

```ts
interface UserRegistrationRequestReviewRequest
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| reviewComment | 否 | `string` | |

### UserRegistrationRequestView

来源：`frontend/packages/api-sdk/src/types.ts:3099`

```ts
interface UserRegistrationRequestView extends BaseRecord
```

| 字段 | 必填 | 类型 | 说明/自动化取值提示 |
|---|---:|---|---|
| status | 否 | `string` | |
| username | 是 | `string` | |
| displayName | 否 | `string` | |
| reason | 否 | `string` | |
| reviewComment | 否 | `string` | |
| reviewerUserId | 否 | `EntityId` | |
| reviewerUsername | 否 | `string` | |
| approvedUserId | 否 | `EntityId` | |
| approvedUsername | 否 | `string` | |
| reviewedAt | 否 | `string` | |

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

