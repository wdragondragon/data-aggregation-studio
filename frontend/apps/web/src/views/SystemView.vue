<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>租户与项目管理</h3>
        <p>当前上下文：{{ authStore.currentTenantName ?? "-" }} / {{ authStore.currentProjectName ?? "-" }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="loadPage">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <el-alert
      v-if="!authStore.currentProjectId"
      class="context-alert"
      type="warning"
      :closable="false"
      title="请先选择当前项目后，再管理项目成员、申请邀请、Worker 下发和资源共享。"
    />

    <SectionCard title="组织管理" description="统一管理租户、项目、成员、申请邀请和 Worker 下发关系。">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="租户" name="tenants">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openTenantDialog()">新建租户</el-button>
          </div>
          <el-table :data="tenants" border size="small">
            <el-table-column prop="tenantCode" label="租户编码" min-width="160" />
            <el-table-column prop="tenantName" label="租户名称" min-width="180" />
            <el-table-column prop="description" label="描述" min-width="220" />
            <el-table-column label="启用" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? "开启" : "关闭" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openTenantDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="deleteTenant(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="项目" name="projects">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openProjectDialog()">新建项目</el-button>
          </div>
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
            <el-table-column label="操作" width="180" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openProjectDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="deleteProject(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="租户成员" name="tenantMembers">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openTenantMemberDialog()">新增成员</el-button>
          </div>
          <el-table :data="tenantMembers" border size="small">
            <el-table-column prop="username" label="用户名" min-width="160" />
            <el-table-column prop="displayName" label="显示名" min-width="180" />
            <el-table-column prop="roleCode" label="角色" min-width="160" />
            <el-table-column prop="status" label="状态" width="110" align="center" />
            <el-table-column label="操作" width="180" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openTenantMemberDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="deleteTenantMember(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="项目成员" name="projectMembers">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openProjectMemberDialog()">新增项目成员</el-button>
          </div>
          <el-table :data="projectMembers" border size="small">
            <el-table-column prop="username" label="用户名" min-width="160" />
            <el-table-column prop="displayName" label="显示名" min-width="180" />
            <el-table-column prop="projectName" label="项目" min-width="180" />
            <el-table-column prop="roleCode" label="角色" min-width="160" />
            <el-table-column prop="status" label="状态" width="110" align="center" />
            <el-table-column label="操作" width="180" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openProjectMemberDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="deleteProjectMember(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="申请 / 邀请" name="requests">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openRequestDialog()">新增邀请</el-button>
          </div>
          <el-table :data="projectMemberRequests" border size="small">
            <el-table-column prop="username" label="用户" min-width="160" />
            <el-table-column prop="projectName" label="项目" min-width="180" />
            <el-table-column prop="requestType" label="类型" width="120" align="center" />
            <el-table-column prop="status" label="状态" width="120" align="center" />
            <el-table-column prop="inviterUsername" label="邀请人" min-width="140" />
            <el-table-column prop="reviewerUsername" label="审批人" min-width="140" />
            <el-table-column prop="reason" label="原因" min-width="220" />
            <el-table-column label="操作" width="180" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openRequestDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="deleteProjectRequest(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="Worker 下发" name="workers">
          <div class="tab-toolbar">
            <el-button type="primary" @click="openWorkerDialog()">绑定 Worker</el-button>
          </div>
          <el-table :data="projectWorkers" border size="small">
            <el-table-column prop="workerCode" label="Worker 编码" min-width="180" />
            <el-table-column prop="workerKind" label="类型" width="110" align="center" />
            <el-table-column prop="hostName" label="主机" min-width="180" />
            <el-table-column prop="status" label="状态" width="110" align="center" />
            <el-table-column prop="lastHeartbeatAt" label="最近心跳" min-width="180" />
            <el-table-column label="已下发" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.boundToProject ? 'success' : 'info'">{{ row.boundToProject ? "是" : "否" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openWorkerDialog(row)">编辑</el-button>
                <el-button v-if="row.id" link type="danger" @click="deleteProjectWorker(row)">解绑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="资源共享" name="shares">
          <div class="tab-toolbar">
            <el-button type="primary" :disabled="!authStore.currentProjectId" @click="openShareDialog()">共享资源</el-button>
          </div>
          <el-table :data="resourceShares" border size="small">
            <el-table-column prop="resourceType" label="资源类型" width="150" align="center" />
            <el-table-column label="资源" min-width="220">
              <template #default="{ row }">
                <div class="stack-cell">
                  <span>{{ resourceLabel(row) }}</span>
                  <span class="cell-subtle">来源项目：{{ resolveProjectLabel(row.sourceProjectId) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="共享到项目" min-width="180">
              <template #default="{ row }">
                {{ resolveProjectLabel(row.targetProjectId) }}
              </template>
            </el-table-column>
            <el-table-column label="启用" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? "开启" : "关闭" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openShareDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="deleteResourceShare(row)">取消共享</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </SectionCard>

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
            <el-option v-for="user in users" :key="user.id" :label="userLabel(user)" :value="user.id" />
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
            <el-option v-for="user in users" :key="user.id" :label="userLabel(user)" :value="user.id" />
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
            <el-option v-for="user in users" :key="user.id" :label="userLabel(user)" :value="user.id" />
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

    <el-dialog v-model="workerDialogOpen" title="Worker 下发" width="480px">
      <el-form label-position="top">
        <el-form-item label="Worker 编码">
          <el-select v-model="workerForm.workerCode" filterable allow-create default-first-option style="width: 100%">
            <el-option v-for="worker in workerCodeOptions" :key="worker" :label="worker" :value="worker" />
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
          <el-select v-model="shareForm.resourceType" style="width: 100%">
            <el-option v-for="item in resourceTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="资源">
          <el-select v-model="shareForm.resourceId" filterable style="width: 100%">
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
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  CollectionTaskDefinitionView,
  DataModelDefinition,
  DataSourceDefinition,
  EntityId,
  ResourceShare,
  StudioUser,
  WorkflowDefinitionView,
  SystemProject,
  SystemProjectMember,
  SystemProjectMemberRequest,
  SystemProjectWorker,
  SystemTenant,
  SystemTenantMember,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { resolveProjectName, sameEntityId } from "@/utils/studio";

const { t } = useI18n();
const authStore = useAuthStore();
const activeTab = ref("tenants");
const tenants = ref<SystemTenant[]>([]);
const projects = ref<SystemProject[]>([]);
const users = ref<StudioUser[]>([]);
const tenantMembers = ref<SystemTenantMember[]>([]);
const projectMembers = ref<SystemProjectMember[]>([]);
const projectMemberRequests = ref<SystemProjectMemberRequest[]>([]);
const projectWorkers = ref<SystemProjectWorker[]>([]);
const resourceShares = ref<ResourceShare[]>([]);
const datasourceResources = ref<DataSourceDefinition[]>([]);
const modelResources = ref<DataModelDefinition[]>([]);
const taskResources = ref<CollectionTaskDefinitionView[]>([]);
const workflowResources = ref<WorkflowDefinitionView[]>([]);

const tenantDialogOpen = ref(false);
const projectDialogOpen = ref(false);
const tenantMemberDialogOpen = ref(false);
const projectMemberDialogOpen = ref(false);
const requestDialogOpen = ref(false);
const workerDialogOpen = ref(false);
const shareDialogOpen = ref(false);

const tenantForm = reactive<Partial<SystemTenant>>({ enabled: true });
const projectForm = reactive<Partial<SystemProject>>({ enabled: true, defaultProject: false });
const tenantMemberForm = reactive<Partial<SystemTenantMember>>({ status: "ACTIVE", roleCode: "TENANT_ADMIN" });
const projectMemberForm = reactive<Partial<SystemProjectMember>>({ status: "ACTIVE", roleCode: "PROJECT_MEMBER" });
const requestForm = reactive<Partial<SystemProjectMemberRequest>>({ requestType: "INVITE", status: "PENDING" });
const workerForm = reactive<Partial<SystemProjectWorker>>({ enabled: true });
const shareForm = reactive<Partial<ResourceShare>>({ enabled: true });

const workerCodeOptions = computed(() => Array.from(new Set(projectWorkers.value.map((item) => item.workerCode).filter(Boolean))) as string[]);
const resourceTypeOptions = [
  { label: "数据源", value: "DATASOURCE" },
  { label: "模型", value: "DATA_MODEL" },
  { label: "采集任务", value: "COLLECTION_TASK" },
  { label: "工作流", value: "WORKFLOW" },
] as const;
const shareTargetProjects = computed(() =>
  projects.value.filter((item) => item.id != null && !sameEntityId(item.id, authStore.currentProjectId)),
);
const shareableResources = computed(() => shareOptionList(normalizeResourceType(shareForm.resourceType)));

function resetForm(target: Record<string, unknown>, defaults: Record<string, unknown>) {
  Object.keys(target).forEach((key) => delete target[key]);
  Object.assign(target, defaults);
}

async function loadPage() {
  try {
    const currentProjectId = authStore.currentProjectId ?? undefined;
    const [tenantData, projectData, userData, tenantMemberData, projectMemberData, requestData, workerData, shareData, datasourceData, modelData, taskData, workflowData] = await Promise.all([
      studioApi.system.tenants.list(),
      studioApi.system.projects.list(),
      studioApi.users.list(),
      studioApi.system.tenantMembers.list(),
      currentProjectId == null ? Promise.resolve([] as SystemProjectMember[]) : studioApi.system.projectMembers.list(currentProjectId),
      currentProjectId == null ? Promise.resolve([] as SystemProjectMemberRequest[]) : studioApi.system.projectMemberRequests.list(currentProjectId),
      currentProjectId == null ? Promise.resolve([] as SystemProjectWorker[]) : studioApi.system.projectWorkers.list(currentProjectId),
      currentProjectId == null ? Promise.resolve([] as ResourceShare[]) : studioApi.system.resourceShares.list({ projectId: currentProjectId }),
      currentProjectId == null ? Promise.resolve([] as DataSourceDefinition[]) : studioApi.datasources.list(),
      currentProjectId == null ? Promise.resolve([] as DataModelDefinition[]) : studioApi.models.list(),
      currentProjectId == null ? Promise.resolve([] as CollectionTaskDefinitionView[]) : studioApi.collectionTasks.list(),
      currentProjectId == null ? Promise.resolve([] as WorkflowDefinitionView[]) : studioApi.workflows.list(),
    ]);
    tenants.value = tenantData;
    projects.value = projectData;
    users.value = userData;
    tenantMembers.value = tenantMemberData;
    projectMembers.value = projectMemberData;
    projectMemberRequests.value = requestData;
    projectWorkers.value = workerData;
    resourceShares.value = shareData;
    datasourceResources.value = datasourceData;
    modelResources.value = modelData;
    taskResources.value = taskData;
    workflowResources.value = workflowData;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载系统管理数据失败");
  }
}

function requireCurrentProjectId() {
  if (authStore.currentProjectId == null) {
    throw new Error("请先选择当前项目");
  }
  return authStore.currentProjectId;
}

function userLabel(user: StudioUser) {
  return user.displayName ? `${user.displayName} (${user.username})` : user.username;
}

function resolveProjectLabel(projectId?: EntityId | null) {
  return resolveProjectName(projects.value, projectId);
}

function normalizeResourceType(value?: string) {
  return String(value ?? "").trim().toUpperCase();
}

function shareOptionList(resourceType: string) {
  const currentProjectId = authStore.currentProjectId;
  switch (resourceType) {
    case "DATASOURCE":
      return datasourceResources.value
        .filter((item) => sameEntityId(item.projectId, currentProjectId))
        .map((item) => ({ id: item.id!, label: `${item.name} (${item.typeCode})` }));
    case "DATA_MODEL":
      return modelResources.value
        .filter((item) => sameEntityId(item.projectId, currentProjectId))
        .map((item) => ({ id: item.id!, label: `${item.name} / ${item.physicalLocator}` }));
    case "COLLECTION_TASK":
      return taskResources.value
        .filter((item) => sameEntityId(item.projectId, currentProjectId))
        .map((item) => ({ id: item.id!, label: item.name }));
    case "WORKFLOW":
      return workflowResources.value
        .filter((item) => sameEntityId(item.projectId, currentProjectId))
        .map((item) => ({ id: item.id!, label: `${item.name} (${item.code})` }));
    default:
      return [];
  }
}

function resourceLabel(share: ResourceShare) {
  const resourceType = normalizeResourceType(share.resourceType);
  const option = shareOptionList(resourceType).find((item) => sameEntityId(item.id, share.resourceId));
  return option?.label ?? `${resourceType || "RESOURCE"} #${share.resourceId ?? "-"}`;
}

function openTenantDialog(row?: SystemTenant) {
  resetForm(tenantForm as Record<string, unknown>, { enabled: true });
  Object.assign(tenantForm, row ?? {});
  tenantDialogOpen.value = true;
}

function openProjectDialog(row?: SystemProject) {
  resetForm(projectForm as Record<string, unknown>, { enabled: true, defaultProject: false });
  Object.assign(projectForm, row ?? {});
  projectDialogOpen.value = true;
}

function openTenantMemberDialog(row?: SystemTenantMember) {
  resetForm(tenantMemberForm as Record<string, unknown>, { status: "ACTIVE", roleCode: "TENANT_ADMIN" });
  Object.assign(tenantMemberForm, row ?? {});
  tenantMemberDialogOpen.value = true;
}

function openProjectMemberDialog(row?: SystemProjectMember) {
  resetForm(projectMemberForm as Record<string, unknown>, { status: "ACTIVE", roleCode: "PROJECT_MEMBER" });
  Object.assign(projectMemberForm, row ?? {});
  projectMemberDialogOpen.value = true;
}

function openRequestDialog(row?: SystemProjectMemberRequest) {
  resetForm(requestForm as Record<string, unknown>, { requestType: "INVITE", status: "PENDING" });
  Object.assign(requestForm, row ?? {});
  requestDialogOpen.value = true;
}

function openWorkerDialog(row?: SystemProjectWorker) {
  resetForm(workerForm as Record<string, unknown>, { enabled: true });
  Object.assign(workerForm, row ?? {});
  workerDialogOpen.value = true;
}

function openShareDialog(row?: ResourceShare) {
  resetForm(shareForm as Record<string, unknown>, { enabled: true, sourceProjectId: authStore.currentProjectId ?? undefined });
  Object.assign(shareForm, row ?? {});
  shareDialogOpen.value = true;
}

async function saveTenant() {
  await wrapSave(() => studioApi.system.tenants.save(tenantForm), tenantDialogOpen, "租户保存成功");
}

async function saveProject() {
  await wrapSave(() => studioApi.system.projects.save(projectForm), projectDialogOpen, "项目保存成功");
}

async function saveTenantMember() {
  await wrapSave(() => studioApi.system.tenantMembers.save(tenantMemberForm), tenantMemberDialogOpen, "租户成员保存成功");
}

async function saveProjectMember() {
  const payload: Partial<SystemProjectMember> = { ...projectMemberForm, projectId: requireCurrentProjectId() };
  await wrapSave(() => studioApi.system.projectMembers.save(payload), projectMemberDialogOpen, "项目成员保存成功");
}

async function saveProjectRequest() {
  const payload: Partial<SystemProjectMemberRequest> = { ...requestForm, projectId: requireCurrentProjectId() };
  await wrapSave(() => studioApi.system.projectMemberRequests.save(payload), requestDialogOpen, "申请 / 邀请保存成功");
}

async function saveProjectWorker() {
  const payload: Partial<SystemProjectWorker> = { ...workerForm, projectId: requireCurrentProjectId() };
  await wrapSave(() => studioApi.system.projectWorkers.save(payload), workerDialogOpen, "Worker 绑定已保存");
}

async function saveResourceShare() {
  const payload: Partial<ResourceShare> = {
    ...shareForm,
    sourceProjectId: requireCurrentProjectId(),
    resourceType: normalizeResourceType(shareForm.resourceType),
  };
  await wrapSave(() => studioApi.system.resourceShares.save(payload), shareDialogOpen, "资源共享已保存");
}

async function deleteTenant(row: SystemTenant) {
  await confirmDelete(`确认删除租户 ${row.tenantName} 吗？`, () => studioApi.system.tenants.delete(row.id!));
}

async function deleteProject(row: SystemProject) {
  await confirmDelete(`确认删除项目 ${row.projectName} 吗？`, () => studioApi.system.projects.delete(row.id!));
}

async function deleteTenantMember(row: SystemTenantMember) {
  await confirmDelete(`确认移除租户成员 ${row.username} 吗？`, () => studioApi.system.tenantMembers.delete(row.id!));
}

async function deleteProjectMember(row: SystemProjectMember) {
  await confirmDelete(`确认移除项目成员 ${row.username} 吗？`, () => studioApi.system.projectMembers.delete(row.id!));
}

async function deleteProjectRequest(row: SystemProjectMemberRequest) {
  await confirmDelete(`确认删除记录 ${row.username} 吗？`, () => studioApi.system.projectMemberRequests.delete(row.id!));
}

async function deleteProjectWorker(row: SystemProjectWorker) {
  await confirmDelete(`确认解绑 Worker ${row.workerCode} 吗？`, () => studioApi.system.projectWorkers.delete(row.id!));
}

async function deleteResourceShare(row: ResourceShare) {
  await confirmDelete(`确认取消共享 ${resourceLabel(row)} 吗？`, () => studioApi.system.resourceShares.delete(row.id!));
}

async function wrapSave(action: () => Promise<unknown>, dialogFlag: { value: boolean }, successMessage: string) {
  try {
    await action();
    dialogFlag.value = false;
    ElMessage.success(successMessage);
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存失败");
  }
}

async function confirmDelete(message: string, action: () => Promise<unknown>) {
  try {
    await ElMessageBox.confirm(message, t("common.confirm"), { type: "warning" });
    await action();
    ElMessage.success("删除成功");
    await loadPage();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除失败");
    }
  }
}

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], () => {
  if (authStore.isAuthenticated) {
    loadPage();
  }
});

onMounted(() => {
  if (authStore.isAuthenticated) {
    loadPage();
  }
});
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
}

.tab-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 14px;
}

.stack-cell {
  display: grid;
  gap: 4px;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}
</style>
