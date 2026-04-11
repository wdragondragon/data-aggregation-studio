<template>
  <StudioShell
    :menus="studioMenus"
    :active-path="activeMenuPath"
    :loading="appLoading"
    :title="pageTitle"
    :subtitle="pageSubtitle"
    :mode-label="t('shell.webRuntime')"
    :locale="locale"
    :locale-options="localeOptions"
    @navigate="router.push($event)"
    @locale-change="handleLocaleChange"
    @logout="handleLogout"
  >
    <template #header-actions>
      <NotificationBell v-if="authStore.isAuthenticated" />
    </template>
    <template #sidebar-context>
      <div class="studio-layout__context">
        <div class="studio-layout__context-header">
          <strong>{{ t("common.project") }} / {{ t("common.tenant") }}</strong>
          <span>{{ authStore.username || t("common.none") }}</span>
        </div>
        <div class="studio-layout__context-field">
          <span class="studio-layout__context-label">{{ t("common.tenant") }}</span>
          <el-select
            :model-value="authStore.currentTenantId ?? undefined"
            class="studio-layout__context-select"
            :placeholder="t('common.selectTenant')"
            :disabled="!authStore.isAuthenticated || authStore.tenants.length === 0 || contextLoading"
            @change="handleTenantChange"
          >
            <el-option
              v-for="tenant in authStore.tenants"
              :key="tenant.tenantId"
              :label="tenant.tenantName"
              :value="tenant.tenantId"
            />
          </el-select>
        </div>
        <div class="studio-layout__context-field">
          <span class="studio-layout__context-label">{{ t("common.project") }}</span>
          <el-select
            :model-value="authStore.currentProjectId ?? undefined"
            class="studio-layout__context-select"
            :placeholder="t('common.selectProject')"
            :disabled="!authStore.isAuthenticated || authStore.projects.length === 0 || contextLoading"
            @change="handleProjectChange"
          >
            <el-option
              v-for="project in authStore.projects"
              :key="project.projectId"
              :label="project.projectName"
              :value="project.projectId"
            />
          </el-select>
        </div>
        <div v-if="authStore.isAuthenticated && !authStore.currentProjectId" class="studio-layout__context-empty">
          <span>{{ t("common.noProjectBound") }}</span>
          <el-button text type="primary" @click="router.push('/access-center')">
            {{ t("common.openAccessCenter") }}
          </el-button>
        </div>
      </div>
    </template>
    <router-view />
  </StudioShell>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useRoute, useRouter } from "vue-router";
import { subscribeStudioApiLoading } from "@studio/api-sdk";
import { StudioShell, type StudioNavItem } from "@studio/ui";
import { persistStudioLocale, resolveStudioLocale } from "@studio/i18n";
import { useI18n } from "vue-i18n";
import NotificationBell from "@/components/NotificationBell.vue";
import { resolveStudioMenus } from "@/router";
import { useAuthStore } from "@/stores/auth";
import { useNotificationStore } from "@/stores/notifications";

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const notificationStore = useNotificationStore();
const { locale, t } = useI18n();
const appLoading = ref(false);
const contextLoading = ref(false);
const unsubscribe = subscribeStudioApiLoading((loading) => {
  appLoading.value = loading;
});

const studioMenus = computed(() => {
  if (!authStore.currentProjectId) {
    return [];
  }
  return resolveStudioMenus(t, {
    systemRoleCodes: authStore.systemRoleCodes,
    effectiveRoleCodes: authStore.effectiveRoleCodes,
    hasProject: Boolean(authStore.currentProjectId),
  });
});
const activeMenuPath = computed(() => {
  return resolveActiveMenuPath(studioMenus.value, route.path) ?? route.path;
});
const localeOptions = computed(() => [
  { value: "en-US", label: t("common.locales.en") },
  { value: "zh-CN", label: t("common.locales.zh") },
]);

const pageTitle = computed(() => {
  const titleKey = typeof route.meta.titleKey === "string" ? route.meta.titleKey : "app.name";
  return t(titleKey);
});
const pageSubtitle = computed(() => {
  const subtitleKey = typeof route.meta.subtitleKey === "string" ? route.meta.subtitleKey : "shell.defaultSubtitle";
  const subtitle = t(subtitleKey);
  if (!authStore.username) {
    return subtitle;
  }
  if (authStore.currentTenantName && authStore.currentProjectName) {
    return t("common.loggedInContext", {
      subtitle,
      username: authStore.username,
      tenant: authStore.currentTenantName,
      project: authStore.currentProjectName,
    });
  }
  if (!authStore.currentProjectId) {
    return t("common.loggedInNoProjectContext", {
      subtitle,
      username: authStore.username,
    });
  }
  return t("common.loggedInAs", { subtitle, username: authStore.username });
});

