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
      <div class="studio-layout__context">
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
    </template>
    <router-view />
  </StudioShell>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import { ElMessage } from "element-plus";
import { useRoute, useRouter } from "vue-router";
import { subscribeStudioApiLoading } from "@studio/api-sdk";
import { StudioShell } from "@studio/ui";
import { persistStudioLocale, resolveStudioLocale } from "@studio/i18n";
import { useI18n } from "vue-i18n";
import { resolveStudioMenus } from "@/router";
import { useAuthStore } from "@/stores/auth";

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const { locale, t } = useI18n();
const appLoading = ref(false);
const contextLoading = ref(false);
const unsubscribe = subscribeStudioApiLoading((loading) => {
  appLoading.value = loading;
});

const studioMenus = computed(() => resolveStudioMenus(t));
const activeMenuPath = computed(() => {
  const matched = studioMenus.value.find((item) => route.path === item.path || route.path.startsWith(`${item.path}/`));
  return matched?.path ?? route.path;
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
});
</script>

<style scoped>
.studio-layout__context {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  justify-content: flex-end;
  width: 100%;
}

.studio-layout__context-select {
  min-width: 220px;
}

@media (max-width: 980px) {
  .studio-layout__context {
    justify-content: flex-start;
  }

  .studio-layout__context-select {
    flex: 1 1 100%;
    min-width: 0;
  }
}
</style>
