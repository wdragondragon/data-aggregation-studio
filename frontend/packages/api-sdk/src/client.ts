import axios, { type AxiosInstance, type AxiosRequestConfig } from "axios";
import { beginStudioApiRequest, endStudioApiRequest } from "./loading";
import type {
  AuthProfile,
  CapabilityMatrix,
  CollectionTaskDefinitionView,
  CollectionTaskListQuery,
  CollectionTaskSaveRequest,
  CollectionTaskScheduleDefinition,
  ConnectionTestResult,
  DataDevelopmentDirectory,
  DataDevelopmentDirectorySaveRequest,
  DataScriptExecutionRequest,
  DataScriptExecutionResult,
  DataDevelopmentMoveRequest,
  DataDevelopmentScript,
  DataDevelopmentScriptSaveRequest,
  DataDevelopmentTreeNode,
  DataModelDefinition,
  DataModelQueryRequest,
  DataModelSaveRequest,
  DataSourceDefinition,
  EntityId,
  ExportProjectBundle,
  JobContainerConfig,
  LoginRequest,
  LoginResponse,
  MetadataSchemaDefinition,
  ModelSyncRequest,
  ModelDiscoveryResult,
  PermissionEntity,
  PageResult,
  PluginCatalogEntry,
  Result,
  RunListQuery,
  RoleEntity,
  RunListResponse,
  RunLogView,
  RunRecord,
  ScriptType,
  SqlExecutionRequest,
  SqlExecutionResult,
  SystemProject,
  SystemProjectMember,
  SystemProjectMemberRequest,
  SystemProjectWorker,
  SystemTenant,
  SystemTenantMember,
  WorkflowRunDetail,
  WorkflowRunListQuery,
  WorkflowRunSummary,
  RuntimeModeResponse,
  ResourceShare,
  StudioUser,
  WorkflowDefinitionView,
  WorkflowSaveRequest,
} from "./types";

export interface StudioApiOptions {
  baseURL?: string;
  timeout?: number;
  getToken?: () => string | null | undefined;
  getTenantId?: () => string | null | undefined;
  getProjectId?: () => string | number | null | undefined;
  onUnauthorized?: () => void;
}

async function unwrap<T>(promise: Promise<{ data: Result<T> }>): Promise<T> {
  const response = await promise;
  if (!response.data.success) {
    throw new Error(response.data.message || "Request failed");
  }
  return response.data.data;
}

