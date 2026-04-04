<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.workflows.heading") }}</h3>
        <p>{{ t("web.workflows.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" @click="router.push('/workflows/new')">{{ t("common.newWorkflow") }}</el-button>
        <el-button plain @click="loadWorkflows">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.workflows.registryTitle')" :description="t('web.workflows.registryDescription')">
      <template v-if="workflows.length">
        <el-table :data="workflows" border>
          <el-table-column prop="code" :label="t('web.workflows.code')" min-width="150" />
          <el-table-column :label="t('web.workflows.name')" min-width="220">
            <template #default="{ row }">
              <el-button link type="primary" class="workflow-name-link" @click="viewWorkflow(row)">
                {{ row.name }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.workflows.status')" width="120">
            <template #default="{ row }">
              <StatusPill :label="row.published ? t('common.published') : t('common.draft')" :tone="row.published ? 'success' : 'warning'" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.workflows.scheduleEnabled')" width="120">
            <template #default="{ row }">
              <StatusPill
                :label="row.schedule?.enabled ? t('common.on') : t('common.off')"
                :tone="row.schedule?.enabled ? 'success' : 'primary'"
              />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.actions')" width="380">
            <template #default="{ row }">
              <el-button link type="primary" @click="editWorkflow(row)">{{ t("common.edit") }}</el-button>
              <el-button link @click="viewWorkflowLogs(row)">{{ t("web.workflows.logsEntry") }}</el-button>
              <el-button link type="success" :disabled="!row.id" @click="publishWorkflow(row)">{{ t("common.publish") }}</el-button>
              <el-button link type="warning" :disabled="!row.id" @click="triggerWorkflow(row)">{{ t("common.trigger") }}</el-button>
              <el-button link type="danger" :disabled="!row.id" @click="deleteWorkflow(row)">{{ t("common.delete") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <div v-else class="workflow-empty-state">
        <el-empty :description="t('web.workflows.emptyDescription')" />
        <el-button type="primary" @click="router.push('/workflows/new')">{{ t("common.newWorkflow") }}</el-button>
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { WorkflowDefinitionView } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";

const { t } = useI18n();
const router = useRouter();
const workflows = ref<WorkflowDefinitionView[]>([]);

async function loadWorkflows() {
  try {
    workflows.value = await studioApi.workflows.list();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  }
}

function editWorkflow(workflow: WorkflowDefinitionView) {
  router.push(`/workflows/${workflow.id}/edit`);
}

function viewWorkflow(workflow: WorkflowDefinitionView) {
  router.push(`/workflows/${workflow.id}`);
}

function viewWorkflowLogs(workflow: WorkflowDefinitionView) {
  router.push({
    path: "/runs",
    query: {
      workflowDefinitionId: String(workflow.id ?? ""),
    },
  });
}

async function publishWorkflow(workflow: WorkflowDefinitionView) {
  if (!workflow.id) {
    return;
  }
  try {
    await studioApi.workflows.publish(workflow.id);
    ElMessage.success(t("web.workflows.publishSuccess"));
    await loadWorkflows();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.publishFailed"));
  }
}

async function triggerWorkflow(workflow: WorkflowDefinitionView) {
  if (!workflow.id) {
    return;
  }
  try {
    await studioApi.workflows.trigger(workflow.id);
    ElMessage.success(t("web.workflows.triggerSuccess"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.triggerFailed"));
  }
}

async function deleteWorkflow(workflow: WorkflowDefinitionView) {
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
  padding: 0;
  font-weight: 600;
}
</style>
