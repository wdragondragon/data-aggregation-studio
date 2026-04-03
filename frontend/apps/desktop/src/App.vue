<template>
  <router-view />
</template>

<script setup lang="ts">
import { watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";

const route = useRoute();
const { locale, t } = useI18n();

watch(
  [() => route.meta.titleKey, locale],
  ([titleKey]) => {
    const resolvedTitle = typeof titleKey === "string" ? t(titleKey) : t("app.name");
    document.title = `${resolvedTitle} | ${t("app.name")}`;
  },
  { immediate: true },
);
</script>