export function createStudioApi(options: StudioApiOptions = {}) {
  const instance: AxiosInstance = axios.create({
    baseURL: options.baseURL ?? "/api/v1",
    timeout: options.timeout ?? 20000,
  });

  instance.interceptors.request.use((config) => {
    beginStudioApiRequest();
    const token = options.getToken?.();
    if (token) {
      config.headers = config.headers ?? {};
      config.headers.Authorization = `Bearer ${token}`;
    }
    const tenantId = options.getTenantId?.();
    if (tenantId) {
      config.headers = config.headers ?? {};
      config.headers["X-Tenant-Id"] = tenantId;
    }
    const projectId = options.getProjectId?.();
    if (projectId != null && projectId !== "") {
      config.headers = config.headers ?? {};
      config.headers["X-Project-Id"] = String(projectId);
    }
    return config;
  }, (error) => {
    endStudioApiRequest();
    return Promise.reject(error);
  });

  instance.interceptors.response.use(
    (response) => {
      endStudioApiRequest();
      return response;
    },
    (error) => {
      endStudioApiRequest();
      if (error.response?.status === 401) {
        options.onUnauthorized?.();
      }
      return Promise.reject(error);
    },
  );

  const request = <T>(config: AxiosRequestConfig) => unwrap<T>(instance.request<Result<T>>(config) as Promise<{ data: Result<T> }>);

  return {
    auth: {
      login(payload: LoginRequest) {
        return request<LoginResponse>({ url: "/auth/login", method: "POST", data: payload });
      },
      me() {
        return request<AuthProfile>({ url: "/auth/me", method: "GET" });
      },
    },
    catalog: {
      plugins(category?: string) {
        return request<PluginCatalogEntry[]>({
          url: "/catalog/plugins",
          method: "GET",
          params: category ? { category } : undefined,
        });
      },
      capabilities() {
        return request<CapabilityMatrix>({ url: "/catalog/capabilities", method: "GET" });
      },
    },
    metaSchemas: {
      list() {
        return request<MetadataSchemaDefinition[]>({ url: "/meta-schemas", method: "GET" });
      },
      syncTechnical(typeCode: string) {
        return request<MetadataSchemaDefinition[]>({ url: `/meta-schemas/technical/sync/${typeCode}`, method: "POST" });
      },
      syncAllTechnical() {
        return request<MetadataSchemaDefinition[]>({ url: "/meta-schemas/technical/sync-all", method: "POST" });
      },
      saveDraft(payload: Record<string, unknown>) {
        return request<MetadataSchemaDefinition>({ url: "/meta-schemas/draft", method: "POST", data: payload });
      },
      publish(schemaId: EntityId) {
        return request<MetadataSchemaDefinition>({ url: `/meta-schemas/${schemaId}/publish`, method: "POST" });
      },
      delete(schemaId: EntityId) {
        return request<void>({ url: `/meta-schemas/${schemaId}`, method: "DELETE" });
      },
    },
    datasources: {
      list() {
        return request<DataSourceDefinition[]>({ url: "/datasources", method: "GET" });
      },
      get(id: EntityId) {
        return request<DataSourceDefinition>({ url: `/datasources/${id}`, method: "GET" });
      },
      save(payload: Record<string, unknown>) {
        return request<DataSourceDefinition>({ url: "/datasources", method: "POST", data: payload });
      },
      test(id: EntityId) {
        return request<ConnectionTestResult>({ url: `/datasources/${id}/test`, method: "POST" });
      },
      testCurrent(payload: Record<string, unknown>) {
        return request<ConnectionTestResult>({ url: "/datasources/test", method: "POST", data: payload });
      },
      discover(id: EntityId) {
        return request<ModelDiscoveryResult>({ url: `/datasources/${id}/discover`, method: "POST" });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/datasources/${id}`, method: "DELETE" });
      },
    },
    models: {
      list() {
        return request<DataModelDefinition[]>({ url: "/models", method: "GET" });
      },
      listByDatasource(datasourceId: EntityId) {
        return request<DataModelDefinition[]>({ url: `/models/datasource/${datasourceId}`, method: "GET" });
      },
      get(modelId: EntityId) {
        return request<DataModelDefinition>({ url: `/models/${modelId}`, method: "GET" });
      },
      query(payload: DataModelQueryRequest) {
        return request<DataModelDefinition[]>({ url: "/models/query", method: "POST", data: payload });
      },
      rebuildIndex(datasourceId?: EntityId) {
        return request<number>({
          url: "/models/index/rebuild",
          method: "POST",
          params: datasourceId == null ? undefined : { datasourceId },
        });
      },
      sync(datasourceId: EntityId) {
        return request<DataModelDefinition[]>({ url: `/models/datasource/${datasourceId}/sync`, method: "POST" });
      },
      syncSelected(datasourceId: EntityId, payload: ModelSyncRequest) {
        return request<DataModelDefinition[]>({ url: `/models/datasource/${datasourceId}/sync-selected`, method: "POST", data: payload });
      },
      save(payload: DataModelSaveRequest) {
        return request<DataModelDefinition>({ url: "/models", method: "POST", data: payload });
      },
      preview(modelId: EntityId, limit = 20) {
        return request<Record<string, unknown>[]>({
          url: `/models/${modelId}/preview`,
          method: "GET",
          params: { limit },
        });
      },
      delete(modelId: EntityId) {
        return request<void>({ url: `/models/${modelId}`, method: "DELETE" });
      },
    },
    workflows: {
      list() {
        return request<WorkflowDefinitionView[]>({ url: "/workflows", method: "GET" });
      },
      get(id: EntityId) {
        return request<WorkflowDefinitionView>({ url: `/workflows/${id}`, method: "GET" });
      },
      save(payload: WorkflowSaveRequest) {
        return request<WorkflowDefinitionView>({ url: "/workflows", method: "POST", data: payload });
      },
      publish(id: EntityId) {
        return request<WorkflowDefinitionView>({ url: `/workflows/${id}/publish`, method: "POST" });
      },
      trigger(id: EntityId) {
        return request<WorkflowDefinitionView>({ url: `/workflows/${id}/trigger`, method: "POST" });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/workflows/${id}`, method: "DELETE" });
      },
    },
    collectionTasks: {
      list(params?: CollectionTaskListQuery) {
        return request<CollectionTaskDefinitionView[]>({ url: "/collection-tasks", method: "GET", params });
      },
      listOnline() {
        return request<CollectionTaskDefinitionView[]>({ url: "/collection-tasks/online", method: "GET" });
      },
      get(id: EntityId) {
        return request<CollectionTaskDefinitionView>({ url: `/collection-tasks/${id}`, method: "GET" });
      },
      save(payload: CollectionTaskSaveRequest) {
        return request<CollectionTaskDefinitionView>({ url: "/collection-tasks", method: "POST", data: payload });
      },
      preview(payload: CollectionTaskSaveRequest) {
        return request<JobContainerConfig>({ url: "/collection-tasks/preview", method: "POST", data: payload });
      },
      publish(id: EntityId) {
        return request<CollectionTaskDefinitionView>({ url: `/collection-tasks/${id}/online`, method: "POST" });
      },
      saveSchedule(id: EntityId, payload: CollectionTaskScheduleDefinition) {
        return request<CollectionTaskDefinitionView>({ url: `/collection-tasks/${id}/schedule`, method: "POST", data: payload });
      },
      trigger(id: EntityId) {
        return request<CollectionTaskDefinitionView>({ url: `/collection-tasks/${id}/trigger`, method: "POST" });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/collection-tasks/${id}`, method: "DELETE" });
      },
    },
    dataDevelopment: {
      tree() {
        return request<DataDevelopmentTreeNode[]>({ url: "/data-development/tree", method: "GET" });
      },
      listDirectories() {
        return request<DataDevelopmentDirectory[]>({ url: "/data-development/directories", method: "GET" });
      },
      saveDirectory(payload: DataDevelopmentDirectorySaveRequest) {
        return request<DataDevelopmentDirectory>({ url: "/data-development/directories", method: "POST", data: payload });
      },
      moveDirectory(id: EntityId, payload: DataDevelopmentMoveRequest) {
        return request<void>({ url: `/data-development/directories/${id}/move`, method: "POST", data: payload });
      },
      deleteDirectory(id: EntityId) {
        return request<void>({ url: `/data-development/directories/${id}`, method: "DELETE" });
      },
      listScripts(scriptType?: ScriptType) {
        return request<DataDevelopmentScript[]>({
          url: "/data-development/scripts",
          method: "GET",
          params: scriptType ? { scriptType } : undefined,
        });
      },
      getScript(id: EntityId) {
        return request<DataDevelopmentScript>({ url: `/data-development/scripts/${id}`, method: "GET" });
      },
      saveScript(payload: DataDevelopmentScriptSaveRequest) {
        return request<DataDevelopmentScript>({ url: "/data-development/scripts", method: "POST", data: payload });
      },
      moveScript(id: EntityId, payload: DataDevelopmentMoveRequest) {
        return request<void>({ url: `/data-development/scripts/${id}/move`, method: "POST", data: payload });
      },
      deleteScript(id: EntityId) {
        return request<void>({ url: `/data-development/scripts/${id}`, method: "DELETE" });
      },
      listSqlDatasources() {
        return request<DataSourceDefinition[]>({ url: "/data-development/datasources", method: "GET" });
      },
      executeSql(payload: SqlExecutionRequest) {
        return request<SqlExecutionResult>({ url: "/data-development/sql/execute", method: "POST", data: payload });
      },
      executeScript(payload: DataScriptExecutionRequest) {
        return request<DataScriptExecutionResult>({ url: "/data-development/scripts/execute", method: "POST", data: payload });
      },
    },
    schedules: {
      list() {
        return request<WorkflowDefinitionView[]>({ url: "/schedules", method: "GET" });
      },
    },
    runs: {
      list(params?: RunListQuery) {
        return request<RunListResponse>({
          url: "/runs",
          method: "GET",
          params,
        });
      },
      get(id: EntityId) {
        return request<RunRecord>({ url: `/runs/${id}`, method: "GET" });
      },
      getLog(id: EntityId) {
        return request<RunLogView>({ url: `/runs/${id}/log`, method: "GET" });
      },
      downloadLog(id: EntityId) {
        return request<RunLogView>({ url: `/runs/${id}/log/download`, method: "GET" });
      },
    },
    workflowRuns: {
      list(params?: WorkflowRunListQuery) {
        return request<PageResult<WorkflowRunSummary>>({
          url: "/workflow-runs",
          method: "GET",
          params,
        });
      },
      get(workflowRunId: EntityId) {
        return request<WorkflowRunDetail>({ url: `/workflow-runs/${workflowRunId}`, method: "GET" });
      },
    },
    users: {
      list() {
        return request<StudioUser[]>({ url: "/users", method: "GET" });
      },
      save(payload: Partial<StudioUser>) {
        return request<StudioUser>({ url: "/users", method: "POST", data: payload });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/users/${id}`, method: "DELETE" });
      },
    },
    roles: {
      list() {
        return request<RoleEntity[]>({ url: "/roles", method: "GET" });
      },
      save(payload: Partial<RoleEntity>) {
        return request<RoleEntity>({ url: "/roles", method: "POST", data: payload });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/roles/${id}`, method: "DELETE" });
      },
    },
    permissions: {
      list() {
        return request<PermissionEntity[]>({ url: "/permissions", method: "GET" });
      },
      save(payload: Partial<PermissionEntity>) {
        return request<PermissionEntity>({ url: "/permissions", method: "POST", data: payload });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/permissions/${id}`, method: "DELETE" });
      },
    },
    system: {
      tenants: {
        list() {
          return request<SystemTenant[]>({ url: "/system/tenants", method: "GET" });
        },
        save(payload: Partial<SystemTenant>) {
          return request<SystemTenant>({ url: "/system/tenants", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/tenants/${id}`, method: "DELETE" });
        },
      },
      projects: {
        list() {
          return request<SystemProject[]>({ url: "/system/projects", method: "GET" });
        },
        save(payload: Partial<SystemProject>) {
          return request<SystemProject>({ url: "/system/projects", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/projects/${id}`, method: "DELETE" });
        },
      },
      tenantMembers: {
        list() {
          return request<SystemTenantMember[]>({ url: "/system/tenant-members", method: "GET" });
        },
        save(payload: Partial<SystemTenantMember>) {
          return request<SystemTenantMember>({ url: "/system/tenant-members", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/tenant-members/${id}`, method: "DELETE" });
        },
      },
      projectMembers: {
        list(projectId?: EntityId) {
          return request<SystemProjectMember[]>({
            url: "/system/project-members",
            method: "GET",
            params: projectId == null ? undefined : { projectId },
          });
        },
        save(payload: Partial<SystemProjectMember>) {
          return request<SystemProjectMember>({ url: "/system/project-members", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/project-members/${id}`, method: "DELETE" });
        },
      },
      projectMemberRequests: {
        list(projectId?: EntityId) {
          return request<SystemProjectMemberRequest[]>({
            url: "/system/project-member-requests",
            method: "GET",
            params: projectId == null ? undefined : { projectId },
          });
        },
        save(payload: Partial<SystemProjectMemberRequest>) {
          return request<SystemProjectMemberRequest>({ url: "/system/project-member-requests", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/project-member-requests/${id}`, method: "DELETE" });
        },
      },
      projectWorkers: {
        list(projectId?: EntityId) {
          return request<SystemProjectWorker[]>({
            url: "/system/project-workers",
            method: "GET",
            params: projectId == null ? undefined : { projectId },
          });
        },
        save(payload: Partial<SystemProjectWorker>) {
          return request<SystemProjectWorker>({ url: "/system/project-workers", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/project-workers/${id}`, method: "DELETE" });
        },
      },
      resourceShares: {
        list(params?: { resourceType?: string; projectId?: EntityId }) {
          return request<ResourceShare[]>({ url: "/system/resource-shares", method: "GET", params });
        },
        save(payload: Partial<ResourceShare>) {
          return request<ResourceShare>({ url: "/system/resource-shares", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/resource-shares/${id}`, method: "DELETE" });
        },
      },
    },
    imports: {
      template() {
        return request<Record<string, unknown>>({ url: "/imports/template", method: "GET" });
      },
    },
    exports: {
      project() {
        return request<ExportProjectBundle>({ url: "/exports/project", method: "GET" });
      },
    },
    runtime: {
      mode() {
        return request<RuntimeModeResponse>({ url: "/runtime/mode", method: "GET" });
      },
    },
  };
}
