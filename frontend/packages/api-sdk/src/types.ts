export interface Result<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  timestamp: string;
}

export interface PageResult<T> {
  pageNo: number;
  pageSize: number;
  total: number;
  items: T[];
}

export type EntityId = string | number;

export interface LoginRequest {
  username: string;
  password: string;
}

export interface UserRegistrationRequestCreateRequest {
  username: string;
  password: string;
  displayName?: string;
  reason?: string;
}

export interface UserRegistrationRequestReviewRequest {
  reviewComment?: string;
}

export interface WorkspaceAccessApplyRequest {
  projectId: EntityId;
  reason?: string;
}

export interface AuthTenant {
  tenantId: string;
  tenantCode?: string;
  tenantName: string;
  enabled?: boolean;
  roleCodes: string[];
}

export interface AuthProject {
  projectId: EntityId;
  tenantId: string;
  projectCode?: string;
  projectName: string;
  enabled?: boolean;
  defaultProject?: boolean;
  roleCodes: string[];
}

export interface AuthProfile {
  userId?: EntityId;
  username: string | null;
  displayName?: string | null;
  currentTenantId?: string | null;
  currentProjectId?: EntityId | null;
  systemRoleCodes: string[];
  effectiveRoleCodes: string[];
  tenants: AuthTenant[];
  projects: AuthProject[];
}

export interface WorkspaceAccessProjectView {
  projectId: EntityId;
  tenantId: string;
  tenantName?: string;
  projectCode?: string;
  projectName: string;
  description?: string;
  enabled?: boolean;
  pendingRequestId?: EntityId | null;
  pendingRequestStatus?: string | null;
}

export interface WorkspaceAccessTenantGroupView {
  tenantId: string;
  tenantCode?: string;
  tenantName: string;
  enabled?: boolean;
  projects: WorkspaceAccessProjectView[];
}

export interface WorkspaceAccessRequestView {
  requestId: EntityId;
  projectId: EntityId;
  tenantId: string;
  tenantName?: string;
  projectName?: string;
  requestType?: string;
  status?: string;
  reason?: string;
  reviewComment?: string;
  createdAt?: string;
  reviewedAt?: string | null;
}

export interface WorkspaceAccessOverviewView {
  tenantGroups: WorkspaceAccessTenantGroupView[];
  requests: WorkspaceAccessRequestView[];
}

export type FollowTargetType =
  | "MODEL_SYNC_TASK"
  | "COLLECTION_TASK"
  | "COLLECTION_TASK_RUN"
  | "WORKFLOW"
  | "WORKFLOW_RUN";

export interface LoginResponse extends AuthProfile {
  token: string;
}

export interface BaseRecord {
  id?: EntityId;
  tenantId?: string;
  projectId?: EntityId;
  deleted?: boolean | number;
  createdAt?: string;
  updatedAt?: string;
}

export type MetadataScope = "TECHNICAL" | "BUSINESS";
export type FieldValueType =
  | "STRING"
  | "BOOLEAN"
  | "INTEGER"
  | "LONG"
  | "DECIMAL"
  | "ARRAY"
  | "OBJECT"
  | "JSON";
export type FieldComponentType =
  | "INPUT"
  | "PASSWORD"
  | "NUMBER"
  | "TEXTAREA"
  | "SELECT"
  | "SWITCH"
  | "JSON_EDITOR"
  | "SQL_EDITOR"
  | "CODE_EDITOR"
  | "CRON";
export type SchemaStatus = "DRAFT" | "PUBLISHED";
export type ModelKind = "TABLE" | "VIEW" | "FILE" | "TOPIC" | "MEASUREMENT" | "DATASET";
export type NodeType = "COLLECTION_TASK" | "QUALITY_TASK" | "DATA_SCRIPT" | "ETL_SINGLE" | "FUSION" | "CONSISTENCY" | "HTTP" | "SHELL";
export type EdgeCondition = "ON_SUCCESS" | "ON_FAILURE" | "ALWAYS";
export type QueryOperator = "EQ" | "LIKE" | "IN" | "GT" | "GE" | "LT" | "LE" | "BETWEEN";
export type RowMatchMode = "SAME_ITEM" | "ANY_ITEM";
export type StatisticType = "COUNT_BY_VALUE" | "SUMMARY" | "COUNT_BY_BUCKET";
export type StatisticsChartType = "TREND" | "BAR" | "PIE" | "TOPN";
export type DataModelLineageLevel = "DATABASE" | "TABLE" | "FIELD";
export type CollectionTaskType = "SINGLE_TABLE" | "FUSION";
export type CollectionTaskStatus = "DRAFT" | "ONLINE";
export type ScriptType = "SQL" | "JAVA" | "PYTHON";
export type DataServiceType = "MODEL_PUBLISH" | "SERVICE_PROXY";
export type DataServiceStatus = "DRAFT" | "ONLINE" | "OFFLINE";
export type DataServiceSourceType = "TABLE" | "SQL";
export type DataServiceRequestMethod = "GET" | "POST";
export type DataServiceResponseType = "JSON" | "XML" | "FILE";
export type DataServiceValueType = "STRING" | "INT" | "TIME" | "FLOAT" | "TIMESTAMP" | "LIST";
export type DataServiceQueryOperator = "EQ" | "LIKE" | "NE" | "GT" | "GE" | "LT" | "LE" | "CONTAINS" | "NOT_CONTAINS";
export type DataServiceParamPosition = "BODY" | "QUERY" | "HEADER";
export type DataIngestionStatus = "DRAFT" | "ONLINE" | "OFFLINE";
export type DataIngestionRequestFormat = "JSON" | "FORM" | "SOAP";
export type DataIngestionPayloadMode = "OBJECT" | "ARRAY";
export type DataIngestionSourcePosition = "BODY" | "FORM" | "QUERY" | "HEADER";
export type DataIngestionTargetType = "DATABASE" | "FILE";
export type ProtocolConversionStatus = "DRAFT" | "ONLINE" | "OFFLINE";
export type ProtocolConversionProtocol = "HTTP_JSON" | "HTTP_XML" | "SOAP_11" | "SOAP_12";
export type ProtocolConversionMode = "FIELD_MAPPING" | "RAW_MESSAGE_FIELD" | "BODY_BRIDGE";
export type ServiceOpenProtocol = "REST" | "SOAP";
export type WebServiceSoapVersion = "SOAP_11" | "SOAP_12";
export type HttpProtocolMode = "REST_JSON" | "REST_XML" | "SOAP";
export type ModelSyncTaskSource = "MANUAL" | "AUTO_PAGE";
export type ModelSyncTaskStatus = "PENDING" | "RUNNING" | "STOPPING" | "SUCCESS" | "FAILED" | "STOPPED";
export type ModelSyncTaskItemStatus = "PENDING" | "RUNNING" | "SUCCESS" | "FAILED" | "STOPPED";
export type FieldMappingRuleParamComponentType =
  | "input"
  | "numberPicker"
  | "textArea"
  | "datePicker"
  | "dateTimePicker"
  | "rangePicker"
  | "select"
  | "checkbox"
  | "radioGroup";

export interface MetadataFieldDefinition {
  fieldKey: string;
  fieldName: string;
  description?: string;
  scope?: MetadataScope;
  valueType?: FieldValueType;
  componentType?: FieldComponentType;
  required?: boolean;
  sensitive?: boolean;
  sortOrder?: number;
  validationRule?: string;
  placeholder?: string;
  defaultValue?: string;
  options?: string[];
  searchable?: boolean;
  sortable?: boolean;
  queryOperators?: QueryOperator[] | string[];
  queryDefaultOperator?: QueryOperator | string;
}

export interface MetadataSchemaDefinition extends BaseRecord {
  schemaCode: string;
  schemaName: string;
  objectType: string;
  typeCode: string;
  currentVersionId?: EntityId;
  versionNumber?: number;
  status?: SchemaStatus;
  description?: string;
  fields: MetadataFieldDefinition[];
}

export interface SourceCapabilityEntry {
  typeCode: string;
  typeName?: string;
  sourceCategory?: string;
  sourcePlugin: string;
  readable: boolean;
  writable: boolean;
  executable: boolean;
  sqlExecutable?: boolean;
  readerPlugins: string[];
  writerPlugins: string[];
}

export interface DatasourceTypeCapabilityView extends BaseRecord {
  typeCode: string;
  typeName: string;
  sourceCategory?: string;
  enabled: boolean;
  readable: boolean;
  writable: boolean;
  executable: boolean;
  sqlExecutable: boolean;
  sourcePlugin?: string;
  readerPlugins: string[];
  writerPlugins: string[];
  sortOrder?: number;
  description?: string;
}

export interface PluginRuntimeOptionSchemaView {
  role: string;
  datasourceType: string;
  sourceCategory?: string;
  pluginType?: string;
  runtimeSupported?: boolean;
  incrementalSupported?: boolean;
  fields: MetadataFieldDefinition[];
  reservedKeys: string[];
  description?: string;
}

export interface CapabilityMatrix {
  executableSourceTypes: string[];
  executableTargetTypes?: string[];
  executableDatasourceTypes?: string[];
  sourceCapabilities?: SourceCapabilityEntry[];
}

export type DataSourceConnectionStatus = "UNKNOWN" | "AVAILABLE" | "UNAVAILABLE";

export interface DatasourceConnectionTestRecordView {
  status?: DataSourceConnectionStatus;
  testedAt?: string;
  startedAt?: string;
  endedAt?: string;
  durationMs?: number | string | null;
  probeMode?: string;
  timeoutSeconds?: number;
  message?: string;
  datasourceId?: EntityId;
  datasourceName?: string;
}

export interface DatasourceConnectionTrendPointView {
  status?: DataSourceConnectionStatus;
  testedAt?: string;
  endedAt?: string;
}

