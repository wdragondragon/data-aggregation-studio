<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.accessCenter.heading") }}</h3>
        <p>{{ t("web.accessCenter.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain :loading="loading" @click="loadOverview">{{ t("common.refresh") }}</el-button>
        <el-button type="primary" :loading="loading" @click="refreshPermissions">{{ t("web.accessCenter.refreshPermissions") }}</el-button>
      </div>
    </div>

    <el-alert
      :title="t('web.accessCenter.emptyContextTitle')"
      :description="t('web.accessCenter.emptyContextDescription')"
      type="info"
      :closable="false"
      show-icon
    />

    <SectionCard :title="t('web.accessCenter.pendingTitle')" :description="t('web.accessCenter.pendingDescription')">
      <el-table v-if="requests.length" :data="requests" border size="small">
        <el-table-column prop="tenantName" :label="t('common.tenant')" min-width="160" />
        <el-table-column prop="projectName" :label="t('common.project')" min-width="180" />
        <el-table-column prop="status" :label="t('common.status')" width="130" align="center" />
        <el-table-column prop="reason" :label="t('web.accessCenter.reasonColumn')" min-width="220" />
        <el-table-column prop="reviewComment" :label="t('web.accessCenter.reviewCommentColumn')" min-width="220" />
        <el-table-column prop="createdAt" :label="t('web.accessCenter.appliedAtColumn')" min-width="180" />
        <el-table-column :label="t('common.actions')" width="140" align="center">
          <template #default="{ row }">
            <el-button
              v-if="String(row.status || '').toUpperCase() === 'PENDING'"
              link
              type="danger"
              @click="cancelRequest(row)"
            >
              {{ t("web.accessCenter.cancelRequest") }}
            </el-button>
            <span v-else class="cell-subtle">{{ t("web.accessCenter.requestCompleted") }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else :description="t('web.accessCenter.pendingEmpty')" />
    </SectionCard>

    <SectionCard :title="t('web.accessCenter.availableTitle')" :description="t('web.accessCenter.availableDescription')">
      <div v-if="tenantGroups.length" class="tenant-groups">
        <section v-for="tenant in tenantGroups" :key="tenant.tenantId" class="tenant-group">
          <div class="tenant-group__header">
            <div>
              <h4>{{ tenant.tenantName }}</h4>
              <p>{{ tenant.tenantCode || tenant.tenantId }}</p>
            </div>
          </div>
          <div class="tenant-group__projects">
            <article v-for="project in tenant.projects" :key="String(project.projectId)" class="project-card">
              <div class="project-card__main">
                <strong>{{ project.projectName }}</strong>
                <span>{{ project.projectCode || t("common.none") }}</span>
                <p>{{ project.description || t("web.accessCenter.projectDescriptionFallback") }}</p>
              </div>
              <div class="project-card__actions">
                <el-tag
                  v-if="project.pendingRequestStatus"
                  type="warning"
                  effect="light"
                >
                  {{ t("web.accessCenter.pendingTag") }}
                </el-tag>
                <el-button
                  v-else
                  type="primary"
                  plain
                  @click="openApplyDialog(project)"
                >
                  {{ t("web.accessCenter.applyAction") }}
                </el-button>
              </div>
            </article>
          </div>
        </section>
      </div>
      <el-empty v-else :description="t('web.accessCenter.availableEmpty')" />
    </SectionCard>

    <el-dialog v-model="applyDialogVisible" :title="t('web.accessCenter.applyDialogTitle')" width="520px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item :label="t('common.tenant')">
          <el-input :model-value="selectedProject?.tenantName || ''" disabled />
        </el-form-item>
        <el-form-item :label="t('common.project')">
          <el-input :model-value="selectedProject?.projectName || ''" disabled />
        </el-form-item>
        <el-form-item :label="t('web.accessCenter.reasonLabel')">
          <el-input
            v-model="applyReason"
            type="textarea"
            :rows="4"
            resize="none"
            :placeholder="t('web.accessCenter.reasonPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyDialogVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitRequest">{{ t("web.accessCenter.submitAction") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import type { WorkspaceAccessOverviewView, WorkspaceAccessProjectView, WorkspaceAccessRequestView } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";

const { t } = useI18n();
const router = useRouter();
const authStore = useAuthStore();

const loading = ref(false);
const submitLoading = ref(false);
const overview = ref<WorkspaceAccessOverviewView>({
  tenantGroups: [],
  requests: [],
});
const applyDialogVisible = ref(false);
const selectedProject = ref<WorkspaceAccessProjectView | null>(null);
const applyReason = ref("");

const tenantGroups = computed(() => overview.value.tenantGroups ?? []);
const requests = computed(() => overview.value.requests ?? []);

async function loadOverview() {
  loading.value = true;
  try {
    overview.value = await studioApi.access.overview();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.accessCenter.loadFailed"));
  } finally {
    loading.value = false;
  }
}

function openApplyDialog(project: WorkspaceAccessProjectView) {
  selectedProject.value = project;
  applyReason.value = "";
  applyDialogVisible.value = true;
}

async function submitRequest() {
  if (!selectedProject.value?.projectId) {
    return;
  }
  submitLoading.value = true;
  try {
    await studioApi.access.apply({
      projectId: selectedProject.value.projectId,
      reason: applyReason.value.trim() || undefined,
    });
    ElMessage.success(t("web.accessCenter.applySuccess"));
    applyDialogVisible.value = false;
    await loadOverview();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.accessCenter.applyFailed"));
  } finally {
    submitLoading.value = false;
  }
}

async function cancelRequest(request: WorkspaceAccessRequestView) {
  if (!request.requestId) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.accessCenter.cancelConfirm"),
      t("web.accessCenter.cancelRequest"),
      {
        type: "warning",
      },
    );
    await studioApi.access.cancel(request.requestId);
    ElMessage.success(t("web.accessCenter.cancelSuccess"));
    await loadOverview();
  } catch (error) {
    if (error === "cancel") {
      return;
    }
    ElMessage.error(error instanceof Error ? error.message : t("web.accessCenter.cancelFailed"));
  }
}

async function refreshPermissions() {
  try {
    await authStore.refreshProfile();
    if (authStore.currentProjectId) {
      await router.replace("/dashboard");
      return;
    }
    ElMessage.info(t("web.accessCenter.refreshPending"));
    await loadOverview();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.accessCenter.refreshFailed"));
  }
}

onMounted(async () => {
  if (authStore.currentProjectId) {
    await router.replace("/dashboard");
    return;
  }
  await loadOverview();
});
</script>

<style scoped>
h3,
h4,
p {
  margin: 0;
}

.tenant-groups {
  display: grid;
  gap: 18px;
}

.tenant-group {
  display: grid;
  gap: 14px;
  padding: 16px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(16, 78, 139, 0.08);
}

.tenant-group__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.tenant-group__header p {
  margin-top: 4px;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.tenant-group__projects {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 14px;
}

.project-card {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: flex-start;
  padding: 16px 18px;
  border-radius: 18px;
  border: 1px solid rgba(16, 78, 139, 0.1);
  background: rgba(247, 251, 255, 0.92);
}

.project-card__main {
  display: grid;
  gap: 6px;
}

.project-card__main span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.project-card__main p {
  color: var(--studio-text-soft);
  line-height: 1.6;
}

.project-card__actions {
  display: flex;
  align-items: center;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}

@media (max-width: 880px) {
  .project-card {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
