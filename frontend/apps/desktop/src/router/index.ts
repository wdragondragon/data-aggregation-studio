import { createRouter, createWebHashHistory, type RouteRecordRaw } from "vue-router";
import type { StudioNavItem } from "@studio/ui";
import { useDesktopAuthStore } from "@desktop/stores/auth";
import DesktopLayout from "@desktop/layout/DesktopLayout.vue";

interface DesktopMenuDescriptor {
  path: string;
  labelKey: string;
  captionKey: string;
  requiredRoleCodes?: string[];
}

export const desktopMenuDescriptors: DesktopMenuDescriptor[] = [
  { path: "/dashboard", labelKey: "routes.web.dashboard.title", captionKey: "routes.web.dashboard.menuCaption" },
  { path: "/catalog", labelKey: "routes.web.catalog.title", captionKey: "routes.web.catalog.menuCaption" },
  { path: "/metadata", labelKey: "routes.web.metadata.title", captionKey: "routes.web.metadata.menuCaption" },
  { path: "/datasources", labelKey: "routes.web.datasources.title", captionKey: "routes.web.datasources.menuCaption" },
  { path: "/models", labelKey: "routes.web.models.title", captionKey: "routes.web.models.menuCaption" },
  { path: "/collection-tasks", labelKey: "routes.web.collectionTasks.title", captionKey: "routes.web.collectionTasks.menuCaption" },
  { path: "/data-development", labelKey: "routes.web.dataDevelopment.title", captionKey: "routes.web.dataDevelopment.menuCaption" },
  { path: "/workflows", labelKey: "routes.web.workflows.title", captionKey: "routes.web.workflows.menuCaption" },
  { path: "/runs", labelKey: "routes.web.runs.title", captionKey: "routes.web.runs.menuCaption" },
  { path: "/ops-center", labelKey: "routes.web.opsCenter.title", captionKey: "routes.web.opsCenter.menuCaption" },
  { path: "/alerts", labelKey: "routes.web.alerts.title", captionKey: "routes.web.alerts.menuCaption" },
  { path: "/runtime-clusters", labelKey: "routes.web.runtimeClusters.title", captionKey: "routes.web.runtimeClusters.menuCaption", requiredRoleCodes: ["SUPER_ADMIN", "TENANT_ADMIN"] },
  { path: "/script-environments", labelKey: "routes.web.scriptEnvironments.title", captionKey: "routes.web.scriptEnvironments.menuCaption", requiredRoleCodes: ["SUPER_ADMIN", "TENANT_ADMIN", "PROJECT_ADMIN", "ADMIN"] },
  { path: "/system", labelKey: "routes.web.system.title", captionKey: "routes.web.system.menuCaption" },
];

export function resolveDesktopMenus(t: (key: string) => string, roleCodes: string[] = []): StudioNavItem[] {
  const normalizedRoles = roleCodes.map((roleCode) => roleCode.toUpperCase());
  return desktopMenuDescriptors.filter((item) => (
    !item.requiredRoleCodes?.length
    || item.requiredRoleCodes.some((roleCode) => normalizedRoles.includes(roleCode.toUpperCase()))
  )).map((item) => ({
    path: item.path,
    label: t(item.labelKey),
    caption: t(item.captionKey),
  }));
}