export interface DataSourceDefinition extends BaseRecord {
  name: string;
  typeCode: string;
  schemaVersionId?: EntityId;
  enabled?: boolean;
  executable?: boolean;
  connectionFingerprint?: string;
  connectionStatus?: DataSourceConnectionStatus;
  lastConnectionTestAt?: string;
  lastConnectionTestMessage?: string;
  lastConnectionTestDurationMs?: number | string | null;
  connectionTesting?: boolean;
  connectionStale?: boolean;
  nextConnectionProbeAt?: string;
  manualConnectionTestTimeoutSeconds?: number;
  scheduledConnectionTestTimeoutSeconds?: number;
  recentConnectionTests?: DatasourceConnectionTestRecordView[];
  technicalMetadata: Record<string, unknown>;
  businessMetadata: Record<string, unknown>;
}

export interface DataSourceListView extends BaseRecord {
  name: string;
  typeCode: string;
  schemaVersionId?: EntityId;
  enabled?: boolean;
  executable?: boolean;
  connectionFingerprint?: string;
  connectionStatus?: DataSourceConnectionStatus;
  lastConnectionTestAt?: string;
  lastConnectionTestMessage?: string;
  lastConnectionTestDurationMs?: number | string | null;
  connectionTesting?: boolean;
  connectionStale?: boolean;
  nextConnectionProbeAt?: string;
  manualConnectionTestTimeoutSeconds?: number;
  scheduledConnectionTestTimeoutSeconds?: number;
  recentConnectionTests?: DatasourceConnectionTrendPointView[];
}

export interface ConnectionTestResult {
  success?: boolean;
  message?: string;
  status?: DataSourceConnectionStatus;
  durationMs?: number | string | null;
  testing?: boolean;
  stale?: boolean;
  busy?: boolean;
  lastTestAt?: string;
  nextProbeAt?: string;
  timeoutSeconds?: number;
  recentConnectionTests?: DatasourceConnectionTestRecordView[];
  detail?: string;
  [key: string]: unknown;
}

export interface DataModelDefinition extends BaseRecord {
  datasourceId: EntityId;
  name: string;
  modelKind?: ModelKind;
  physicalLocator: string;
  schemaVersionId?: EntityId;
  technicalMetadata: Record<string, unknown>;
  businessMetadata: Record<string, unknown>;
}

export interface DataModelListView extends BaseRecord {
  datasourceId: EntityId;
  name: string;
  modelKind?: ModelKind;
  physicalLocator: string;
  schemaVersionId?: EntityId;
}

export interface DataServiceRequestParam {
  id?: EntityId;
  serviceId?: EntityId;
  sortOrder?: number;
  paramName: string;
  fieldName?: string;
  valueType?: DataServiceValueType;
  queryOperator?: DataServiceQueryOperator;
  required?: boolean;
  description?: string;
  fixedParam?: boolean;
}

export interface DataServiceResponseParam {
  id?: EntityId;
  serviceId?: EntityId;
  sortOrder?: number;
  enabled?: boolean;
  paramName: string;
  fieldName: string;
  exampleValue?: string;
  description?: string;
  transformers?: TransformerBinding[];
}

export interface DataServicePublishParam {
  id?: EntityId;
  serviceId?: EntityId;
  sortOrder?: number;
  frontendParamName: string;
  backendParamName: string;
  position?: DataServiceParamPosition;
  valueType?: DataServiceValueType;
  exampleValue?: string;
  defaultValue?: string;
  required?: boolean;
  description?: string;
}

export interface DataServiceFieldView {
  fieldName: string;
  fieldType?: string;
  exampleValue?: string;
  description?: string;
}

export interface DataServiceResolveFieldsView {
  fields: DataServiceFieldView[];
  requestParams: DataServiceRequestParam[];
  responseParams: DataServiceResponseParam[];
}

export interface WebServiceConfig {
  enabled?: boolean;
  soapVersion?: WebServiceSoapVersion;
  namespaceUri?: string;
  operationName?: string;
  soapAction?: string;
  requestRootName?: string;
  responseRootName?: string;
  responseDataNodePath?: string;
}

export interface WebServicePreviewView {
  endpointPath?: string;
  wsdlPath?: string;
  wsdl?: string;
  sampleRequest?: string;
  sampleResponse?: string;
  soapAction?: string;
  namespaceUri?: string;
  operationName?: string;
}

export interface WebServiceDebugRequest {
  soapEnvelope?: string;
  soapVersion?: WebServiceSoapVersion;
  headers?: Record<string, unknown>;
}

export interface WebServiceDebugResult {
  success?: boolean;
  httpStatus?: number;
  requestEnvelope?: string;
  responseEnvelope?: string;
  result?: unknown;
  errorCode?: string;
  errorMessage?: string;
}

export interface DataServiceDefinitionView extends BaseRecord {
  createdBy?: EntityId;
  serviceCode: string;
  serviceName: string;
  serviceType?: DataServiceType;
  status?: DataServiceStatus;
  sourceType?: DataServiceSourceType;
  datasourceId?: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId?: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
  customSql?: string;
  requestMethod?: DataServiceRequestMethod;
  responseType?: DataServiceResponseType;
  endpointPath?: string;
  serviceKey?: string;
  cacheEnabled?: boolean;
  tokenRequired?: boolean;
  defaultSubscriptionName?: string;
  webserviceEnabled?: boolean;
  webserviceConfig?: WebServiceConfig;
  requestParams: DataServiceRequestParam[];
  responseParams: DataServiceResponseParam[];
  publishParams: DataServicePublishParam[];
}

export interface DataServiceListView extends BaseRecord {
  createdBy?: EntityId;
  serviceCode: string;
  serviceName: string;
  serviceType?: DataServiceType;
  status?: DataServiceStatus;
  sourceType?: DataServiceSourceType;
  datasourceId?: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId?: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
  requestMethod?: DataServiceRequestMethod;
  responseType?: DataServiceResponseType;
  endpointPath?: string;
  cacheEnabled?: boolean;
  tokenRequired?: boolean;
  defaultSubscriptionName?: string;
  webserviceEnabled?: boolean;
}

export interface DataServiceSaveRequest {
  id?: EntityId;
  serviceCode: string;
  serviceName: string;
  serviceType?: DataServiceType;
  sourceType?: DataServiceSourceType;
  datasourceId?: EntityId;
  modelId?: EntityId;
  customSql?: string;
  requestMethod?: DataServiceRequestMethod;
  responseType?: DataServiceResponseType;
  cacheEnabled?: boolean;
  tokenRequired?: boolean;
  defaultSubscriptionName?: string;
  webserviceEnabled?: boolean;
  webserviceConfig?: WebServiceConfig;
  requestParams: DataServiceRequestParam[];
  responseParams: DataServiceResponseParam[];
  publishParams: DataServicePublishParam[];
}

export interface DataServiceResolveFieldsRequest {
  sourceType?: DataServiceSourceType;
  datasourceId?: EntityId;
  modelId?: EntityId;
  customSql?: string;
}

export interface DataServiceDebugRequest {
  headers?: Record<string, unknown>;
  query?: Record<string, unknown>;
  body?: Record<string, unknown>;
}

export interface DataServiceInvokeResponse {
  pageNum?: number;
  pageSize?: number;
  pages?: number;
  table?: {
    bodies?: Record<string, unknown>[];
  };
  [key: string]: unknown;
}

export interface DataServiceSubscriptionView extends BaseRecord {
  serviceId?: EntityId;
  subscriptionName: string;
  token?: string;
  tokenMasked?: string;
  enabled?: boolean;
  createdBy?: EntityId;
  lastUsedAt?: string;
  rotatedAt?: string;
  rotatedBy?: EntityId;
}

export interface DataIngestionFieldMapping {
  sortOrder?: number;
  sourcePosition?: DataIngestionSourcePosition;
  sourceField?: string;
  targetField: string;
  valueType?: FieldValueType;
  required?: boolean;
  defaultValue?: string;
  description?: string;
}

export interface DataIngestionServiceView extends BaseRecord {
  createdBy?: EntityId;
  serviceCode: string;
  serviceName: string;
  status?: DataIngestionStatus;
  requestFormat?: DataIngestionRequestFormat;
  payloadMode?: DataIngestionPayloadMode;
  dataNodePath?: string;
  targetType?: DataIngestionTargetType;
  datasourceId?: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId?: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
  endpointPath?: string;
  serviceKey?: string;
  maxBatchSize?: number;
  tokenRequired?: boolean;
  defaultSubscriptionName?: string;
  webserviceEnabled?: boolean;
  webserviceConfig?: WebServiceConfig;
  writerOptions?: Record<string, unknown>;
  fieldMappings?: DataIngestionFieldMapping[];
  sourcePositions?: string[];
}

export interface DataIngestionServiceListView extends BaseRecord {
  createdBy?: EntityId;
  serviceCode: string;
  serviceName: string;
  status?: DataIngestionStatus;
  requestFormat?: DataIngestionRequestFormat;
  payloadMode?: DataIngestionPayloadMode;
  dataNodePath?: string;
  targetType?: DataIngestionTargetType;
  datasourceId?: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId?: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
  endpointPath?: string;
  maxBatchSize?: number;
  tokenRequired?: boolean;
  defaultSubscriptionName?: string;
  webserviceEnabled?: boolean;
  sourcePositions?: string[];
}

export interface DataIngestionServiceSaveRequest {
  id?: EntityId;
  serviceCode: string;
  serviceName: string;
  requestFormat?: DataIngestionRequestFormat;
  payloadMode?: DataIngestionPayloadMode;
  dataNodePath?: string;
  targetType?: DataIngestionTargetType;
  datasourceId?: EntityId;
  modelId?: EntityId;
  maxBatchSize?: number;
  tokenRequired?: boolean;
  defaultSubscriptionName?: string;
  webserviceEnabled?: boolean;
  webserviceConfig?: WebServiceConfig;
  writerOptions?: Record<string, unknown>;
  fieldMappings: DataIngestionFieldMapping[];
}

export interface DataIngestionResolveFieldsRequest {
  datasourceId?: EntityId;
  modelId?: EntityId;
}

export interface DataIngestionResolveFieldsView {
  fields: DataServiceFieldView[];
  fieldMappings: DataIngestionFieldMapping[];
}

export interface DataIngestionDebugRequest {
  headers?: Record<string, unknown>;
  query?: Record<string, unknown>;
  form?: Record<string, unknown>;
  body?: unknown;
}