function handleLocaleChange(nextLocale: string) {
  const resolvedLocale = resolveStudioLocale(nextLocale);
  locale.value = resolvedLocale;
  persistStudioLocale(resolvedLocale);
}

function handleLogout() {
  authStore.logout();
  router.push("/login");
}

async function handleTenantChange(tenantId: string) {
  if (!tenantId || tenantId === authStore.currentTenantId) {
    return;
  }
  contextLoading.value = true;
  try {
    await authStore.selectTenant(tenantId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("common.contextSwitchFailed"));
  } finally {
    contextLoading.value = false;
  }
}

async function handleProjectChange(projectId: string | number) {
  if (projectId == null || String(projectId) === String(authStore.currentProjectId ?? "")) {
    return;
  }
  contextLoading.value = true;
  try {
    await authStore.selectProject(projectId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("common.contextSwitchFailed"));
  } finally {
    contextLoading.value = false;
  }
}

onBeforeUnmount(() => {
  unsubscribe();
  notificationStore.stop();
});

onMounted(() => {
  if (authStore.isAuthenticated) {
    notificationStore.start();
    void notificationStore.loadSnapshot().catch(() => {});
  }
});

watch(() => authStore.isAuthenticated, (authenticated) => {
  if (authenticated) {
    notificationStore.start();
    void notificationStore.loadSnapshot().catch(() => {});
    return;
  }
  notificationStore.stop();
});

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], () => {
  if (!authStore.isAuthenticated) {
    return;
  }
  notificationStore.stop();
  notificationStore.start();
  void notificationStore.loadSnapshot().catch(() => {});
});

function resolveActiveMenuPath(items: StudioNavItem[], currentPath: string): string | null {
  for (const item of items) {
    if (item.path && (currentPath === item.path || currentPath.startsWith(`${item.path}/`))) {
      return item.path;
    }
    if (item.children?.length) {
      const matched = resolveActiveMenuPath(item.children, currentPath);
      if (matched) {
        return matched;
      }
    }
  }
  return null;
}
</script>

<style scoped>
.studio-layout__context {
  display: grid;
  gap: 10px;
  width: 100%;
  padding: 12px 12px 14px;
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(243, 248, 255, 0.1), rgba(243, 248, 255, 0.05));
  border: 1px solid rgba(243, 248, 255, 0.1);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.studio-layout__context-header {
  display: grid;
  gap: 4px;
}

.studio-layout__context-header strong {
  color: rgba(248, 250, 252, 0.96);
  font-size: 13px;
  font-weight: 700;
}

.studio-layout__context-header span {
  color: rgba(226, 232, 240, 0.8);
  font-size: 12px;
}

.studio-layout__context-field {
  display: grid;
  gap: 6px;
}

.studio-layout__context-label {
  color: rgba(241, 245, 249, 0.92);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.studio-layout__context-select {
  width: 100%;
  min-width: 0;
}

.studio-layout__context-select :deep(.el-select__wrapper) {
  min-height: 40px;
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.28);
  box-shadow: inset 0 0 0 1px rgba(191, 219, 254, 0.16);
  transition: box-shadow 0.2s ease, background 0.2s ease, transform 0.2s ease;
}

.studio-layout__context-select :deep(.el-select__wrapper:hover) {
  background: rgba(15, 23, 42, 0.34);
  box-shadow: inset 0 0 0 1px rgba(191, 219, 254, 0.24);
}

.studio-layout__context-empty {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding-top: 2px;
  color: rgba(226, 232, 240, 0.88);
  font-size: 12px;
}

.studio-layout__context-select :deep(.el-select__wrapper.is-focused) {
  background: rgba(15, 23, 42, 0.4);
  box-shadow:
    inset 0 0 0 1px rgba(147, 197, 253, 0.52),
    0 0 0 3px rgba(59, 130, 246, 0.14);
}

.studio-layout__context-select :deep(.el-select__selected-item) {
  color: rgba(248, 250, 252, 0.96);
  font-weight: 600;
}

.studio-layout__context-select :deep(.el-select__placeholder) {
  color: rgba(226, 232, 240, 0.58);
}

.studio-layout__context-select :deep(.el-select__caret),
.studio-layout__context-select :deep(.el-input__icon) {
  color: rgba(226, 232, 240, 0.74);
}

.studio-layout__context-select :deep(.el-select__wrapper.is-disabled) {
  background: rgba(15, 23, 42, 0.18);
  box-shadow: inset 0 0 0 1px rgba(191, 219, 254, 0.08);
}

@media (max-width: 980px) {
  .studio-layout__context {
    gap: 8px;
  }

  .studio-layout__context-select {
    min-width: 0;
  }
}
</style>
