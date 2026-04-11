import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import type { StudioNavItem } from "@studio/ui";
import { useAuthStore } from "@/stores/auth";
import StudioLayout from "@/layout/StudioLayout.vue";

interface StudioMenuDescriptor {
  path: string;
  labelKey: string;
  captionKey: string;
  requiredRoleCodes?: string[];
  requiresProject?: boolean;
  visibleWhenNoProject?: boolean;
}

interface StudioMenuGroupDescriptor {
  key: string;
  labelKey: string;
  captionKey?: string;
  items: StudioMenuDescriptor[];
}

export const studioMenuDescriptors: StudioMenuGroupDescriptor[] = [
  {
    key: "workspace",
    labelKey: "routes.web.menuGroups.workspace.title",
    captionKey: "routes.web.menuGroups.workspace.caption",
    items: [
      { path: "/dashboard", labelKey: "routes.web.dashboard.title", captionKey: "routes.web.dashboard.menuCaption", requiresProject: true },
      { path: "/catalog", labelKey: "routes.web.catalog.title", captionKey: "routes.web.catalog.menuCaption", requiresProject: true },
      { path: "/access-center", labelKey: "routes.web.accessCenter.title", captionKey: "routes.web.accessCenter.subtitle", visibleWhenNoProject: true },
    ],
  },
  {
    key: "assets",
    labelKey: "routes.web.menuGroups.assets.title",
    captionKey: "routes.web.menuGroups.assets.caption",
    items: [
      { path: "/metadata", labelKey: "routes.web.metadata.title", captionKey: "routes.web.metadata.menuCaption", requiresProject: true },
      { path: "/datasources", labelKey: "routes.web.datasources.title", captionKey: "routes.web.datasources.menuCaption", requiresProject: true },
      { path: "/models", labelKey: "routes.web.models.title", captionKey: "routes.web.models.menuCaption", requiresProject: true },
      { path: "/statistics", labelKey: "routes.web.statistics.title", captionKey: "routes.web.statistics.menuCaption", requiresProject: true },
    ],
  },
  {
    key: "collection",
    labelKey: "routes.web.menuGroups.collection.title",
    captionKey: "routes.web.menuGroups.collection.caption",
    items: [
      {
        path: "/field-mapping-rules",
        labelKey: "routes.web.fieldMappingRules.title",
        captionKey: "routes.web.fieldMappingRules.menuCaption",
        requiredRoleCodes: ["SUPER_ADMIN"],
        requiresProject: true,
      },
      { path: "/collection-tasks", labelKey: "routes.web.collectionTasks.title", captionKey: "routes.web.collectionTasks.menuCaption", requiresProject: true },
      { path: "/collection-task-runs", labelKey: "routes.web.collectionTaskRuns.title", captionKey: "routes.web.collectionTaskRuns.menuCaption", requiresProject: true },
      { path: "/run-metrics", labelKey: "routes.web.runMetrics.title", captionKey: "routes.web.runMetrics.menuCaption", requiresProject: true },
    ],
  },
  {
    key: "development",
    labelKey: "routes.web.menuGroups.development.title",
    captionKey: "routes.web.menuGroups.development.caption",
    items: [
      { path: "/data-development", labelKey: "routes.web.dataDevelopment.title", captionKey: "routes.web.dataDevelopment.menuCaption", requiresProject: true },
      { path: "/workflows", labelKey: "routes.web.workflows.title", captionKey: "routes.web.workflows.menuCaption", requiresProject: true },
      { path: "/runs", labelKey: "routes.web.runs.title", captionKey: "routes.web.runs.menuCaption", requiresProject: true },
    ],
  },
  {
    key: "administration",
    labelKey: "routes.web.menuGroups.administration.title",
    captionKey: "routes.web.menuGroups.administration.caption",
    items: [
      { path: "/system", labelKey: "routes.web.system.title", captionKey: "routes.web.system.menuCaption", requiredRoleCodes: ["SUPER_ADMIN", "TENANT_ADMIN", "PROJECT_ADMIN"] },
    ],
  },
];