export interface DataIngestionInvokeResult {
  requestId?: string;
  serviceCode?: string;
  receivedCount?: number;
  successCount?: number;
  failedCount?: number;
  status?: string;
}

export interface DataIngestionSubscriptionView extends BaseRecord {
  serviceId?: EntityId;
  subscriptionName: string;
  token?: string;
  tokenMasked?: string;
  enabled?: boolean;
  createdBy?: EntityId;
  lastUsedAt?: string;
  rotatedAt?: string;
  rotatedBy?: EntityId;
}

export interface ProtocolConversionFieldMapping {
  sortOrder?: number;
  sourcePosition?: DataIngestionSourcePosition;
  sourceField?: string;
  targetField: string;
  targetPath?: string;
  valueType?: FieldValueType;
  required?: boolean;
  defaultValue?: string;
  description?: string;
  transformers?: TransformerBinding[];
}

export interface ProtocolConversionFixedField {
  targetField: string;
  targetPath?: string;
  value?: unknown;
  valueType?: FieldValueType;
  description?: string;
}

export interface ProtocolConversionServiceView extends BaseRecord {
  createdBy?: EntityId;
  serviceCode: string;
  serviceName: string;
  status?: ProtocolConversionStatus;
  endpointPath?: string;
  webserviceEndpointPath?: string;
  serviceKey?: string;
  tokenRequired?: boolean;
  defaultSubscriptionName?: string;
  sourceProtocol?: ProtocolConversionProtocol;
  sourceMethod?: string;
  sourceDataNodePath?: string;
  webserviceConfig?: WebServiceConfig;
  conversionMode?: ProtocolConversionMode;
  fieldMappings: ProtocolConversionFieldMapping[];
  rawTransformers?: TransformerBinding[];
  fixedFields?: ProtocolConversionFixedField[];
  bodyBridgeOptions?: Record<string, unknown>;
  requestPassthrough?: Record<string, unknown>;
  targetDatasourceId?: EntityId;
  targetDatasourceName?: string;
  targetPath?: string;
  targetProtocol?: ProtocolConversionProtocol;
  targetMethod?: string;
  targetHeaders?: Record<string, unknown>;
  targetQuery?: Record<string, unknown>;
  targetWebserviceConfig?: WebServiceConfig;
  targetBodyTemplate?: string;
  targetDataNodePath?: string;
  payloadMode?: DataIngestionPayloadMode;
  batchSize?: number;
  responseStatus?: Record<string, unknown>;
}

export interface ProtocolConversionServiceListView extends BaseRecord {
  createdBy?: EntityId;
  serviceCode: string;
  serviceName: string;
  status?: ProtocolConversionStatus;
  endpointPath?: string;
  webserviceEndpointPath?: string;
  tokenRequired?: boolean;
  defaultSubscriptionName?: string;
  sourceProtocol?: ProtocolConversionProtocol;
  sourceMethod?: string;
  sourceDataNodePath?: string;
  conversionMode?: ProtocolConversionMode;
  targetDatasourceId?: EntityId;
  targetDatasourceName?: string;
  targetPath?: string;
  targetProtocol?: ProtocolConversionProtocol;
  targetMethod?: string;
  payloadMode?: DataIngestionPayloadMode;
  batchSize?: number;
}

export interface ProtocolConversionServiceSaveRequest {
  id?: EntityId;
  serviceCode: string;
  serviceName: string;
  tokenRequired?: boolean;
  defaultSubscriptionName?: string;
  sourceProtocol?: ProtocolConversionProtocol;
  sourceMethod?: string;
  sourceDataNodePath?: string;
  webserviceConfig?: WebServiceConfig;
  conversionMode?: ProtocolConversionMode;
  fieldMappings: ProtocolConversionFieldMapping[];
  rawTransformers?: TransformerBinding[];
  fixedFields?: ProtocolConversionFixedField[];
  bodyBridgeOptions?: Record<string, unknown>;
  requestPassthrough?: Record<string, unknown>;
  targetDatasourceId?: EntityId;
  targetPath?: string;
  targetProtocol?: ProtocolConversionProtocol;
  targetMethod?: string;
  targetHeaders?: Record<string, unknown>;
  targetQuery?: Record<string, unknown>;
  targetWebserviceConfig?: WebServiceConfig;
  targetBodyTemplate?: string;
  targetDataNodePath?: string;
  payloadMode?: DataIngestionPayloadMode;
  batchSize?: number;
  responseStatus?: Record<string, unknown>;
}

export interface ProtocolConversionDebugRequest {
  headers?: Record<string, unknown>;
  query?: Record<string, unknown>;
  form?: Record<string, unknown>;
  body?: unknown;
  rawBody?: string;
}

export interface ProtocolConversionInvokeResult {
  requestId?: string;
  serviceCode?: string;
  sourceProtocol?: ProtocolConversionProtocol;
  status?: string;
  targetHttpStatus?: number;
  targetContentType?: string;
  targetBody?: unknown;
  responseBody?: unknown;
  receivedCount?: number;
  successCount?: number;
  failedCount?: number;
}

export interface ProtocolConversionTraceStepView {
  key?: string;
  title?: string;
  status?: string;
  protocol?: string;
  method?: string;
  url?: string;
  httpStatus?: number;
  contentType?: string;
  bodyFormat?: string;
  summary?: string;
  errorMessage?: string;
  headers?: Record<string, unknown>;
  query?: Record<string, unknown>;
  form?: Record<string, unknown>;
  bodyPreview?: string;
}

export interface ProtocolConversionTraceView {
  requestId?: string;
  sourceRequest?: ProtocolConversionTraceStepView;
  convertedRequest?: ProtocolConversionTraceStepView;
  targetResponse?: ProtocolConversionTraceStepView;
  convertedResponse?: ProtocolConversionTraceStepView;
}

export interface ProtocolConversionDebugResult extends ProtocolConversionInvokeResult {
  targetRequest?: Record<string, unknown>;
  conversionTrace?: ProtocolConversionTraceView;
}

export interface ProtocolConversionAccessLogView extends BaseRecord {
  serviceId?: EntityId;
  serviceCode?: string;
  serviceName?: string;
  serviceStatus?: string;
  subscriptionId?: EntityId;
  subscriptionName?: string;
  requestId?: string;
  requestMethod?: string;
  sourceProtocol?: string;
  targetProtocol?: string;
  occurredAt?: string;
  durationMs?: number;
  success?: boolean;
  httpStatus?: number;
  targetHttpStatus?: number;
  errorCode?: string;
  errorMessage?: string;
  systemLog?: string;
  clientIp?: string;
  userAgent?: string;
  receivedCount?: number;
  successCount?: number;
  failedCount?: number;
  logStorageType?: string;
  logObjectBucket?: string;
  logObjectKey?: string;
  logSizeBytes?: number;
  logCharset?: string;
  logArchiveStatus?: string;
  logArchiveError?: string;
}

export interface ProtocolConversionSubscriptionView extends BaseRecord {
  serviceId?: EntityId;
  subscriptionName: string;
  token?: string;
  tokenMasked?: string;
  enabled?: boolean;
  createdBy?: EntityId;
  lastUsedAt?: string;
  rotatedAt?: string;
  rotatedBy?: EntityId;
}

export interface DataServiceMetricOptionView {
  id?: EntityId;
  name?: string;
  label?: string;
  status?: string;
  serviceId?: EntityId;
  serviceName?: string;
}

export interface DataServiceMetricOptionsView {
  services: DataServiceMetricOptionView[];
  subscriptions: DataServiceMetricOptionView[];
}

export interface DataServiceMetricSummaryView {
  accessCount?: number;
  successCount?: number;
  failureCount?: number;
  successRate?: number;
  minResponseTimeMs?: number;
  maxResponseTimeMs?: number;
  avgResponseTimeMs?: number;
  p95ResponseTimeMs?: number;
  p99ResponseTimeMs?: number;
  lastAccessAt?: string;
  cacheEnabledCount?: number;
  cacheHitCount?: number;
  cacheMissCount?: number;
  cacheDisabledCount?: number;
  cacheHitRate?: number;
  counterBacked?: boolean;
}

export interface DataServiceApiMetricView {
  serviceId?: EntityId;
  serviceName?: string;
  serviceCode?: string;
  status?: string;
  subscriptionId?: EntityId;
  subscriptionName?: string;
  accessCount?: number;
  successCount?: number;
  failureCount?: number;
  successRate?: number;
  minResponseTimeMs?: number;
  maxResponseTimeMs?: number;
  avgResponseTimeMs?: number;
  p95ResponseTimeMs?: number;
  p99ResponseTimeMs?: number;
  lastAccessAt?: string;
  cacheEnabledCount?: number;
  cacheHitCount?: number;
  cacheMissCount?: number;
  cacheDisabledCount?: number;
  cacheHitRate?: number;
}

export interface DataServiceMetricDistributionView {
  key?: string;
  label?: string;
  count?: number;
}

export interface DataServiceAccessLogView extends BaseRecord {
  serviceId?: EntityId;
  serviceCode?: string;
  serviceName?: string;
  serviceStatus?: string;
  subscriptionId?: EntityId;
  subscriptionName?: string;
  requestId?: string;
  requestMethod?: string;
  occurredAt?: string;
  durationMs?: number;
  success?: boolean;
  httpStatus?: number;
  errorCode?: string;
  errorMessage?: string;
  systemLog?: string;
  clientIp?: string;
  userAgent?: string;
  cacheEnabled?: boolean;
  cacheHit?: boolean;
  rowCount?: number;
  logStorageType?: string;
  logObjectBucket?: string;
  logObjectKey?: string;
  logSizeBytes?: number;
  logCharset?: string;
  logArchiveStatus?: string;
  logArchiveError?: string;
}

export interface DataServiceMetricDashboardView {
  summary?: DataServiceMetricSummaryView;
  accessTrend?: RunMetricTrendView;
  cumulativeAccessTrend?: RunMetricTrendView;
  outputRowTrend?: RunMetricTrendView;
  cumulativeOutputRowTrend?: RunMetricTrendView;
  cacheHitTrend?: RunMetricTrendView;
  cumulativeCacheHitTrend?: RunMetricTrendView;
  responseTimeTrend?: RunMetricTrendView;
  successRateTrend?: RunMetricTrendView;
  errorDistribution?: DataServiceMetricDistributionView[];
  topSlowApis?: DataServiceApiMetricView[];
  topFailedApis?: DataServiceApiMetricView[];
  subscriptionRank?: DataServiceApiMetricView[];
}

