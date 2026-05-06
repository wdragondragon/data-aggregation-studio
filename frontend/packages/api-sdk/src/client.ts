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
  DatasourceTypeCapabilityView,
  DataSourceDefinition,
  DataServiceDefinitionView,
  DataServiceDebugRequest,
  DataServiceInvokeResponse,
  DataServiceAccessLogView,
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
  FieldMappingRuleView,
  FollowRequest,
  FollowStatusView,
  JobContainerConfig,
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
  PermissionEntity,
  PageResult,
  PluginRuntimeOptionSchemaView,
  QualityRuleParseRequest,
  QualityRuleParseResult,
  QualityRuleSaveRequest,
  QualityRuleValidationResult,
  QualityRuleValidateRequest,
  QualityRuleView,
  QualityTaskDefinitionView,
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
  RunMetricOptionsView,
  RunListQuery,
  RoleEntity,
  RunListResponse,
  RunLogQuery,
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
    catalog: {
      capabilities() {
        return request<CapabilityMatrix>({ url: "/catalog/capabilities", method: "GET" });
      },
      datasourceTypes() {
        return request<DatasourceTypeCapabilityView[]>({ url: "/catalog/datasource-types", method: "GET" });
      },
      runtimeOptionSchema(params: { role: string; datasourceType: string }) {
        return request<PluginRuntimeOptionSchemaView>({ url: "/catalog/runtime-option-schemas", method: "GET", params });
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
        return request<PageResult<FieldMappingRuleView>>({ url: "/field-mapping-rules", method: "GET", params });
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
        return request<PageResult<QualityRuleView>>({ url: "/quality-rules", method: "GET", params });
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
      list() {
        return request<MetadataSchemaDefinition[]>({ url: "/meta-schemas", method: "GET" });
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
      }) {
        return request<PageResult<DataServiceDefinitionView>>({ url: "/data-services", method: "GET", params });
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
      enableSubscription(id: EntityId, subscriptionId: EntityId) {
        return request<DataServiceSubscriptionView>({
          url: `/data-services/${id}/subscriptions/${subscriptionId}/enable`,
          method: "POST",
        });
      },
    },
    dataServiceMetrics: {
      options() {
        return request<DataServiceMetricOptionsView>({ url: "/data-service-metrics/options", method: "GET" });
      },
      queryDashboard(payload?: DataServiceMetricQueryRequest) {
        return request<DataServiceMetricDashboardView>({ url: "/data-service-metrics/dashboard/query", method: "POST", data: payload });
      },
      queryApiStats(payload?: DataServiceMetricQueryRequest) {
        return request<PageResult<DataServiceApiMetricView>>({ url: "/data-service-metrics/api-stats/query", method: "POST", data: payload });
      },
      queryAccessLogs(payload?: DataServiceMetricQueryRequest) {
        return request<PageResult<DataServiceAccessLogView>>({ url: "/data-service-metrics/access-logs/query", method: "POST", data: payload });
      },
    },
    models: {
      listPage(params?: {
        datasourceType?: string;
        pageNo?: number;
        pageSize?: number;
      }) {
        return request<unknown>({ url: "/models", method: "GET", params }).then((payload) =>
          normalizePageResult<DataModelDefinition>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      async list(params?: {
        datasourceType?: string;
        pageNo?: number;
        pageSize?: number;
      }) {
        const result = await request<PageResult<DataModelDefinition>>({
          url: "/models",
          method: "GET",
          params: {
            datasourceType: params?.datasourceType,
            pageNo: params?.pageNo ?? 1,
            pageSize: params?.pageSize ?? 5000,
          },
        });
        return result.items;
      },
      listByDatasourcePage(datasourceId: EntityId, params?: {
        pageNo?: number;
        pageSize?: number;
      }) {
        return request<unknown>({
          url: `/models/datasource/${datasourceId}`,
          method: "GET",
          params,
        }).then((payload) =>
          normalizePageResult<DataModelDefinition>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
        );
      },
      async listByDatasource(datasourceId: EntityId, params?: {
        pageNo?: number;
        pageSize?: number;
      }) {
        const result = await request<PageResult<DataModelDefinition>>({
          url: `/models/datasource/${datasourceId}`,
          method: "GET",
          params: {
            pageNo: params?.pageNo ?? 1,
            pageSize: params?.pageSize ?? 5000,
          },
        });
        return result.items;
      },
      get(modelId: EntityId) {
        return request<DataModelDefinition>({ url: `/models/${modelId}`, method: "GET" });
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
        return request<PageResult<ModelSyncTaskView>>({ url: "/model-sync-tasks", method: "GET", params });
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
        return request<PageResult<ModelSyncTaskItemView>>({
          url: `/model-sync-tasks/${taskId}/items`,
          method: "GET",
          params,
        });
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
          normalizePageResult<QualityTaskDefinitionView>(payload, params?.pageNo ?? 1, params?.pageSize ?? 20),
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
        const firstPage = normalizePageResult<QualityTaskDefinitionView>(firstPagePayload, 1, pageSize);
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
          const page = normalizePageResult<QualityTaskDefinitionView>(pagePayload, pageNo, pageSize);
          items.push(...page.items);
        }
        return items;
      },
      listOnline() {
        return request<QualityTaskDefinitionView[]>({ url: "/quality-tasks/online", method: "GET" });
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
      options() {
        return request<QualityMetricOptionsView>({ url: "/quality-metrics/options", method: "GET" });
      },
      queryDashboard(payload?: QualityMetricDashboardQueryRequest) {
        return request<QualityMetricDashboardView>({ url: "/quality-metrics/dashboard/query", method: "POST", data: payload });
      },
      queryAssets(payload?: QualityAssetQueryRequest) {
        return request<QualityAssetRiskView[]>({ url: "/quality-metrics/assets/query", method: "POST", data: payload });
      },
      getAsset(assetId: string, params?: { startTime?: string; endTime?: string }) {
        return request<QualityAssetDetailView>({ url: `/quality-metrics/assets/${assetId}`, method: "GET", params });
      },
      queryIssues(payload?: QualityIssueQueryRequest) {
        return request<QualityIssueView[]>({ url: "/quality-metrics/issues/query", method: "POST", data: payload });
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
      listSqlDatasourceTypes() {
        return request<string[]>({ url: "/data-development/datasource-types", method: "GET" });
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
      getLog(id: EntityId, params?: RunLogQuery) {
        return request<RunLogView>({ url: `/runs/${id}/log`, method: "GET", params });
      },
      downloadLog(id: EntityId) {
        return request<RunLogView>({ url: `/runs/${id}/log/download`, method: "GET" });
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
    notifications: {
      list(params?: NotificationQueryRequest) {
        return request<PageResult<NotificationView>>({ url: "/notifications", method: "GET", params });
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
      userRegistrationRequests: {
        list() {
          return request<UserRegistrationRequestView[]>({ url: "/system/user-registration-requests", method: "GET" });
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
