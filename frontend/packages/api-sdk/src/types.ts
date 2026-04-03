export interface Result<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
  timestamp: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  username: string;
  token: string;
}

export interface BaseRecord {
  id?: number;
  tenantId?: string;
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
export type ModelKind = "TABLE" | "FILE" | "TOPIC" | "MEASUREMENT" | "DATASET";
export type NodeType = "ETL_SINGLE" | "FUSION" | "CONSISTENCY" | "HTTP" | "SHELL";
export type EdgeCondition = "ON_SUCCESS" | "ON_FAILURE" | "ALWAYS";

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
}

export interface MetadataSchemaDefinition extends BaseRecord {
  schemaCode: string;
  schemaName: string;
  objectType: string;
  typeCode: string;
  currentVersionId?: number;
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

export interface CapabilityMatrix {
  executableSourceTypes: string[];
  plugins: PluginCatalogEntry[];
}

export interface DataSourceDefinition extends BaseRecord {
  name: string;
  typeCode: string;
  schemaVersionId?: number;
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
  datasourceId: number;
  name: string;
  modelKind?: ModelKind;
  physicalLocator: string;
  schemaVersionId?: number;
  technicalMetadata: Record<string, unknown>;
  businessMetadata: Record<string, unknown>;
}

export interface ModelDiscoveryResult {
  datasourceType?: string;
  models: DataModelDefinition[];
  [key: string]: unknown;
}

export interface TransformerBinding {
  transformerCode: string;
  parameters: Record<string, unknown>;
}

export interface FieldMappingDefinition {
  sourceField?: string;
  targetField?: string;
  expression?: string;
  transformers: TransformerBinding[];
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
  versionId?: number;
  versionNumber?: number;
  published?: boolean;
  schedule?: WorkflowScheduleDefinition;
  nodes: WorkflowNodeDefinition[];
  edges: WorkflowEdgeDefinition[];
}

export interface WorkflowSaveRequest {
  definitionId?: number;
  code: string;
  name: string;
  schedule?: WorkflowScheduleDefinition;
  nodes: WorkflowNodeDefinition[];
  edges: WorkflowEdgeDefinition[];
}

export interface QueuedTask extends BaseRecord {
  workflowDefinitionId?: number;
  workflowVersionId?: number;
  nodeCode?: string;
  status?: string;
  attempts?: number;
  maxRetries?: number;
  payloadJson?: Record<string, unknown>;
}

export interface RunRecord extends BaseRecord {
  workflowDefinitionId?: number;
  workflowVersionId?: number;
  nodeCode?: string;
  workerCode?: string;
  status?: string;
  message?: string;
}

export interface RunListResponse {
  queuedTasks: QueuedTask[];
  runRecords: RunRecord[];
}

export interface StudioUser extends BaseRecord {
  username: string;
  displayName?: string;
  passwordHash?: string;
  enabled?: number | boolean;
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
  catalog: PluginCatalogEntry[];
  metaSchemas: MetadataSchemaDefinition[];
  workflows: WorkflowDefinitionView[];
}