export interface DataServiceMetricQueryRequest {
  serviceId?: EntityId;
  subscriptionId?: EntityId;
  serviceStatus?: string;
  success?: boolean;
  cacheHit?: boolean;
  startTime?: string;
  endTime?: string;
  granularity?: string;
  logFocus?: "ALL" | "ERROR" | "SLOW" | "ERROR_OR_SLOW";
  minDurationMs?: number;
  topN?: number;
  pageNo?: number;
  pageSize?: number;
}

export interface ProtocolConversionMetricQueryRequest {
  serviceId?: EntityId;
  subscriptionId?: EntityId;
  serviceStatus?: string;
  success?: boolean;
  startTime?: string;
  endTime?: string;
  logFocus?: string;
  minDurationMs?: number;
  pageNo?: number;
  pageSize?: number;
}

export interface DataIngestionMetricSummaryView {
  accessCount?: number;
  successCount?: number;
  failureCount?: number;
  successRate?: number;
  receivedCount?: number;
  writtenCount?: number;
  failedCount?: number;
  minResponseTimeMs?: number;
  maxResponseTimeMs?: number;
  avgResponseTimeMs?: number;
  p95ResponseTimeMs?: number;
  p99ResponseTimeMs?: number;
  lastAccessAt?: string;
  counterBacked?: boolean;
}

export interface DataIngestionApiMetricView {
  serviceId?: EntityId;
  serviceName?: string;
  serviceCode?: string;
  status?: string;
  subscriptionId?: EntityId;
  subscriptionName?: string;
  accessCount?: number;
  successCount?: number;
  failureCount?: number;
  successRate?: number;
  receivedCount?: number;
  writtenCount?: number;
  failedCount?: number;
  minResponseTimeMs?: number;
  maxResponseTimeMs?: number;
  avgResponseTimeMs?: number;
  p95ResponseTimeMs?: number;
  p99ResponseTimeMs?: number;
  lastAccessAt?: string;
}

export interface DataIngestionAccessLogView extends BaseRecord {
  serviceId?: EntityId;
  serviceCode?: string;
  serviceName?: string;
  serviceStatus?: string;
  subscriptionId?: EntityId;
  subscriptionName?: string;
  requestId?: string;
  requestMethod?: string;
  occurredAt?: string;
  durationMs?: number;
  success?: boolean;
  httpStatus?: number;
  errorCode?: string;
  errorMessage?: string;
  systemLog?: string;
  clientIp?: string;
  userAgent?: string;
  receivedCount?: number;
  successCount?: number;
  failedCount?: number;
  logStorageType?: string;
  logObjectBucket?: string;
  logObjectKey?: string;
  logSizeBytes?: number;
  logCharset?: string;
  logArchiveStatus?: string;
  logArchiveError?: string;
}

export interface DataIngestionMetricDashboardView {
  summary?: DataIngestionMetricSummaryView;
  accessTrend?: RunMetricTrendView;
  cumulativeAccessTrend?: RunMetricTrendView;
  receivedTrend?: RunMetricTrendView;
  cumulativeReceivedTrend?: RunMetricTrendView;
  writtenTrend?: RunMetricTrendView;
  cumulativeWrittenTrend?: RunMetricTrendView;
  responseTimeTrend?: RunMetricTrendView;
  successRateTrend?: RunMetricTrendView;
  errorDistribution?: DataServiceMetricDistributionView[];
  topSlowServices?: DataIngestionApiMetricView[];
  topFailedServices?: DataIngestionApiMetricView[];
  subscriptionRank?: DataIngestionApiMetricView[];
}

export interface DataIngestionMetricQueryRequest {
  serviceId?: EntityId;
  subscriptionId?: EntityId;
  serviceStatus?: string;
  success?: boolean;
  startTime?: string;
  endTime?: string;
  granularity?: string;
  logFocus?: "ALL" | "ERROR" | "SLOW" | "ERROR_OR_SLOW";
  minDurationMs?: number;
  topN?: number;
  pageNo?: number;
  pageSize?: number;
}

export interface DataModelLineageSummaryView {
  upstreamDepth?: number | string | null;
  totalUpstreamCount?: number | string | null;
  directUpstreamCount?: number | string | null;
  downstreamDepth?: number | string | null;
  totalDownstreamCount?: number | string | null;
  directDownstreamCount?: number | string | null;
}

export interface DataModelLineageNodeFieldView {
  fieldKey?: string;
  fieldName?: string;
}

export interface DataModelLineageNodeView {
  nodeId: string;
  visualType?: string;
  focus?: boolean;
  title?: string;
  subtitle?: string;
  datasourceId?: EntityId;
  modelId?: EntityId;
  datasourceName?: string;
  datasourceType?: string;
  databaseName?: string;
  physicalLocator?: string;
  host?: string;
  port?: string;
  dailyIncrement?: string;
  totalCount?: string;
  fields: DataModelLineageNodeFieldView[];
}

export interface DataModelLineageEdgeView {
  edgeId: string;
  sourceNodeId: string;
  targetNodeId: string;
  selfLoop?: boolean;
  sourceField?: string;
  targetField?: string;
  label?: string;
  sourceType?: string;
  sourceTypeLabel?: string;
  displayStatus?: string;
  latestRunStatus?: string;
  latestRunId?: EntityId;
  latestRunAt?: string;
  contributorCount?: number | string | null;
}

export interface DataModelLineageUnresolvedExpressionView {
  collectionTaskId?: EntityId;
  collectionTaskName?: string;
  targetField?: string;
  expression?: string;
  latestRunStatus?: string;
  latestRunId?: EntityId;
  latestRunAt?: string;
}

export interface DataModelLineageContributorView {
  sourceType?: string;
  sourceTypeLabel?: string;
  displayStatus?: string;
  relationId?: EntityId;
  sourceModelId?: EntityId;
  targetModelId?: EntityId;
  sourceField?: string;
  targetField?: string;
  collectionTaskId?: EntityId;
  collectionTaskName?: string;
  latestRunId?: EntityId;
  latestRunStatus?: string;
  latestRunAt?: string;
  taskPath?: string;
  runPath?: string;
  mappingMode?: string;
  expression?: string;
  maintainerUserId?: EntityId;
  maintainer?: string;
  updatedAt?: string;
  editable?: boolean;
}

export interface DataModelLineageEdgeDetailView {
  edgeId?: string;
  sourceNodeTitle?: string;
  targetNodeTitle?: string;
  sourceField?: string;
  targetField?: string;
  sourceType?: string;
  sourceTypeLabel?: string;
  displayStatus?: string;
  latestRunStatus?: string;
  latestRunId?: EntityId;
  latestRunAt?: string;
  contributors: DataModelLineageContributorView[];
}

export interface DataModelLineageView {
  editable?: boolean;
  summary?: DataModelLineageSummaryView;
  nodes: DataModelLineageNodeView[];
  edges: DataModelLineageEdgeView[];
  unresolvedExpressions: DataModelLineageUnresolvedExpressionView[];
}

export interface DataModelIndexQueueStatusView {
  queuedRebuildCount?: number | string | null;
  activeRebuildCount?: number | string | null;
  pendingRebuildCount?: number | string | null;
  queuedCommandCount?: number | string | null;
  activeCommandCount?: number | string | null;
  busy?: boolean | null;
}

export interface DataModelSaveRequest {
  id?: EntityId;
  datasourceId: EntityId;
  name: string;
  physicalLocator: string;
  modelKind?: ModelKind;
  schemaVersionId?: EntityId;
  technicalMetadata: Record<string, unknown>;
  businessMetadata: Record<string, unknown>;
}

export interface DataModelQueryCondition {
  fieldKey: string;
  operator?: QueryOperator | string;
  value?: unknown;
  values?: unknown[];
}

export interface DataModelQueryGroup {
  scope?: MetadataScope | string;
  metaSchemaCode: string;
  rowMatchMode?: RowMatchMode | string;
  conditions: DataModelQueryCondition[];
}

export interface DataModelQueryRequest {
  datasourceId?: EntityId;
  datasourceType?: string;
  modelKind?: ModelKind | string;
  sortField?: string;
  sortOrder?: string;
  pageNo?: number;
  pageSize?: number;
  groups: DataModelQueryGroup[];
}

export interface DataModelManualLineageSaveRequest {
  level: DataModelLineageLevel;
  sourceModelId: EntityId;
  targetModelId: EntityId;
  sourceFieldKey?: string;
  targetFieldKey?: string;
}

export interface DataModelStatisticsBucketConfig {
  lowerBound?: number;
  upperBound?: number;
  step?: number;
}

export interface DataModelStatisticsRequest extends DataModelQueryRequest {
  targetMetaSchemaCode: string;
  targetFieldKey: string;
  targetScope?: MetadataScope | string;
  statType?: StatisticType | string;
  topN?: number;
  bucketConfig?: DataModelStatisticsBucketConfig;
}

export interface DataModelStatisticsBucketView {
  key?: string;
  label?: string;
  value?: string;
  lowerBound?: number | string | null;
  upperBound?: number | string | null;
  count?: number | string | null;
}

export interface DataModelStatisticsView {
  matchedModelCount?: number | string | null;
  matchedItemCount?: number | string | null;
  buckets: DataModelStatisticsBucketView[];
  summaryMetrics: Record<string, unknown>;
}

export interface DataModelStatisticsOptionsRequest {
  datasourceId?: EntityId;
  datasourceType?: string;
  targetScope?: MetadataScope | string;
}

export interface DataModelStatisticsFieldOptionView {
  fieldKey: string;
  fieldName: string;
  scope?: MetadataScope;
  valueType?: FieldValueType;
  queryOperators?: QueryOperator[] | string[];
  queryDefaultOperator?: QueryOperator | string;
  supportedChartTypes?: StatisticsChartType[] | string[];
}

