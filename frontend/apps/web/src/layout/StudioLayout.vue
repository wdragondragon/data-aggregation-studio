<template>
  <StudioShell
    :menus="studioMenus"
    :active-path="route.path"
    :title="pageTitle"
    :subtitle="pageSubtitle"
    mode-label="Web Runtime"
    @navigate="router.push($event)"
    @logout="handleLogout"
  >
    <router-view />
  </StudioShell>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { StudioShell } from "@studio/ui";
import { studioMenus } from "@/router";
import { useAuthStore } from "@/stores/auth";

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const pageTitle = computed(() => String(route.meta.title ?? "Data Aggregation Studio"));
const pageSubtitle = computed(() => {
  const subtitle = String(route.meta.subtitle ?? "Web-first orchestration and metadata center");
  return authStore.username ? `${subtitle} Logged in as ${authStore.username}.` : subtitle;
});

function handleLogout() {
  authStore.logout();
  router.push("/login");
}
</script>
