import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import type { StudioNavItem } from "@studio/ui";
import { useAuthStore } from "@/stores/auth";
import StudioLayout from "@/layout/StudioLayout.vue";

interface StudioMenuDescriptor {
  path: string;
  labelKey: string;
  captionKey: string;
}

export const studioMenuDescriptors: StudioMenuDescriptor[] = [
  { path: "/dashboard", labelKey: "routes.web.dashboard.title", captionKey: "routes.web.dashboard.menuCaption" },
  { path: "/catalog", labelKey: "routes.web.catalog.title", captionKey: "routes.web.catalog.menuCaption" },
  { path: "/metadata", labelKey: "routes.web.metadata.title", captionKey: "routes.web.metadata.menuCaption" },
  { path: "/datasources", labelKey: "routes.web.datasources.title", captionKey: "routes.web.datasources.menuCaption" },
  { path: "/models", labelKey: "routes.web.models.title", captionKey: "routes.web.models.menuCaption" },
  { path: "/workflows", labelKey: "routes.web.workflows.title", captionKey: "routes.web.workflows.menuCaption" },
  { path: "/runs", labelKey: "routes.web.runs.title", captionKey: "routes.web.runs.menuCaption" },
  { path: "/system", labelKey: "routes.web.system.title", captionKey: "routes.web.system.menuCaption" },
];

export function resolveStudioMenus(t: (key: string) => string): StudioNavItem[] {
  return studioMenuDescriptors.map((item) => ({
    path: item.path,
    label: t(item.labelKey),
    caption: t(item.captionKey),
  }));
}

const routes: RouteRecordRaw[] = [
  {
    path: "/login",
    name: "login",
    component: () => import("@/views/LoginView.vue"),
    meta: {
      public: true,
      titleKey: "routes.web.login.title",
      subtitleKey: "routes.web.login.subtitle",
    },
  },
  {
    path: "/",
    component: StudioLayout,
    redirect: "/dashboard",
    children: [
      {
        path: "/dashboard",
        name: "dashboard",
        component: () => import("@/views/DashboardView.vue"),
        meta: {
          titleKey: "routes.web.dashboard.title",
          subtitleKey: "routes.web.dashboard.subtitle",
        },
      },
      {
        path: "/catalog",
        name: "catalog",
        component: () => import("@/views/CatalogView.vue"),
        meta: {
          titleKey: "routes.web.catalog.title",
          subtitleKey: "routes.web.catalog.subtitle",
        },
      },
      {
        path: "/metadata",
        name: "metadata",
        component: () => import("@/views/MetadataSchemasView.vue"),
        meta: {
          titleKey: "routes.web.metadata.title",
          subtitleKey: "routes.web.metadata.subtitle",
        },
      },
      {
        path: "/datasources",
        name: "datasources",
        component: () => import("@/views/DatasourcesView.vue"),
        meta: {
          titleKey: "routes.web.datasources.title",
          subtitleKey: "routes.web.datasources.subtitle",
        },
      },
      {
        path: "/models",
        name: "models",
        component: () => import("@/views/ModelsView.vue"),
        meta: {
          titleKey: "routes.web.models.title",
          subtitleKey: "routes.web.models.subtitle",
        },
      },
      {
        path: "/models/:modelId",
        name: "model-detail",
        component: () => import("@/views/ModelsView.vue"),
        meta: {
          titleKey: "routes.web.models.detailTitle",
          subtitleKey: "routes.web.models.detailSubtitle",
        },
      },
      {
        path: "/workflows",
        name: "workflows",
        component: () => import("@/views/WorkflowsView.vue"),
        meta: {
          titleKey: "routes.web.workflows.title",
          subtitleKey: "routes.web.workflows.subtitle",
        },
      },
      {
        path: "/runs",
        name: "runs",
        component: () => import("@/views/RunsView.vue"),
        meta: {
          titleKey: "routes.web.runs.title",
          subtitleKey: "routes.web.runs.subtitle",
        },
      },
      {
        path: "/system",
        name: "system",
        component: () => import("@/views/SystemView.vue"),
        meta: {
          titleKey: "routes.web.system.title",
          subtitleKey: "routes.web.system.subtitle",
        },
      },
    ],
  },
  {
    path: "/:pathMatch(.*)*",
    redirect: "/dashboard",
  },
];

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
});

router.beforeEach(async (to) => {
  const authStore = useAuthStore();
  await authStore.bootstrap();

  if (to.meta.public) {
    return true;
  }

  if (!authStore.isAuthenticated) {
    return {
      path: "/login",
      query: {
        redirect: to.fullPath,
      },
    };
  }

  return true;
});

export default router;