const routes: RouteRecordRaw[] = [
  {
    path: "/login",
    name: "login",
    component: () => import("@desktop/views/DesktopLoginView.vue"),
    meta: {
      public: true,
      titleKey: "routes.desktop.login.title",
      subtitleKey: "routes.desktop.login.subtitle",
    },
  },
  {
    path: "/",
    component: DesktopLayout,
    redirect: "/dashboard",
    children: [
      {
        path: "/dashboard",
        name: "dashboard",
        component: () => import("@web/views/DashboardView.vue"),
        meta: {
          titleKey: "routes.web.dashboard.title",
          subtitleKey: "routes.web.dashboard.subtitle",
        },
      },
      {
        path: "/catalog",
        name: "catalog",
        component: () => import("@web/views/CatalogView.vue"),
        meta: {
          titleKey: "routes.web.catalog.title",
          subtitleKey: "routes.web.catalog.subtitle",
        },
      },
      {
        path: "/metadata",
        name: "metadata",
        component: () => import("@web/views/MetadataSchemasView.vue"),
        meta: {
          titleKey: "routes.web.metadata.title",
          subtitleKey: "routes.web.metadata.subtitle",
        },
      },
      {
        path: "/datasources",
        name: "datasources",
        component: () => import("@web/views/DatasourcesView.vue"),
        meta: {
          titleKey: "routes.web.datasources.title",
          subtitleKey: "routes.web.datasources.subtitle",
        },
      },
      {
        path: "/models",
        name: "models",
        component: () => import("@web/views/ModelsView.vue"),
        meta: {
          titleKey: "routes.web.models.title",
          subtitleKey: "routes.web.models.subtitle",
        },
      },
      {
        path: "/models/:modelId",
        name: "model-detail",
        component: () => import("@web/views/ModelsView.vue"),
        meta: {
          titleKey: "routes.web.models.detailTitle",
          subtitleKey: "routes.web.models.detailSubtitle",
        },
      },
      {
        path: "/collection-tasks",
        name: "collection-tasks",
        component: () => import("@web/views/CollectionTasksView.vue"),
        meta: {
          titleKey: "routes.web.collectionTasks.title",
          subtitleKey: "routes.web.collectionTasks.subtitle",
        },
      },
      {
        path: "/collection-tasks/new",
        name: "collection-task-create",
        component: () => import("@web/views/CollectionTaskEditorView.vue"),
        meta: {
          titleKey: "routes.web.collectionTasks.createTitle",
          subtitleKey: "routes.web.collectionTasks.createSubtitle",
        },
      },
      {
        path: "/collection-tasks/:taskId/edit",
        name: "collection-task-edit",
        component: () => import("@web/views/CollectionTaskEditorView.vue"),
        meta: {
          titleKey: "routes.web.collectionTasks.editTitle",
          subtitleKey: "routes.web.collectionTasks.editSubtitle",
        },
      },
      {
        path: "/collection-task-runs",
        name: "collection-task-runs",
        component: () => import("@web/views/CollectionTaskRunsView.vue"),
        meta: {
          titleKey: "routes.web.collectionTaskRuns.title",
          subtitleKey: "routes.web.collectionTaskRuns.subtitle",
        },
      },
      {
        path: "/data-development",
        name: "data-development",
        component: () => import("@web/views/DataDevelopmentView.vue"),
        meta: {
          titleKey: "routes.web.dataDevelopment.title",
          subtitleKey: "routes.web.dataDevelopment.subtitle",
        },
      },
      {
        path: "/script-environments",
        name: "script-environments",
        component: () => import("@web/views/ScriptEnvironmentsView.vue"),
        meta: {
          titleKey: "routes.web.scriptEnvironments.title",
          subtitleKey: "routes.web.scriptEnvironments.subtitle",
          requiredRoleCodes: ["SUPER_ADMIN", "TENANT_ADMIN", "PROJECT_ADMIN", "ADMIN"],
        },
      },
      {
        path: "/quality-task-runs",
        name: "quality-task-runs",
        component: () => import("@web/views/QualityTaskRunsView.vue"),
        meta: {
          titleKey: "routes.web.qualityTaskRuns.title",
          subtitleKey: "routes.web.qualityTaskRuns.subtitle",
        },
      },
      {
        path: "/workflows",
        name: "workflows",
        component: () => import("@web/views/WorkflowsView.vue"),
        meta: {
          titleKey: "routes.web.workflows.title",
          subtitleKey: "routes.web.workflows.subtitle",
        },
      },
      {
        path: "/workflows/:workflowId",
        name: "workflow-detail",
        component: () => import("@web/views/WorkflowDetailView.vue"),
        meta: {
          titleKey: "routes.web.workflows.detailTitle",
          subtitleKey: "routes.web.workflows.detailSubtitle",
        },
      },
      {
        path: "/workflows/new",
        name: "workflow-create",
        component: () => import("@web/views/WorkflowEditorView.vue"),
        meta: {
          titleKey: "routes.web.workflows.createTitle",
          subtitleKey: "routes.web.workflows.createSubtitle",
        },
      },
      {
        path: "/workflows/:workflowId/edit",
        name: "workflow-edit",
        component: () => import("@web/views/WorkflowEditorView.vue"),
        meta: {
          titleKey: "routes.web.workflows.editorTitle",
          subtitleKey: "routes.web.workflows.editorSubtitle",
        },
      },
      {
        path: "/runs",
        name: "runs",
        component: () => import("@web/views/RunsView.vue"),
        meta: {
          titleKey: "routes.web.runs.title",
          subtitleKey: "routes.web.runs.subtitle",
        },
      },
      {
        path: "/runs/:workflowRunId",
        name: "workflow-run-detail",
        component: () => import("@web/views/WorkflowRunDetailView.vue"),
        meta: {
          titleKey: "routes.web.runs.detailTitle",
          subtitleKey: "routes.web.runs.detailSubtitle",
        },
      },
      {
        path: "/data-service-metrics/access-logs",
        name: "data-service-access-logs",
        component: () => import("@web/views/DataServiceAccessLogsView.vue"),
        meta: {
          titleKey: "routes.web.dataServiceMetrics.logsTitle",
          subtitleKey: "routes.web.dataServiceMetrics.logsSubtitle",
        },
      },
      {
        path: "/data-ingestion-metrics/access-logs",
        name: "data-ingestion-access-logs",
        component: () => import("@web/views/DataIngestionAccessLogsView.vue"),
        meta: {
          titleKey: "routes.web.dataIngestionMetrics.logsTitle",
          subtitleKey: "routes.web.dataIngestionMetrics.logsSubtitle",
        },
      },
      {
        path: "/protocol-conversions/access-logs",
        name: "protocol-conversion-access-logs",
        component: () => import("@web/views/ProtocolConversionAccessLogsView.vue"),
        meta: {
          titleKey: "routes.web.protocolConversions.logsTitle",
          subtitleKey: "routes.web.protocolConversions.logsSubtitle",
        },
      },
      {
        path: "/ops-center",
        name: "ops-center",
        component: () => import("@web/views/OpsCenterView.vue"),
        meta: {
          titleKey: "routes.web.opsCenter.title",
          subtitleKey: "routes.web.opsCenter.subtitle",
        },
      },
      {
        path: "/alerts",
        name: "alerts",
        component: () => import("@web/views/AlertsView.vue"),
        meta: {
          titleKey: "routes.web.alerts.title",
          subtitleKey: "routes.web.alerts.subtitle",
        },
      },
      {
        path: "/runtime-clusters",
        name: "runtime-clusters",
        component: () => import("@web/views/RuntimeClustersView.vue"),
        meta: {
          titleKey: "routes.web.runtimeClusters.title",
          subtitleKey: "routes.web.runtimeClusters.subtitle",
          requiredRoleCodes: ["SUPER_ADMIN", "TENANT_ADMIN"],
        },
      },
      {
        path: "/system",
        name: "system",
        component: () => import("@web/views/SystemView.vue"),
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
    return {
      path: "/login",
      query: {
        redirect: to.fullPath,
      },
    };
  }
  const requiredRoleCodes = Array.isArray(to.meta.requiredRoleCodes) ? to.meta.requiredRoleCodes : [];
  if (requiredRoleCodes.length > 0) {
    const normalizedRoles = [...authStore.systemRoleCodes, ...authStore.effectiveRoleCodes]
      .map((roleCode) => String(roleCode).toUpperCase());
    if (!requiredRoleCodes.some((roleCode) => normalizedRoles.includes(String(roleCode).toUpperCase()))) {
      return "/dashboard";
    }
  }
  return true;
});

export default router;
