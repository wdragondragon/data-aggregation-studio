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
    <router-view />
  </StudioShell>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
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
  return authStore.username ? t("common.loggedInAs", { subtitle, username: authStore.username }) : subtitle;
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

onBeforeUnmount(() => {
  unsubscribe();
});
</script>