export interface DataModelStatisticsSchemaOptionView {
  schemaCode: string;
  schemaName: string;
  scope?: MetadataScope;
  datasourceType?: string;
  metaModelCode?: string;
  displayMode?: "SINGLE" | "MULTIPLE" | string;
  fields: DataModelStatisticsFieldOptionView[];
}

export interface DataModelStatisticsOptionsView {
  datasourceType?: string;
  querySchemas: DataModelStatisticsSchemaOptionView[];
  targetSchemas: DataModelStatisticsSchemaOptionView[];
}

export interface DataModelStatisticsChartRequest extends DataModelStatisticsRequest {
  chartType: StatisticsChartType | string;
  days?: number;
  timeMode?: "CREATED_AT" | string;
}

export interface DataModelStatisticsChartSeriesView {
  name?: string;
  type?: string;
  data: unknown[];
}

export interface DataModelStatisticsChartTableRowView {
  key?: string;
  label?: string;
  category?: string;
  date?: string;
  rank?: number;
  count?: number | string | null;
  ratio?: number | string | null;
  lowerBound?: number | string | null;
  upperBound?: number | string | null;
  value?: string;
}

export interface DataModelStatisticsChartView {
  chartType?: StatisticsChartType | string;
  summaryMetrics: Record<string, unknown>;
  xAxis: string[];
  series: DataModelStatisticsChartSeriesView[];
  tableRows: DataModelStatisticsChartTableRowView[];
  disabledReason?: string;
}

export interface ModelSyncRequest {
  physicalLocators: string[];
}

export interface ModelDiscoveryResult {
  models: DataModelDefinition[];
  message?: string;
  total?: number;
  pageNo?: number;
  pageSize?: number;
  hasMore?: boolean;
  [key: string]: unknown;
}

export interface ModelSyncTaskCreateRequest {
  datasourceId: EntityId;
  physicalLocators: string[];
  source?: ModelSyncTaskSource | string;
}

export interface ModelSyncTaskView extends BaseRecord {
  datasourceId?: EntityId;
  datasourceType?: string;
  datasourceNameSnapshot?: string;
  batchNo?: number;
  name: string;
  source?: ModelSyncTaskSource | string;
  status?: ModelSyncTaskStatus | string;
  totalCount?: number;
  successCount?: number;
  failedCount?: number;
  stoppedCount?: number;
  progressPercent?: number;
  stopRequested?: boolean;
  createdBy?: EntityId;
  startedAt?: string;
  finishedAt?: string;
  durationMs?: number;
  lastError?: string;
}

export interface ModelSyncTaskItemView extends BaseRecord {
  taskId?: EntityId;
  seqNo?: number;
  physicalLocator?: string;
  modelNameSnapshot?: string;
  status?: ModelSyncTaskItemStatus | string;
  message?: string;
  startedAt?: string;
  finishedAt?: string;
  durationMs?: number;
}

export interface TransformerBinding {
  mappingRuleId?: EntityId;
  mappingCode?: string;
  mappingName?: string;
  mappingType?: string;
  transformerCode: string;
  parameters: Record<string, unknown>;
}

export interface FieldMappingRuleParamView extends BaseRecord {
  ruleId?: EntityId;
  paramName: string;
  paramOrder: number;
  componentType: FieldMappingRuleParamComponentType | string;
  paramValueJson?: string;
  description?: string;
}

export interface FieldMappingRuleView extends BaseRecord {
  mappingName: string;
  mappingType: string;
  mappingCode: string;
  enabled?: boolean;
  description?: string;
  createdBy?: EntityId;
  createdByName?: string;
  params: FieldMappingRuleParamView[];
}

export interface FieldMappingRuleListView extends BaseRecord {
  mappingName: string;
  mappingType: string;
  mappingCode: string;
  enabled?: boolean;
  createdByName?: string;
}

export interface FieldMappingRuleParamSaveRequest {
  id?: EntityId;
  paramName: string;
  paramOrder: number;
  componentType: FieldMappingRuleParamComponentType | string;
  paramValueJson?: string;
  description?: string;
}

export interface FieldMappingRuleSaveRequest {
  id?: EntityId;
  mappingName: string;
  mappingType: string;
  mappingCode: string;
  enabled?: boolean;
  description?: string;
  params: FieldMappingRuleParamSaveRequest[];
}

export interface FieldMappingDefinition {
  sourceAlias?: string;
  sourceField?: string;
  targetField?: string;
  expression?: string;
  transformers: TransformerBinding[];
}

export interface CollectionIncrementalDefinition {
  enabled?: boolean;
  incrColumn?: string;
  incrModel?: string;
  pkValue?: unknown;
  valueType?: string;
  lastRunRecordId?: EntityId;
  lastUpdatedAt?: string;
  cursorStates?: CollectionIncrementalCursorState[];
}

export interface CollectionIncrementalCursorState {
  incrColumn?: string;
  incrModel?: string;
  pkValue?: unknown;
  valueType?: string;
  lastRunRecordId?: EntityId;
  lastUpdatedAt?: string;
}

export interface CollectionTaskSourceBinding {
  sourceAlias: string;
  datasourceId: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
  readerOptions?: Record<string, unknown>;
  incremental?: CollectionIncrementalDefinition;
}

export interface CollectionTaskTargetBinding {
  datasourceId: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
  writerOptions?: Record<string, unknown>;
}

export interface CollectionTaskScheduleDefinition {
  cronExpression?: string;
  enabled?: boolean;
  timezone?: string;
}

export interface CollectionTaskDefinitionView extends BaseRecord {
  name: string;
  taskType?: CollectionTaskType;
  status?: CollectionTaskStatus;
  sourceCount?: number;
  sourceBindings: CollectionTaskSourceBinding[];
  targetBinding?: CollectionTaskTargetBinding;
  fieldMappings: FieldMappingDefinition[];
  executionOptions: Record<string, unknown>;
  schedule?: CollectionTaskScheduleDefinition;
}

export interface CollectionTaskListView extends BaseRecord {
  name: string;
  taskType?: CollectionTaskType;
  status?: CollectionTaskStatus;
  sourceCount?: number;
  targetDatasourceName?: string;
  targetDatasourceTypeCode?: string;
  targetModelName?: string;
  targetModelPhysicalLocator?: string;
  schedule?: CollectionTaskScheduleDefinition;
}

export interface CollectionTaskListQuery {
  name?: string;
  targetDatasource?: string;
  targetModel?: string;
}

export interface CollectionTaskSaveRequest {
  id?: EntityId;
  name: string;
  sourceBindings: CollectionTaskSourceBinding[];
  targetBinding: CollectionTaskTargetBinding;
  fieldMappings: FieldMappingDefinition[];
  executionOptions: Record<string, unknown>;
  schedule?: CollectionTaskScheduleDefinition;
}

export type JobContainerConfig = Record<string, unknown>;

export type QualityRuleScopeType = "SYSTEM" | "PROJECT";
export type QualityRuleDimension = "CONSISTENCY" | "ACCURACY" | "UNIQUENESS" | "TIMELINESS" | "COMPLETENESS" | "VALIDITY";
export type QualityRuleGranularity = "TABLE" | "COLUMN";
export type QualityRuleParamType = "TABLE" | "COLUMN" | "CUSTOM";
export type QualityRuleOutputType = "NUMBER" | "STRING";
export type QualityTaskStatus = "DRAFT" | "ONLINE";
export type QualityTaskAlertOperator = "EQ" | "NE" | "LT" | "LE" | "GT" | "GE" | "LT_R_LT" | "LT_R_LE" | "LE_R_LT" | "LE_R_LE";

export interface QualityRuleInputParamView extends BaseRecord {
  ruleId?: EntityId;
  paramOrder?: number;
  paramName: string;
  paramType?: QualityRuleParamType;
  paramMeaning?: string;
}

export interface QualityRuleOutputParamView extends BaseRecord {
  ruleId?: EntityId;
  outputOrder?: number;
  resultField: string;
  outputType?: QualityRuleOutputType;
  outputDescription?: string;
}

export interface QualityRuleView extends BaseRecord {
  ruleName: string;
  ruleCode: string;
  scopeType?: QualityRuleScopeType;
  ruleDimension?: QualityRuleDimension;
  description?: string;
  supportedDatasourceTypes: string[];
  granularity?: QualityRuleGranularity;
  logicSql: string;
  enabled?: boolean;
  createdBy?: EntityId;
  createdByName?: string;
  editable?: boolean;
  deletable?: boolean;
  inputParams: QualityRuleInputParamView[];
  outputParams: QualityRuleOutputParamView[];
}

export interface QualityRuleListView extends BaseRecord {
  ruleName: string;
  ruleCode: string;
  scopeType?: QualityRuleScopeType;
  ruleDimension?: QualityRuleDimension;
  granularity?: QualityRuleGranularity;
  enabled?: boolean;
  createdBy?: EntityId;
  createdByName?: string;
  editable?: boolean;
  deletable?: boolean;
}

export interface QualityRuleInputParamSaveRequest {
  id?: EntityId;
  paramOrder?: number;
  paramName: string;
  paramType?: QualityRuleParamType;
  paramMeaning?: string;
}

export interface QualityRuleOutputParamSaveRequest {
  id?: EntityId;
  outputOrder?: number;
  resultField: string;
  outputType?: QualityRuleOutputType;
  outputDescription?: string;
}

export interface QualityRuleSaveRequest {
  id?: EntityId;
  ruleName: string;
  ruleCode: string;
  scopeType: QualityRuleScopeType;
  ruleDimension: QualityRuleDimension;
  description?: string;
  supportedDatasourceTypes: string[];
  granularity: QualityRuleGranularity;
  logicSql: string;
  enabled?: boolean;
  inputParams: QualityRuleInputParamSaveRequest[];
  outputParams: QualityRuleOutputParamSaveRequest[];
}

export interface QualityRuleParseRequest {
  granularity?: QualityRuleGranularity;
  logicSql?: string;
}

export interface QualityRuleValidateRequest {
  granularity?: QualityRuleGranularity;
  logicSql?: string;
}

