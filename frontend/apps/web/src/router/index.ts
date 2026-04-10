import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import type { StudioNavItem } from "@studio/ui";
import { useAuthStore } from "@/stores/auth";
import StudioLayout from "@/layout/StudioLayout.vue";

interface StudioMenuDescriptor {
  path: string;
  labelKey: string;
  captionKey: string;
  requiredSystemRole?: string;
}

export const studioMenuDescriptors: StudioMenuDescriptor[] = [
  { path: "/dashboard", labelKey: "routes.web.dashboard.title", captionKey: "routes.web.dashboard.menuCaption" },
  { path: "/catalog", labelKey: "routes.web.catalog.title", captionKey: "routes.web.catalog.menuCaption" },
  { path: "/metadata", labelKey: "routes.web.metadata.title", captionKey: "routes.web.metadata.menuCaption" },
  { path: "/datasources", labelKey: "routes.web.datasources.title", captionKey: "routes.web.datasources.menuCaption" },
  { path: "/models", labelKey: "routes.web.models.title", captionKey: "routes.web.models.menuCaption" },
  { path: "/statistics", labelKey: "routes.web.statistics.title", captionKey: "routes.web.statistics.menuCaption" },
  { path: "/data-development", labelKey: "routes.web.dataDevelopment.title", captionKey: "routes.web.dataDevelopment.menuCaption" },
  {
    path: "/field-mapping-rules",
    labelKey: "routes.web.fieldMappingRules.title",
    captionKey: "routes.web.fieldMappingRules.menuCaption",
    requiredSystemRole: "SUPER_ADMIN",
  },
  { path: "/collection-tasks", labelKey: "routes.web.collectionTasks.title", captionKey: "routes.web.collectionTasks.menuCaption" },
  { path: "/workflows", labelKey: "routes.web.workflows.title", captionKey: "routes.web.workflows.menuCaption" },
  { path: "/runs", labelKey: "routes.web.runs.title", captionKey: "routes.web.runs.menuCaption" },
  { path: "/system", labelKey: "routes.web.system.title", captionKey: "routes.web.system.menuCaption" },
];

export function resolveStudioMenus(t: (key: string) => string, systemRoleCodes: string[] = []): StudioNavItem[] {
  const normalizedRoles = systemRoleCodes.map((item) => item.toUpperCase());
  return studioMenuDescriptors
    .filter((item) => !item.requiredSystemRole || normalizedRoles.includes(item.requiredSystemRole.toUpperCase()))
    .map((item) => ({
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
        path: "/statistics",
        name: "statistics",
        component: () => import("@/views/ModelStatisticsView.vue"),
        meta: {
          titleKey: "routes.web.statistics.title",
          subtitleKey: "routes.web.statistics.subtitle",
        },
      },
      {
        path: "/models/statistics",
        name: "model-statistics",
        redirect: {
          name: "statistics",
        },
      },
      {
        path: "/models/sync-tasks/:taskId",
        name: "model-sync-task-detail",
        component: () => import("@/views/ModelSyncTaskDetailView.vue"),
        meta: {
          titleKey: "routes.web.models.syncTaskDetailTitle",
          subtitleKey: "routes.web.models.syncTaskDetailSubtitle",
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
        path: "/data-development",
        name: "data-development",
        component: () => import("@/views/DataDevelopmentView.vue"),
        meta: {
          titleKey: "routes.web.dataDevelopment.title",
          subtitleKey: "routes.web.dataDevelopment.subtitle",
        },
      },
      {
        path: "/field-mapping-rules",
        name: "field-mapping-rules",
        component: () => import("@/views/FieldMappingRulesView.vue"),
        meta: {
          titleKey: "routes.web.fieldMappingRules.title",
          subtitleKey: "routes.web.fieldMappingRules.subtitle",
          requiresSuperAdmin: true,
        },
      },
      {
        path: "/field-mapping-rules/new",
        name: "field-mapping-rule-create",
        component: () => import("@/views/FieldMappingRuleEditorView.vue"),
        meta: {
          titleKey: "routes.web.fieldMappingRules.createTitle",
          subtitleKey: "routes.web.fieldMappingRules.createSubtitle",
          requiresSuperAdmin: true,
        },
      },
      {
        path: "/field-mapping-rules/:ruleId/edit",
        name: "field-mapping-rule-edit",
        component: () => import("@/views/FieldMappingRuleEditorView.vue"),
        meta: {
          titleKey: "routes.web.fieldMappingRules.editTitle",
          subtitleKey: "routes.web.fieldMappingRules.editSubtitle",
          requiresSuperAdmin: true,
        },
      },
      {
        path: "/collection-tasks",
        name: "collection-tasks",
        component: () => import("@/views/CollectionTasksView.vue"),
        meta: {
          titleKey: "routes.web.collectionTasks.title",
          subtitleKey: "routes.web.collectionTasks.subtitle",
        },
      },
      {
        path: "/collection-tasks/new",
        name: "collection-task-create",
        component: () => import("@/views/CollectionTaskEditorView.vue"),
        meta: {
          titleKey: "routes.web.collectionTasks.createTitle",
          subtitleKey: "routes.web.collectionTasks.createSubtitle",
        },
      },
      {
        path: "/collection-tasks/:taskId/edit",
        name: "collection-task-edit",
        component: () => import("@/views/CollectionTaskEditorView.vue"),
        meta: {
          titleKey: "routes.web.collectionTasks.editTitle",
          subtitleKey: "routes.web.collectionTasks.editSubtitle",
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
        path: "/workflows/:workflowId",
        name: "workflow-detail",
        component: () => import("@/views/WorkflowDetailView.vue"),
        meta: {
          titleKey: "routes.web.workflows.detailTitle",
          subtitleKey: "routes.web.workflows.detailSubtitle",
        },
      },
      {
        path: "/workflows/new",
        name: "workflow-create",
        component: () => import("@/views/WorkflowEditorView.vue"),
        meta: {
          titleKey: "routes.web.workflows.createTitle",
          subtitleKey: "routes.web.workflows.createSubtitle",
        },
      },
      {
        path: "/workflows/:workflowId/edit",
        name: "workflow-edit",
        component: () => import("@/views/WorkflowEditorView.vue"),
        meta: {
          titleKey: "routes.web.workflows.editorTitle",
          subtitleKey: "routes.web.workflows.editorSubtitle",
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
        path: "/runs/:workflowRunId",
        name: "workflow-run-detail",
        component: () => import("@/views/WorkflowRunDetailView.vue"),
        meta: {
          titleKey: "routes.web.runs.detailTitle",
          subtitleKey: "routes.web.runs.detailSubtitle",
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

  if (to.meta.requiresSuperAdmin) {
    const normalizedRoles = (authStore.systemRoleCodes ?? []).map((item) => item.toUpperCase());
    if (!normalizedRoles.includes("SUPER_ADMIN")) {
      return {
        path: "/dashboard",
      };
    }
  }

  return true;
});

export default router;
