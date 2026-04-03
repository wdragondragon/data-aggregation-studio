<template>
  <StudioShell
    :menus="desktopMenus"
    :active-path="activeMenuPath"
    :title="pageTitle"
    :subtitle="pageSubtitle"
    :mode-label="t('shell.desktopRuntime')"
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
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { StudioShell } from "@studio/ui";
import { persistStudioLocale, resolveStudioLocale } from "@studio/i18n";
import { useI18n } from "vue-i18n";
import { resolveDesktopMenus } from "@/router";
import { useDesktopAuthStore } from "@/stores/auth";

const route = useRoute();
const router = useRouter();
const authStore = useDesktopAuthStore();
const { locale, t } = useI18n();

const desktopMenus = computed(() => resolveDesktopMenus(t));
const activeMenuPath = computed(() => {
  const matched = desktopMenus.value.find((item) => route.path === item.path || route.path.startsWith(`${item.path}/`));
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
  const subtitleKey = typeof route.meta.subtitleKey === "string" ? route.meta.subtitleKey : "shell.desktopSubtitle";
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
</script>
