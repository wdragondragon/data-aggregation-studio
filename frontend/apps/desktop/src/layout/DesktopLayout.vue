<template>
  <StudioShell
    :menus="desktopMenus"
    :active-path="route.path"
    :title="pageTitle"
    :subtitle="pageSubtitle"
    mode-label="Desktop Runtime"
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
import { desktopMenus } from "@/router";
import { useDesktopAuthStore } from "@/stores/auth";

const route = useRoute();
const router = useRouter();
const authStore = useDesktopAuthStore();

const pageTitle = computed(() => String(route.meta.title ?? "Desktop Runtime"));
const pageSubtitle = computed(() => {
  const subtitle = String(route.meta.subtitle ?? "Local runtime and bundle exchange console");
  return authStore.username ? `${subtitle} Logged in as ${authStore.username}.` : subtitle;
});

function handleLogout() {
  authStore.logout();
  router.push("/login");
}
</script>
