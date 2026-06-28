import axios, { type AxiosInstance, type AxiosRequestConfig } from "axios";
import { beginStudioApiRequest, endStudioApiRequest } from "./loading";
import type {
  AuthProfile,
  CapabilityMatrix,
  CollectionTaskDefinitionView,
  CollectionTaskListView,
  CollectionTaskListQuery,
  CollectionTaskOptionView,
  CollectionTaskSaveRequest,
  CollectionTaskScheduleDefinition,
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
  DatasourceConnectionTestRecordView,
  DatasourceTypeCapabilityView,
  DataSourceDefinition,
  DataSourceListView,
  DataSourceOptionView,
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
  EntityId,
  ExportProjectBundle,
  FieldMappingRuleSaveRequest,
  FieldMappingRuleListView,
  FieldMappingRuleOptionView,
  FieldMappingRuleView,
  FollowRequest,
  FollowStatusView,
  JobContainerConfig,
  JavaImportHintResponse,
  JavaMemberHintResponse,
  LoginRequest,
  LoginResponse,
  MetadataSchemaDefinition,
  ModelSyncRequest,
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
  Result,
  RunMetricDashboardQueryRequest,
  RunMetricDashboardResponse,
  RunMetricFilterOption,
  RunMetricOptionsView,
  RunListQuery,
  RunRecordPageQuery,
  RunRecordPageResponse,
  RoleEntity,
  RunListResponse,
  RunLogQuery,
  RunLogView,
  RunRecord,
  SavedDataScriptExecutionRequest,
  ScriptType,
  ScriptEnvironment,
  ScriptEnvironmentListView,
  ScriptEnvironmentOption,
  ScriptEnvironmentSaveRequest,
  SqlExecutionRequest,
  SqlExecutionResult,
  SystemProject,
  SystemProjectMember,
  SystemProjectMemberRequest,
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

  return {
    auth: {
      login(payload: LoginRequest) {
        return request<LoginResponse>({ url: "/auth/login", method: "POST", data: payload });
      },
      gatewayExchange() {
        return request<LoginResponse>({ url: "/auth/gateway/exchange", method: "POST" });
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
      disable(id: EntityId) {
        return request<QualityRuleView>({ url: `/quality-rules/${id}/disable`, method: "POST" });
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
      syncAllTechnical() {
        return request<MetadataSchemaDefinition[]>({ url: "/meta-schemas/technical/sync-all", method: "POST" });
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
      options(config?: StudioRequestConfig) {
        return request<DataSourceOptionView[]>({ ...config, url: "/datasources/options", method: "GET" });
      },
      listPage(params?: { pageNo?: number; pageSize?: number }, config?: StudioRequestConfig) {
        return requestPage<DataSourceListView>({ ...config, url: "/datasources/page", method: "GET", params }, params);
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
      connectionHistory(id: EntityId, params?: { days?: number; limit?: number }) {
        return request<DatasourceConnectionTestRecordView[]>({
          url: `/datasources/${id}/connection-history`,
          method: "GET",
          params,
        });
      },
      discover(id: EntityId, keywordOrOptions?: string | { keyword?: string; pageNo?: number; pageSize?: number }) {
        const options = typeof keywordOrOptions === "string"
          ? { keyword: keywordOrOptions }
          : (keywordOrOptions ?? {});
        const keyword = options.keyword?.trim();
        return request<ModelDiscoveryResult>({
          url: `/datasources/${id}/discover`,
          method: "POST",
          params: {
            ...(keyword ? { keyword } : {}),
            ...(options.pageNo ? { pageNo: options.pageNo } : {}),
            ...(options.pageSize ? { pageSize: options.pageSize } : {}),
          },
        });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/datasources/${id}`, method: "DELETE" });
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
      offline(id: EntityId) {
        return request<DataServiceDefinitionView>({ url: `/data-services/${id}/offline`, method: "POST" });
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
      offline(id: EntityId) {
        return request<DataIngestionServiceView>({ url: `/data-ingestion-services/${id}/offline`, method: "POST" });
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
      offline(id: EntityId) {
        return request<ProtocolConversionServiceView>({ url: `/protocol-conversions/${id}/offline`, method: "POST" });
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
      trigger(id: EntityId) {
        return request<WorkflowDefinitionView>({ url: `/workflows/${id}/trigger`, method: "POST" });
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
      trigger(id: EntityId) {
        return request<CollectionTaskDefinitionView>({ url: `/collection-tasks/${id}/trigger`, method: "POST" });
      },
      delete(id: EntityId) {
        return request<void>({ url: `/collection-tasks/${id}`, method: "DELETE" });
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
      trigger(id: EntityId) {
        return request<QualityTaskDefinitionView>({ url: `/quality-tasks/${id}/trigger`, method: "POST" });
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
      queryDashboard(payload?: QualityMetricDashboardQueryRequest, config?: StudioRequestConfig) {
        return request<QualityMetricDashboardView>({ ...config, url: "/quality-metrics/dashboard/query", method: "POST", data: payload });
      },
      queryAssets(payload?: QualityAssetQueryRequest, config?: StudioRequestConfig) {
        return request<QualityAssetRiskView[]>({ ...config, url: "/quality-metrics/assets/query", method: "POST", data: payload });
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
      listSqlDatasources() {
        return request<DataSourceDefinition[]>({ url: "/data-development/datasources", method: "GET" });
      },
      listSqlDatasourceOptions() {
        return request<DataSourceOptionView[]>({ url: "/data-development/datasource-options", method: "GET" });
      },
      listSqlDatasourceTypes() {
        return request<string[]>({ url: "/data-development/datasource-types", method: "GET" });
      },
      executeSql(payload: SqlExecutionRequest) {
        return request<SqlExecutionResult>({ url: "/data-development/sql/execute", method: "POST", data: payload });
      },
      executeScript(payload: DataScriptExecutionRequest) {
        return request<DataScriptExecutionResult>({ url: "/data-development/scripts/execute", method: "POST", data: payload });
      },
      executeSavedScript(id: EntityId, payload?: SavedDataScriptExecutionRequest) {
        return request<DataScriptExecutionResult>({ url: `/data-development/scripts/${id}/execute`, method: "POST", data: payload ?? {} });
      },
      javaImportHints(params?: { environmentId?: EntityId; keyword?: string; limit?: number }, config?: StudioRequestConfig) {
        return request<JavaImportHintResponse>({ ...config, url: "/data-development/java/import-hints", method: "GET", params });
      },
      javaMemberHints(params: { environmentId?: EntityId; className: string; keyword?: string; staticOnly?: boolean; limit?: number }, config?: StudioRequestConfig) {
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
      queryLogEvents(payload?: OpsCenterQueryRequest, config?: StudioRequestConfig) {
        return requestPage<OpsCenterLogEventView>({ ...config, url: "/ops-center/log-events/query", method: "POST", data: payload }, payload);
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
        return request<NotificationView>({ url: `/notifications/${id}/read`, method: "POST" });
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