export interface QualityRuleParseResult {
  inputParams: QualityRuleInputParamView[];
  outputParams: QualityRuleOutputParamView[];
  warnings: string[];
}

export interface QualityRuleValidationResult {
  valid?: boolean;
  message?: string;
  warnings: string[];
}

export interface QualityTaskParamBinding {
  paramOrder?: number;
  paramName: string;
  paramType?: QualityRuleParamType;
  paramMeaning?: string;
  paramValue?: string;
  editable?: boolean;
}

export interface QualityTaskAlertConfig {
  outputOrder?: number;
  resultField?: string;
  outputType?: QualityRuleOutputType;
  enabled?: boolean;
  operator?: QualityTaskAlertOperator;
  expectedValue?: string;
  minValue?: string;
  maxValue?: string;
}

export interface QualityTaskDefinitionView extends BaseRecord {
  createdBy?: EntityId;
  taskName: string;
  taskCode: string;
  status?: QualityTaskStatus;
  ruleId?: EntityId;
  ruleName?: string;
  ruleDimension?: QualityRuleDimension;
  granularity?: QualityRuleGranularity;
  datasourceId?: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId?: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
  columnName?: string;
  whereClause?: string;
  resolvedSqlPreview?: string;
  parameterBindings: QualityTaskParamBinding[];
  outputParams: QualityRuleOutputParamView[];
  alertConfigs: QualityTaskAlertConfig[];
  schedule?: CollectionTaskScheduleDefinition;
  ruleSnapshot?: Record<string, unknown>;
}

export interface QualityTaskListView extends BaseRecord {
  createdBy?: EntityId;
  taskName: string;
  taskCode: string;
  status?: QualityTaskStatus;
  ruleId?: EntityId;
  ruleName?: string;
  ruleDimension?: QualityRuleDimension;
  granularity?: QualityRuleGranularity;
  datasourceId?: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId?: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
  columnName?: string;
}

export interface QualityTaskSaveRequest {
  id?: EntityId;
  taskName: string;
  taskCode: string;
  ruleId: EntityId;
  granularity: QualityRuleGranularity;
  datasourceId: EntityId;
  modelId: EntityId;
  columnName?: string;
  whereClause?: string;
  resolvedSqlPreview?: string;
  parameterBindings: QualityTaskParamBinding[];
  alertConfigs: QualityTaskAlertConfig[];
  schedule?: CollectionTaskScheduleDefinition;
}

export interface QualityTaskPreviewView {
  resolvedSql?: string;
  warnings: string[];
}

export interface QualityTaskValidationView {
  valid?: boolean;
  message?: string;
  resolvedSql?: string;
  columns: string[];
  rows: Record<string, unknown>[];
  outputParams: QualityRuleOutputParamView[];
  warnings: string[];
  summary: Record<string, unknown>;
}

export type QualityIssueSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export type QualityIssueStatus =
  | "OPEN"
  | "ACKNOWLEDGED"
  | "INVESTIGATING"
  | "MITIGATED"
  | "RESOLVED"
  | "FALSE_POSITIVE";

export interface QualityMetricOptionsView {
  datasources: RunMetricFilterOption[];
  models: RunMetricFilterOption[];
}

export interface QualityScoreTrendPoint {
  dateLabel?: string;
  executionHealthScore?: number;
  governanceRiskScore?: number;
}

export interface QualityAssetRiskView {
  assetId?: string;
  datasourceId?: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId?: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
  executionHealthScore?: number;
  governanceRiskScore?: number;
  activeIssueCount?: number;
  overdueIssueCount?: number;
  coverageRate?: number;
  coverageDimensions: string[];
  riskDimensions: string[];
  latestRunStatus?: string;
  latestRunAt?: string;
  latestEvidence?: string;
}

export interface QualityMetricDashboardView {
  summaryMetrics: Record<string, number>;
  scoreTrend: QualityScoreTrendPoint[];
  issueTrend: Array<Record<string, unknown>>;
  dimensionDistribution: Array<Record<string, unknown>>;
  coverageMatrix: Array<Record<string, unknown>>;
  riskyAssets: QualityAssetRiskView[];
  noisyTargets: Array<Record<string, unknown>>;
}

export interface QualityIssueTimelineEvent {
  id?: EntityId;
  eventType?: string;
  title?: string;
  message?: string;
  actorUserId?: EntityId;
  actorName?: string;
  createdAt?: string;
  metadata?: Record<string, unknown>;
}

export interface QualityIssueView {
  id?: EntityId;
  issueCode?: string;
  issueType?: string;
  title?: string;
  severity?: QualityIssueSeverity;
  systemSeverity?: QualityIssueSeverity;
  manualSeverity?: QualityIssueSeverity;
  status?: QualityIssueStatus;
  assetId?: string;
  datasourceId?: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId?: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
  qualityTaskId?: EntityId;
  qualityTaskName?: string;
  ruleId?: EntityId;
  ruleName?: string;
  ruleDimension?: string;
  granularity?: string;
  columnName?: string;
  outputField?: string;
  firstSeenAt?: string;
  lastSeenAt?: string;
  lastRecoveryAt?: string;
  slaDueAt?: string;
  assigneeUserId?: EntityId;
  assigneeName?: string;
  latestMessage?: string;
  lastRunRecordId?: EntityId;
  lastRunStatus?: string;
  occurrenceCount?: number;
  consecutiveFailureCount?: number;
  reopenCount?: number;
  overdue?: boolean;
  active?: boolean;
}

export interface QualityIssueDetailView extends QualityIssueView {
  currentEvidence?: Record<string, unknown>;
  timeline: QualityIssueTimelineEvent[];
}

export interface QualityAssetDetailView extends QualityAssetRiskView {
  summaryMetrics: Record<string, unknown>;
  scoreTrend: QualityScoreTrendPoint[];
  activeIssues: QualityIssueView[];
  relatedTasks: Array<Record<string, unknown>>;
  latestFailures: Array<Record<string, unknown>>;
  fieldIssueGroups: Array<Record<string, unknown>>;
}

export interface QualityMetricDashboardQueryRequest {
  datasourceId?: EntityId;
  modelId?: EntityId;
  ruleDimension?: string;
  granularity?: string;
  taskStatus?: string;
  startTime?: string;
  endTime?: string;
  topN?: number;
}

export interface QualityAssetQueryRequest {
  datasourceId?: EntityId;
  modelId?: EntityId;
  ruleDimension?: string;
  granularity?: string;
  taskStatus?: string;
  startTime?: string;
  endTime?: string;
  onlyProblemAssets?: boolean;
  onlyLowCoverageAssets?: boolean;
}

export interface QualityIssueQueryRequest {
  datasourceId?: EntityId;
  modelId?: EntityId;
  ruleDimension?: string;
  granularity?: string;
  taskStatus?: string;
  severity?: QualityIssueSeverity | string;
  status?: QualityIssueStatus | string;
  assigneeUserId?: EntityId;
  startTime?: string;
  endTime?: string;
}

export interface QualityIssueAssignRequest {
  assigneeUserId?: EntityId | null;
}

export interface QualityIssueStatusRequest {
  status: QualityIssueStatus | string;
  comment?: string;
}

export interface QualityIssueSeverityRequest {
  severity: QualityIssueSeverity | string;
  comment?: string;
}

export interface QualityIssueCommentRequest {
  content: string;
}

export interface DataDevelopmentDirectory extends BaseRecord {
  parentId?: EntityId;
  name: string;
  permissionCode?: string;
  description?: string;
}

export interface DataDevelopmentScript extends BaseRecord {
  directoryId?: EntityId;
  fileName: string;
  scriptType: ScriptType;
  datasourceId?: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  environmentId?: EntityId;
  environmentName?: string;
  description?: string;
  content: string;
}

export interface DataDevelopmentScriptListView extends BaseRecord {
  directoryId?: EntityId;
  fileName: string;
  scriptType: ScriptType;
  datasourceId?: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  environmentId?: EntityId;
  environmentName?: string;
  description?: string;
}

export interface DataDevelopmentTreeNode {
  nodeKey: string;
  nodeType: "DIRECTORY" | "SCRIPT";
  directoryId?: EntityId;
  scriptId?: EntityId;
  parentId?: EntityId;
  projectId?: EntityId;
  name: string;
  permissionCode?: string;
  scriptType?: ScriptType;
  datasourceName?: string;
  environmentId?: EntityId;
  environmentName?: string;
  children: DataDevelopmentTreeNode[];
}

export interface DataDevelopmentDirectorySaveRequest {
  id?: EntityId;
  parentId?: EntityId;
  name: string;
  permissionCode?: string;
  description?: string;
}

export interface DataDevelopmentScriptSaveRequest {
  id?: EntityId;
  directoryId?: EntityId;
  fileName: string;
  scriptType: ScriptType;
  datasourceId?: EntityId;
  environmentId?: EntityId;
  description?: string;
  content: string;
}

export interface DataDevelopmentMoveRequest {
  targetDirectoryId?: EntityId;
}

export interface SqlExecutionRequest {
  datasourceId: EntityId;
  scriptType: ScriptType;
  content: string;
  maxRows?: number;
}

export interface DataScriptExecutionRequest {
  scriptType: ScriptType;
  datasourceId?: EntityId;
  environmentId?: EntityId;
  content: string;
  arguments?: Record<string, unknown>;
  maxRows?: number;
}

export interface SavedDataScriptExecutionRequest {
  arguments?: Record<string, unknown>;
  maxRows?: number;
  waitTimeoutSeconds?: number;
}

export interface EnvironmentDependency extends BaseRecord {
  name: string;
  version?: string;
  scriptType: ScriptType | string;
  artifactUrl?: string;
  artifactType?: "JAR" | "ZIP" | string;
  checksum?: string;
  enabled: boolean;
  description?: string;
  files?: EnvironmentDependencyFile[];
}

export interface EnvironmentDependencyListView extends BaseRecord {
  name: string;
  version?: string;
  scriptType: ScriptType | string;
  enabled: boolean;
  files?: EnvironmentDependencyFileListView[];
}

export interface EnvironmentDependencyOption extends BaseRecord {
  name: string;
  version?: string;
  scriptType: ScriptType | string;
  enabled: boolean;
}

