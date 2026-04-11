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
export type NodeType = "COLLECTION_TASK" | "DATA_SCRIPT" | "ETL_SINGLE" | "FUSION" | "CONSISTENCY" | "HTTP" | "SHELL";
export type EdgeCondition = "ON_SUCCESS" | "ON_FAILURE" | "ALWAYS";
export type QueryOperator = "EQ" | "LIKE" | "IN" | "GT" | "GE" | "LT" | "LE" | "BETWEEN";
export type RowMatchMode = "SAME_ITEM" | "ANY_ITEM";
export type StatisticType = "COUNT_BY_VALUE" | "SUMMARY" | "COUNT_BY_BUCKET";
export type StatisticsChartType = "TREND" | "BAR" | "PIE" | "TOPN";
export type CollectionTaskType = "SINGLE_TABLE" | "FUSION";
export type CollectionTaskStatus = "DRAFT" | "ONLINE";
export type ScriptType = "SQL" | "JAVA" | "PYTHON";
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

export interface PluginCatalogEntry extends BaseRecord {
  pluginName: string;
  pluginCategory: string;
  assetType: string;
  assetPath: string;
  executable?: number | boolean;
  metadata?: Record<string, unknown>;
  template?: Record<string, unknown>;
}

export interface SourceCapabilityEntry {
  typeCode: string;
  sourcePlugin: string;
  readable: boolean;
  writable: boolean;
  executable: boolean;
  readerPlugins: string[];
  writerPlugins: string[];
}

export interface CapabilityMatrix {
  executableSourceTypes: string[];
  executableTargetTypes?: string[];
  executableDatasourceTypes?: string[];
  sourceCapabilities?: SourceCapabilityEntry[];
}

export interface DataSourceDefinition extends BaseRecord {
  name: string;
  typeCode: string;
  schemaVersionId?: EntityId;
  enabled?: boolean;
  executable?: boolean;
  technicalMetadata: Record<string, unknown>;
  businessMetadata: Record<string, unknown>;
}

export interface ConnectionTestResult {
  success?: boolean;
  message?: string;
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
  modelKind?: ModelKind | string;
  groups: DataModelQueryGroup[];
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

export interface CollectionTaskSourceBinding {
  sourceAlias: string;
  datasourceId: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
}

export interface CollectionTaskTargetBinding {
  datasourceId: EntityId;
  datasourceName?: string;
  datasourceTypeCode?: string;
  modelId: EntityId;
  modelName?: string;
  modelPhysicalLocator?: string;
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
  description?: string;
  content: string;
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
  content: string;
  arguments?: Record<string, unknown>;
  maxRows?: number;
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
  nodeCode?: string;
  status?: string;
  leaseOwner?: string;
  attempts?: number;
  maxRetries?: number;
  payloadJson?: Record<string, unknown>;
}

export interface RunRecord extends BaseRecord {
  executionType?: string;
  workflowRunId?: EntityId;
  workflowDefinitionId?: EntityId;
  workflowVersionId?: EntityId;
  workflowName?: string;
  collectionTaskId?: EntityId;
  collectionTaskName?: string;
  nodeCode?: string;
  workerCode?: string;
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
  sizeBytes?: number;
  updatedAt?: string;
  charset?: string;
  downloadName?: string;
  contentType?: string;
  historicalFallback?: boolean;
}

export interface RunListResponse {
  queuedTasks: QueuedTask[];
  runRecords: RunRecord[];
}

export interface RunListQuery {
  collectionTaskId?: EntityId;
  workflowDefinitionId?: EntityId;
  startTime?: string;
  endTime?: string;
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
  workerCode?: string;
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
  workerCode: string;
  workerKind?: string;
  hostName?: string;
  status?: string;
  lastHeartbeatAt?: string;
  boundToProject?: boolean;
  enabled?: boolean | number;
}

export interface ResourceShare extends BaseRecord {
  sourceProjectId?: EntityId;
  targetProjectId?: EntityId;
  resourceType?: string;
  resourceId?: EntityId;
  sharedByUserId?: EntityId;
  enabled?: boolean | number;
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
