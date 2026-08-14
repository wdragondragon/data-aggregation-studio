import axios, { type AxiosInstance, type AxiosRequestConfig } from "axios";
import { beginStudioApiRequest, endStudioApiRequest } from "./loading";
import type {
  AssistantConfigResponse,
  AssistantKnowledgeCapability,
  AssistantLearnRequest,
  AssistantLearnResponse,
  AssistantPortableSkill,
  AssistantStudioOperation,
  AssistantToolExecuteRequest,
  AssistantToolExecutionResult,
  AlertChannelQueryRequest,
  AlertChannelSaveRequest,
  AlertChannelView,
  AlertDeliveryQueryRequest,
  AlertDeliveryView,
  AlertEventView,
  AlertIncidentQueryRequest,
  AlertIncidentView,
  AlertOptionsView,
  AlertRuleQueryRequest,
  AlertRuleSaveRequest,
  AlertRuleView,
  AlertSelectOptionView,
  AlertSummaryView,
  AlertTenantProjectSummaryView,
  ArtifactStore,
  ArtifactStoreSaveRequest,
  ArtifactStoreTestResult,
  AuthProfile,
  CapabilityMatrix,
  CollectionTaskDefinitionView,
  CollectionTaskListView,
  CollectionTaskListQuery,
  CollectionTaskOptionView,
  CollectionTaskSaveRequest,
  CollectionTaskScheduleDefinition,
  CollectionTaskWorkflowOptionView,
  ConnectionTestResult,
  DataDevelopmentDirectory,
  DataDevelopmentDirectorySaveRequest,
  DataScriptExecutionRequest,
  DataScriptExecutionResult,
  DataDevelopmentMoveRequest,
  DataDevelopmentScript,
  DataDevelopmentScriptListView,
  DataDevelopmentScriptSaveRequest,
  DataDevelopmentTreeNode,
  EnvironmentDependency,
  EnvironmentDependencyListView,
  EnvironmentDependencyOption,
  EnvironmentDependencySaveRequest,
  DataModelDefinition,
  DataModelDatasourceOptionView,
  DataModelListView,
  DataModelOptionView,
  DataModelSqlHintView,
  DataModelLineageEdgeDetailView,
  DataModelLineageLevel,
  DataModelLineageView,
  DataModelIndexQueueStatusView,
  DataModelQueryRequest,
  DataModelManualLineageSaveRequest,
  DataModelStatisticsRequest,
  DataModelStatisticsOptionsRequest,
  DataModelStatisticsOptionsView,
  DataModelStatisticsChartRequest,
  DataModelStatisticsChartView,
  DataModelStatisticsView,
  DataModelSaveRequest,
  DatasourceClusterBindingImpactView,
  DatasourceConnectionTestRecordView,
  DatasourceTypeCapabilityView,
  DataSourceDefinition,
  DataSourceListView,
  DataSourceOptionView,
  DataSourceSaveRequest,
  DataIngestionDebugRequest,
  DataIngestionAccessLogListView,
  DataIngestionApiMetricView,
  DataIngestionInvokeResult,
  DataIngestionMetricDashboardView,
  DataIngestionMetricQueryRequest,
  DataIngestionResolveFieldsRequest,
  DataIngestionResolveFieldsView,
  DataIngestionServiceSaveRequest,
  DataIngestionServiceListView,
  DataIngestionServiceView,
  DataIngestionSubscriptionView,
  DataServiceDefinitionView,
  DataServiceListView,
  DataServiceDebugRequest,
  DataServiceInvokeResponse,
  DataServiceAccessLogListView,
  DataServiceApiMetricView,
  DataServiceMetricDashboardView,
  DataServiceMetricOptionsView,
  DataServiceMetricQueryRequest,
  DataServiceResolveFieldsRequest,
  DataServiceResolveFieldsView,
  DataServiceSaveRequest,
  DataServiceSubscriptionView,
  ElinkGroupOptionView,
  ElinkUserOptionView,
  EntityId,
  ExportProjectBundle,
  FieldMappingRuleSaveRequest,
  FieldMappingRuleListView,
  FieldMappingRuleOptionView,
  FieldMappingRuleView,
  FileTransferBrowserPageView,
  FileTransferBrowserRequest,
  FileTransferFileEntryView,
  FileTransferManualItemRequest,
  FileTransferManualRunRequest,
  FileTransferMetricDashboardView,
  FileTransferMetricQueryRequest,
  FileTransferPreviewRequest,
  FileTransferRunItemView,
  FileTransferRunView,
  FileTransferSelectionPreviewView,
  FileTransferTaskDefinitionView,
  FileTransferTaskSaveRequest,
  UnstructuredAclEntryView,
  UnstructuredArchiveDownloadRequest,
  UnstructuredDownloadTicketRequest,
  UnstructuredDownloadTicketView,
  UnstructuredOperationRequest,
  UnstructuredOperationResultView,
  UnstructuredPathAclRequest,
  UnstructuredPermissionView,
  UnstructuredSourceAclRequest,
  UnstructuredSourceView,
  UnstructuredUploadResultView,
  FollowRequest,
  FollowStatusView,
  JobContainerConfig,
  JavaImportHintResponse,
  JavaMemberHintResponse,
  LoginRequest,
  LoginResponse,
  MetadataSchemaDefinition,
  ModelSyncRequest,
  ModelDiscoveryOptionResult,
  ModelDiscoveryResult,
  ModelSyncTaskCreateRequest,
  ModelSyncTaskItemView,
  ModelSyncTaskView,
  NotificationQueryRequest,
  NotificationSnapshotView,
  NotificationView,
  OpsCenterLogEventView,
  OpsCenterOptionsView,
  OpsCenterOverviewView,
  OpsCenterQueryRequest,
  OpsCenterQueueItemView,
  OpsCenterRunIncidentView,
  OpsCenterServiceEventView,
  OpsCenterWorkerGroupView,
  PermissionEntity,
  PageResult,
  PluginRuntimeOptionSchemaView,
  PythonPackageQueryRequest,
  PythonPackageSummary,
  ProtocolConversionAccessLogListView,
  ProtocolConversionDebugResult,
  ProtocolConversionDebugRequest,
  ProtocolConversionMetricQueryRequest,
  ProtocolConversionServiceSaveRequest,
  ProtocolConversionServiceListView,
  ProtocolConversionServiceView,
  ProtocolConversionSubscriptionView,
  ProtocolConversionTraceView,
  QualityRuleListView,
  QualityRuleOptionView,
  QualityRuleParseRequest,
  QualityRuleParseResult,
  QualityRuleSaveRequest,
  QualityRuleValidationResult,
  QualityRuleValidateRequest,
  QualityRuleView,
  QualityTaskDefinitionView,
  QualityTaskListView,
  QualityTaskOptionView,
  QualityMetricOptionsView,
  QualityMetricDashboardView,
  QualityMetricDashboardQueryRequest,
  QualityAssetRiskView,
  QualityAssetDetailView,
  QualityAssetQueryRequest,
  QualityIssueAssigneeOptionView,
  QualityIssueView,
  QualityIssueDetailView,
  QualityIssueQueryRequest,
  QualityIssueAssignRequest,
  QualityIssueStatusRequest,
  QualityIssueSeverityRequest,
  QualityIssueCommentRequest,
  QualityTaskPreviewView,
  QualityTaskSaveRequest,
  QualityTaskValidationView,
  QualityTaskWorkflowOptionView,
  Result,
  RunMetricDashboardQueryRequest,
  RunMetricDashboardResponse,
  RunMetricFilterOption,
  RuntimeClusterProjectAuthorizationRequest,
  RuntimeClusterProjectAuthorizationView,
  RuntimeClusterInstanceView,
  RuntimeClusterSaveRequest,
  RuntimeClusterView,
  RuntimeEndpointSaveRequest,
  RuntimeEndpointView,
  RuntimeValidationQueryRequest,
  RuntimeValidationView,
  RunMetricOptionsView,
  RunListQuery,
  RunRecordPageQuery,
  RunRecordPageResponse,
  RoleEntity,
  RunListResponse,
  RunLogQuery,
  RunLogView,
  RunRecord,
  RunRecordListView,
  SavedDataScriptExecutionRequest,
  ScriptType,
  ScriptEnvironment,
  ScriptEnvironmentListView,
  ScriptEnvironmentOption,
  ScriptEnvironmentSaveRequest,
  SqlExecutionRequest,
  SqlExecutionResult,
  SystemProject,
  SystemProjectOption,
  SystemProjectMember,
  SystemProjectMemberRequest,
  SystemProjectWorkerOption,
  SystemProjectWorker,
  SystemTenant,
  SystemTenantMember,
  UserRegistrationRequestCreateRequest,
  UserRegistrationRequestReviewRequest,
  UserRegistrationRequestView,
  WorkspaceAccessApplyRequest,
  WorkspaceAccessOverviewView,
  WorkspaceAccessRequestView,
  WorkflowRunDetail,
  WorkflowRunListQuery,
  WorkflowRunSummary,
  RuntimeModeResponse,
  ResourceShare,
  ShareResourceOption,
  StudioDashboardView,
  StudioUser,
  StudioUserListView,
  StudioUserOption,
  WebServiceDebugRequest,
  WebServiceDebugResult,
  WebServicePreviewView,
  WorkflowDefinitionView,
  WorkflowListView,
  WorkflowOptionView,
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

export interface StudioRequestConfig extends AxiosRequestConfig {
  studioSkipGlobalLoading?: boolean;
}

const DATA_DEVELOPMENT_EXECUTION_TIMEOUT_MS = 120_000;

function shouldUseGlobalLoading(config?: unknown) {
  return !(config && typeof config === "object" && (config as StudioRequestConfig).studioSkipGlobalLoading);
}

async function unwrap<T>(promise: Promise<{ data: Result<T> }>): Promise<T> {
  const response = await promise;
  if (!response.data.success) {
    throw new Error(response.data.message || "Request failed");
  }
  return response.data.data;
}

function responseDataMessage(data: unknown): string {
  if (!data) {
    return "";
  }
  if (typeof data === "string") {
    const trimmed = data.trim();
    if (!trimmed) {
      return "";
    }
    try {
      return responseDataMessage(JSON.parse(trimmed));
    } catch {
      return trimmed;
    }
  }
  if (typeof data !== "object") {
    return "";
  }
  const record = data as Record<string, unknown>;
  const candidates = [
    record.message,
    record.msg,
    record.detail,
    record.error_description,
    record.error,
  ];
  for (const candidate of candidates) {
    if (typeof candidate === "string" && candidate.trim()) {
      return candidate.trim();
    }
  }
  if (record.data && typeof record.data === "object") {
    const nested = responseDataMessage(record.data);
    if (nested) {
      return nested;
    }
  }
  return "";
}

function responseDataCode(data: unknown): string {
  if (!data || typeof data !== "object") {
    return "";
  }
  const code = (data as Record<string, unknown>).code;
  return typeof code === "string" ? code : "";
}

function httpErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const businessMessage = responseDataMessage(error.response?.data);
    if (businessMessage) {
      return businessMessage;
    }
    if (error.response?.status) {
      const statusText = error.response.statusText ? ` ${error.response.statusText}` : "";
      return `HTTP ${error.response.status}${statusText}`;
    }
    return error.message || "网络请求失败";
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return "网络请求失败";
}

function normalizeRequestError(error: unknown): Error {
  const normalized = new Error(httpErrorMessage(error));
  if (error && typeof error === "object") {
    const source = error as {
      code?: unknown;
      config?: unknown;
      request?: unknown;
      response?: { status?: number; data?: unknown };
      status?: unknown;
    };
    Object.assign(normalized, {
      cause: error,
      code: responseDataCode(source.response?.data) || source.code,
      config: source.config,
      request: source.request,
      response: source.response,
      status: source.response?.status ?? source.status,
    });
  }
  return normalized;
}

