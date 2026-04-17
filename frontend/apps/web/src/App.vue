<template>
  <el-config-provider :locale="elementLocale">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import elementEn from "element-plus/es/locale/lang/en";
import elementZhCn from "element-plus/es/locale/lang/zh-cn";
import { installFloatingPlaceholder } from "@/utils/floatingPlaceholder";

const route = useRoute();
const { locale, t } = useI18n();
const elementLocale = computed(() => (locale.value === "zh-CN" ? elementZhCn : elementEn));
let uninstallFloatingPlaceholder: (() => void) | undefined;

watch(
  [() => route.meta.titleKey, locale],
  ([titleKey]) => {
    const resolvedTitle = typeof titleKey === "string" ? t(titleKey) : t("app.name");
    document.title = `${resolvedTitle} | ${t("app.name")}`;
  },
  { immediate: true },
);

onMounted(() => {
  uninstallFloatingPlaceholder = installFloatingPlaceholder();
});

onBeforeUnmount(() => {
  uninstallFloatingPlaceholder?.();
});
</script>
