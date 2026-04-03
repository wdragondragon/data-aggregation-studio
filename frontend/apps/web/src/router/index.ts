import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import type { StudioNavItem } from "@studio/ui";
import { useAuthStore } from "@/stores/auth";
import StudioLayout from "@/layout/StudioLayout.vue";

export const studioMenus: StudioNavItem[] = [
  { label: "Dashboard", path: "/dashboard", caption: "Control tower and runtime summary" },
  { label: "Catalog", path: "/catalog", caption: "Plugin inventory and execution matrix" },
  { label: "Metadata", path: "/metadata", caption: "Dynamic schema and field definitions" },
  { label: "Datasources", path: "/datasources", caption: "Connection registry and testing" },
  { label: "Models", path: "/models", caption: "Model discovery and preview" },
  { label: "Workflows", path: "/workflows", caption: "DAG designer and node orchestration" },
  { label: "Runs", path: "/runs", caption: "Dispatch queue and run records" },
  { label: "System", path: "/system", caption: "Users, roles and permissions" },
];

const routes: RouteRecordRaw[] = [
  {
    path: "/login",
    name: "login",
    component: () => import("@/views/LoginView.vue"),
    meta: {
      public: true,
      title: "Sign In",
      subtitle: "Use the seeded admin account to enter the studio.",
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
          title: "Dashboard",
          subtitle: "Observe catalog breadth, workflow health and runtime activity at a glance.",
        },
      },
      {
        path: "/catalog",
        name: "catalog",
        component: () => import("@/views/CatalogView.vue"),
        meta: {
          title: "Plugin Catalog",
          subtitle: "Track source, reader, writer and transformer coverage before you design jobs.",
        },
      },
      {
        path: "/metadata",
        name: "metadata",
        component: () => import("@/views/MetadataSchemasView.vue"),
        meta: {
          title: "Metadata Center",
          subtitle: "Draft and publish technical and business schema definitions for dynamic forms.",
        },
      },
      {
        path: "/datasources",
        name: "datasources",
        component: () => import("@/views/DatasourcesView.vue"),
        meta: {
          title: "Datasource Center",
          subtitle: "Manage connections with schema-driven metadata, testing and model discovery.",
        },
      },
      {
        path: "/models",
        name: "models",
        component: () => import("@/views/ModelsView.vue"),
        meta: {
          title: "Model Center",
          subtitle: "Synchronize physical models into the platform abstraction and inspect samples.",
        },
      },
      {
        path: "/workflows",
        name: "workflows",
        component: () => import("@/views/WorkflowsView.vue"),
        meta: {
          title: "Workflow Studio",
          subtitle: "Compose ETL, fusion and operational steps in a drag-and-drop execution graph.",
        },
      },
      {
        path: "/runs",
        name: "runs",
        component: () => import("@/views/RunsView.vue"),
        meta: {
          title: "Runtime Center",
          subtitle: "Inspect queued tasks, worker activity and node-level execution results.",
        },
      },
      {
        path: "/system",
        name: "system",
        component: () => import("@/views/SystemView.vue"),
        meta: {
          title: "System Management",
          subtitle: "Handle users, roles and permission surfaces for the web platform.",
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

router.afterEach((to) => {
  const title = typeof to.meta.title === "string" ? to.meta.title : "Data Aggregation Studio";
  document.title = `${title} | Data Aggregation Studio`;
});

export default router;
