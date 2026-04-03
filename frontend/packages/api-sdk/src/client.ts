import axios, { type AxiosInstance, type AxiosRequestConfig } from "axios";
import type {
  CapabilityMatrix,
  ConnectionTestResult,
  DataModelDefinition,
  DataModelSaveRequest,
  DataSourceDefinition,
  EntityId,
  ExportProjectBundle,
  LoginRequest,
  LoginResponse,
  MetadataSchemaDefinition,
  ModelSyncRequest,
  ModelDiscoveryResult,
  PermissionEntity,
  PluginCatalogEntry,
  Result,
  RoleEntity,
  RunListResponse,
  RuntimeModeResponse,
  StudioUser,
  WorkflowDefinitionView,
  WorkflowSaveRequest,
} from "./types";

export interface StudioApiOptions {
  baseURL?: string;
  timeout?: number;
  getToken?: () => string | null | undefined;
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
    const token = options.getToken?.();
    if (token) {
      config.headers = config.headers ?? {};
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });

  instance.interceptors.response.use(
    (response) => response,
    (error) => {
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
        return request<{ username: string | null }>({ url: "/auth/me", method: "GET" });
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
    schedules: {
      list() {
        return request<WorkflowDefinitionView[]>({ url: "/schedules", method: "GET" });
      },
    },
    runs: {
      list() {
        return request<RunListResponse>({ url: "/runs", method: "GET" });
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
