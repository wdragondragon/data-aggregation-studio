<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.workflows.heading") }}</h3>
        <p>{{ t("web.workflows.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" :disabled="!authStore.currentProjectId" @click="router.push('/workflows/new')">{{ t("common.newWorkflow") }}</el-button>
        <el-button plain @click="loadWorkflows">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.workflows.registryTitle')" :description="t('web.workflows.registryDescription')">
      <template v-if="workflows.length">
        <StudioTableShell min-width="1280px">
          <el-table
            :data="pagedWorkflows"
            border
            size="small"
            table-layout="fixed"
            scrollbar-always-on
            max-height="calc(100vh - 320px)"
            class="workflow-table"
          >
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">
              {{ getPaginatedRowNumber(workflowPagination, $index) }}
            </template>
          </el-table-column>
          <el-table-column :label="t('web.workflows.code')" min-width="190">
            <template #default="{ row }">
              <span class="workflow-code-text">{{ row.code || t("common.none") }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.workflows.name')" min-width="260">
            <template #default="{ row }">
              <div class="stack-cell">
                <el-button link type="primary" class="workflow-name-link" @click="viewWorkflow(row)">
                  {{ row.name }}
                </el-button>
                <FollowToggleButton v-if="row.id" :target-type="STUDIO_RESOURCE_TYPE.WORKFLOW" :target-id="row.id" />
                <span v-if="isSharedWorkflow(row)" class="cell-subtle">共享工作流只读</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="所属项目" min-width="170">
            <template #default="{ row }">
              <div class="stack-cell">
                <span>{{ resolveProjectLabel(row.projectId) }}</span>
                <span class="cell-subtle">{{ isSharedWorkflow(row) ? "共享来源" : "当前项目" }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.workflows.status')" width="120" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill :label="row.published ? t('common.published') : t('common.draft')" :tone="row.published ? 'success' : 'warning'" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.workflows.scheduleEnabled')" width="120" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill
                :label="row.schedule?.enabled ? t('common.on') : t('common.off')"
                :tone="row.schedule?.enabled ? 'success' : 'primary'"
              />
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" min-width="180" show-overflow-tooltip />
          <el-table-column prop="updatedAt" label="更新时间" min-width="180" show-overflow-tooltip />
          <el-table-column :label="t('web.metadata.actions')" width="150" align="center" header-align="center" fixed="right">
            <template #default="{ row }">
              <OverflowActionGroup :items="buildWorkflowActions(row)" />
            </template>
          </el-table-column>
          </el-table>
        </StudioTableShell>
        <div class="table-pagination">
          <el-pagination
            v-model:current-page="workflowPagination.page"
            v-model:page-size="workflowPagination.pageSize"
            background
            layout="total, sizes, prev, pager, next"
            :page-sizes="[10, 20, 50, 100]"
            :total="workflows.length"
          />
        </div>
      </template>
      <div v-else class="workflow-empty-state">
        <el-empty :description="t('web.workflows.emptyDescription')" />
        <el-button type="primary" @click="router.push('/workflows/new')">{{ t("common.newWorkflow") }}</el-button>
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { WorkflowListView } from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";
import FollowToggleButton from "@/components/FollowToggleButton.vue";
import { useAuthStore } from "@/stores/auth";
import { getPaginatedRowNumber, useClientPagination } from "@/composables/useClientPagination";
import { STUDIO_RESOURCE_TYPE } from "@/constants/studioDomain";
import { isSharedFromAnotherProject, resolveProjectName } from "@/utils/studio";

const { t } = useI18n();
const router = useRouter();
const authStore = useAuthStore();
const workflows = ref<WorkflowListView[]>([]);
const { pagination: workflowPagination, pagedItems: pagedWorkflows, resetPagination: resetWorkflowPagination } = useClientPagination(workflows);

async function loadWorkflows() {
  try {
    workflows.value = await studioApi.workflows.list();
    resetWorkflowPagination();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  }
}

function editWorkflow(workflow: WorkflowListView) {
  router.push(`/workflows/${workflow.id}/edit`);
}

function viewWorkflow(workflow: WorkflowListView) {
  router.push(`/workflows/${workflow.id}`);
}

function viewWorkflowLogs(workflow: WorkflowListView) {
  router.push({
    path: "/runs",
    query: {
      workflowDefinitionId: String(workflow.id ?? ""),
    },
  });
}

function buildWorkflowActions(workflow: WorkflowListView) {
  const shared = isSharedWorkflow(workflow);
  return [
    { key: "edit", label: t("common.edit"), type: "primary", disabled: shared, onClick: () => editWorkflow(workflow) },
    { key: "logs", label: t("web.workflows.logsEntry"), onClick: () => viewWorkflowLogs(workflow) },
    { key: "publish", label: t("common.publish"), type: "success", disabled: shared || !workflow.id, onClick: () => publishWorkflow(workflow) },
    { key: "trigger", label: t("common.trigger"), type: "warning", disabled: !workflow.id, onClick: () => triggerWorkflow(workflow) },
    { key: "delete", label: t("common.delete"), type: "danger", disabled: shared || !workflow.id, onClick: () => deleteWorkflow(workflow) },
  ];
}

function resolveProjectLabel(projectId?: string | number) {
  return resolveProjectName(authStore.projects, projectId);
}

function isSharedWorkflow(workflow: WorkflowListView) {
  return isSharedFromAnotherProject(authStore.currentProjectId, workflow.projectId);
}

async function publishWorkflow(workflow: WorkflowListView) {
  if (!workflow.id) {
    return;
  }
  try {
    const updated = await studioApi.workflows.publish(workflow.id);
    patchWorkflowRow(workflow, updated);
    ElMessage.success(t("web.workflows.publishSuccess"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.publishFailed"));
  }
}

function patchWorkflowRow(workflow: WorkflowListView, patch: Partial<WorkflowListView>) {
  const target = workflows.value.find((item) => item.id === workflow.id);
  if (!target) {
    return;
  }
  const keys: (keyof WorkflowListView)[] = [
    "updatedAt",
    "code",
    "name",
    "versionId",
    "versionNumber",
    "published",
    "schedule",
  ];
  const next: Partial<WorkflowListView> = {};
  for (const key of keys) {
    if (Object.prototype.hasOwnProperty.call(patch, key)) {
      (next as Record<string, unknown>)[key] = (patch as Record<string, unknown>)[key];
    }
  }
  Object.assign(target, next);
}

async function triggerWorkflow(workflow: WorkflowListView) {
  if (!workflow.id) {
    return;
  }
  try {
    await studioApi.workflows.trigger(workflow.id);
    ElMessage.success(t("web.workflows.triggerSuccess"));
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    ElMessage.error(message.includes("Workflow already has an active run") ? t("web.workflows.activeRunTriggerHint") : message || t("web.workflows.triggerFailed"));
  }
}

async function deleteWorkflow(workflow: WorkflowListView) {
  if (!workflow.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.workflows.deleteConfirmMessage", { name: workflow.name }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.workflows.delete(workflow.id);
    ElMessage.success(t("web.workflows.deleteSuccess"));
    await loadWorkflows();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.workflows.deleteFailed"));
    }
  }
}

onMounted(loadWorkflows);

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], () => {
  if (authStore.isAuthenticated) {
    loadWorkflows();
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

.workflow-empty-state {
  display: flex;
  flex-direction: column;
  gap: 16px;
  align-items: center;
  justify-content: center;
  min-height: 240px;
}

.workflow-name-link {
  align-items: flex-start;
  justify-content: flex-start;
  height: auto;
  max-width: 100%;
  padding: 0;
  font-weight: 600;
  line-height: 1.35;
  text-align: left;
  white-space: normal;
}

.workflow-name-link :deep(span) {
  display: inline;
  min-width: 0;
  max-width: 100%;
  overflow-wrap: anywhere;
  white-space: normal;
}

.workflow-table {
  width: 100%;
}

.workflow-table :deep(.cell) {
  white-space: normal;
}

.workflow-code-text {
  display: inline-block;
  max-width: 100%;
  overflow-wrap: anywhere;
  line-height: 1.35;
}

.stack-cell {
  display: grid;
  gap: 4px;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

@media (max-width: 960px) {
  .table-pagination {
    justify-content: flex-start;
  }
}
</style>
