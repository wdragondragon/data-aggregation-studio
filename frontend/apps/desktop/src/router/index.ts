import { createRouter, createWebHashHistory, type RouteRecordRaw } from "vue-router";
import type { StudioNavItem } from "@studio/ui";
import { useDesktopAuthStore } from "@/stores/auth";
import DesktopLayout from "@/layout/DesktopLayout.vue";

export const desktopMenus: StudioNavItem[] = [
  { label: "Offline Home", path: "/home", caption: "Local runtime overview" },
  { label: "Projects", path: "/projects", caption: "Import and export bundles" },
  { label: "Runtime", path: "/runtime", caption: "Local execution and docs" },
];

const routes: RouteRecordRaw[] = [
  {
    path: "/login",
    component: () => import("@/views/DesktopLoginView.vue"),
    meta: {
      public: true,
      title: "Desktop Sign In",
    },
  },
  {
    path: "/",
    component: DesktopLayout,
    redirect: "/home",
    children: [
      {
        path: "/home",
        component: () => import("@/views/DesktopHomeView.vue"),
        meta: {
          title: "Offline Home",
          subtitle: "The desktop runtime keeps execution local and synchronization explicit.",
        },
      },
      {
        path: "/projects",
        component: () => import("@/views/DesktopProjectsView.vue"),
        meta: {
          title: "Projects",
          subtitle: "Exchange definitions through import and export bundles instead of automatic sync.",
        },
      },
      {
        path: "/runtime",
        component: () => import("@/views/DesktopRuntimeView.vue"),
        meta: {
          title: "Runtime",
          subtitle: "Inspect local runtime mode, offline execution posture and API endpoints.",
        },
      },
    ],
  },
];

const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

router.beforeEach(async (to) => {
  const authStore = useDesktopAuthStore();
  await authStore.bootstrap();
  if (to.meta.public) {
    return true;
  }
  if (!authStore.isAuthenticated) {
    return "/login";
  }
  return true;
});

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? "Desktop")} | Data Aggregation Studio`;
});

export default router;