export function resolveStudioMenus(
  t: (key: string) => string,
  context: {
    systemRoleCodes?: string[];
    effectiveRoleCodes?: string[];
    hasProject?: boolean;
  } = {},
): StudioNavItem[] {
  const normalizedRoles = [
    ...(context.systemRoleCodes ?? []),
    ...(context.effectiveRoleCodes ?? []),
  ].map((item) => item.toUpperCase());
  const hasProject = Boolean(context.hasProject);
  const menus: StudioNavItem[] = [];
  for (const group of studioMenuDescriptors) {
    const children: StudioNavItem[] = group.items
      .filter((item) => isMenuVisible(item, normalizedRoles, hasProject))
      .map((item) => ({
        key: item.path,
        path: item.path,
        label: t(item.labelKey),
        caption: t(item.captionKey),
      }));
    if (children.length === 0) {
      continue;
    }
    menus.push({
      key: group.key,
      label: t(group.labelKey),
      caption: group.captionKey ? t(group.captionKey) : undefined,
      children,
    });
  }
  return menus;
}

function isMenuVisible(item: StudioMenuDescriptor, normalizedRoles: string[], hasProject: boolean) {
  if (item.visibleWhenNoProject) {
    return !hasProject;
  }
  if (item.requiresProject && !hasProject) {
    return false;
  }
  if (!item.requiredRoleCodes || item.requiredRoleCodes.length === 0) {
    return true;
  }
  return item.requiredRoleCodes.some((roleCode) => normalizedRoles.includes(roleCode.toUpperCase()));
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
    path: "/register",
    name: "register",
    component: () => import("@/views/RegisterView.vue"),
    meta: {
      public: true,
      titleKey: "routes.web.register.title",
      subtitleKey: "routes.web.register.subtitle",
    },
  },
  {
    path: "/",
    component: StudioLayout,
    redirect: "/dashboard",
    children: [
      {
        path: "/access-center",
        name: "access-center",
        component: () => import("@/views/WorkspaceAccessView.vue"),
        meta: {
          titleKey: "routes.web.accessCenter.title",
          subtitleKey: "routes.web.accessCenter.subtitle",
        },
      },
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
        path: "/collection-task-runs",
        name: "collection-task-runs",
        component: () => import("@/views/CollectionTaskRunsView.vue"),
        meta: {
          titleKey: "routes.web.collectionTaskRuns.title",
          subtitleKey: "routes.web.collectionTaskRuns.subtitle",
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
        path: "/run-metrics",
        name: "run-metrics",
        component: () => import("@/views/RunMetricsView.vue"),
        meta: {
          titleKey: "routes.web.runMetrics.title",
          subtitleKey: "routes.web.runMetrics.subtitle",
        },
      },
      {
        path: "/notifications",
        name: "notifications",
        component: () => import("@/views/NotificationsView.vue"),
        meta: {
          titleKey: "routes.web.notifications.title",
          subtitleKey: "routes.web.notifications.subtitle",
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

  if (!authStore.currentProjectId && !canAccessWithoutProject(to.path, authStore)) {
    return {
      path: "/access-center",
    };
  }

  return true;
});

function canAccessWithoutProject(path: string, authStore: ReturnType<typeof useAuthStore>) {
  if (!path) {
    return false;
  }
  if (path === "/access-center" || path.startsWith("/access-center/")) {
    return true;
  }
  if (path === "/notifications" || path.startsWith("/notifications/")) {
    return true;
  }
  if (path === "/system" || path.startsWith("/system/")) {
    const roleCodes = [
      ...(authStore.systemRoleCodes ?? []),
      ...(authStore.effectiveRoleCodes ?? []),
    ].map((item) => item.toUpperCase());
    return roleCodes.includes("SUPER_ADMIN") || roleCodes.includes("TENANT_ADMIN");
  }
  return false;
}

export default router;
