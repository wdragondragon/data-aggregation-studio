<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.system.heading") }}</h3>
        <p>{{ t("web.system.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain :loading="currentTabLoading" @click="loadCurrentTab">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <div v-if="!authStore.currentProjectId" class="context-alert" role="alert">
      请先选择当前项目后，再管理项目成员、申请邀请、Worker 组下发和资源共享。
    </div>

    <SectionCard title="组织管理" description="统一管理租户、项目、成员、申请邀请和 Worker 组下发关系。">
      <el-tabs v-model="activeTab">
        <el-tab-pane v-if="isSuperAdmin" label="用户管理" name="users">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openUserDialog()">新建用户</el-button>
          </div>
          <StudioTableShell v-loading="tabLoading.users" min-width="1160px">
          <el-table :data="users" border size="small">
            <el-table-column prop="username" label="用户名" min-width="160" />
            <el-table-column prop="displayName" label="显示名" min-width="180" />
            <el-table-column :label="t('web.system.elinkAccount')" min-width="220">
              <template #default="{ row }">
                <div v-if="row.elinkUserId" class="stack-cell">
                  <span>{{ row.elinkUserName || row.elinkUserId }}</span>
                  <span v-if="row.elinkUserName" class="cell-subtle">{{ row.elinkUserId }}</span>
                </div>
                <span v-else class="cell-subtle">{{ t("web.system.elinkUnbound") }}</span>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="toBooleanFlag(row.enabled) ? 'success' : 'info'">{{ toBooleanFlag(row.enabled) ? "开启" : "关闭" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" min-width="180" />
            <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
              <template #default="{ row }">
                <OverflowActionGroup :items="buildUserActions(row, systemActionHandlers)" />
              </template>
            </el-table-column>
          </el-table>
          </StudioTableShell>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="systemPagination.users.page"
              v-model:page-size="systemPagination.users.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="SYSTEM_PAGE_SIZES"
              :total="systemPagination.users.total"
              @current-change="reloadPaginatedTab('users')"
              @size-change="handlePaginatedPageSizeChange('users')"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="isSuperAdmin" label="注册登记" name="registrationRequests">
          <StudioTableShell v-loading="tabLoading.registrationRequests" min-width="1220px">
          <el-table :data="registrationRequests" border size="small">
            <el-table-column prop="status" label="状态" width="110" align="center" />
            <el-table-column prop="username" label="用户名" min-width="160" />
            <el-table-column prop="displayName" label="显示名" min-width="160" />
            <el-table-column prop="reason" label="申请说明" min-width="220" />
            <el-table-column prop="createdAt" label="提交时间" min-width="180" />
            <el-table-column label="审批信息" min-width="220">
              <template #default="{ row }">
                <div class="stack-cell">
                  <span>{{ row.reviewerUsername || "待处理" }}</span>
                  <span class="cell-subtle">{{ row.reviewComment || "暂无审批备注" }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
              <template #default="{ row }">
                <OverflowActionGroup :items="buildRegistrationActions(row, systemActionHandlers)" />
              </template>
            </el-table-column>
          </el-table>
          </StudioTableShell>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="systemPagination.registrationRequests.page"
              v-model:page-size="systemPagination.registrationRequests.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="SYSTEM_PAGE_SIZES"
              :total="systemPagination.registrationRequests.total"
              @current-change="reloadPaginatedTab('registrationRequests')"
              @size-change="handlePaginatedPageSizeChange('registrationRequests')"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="租户" name="tenants">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openTenantDialog()">新建租户</el-button>
          </div>
          <StudioTableShell v-loading="tabLoading.tenants" min-width="980px">
          <el-table :data="tenants" border size="small">
            <el-table-column prop="tenantCode" label="租户编码" min-width="160" />
            <el-table-column prop="tenantName" label="租户名称" min-width="180" />
            <el-table-column prop="description" label="描述" min-width="220" />
            <el-table-column label="启用" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? "开启" : "关闭" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
              <template #default="{ row }">
                <OverflowActionGroup :items="buildTenantActions(row, systemActionHandlers)" />
              </template>
            </el-table-column>
          </el-table>
          </StudioTableShell>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="systemPagination.tenants.page"
              v-model:page-size="systemPagination.tenants.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="SYSTEM_PAGE_SIZES"
              :total="systemPagination.tenants.total"
              @current-change="reloadPaginatedTab('tenants')"
              @size-change="handlePaginatedPageSizeChange('tenants')"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="项目" name="projects">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openProjectDialog()">新建项目</el-button>
          </div>
          <StudioTableShell v-loading="tabLoading.projects" min-width="1080px">
          <el-table :data="projects" border size="small">
            <el-table-column prop="projectCode" label="项目编码" min-width="160" />
            <el-table-column prop="projectName" label="项目名称" min-width="180" />
            <el-table-column prop="description" label="描述" min-width="220" />
            <el-table-column label="默认项目" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="row.defaultProject ? 'warning' : 'info'">{{ row.defaultProject ? "是" : "否" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? "开启" : "关闭" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
              <template #default="{ row }">
                <OverflowActionGroup :items="buildProjectActions(row, systemActionHandlers)" />
              </template>
            </el-table-column>
          </el-table>
          </StudioTableShell>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="systemPagination.projects.page"
              v-model:page-size="systemPagination.projects.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="SYSTEM_PAGE_SIZES"
              :total="systemPagination.projects.total"
              @current-change="reloadPaginatedTab('projects')"
              @size-change="handlePaginatedPageSizeChange('projects')"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="租户成员" name="tenantMembers">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openTenantMemberDialog()">新增成员</el-button>
          </div>
          <StudioTableShell v-loading="tabLoading.tenantMembers" min-width="880px">
          <el-table :data="tenantMembers" border size="small">
            <el-table-column prop="username" label="用户名" min-width="160" />
            <el-table-column prop="displayName" label="显示名" min-width="180" />
            <el-table-column prop="roleCode" label="角色" min-width="160" />
            <el-table-column prop="status" label="状态" width="110" align="center" />
            <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
              <template #default="{ row }">
                <OverflowActionGroup :items="buildTenantMemberActions(row, systemActionHandlers)" />
              </template>
            </el-table-column>
          </el-table>
          </StudioTableShell>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="systemPagination.tenantMembers.page"
              v-model:page-size="systemPagination.tenantMembers.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="SYSTEM_PAGE_SIZES"
              :total="systemPagination.tenantMembers.total"
              @current-change="reloadPaginatedTab('tenantMembers')"
              @size-change="handlePaginatedPageSizeChange('tenantMembers')"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="项目成员" name="projectMembers">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openProjectMemberDialog()">新增项目成员</el-button>
          </div>
          <StudioTableShell v-loading="tabLoading.projectMembers" min-width="1060px">
          <el-table :data="projectMembers" border size="small">
            <el-table-column prop="username" label="用户名" min-width="160" />
            <el-table-column prop="displayName" label="显示名" min-width="180" />
            <el-table-column prop="projectName" label="项目" min-width="180" />
            <el-table-column prop="roleCode" label="角色" min-width="160" />
            <el-table-column prop="status" label="状态" width="110" align="center" />
            <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
              <template #default="{ row }">
                <OverflowActionGroup :items="buildProjectMemberActions(row, systemActionHandlers)" />
              </template>
            </el-table-column>
          </el-table>
          </StudioTableShell>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="systemPagination.projectMembers.page"
              v-model:page-size="systemPagination.projectMembers.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="SYSTEM_PAGE_SIZES"
              :total="systemPagination.projectMembers.total"
              @current-change="reloadPaginatedTab('projectMembers')"
              @size-change="handlePaginatedPageSizeChange('projectMembers')"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="申请 / 邀请" name="requests">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openRequestDialog()">新增邀请</el-button>
          </div>
          <StudioTableShell v-loading="tabLoading.requests" min-width="1260px">
          <el-table :data="projectMemberRequests" border size="small">
            <el-table-column prop="username" label="用户" min-width="160" />
            <el-table-column prop="projectName" label="项目" min-width="180" />
            <el-table-column prop="requestType" label="类型" width="120" align="center" />
            <el-table-column prop="status" label="状态" width="120" align="center" />
            <el-table-column prop="inviterUsername" label="邀请人" min-width="140" />
            <el-table-column prop="reviewerUsername" label="审批人" min-width="140" />
            <el-table-column prop="reason" label="原因" min-width="220" />
            <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
              <template #default="{ row }">
                <OverflowActionGroup :items="buildProjectRequestActions(row, systemActionHandlers)" />
              </template>
            </el-table-column>
          </el-table>
          </StudioTableShell>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="systemPagination.requests.page"
              v-model:page-size="systemPagination.requests.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="SYSTEM_PAGE_SIZES"
              :total="systemPagination.requests.total"
              @current-change="reloadPaginatedTab('requests')"
              @size-change="handlePaginatedPageSizeChange('requests')"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="Worker 下发" name="workers">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openWorkerDialog()">绑定 Worker 组</el-button>
          </div>
          <StudioTableShell v-loading="tabLoading.workers" min-width="1180px">
          <el-table :data="projectWorkers" :row-key="workerRowKey" border size="small">
            <el-table-column type="expand" width="48">
              <template #default="{ row }">
                <div class="worker-instances">
                  <el-empty
                    v-if="workerInstances(row).length === 0"
                    description="暂无在线或近期 Pod 实例"
                    :image-size="72"
                  />
                  <el-table v-else :data="workerInstances(row)" border size="small">
                    <el-table-column label="Pod" min-width="180" show-overflow-tooltip>
                      <template #default="{ row: instance }">
                        <span class="mono-ellipsis">{{ instance.podName || "-" }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="实例 ID" min-width="220" show-overflow-tooltip>
                      <template #default="{ row: instance }">
                        <span class="mono-ellipsis">{{ instance.workerInstanceId || "-" }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column prop="nodeName" label="节点" min-width="150" show-overflow-tooltip />
                    <el-table-column prop="hostName" label="主机" min-width="150" show-overflow-tooltip />
                    <el-table-column label="类型" width="110" align="center">
                      <template #default="{ row: instance }">{{ workerKindLabel(instance.workerKind) }}</template>
                    </el-table-column>
                    <el-table-column label="Worker Code" min-width="170" show-overflow-tooltip>
                      <template #default="{ row: instance }">
                        <span class="mono-ellipsis">{{ instance.workerCode || "-" }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="实例状态" width="110" align="center">
                      <template #default="{ row: instance }">
                        <el-tag :type="workerInstanceTagType(instance)">{{ workerInstanceStatusLabel(instance) }}</el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="lastHeartbeatAt" label="最近心跳" min-width="180" />
                    <el-table-column prop="leaseExpiresAt" label="租约到期" min-width="180" />
                  </el-table>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="Worker 组" min-width="220">
              <template #default="{ row }">
                <div class="stack-cell">
                  <span class="mono-ellipsis">{{ row.workerGroupCode || row.workerCode || "-" }}</span>
                  <span class="cell-subtle">最近实例：{{ row.workerInstanceId || "-" }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="下发状态" width="120" align="center">
              <template #default="{ row }">
                <el-tag :type="row.boundToProject ? 'success' : 'info'">{{ row.boundToProject ? "已下发" : "未下发" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? "启用" : "停用" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="组状态" width="120" align="center">
              <template #default="{ row }">
                <el-tag :type="workerGroupTagType(row)">{{ workerGroupStatusLabel(row) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="在线实例" width="110" align="center">
              <template #default="{ row }">{{ row.onlineInstanceCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="近期实例" width="110" align="center">
              <template #default="{ row }">{{ row.recentInstanceCount ?? workerInstances(row).length }}</template>
            </el-table-column>
            <el-table-column prop="latestHeartbeatAt" label="最近心跳" min-width="180" />
            <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
              <template #default="{ row }">
                <OverflowActionGroup :items="buildWorkerActions(row, systemActionHandlers)" />
              </template>
            </el-table-column>
          </el-table>
          </StudioTableShell>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="systemPagination.workers.page"
              v-model:page-size="systemPagination.workers.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="SYSTEM_PAGE_SIZES"
              :total="systemPagination.workers.total"
              @current-change="reloadPaginatedTab('workers')"
              @size-change="handlePaginatedPageSizeChange('workers')"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="资源共享" name="shares">
          <div class="tab-toolbar tab-toolbar--split">
            <div class="tab-filters">
              <el-select
                v-model="shareFilters.resourceType"
                placeholder="资源类型"
                style="width: 180px"
                @change="handleResourceShareTypeFilterChange"
              >
                <el-option v-for="item in resourceTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </div>
            <el-button type="primary" :disabled="!authStore.currentProjectId" @click="openShareDialog()">共享资源</el-button>
          </div>
          <StudioTableShell v-loading="tabLoading.shares" min-width="980px">
          <el-table :data="resourceShares" border size="small">
            <el-table-column label="资源类型" width="150" align="center">
              <template #default="{ row }">
                {{ resourceTypeLabel(row.resourceType) }}
              </template>
            </el-table-column>
            <el-table-column label="资源" min-width="220">
              <template #default="{ row }">
                <div class="stack-cell">
                  <span>{{ resourceLabel(row) }}</span>
                  <span class="cell-subtle">来源项目：{{ resourceProjectLabel(row.sourceProjectId, row.sourceProjectName) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="共享到项目" min-width="180">
              <template #default="{ row }">
                {{ resourceProjectLabel(row.targetProjectId, row.targetProjectName) }}
              </template>
            </el-table-column>
            <el-table-column label="启用" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? "开启" : "关闭" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
              <template #default="{ row }">
                <OverflowActionGroup :items="buildResourceShareActions(row, systemActionHandlers)" />
              </template>
            </el-table-column>
          </el-table>
          </StudioTableShell>
          <div class="table-pagination">
            <el-pagination
              v-model:current-page="resourceSharePagination.page"
              v-model:page-size="resourceSharePagination.pageSize"
              background
              layout="total, sizes, prev, pager, next"
              :page-sizes="[10, 20, 50, 100]"
              :total="resourceSharePagination.total"
              @current-change="reloadResourceShares"
              @size-change="handleResourceSharePageSizeChange"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </SectionCard>

    <el-dialog v-model="userDialogOpen" title="用户" width="480px">
      <el-form label-position="top">
        <el-form-item label="用户名"><el-input v-model="userForm.username" /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="userForm.displayName" /></el-form-item>
        <el-form-item :label="t('web.system.mobilePhone')">
          <el-input
            v-model="userForm.mobilePhone"
            clearable
            maxlength="32"
            :placeholder="t('web.system.mobilePhonePlaceholder')"
          />
        </el-form-item>
        <el-form-item label="密码"><el-input v-model="userForm.passwordHash" type="password" show-password /></el-form-item>
        <el-form-item :label="t('web.system.elinkAccount')">
          <el-select
            v-model="userForm.elinkUserId"
            clearable
            filterable
            remote
            reserve-keyword
            :loading="elinkUserLoading"
            :remote-method="searchElinkUsers"
            :placeholder="t('web.system.elinkAccountPlaceholder')"
            style="width: 100%"
            @visible-change="handleElinkUserDropdownVisible"
          >
            <el-option
              v-for="user in elinkUserOptions"
              :key="user.userId"
              :label="elinkUserLabel(user)"
              :value="user.userId"
              :disabled="user.enabled === false && user.userId !== userForm.elinkUserId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="userForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveUser">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="tenantDialogOpen" title="租户" width="480px">
      <el-form label-position="top">
        <el-form-item label="租户编码"><el-input v-model="tenantForm.tenantCode" /></el-form-item>
        <el-form-item label="租户名称"><el-input v-model="tenantForm.tenantName" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="tenantForm.description" type="textarea" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="tenantForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tenantDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveTenant">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="projectDialogOpen" title="项目" width="480px">
      <el-form label-position="top">
        <el-form-item label="项目编码"><el-input v-model="projectForm.projectCode" /></el-form-item>
        <el-form-item label="项目名称"><el-input v-model="projectForm.projectName" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="projectForm.description" type="textarea" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="projectForm.enabled" /></el-form-item>
        <el-form-item label="默认项目"><el-switch v-model="projectForm.defaultProject" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="projectDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveProject">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="tenantMemberDialogOpen" title="租户成员" width="480px">
      <el-form label-position="top">
        <el-form-item label="用户">
          <el-select v-model="tenantMemberForm.userId" filterable style="width: 100%">
            <el-option v-for="user in userOptions" :key="user.id" :label="userLabel(user)" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="tenantMemberForm.roleCode" style="width: 100%">
            <el-option label="TENANT_ADMIN" value="TENANT_ADMIN" />
            <el-option label="PROJECT_ADMIN" value="PROJECT_ADMIN" />
            <el-option label="PROJECT_MEMBER" value="PROJECT_MEMBER" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="tenantMemberForm.status" style="width: 100%">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="INACTIVE" value="INACTIVE" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tenantMemberDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveTenantMember">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="projectMemberDialogOpen" title="项目成员" width="480px">
      <el-form label-position="top">
        <el-form-item label="用户">
          <el-select v-model="projectMemberForm.userId" filterable style="width: 100%">
            <el-option v-for="user in userOptions" :key="user.id" :label="userLabel(user)" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="projectMemberForm.roleCode" style="width: 100%">
            <el-option label="PROJECT_ADMIN" value="PROJECT_ADMIN" />
            <el-option label="PROJECT_MEMBER" value="PROJECT_MEMBER" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="projectMemberForm.status" style="width: 100%">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="INACTIVE" value="INACTIVE" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="projectMemberDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveProjectMember">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="requestDialogOpen" title="申请 / 邀请" width="520px">
      <el-form label-position="top">
        <el-form-item label="用户">
          <el-select v-model="requestForm.userId" filterable style="width: 100%">
            <el-option v-for="user in userOptions" :key="user.id" :label="userLabel(user)" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="requestForm.requestType" style="width: 100%">
            <el-option label="INVITE" value="INVITE" />
            <el-option label="APPLY" value="APPLY" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="requestForm.status" style="width: 100%">
            <el-option label="PENDING" value="PENDING" />
            <el-option label="APPROVED" value="APPROVED" />
            <el-option label="REJECTED" value="REJECTED" />
            <el-option label="ACCEPTED" value="ACCEPTED" />
            <el-option label="CANCELLED" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因"><el-input v-model="requestForm.reason" type="textarea" /></el-form-item>
        <el-form-item label="审批备注"><el-input v-model="requestForm.reviewComment" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="requestDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveProjectRequest">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="workerDialogOpen" :title="workerDialogTitle" width="480px">
      <el-form label-position="top">
        <el-form-item label="Worker 组">
          <el-select v-model="workerForm.workerGroupCode" filterable allow-create default-first-option style="width: 100%">
            <el-option
              v-for="worker in workerGroupOptions"
              :key="worker.value"
              :label="worker.label"
              :value="worker.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="workerForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="workerDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveProjectWorker">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="shareDialogOpen" title="资源共享" width="520px">
      <el-form label-position="top">
        <el-form-item label="资源类型">
          <el-select v-model="shareForm.resourceType" style="width: 100%" @change="handleShareFormResourceTypeChange">
            <el-option v-for="item in resourceTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="资源">
          <el-select
            v-model="shareForm.resourceId"
            filterable
            :loading="shareResourceLoading"
            :disabled="!shareForm.resourceType"
            style="width: 100%"
          >
            <el-option
              v-for="item in shareableResources"
              :key="String(item.id)"
              :label="item.label"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="共享到项目">
          <el-select v-model="shareForm.targetProjectId" filterable style="width: 100%">
            <el-option
              v-for="project in shareTargetProjects"
              :key="project.id"
              :label="project.projectName"
              :value="project.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="shareForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shareDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveResourceShare">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import type { Ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import type {
  EntityId,
  ResourceShare,
  ShareResourceOption,
  StudioUser,
  StudioUserListView,
  StudioUserOption,
  SystemProject,
  SystemProjectOption,
  SystemProjectMember,
  SystemProjectMemberRequest,
  SystemProjectWorker,
  SystemProjectWorkerOption,
  SystemWorkerInstance,
  SystemTenant,
  SystemTenantMember,
  UserRegistrationRequestView,
} from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useElinkDirectoryOptions } from "@/composables/useElinkDirectoryOptions";
import {
  buildProjectActions,
  buildProjectMemberActions,
  buildProjectRequestActions,
  buildRegistrationActions,
  buildResourceShareActions,
  buildTenantActions,
  buildTenantMemberActions,
  buildUserActions,
  buildWorkerActions,
} from "@/components/system/systemActions";
import type { SystemActionHandlers } from "@/components/system/systemActions";
import { normalizeDeletedFlag, normalizeResourceType, resourceTypeOptions, toBooleanFlag, toIntegerFlag } from "@/components/system/systemViewSupport";
import { useAuthStore } from "@/stores/auth";
import { resolveProjectName, sameEntityId } from "@/utils/studio";

type SystemTabName =
  | "users"
  | "registrationRequests"
  | "tenants"
  | "projects"
  | "tenantMembers"
  | "projectMembers"
  | "requests"
  | "workers"
  | "shares";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const {
  userOptions: elinkUserOptions,
  userLoading: elinkUserLoading,
  seedUsers: seedElinkUsers,
  queryUsers: queryElinkUsers,
  ensureUsers: ensureElinkUsers,
  cancelUserQuery: cancelElinkUserQuery,
  userLabel: elinkUserLabel,
} = useElinkDirectoryOptions();
const LOCAL_LOADING_REQUEST = { studioSkipGlobalLoading: true } as const;
const ELINK_SEARCH_DEBOUNCE_MS = 250;
const DEFAULT_SHARE_RESOURCE_TYPE = resourceTypeOptions[0].value;
const SYSTEM_PAGE_SIZES = [10, 20, 50, 100];
const activeTab = ref<SystemTabName>("tenants");
const systemMounted = ref(false);
const pendingCurrentTabReload = ref(false);
const isSuperAdmin = computed(() => authStore.systemRoleCodes.map((item) => item.toUpperCase()).includes("SUPER_ADMIN"));
const tabLoading = reactive<Record<SystemTabName, boolean>>({
  users: false,
  registrationRequests: false,
  tenants: false,
  projects: false,
  tenantMembers: false,
  projectMembers: false,
  requests: false,
  workers: false,
  shares: false,
});
const tenants = ref<SystemTenant[]>([]);
const projects = ref<SystemProject[]>([]);
const users = ref<StudioUserListView[]>([]);
const projectOptions = ref<SystemProjectOption[]>([]);
const userOptions = ref<StudioUserOption[]>([]);
const registrationRequests = ref<UserRegistrationRequestView[]>([]);
const tenantMembers = ref<SystemTenantMember[]>([]);
const projectMembers = ref<SystemProjectMember[]>([]);
const projectMemberRequests = ref<SystemProjectMemberRequest[]>([]);
const projectWorkers = ref<SystemProjectWorker[]>([]);
const projectWorkerOptions = ref<SystemProjectWorkerOption[]>([]);
const projectWorkerOptionsLoaded = ref(false);
const resourceShares = ref<ResourceShare[]>([]);
const shareResourceOptions = reactive<Record<string, ShareResourceOption[]>>({});
const shareResourceLoading = ref(false);
const loadedShareResourceTypes = reactive<Record<string, boolean>>({});
const shareFilters = reactive<{ resourceType: string }>({
  resourceType: DEFAULT_SHARE_RESOURCE_TYPE,
});
type PaginatedSystemTabName = "users" | "registrationRequests" | "tenants" | "projects" | "tenantMembers" | "projectMembers" | "requests" | "workers";

function createPagination() {
  return {
    page: 1,
    pageSize: 10,
    total: 0,
  };
}

const systemPagination = reactive<Record<PaginatedSystemTabName, ReturnType<typeof createPagination>>>({
  users: createPagination(),
  registrationRequests: createPagination(),
  tenants: createPagination(),
  projects: createPagination(),
  tenantMembers: createPagination(),
  projectMembers: createPagination(),
  requests: createPagination(),
  workers: createPagination(),
});
const resourceSharePagination = reactive({
  page: 1,
  pageSize: 10,
  total: 0,
});

const userDialogOpen = ref(false);
let elinkUserSearchTimer: ReturnType<typeof setTimeout> | undefined;
let elinkUserSearchKeyword = "";
const originalElinkUserId = ref("");
const originalElinkUserName = ref("");
const tenantDialogOpen = ref(false);
const projectDialogOpen = ref(false);
const tenantMemberDialogOpen = ref(false);
const projectMemberDialogOpen = ref(false);
const requestDialogOpen = ref(false);
const workerDialogOpen = ref(false);
const shareDialogOpen = ref(false);

const userForm = reactive<Partial<StudioUser>>({ enabled: 1, passwordHash: "" });
const tenantForm = reactive<Partial<SystemTenant>>({ enabled: true });
const projectForm = reactive<Partial<SystemProject>>({ enabled: true, defaultProject: false });
const tenantMemberForm = reactive<Partial<SystemTenantMember>>({ status: "ACTIVE", roleCode: "TENANT_ADMIN" });
const projectMemberForm = reactive<Partial<SystemProjectMember>>({ status: "ACTIVE", roleCode: "PROJECT_MEMBER" });
const requestForm = reactive<Partial<SystemProjectMemberRequest>>({ requestType: "INVITE", status: "PENDING" });
const workerForm = reactive<Partial<SystemProjectWorker>>({ enabled: true });
const shareForm = reactive<Partial<ResourceShare>>({ enabled: true });

type WorkerGroupOptionSource = Pick<SystemProjectWorkerOption,
  "workerGroupCode" | "workerCode" | "onlineInstanceCount" | "recentInstanceCount" | "boundToProject"> & {
    instances?: SystemWorkerInstance[];
  };

const workerGroupOptions = computed(() => {
  const source: WorkerGroupOptionSource[] = projectWorkerOptions.value.length > 0 ? projectWorkerOptions.value : projectWorkers.value;
  return source
    .map((item) => {
      const value = item.workerGroupCode || item.workerCode;
      const recentCount = item.recentInstanceCount ?? item.instances?.length ?? 0;
      return value
        ? {
          value,
          label: `${value} / 在线 ${item.onlineInstanceCount ?? 0} / 近期 ${recentCount} / ${item.boundToProject ? "已下发" : "未下发"}`,
        }
        : null;
    })
    .filter((item): item is { value: string; label: string } => item != null);
});
const workerDialogTitle = computed(() => (workerForm.id ? "编辑 Worker 组下发" : "下发 Worker 组"));
const shareTargetProjects = computed(() =>
  projectOptions.value.filter((item) => item.id != null && !sameEntityId(item.id, authStore.currentProjectId)),
);
const shareableResources = computed(() => shareOptionList(normalizeResourceType(shareForm.resourceType)));
const currentTabLoading = computed(() => tabLoading[activeTab.value]);

function resetForm(target: Record<string, unknown>, defaults: Record<string, unknown>) {
  Object.keys(target).forEach((key) => delete target[key]);
  Object.assign(target, defaults);
}

function normalizeSystemTab(value: unknown): SystemTabName {
  if (value === "users" || value === "registrationRequests") {
    return isSuperAdmin.value ? value : "tenants";
  }
  if (value === "tenants"
    || value === "projects"
    || value === "tenantMembers"
    || value === "projectMembers"
    || value === "requests"
    || value === "workers"
    || value === "shares") {
    return value;
  }
  return "tenants";
}

async function withTabLoading(tab: SystemTabName, action: () => Promise<void>, errorMessage: string) {
  tabLoading[tab] = true;
  try {
    await action();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : errorMessage);
  } finally {
    tabLoading[tab] = false;
  }
}

async function loadCurrentTab() {
  await loadTab(activeTab.value);
}

function requestCurrentTabReload() {
  if (pendingCurrentTabReload.value) {
    return;
  }
  pendingCurrentTabReload.value = true;
  nextTick(() => {
    pendingCurrentTabReload.value = false;
    if (!systemMounted.value || !authStore.isAuthenticated) {
      return;
    }
    loadCurrentTab();
  });
}

async function loadTab(tab: SystemTabName) {
  switch (tab) {
    case "users":
      await loadUsers();
      break;
    case "registrationRequests":
      await loadRegistrationRequests();
      break;
    case "tenants":
      await loadTenants();
      break;
    case "projects":
      await loadProjects();
      break;
    case "tenantMembers":
      await loadTenantMembers();
      break;
    case "projectMembers":
      await loadProjectMembers();
      break;
    case "requests":
      await loadProjectMemberRequests();
      break;
    case "workers":
      await loadProjectWorkers();
      break;
    case "shares":
      await loadResourceSharesTab();
      break;
    default:
      await loadTenants();
  }
}

async function loadUsers() {
  await withTabLoading("users", async () => {
    if (!isSuperAdmin.value) {
      users.value = [];
      systemPagination.users.total = 0;
      return;
    }
    const page = await studioApi.users.listPage({
      pageNo: systemPagination.users.page,
      pageSize: systemPagination.users.pageSize,
    }, LOCAL_LOADING_REQUEST);
    users.value = page.items || [];
    systemPagination.users.total = page.total || 0;
  }, "加载用户失败");
}

async function loadRegistrationRequests() {
  await withTabLoading("registrationRequests", async () => {
    if (!isSuperAdmin.value) {
      registrationRequests.value = [];
      systemPagination.registrationRequests.total = 0;
      return;
    }
    const page = await studioApi.system.userRegistrationRequests.listPage({
      pageNo: systemPagination.registrationRequests.page,
      pageSize: systemPagination.registrationRequests.pageSize,
    }, LOCAL_LOADING_REQUEST);
    registrationRequests.value = page.items || [];
    systemPagination.registrationRequests.total = page.total || 0;
  }, "加载注册登记失败");
}

async function loadTenants() {
  await withTabLoading("tenants", async () => {
    const page = await studioApi.system.tenants.listPage({
      pageNo: systemPagination.tenants.page,
      pageSize: systemPagination.tenants.pageSize,
    }, LOCAL_LOADING_REQUEST);
    tenants.value = page.items || [];
    systemPagination.tenants.total = page.total || 0;
  }, "加载租户失败");
}

async function loadProjects() {
  await withTabLoading("projects", async () => {
    const page = await studioApi.system.projects.listPage({
      pageNo: systemPagination.projects.page,
      pageSize: systemPagination.projects.pageSize,
    }, LOCAL_LOADING_REQUEST);
    projects.value = page.items || [];
    systemPagination.projects.total = page.total || 0;
  }, "加载项目失败");
}

async function loadTenantMembers() {
  await withTabLoading("tenantMembers", async () => {
    const page = await studioApi.system.tenantMembers.listPage({
      pageNo: systemPagination.tenantMembers.page,
      pageSize: systemPagination.tenantMembers.pageSize,
    }, LOCAL_LOADING_REQUEST);
    tenantMembers.value = page.items || [];
    systemPagination.tenantMembers.total = page.total || 0;
  }, "加载租户成员失败");
}

async function loadProjectMembers() {
  await withTabLoading("projectMembers", async () => {
    const currentProjectId = authStore.currentProjectId ?? undefined;
    if (currentProjectId == null) {
      projectMembers.value = [];
      systemPagination.projectMembers.total = 0;
      return;
    }
    const page = await studioApi.system.projectMembers.listPage({
      projectId: currentProjectId,
      pageNo: systemPagination.projectMembers.page,
      pageSize: systemPagination.projectMembers.pageSize,
    }, LOCAL_LOADING_REQUEST);
    projectMembers.value = page.items || [];
    systemPagination.projectMembers.total = page.total || 0;
  }, "加载项目成员失败");
}

async function loadProjectMemberRequests() {
  await withTabLoading("requests", async () => {
    const currentProjectId = authStore.currentProjectId ?? undefined;
    if (currentProjectId == null) {
      projectMemberRequests.value = [];
      systemPagination.requests.total = 0;
      return;
    }
    const page = await studioApi.system.projectMemberRequests.listPage({
      projectId: currentProjectId,
      pageNo: systemPagination.requests.page,
      pageSize: systemPagination.requests.pageSize,
    }, LOCAL_LOADING_REQUEST);
    projectMemberRequests.value = page.items || [];
    systemPagination.requests.total = page.total || 0;
  }, "加载申请 / 邀请失败");
}

async function loadProjectWorkers() {
  await withTabLoading("workers", async () => {
    const currentProjectId = authStore.currentProjectId ?? undefined;
    if (currentProjectId == null) {
      projectWorkers.value = [];
      systemPagination.workers.total = 0;
      return;
    }
    const page = await studioApi.system.projectWorkers.listPage({
      projectId: currentProjectId,
      pageNo: systemPagination.workers.page,
      pageSize: systemPagination.workers.pageSize,
    }, LOCAL_LOADING_REQUEST);
    projectWorkers.value = page.items || [];
    systemPagination.workers.total = page.total || 0;
  }, "加载 Worker 下发失败");
}

async function loadResourceSharesTab() {
  await withTabLoading("shares", async () => {
    await loadResourceSharesData();
  }, "加载资源共享失败");
}

async function loadProjectsData() {
  projectOptions.value = await studioApi.system.projects.options(LOCAL_LOADING_REQUEST);
}

async function loadResourceSharesData() {
  const currentProjectId = authStore.currentProjectId ?? undefined;
  if (currentProjectId == null) {
    resourceShares.value = [];
    resourceSharePagination.total = 0;
    return;
  }
  const page = await studioApi.system.resourceShares.listPage({
    projectId: currentProjectId,
    resourceType: shareFilters.resourceType,
    pageNo: resourceSharePagination.page,
    pageSize: resourceSharePagination.pageSize,
  }, LOCAL_LOADING_REQUEST);
  resourceShares.value = page.items || [];
  resourceSharePagination.total = page.total || 0;
}

async function loadShareResourceOptions(resourceType = shareFilters.resourceType, force = false) {
  const currentProjectId = authStore.currentProjectId ?? undefined;
  const normalizedType = normalizeResourceType(resourceType);
  if (currentProjectId == null) {
    resetShareResourceCache();
    return;
  }
  if (!normalizedType || (loadedShareResourceTypes[normalizedType] && !force)) {
    return;
  }
  shareResourceLoading.value = true;
  try {
    shareResourceOptions[normalizedType] = await studioApi.system.resourceShares.options({
      resourceType: normalizedType,
      projectId: currentProjectId,
    }, LOCAL_LOADING_REQUEST);
    loadedShareResourceTypes[normalizedType] = true;
  } finally {
    shareResourceLoading.value = false;
  }
}

function loadUsersForDialog() {
  if (userOptions.value.length === 0) {
    void studioApi.users.options(LOCAL_LOADING_REQUEST)
      .then((items) => {
        userOptions.value = items;
      })
      .catch((error) => {
        ElMessage.error(error instanceof Error ? error.message : "加载用户失败");
      });
  }
}

function loadProjectsForDialog() {
  if (projectOptions.value.length === 0) {
    void loadProjectsData().catch((error) => {
      ElMessage.error(error instanceof Error ? error.message : "加载项目失败");
    });
  }
}

function loadWorkerGroupsForDialog() {
  const currentProjectId = authStore.currentProjectId ?? undefined;
  if (currentProjectId == null || projectWorkerOptionsLoaded.value) {
    return;
  }
  void studioApi.system.projectWorkers.options(currentProjectId, LOCAL_LOADING_REQUEST)
    .then((items) => {
      projectWorkerOptions.value = items;
      projectWorkerOptionsLoaded.value = true;
    })
    .catch((error) => {
      ElMessage.error(error instanceof Error ? error.message : "加载 Worker 组失败");
    });
}

function loadShareResourcesForDialog() {
  void Promise.all([loadProjectsData(), loadShareResourceOptions(normalizeResourceType(shareForm.resourceType) || shareFilters.resourceType)]).catch((error) => {
    ElMessage.error(error instanceof Error ? error.message : "加载资源共享选项失败");
  });
}

function reloadResourceShares() {
  void withTabLoading("shares", async () => {
    await loadResourceSharesData();
  }, "加载资源共享失败");
}

function reloadPaginatedTab(tab: PaginatedSystemTabName) {
  switch (tab) {
    case "users":
      void loadUsers();
      break;
    case "registrationRequests":
      void loadRegistrationRequests();
      break;
    case "tenants":
      void loadTenants();
      break;
    case "projects":
      void loadProjects();
      break;
    case "tenantMembers":
      void loadTenantMembers();
      break;
    case "projectMembers":
      void loadProjectMembers();
      break;
    case "requests":
      void loadProjectMemberRequests();
      break;
    case "workers":
      void loadProjectWorkers();
      break;
  }
}

function handlePaginatedPageSizeChange(tab: PaginatedSystemTabName) {
  systemPagination[tab].page = 1;
  reloadPaginatedTab(tab);
}

type PaginationState = {
  page: number;
  total: number;
};

function findRowById<T extends { id?: EntityId | null }>(rowsRef: Ref<T[]>, id: EntityId | null | undefined) {
  if (id == null) {
    return undefined;
  }
  return rowsRef.value.find((item) => sameEntityId(item.id, id));
}

function patchRowById<T extends { id?: EntityId | null }>(
  rowsRef: Ref<T[]>,
  id: EntityId | null | undefined,
  patch: Partial<T>,
  keys: (keyof T)[],
) {
  const target = findRowById(rowsRef, id);
  if (!target) {
    return false;
  }
  const next: Partial<T> = {};
  for (const key of keys) {
    if (Object.prototype.hasOwnProperty.call(patch, key)) {
      const value = (patch as Record<string, unknown>)[key as string];
      if (value !== undefined) {
        (next as Record<string, unknown>)[key as string] = value;
      }
    }
  }
  Object.assign(target, next);
  return true;
}

function removeRowById<T extends { id?: EntityId | null }>(
  rowsRef: Ref<T[]>,
  pagination: PaginationState,
  id: EntityId | null | undefined,
) {
  if (id == null) {
    return false;
  }
  const beforeCount = rowsRef.value.length;
  rowsRef.value = rowsRef.value.filter((item) => !sameEntityId(item.id, id));
  if (rowsRef.value.length === beforeCount) {
    return false;
  }
  pagination.total = Math.max(0, pagination.total - 1);
  return true;
}

async function reloadWhenCurrentPageEmpty<T>(
  rowsRef: Ref<T[]>,
  pagination: PaginationState,
  loadFn: () => Promise<void>,
) {
  if (rowsRef.value.length > 0 || pagination.total <= 0) {
    return;
  }
  if (pagination.page > 1) {
    pagination.page -= 1;
  }
  await loadFn();
}

async function removePaginatedSystemRow<T extends { id?: EntityId | null }>(
  tab: PaginatedSystemTabName,
  rowsRef: Ref<T[]>,
  id: EntityId | null | undefined,
  loadFn: () => Promise<void>,
) {
  if (removeRowById(rowsRef, systemPagination[tab], id)) {
    await reloadWhenCurrentPageEmpty(rowsRef, systemPagination[tab], loadFn);
  }
}

async function removeResourceShareRow(id: EntityId | null | undefined) {
  if (removeRowById(resourceShares, resourceSharePagination, id)) {
    await reloadWhenCurrentPageEmpty(resourceShares, resourceSharePagination, loadResourceSharesData);
  }
}

function resetPaginatedSystemPages() {
  (Object.keys(systemPagination) as PaginatedSystemTabName[]).forEach((tab) => {
    systemPagination[tab].page = 1;
  });
}

function handleResourceShareTypeFilterChange() {
  resourceSharePagination.page = 1;
  reloadResourceShares();
}

function handleResourceSharePageSizeChange() {
  resourceSharePagination.page = 1;
  reloadResourceShares();
}

function handleShareFormResourceTypeChange() {
  shareForm.resourceId = undefined;
  void loadShareResourceOptions(normalizeResourceType(shareForm.resourceType)).catch((error) => {
    ElMessage.error(error instanceof Error ? error.message : "加载资源选项失败");
  });
}

function resetShareResourceCache() {
  Object.keys(shareResourceOptions).forEach((key) => delete shareResourceOptions[key]);
  Object.keys(loadedShareResourceTypes).forEach((key) => delete loadedShareResourceTypes[key]);
}

function requireCurrentProjectId() {
  if (authStore.currentProjectId == null) {
    throw new Error("请先选择当前项目");
  }
  return authStore.currentProjectId;
}

function userLabel(user: StudioUserOption) {
  return user.displayName ? `${user.displayName} (${user.username})` : user.username;
}

function resolveProjectLabel(projectId?: EntityId | null) {
  return resolveProjectName(projectOptions.value.length ? projectOptions.value : projects.value, projectId);
}

function resourceProjectLabel(projectId?: EntityId | null, projectName?: string | null) {
  return projectName || resolveProjectLabel(projectId);
}

function shareOptionList(resourceType: string) {
  const currentProjectId = authStore.currentProjectId;
  return (shareResourceOptions[resourceType] ?? [])
    .filter((item) => sameEntityId(item.projectId, currentProjectId))
    .map((item) => ({ id: item.id!, label: item.label }));
}

function resourceTypeLabel(resourceType?: string | null) {
  const normalizedType = normalizeResourceType(resourceType ?? undefined);
  const matched = resourceTypeOptions.find((item) => item.value === normalizedType);
  return matched?.label ?? (normalizedType || "-");
}

function resourceLabel(share: ResourceShare) {
  if (share.resourceLabel) {
    return share.resourceLabel;
  }
  const resourceType = normalizeResourceType(share.resourceType);
  const option = shareOptionList(resourceType).find((item) => sameEntityId(item.id, share.resourceId));
  return option?.label ?? `${resourceType || "RESOURCE"} #${share.resourceId ?? "-"}`;
}

async function refreshProfileIf(condition: boolean) {
  if (condition) {
    await authStore.refreshProfile();
  }
}

async function refreshProfileForUser(userId?: EntityId | null) {
  await refreshProfileIf(sameEntityId(userId, authStore.userId));
}

function openTenantDialog(row?: SystemTenant) {
  resetForm(tenantForm as Record<string, unknown>, { enabled: true });
  Object.assign(tenantForm, row ?? {});
  tenantForm.enabled = toBooleanFlag(tenantForm.enabled);
  tenantDialogOpen.value = true;
}

function openUserDialog(row?: StudioUserListView) {
  resetForm(userForm as Record<string, unknown>, { enabled: 1, passwordHash: "" });
  Object.assign(userForm, row ?? {});
  userForm.enabled = toBooleanFlag(userForm.enabled);
  userForm.passwordHash = "";
  const elinkUserId = normalizeElinkUserId(row?.elinkUserId);
  originalElinkUserId.value = elinkUserId;
  originalElinkUserName.value = row?.elinkUserName?.trim() || "";
  userForm.elinkUserId = elinkUserId || undefined;
  userForm.elinkUserName = originalElinkUserName.value || undefined;
  seedElinkUsers(elinkUserId ? [elinkUserId] : [], originalElinkUserName.value ? [originalElinkUserName.value] : []);
  userDialogOpen.value = true;
}

function normalizeElinkUserId(value?: string | null) {
  return value?.trim() || "";
}

function handleElinkUserDropdownVisible(visible: boolean) {
  if (visible) void loadElinkUsers("", true);
  else cleanupElinkUserRequest();
}

function searchElinkUsers(keyword: string) {
  const normalizedKeyword = keyword.trim();
  if (!normalizedKeyword && !elinkUserSearchKeyword) return;
  elinkUserSearchKeyword = normalizedKeyword;
  if (elinkUserSearchTimer) clearTimeout(elinkUserSearchTimer);
  elinkUserSearchTimer = setTimeout(() => {
    void loadElinkUsers(normalizedKeyword, !normalizedKeyword);
  }, ELINK_SEARCH_DEBOUNCE_MS);
}

async function loadElinkUsers(keyword = "", preferCache = false) {
  const selectedUserId = normalizeElinkUserId(userForm.elinkUserId);
  const selectedOption = elinkUserOptions.value.find((item) => item.userId === selectedUserId);
  const historicalName = selectedUserId === originalElinkUserId.value ? originalElinkUserName.value : "";
  try {
    const selectedIds = selectedUserId ? [selectedUserId] : [];
    const selectedNames = selectedUserId ? [selectedOption?.name || historicalName] : [];
    if (preferCache && !keyword.trim()) await ensureElinkUsers(selectedIds, selectedNames);
    else await queryElinkUsers(keyword, selectedIds, selectedNames);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.system.elinkDirectoryLoadFailed"));
  }
}

function cleanupElinkUserRequest() {
  if (elinkUserSearchTimer) clearTimeout(elinkUserSearchTimer);
  elinkUserSearchTimer = undefined;
  elinkUserSearchKeyword = "";
  cancelElinkUserQuery();
}

function openProjectDialog(row?: SystemProject) {
  resetForm(projectForm as Record<string, unknown>, { enabled: true, defaultProject: false });
  Object.assign(projectForm, row ?? {});
  projectForm.enabled = toBooleanFlag(projectForm.enabled);
  projectForm.defaultProject = toBooleanFlag(projectForm.defaultProject);
  projectDialogOpen.value = true;
}

function openTenantMemberDialog(row?: SystemTenantMember) {
  loadUsersForDialog();
  resetForm(tenantMemberForm as Record<string, unknown>, { status: "ACTIVE", roleCode: "TENANT_ADMIN" });
  Object.assign(tenantMemberForm, row ?? {});
  tenantMemberDialogOpen.value = true;
}

function openProjectMemberDialog(row?: SystemProjectMember) {
  loadUsersForDialog();
  resetForm(projectMemberForm as Record<string, unknown>, { status: "ACTIVE", roleCode: "PROJECT_MEMBER" });
  Object.assign(projectMemberForm, row ?? {});
  projectMemberDialogOpen.value = true;
}

function openRequestDialog(row?: SystemProjectMemberRequest) {
  loadUsersForDialog();
  resetForm(requestForm as Record<string, unknown>, { requestType: "INVITE", status: "PENDING" });
  Object.assign(requestForm, row ?? {});
  requestDialogOpen.value = true;
}

function normalizeRequestValue(value?: string | null) {
  return value?.trim().toUpperCase() ?? "";
}

function isApprovedStatus(value?: string | null) {
  const status = normalizeRequestValue(value);
  return status === "APPROVED" || status === "ACCEPTED";
}

function canReviewProjectRequest(row: SystemProjectMemberRequest) {
  return normalizeRequestValue(row.requestType) === "APPLY" && normalizeRequestValue(row.status) === "PENDING";
}

function workerRowKey(row: SystemProjectWorker) {
  return row.workerGroupCode || row.workerCode || String(row.id ?? "");
}

function workerInstances(row: SystemProjectWorker): SystemWorkerInstance[] {
  return Array.isArray(row.instances) ? row.instances : [];
}

function normalizeWorkerStatus(value?: string | null) {
  return value?.trim().toUpperCase() ?? "";
}

function workerGroupStatusLabel(row: SystemProjectWorker) {
  switch (normalizeWorkerStatus(row.displayStatus || row.status)) {
    case "ONLINE":
      return "在线";
    case "OFFLINE":
      return "离线";
    case "NO_INSTANCE":
      return "无实例";
    default:
      return row.displayStatus || row.status || "未知";
  }
}

function workerGroupTagType(row: SystemProjectWorker) {
  switch (normalizeWorkerStatus(row.displayStatus || row.status)) {
    case "ONLINE":
      return "success";
    case "OFFLINE":
      return "warning";
    default:
      return "info";
  }
}

function workerInstanceStatusLabel(instance: SystemWorkerInstance) {
  return instance.online ? "在线" : "离线";
}

function workerInstanceTagType(instance: SystemWorkerInstance) {
  return instance.online ? "success" : "info";
}

function workerKindLabel(value?: string | null) {
  switch (normalizeWorkerStatus(value)) {
    case "WORKER":
      return "Worker";
    case "DESKTOP":
      return "桌面运行时";
    case "ONLINE":
      return "Worker";
    default:
      return value || "-";
  }
}

function openWorkerDialog(row?: SystemProjectWorker) {
  loadWorkerGroupsForDialog();
  resetForm(workerForm as Record<string, unknown>, { enabled: true });
  Object.assign(workerForm, row ?? {});
  workerForm.workerGroupCode = workerForm.workerGroupCode || workerForm.workerCode;
  workerForm.enabled = row?.boundToProject ? toBooleanFlag(workerForm.enabled) : true;
  workerDialogOpen.value = true;
}

function openShareDialog(row?: ResourceShare) {
  loadProjectsForDialog();
  const resourceType = normalizeResourceType(row?.resourceType) || shareFilters.resourceType || DEFAULT_SHARE_RESOURCE_TYPE;
  resetForm(shareForm as Record<string, unknown>, {
    enabled: true,
    sourceProjectId: authStore.currentProjectId ?? undefined,
    resourceType,
  });
  Object.assign(shareForm, row ?? {});
  shareForm.resourceType = normalizeResourceType(shareForm.resourceType) || resourceType;
  shareForm.enabled = toBooleanFlag(shareForm.enabled);
  loadShareResourcesForDialog();
  shareDialogOpen.value = true;
}

async function saveTenant() {
  const payload = normalizeDeletedFlag<Partial<SystemTenant>>({
    ...tenantForm,
    enabled: toIntegerFlag(tenantForm.enabled),
  });
  await wrapSave(() => studioApi.system.tenants.save(payload), tenantDialogOpen, "租户保存成功", {
    onSaved: async (saved) => {
      if (!patchRowById(tenants, saved.id, saved, ["tenantCode", "tenantName", "description", "enabled", "updatedAt"])) {
        await loadTenants();
      }
    },
    refreshProfile: true,
  });
}

async function saveUser() {
  const selectedElinkUserId = normalizeElinkUserId(userForm.elinkUserId);
  const payload: Partial<StudioUser> = {
    ...userForm,
    enabled: toIntegerFlag(userForm.enabled),
    passwordHash: userForm.passwordHash?.trim() ? userForm.passwordHash.trim() : undefined,
  };
  delete payload.elinkUserId;
  delete payload.elinkUserName;
  delete payload.clearElinkUserBinding;
  if (selectedElinkUserId !== originalElinkUserId.value) {
    if (selectedElinkUserId) {
      payload.elinkUserId = selectedElinkUserId;
    } else if (originalElinkUserId.value) {
      payload.clearElinkUserBinding = true;
    }
  }
  try {
    const saved = await studioApi.users.save(payload);
    userOptions.value = [];
    if (!patchRowById(users, saved.id, saved, ["username", "displayName", "mobilePhone", "elinkUserId", "elinkUserName", "enabled", "updatedAt"])) {
      await loadUsers();
    }
    userDialogOpen.value = false;
    ElMessage.success("用户保存成功");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存失败");
  }
}

async function saveProject() {
  const payload = normalizeDeletedFlag<Partial<SystemProject>>({
    ...projectForm,
    enabled: toIntegerFlag(projectForm.enabled),
    defaultProject: toIntegerFlag(projectForm.defaultProject),
  });
  await wrapSave(() => studioApi.system.projects.save(payload), projectDialogOpen, "项目保存成功", {
    onSaved: async (saved) => {
      projectOptions.value = [];
      const current = findRowById(projects, saved.id);
      const defaultProjectChanged = current && current.defaultProject !== saved.defaultProject;
      if (defaultProjectChanged || !patchRowById(projects, saved.id, saved, ["projectCode", "projectName", "description", "defaultProject", "enabled", "updatedAt"])) {
        await loadProjects();
      }
    },
    refreshProfile: true,
  });
}

async function saveTenantMember() {
  const payload = normalizeDeletedFlag<Partial<SystemTenantMember>>({ ...tenantMemberForm });
  await wrapSave(() => studioApi.system.tenantMembers.save(payload), tenantMemberDialogOpen, "租户成员保存成功", {
    onSaved: async (saved) => {
      const current = findRowById(tenantMembers, saved.id);
      const userChanged = current && !sameEntityId(current.userId, saved.userId ?? payload.userId);
      if (userChanged || !patchRowById(tenantMembers, saved.id, saved, ["userId", "roleCode", "status", "updatedAt"])) {
        await loadTenantMembers();
      }
      await refreshProfileForUser(saved.userId ?? payload.userId);
    },
  });
}

async function saveProjectMember() {
  const payload = normalizeDeletedFlag<Partial<SystemProjectMember>>({
    ...projectMemberForm,
    projectId: requireCurrentProjectId(),
  });
  await wrapSave(() => studioApi.system.projectMembers.save(payload), projectMemberDialogOpen, "项目成员保存成功", {
    onSaved: async (saved) => {
      const current = findRowById(projectMembers, saved.id);
      const userChanged = current && !sameEntityId(current.userId, saved.userId ?? payload.userId);
      if (userChanged || !patchRowById(projectMembers, saved.id, saved, ["userId", "roleCode", "status", "updatedAt"])) {
        await loadProjectMembers();
      }
      await refreshProfileForUser(saved.userId ?? payload.userId);
    },
  });
}

async function saveProjectRequest() {
  const payload = normalizeDeletedFlag<Partial<SystemProjectMemberRequest>>({
    ...requestForm,
    projectId: requireCurrentProjectId(),
  });
  await wrapSave(() => studioApi.system.projectMemberRequests.save(payload), requestDialogOpen, "申请 / 邀请保存成功", {
    onSaved: async (saved) => {
      const current = findRowById(projectMemberRequests, saved.id);
      const userChanged = current && !sameEntityId(current.userId, saved.userId ?? payload.userId);
      if (userChanged || !patchRowById(projectMemberRequests, saved.id, saved, ["userId", "requestType", "status", "reason", "reviewComment", "updatedAt"])) {
        await loadProjectMemberRequests();
      }
      if (isApprovedStatus(saved.status ?? payload.status)) {
        await refreshProfileForUser(saved.userId ?? payload.userId);
      }
    },
  });
}

async function approveProjectRequest(row: SystemProjectMemberRequest) {
  const result = await ElMessageBox.prompt("可选填写审批备注", "通过项目加入申请", {
    confirmButtonText: "通过",
    cancelButtonText: "取消",
    inputValue: row.reviewComment ?? "",
    inputPlaceholder: "如无需备注可留空",
  }).catch((error: unknown) => {
    if (error === "cancel" || error === "close") {
      return null;
    }
    throw error;
  });
  if (result == null) {
    return;
  }
  const payload = normalizeDeletedFlag<Partial<SystemProjectMemberRequest>>({
    ...row,
    projectId: requireCurrentProjectId(),
    status: "APPROVED",
    reviewComment: result.value?.trim() || undefined,
  });
  const saved = await studioApi.system.projectMemberRequests.save(payload);
  patchRowById(projectMemberRequests, saved.id ?? row.id, saved, ["status", "reviewComment", "reviewerUserId", "reviewerUsername", "updatedAt"]);
  await refreshProfileForUser(saved.userId ?? payload.userId);
  ElMessage.success("项目加入申请已通过");
}

async function rejectProjectRequest(row: SystemProjectMemberRequest) {
  const result = await ElMessageBox.prompt("请填写拒绝原因", "拒绝项目加入申请", {
    confirmButtonText: "拒绝",
    cancelButtonText: "取消",
    inputType: "textarea",
    inputValue: row.reviewComment ?? "",
    inputPlaceholder: "请输入拒绝原因",
    inputValidator: (value) => (value?.trim() ? true : "请填写拒绝原因"),
  }).catch((error: unknown) => {
    if (error === "cancel" || error === "close") {
      return null;
    }
    throw error;
  });
  if (result == null) {
    return;
  }
  const payload = normalizeDeletedFlag<Partial<SystemProjectMemberRequest>>({
    ...row,
    projectId: requireCurrentProjectId(),
    status: "REJECTED",
    reviewComment: result.value.trim(),
  });
  const saved = await studioApi.system.projectMemberRequests.save(payload);
  patchRowById(projectMemberRequests, saved.id ?? row.id, saved, ["status", "reviewComment", "reviewerUserId", "reviewerUsername", "updatedAt"]);
  ElMessage.success("项目加入申请已拒绝");
}

async function saveProjectWorker() {
  const workerGroupCode = workerForm.workerGroupCode || workerForm.workerCode;
  const payload = normalizeDeletedFlag<Partial<SystemProjectWorker>>({
    id: workerForm.id,
    projectId: requireCurrentProjectId(),
    workerGroupCode,
    workerCode: workerGroupCode,
    enabled: toIntegerFlag(workerForm.enabled),
  });
  const current = findRowById(projectWorkers, workerForm.id);
  await wrapSave(() => studioApi.system.projectWorkers.save(payload), workerDialogOpen, "Worker 组绑定已保存", {
    onSaved: async (saved) => {
      projectWorkerOptionsLoaded.value = false;
      const savedWorkerGroupCode = saved.workerGroupCode || saved.workerCode || workerGroupCode;
      const currentWorkerGroupCode = current?.workerGroupCode || current?.workerCode;
      const workerGroupChanged = current && currentWorkerGroupCode !== savedWorkerGroupCode;
      if (workerGroupChanged || !patchRowById(projectWorkers, saved.id, saved, ["workerGroupCode", "workerCode", "enabled", "updatedAt"])) {
        await loadProjectWorkers();
      }
    },
  });
}

async function saveResourceShare() {
  const normalizedResourceType = normalizeResourceType(shareForm.resourceType) || DEFAULT_SHARE_RESOURCE_TYPE;
  const payload = normalizeDeletedFlag<Partial<ResourceShare>>({
    ...shareForm,
    sourceProjectId: requireCurrentProjectId(),
    resourceType: normalizedResourceType,
    enabled: toIntegerFlag(shareForm.enabled),
  });
  const current = findRowById(resourceShares, shareForm.id);
  const shouldResetSharePage = !current || normalizeResourceType(current.resourceType) !== normalizedResourceType;
  shareFilters.resourceType = normalizedResourceType;
  if (shouldResetSharePage) {
    resourceSharePagination.page = 1;
  }
  await wrapSave(() => studioApi.system.resourceShares.save(payload), shareDialogOpen, "资源共享已保存", {
    onSaved: async (saved) => {
      const labelHydrationChanged = current && (
        normalizeResourceType(current.resourceType) !== normalizedResourceType
        || !sameEntityId(current.resourceId, saved.resourceId ?? payload.resourceId)
        || !sameEntityId(current.targetProjectId, saved.targetProjectId ?? payload.targetProjectId)
      );
      if (labelHydrationChanged || !patchRowById(resourceShares, saved.id, saved, ["sourceProjectId", "targetProjectId", "resourceType", "resourceId", "enabled", "updatedAt"])) {
        await loadResourceSharesData();
      }
    },
  });
}

async function deleteTenant(row: SystemTenant) {
  await confirmDelete(`确认删除租户 ${row.tenantName} 吗？`, () => studioApi.system.tenants.delete(row.id!), {
    onDeleted: async () => {
      await removePaginatedSystemRow("tenants", tenants, row.id, loadTenants);
    },
    refreshProfile: true,
  });
}

async function deleteUser(row: StudioUserListView) {
  try {
    await ElMessageBox.confirm(`确认删除用户 ${row.username} 吗？`, t("common.confirm"), { type: "warning" });
    await studioApi.users.delete(row.id!);
    userOptions.value = userOptions.value.filter((item) => !sameEntityId(item.id, row.id));
    await removePaginatedSystemRow("users", users, row.id, loadUsers);
    ElMessage.success("删除成功");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除失败");
    }
  }
}

async function deleteProject(row: SystemProject) {
  await confirmDelete(`确认删除项目 ${row.projectName} 吗？`, () => studioApi.system.projects.delete(row.id!), {
    onDeleted: async () => {
      projectOptions.value = projectOptions.value.filter((item) => !sameEntityId(item.id, row.id));
      await removePaginatedSystemRow("projects", projects, row.id, loadProjects);
    },
    refreshProfile: true,
  });
}

async function deleteTenantMember(row: SystemTenantMember) {
  await confirmDelete(`确认移除租户成员 ${row.username} 吗？`, () => studioApi.system.tenantMembers.delete(row.id!), {
    onDeleted: async () => {
      await removePaginatedSystemRow("tenantMembers", tenantMembers, row.id, loadTenantMembers);
      await refreshProfileForUser(row.userId);
    },
  });
}

async function deleteProjectMember(row: SystemProjectMember) {
  await confirmDelete(`确认移除项目成员 ${row.username} 吗？`, () => studioApi.system.projectMembers.delete(row.id!), {
    onDeleted: async () => {
      await removePaginatedSystemRow("projectMembers", projectMembers, row.id, loadProjectMembers);
      await refreshProfileForUser(row.userId);
    },
  });
}

async function deleteProjectRequest(row: SystemProjectMemberRequest) {
  await confirmDelete(`确认删除记录 ${row.username} 吗？`, () => studioApi.system.projectMemberRequests.delete(row.id!), {
    onDeleted: async () => {
      await removePaginatedSystemRow("requests", projectMemberRequests, row.id, loadProjectMemberRequests);
    },
  });
}

async function deleteProjectWorker(row: SystemProjectWorker) {
  await confirmDelete(`确认解绑 Worker 组 ${row.workerGroupCode || row.workerCode} 吗？`, () => studioApi.system.projectWorkers.delete(row.id!), {
    onDeleted: async () => {
      projectWorkerOptionsLoaded.value = false;
      await removePaginatedSystemRow("workers", projectWorkers, row.id, loadProjectWorkers);
    },
  });
}

async function deleteResourceShare(row: ResourceShare) {
  await confirmDelete(`确认取消共享 ${resourceLabel(row)} 吗？`, () => studioApi.system.resourceShares.delete(row.id!), {
    onDeleted: async () => {
      await removeResourceShareRow(row.id);
    },
  });
}

async function approveRegistration(row: UserRegistrationRequestView) {
  try {
    const result = await ElMessageBox.prompt("可选填写审批备注", "通过注册登记", {
      confirmButtonText: t("common.confirm"),
      cancelButtonText: t("common.cancel"),
      inputPlaceholder: "审批通过后会自动创建账号",
    });
    const saved = await studioApi.system.userRegistrationRequests.approve(row.id!, {
      reviewComment: result.value?.trim() || undefined,
    });
    patchRowById(registrationRequests, saved.id ?? row.id, saved, [
      "status",
      "displayName",
      "reason",
      "reviewComment",
      "reviewerUserId",
      "reviewerUsername",
      "approvedUserId",
      "approvedUsername",
      "reviewedAt",
      "updatedAt",
    ]);
    userOptions.value = [];
    ElMessage.success("注册登记已通过");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "审批失败");
    }
  }
}

async function rejectRegistration(row: UserRegistrationRequestView) {
  try {
    const result = await ElMessageBox.prompt("请填写拒绝原因", "拒绝注册登记", {
      confirmButtonText: t("common.confirm"),
      cancelButtonText: t("common.cancel"),
      inputPlaceholder: "请输入拒绝原因",
      inputValidator: (value) => {
        if (!value || !value.trim()) {
          return "拒绝原因不能为空";
        }
        return true;
      },
    });
    const saved = await studioApi.system.userRegistrationRequests.reject(row.id!, {
      reviewComment: result.value.trim(),
    });
    patchRowById(registrationRequests, saved.id ?? row.id, saved, [
      "status",
      "displayName",
      "reason",
      "reviewComment",
      "reviewerUserId",
      "reviewerUsername",
      "reviewedAt",
      "updatedAt",
    ]);
    ElMessage.success("注册登记已拒绝");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "审批失败");
    }
  }
}

async function deleteRegistration(row: UserRegistrationRequestView) {
  await confirmDelete(`确认删除注册登记 ${row.username} 吗？`, () => studioApi.system.userRegistrationRequests.delete(row.id!), {
    onDeleted: async () => {
      await removePaginatedSystemRow("registrationRequests", registrationRequests, row.id, loadRegistrationRequests);
    },
  });
}

async function wrapSave<T>(
  action: () => Promise<T>,
  dialogFlag: { value: boolean },
  successMessage: string,
  options: {
    onSaved?: (saved: T) => void | Promise<void>;
    refreshProfile?: boolean | ((saved: T) => boolean);
  } = {},
) {
  try {
    const saved = await action();
    dialogFlag.value = false;
    const shouldRefreshProfile = typeof options.refreshProfile === "function"
      ? options.refreshProfile(saved)
      : Boolean(options.refreshProfile);
    await refreshProfileIf(shouldRefreshProfile);
    await options.onSaved?.(saved);
    ElMessage.success(successMessage);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存失败");
  }
}

async function confirmDelete(
  message: string,
  action: () => Promise<unknown>,
  options: {
    onDeleted?: () => void | Promise<void>;
    refreshProfile?: boolean;
  } = {},
) {
  try {
    await ElMessageBox.confirm(message, t("common.confirm"), { type: "warning" });
    await action();
    await refreshProfileIf(Boolean(options.refreshProfile));
    await options.onDeleted?.();
    ElMessage.success("删除成功");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除失败");
    }
  }
}

const systemActionHandlers: SystemActionHandlers = {
  openUserDialog,
  deleteUser,
  approveRegistration,
  rejectRegistration,
  deleteRegistration,
  openTenantDialog,
  deleteTenant,
  openProjectDialog,
  deleteProject,
  openTenantMemberDialog,
  deleteTenantMember,
  openProjectMemberDialog,
  deleteProjectMember,
  canReviewProjectRequest,
  approveProjectRequest,
  rejectProjectRequest,
  openRequestDialog,
  deleteProjectRequest,
  openWorkerDialog,
  deleteProjectWorker,
  openShareDialog,
  deleteResourceShare,
};

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], () => {
  resetShareResourceCache();
  projectWorkerOptions.value = [];
  projectWorkerOptionsLoaded.value = false;
  resetPaginatedSystemPages();
  resourceSharePagination.page = 1;
  if (authStore.isAuthenticated) {
    requestCurrentTabReload();
  }
});

watch([() => authStore.isAuthenticated, isSuperAdmin], ([authenticated, superAdmin], [previousAuthenticated, previousSuperAdmin]) => {
  if (!authenticated) {
    return;
  }
  if (!previousAuthenticated || (!previousSuperAdmin && superAdmin)) {
    requestCurrentTabReload();
  }
});

watch(() => route.query.tab, (value) => {
  activeTab.value = normalizeSystemTab(value);
}, { immediate: true });

watch(activeTab, (value) => {
  if (route.query.tab === value) {
    if (systemMounted.value && authStore.isAuthenticated) {
      requestCurrentTabReload();
    }
    return;
  }
  router.replace({
    query: {
      ...route.query,
      tab: value,
    },
  });
  if (systemMounted.value && authStore.isAuthenticated) {
    requestCurrentTabReload();
  }
});

watch(userDialogOpen, (visible) => {
  if (!visible) cleanupElinkUserRequest();
});

onMounted(() => {
  systemMounted.value = true;
  requestCurrentTabReload();
});
onBeforeUnmount(cleanupElinkUserRequest);
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.context-alert {
  margin-bottom: 14px;
  border: 1px solid rgba(198, 107, 0, 0.28);
  background: rgba(255, 243, 224, 0.88);
  color: #8a5200;
  border-radius: 14px;
  padding: 12px 14px;
}

.tab-toolbar {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-bottom: 14px;
}

.tab-toolbar--split {
  align-items: center;
  justify-content: space-between;
}

.tab-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.stack-cell {
  display: grid;
  gap: 4px;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.worker-instances {
  padding: 12px 18px;
  background: rgba(248, 250, 252, 0.82);
}

.mono-ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: bottom;
  white-space: nowrap;
  font-family: "JetBrains Mono", "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
}
</style>