export interface EnvironmentDependencyFile extends BaseRecord {
  dependencyId?: EntityId;
  originalFileName: string;
  artifactType: "JAR" | "ZIP" | string;
  checksum?: string;
  sizeBytes?: number;
  visible?: boolean;
  runtimeArtifact?: boolean;
  sourceFileId?: EntityId;
  enabled?: boolean;
  uploadedAt?: string;
}

export interface EnvironmentDependencyFileListView extends BaseRecord {
  dependencyId?: EntityId;
  originalFileName: string;
  artifactType: "JAR" | "ZIP" | string;
  sizeBytes?: number;
  visible?: boolean;
  enabled?: boolean;
}

export interface EnvironmentDependencySaveRequest {
  id?: EntityId;
  name: string;
  version?: string;
  scriptType?: ScriptType | string;
  artifactUrl?: string;
  artifactType?: "JAR" | "ZIP" | string;
  checksum?: string;
  enabled?: boolean;
  description?: string;
}

export interface ScriptEnvironment extends BaseRecord {
  environmentName: string;
  environmentCode: string;
  enabled: boolean;
  useApplicationParent: boolean;
  environmentVersion?: number;
  description?: string;
  dependencyIds: EntityId[];
  dependencies: EnvironmentDependency[];
}

export interface ScriptEnvironmentListView extends BaseRecord {
  environmentName: string;
  environmentCode: string;
  enabled: boolean;
  useApplicationParent: boolean;
  environmentVersion?: number;
  dependencyIds: EntityId[];
  dependencies: EnvironmentDependencyOption[];
}

export interface ScriptEnvironmentSaveRequest {
  id?: EntityId;
  environmentName: string;
  environmentCode: string;
  enabled?: boolean;
  useApplicationParent?: boolean;
  dependencyIds?: EntityId[];
  description?: string;
}

export interface JavaImportHint {
  qualifiedName: string;
  simpleName: string;
  packageName: string;
  source?: string;
  environmentDependencyId?: EntityId;
}

export interface JavaImportHintResponse {
  environmentId?: EntityId;
  environmentVersion?: number;
  generatedAt?: string;
  classes: JavaImportHint[];
}

export type JavaMemberHintKind = "METHOD" | "FIELD";

export interface JavaMemberHint {
  name: string;
  kind: JavaMemberHintKind;
  staticMember?: boolean;
  returnType?: string;
  declaringClass?: string;
  parameterTypes?: string[];
  parameterNames?: string[];
  displaySignature?: string;
  insertText?: string;
}

export interface JavaMemberHintResponse {
  environmentId?: EntityId;
  environmentVersion?: number;
  className?: string;
  generatedAt?: string;
  members: JavaMemberHint[];
}

export interface SqlStatementExecutionResult {
  statementIndex?: number;
  sql?: string;
  query?: boolean;
  affectedRows?: number;
  executionMs?: number;
  message?: string;
  columns: string[];
  rows: Record<string, unknown>[];
  summary: Record<string, unknown>;
}

export interface SqlExecutionResult {
  query?: boolean;
  statementCount?: number;
  affectedRows?: number;
  executionMs?: number;
  message?: string;
  datasourceName?: string;
  columns: string[];
  rows: Record<string, unknown>[];
  summary: Record<string, unknown>;
  results: SqlStatementExecutionResult[];
}

export interface DataScriptExecutionResult {
  scriptType?: ScriptType;
  success?: boolean;
  status?: string;
  message?: string;
  executionMs?: number;
  dispatchTaskId?: EntityId;
  runRecordId?: EntityId;
  logStatus?: string;
  datasourceName?: string;
  logs?: string;
  resultJson: Record<string, unknown>;
  sqlResult?: SqlExecutionResult;
}

export interface WorkflowNodeDefinition {
  nodeCode: string;
  nodeName: string;
  nodeType?: NodeType;
  config: Record<string, unknown>;
  fieldMappings: FieldMappingDefinition[];
}

export interface WorkflowEdgeDefinition {
  fromNodeCode: string;
  toNodeCode: string;
  condition?: EdgeCondition;
}

export interface WorkflowScheduleDefinition {
  cronExpression?: string;
  enabled?: boolean;
  timezone?: string;
}

export interface WorkflowDefinitionView extends BaseRecord {
  code: string;
  name: string;
  versionId?: EntityId;
  versionNumber?: number;
  published?: boolean;
  schedule?: WorkflowScheduleDefinition;
  nodes: WorkflowNodeDefinition[];
  edges: WorkflowEdgeDefinition[];
}

export interface WorkflowListView extends BaseRecord {
  code: string;
  name: string;
  versionId?: EntityId;
  versionNumber?: number;
  published?: boolean;
  schedule?: WorkflowScheduleDefinition;
}

export interface WorkflowSaveRequest {
  definitionId?: EntityId;
  code: string;
  name: string;
  schedule?: WorkflowScheduleDefinition;
  nodes: WorkflowNodeDefinition[];
  edges: WorkflowEdgeDefinition[];
}

export interface QueuedTask extends BaseRecord {
  executionType?: string;
  workflowRunId?: EntityId;
  workflowDefinitionId?: EntityId;
  workflowVersionId?: EntityId;
  workflowName?: string;
  collectionTaskId?: EntityId;
  collectionTaskName?: string;
  qualityTaskId?: EntityId;
  qualityTaskName?: string;
  nodeCode?: string;
  status?: string;
  workerGroupCode?: string;
  leaseOwner?: string;
  attempts?: number;
  maxRetries?: number;
  payloadJson?: Record<string, unknown>;
}

export interface QueuedTaskListView extends BaseRecord {
  executionType?: string;
  workflowRunId?: EntityId;
  workflowDefinitionId?: EntityId;
  workflowVersionId?: EntityId;
  workflowName?: string;
  collectionTaskId?: EntityId;
  collectionTaskName?: string;
  qualityTaskId?: EntityId;
  qualityTaskName?: string;
  nodeCode?: string;
  status?: string;
  workerGroupCode?: string;
  leaseOwner?: string;
  attempts?: number;
  maxRetries?: number;
}

export interface RunRecord extends BaseRecord {
  executionType?: string;
  workflowRunId?: EntityId;
  workflowDefinitionId?: EntityId;
  workflowVersionId?: EntityId;
  workflowName?: string;
  collectionTaskId?: EntityId;
  collectionTaskName?: string;
  qualityTaskId?: EntityId;
  qualityTaskName?: string;
  nodeCode?: string;
  workerGroupCode?: string;
  workerCode?: string;
  workerInstanceId?: string;
  workerPodName?: string;
  workerNodeName?: string;
  status?: string;
  message?: string;
  startedAt?: string;
  endedAt?: string;
  logFilePath?: string;
  logSizeBytes?: number;
  logCharset?: string;
  metricSummary?: RunMetricSummary;
  payloadJson?: Record<string, unknown>;
  resultJson?: Record<string, unknown>;
}

export interface RunRecordListView extends BaseRecord {
  executionType?: string;
  workflowRunId?: EntityId;
  workflowDefinitionId?: EntityId;
  workflowVersionId?: EntityId;
  workflowName?: string;
  collectionTaskId?: EntityId;
  collectionTaskName?: string;
  qualityTaskId?: EntityId;
  qualityTaskName?: string;
  nodeCode?: string;
  workerGroupCode?: string;
  workerCode?: string;
  workerInstanceId?: string;
  workerPodName?: string;
  workerNodeName?: string;
  status?: string;
  message?: string;
  startedAt?: string;
  endedAt?: string;
  durationMs?: number;
  metricSummary?: RunMetricSummary;
}

export interface RunMetricSummary {
  collectedRecords?: number | string | null;
  successRecords?: number | string | null;
  readSucceedRecords?: number | string | null;
  readFailedRecords?: number | string | null;
  writeSucceedRecords?: number | string | null;
  writeFailedRecords?: number | string | null;
  failedRecords?: number | string | null;
  transformerTotalRecords?: number | string | null;
  transformerSuccessRecords?: number | string | null;
  transformerFailedRecords?: number | string | null;
  transformerFilterRecords?: number | string | null;
}

export interface RunMetricFilterOption {
  id?: EntityId;
  name?: string;
  label?: string;
  typeCode?: string;
}

export interface RunMetricOptionsView {
  datasources: RunMetricFilterOption[];
  sourceModels: RunMetricFilterOption[];
  targetModels: RunMetricFilterOption[];
}

export interface RunMetricTrendSeries {
  key?: string;
  name?: string;
  data: Array<number | string | null>;
}

export interface RunMetricTrendView {
  xAxis: string[];
  series: RunMetricTrendSeries[];
}

export interface RunMetricTopNItem {
  id?: EntityId;
  name?: string;
  label?: string;
  typeCode?: string;
  count?: number | string | null;
}

export interface RunMetricDashboardResponse {
  trend: RunMetricTrendView;
  sourceDatasourceTopN: RunMetricTopNItem[];
  targetDatasourceTopN: RunMetricTopNItem[];
  sourceModelTopN: RunMetricTopNItem[];
  targetModelTopN: RunMetricTopNItem[];
  legacyRunCount?: number | string | null;
}

export interface RunMetricDashboardQueryRequest {
  datasourceId?: EntityId;
  sourceModelId?: EntityId;
  targetModelId?: EntityId;
  startTime?: string;
  endTime?: string;
  granularity?: "DAY" | "WEEK" | "MONTH" | string;
  topN?: number;
}

export interface RunLogView {
  runRecordId?: EntityId;
  content?: string;
  truncated?: boolean;
  paged?: boolean;
  sizeBytes?: number;
  updatedAt?: string;
  charset?: string;
  downloadName?: string;
  contentType?: string;
  historicalFallback?: boolean;
  pageNo?: number;
  totalPages?: number;
  pageSizeBytes?: number;
}

export interface RunListResponse {
  queuedTasks: QueuedTaskListView[];
  runRecords: RunRecordListView[];
}

export interface RunListQuery {
  collectionTaskId?: EntityId;
  qualityTaskId?: EntityId;
  workflowDefinitionId?: EntityId;
  startTime?: string;
  endTime?: string;
  includeRunRecords?: boolean;
}