function normalizePageResult<T>(payload: unknown, pageNo = 1, pageSize = 20): PageResult<T> {
  if (Array.isArray(payload)) {
    return {
      pageNo,
      pageSize,
      total: payload.length,
      items: payload as T[],
    };
  }
  if (payload && typeof payload === "object") {
    const candidate = payload as Partial<PageResult<T>>;
    if (Array.isArray(candidate.items)) {
      return {
        pageNo: Number(candidate.pageNo ?? pageNo),
        pageSize: Number(candidate.pageSize ?? pageSize),
        total: Number(candidate.total ?? candidate.items.length ?? 0),
        items: candidate.items,
      };
    }
  }
  return {
    pageNo,
    pageSize,
    total: 0,
    items: [],
  };
}

export function createStudioApi(options: StudioApiOptions = {}) {
  const instance: AxiosInstance = axios.create({
    baseURL: options.baseURL ?? "/api/v1",
    timeout: options.timeout ?? 20000,
  });

  instance.interceptors.request.use((config) => {
    if (shouldUseGlobalLoading(config)) {
      beginStudioApiRequest();
    }
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
    if (shouldUseGlobalLoading(error?.config)) {
      endStudioApiRequest();
    }
    return Promise.reject(error);
  });

  instance.interceptors.response.use(
    (response) => {
      if (shouldUseGlobalLoading(response.config)) {
        endStudioApiRequest();
      }
      return response;
    },
    (error) => {
      if (shouldUseGlobalLoading(error?.config)) {
        endStudioApiRequest();
      }
      if (error.response?.status === 401) {
        options.onUnauthorized?.();
      }
      return Promise.reject(normalizeRequestError(error));
    },
  );

  const request = <T>(config: StudioRequestConfig) => unwrap<T>(instance.request<Result<T>>(config) as Promise<{ data: Result<T> }>);
  const requestPage = <T>(config: StudioRequestConfig, query?: { pageNo?: number; pageSize?: number }) =>
    request<unknown>(config).then((payload) => normalizePageResult<T>(payload, query?.pageNo ?? 1, query?.pageSize ?? 20));
  const requestBlob = async (config: StudioRequestConfig) => {
    try {
      const response = await instance.request<Blob>({ ...config, responseType: "blob" });
      return response.data;
    } catch (error) {
      const source = error as { cause?: { response?: { data?: unknown } }; response?: { data?: unknown } };
      const data = source.cause?.response?.data ?? source.response?.data;
      if (data instanceof Blob) {
        const message = responseDataMessage(await data.text());
        if (message && error instanceof Error) {
          error.message = message;
        }
      }
      throw error;
    }
  };

  return {
    auth: {
      login(payload: LoginRequest) {
        return request<LoginResponse>({ url: "/auth/login", method: "POST", data: payload });
      },
      gatewayExchange() {
        return request<LoginResponse>({ url: "/auth/gateway/exchange", method: "POST" });
      },
      logout() {
        return request<void>({ url: "/auth/logout", method: "POST" });
      },
      me() {
        return request<AuthProfile>({ url: "/auth/me", method: "GET" });
      },
      submitRegisterRequest(payload: UserRegistrationRequestCreateRequest) {
        return request<void>({ url: "/auth/register-requests", method: "POST", data: payload });
      },
    },
    access: {
      overview() {
        return request<WorkspaceAccessOverviewView>({ url: "/access/overview", method: "GET" });
      },
      apply(payload: WorkspaceAccessApplyRequest) {
        return request<WorkspaceAccessRequestView>({ url: "/access/project-requests", method: "POST", data: payload });
      },
      cancel(requestId: EntityId) {
        return request<WorkspaceAccessRequestView>({ url: `/access/project-requests/${requestId}/cancel`, method: "POST" });
      },
    },
    dashboard: {
      overview() {
        return request<StudioDashboardView>({ url: "/dashboard/overview", method: "GET" });
      },
    },
    assistant: {
      config() {
        return request<AssistantConfigResponse>({ url: "/assistant/config", method: "GET", studioSkipGlobalLoading: true });
      },
      capabilities() {
        return request<AssistantKnowledgeCapability[]>({ url: "/assistant/capabilities", method: "GET" });
      },
      operations() {
        return request<AssistantStudioOperation[]>({ url: "/assistant/operations", method: "GET", studioSkipGlobalLoading: true });
      },
      skills() {
        return request<AssistantPortableSkill[]>({ url: "/assistant/skills", method: "GET", studioSkipGlobalLoading: true });
      },
      executeTool(payload: AssistantToolExecuteRequest) {
        return request<AssistantToolExecutionResult>({ url: "/assistant/tools/execute", method: "POST", data: payload, studioSkipGlobalLoading: true });
      },
      learn(payload: AssistantLearnRequest) {
        return request<AssistantLearnResponse>({ url: "/assistant/learn", method: "POST", data: payload, studioSkipGlobalLoading: true });
      },
    },
    catalog: {
      capabilities() {
        return request<CapabilityMatrix>({ url: "/catalog/capabilities", method: "GET" });
      },
      datasourceTypes() {
        return request<DatasourceTypeCapabilityView[]>({ url: "/catalog/datasource-types", method: "GET" });
      },
      runtimeOptionSchema(params: { role: string; datasourceType: string; protocolMode?: string }) {
        return request<PluginRuntimeOptionSchemaView>({
          url: "/catalog/runtime-option-schemas",
          method: "GET",
          params,
          studioSkipGlobalLoading: true,
        });
      },
    },
    fieldMappingRules: {
      list(params?: {
        pageNo?: number;
        pageSize?: number;
        keyword?: string;
        mappingType?: string;
        enabled?: boolean;
      }) {
        return requestPage<FieldMappingRuleListView>({ url: "/field-mapping-rules", method: "GET", params }, params);
      },
      get(id: EntityId) {
        return request<FieldMappingRuleView>({ url: `/field-mapping-rules/${id}`, method: "GET" });
      },
      save(payload: FieldMappingRuleSaveRequest) {
        return request<FieldMappingRuleView>({ url: "/field-mapping-rules", method: "POST", data: payload });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/field-mapping-rules/${id}`, method: "DELETE" });
      },
      options(mappingType?: string) {
        return request<FieldMappingRuleView[]>({
          url: "/field-mapping-rules/options",
          method: "GET",
          params: mappingType ? { mappingType } : undefined,
        });
      },
      optionSummaries(mappingType?: string) {
        return request<FieldMappingRuleOptionView[]>({
          url: "/field-mapping-rules/option-summaries",
          method: "GET",
          params: mappingType ? { mappingType } : undefined,
        });
      },
    },
    qualityRules: {
      list(params?: {
        pageNo?: number;
        pageSize?: number;
        keyword?: string;
        ruleDimension?: string;
        scopeType?: string;
        enabled?: boolean;
      }) {
        return requestPage<QualityRuleListView>({ url: "/quality-rules", method: "GET", params }, params);
      },
      get(id: EntityId) {
        return request<QualityRuleView>({ url: `/quality-rules/${id}`, method: "GET" });
      },
      save(payload: QualityRuleSaveRequest) {
        return request<QualityRuleView>({ url: "/quality-rules", method: "POST", data: payload });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/quality-rules/${id}`, method: "DELETE" });
      },
      batchDelete(ids: EntityId[]) {
        return request<void>({ url: "/quality-rules/batch-delete", method: "POST", data: { ids } });
      },
      enable(id: EntityId) {
        return request<QualityRuleView>({ url: `/quality-rules/${id}/enable`, method: "POST" });
      },
      enableSummary(id: EntityId) {
        return request<QualityRuleListView>({ url: `/quality-rules/${id}/enable-summary`, method: "POST" });
      },
      disable(id: EntityId) {
        return request<QualityRuleView>({ url: `/quality-rules/${id}/disable`, method: "POST" });
      },
      disableSummary(id: EntityId) {
        return request<QualityRuleListView>({ url: `/quality-rules/${id}/disable-summary`, method: "POST" });
      },
      parse(payload: QualityRuleParseRequest) {
        return request<QualityRuleParseResult>({ url: "/quality-rules/parse-params", method: "POST", data: payload });
      },
      validate(payload: QualityRuleValidateRequest) {
        return request<QualityRuleValidationResult>({ url: "/quality-rules/validate", method: "POST", data: payload });
      },
      options(params?: {
        ruleDimension?: string;
        granularity?: string;
        datasourceType?: string;
        enabledOnly?: boolean;
      }) {
        return request<QualityRuleView[]>({ url: "/quality-rules/options", method: "GET", params });
      },
      optionSummaries(params?: {
        ruleDimension?: string;
        granularity?: string;
        datasourceType?: string;
        enabledOnly?: boolean;
      }) {
        return request<QualityRuleOptionView[]>({ url: "/quality-rules/option-summaries", method: "GET", params });
      },
    },
    metaSchemas: {
      list(params?: { includeFields?: boolean }) {
        return request<MetadataSchemaDefinition[]>({ url: "/meta-schemas", method: "GET", params });
      },
      get(schemaId: EntityId) {
        return request<MetadataSchemaDefinition>({ url: `/meta-schemas/${schemaId}`, method: "GET" });
      },
      details(schemaIds: EntityId[]) {
        return request<MetadataSchemaDefinition[]>({
          url: "/meta-schemas/details",
          method: "GET",
          params: { schemaIds: schemaIds.map(String).join(",") },
        });
      },
      syncTechnical(typeCode: string) {
        return request<MetadataSchemaDefinition[]>({ url: `/meta-schemas/technical/sync/${typeCode}`, method: "POST" });
      },
      syncTechnicalSummary(typeCode: string) {
        return request<MetadataSchemaDefinition[]>({ url: `/meta-schemas/technical/sync/${typeCode}/summary`, method: "POST" });
      },
      syncAllTechnical() {
        return request<MetadataSchemaDefinition[]>({ url: "/meta-schemas/technical/sync-all", method: "POST" });
      },
      syncAllTechnicalSummary() {
        return request<MetadataSchemaDefinition[]>({ url: "/meta-schemas/technical/sync-all/summary", method: "POST" });
      },
      syncStandardRuntimeOptions() {
        return request<MetadataSchemaDefinition[]>({ url: "/meta-schemas/runtime-options/sync-standard", method: "POST" });
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
      list(config?: StudioRequestConfig) {
        return request<DataSourceListView[]>({ ...config, url: "/datasources", method: "GET" });
      },
      options(runtimeClusterId: EntityId, config?: StudioRequestConfig) {
        return request<DataSourceOptionView[]>({
          ...config,
          url: "/datasources/options",
          method: "GET",
          params: { runtimeClusterId },
        });
      },
      optionsByRuntimeCluster(runtimeClusterId: EntityId, config?: StudioRequestConfig) {
        return request<DataSourceOptionView[]>({
          ...config,
          url: "/datasources/options",
          method: "GET",
          params: { runtimeClusterId },
        });
      },
      listPage(params?: { pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
        return requestPage<DataSourceListView>({ ...config, url: "/datasources/page", method: "GET", params }, params);
      },
      get(id: EntityId) {
        return request<DataSourceDefinition>({ url: `/datasources/${id}`, method: "GET" });
      },
      save(payload: DataSourceSaveRequest) {
        return request<DataSourceDefinition>({ url: "/datasources", method: "POST", data: payload });
      },
      clusterBindingImpact(id: EntityId, applicableClusterIds: EntityId[]) {
        return request<DatasourceClusterBindingImpactView>({
          url: `/datasources/${id}/cluster-binding-impact`,
          method: "POST",
          data: { applicableClusterIds },
        });
      },
      test(id: EntityId, runtimeClusterId: EntityId) {
        return request<ConnectionTestResult>({
          url: `/datasources/${id}/test`,
          method: "POST",
          params: { runtimeClusterId },
        });
      },
      testCurrent(payload: DataSourceSaveRequest, runtimeClusterId: EntityId) {
        return request<ConnectionTestResult>({
          url: "/datasources/test",
          method: "POST",
          data: payload,
          params: { runtimeClusterId },
        });
      },
      connectionHistory(id: EntityId, params: { runtimeClusterId: EntityId; days?: number; limit?: number }) {
        return request<DatasourceConnectionTestRecordView[]>({
          url: `/datasources/${id}/connection-history`,
          method: "GET",
          params,
        });
      },
      discover(id: EntityId, options: { runtimeClusterId: EntityId; keyword?: string; pageNo?: number; pageSize?: number }) {
        const keyword = options.keyword?.trim();
        return request<ModelDiscoveryResult>({
          url: `/datasources/${id}/discover`,
          method: "POST",
          params: {
            ...(keyword ? { keyword } : {}),
            ...(options.pageNo ? { pageNo: options.pageNo } : {}),
            ...(options.pageSize ? { pageSize: options.pageSize } : {}),
            runtimeClusterId: options.runtimeClusterId,
          },
        });
      },
      discoverOptions(id: EntityId, options: { runtimeClusterId: EntityId; keyword?: string; pageNo?: number; pageSize?: number }) {
        const keyword = options.keyword?.trim();
        return request<ModelDiscoveryOptionResult>({
          url: `/datasources/${id}/discover-options`,
          method: "POST",
          params: {
            ...(keyword ? { keyword } : {}),
            ...(options.pageNo ? { pageNo: options.pageNo } : {}),
            ...(options.pageSize ? { pageSize: options.pageSize } : {}),
            runtimeClusterId: options.runtimeClusterId,
          },
        });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/datasources/${id}`, method: "DELETE" });
      },
    },
    runtimeClusters: {
      list(config?: StudioRequestConfig) {
        return request<RuntimeClusterView[]>({ ...config, url: "/runtime-clusters", method: "GET" });
      },
      options(projectId?: EntityId, config?: StudioRequestConfig) {
        return request<RuntimeClusterView[]>({
          ...config,
          url: "/runtime-clusters/options",
          method: "GET",
          params: projectId == null ? undefined : { projectId },
        });
      },
      get(id: EntityId) {
        return request<RuntimeClusterView>({ url: `/runtime-clusters/${id}`, method: "GET" });
      },
      save(payload: RuntimeClusterSaveRequest) {
        return request<RuntimeClusterView>({ url: "/runtime-clusters", method: "POST", data: payload });
      },
      enable(id: EntityId) {
        return request<RuntimeClusterView>({ url: `/runtime-clusters/${id}/enable`, method: "POST" });
      },
      disable(id: EntityId) {
        return request<RuntimeClusterView>({ url: `/runtime-clusters/${id}/disable`, method: "POST" });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/runtime-clusters/${id}`, method: "DELETE" });
      },
      instances(id: EntityId) {
        return request<RuntimeClusterInstanceView[]>({ url: `/runtime-clusters/${id}/instances`, method: "GET" });
      },
      endpoints(runtimeClusterId: EntityId) {
        return request<RuntimeEndpointView[]>({ url: `/runtime-clusters/${runtimeClusterId}/endpoints`, method: "GET" });
      },
      saveEndpoint(payload: RuntimeEndpointSaveRequest) {
        return request<RuntimeEndpointView>({ url: "/runtime-clusters/endpoints", method: "POST", data: payload });
      },
      testEndpoint(id: EntityId) {
        return request<RuntimeEndpointView>({ url: `/runtime-clusters/endpoints/${id}/test`, method: "POST" });
      },
      disableEndpoint(id: EntityId) {
        return request<RuntimeEndpointView>({ url: `/runtime-clusters/endpoints/${id}/disable`, method: "POST" });
      },
      deleteEndpoint(id: EntityId) {
        return request<void>({ url: `/runtime-clusters/endpoints/${id}`, method: "DELETE" });
      },
      projectAuthorizations(projectId: EntityId) {
        return request<RuntimeClusterProjectAuthorizationView[]>({
          url: "/runtime-clusters/project-authorizations",
          method: "GET",
          params: { projectId },
        });
      },
      saveProjectAuthorization(payload: RuntimeClusterProjectAuthorizationRequest) {
        return request<RuntimeClusterProjectAuthorizationView>({
          url: "/runtime-clusters/project-authorizations",
          method: "POST",
          data: payload,
        });
      },
      queryInvalidValidations(payload: RuntimeValidationQueryRequest = {}) {
        return request<RuntimeValidationView[]>({
          url: "/runtime-clusters/validations/query",
          method: "POST",
          data: payload,
        });
      },
    },
    dataServices: {
      list(params?: {
        pageNo?: number;
        pageSize?: number;
        keyword?: string;
        status?: string;
        serviceType?: string;
      }, config?: StudioRequestConfig) {
        return requestPage<DataServiceListView>({ ...config, url: "/data-services", method: "GET", params }, params);
      },
      get(id: EntityId) {
        return request<DataServiceDefinitionView>({ url: `/data-services/${id}`, method: "GET" });
      },
      save(payload: DataServiceSaveRequest) {
        return request<DataServiceDefinitionView>({ url: "/data-services", method: "POST", data: payload });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/data-services/${id}`, method: "DELETE" });
      },
      publish(id: EntityId) {
        return request<DataServiceDefinitionView>({ url: `/data-services/${id}/publish`, method: "POST" });
      },
      publishSummary(id: EntityId) {
        return request<DataServiceListView>({ url: `/data-services/${id}/publish-summary`, method: "POST" });
      },
      offline(id: EntityId) {
        return request<DataServiceDefinitionView>({ url: `/data-services/${id}/offline`, method: "POST" });
      },
      offlineSummary(id: EntityId) {
        return request<DataServiceListView>({ url: `/data-services/${id}/offline-summary`, method: "POST" });
      },
      resolveFields(payload: DataServiceResolveFieldsRequest) {
        return request<DataServiceResolveFieldsView>({ url: "/data-services/resolve-fields", method: "POST", data: payload });
      },
      async debug(id: EntityId, payload: DataServiceDebugRequest) {
        const response = await instance.request<Result<DataServiceInvokeResponse>>({ url: `/data-services/${id}/debug`, method: "POST", data: payload });
        if (!response.data.success) {
          throw new Error(response.data.message || "Request failed");
        }
        return response.data;
      },
      previewWebService(id: EntityId) {
        return request<WebServicePreviewView>({ url: `/data-services/${id}/webservice/preview`, method: "GET" });
      },
      debugWebService(id: EntityId, payload: WebServiceDebugRequest) {
        return request<WebServiceDebugResult>({ url: `/data-services/${id}/webservice/debug`, method: "POST", data: payload });
      },
      listSubscriptions(id: EntityId) {
        return request<DataServiceSubscriptionView[]>({ url: `/data-services/${id}/subscriptions`, method: "GET" });
      },
      createSubscription(id: EntityId, subscriptionName: string) {
        return request<DataServiceSubscriptionView>({
          url: `/data-services/${id}/subscriptions`,
          method: "POST",
          data: { subscriptionName },
        });
      },
      disableSubscription(id: EntityId, subscriptionId: EntityId) {
        return request<DataServiceSubscriptionView>({
          url: `/data-services/${id}/subscriptions/${subscriptionId}/disable`,
          method: "POST",
        });
      },
      rotateSubscription(id: EntityId, subscriptionId: EntityId) {
        return request<DataServiceSubscriptionView>({
          url: `/data-services/${id}/subscriptions/${subscriptionId}/rotate`,
          method: "POST",
        });
      },
      enableSubscription(id: EntityId, subscriptionId: EntityId) {
        return request<DataServiceSubscriptionView>({
          url: `/data-services/${id}/subscriptions/${subscriptionId}/enable`,
          method: "POST",
        });
      },
    },
    dataIngestionServices: {
      list(params?: {
        pageNo?: number;
        pageSize?: number;
        keyword?: string;
        status?: string;
        targetType?: string;
      }, config?: StudioRequestConfig) {
        return requestPage<DataIngestionServiceListView>({ ...config, url: "/data-ingestion-services", method: "GET", params }, params);
      },
      get(id: EntityId) {
        return request<DataIngestionServiceView>({ url: `/data-ingestion-services/${id}`, method: "GET" });
      },
      save(payload: DataIngestionServiceSaveRequest) {
        return request<DataIngestionServiceView>({ url: "/data-ingestion-services", method: "POST", data: payload });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/data-ingestion-services/${id}`, method: "DELETE" });
      },
      publish(id: EntityId) {
        return request<DataIngestionServiceView>({ url: `/data-ingestion-services/${id}/publish`, method: "POST" });
      },
      publishSummary(id: EntityId) {
        return request<DataIngestionServiceListView>({ url: `/data-ingestion-services/${id}/publish-summary`, method: "POST" });
      },
      offline(id: EntityId) {
        return request<DataIngestionServiceView>({ url: `/data-ingestion-services/${id}/offline`, method: "POST" });
      },
      offlineSummary(id: EntityId) {
        return request<DataIngestionServiceListView>({ url: `/data-ingestion-services/${id}/offline-summary`, method: "POST" });
      },
      resolveFields(payload: DataIngestionResolveFieldsRequest) {
        return request<DataIngestionResolveFieldsView>({ url: "/data-ingestion-services/resolve-fields", method: "POST", data: payload });
      },
      debug(id: EntityId, payload: DataIngestionDebugRequest) {
        return request<DataIngestionInvokeResult>({ url: `/data-ingestion-services/${id}/debug`, method: "POST", data: payload });
      },
      previewWebService(id: EntityId) {
        return request<WebServicePreviewView>({ url: `/data-ingestion-services/${id}/webservice/preview`, method: "GET" });
      },
      debugWebService(id: EntityId, payload: WebServiceDebugRequest) {
        return request<WebServiceDebugResult>({ url: `/data-ingestion-services/${id}/webservice/debug`, method: "POST", data: payload });
      },
      listSubscriptions(id: EntityId) {
        return request<DataIngestionSubscriptionView[]>({ url: `/data-ingestion-services/${id}/subscriptions`, method: "GET" });
      },
      createSubscription(id: EntityId, subscriptionName: string) {
        return request<DataIngestionSubscriptionView>({
          url: `/data-ingestion-services/${id}/subscriptions`,
          method: "POST",
          data: { subscriptionName },
        });
      },
      disableSubscription(id: EntityId, subscriptionId: EntityId) {
        return request<DataIngestionSubscriptionView>({
          url: `/data-ingestion-services/${id}/subscriptions/${subscriptionId}/disable`,
          method: "POST",
        });
      },
      rotateSubscription(id: EntityId, subscriptionId: EntityId) {
        return request<DataIngestionSubscriptionView>({
          url: `/data-ingestion-services/${id}/subscriptions/${subscriptionId}/rotate`,
          method: "POST",
        });
      },
      enableSubscription(id: EntityId, subscriptionId: EntityId) {
        return request<DataIngestionSubscriptionView>({
          url: `/data-ingestion-services/${id}/subscriptions/${subscriptionId}/enable`,
          method: "POST",
        });
      },
    },
    protocolConversions: {
      list(params?: {
        pageNo?: number;
        pageSize?: number;
        keyword?: string;
        status?: string;
      }, config?: StudioRequestConfig) {
        return requestPage<ProtocolConversionServiceListView>({ ...config, url: "/protocol-conversions", method: "GET", params }, params);
      },
      get(id: EntityId) {
        return request<ProtocolConversionServiceView>({ url: `/protocol-conversions/${id}`, method: "GET" });
      },
      save(payload: ProtocolConversionServiceSaveRequest) {
        return request<ProtocolConversionServiceView>({ url: "/protocol-conversions", method: "POST", data: payload });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/protocol-conversions/${id}`, method: "DELETE" });
      },
      publish(id: EntityId) {
        return request<ProtocolConversionServiceView>({ url: `/protocol-conversions/${id}/publish`, method: "POST" });
      },
      publishSummary(id: EntityId) {
        return request<ProtocolConversionServiceListView>({ url: `/protocol-conversions/${id}/publish-summary`, method: "POST" });
      },
      offline(id: EntityId) {
        return request<ProtocolConversionServiceView>({ url: `/protocol-conversions/${id}/offline`, method: "POST" });
      },
      offlineSummary(id: EntityId) {
        return request<ProtocolConversionServiceListView>({ url: `/protocol-conversions/${id}/offline-summary`, method: "POST" });
      },
      debug(id: EntityId, payload: ProtocolConversionDebugRequest) {
        return request<ProtocolConversionDebugResult>({ url: `/protocol-conversions/${id}/debug`, method: "POST", data: payload });
      },
      listSubscriptions(id: EntityId) {
        return request<ProtocolConversionSubscriptionView[]>({ url: `/protocol-conversions/${id}/subscriptions`, method: "GET" });
      },
      createSubscription(id: EntityId, subscriptionName: string) {
        return request<ProtocolConversionSubscriptionView>({
          url: `/protocol-conversions/${id}/subscriptions`,
          method: "POST",
          data: { subscriptionName },
        });
      },
      disableSubscription(id: EntityId, subscriptionId: EntityId) {
        return request<ProtocolConversionSubscriptionView>({
          url: `/protocol-conversions/${id}/subscriptions/${subscriptionId}/disable`,
          method: "POST",
        });
      },
      rotateSubscription(id: EntityId, subscriptionId: EntityId) {
        return request<ProtocolConversionSubscriptionView>({
          url: `/protocol-conversions/${id}/subscriptions/${subscriptionId}/rotate`,
          method: "POST",
        });
      },
      enableSubscription(id: EntityId, subscriptionId: EntityId) {
        return request<ProtocolConversionSubscriptionView>({
          url: `/protocol-conversions/${id}/subscriptions/${subscriptionId}/enable`,
          method: "POST",
        });
      },
    },
    protocolConversionMetrics: {
      options() {
        return request<DataServiceMetricOptionsView>({ url: "/protocol-conversion-metrics/options", method: "GET" });
      },
      queryAccessLogs(payload?: ProtocolConversionMetricQueryRequest) {
        return requestPage<ProtocolConversionAccessLogListView>({ url: "/protocol-conversion-metrics/access-logs/query", method: "POST", data: payload }, payload);
      },
      getAccessLogTrace(id: EntityId) {
        return request<ProtocolConversionTraceView>({ url: `/protocol-conversion-metrics/access-logs/${id}/trace`, method: "GET" });
      },
    },
    dataServiceMetrics: {
      options(config?: StudioRequestConfig) {
        return request<DataServiceMetricOptionsView>({ ...config, url: "/data-service-metrics/options", method: "GET" });
      },
      queryDashboard(payload?: DataServiceMetricQueryRequest, config?: StudioRequestConfig) {
        return request<DataServiceMetricDashboardView>({ ...config, url: "/data-service-metrics/dashboard/query", method: "POST", data: payload });
      },
      queryApiStats(payload?: DataServiceMetricQueryRequest, config?: StudioRequestConfig) {
        return requestPage<DataServiceApiMetricView>({ ...config, url: "/data-service-metrics/api-stats/query", method: "POST", data: payload }, payload);
      },
      queryAccessLogs(payload?: DataServiceMetricQueryRequest) {
        return requestPage<DataServiceAccessLogListView>({ url: "/data-service-metrics/access-logs/query", method: "POST", data: payload }, payload);
      },
    },
    dataIngestionMetrics: {
      options(config?: StudioRequestConfig) {
        return request<DataServiceMetricOptionsView>({ ...config, url: "/data-ingestion-metrics/options", method: "GET" });
      },
      queryDashboard(payload?: DataIngestionMetricQueryRequest, config?: StudioRequestConfig) {
        return request<DataIngestionMetricDashboardView>({ ...config, url: "/data-ingestion-metrics/dashboard/query", method: "POST", data: payload });
      },
      queryApiStats(payload?: DataIngestionMetricQueryRequest, config?: StudioRequestConfig) {
        return requestPage<DataIngestionApiMetricView>({ ...config, url: "/data-ingestion-metrics/api-stats/query", method: "POST", data: payload }, payload);
      },
      queryAccessLogs(payload?: DataIngestionMetricQueryRequest) {
        return requestPage<DataIngestionAccessLogListView>({ url: "/data-ingestion-metrics/access-logs/query", method: "POST", data: payload }, payload);
      },
    },
    models: {
      listPage(params?: {
        datasourceType?: string;
        pageNo?: number;
        pageSize?: number;
        sortField?: string;
        sortOrder?: string;
      }, config?: StudioRequestConfig) {
        return request<unknown>({ ...config, url: "/models", method: "GET", params }).then((payload) =>
          normalizePageResult<DataModelDefinition>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      listSummaryPage(params?: {
        datasourceType?: string;
        pageNo?: number;
        pageSize?: number;
        sortField?: string;
        sortOrder?: string;
      }, config?: StudioRequestConfig) {
        return request<unknown>({ ...config, url: "/models/summaries", method: "GET", params }).then((payload) =>
          normalizePageResult<DataModelListView>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      listOptions(params?: {
        keyword?: string;
        pageNo?: number;
        pageSize?: number;
      }, config?: StudioRequestConfig) {
        return request<unknown>({ ...config, url: "/models/options", method: "GET", params }).then((payload) =>
          normalizePageResult<DataModelOptionView>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      listSelectorOptions(params: {
        datasourceType?: string;
        datasourceId?: EntityId;
        runtimeClusterId: EntityId;
        keyword?: string;
        pageNo?: number;
        pageSize?: number;
      }, config?: StudioRequestConfig) {
        return request<unknown>({ ...config, url: "/models/selector-options", method: "GET", params }).then((payload) =>
          normalizePageResult<DataModelDatasourceOptionView>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      async list(params?: {
        datasourceType?: string;
        pageNo?: number;
        pageSize?: number;
        sortField?: string;
        sortOrder?: string;
      }) {
        const result = await request<PageResult<DataModelDefinition>>({
          url: "/models",
          method: "GET",
          params: {
            datasourceType: params?.datasourceType,
            pageNo: params?.pageNo ?? 1,
            pageSize: params?.pageSize ?? 5000,
            sortField: params?.sortField,
            sortOrder: params?.sortOrder,
          },
        });
        return result.items;
      },
      async listSummaries(params?: {
        datasourceType?: string;
        pageNo?: number;
        pageSize?: number;
        sortField?: string;
        sortOrder?: string;
      }) {
        const result = await request<PageResult<DataModelListView>>({
          url: "/models/summaries",
          method: "GET",
          params: {
            datasourceType: params?.datasourceType,
            pageNo: params?.pageNo ?? 1,
            pageSize: params?.pageSize ?? 5000,
            sortField: params?.sortField,
            sortOrder: params?.sortOrder,
          },
        });
        return result.items;
      },
      listByDatasourcePage(datasourceId: EntityId, params?: {
        pageNo?: number;
        pageSize?: number;
        sortField?: string;
        sortOrder?: string;
      }) {
        return request<unknown>({
          url: `/models/datasource/${datasourceId}`,
          method: "GET",
          params,
        }).then((payload) =>
          normalizePageResult<DataModelDefinition>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      listSummaryByDatasourcePage(datasourceId: EntityId, params?: {
        pageNo?: number;
        pageSize?: number;
        sortField?: string;
        sortOrder?: string;
      }) {
        return request<unknown>({
          url: `/models/datasource/${datasourceId}/summaries`,
          method: "GET",
          params,
        }).then((payload) =>
          normalizePageResult<DataModelListView>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      listDatasourceOptions(datasourceId: EntityId, params?: {
        keyword?: string;
        pageNo?: number;
        pageSize?: number;
      }) {
        return request<unknown>({
          url: `/models/datasource/${datasourceId}/options`,
          method: "GET",
          params,
        }).then((payload) =>
          normalizePageResult<DataModelDatasourceOptionView>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      async listByDatasource(datasourceId: EntityId, params?: {
        pageNo?: number;
        pageSize?: number;
        sortField?: string;
        sortOrder?: string;
      }) {
        const result = await request<PageResult<DataModelDefinition>>({
          url: `/models/datasource/${datasourceId}`,
          method: "GET",
          params: {
            pageNo: params?.pageNo ?? 1,
            pageSize: params?.pageSize ?? 5000,
            sortField: params?.sortField,
            sortOrder: params?.sortOrder,
          },
        });
        return result.items;
      },
      async listSummariesByDatasource(datasourceId: EntityId, params?: {
        pageNo?: number;
        pageSize?: number;
        sortField?: string;
        sortOrder?: string;
      }) {
        const result = await request<PageResult<DataModelListView>>({
          url: `/models/datasource/${datasourceId}/summaries`,
          method: "GET",
          params: {
            pageNo: params?.pageNo ?? 1,
            pageSize: params?.pageSize ?? 5000,
            sortField: params?.sortField,
            sortOrder: params?.sortOrder,
          },
        });
        return result.items;
      },
      listSqlHintsByDatasource(datasourceId: EntityId, config?: StudioRequestConfig) {
        return request<DataModelSqlHintView[]>({ ...config, url: `/models/datasource/${datasourceId}/sql-hints`, method: "GET" });
      },
      listSqlHintsByIds(modelIds: EntityId[], config?: StudioRequestConfig) {
        const normalizedModelIds = modelIds.map((item) => String(item).trim()).filter(Boolean);
        if (normalizedModelIds.length === 0) {
          return Promise.resolve([] as DataModelSqlHintView[]);
        }
        return request<DataModelSqlHintView[]>({
          ...config,
          url: "/models/sql-hints",
          method: "GET",
          params: { modelIds: normalizedModelIds.join(",") },
        });
      },
      get(modelId: EntityId, config?: StudioRequestConfig) {
        return request<DataModelDefinition>({ ...config, url: `/models/${modelId}`, method: "GET" });
      },
      queryPage(payload: DataModelQueryRequest, params?: {
        pageNo?: number;
        pageSize?: number;
      }) {
        return request<unknown>({
          url: "/models/query",
          method: "POST",
          data: payload,
          params,
        }).then((result) =>
          normalizePageResult<DataModelDefinition>(
            result,
            params?.pageNo ?? payload.pageNo ?? 1,
            params?.pageSize ?? payload.pageSize ?? 20,
          ),
        );
      },
      querySummaryPage(payload: DataModelQueryRequest, params?: {
        pageNo?: number;
        pageSize?: number;
      }) {
        return request<unknown>({
          url: "/models/query/summaries",
          method: "POST",
          data: payload,
          params,
        }).then((result) =>
          normalizePageResult<DataModelListView>(
            result,
            params?.pageNo ?? payload.pageNo ?? 1,
            params?.pageSize ?? payload.pageSize ?? 20,
          ),
        );
      },
      async query(payload: DataModelQueryRequest, params?: {
        pageNo?: number;
        pageSize?: number;
      }) {
        const result = await request<PageResult<DataModelDefinition>>({
          url: "/models/query",
          method: "POST",
          data: payload,
          params: {
            pageNo: params?.pageNo ?? payload.pageNo ?? 1,
            pageSize: params?.pageSize ?? payload.pageSize ?? 5000,
          },
        });
        return result.items;
      },
      async querySummaries(payload: DataModelQueryRequest, params?: {
        pageNo?: number;
        pageSize?: number;
      }) {
        const result = await request<PageResult<DataModelListView>>({
          url: "/models/query/summaries",
          method: "POST",
          data: payload,
          params: {
            pageNo: params?.pageNo ?? payload.pageNo ?? 1,
            pageSize: params?.pageSize ?? payload.pageSize ?? 5000,
          },
        });
        return result.items;
      },
      statistics(payload: DataModelStatisticsRequest) {
        return request<DataModelStatisticsView>({ url: "/models/statistics", method: "POST", data: payload });
      },
      rebuildIndex(datasourceId?: EntityId) {
        return request<number>({
          url: "/models/index/rebuild",
          method: "POST",
          params: datasourceId == null ? undefined : { datasourceId },
        });
      },
      indexQueueStatus() {
        return request<DataModelIndexQueueStatusView>({
          url: "/models/index/queue-status",
          method: "GET",
        });
      },
      sync(datasourceId: EntityId, runtimeClusterId: EntityId) {
        return request<DataModelDefinition[]>({
          url: `/models/datasource/${datasourceId}/sync`,
          method: "POST",
          params: { runtimeClusterId },
        });
      },
      syncSelected(datasourceId: EntityId, payload: ModelSyncRequest) {
        return request<DataModelDefinition[]>({ url: `/models/datasource/${datasourceId}/sync-selected`, method: "POST", data: payload });
      },
      save(payload: DataModelSaveRequest) {
        return request<DataModelDefinition>({ url: "/models", method: "POST", data: payload });
      },
      preview(modelId: EntityId, limit: number, runtimeClusterId: EntityId) {
        return request<Record<string, unknown>[]>({
          url: `/models/${modelId}/preview`,
          method: "GET",
          params: { limit, runtimeClusterId },
          studioSkipGlobalLoading: true,
        });
      },
      lineage(modelId: EntityId, level: DataModelLineageLevel | string) {
        return request<DataModelLineageView>({
          url: `/models/${modelId}/lineage`,
          method: "GET",
          params: { level },
        });
      },
      lineageEdgeDetail(modelId: EntityId, edgeId: string, level: DataModelLineageLevel | string) {
        return request<DataModelLineageEdgeDetailView>({
          url: `/models/${modelId}/lineage/edges/${encodeURIComponent(edgeId)}`,
          method: "GET",
          params: { level },
        });
      },
      createManualLineage(modelId: EntityId, payload: DataModelManualLineageSaveRequest) {
        return request<void>({
          url: `/models/${modelId}/lineage/manual`,
          method: "POST",
          data: payload,
        });
      },
      updateManualLineage(modelId: EntityId, relationId: EntityId, payload: DataModelManualLineageSaveRequest) {
        return request<void>({
          url: `/models/${modelId}/lineage/manual/${relationId}`,
          method: "PUT",
          data: payload,
        });
      },
      deleteManualLineage(modelId: EntityId, relationId: EntityId) {
        return request<void>({
          url: `/models/${modelId}/lineage/manual/${relationId}`,
          method: "DELETE",
        });
      },
      delete(modelId: EntityId) {
        return request<void>({ url: `/models/${modelId}`, method: "DELETE" });
      },
    },
    modelSyncTasks: {
      create(payload: ModelSyncTaskCreateRequest) {
        return request<ModelSyncTaskView>({ url: "/model-sync-tasks", method: "POST", data: payload });
      },
      list(params?: {
        pageNo?: number;
        pageSize?: number;
        datasourceType?: string;
        datasourceId?: EntityId;
        status?: string;
      }) {
        return requestPage<ModelSyncTaskView>({ url: "/model-sync-tasks", method: "GET", params }, params);
      },
      get(taskId: EntityId) {
        return request<ModelSyncTaskView>({ url: `/model-sync-tasks/${taskId}`, method: "GET" });
      },
      listItems(taskId: EntityId, params?: {
        pageNo?: number;
        pageSize?: number;
        keyword?: string;
        status?: string;
      }) {
        return requestPage<ModelSyncTaskItemView>({
          url: `/model-sync-tasks/${taskId}/items`,
          method: "GET",
          params,
        }, params);
      },
      stop(taskId: EntityId) {
        return request<ModelSyncTaskView>({ url: `/model-sync-tasks/${taskId}/stop`, method: "POST" });
      },
      delete(taskId: EntityId) {
        return request<void>({ url: `/model-sync-tasks/${taskId}`, method: "DELETE" });
      },
    },
    statistics: {
      options(payload?: DataModelStatisticsOptionsRequest) {
        return request<DataModelStatisticsOptionsView>({ url: "/statistics/options", method: "POST", data: payload });
      },
      queryChart(payload: DataModelStatisticsChartRequest) {
        return request<DataModelStatisticsChartView>({ url: "/statistics/charts/query", method: "POST", data: payload });
      },
    },
    workflows: {
      list(config?: StudioRequestConfig) {
        return request<WorkflowListView[]>({ ...config, url: "/workflows", method: "GET" });
      },
      options(config?: StudioRequestConfig) {
        return request<WorkflowOptionView[]>({ ...config, url: "/workflows/options", method: "GET" });
      },
      listPage(params?: { pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
        return requestPage<WorkflowListView>({ ...config, url: "/workflows/page", method: "GET", params }, params);
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
      trigger(id: EntityId, runtimeClusterId?: EntityId | null) {
        return request<void>({
          url: `/workflows/${id}/trigger`,
          method: "POST",
          data: runtimeClusterId == null ? undefined : { runtimeClusterId },
        });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/workflows/${id}`, method: "DELETE" });
      },
    },
    collectionTasks: {
      list(params?: CollectionTaskListQuery, config?: StudioRequestConfig) {
        return request<CollectionTaskListView[]>({ ...config, url: "/collection-tasks", method: "GET", params });
      },
      options(config?: StudioRequestConfig) {
        return request<CollectionTaskOptionView[]>({ ...config, url: "/collection-tasks/options", method: "GET" });
      },
      listPage(params?: CollectionTaskListQuery & { pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
        return request<unknown>({ ...config, url: "/collection-tasks/page", method: "GET", params }).then((payload) =>
          normalizePageResult<CollectionTaskListView>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      listOnline() {
        return request<CollectionTaskListView[]>({ url: "/collection-tasks/online", method: "GET" });
      },
      workflowOptions(params: {
        pageNo?: number;
        pageSize?: number;
        keyword?: string;
        runtimeClusterId: EntityId;
      }) {
        return request<unknown>({ url: "/collection-tasks/workflow-options", method: "GET", params }).then((payload) =>
          normalizePageResult<CollectionTaskWorkflowOptionView>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
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
        return request<CollectionTaskListView>({ url: `/collection-tasks/${id}/online`, method: "POST" });
      },
      saveSchedule(id: EntityId, payload: CollectionTaskScheduleDefinition) {
        return request<CollectionTaskDefinitionView>({ url: `/collection-tasks/${id}/schedule`, method: "POST", data: payload });
      },
      resetIncrementalCursor(id: EntityId, sourceAlias?: string, scope?: { incrColumn?: string; incrModel?: string }) {
        return request<CollectionTaskDefinitionView>({
          url: `/collection-tasks/${id}/incremental-cursors/reset`,
          method: "POST",
          params: {
            ...(sourceAlias ? { sourceAlias } : {}),
            ...(scope?.incrColumn ? { incrColumn: scope.incrColumn } : {}),
            ...(scope?.incrModel ? { incrModel: scope.incrModel } : {}),
          },
        });
      },
      trigger(id: EntityId, runtimeClusterId?: EntityId | null) {
        return request<void>({
          url: `/collection-tasks/${id}/trigger`,
          method: "POST",
          data: runtimeClusterId == null ? undefined : { runtimeClusterId },
        });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/collection-tasks/${id}`, method: "DELETE" });
      },
    },
    fileTransfer: {
      browser: {
        list(payload: FileTransferBrowserRequest, config?: StudioRequestConfig) {
          return request<FileTransferBrowserPageView>({
            ...config,
            url: "/file-transfer/browser/list",
            method: "POST",
            data: payload,
          });
        },
      },
      runs: {
        createManual(payload: FileTransferManualRunRequest) {
          return request<FileTransferRunView>({ url: "/file-transfer/runs/manual", method: "POST", data: payload });
        },
        addItems(runId: EntityId, payload: FileTransferManualItemRequest[]) {
          return request<FileTransferRunView>({ url: `/file-transfer/runs/${runId}/items`, method: "POST", data: payload });
        },
        list(params?: { pageNo?: number; pageSize?: number; taskId?: EntityId; status?: string; triggerType?: string; statusGroup?: "ACTIVE" | "TERMINAL" }, config?: StudioRequestConfig) {
          return requestPage<FileTransferRunView>({
            ...config,
            url: "/file-transfer/runs",
            method: "GET",
            params,
          }, params);
        },
        get(runId: EntityId, config?: StudioRequestConfig) {
          return request<FileTransferRunView>({ ...config, url: `/file-transfer/runs/${runId}`, method: "GET" });
        },
        items(runId: EntityId, params?: { pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
          return requestPage<FileTransferRunItemView>({
            ...config,
            url: `/file-transfer/runs/${runId}/items`,
            method: "GET",
            params,
          }, params);
        },
        pause(runId: EntityId) {
          return request<FileTransferRunView>({ url: `/file-transfer/runs/${runId}/pause`, method: "POST" });
        },
        resume(runId: EntityId) {
          return request<FileTransferRunView>({ url: `/file-transfer/runs/${runId}/resume`, method: "POST" });
        },
        cancel(runId: EntityId) {
          return request<FileTransferRunView>({ url: `/file-transfer/runs/${runId}/cancel`, method: "POST" });
        },
        remove(runId: EntityId) {
          return request<void>({ url: `/file-transfer/runs/${runId}`, method: "DELETE" });
        },
        retryItem(runId: EntityId, itemId: EntityId) {
          return request<FileTransferRunView>({ url: `/file-transfer/runs/${runId}/items/${itemId}/retry`, method: "POST" });
        },
        removeItem(runId: EntityId, itemId: EntityId) {
          return request<void>({ url: `/file-transfer/runs/${runId}/items/${itemId}`, method: "DELETE" });
        },
      },
      tasks: {
        list(params?: { pageNo?: number; pageSize?: number; keyword?: string; status?: string }, config?: StudioRequestConfig) {
          return requestPage<FileTransferTaskDefinitionView>({
            ...config,
            url: "/file-transfer-tasks",
            method: "GET",
            params,
          }, params);
        },
        get(id: EntityId, config?: StudioRequestConfig) {
          return request<FileTransferTaskDefinitionView>({ ...config, url: `/file-transfer-tasks/${id}`, method: "GET" });
        },
        create(payload: FileTransferTaskSaveRequest) {
          return request<FileTransferTaskDefinitionView>({ url: "/file-transfer-tasks", method: "POST", data: payload });
        },
        update(id: EntityId, payload: FileTransferTaskSaveRequest) {
          return request<FileTransferTaskDefinitionView>({ url: `/file-transfer-tasks/${id}`, method: "PUT", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/file-transfer-tasks/${id}`, method: "DELETE" });
        },
        validate(id: EntityId) {
          return request<Record<string, unknown>>({ url: `/file-transfer-tasks/${id}/validate`, method: "POST" });
        },
        preview(id: EntityId, payload?: FileTransferPreviewRequest) {
          return request<FileTransferSelectionPreviewView>({
            url: `/file-transfer-tasks/${id}/preview-selection`,
            method: "POST",
            data: payload,
          });
        },
        publish(id: EntityId) {
          return request<FileTransferTaskDefinitionView>({ url: `/file-transfer-tasks/${id}/publish`, method: "POST" });
        },
        offline(id: EntityId) {
          return request<FileTransferTaskDefinitionView>({ url: `/file-transfer-tasks/${id}/offline`, method: "POST" });
        },
        trigger(id: EntityId) {
          return request<FileTransferRunView>({ url: `/file-transfer-tasks/${id}/trigger`, method: "POST" });
        },
      },
      metrics: {
        dashboard(payload?: FileTransferMetricQueryRequest, config?: StudioRequestConfig) {
          return request<FileTransferMetricDashboardView>({
            ...config,
            url: "/file-transfer-metrics/dashboard",
            method: "POST",
            data: payload,
          });
        },
      },
    },
    unstructuredManagement: {
      sources(runtimeClusterId: EntityId, config?: StudioRequestConfig) {
        return request<UnstructuredSourceView[]>({ ...config, url: "/unstructured-management/sources", method: "GET", params: { runtimeClusterId } });
      },
      browser: {
        list(payload: FileTransferBrowserRequest, config?: StudioRequestConfig) {
          return request<FileTransferBrowserPageView>({ ...config, url: "/unstructured-management/browser/list", method: "POST", data: payload });
        },
      },
      stat(params: { runtimeClusterId: EntityId; datasourceId: EntityId; path: string }, config?: StudioRequestConfig) {
        return request<FileTransferFileEntryView>({ ...config, url: "/unstructured-management/stat", method: "GET", params });
      },
      upload(
        params: { runtimeClusterId: EntityId; datasourceId: EntityId; targetPath: string; overwrite?: boolean },
        file: File,
        onProgress?: (percentage: number) => void,
        config?: StudioRequestConfig,
      ) {
        return request<UnstructuredUploadResultView>({
          ...config,
          url: "/unstructured-management/upload",
          method: "POST",
          params,
          data: file,
          timeout: 0,
          headers: { ...config?.headers, "Content-Type": "application/octet-stream" },
          onUploadProgress: (event) => {
            const total = event.total ?? file.size;
            onProgress?.(total > 0 ? Math.min(100, Math.round((event.loaded / total) * 100)) : 100);
          },
        });
      },
      download(params: { runtimeClusterId: EntityId; datasourceId: EntityId; path: string }, config?: StudioRequestConfig) {
        return requestBlob({ ...config, url: "/unstructured-management/download", method: "GET", params, timeout: 0 });
      },
      downloadArchive(payload: UnstructuredArchiveDownloadRequest, config?: StudioRequestConfig) {
        return requestBlob({ ...config, url: "/unstructured-management/download/archive", method: "POST", data: payload, timeout: 0 });
      },
      createDownloadTicket(payload: UnstructuredDownloadTicketRequest, config?: StudioRequestConfig) {
        return request<UnstructuredDownloadTicketView>({
          ...config,
          url: "/unstructured-management/download-tickets",
          method: "POST",
          data: payload,
        });
      },
      operate(payload: UnstructuredOperationRequest) {
        return request<UnstructuredOperationResultView>({ url: "/unstructured-management/operations", method: "POST", data: payload });
      },
      sourceAcl(datasourceId: EntityId) {
        return request<UnstructuredAclEntryView[]>({ url: `/unstructured-management/acl/source/${datasourceId}`, method: "GET" });
      },
      replaceSourceAcl(datasourceId: EntityId, payload: UnstructuredSourceAclRequest) {
        return request<UnstructuredAclEntryView[]>({ url: `/unstructured-management/acl/source/${datasourceId}`, method: "PUT", data: payload });
      },
      pathAcl(datasourceId: EntityId, path: string) {
        return request<UnstructuredAclEntryView[]>({ url: "/unstructured-management/acl/path", method: "GET", params: { datasourceId, path } });
      },
      replacePathAcl(payload: UnstructuredPathAclRequest) {
        return request<UnstructuredAclEntryView[]>({ url: "/unstructured-management/acl/path", method: "PUT", data: payload });
      },
      deleteAcl(id: EntityId) {
        return request<void>({ url: `/unstructured-management/acl/${id}`, method: "DELETE" });
      },
      userOptions() {
        return request<StudioUserOption[]>( { url: "/unstructured-management/users/options", method: "GET" });
      },
      permissions(datasourceId: EntityId, path = "/") {
        return request<UnstructuredPermissionView>({ url: "/unstructured-management/permissions", method: "GET", params: { datasourceId, path }, studioSkipGlobalLoading: true });
      },
    },
    qualityTasks: {
      listPage(params?: {
        pageNo?: number;
        pageSize?: number;
        keyword?: string;
        status?: string;
        ruleDimension?: string;
        granularity?: string;
      }) {
        return request<unknown>({ url: "/quality-tasks", method: "GET", params }).then((payload) =>
          normalizePageResult<QualityTaskListView>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      async list(params?: {
        keyword?: string;
        status?: string;
        ruleDimension?: string;
        granularity?: string;
      }) {
        const pageSize = 200;
        const firstPagePayload = await request<unknown>({
          url: "/quality-tasks",
          method: "GET",
          params: {
            ...params,
            pageNo: 1,
            pageSize,
          },
        });
        const firstPage = normalizePageResult<QualityTaskListView>(firstPagePayload, 1, pageSize);
        const items = [...firstPage.items];
        const totalPages = Math.ceil(firstPage.total / pageSize);
        for (let pageNo = 2; pageNo <= totalPages; pageNo += 1) {
          const pagePayload = await request<unknown>({
            url: "/quality-tasks",
            method: "GET",
            params: {
              ...params,
              pageNo,
              pageSize,
            },
          });
          const page = normalizePageResult<QualityTaskListView>(pagePayload, pageNo, pageSize);
          items.push(...page.items);
        }
        return items;
      },
      listOnline() {
        return request<QualityTaskListView[]>({ url: "/quality-tasks/online", method: "GET" });
      },
      options() {
        return request<QualityTaskOptionView[]>({ url: "/quality-tasks/options", method: "GET" });
      },
      workflowOptions(params: {
        pageNo?: number;
        pageSize?: number;
        keyword?: string;
        runtimeClusterId: EntityId;
      }) {
        return request<unknown>({ url: "/quality-tasks/workflow-options", method: "GET", params }).then((payload) =>
          normalizePageResult<QualityTaskWorkflowOptionView>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      get(id: EntityId) {
        return request<QualityTaskDefinitionView>({ url: `/quality-tasks/${id}`, method: "GET" });
      },
      save(payload: QualityTaskSaveRequest) {
        return request<QualityTaskDefinitionView>({ url: "/quality-tasks", method: "POST", data: payload });
      },
      preview(payload: QualityTaskSaveRequest) {
        return request<QualityTaskPreviewView>({ url: "/quality-tasks/preview", method: "POST", data: payload });
      },
      validate(payload: QualityTaskSaveRequest) {
        return request<QualityTaskValidationView>({ url: "/quality-tasks/validate", method: "POST", data: payload });
      },
      publish(id: EntityId) {
        return request<QualityTaskDefinitionView>({ url: `/quality-tasks/${id}/online`, method: "POST" });
      },
      saveSchedule(id: EntityId, payload: CollectionTaskScheduleDefinition) {
        return request<QualityTaskDefinitionView>({ url: `/quality-tasks/${id}/schedule`, method: "POST", data: payload });
      },
      trigger(id: EntityId, runtimeClusterId?: EntityId | null) {
        return request<void>({
          url: `/quality-tasks/${id}/trigger`,
          method: "POST",
          data: runtimeClusterId == null ? undefined : { runtimeClusterId },
        });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/quality-tasks/${id}`, method: "DELETE" });
      },
    },
    qualityMetrics: {
      options(config?: StudioRequestConfig) {
        return request<QualityMetricOptionsView>({ ...config, url: "/quality-metrics/options", method: "GET" });
      },
      modelOptions(params?: { datasourceId?: EntityId; keyword?: string; pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
        return requestPage<RunMetricFilterOption>(
          { ...config, url: "/quality-metrics/model-options", method: "GET", params },
          params,
        );
      },
      assigneeOptions(config?: StudioRequestConfig) {
        return request<QualityIssueAssigneeOptionView[]>({ ...config, url: "/quality-metrics/assignee-options", method: "GET" });
      },
      queryDashboard(payload?: QualityMetricDashboardQueryRequest, config?: StudioRequestConfig) {
        return request<QualityMetricDashboardView>({ ...config, url: "/quality-metrics/dashboard/query", method: "POST", data: payload });
      },
      queryAssets(payload?: QualityAssetQueryRequest, config?: StudioRequestConfig) {
        return request<QualityAssetRiskView[]>({ ...config, url: "/quality-metrics/assets/query", method: "POST", data: payload });
      },
      queryAssetsPage(payload?: QualityAssetQueryRequest, config?: StudioRequestConfig) {
        return requestPage<QualityAssetRiskView>(
          { ...config, url: "/quality-metrics/assets/page", method: "POST", data: payload },
          payload,
        );
      },
      getAsset(assetId: string, params?: { startTime?: string; endTime?: string }) {
        return request<QualityAssetDetailView>({ url: `/quality-metrics/assets/${assetId}`, method: "GET", params });
      },
      queryIssues(payload?: QualityIssueQueryRequest, config?: StudioRequestConfig) {
        return request<QualityIssueView[]>({ ...config, url: "/quality-metrics/issues/query", method: "POST", data: payload });
      },
      queryIssuesPage(payload?: QualityIssueQueryRequest, config?: StudioRequestConfig) {
        return requestPage<QualityIssueView>(
          { ...config, url: "/quality-metrics/issues/page", method: "POST", data: payload },
          payload,
        );
      },
      getIssue(id: EntityId) {
        return request<QualityIssueDetailView>({ url: `/quality-metrics/issues/${id}`, method: "GET" });
      },
      assignIssue(id: EntityId, payload?: QualityIssueAssignRequest) {
        return request<QualityIssueDetailView>({ url: `/quality-metrics/issues/${id}/assign`, method: "POST", data: payload });
      },
      updateIssueStatus(id: EntityId, payload: QualityIssueStatusRequest) {
        return request<QualityIssueDetailView>({ url: `/quality-metrics/issues/${id}/status`, method: "POST", data: payload });
      },
      updateIssueSeverity(id: EntityId, payload: QualityIssueSeverityRequest) {
        return request<QualityIssueDetailView>({ url: `/quality-metrics/issues/${id}/severity`, method: "POST", data: payload });
      },
      addIssueComment(id: EntityId, payload: QualityIssueCommentRequest) {
        return request<QualityIssueDetailView>({ url: `/quality-metrics/issues/${id}/comment`, method: "POST", data: payload });
      },
    },
    environmentDependencies: {
      queryPage(params?: { pageNum?: number; pageSize?: number; keyword?: string; enabled?: boolean }) {
        return requestPage<EnvironmentDependencyListView>(
          { url: "/environment-dependencies/queryPage", method: "POST", params },
          { pageNo: params?.pageNum, pageSize: params?.pageSize },
        );
      },
      options(params?: { enabledOnly?: boolean }) {
        return request<EnvironmentDependencyOption[]>({ url: "/environment-dependencies/options", method: "GET", params });
      },
      get(id: EntityId) {
        return request<EnvironmentDependency>({ url: `/environment-dependencies/${id}`, method: "GET" });
      },
      saveOrUpdateCheck(payload: EnvironmentDependencySaveRequest) {
        return request<EnvironmentDependency>({ url: "/environment-dependencies/saveOrUpdateCheck", method: "POST", data: payload });
      },
      saveOrUpdateCheckForm(payload: FormData) {
        return request<EnvironmentDependency>({ url: "/environment-dependencies/saveOrUpdateCheck", method: "POST", data: payload });
      },
      async downloadFile(dependencyId: EntityId, fileId: EntityId) {
        const response = await instance.request<Blob>({
          url: `/environment-dependencies/${dependencyId}/files/${fileId}/download`,
          method: "GET",
          responseType: "blob",
        });
        return response.data;
      },
      deleteFile(dependencyId: EntityId, fileId: EntityId) {
        return request<void>({ url: `/environment-dependencies/${dependencyId}/files/${fileId}`, method: "DELETE" });
      },
      enable(id: EntityId) {
        return request<EnvironmentDependency>({ url: `/environment-dependencies/${id}/enable`, method: "POST" });
      },
      disable(id: EntityId) {
        return request<EnvironmentDependency>({ url: `/environment-dependencies/${id}/disable`, method: "POST" });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/environment-dependencies/${id}`, method: "DELETE" });
      },
      batchDelete(ids: EntityId[]) {
        return request<void>({ url: "/environment-dependencies/batch-delete", method: "POST", data: { ids } });
      },
    },
    artifactStores: {
      list(params?: { enabledOnly?: boolean }) {
        return request<ArtifactStore[]>({ url: "/artifact-stores", method: "GET", params });
      },
      get(id: EntityId) {
        return request<ArtifactStore>({ url: `/artifact-stores/${id}`, method: "GET" });
      },
      save(payload: ArtifactStoreSaveRequest) {
        return request<ArtifactStore>({ url: "/artifact-stores", method: "POST", data: payload });
      },
      test(id: EntityId) {
        return request<ArtifactStoreTestResult>({ url: `/artifact-stores/${id}/test`, method: "POST" });
      },
      enable(id: EntityId) {
        return request<ArtifactStore>({ url: `/artifact-stores/${id}/enable`, method: "POST" });
      },
      disable(id: EntityId) {
        return request<ArtifactStore>({ url: `/artifact-stores/${id}/disable`, method: "POST" });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/artifact-stores/${id}`, method: "DELETE" });
      },
    },
    pythonPackages: {
      queryPage(payload: PythonPackageQueryRequest) {
        return requestPage<PythonPackageSummary>(
          { url: "/python-packages/queryPage", method: "POST", params: payload },
          { pageNo: payload?.pageNum, pageSize: payload?.pageSize },
        );
      },
      versions(packageName: string) {
        return request<EnvironmentDependencyListView[]>({ url: `/python-packages/${encodeURIComponent(packageName)}/versions`, method: "GET" });
      },
      async exportRequirements(params?: { keyword?: string; enabled?: boolean }) {
        const response = await instance.request<Blob>({
          url: "/python-packages/export",
          method: "GET",
          params,
          responseType: "blob",
        });
        return response.data;
      },
      todayDownloadCount() {
        return request<number>({ url: "/python-packages/download-count/today", method: "GET" });
      },
    },
    scriptEnvironments: {
      queryPage(params?: { pageNum?: number; pageSize?: number; keyword?: string; enabled?: boolean }) {
        return requestPage<ScriptEnvironmentListView>(
          { url: "/script-environments/queryPage", method: "POST", params },
          { pageNo: params?.pageNum, pageSize: params?.pageSize },
        );
      },
      options(params?: { enabledOnly?: boolean }) {
        return request<ScriptEnvironmentOption[]>({ url: "/script-environments/options", method: "GET", params });
      },
      get(id: EntityId) {
        return request<ScriptEnvironment>({ url: `/script-environments/${id}`, method: "GET" });
      },
      saveOrUpdateCheck(payload: ScriptEnvironmentSaveRequest) {
        return request<ScriptEnvironment>({ url: "/script-environments/saveOrUpdateCheck", method: "POST", data: payload });
      },
      enable(id: EntityId) {
        return request<ScriptEnvironment>({ url: `/script-environments/${id}/enable`, method: "POST" });
      },
      disable(id: EntityId) {
        return request<ScriptEnvironment>({ url: `/script-environments/${id}/disable`, method: "POST" });
      },
      refresh(id: EntityId) {
        return request<ScriptEnvironment>({ url: `/script-environments/${id}/refresh`, method: "POST" });
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
      listScripts(scriptType?: ScriptType, config?: StudioRequestConfig) {
        return request<DataDevelopmentScriptListView[]>({
          ...config,
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
      listSqlDatasources(runtimeClusterId: EntityId) {
        return request<DataSourceDefinition[]>({
          url: "/data-development/datasources",
          method: "GET",
          params: { runtimeClusterId },
        });
      },
      listSqlDatasourceOptions(runtimeClusterId: EntityId) {
        return request<DataSourceOptionView[]>({
          url: "/data-development/datasource-options",
          method: "GET",
          params: { runtimeClusterId },
        });
      },
      listSqlDatasourceTypes() {
        return request<string[]>({ url: "/data-development/datasource-types", method: "GET" });
      },
      executeSql(payload: SqlExecutionRequest) {
        return request<SqlExecutionResult>({
          url: "/data-development/sql/execute",
          method: "POST",
          data: payload,
          timeout: DATA_DEVELOPMENT_EXECUTION_TIMEOUT_MS,
        });
      },
      executeScript(payload: DataScriptExecutionRequest) {
        return request<DataScriptExecutionResult>({
          url: "/data-development/scripts/execute",
          method: "POST",
          data: payload,
          timeout: DATA_DEVELOPMENT_EXECUTION_TIMEOUT_MS,
        });
      },
      executeSavedScript(id: EntityId, payload?: SavedDataScriptExecutionRequest) {
        return request<DataScriptExecutionResult>({
          url: `/data-development/scripts/${id}/execute`,
          method: "POST",
          data: payload ?? {},
          timeout: DATA_DEVELOPMENT_EXECUTION_TIMEOUT_MS,
        });
      },
      javaImportHints(params: { runtimeClusterId: EntityId; environmentId?: EntityId; keyword?: string; limit?: number }, config?: StudioRequestConfig) {
        return request<JavaImportHintResponse>({ ...config, url: "/data-development/java/import-hints", method: "GET", params });
      },
      javaMemberHints(params: { runtimeClusterId: EntityId; environmentId?: EntityId; className: string; keyword?: string; staticOnly?: boolean; limit?: number }, config?: StudioRequestConfig) {
        return request<JavaMemberHintResponse>({ ...config, url: "/data-development/java/member-hints", method: "GET", params });
      },
    },
    schedules: {
      list() {
        return request<WorkflowListView[]>({ url: "/schedules", method: "GET" });
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
      listPage(params?: RunRecordPageQuery) {
        return request<RunRecordPageResponse>({
          url: "/runs/page",
          method: "GET",
          params,
        });
      },
      get(id: EntityId) {
        return request<RunRecord>({ url: `/runs/${id}`, method: "GET" });
      },
      getSummary(id: EntityId) {
        return request<RunRecordListView>({ url: `/runs/${id}/summary`, method: "GET" });
      },
      getLog(id: EntityId, params?: RunLogQuery) {
        return request<RunLogView>({ url: `/runs/${id}/log`, method: "GET", params });
      },
      downloadLog(id: EntityId) {
        return request<RunLogView>({ url: `/runs/${id}/log/download`, method: "GET" });
      },
    },
    invocationLogs: {
      get(domain: string, accessLogId: EntityId, params?: RunLogQuery) {
        return request<RunLogView>({
          url: `/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}`,
          method: "GET",
          params,
        });
      },
      download(domain: string, accessLogId: EntityId) {
        return request<RunLogView>({
          url: `/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/download`,
          method: "GET",
        });
      },
      getSection(domain: string, accessLogId: EntityId, sectionKey: string, params?: RunLogQuery) {
        return request<RunLogView>({
          url: `/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/sections/${encodeURIComponent(sectionKey)}`,
          method: "GET",
          params,
        });
      },
      downloadSection(domain: string, accessLogId: EntityId, sectionKey: string) {
        return request<RunLogView>({
          url: `/invocation-logs/${encodeURIComponent(domain)}/${accessLogId}/sections/${encodeURIComponent(sectionKey)}/download`,
          method: "GET",
        });
      },
    },
    runMetrics: {
      options() {
        return request<RunMetricOptionsView>({ url: "/run-metrics/options", method: "GET" });
      },
      query(payload?: RunMetricDashboardQueryRequest) {
        return request<RunMetricDashboardResponse>({ url: "/run-metrics/query", method: "POST", data: payload });
      },
    },
    opsCenter: {
      options(config?: StudioRequestConfig) {
        return request<OpsCenterOptionsView>({ ...config, url: "/ops-center/options", method: "GET" });
      },
      queryOverview(payload?: OpsCenterQueryRequest, config?: StudioRequestConfig) {
        return request<OpsCenterOverviewView>({ ...config, url: "/ops-center/overview/query", method: "POST", data: payload });
      },
      queryRuns(payload?: OpsCenterQueryRequest, config?: StudioRequestConfig) {
        return requestPage<OpsCenterRunIncidentView>({ ...config, url: "/ops-center/runs/query", method: "POST", data: payload }, payload);
      },
      queryQueue(payload?: OpsCenterQueryRequest, config?: StudioRequestConfig) {
        return requestPage<OpsCenterQueueItemView>({ ...config, url: "/ops-center/queue/query", method: "POST", data: payload }, payload);
      },
      queryWorkers(payload?: OpsCenterQueryRequest, config?: StudioRequestConfig) {
        return requestPage<OpsCenterWorkerGroupView>({ ...config, url: "/ops-center/workers/query", method: "POST", data: payload }, payload);
      },
      queryServiceEvents(payload?: OpsCenterQueryRequest, config?: StudioRequestConfig) {
        return requestPage<OpsCenterServiceEventView>({ ...config, url: "/ops-center/service-events/query", method: "POST", data: payload }, payload);
      },
      queryIngestionEvents(payload?: OpsCenterQueryRequest, config?: StudioRequestConfig) {
        return requestPage<OpsCenterServiceEventView>({ ...config, url: "/ops-center/ingestion-events/query", method: "POST", data: payload }, payload);
      },
      queryProtocolConversionEvents(payload?: OpsCenterQueryRequest, config?: StudioRequestConfig) {
        return requestPage<OpsCenterServiceEventView>({ ...config, url: "/ops-center/protocol-conversion-events/query", method: "POST", data: payload }, payload);
      },
      queryLogEvents(payload?: OpsCenterQueryRequest, config?: StudioRequestConfig) {
        return requestPage<OpsCenterLogEventView>({ ...config, url: "/ops-center/log-events/query", method: "POST", data: payload }, payload);
      },
    },
    alerts: {
      options() {
        return request<AlertOptionsView>({ url: "/alerts/options", method: "GET" });
      },
      subjects(params: { subjectType: string; keyword?: string; pageNo?: number; pageSize?: number }) {
        return requestPage<AlertSelectOptionView>({ url: "/alerts/subjects", method: "GET", params }, params);
      },
      recipientOptions(params?: { keyword?: string; pageNo?: number; pageSize?: number }) {
        return requestPage<AlertSelectOptionView>({ url: "/alerts/recipient-options", method: "GET", params }, params);
      },
      summary() {
        return request<AlertSummaryView>({ url: "/alerts/summary", method: "GET" });
      },
      tenantSummary(payload?: { keyword?: string; pageNo?: number; pageSize?: number }) {
        return requestPage<AlertTenantProjectSummaryView>({ url: "/alerts/tenant-summary/query", method: "POST", data: payload }, payload);
      },
      queryRules(payload?: AlertRuleQueryRequest) {
        return requestPage<AlertRuleView>({ url: "/alerts/rules/query", method: "POST", data: payload }, payload);
      },
      getRule(id: EntityId) {
        return request<AlertRuleView>({ url: `/alerts/rules/${id}`, method: "GET" });
      },
      saveRule(payload: AlertRuleSaveRequest) {
        return request<AlertRuleView>({ url: "/alerts/rules", method: "POST", data: payload });
      },
      deleteRule(id: EntityId) {
        return request<void>({ url: `/alerts/rules/${id}`, method: "DELETE" });
      },
      enableRule(id: EntityId) {
        return request<AlertRuleView>({ url: `/alerts/rules/${id}/enable`, method: "POST" });
      },
      disableRule(id: EntityId) {
        return request<AlertRuleView>({ url: `/alerts/rules/${id}/disable`, method: "POST" });
      },
      testRule(id: EntityId) {
        return request<AlertEventView>({ url: `/alerts/rules/${id}/test`, method: "POST" });
      },
      queryIncidents(payload?: AlertIncidentQueryRequest) {
        return requestPage<AlertIncidentView>({ url: "/alerts/incidents/query", method: "POST", data: payload }, payload);
      },
      getIncident(id: EntityId) {
        return request<AlertIncidentView>({ url: `/alerts/incidents/${id}`, method: "GET" });
      },
      incidentEvents(id: EntityId, params?: { pageNo?: number; pageSize?: number }) {
        return requestPage<AlertEventView>({ url: `/alerts/incidents/${id}/events`, method: "GET", params }, params);
      },
      acknowledgeIncident(id: EntityId, payload?: { comment?: string }) {
        return request<AlertIncidentView>({ url: `/alerts/incidents/${id}/acknowledge`, method: "POST", data: payload });
      },
      closeIncident(id: EntityId, payload?: { comment?: string }) {
        return request<AlertIncidentView>({ url: `/alerts/incidents/${id}/close`, method: "POST", data: payload });
      },
      queryChannels(payload?: AlertChannelQueryRequest) {
        return requestPage<AlertChannelView>({ url: "/alerts/channels/query", method: "POST", data: payload }, payload);
      },
      getChannel(id: EntityId) {
        return request<AlertChannelView>({ url: `/alerts/channels/${id}`, method: "GET" });
      },
      saveChannel(payload: AlertChannelSaveRequest) {
        return request<AlertChannelView>({ url: "/alerts/channels", method: "POST", data: payload });
      },
      deleteChannel(id: EntityId) {
        return request<void>({ url: `/alerts/channels/${id}`, method: "DELETE" });
      },
      enableChannel(id: EntityId) {
        return request<AlertChannelView>({ url: `/alerts/channels/${id}/enable`, method: "POST" });
      },
      disableChannel(id: EntityId) {
        return request<AlertChannelView>({ url: `/alerts/channels/${id}/disable`, method: "POST" });
      },
      testChannel(id: EntityId) {
        return request<AlertEventView>({ url: `/alerts/channels/${id}/test`, method: "POST" });
      },
      queryDeliveries(payload?: AlertDeliveryQueryRequest) {
        return requestPage<AlertDeliveryView>({ url: "/alerts/deliveries/query", method: "POST", data: payload }, payload);
      },
      retryDelivery(id: EntityId) {
        return request<AlertDeliveryView>({ url: `/alerts/deliveries/${id}/retry`, method: "POST" });
      },
    },
    elink: {
      queryUsers(params?: { keyword?: string; pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
        return requestPage<ElinkUserOptionView>({ ...config, url: "/elink/users", method: "GET", params }, params);
      },
      queryGroups(params?: { keyword?: string; pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
        return requestPage<ElinkGroupOptionView>({ ...config, url: "/elink/groups", method: "GET", params }, params);
      },
    },
    notifications: {
      list(params?: NotificationQueryRequest) {
        return requestPage<NotificationView>({ url: "/notifications", method: "GET", params }, params);
      },
      snapshot() {
        return request<NotificationSnapshotView>({ url: "/notifications/snapshot", method: "GET" });
      },
      unreadCount() {
        return request<number>({ url: "/notifications/unread-count", method: "GET" });
      },
      markRead(id: EntityId) {
        return request<void>({ url: `/notifications/${id}/read`, method: "POST" });
      },
      markAllRead() {
        return request<void>({ url: "/notifications/read-all", method: "POST" });
      },
    },
    follows: {
      status(targetType: string, targetId: EntityId) {
        return request<FollowStatusView>({
          url: "/follows/status",
          method: "GET",
          params: { targetType, targetId },
        });
      },
      follow(payload: FollowRequest) {
        return request<FollowStatusView>({ url: "/follows", method: "POST", data: payload });
      },
      unfollow(targetType: string, targetId: EntityId) {
        return request<void>({
          url: "/follows",
          method: "DELETE",
          params: { targetType, targetId },
        });
      },
    },
    workflowRuns: {
      list(params?: WorkflowRunListQuery) {
        return requestPage<WorkflowRunSummary>({
          url: "/workflow-runs",
          method: "GET",
          params,
        }, params);
      },
      get(workflowRunId: EntityId) {
        return request<WorkflowRunDetail>({ url: `/workflow-runs/${workflowRunId}`, method: "GET" });
      },
      terminate(workflowRunId: EntityId) {
        return request<WorkflowRunDetail>({ url: `/workflow-runs/${workflowRunId}/terminate`, method: "POST" });
      },
    },
    users: {
      list(config?: StudioRequestConfig) {
        return request<StudioUserListView[]>({ ...config, url: "/users", method: "GET" });
      },
      options(config?: StudioRequestConfig) {
        return request<StudioUserOption[]>({ ...config, url: "/users/options", method: "GET" });
      },
      listPage(params?: { pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
        return requestPage<StudioUserListView>({ ...config, url: "/users/page", method: "GET", params }, params);
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
        list(config?: StudioRequestConfig) {
          return request<SystemTenant[]>({ ...config, url: "/system/tenants", method: "GET" });
        },
        listPage(params?: { pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
          return requestPage<SystemTenant>({ ...config, url: "/system/tenants/page", method: "GET", params }, params);
        },
        save(payload: Partial<SystemTenant>) {
          return request<SystemTenant>({ url: "/system/tenants", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/tenants/${id}`, method: "DELETE" });
        },
      },
      projects: {
        list(config?: StudioRequestConfig) {
          return request<SystemProject[]>({ ...config, url: "/system/projects", method: "GET" });
        },
        options(config?: StudioRequestConfig) {
          return request<SystemProjectOption[]>({ ...config, url: "/system/project-options", method: "GET" });
        },
        listPage(params?: { pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
          return requestPage<SystemProject>({ ...config, url: "/system/projects/page", method: "GET", params }, params);
        },
        save(payload: Partial<SystemProject>) {
          return request<SystemProject>({ url: "/system/projects", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/projects/${id}`, method: "DELETE" });
        },
      },
      tenantMembers: {
        list(config?: StudioRequestConfig) {
          return request<SystemTenantMember[]>({ ...config, url: "/system/tenant-members", method: "GET" });
        },
        listPage(params?: { pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
          return requestPage<SystemTenantMember>({ ...config, url: "/system/tenant-members/page", method: "GET", params }, params);
        },
        save(payload: Partial<SystemTenantMember>) {
          return request<SystemTenantMember>({ url: "/system/tenant-members", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/tenant-members/${id}`, method: "DELETE" });
        },
      },
      projectMembers: {
        list(projectId?: EntityId, config?: StudioRequestConfig) {
          return request<SystemProjectMember[]>({
            ...config,
            url: "/system/project-members",
            method: "GET",
            params: projectId == null ? undefined : { projectId },
          });
        },
        listPage(params?: { projectId?: EntityId; pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
          return requestPage<SystemProjectMember>({ ...config, url: "/system/project-members/page", method: "GET", params }, params);
        },
        save(payload: Partial<SystemProjectMember>) {
          return request<SystemProjectMember>({ url: "/system/project-members", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/project-members/${id}`, method: "DELETE" });
        },
      },
      projectMemberRequests: {
        list(projectId?: EntityId, config?: StudioRequestConfig) {
          return request<SystemProjectMemberRequest[]>({
            ...config,
            url: "/system/project-member-requests",
            method: "GET",
            params: projectId == null ? undefined : { projectId },
          });
        },
        listPage(params?: { projectId?: EntityId; pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
          return requestPage<SystemProjectMemberRequest>({ ...config, url: "/system/project-member-requests/page", method: "GET", params }, params);
        },
        save(payload: Partial<SystemProjectMemberRequest>) {
          return request<SystemProjectMemberRequest>({ url: "/system/project-member-requests", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/project-member-requests/${id}`, method: "DELETE" });
        },
      },
      projectWorkers: {
        list(projectId?: EntityId, config?: StudioRequestConfig) {
          return request<SystemProjectWorker[]>({
            ...config,
            url: "/system/project-workers",
            method: "GET",
            params: projectId == null ? undefined : { projectId },
          });
        },
        listPage(params?: { projectId?: EntityId; pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
          return requestPage<SystemProjectWorker>({ ...config, url: "/system/project-workers/page", method: "GET", params }, params);
        },
        options(projectId?: EntityId, config?: StudioRequestConfig) {
          return request<SystemProjectWorkerOption[]>({
            ...config,
            url: "/system/project-worker-options",
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
        list(params?: { resourceType?: string; projectId?: EntityId }, config?: StudioRequestConfig) {
          return request<ResourceShare[]>({ ...config, url: "/system/resource-shares", method: "GET", params });
        },
        listPage(params?: { resourceType?: string; projectId?: EntityId; pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
          return requestPage<ResourceShare>({ ...config, url: "/system/resource-shares/page", method: "GET", params }, params);
        },
        options(params: { resourceType: string; projectId?: EntityId }, config?: StudioRequestConfig) {
          return request<ShareResourceOption[]>({ ...config, url: "/system/resource-share-options", method: "GET", params });
        },
        save(payload: Partial<ResourceShare>) {
          return request<ResourceShare>({ url: "/system/resource-shares", method: "POST", data: payload });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/resource-shares/${id}`, method: "DELETE" });
        },
      },
      userRegistrationRequests: {
        list(config?: StudioRequestConfig) {
          return request<UserRegistrationRequestView[]>({ ...config, url: "/system/user-registration-requests", method: "GET" });
        },
        listPage(params?: { pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
          return requestPage<UserRegistrationRequestView>({ ...config, url: "/system/user-registration-requests/page", method: "GET", params }, params);
        },
        approve(id: EntityId, payload?: UserRegistrationRequestReviewRequest) {
          return request<UserRegistrationRequestView>({
            url: `/system/user-registration-requests/${id}/approve`,
            method: "POST",
            data: payload,
          });
        },
        reject(id: EntityId, payload?: UserRegistrationRequestReviewRequest) {
          return request<UserRegistrationRequestView>({
            url: `/system/user-registration-requests/${id}/reject`,
            method: "POST",
            data: payload,
          });
        },
        delete(id: EntityId) {
          return request<void>({ url: `/system/user-registration-requests/${id}`, method: "DELETE" });
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