export type OpsCenterHealthStatus = "HEALTHY" | "WARNING" | "CRITICAL";

export interface OpsCenterQueryRequest {
  startTime?: string;
  endTime?: string;
  executionType?: string;
  status?: string;
  workerGroupCode?: string;
  pageNo?: number;
  pageSize?: number;
}

export interface OpsCenterOptionsView {
  executionTypes: string[];
  statuses: string[];
  workerGroups: string[];
}

export interface OpsCenterMetricCardView {
  key?: string;
  label?: string;
  value?: number | string | null;
  status?: OpsCenterHealthStatus | string;
  hint?: string;
}

export interface OpsCenterOverviewView {
  healthStatus?: OpsCenterHealthStatus | string;
  healthMessage?: string;
  startTime?: string;
  endTime?: string;
  healthReasons?: string[];
  metrics?: OpsCenterMetricCardView[];
  runTotal?: number | string | null;
  failedRuns?: number | string | null;
  runningRuns?: number | string | null;
  slowRuns?: number | string | null;
  queuedTasks?: number | string | null;
  runningQueueTasks?: number | string | null;
  serviceFailures?: number | string | null;
  serviceSlowCalls?: number | string | null;
  ingestionFailures?: number | string | null;
  ingestionSlowCalls?: number | string | null;
  logFailures?: number | string | null;
  onlineWorkerInstances?: number | string | null;
  boundWorkerGroups?: number | string | null;
}

export interface OpsCenterQueueItemView extends BaseRecord {
  executionType?: string;
  workflowRunId?: EntityId;
  workflowDefinitionId?: EntityId;
  workflowVersionId?: EntityId;
  collectionTaskId?: EntityId;
  qualityTaskId?: EntityId;
  targetName?: string;
  nodeCode?: string;
  status?: string;
  workerGroupCode?: string;
  leaseOwner?: string;
  workerInstanceId?: string;
  scheduledFireTime?: string;
  queuedDurationMs?: number | string | null;
  scheduleDelayMs?: number | string | null;
  attempts?: number;
  maxRetries?: number;
}

export interface OpsCenterRunIncidentView extends BaseRecord {
  executionType?: string;
  workflowRunId?: EntityId;
  workflowDefinitionId?: EntityId;
  workflowVersionId?: EntityId;
  collectionTaskId?: EntityId;
  qualityTaskId?: EntityId;
  nodeCode?: string;
  status?: string;
  message?: string;
  workerGroupCode?: string;
  workerCode?: string;
  workerInstanceId?: string;
  workerPodName?: string;
  workerNodeName?: string;
  startedAt?: string;
  endedAt?: string;
  durationMs?: number | string | null;
  logStorageType?: string;
  logStatus?: string;
  logErrorSummary?: string;
  slow?: boolean;
  logAbnormal?: boolean;
}

export interface OpsCenterWorkerGroupView {
  workerGroupCode?: string;
  displayStatus?: string;
  boundToProject?: boolean;
  enabled?: boolean;
  onlineInstanceCount?: number;
  recentInstanceCount?: number;
  latestHeartbeatAt?: string;
  latestWorkerCode?: string;
  latestWorkerInstanceId?: string;
  latestPodName?: string;
  latestNodeName?: string;
  leaseExpiresAt?: string;
}

export interface OpsCenterServiceEventView {
  id?: EntityId;
  serviceId?: EntityId;
  serviceCode?: string;
  serviceName?: string;
  serviceStatus?: string;
  subscriptionId?: EntityId;
  subscriptionName?: string;
  requestMethod?: string;
  occurredAt?: string;
  durationMs?: number | string | null;
  success?: boolean;
  httpStatus?: number;
  errorCode?: string;
  errorMessage?: string;
  rowCount?: number | string | null;
  receivedCount?: number | string | null;
  writtenCount?: number | string | null;
  failedCount?: number | string | null;
  slow?: boolean;
}

export interface OpsCenterLogEventView extends BaseRecord {
  executionType?: string;
  workflowRunId?: EntityId;
  workflowDefinitionId?: EntityId;
  collectionTaskId?: EntityId;
  qualityTaskId?: EntityId;
  nodeCode?: string;
  status?: string;
  workerGroupCode?: string;
  workerInstanceId?: string;
  startedAt?: string;
  endedAt?: string;
  logStorageType?: string;
  logStatus?: string;
  logErrorSummary?: string;
}

export interface RunLogQuery {
  pageNo?: number;
  pageSizeBytes?: number;
}

export interface WorkflowRunSummary {
  tenantId?: string;
  projectId?: EntityId;
  workflowRunId?: EntityId;
  workflowDefinitionId?: EntityId;
  workflowVersionId?: EntityId;
  workflowName?: string;
  status?: string;
  startedAt?: string;
  endedAt?: string;
  durationMs?: number;
  totalNodes?: number;
  successNodes?: number;
  failedNodes?: number;
  runningNodes?: number;
  queuedNodes?: number;
  notRunNodes?: number;
  summaryMessage?: string;
}

export interface WorkflowNodeRun {
  runRecordId?: EntityId;
  workflowRunId?: EntityId;
  workflowDefinitionId?: EntityId;
  workflowName?: string;
  nodeCode?: string;
  nodeName?: string;
  nodeType?: NodeType | string;
  status?: string;
  workerGroupCode?: string;
  workerCode?: string;
  workerInstanceId?: string;
  message?: string;
  startedAt?: string;
  endedAt?: string;
  durationMs?: number;
  logAvailable?: boolean;
}

export interface WorkflowRunDetail extends WorkflowRunSummary {
  workflow?: WorkflowDefinitionView;
  nodeRuns: WorkflowNodeRun[];
}

export interface WorkflowRunListQuery {
  workflowDefinitionId?: EntityId;
  status?: string;
  startTime?: string;
  endTime?: string;
  pageNo?: number;
  pageSize?: number;
}

export interface StudioUser extends BaseRecord {
  username: string;
  displayName?: string;
  passwordHash?: string;
  enabled?: number | boolean;
  authSource?: string;
  externalAccount?: string;
}

export interface UserRegistrationRequestView extends BaseRecord {
  status?: string;
  username: string;
  displayName?: string;
  reason?: string;
  reviewComment?: string;
  reviewerUserId?: EntityId;
  reviewerUsername?: string;
  approvedUserId?: EntityId;
  approvedUsername?: string;
  reviewedAt?: string;
}

export interface NotificationView {
  id?: EntityId;
  category?: string;
  title?: string;
  content?: string;
  targetType?: string;
  targetId?: EntityId;
  targetPath?: string;
  targetTenantId?: string;
  targetProjectId?: EntityId;
  read?: boolean;
  readAt?: string;
  archivedAt?: string;
  createdAt?: string;
  payloadJson?: Record<string, unknown>;
}

export interface NotificationSnapshotView {
  unreadCount?: number;
  recentNotifications: NotificationView[];
}

export interface NotificationQueryRequest {
  pageNo?: number;
  pageSize?: number;
  unreadOnly?: boolean;
}

export interface FollowRequest {
  targetType: FollowTargetType | string;
  targetId: EntityId;
}

export interface FollowStatusView {
  targetType?: FollowTargetType | string;
  targetId?: EntityId;
  following?: boolean;
}

export interface SystemTenant extends BaseRecord {
  tenantCode: string;
  tenantName: string;
  description?: string;
  enabled?: boolean | number;
}

export interface SystemProject extends BaseRecord {
  projectCode: string;
  projectName: string;
  description?: string;
  enabled?: boolean | number;
  defaultProject?: boolean | number;
}

export interface SystemTenantMember extends BaseRecord {
  userId: EntityId;
  username?: string;
  displayName?: string;
  roleCode?: string;
  status?: string;
}

export interface SystemProjectMember extends BaseRecord {
  userId: EntityId;
  username?: string;
  displayName?: string;
  projectName?: string;
  roleCode?: string;
  status?: string;
}

export interface SystemProjectMemberRequest extends BaseRecord {
  userId: EntityId;
  username?: string;
  displayName?: string;
  projectName?: string;
  requestType?: string;
  status?: string;
  inviterUserId?: EntityId;
  inviterUsername?: string;
  reviewerUserId?: EntityId;
  reviewerUsername?: string;
  reason?: string;
  reviewComment?: string;
}

export interface SystemProjectWorker extends BaseRecord {
  workerGroupCode?: string;
  workerCode?: string;
  workerInstanceId?: string;
  workerKind?: string;
  hostName?: string;
  podName?: string;
  nodeName?: string;
  onlineInstanceCount?: number;
  recentInstanceCount?: number;
  status?: string;
  displayStatus?: string;
  lastHeartbeatAt?: string;
  latestHeartbeatAt?: string;
  boundToProject?: boolean;
  enabled?: boolean | number;
  instances?: SystemWorkerInstance[];
}

export interface SystemWorkerInstance {
  workerGroupCode?: string;
  workerCode?: string;
  workerInstanceId?: string;
  workerKind?: string;
  hostName?: string;
  podName?: string;
  nodeName?: string;
  status?: string;
  lastHeartbeatAt?: string;
  leaseExpiresAt?: string;
  online?: boolean;
}

export interface ResourceShare extends BaseRecord {
  sourceProjectId?: EntityId;
  targetProjectId?: EntityId;
  resourceType?: string;
  resourceId?: EntityId;
  sharedByUserId?: EntityId;
  enabled?: boolean | number;
}

export interface ShareResourceOption extends BaseRecord {
  resourceType?: string;
  label: string;
  name?: string;
  code?: string;
  status?: string;
}

export interface RoleEntity extends BaseRecord {
  code: string;
  name: string;
  description?: string;
}

export interface PermissionEntity extends BaseRecord {
  code: string;
  name: string;
  httpMethod?: string;
  pathPattern?: string;
}

export interface RuntimeModeResponse {
  mode: string;
  syncStrategy: string;
  offlineExecution: boolean;
}

export interface ExportProjectBundle {
  metaSchemas: MetadataSchemaDefinition[];
  workflows: WorkflowDefinitionView[];
}
