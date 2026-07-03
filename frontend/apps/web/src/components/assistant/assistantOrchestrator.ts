import type {
  AssistantInterfaceDefinition,
  CapabilityMatrix,
  CollectionTaskSaveRequest,
  DataModelDatasourceOptionView,
  DataModelDefinition,
  DataSourceOptionView,
  EntityId,
  FieldMappingDefinition,
  PluginRuntimeOptionSchemaView,
} from "@studio/api-sdk";
import { mergeRuntimeDefaults, resolveFieldsByModel } from "@/components/collection-task/collectionTaskEditorSupport";

export const SINGLE_TABLE_CAPABILITY_CODE = "collection.singleTable.create";
export const SINGLE_TABLE_SOURCE_ALIAS = "src1";
export const DEFAULT_COLLECTION_MODE = "FULL";
const MAX_PLANNER_RESULT_ITEMS = 20;
const MAX_PLANNER_OBJECT_FIELDS = 32;
const MAX_PLANNER_NESTED_ITEMS = 80;
const MAX_PLANNER_SAMPLE_ROWS = 10;
const SUMMARY_ARRAY_KEYS = [
  "fields",
  "columns",
  "fieldDefinitions",
  "columnDefinitions",
  "requestParams",
  "responseParams",
  "sourceFields",
  "targetFields",
  "mappings",
  "fieldMappings",
  "rows",
  "records",
  "sampleRows",
  "previewRows",
  "resultRows",
];
const SUMMARY_METADATA_KEYS = [
  "technicalMetadata",
  "businessMetadata",
  "metadata",
  "schema",
  "tableSchema",
];
const SUMMARY_FIELD_KEYS = [
  "fields",
  "columns",
  "fieldDefinitions",
  "columnDefinitions",
  "sourceFields",
  "targetFields",
];
const SUMMARY_ROW_KEYS = [
  "rows",
  "records",
  "sampleRows",
  "previewRows",
  "resultRows",
];

export interface AssistantLogMessage {
  id: string;
  role: "user" | "assistant" | "system";
  content: string;
  rawContent?: string;
  streaming?: boolean;
  controls?: AssistantChatControl[];
}

export interface AssistantChatControlOption {
  label: string;
  value: string;
  description?: string;
}

export interface AssistantChatControl {
  id: string;
  type: "choices" | "select" | "text" | "textarea" | "confirm";
  title: string;
  detail?: string;
  interfaceCode?: string;
  paramKey?: string;
  placeholder?: string;
  options: AssistantChatControlOption[];
}

export interface AssistantToolResult {
  id: string;
  interfaceCode: string;
  ok: boolean;
  params: Record<string, unknown>;
  data?: unknown;
  error?: string;
  executedAt: string;
}

export interface AssistantToolExecutionCandidate {
  interfaceCode: string;
  interfaceDefinition?: AssistantInterfaceDefinition;
  params: Record<string, unknown>;
}

export interface CollectionIntentTerms {
  sourcePhrase: string;
  targetPhrase: string;
  sourceTokens: string[];
  targetTokens: string[];
}

export interface CandidateMatch<T> {
  item: T;
  score: number;
}

export interface CandidateResolution<T> {
  selected?: T;
  ranked: CandidateMatch<T>[];
  ambiguous: boolean;
  reason: "empty" | "single" | "matched" | "ambiguous" | "missing";
}

export interface AssistantFieldMappingRow {
  targetField: string;
  sourceField: string;
  matchedAutomatically: boolean;
  skipped: boolean;
}

export interface SingleTableAssistantDraft {
  name: string;
  sourceDatasourceId: string;
  sourceModelId: string;
  targetDatasourceId: string;
  targetModelId: string;
  collectionMode: string;
  fieldMappings: AssistantFieldMappingRow[];
}

export interface SingleTableCollectedInputs {
  name?: string;
  source?: {
    datasourceId?: string;
    modelId?: string;
  };
  target?: {
    datasourceId?: string;
    modelId?: string;
  };
  fieldMappings?: FieldMappingDefinition[];
  executionOptions?: {
    collectionMode?: string;
  };
}

export interface RuntimeSchemaState {
  reader?: PluginRuntimeOptionSchemaView;
  writer?: PluginRuntimeOptionSchemaView;
}

export interface BuildPayloadContext {
  sourceDatasource?: DataSourceOptionView;
  targetDatasource?: DataSourceOptionView;
  sourceModel?: DataModelDefinition | DataModelDatasourceOptionView;
  targetModel?: DataModelDefinition | DataModelDatasourceOptionView;
  runtimeSchemas?: RuntimeSchemaState;
}

export function createAssistantMessage(role: AssistantLogMessage["role"], content: string): AssistantLogMessage {
  return {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content,
  };
}

export function createEmptySingleTableDraft(): SingleTableAssistantDraft {
  return {
    name: "",
    sourceDatasourceId: "",
    sourceModelId: "",
    targetDatasourceId: "",
    targetModelId: "",
    collectionMode: DEFAULT_COLLECTION_MODE,
    fieldMappings: [],
  };
}

export function collectSingleTableInputs(draft: SingleTableAssistantDraft): SingleTableCollectedInputs {
  const inputs: SingleTableCollectedInputs = {
    executionOptions: {
      collectionMode: draft.collectionMode || DEFAULT_COLLECTION_MODE,
    },
  };
  if (draft.name.trim()) {
    inputs.name = draft.name.trim();
  }
  if (draft.sourceDatasourceId || draft.sourceModelId) {
    inputs.source = {};
    if (draft.sourceDatasourceId) {
      inputs.source.datasourceId = draft.sourceDatasourceId;
    }
    if (draft.sourceModelId) {
      inputs.source.modelId = draft.sourceModelId;
    }
  }
  if (draft.targetDatasourceId || draft.targetModelId) {
    inputs.target = {};
    if (draft.targetDatasourceId) {
      inputs.target.datasourceId = draft.targetDatasourceId;
    }
    if (draft.targetModelId) {
      inputs.target.modelId = draft.targetModelId;
    }
  }
  const mappings = toFieldMappings(draft.fieldMappings);
  if (mappings.length) {
    inputs.fieldMappings = mappings;
  }
  return inputs;
}

export function createToolResult(
  interfaceCode: string,
  params: Record<string, unknown>,
  data: unknown,
  ok = true,
  error?: string,
): AssistantToolResult {
  return {
    id: `${interfaceCode}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    interfaceCode,
    ok,
    params,
    data,
    error,
    executedAt: new Date().toISOString(),
  };
}

export function summarizeToolResultForPlanner(result: AssistantToolResult) {
  return {
    id: result.id,
    interfaceCode: result.interfaceCode,
    ok: result.ok,
    params: result.params,
    error: result.error,
    data: summarizeToolData(result.data),
  };
}

export function latestToolData<T>(results: AssistantToolResult[], interfaceCode: string, params?: Record<string, unknown>) {
  for (let index = results.length - 1; index >= 0; index -= 1) {
    const result = results[index];
    if (result.interfaceCode !== interfaceCode || !result.ok) {
      continue;
    }
    if (params && !paramsMatch(result.params, params)) {
      continue;
    }
    return result.data as T;
  }
  return undefined;
}

export function isMutationInterface(interfaceDefinition: AssistantInterfaceDefinition | undefined) {
  return Boolean(interfaceDefinition?.mutation);
}

export function findInterfaceDefinition(
  interfaces: AssistantInterfaceDefinition[] | undefined,
  interfaceCode: string,
) {
  return interfaces?.find((item) => item.interfaceCode === interfaceCode);
}

export function extractCollectionIntentTerms(message: string): CollectionIntentTerms {
  const text = String(message ?? "").trim();
  const connector = /(.+?)(?:采集到|同步到|同步至|写入到|抽取到|写入|->|=>|到)(.+)/i.exec(text);
  const sourcePhrase = cleanIntentPhrase(connector?.[1] ?? text);
  const targetPhrase = cleanIntentPhrase(connector?.[2] ?? "");
  return {
    sourcePhrase,
    targetPhrase,
    sourceTokens: tokenizeIntentPhrase(sourcePhrase),
    targetTokens: tokenizeIntentPhrase(targetPhrase),
  };
}

export function inferSingleTableTaskName(message: string, terms: CollectionIntentTerms) {
  const source = shortPhrase(terms.sourcePhrase);
  const target = shortPhrase(terms.targetPhrase);
  if (source && target) {
    return `${source}到${target}采集`;
  }
  const compact = shortPhrase(cleanIntentPhrase(message));
  return compact ? `${compact}采集` : "AI 单表采集任务";
}

export function filterDatasourceCandidates(
  datasources: DataSourceOptionView[],
  capabilityMatrix: CapabilityMatrix | null | undefined,
  role: "source" | "target",
) {
  const allowedTypes = resolveAllowedDatasourceTypes(capabilityMatrix, role);
  return datasources.filter((item) => {
    if (item.enabled === false) {
      return false;
    }
    if (!allowedTypes.size) {
      return true;
    }
    return allowedTypes.has(normalizeTypeCode(item.typeCode));
  });
}

export function resolveCandidate<T>(items: T[], score: (item: T) => number): CandidateResolution<T> {
  const ranked = items
    .map((item) => ({ item, score: score(item) }))
    .sort((left, right) => right.score - left.score);
  if (ranked.length === 0) {
    return { ranked, ambiguous: false, reason: "empty" };
  }
  if (ranked.length === 1) {
    return { selected: ranked[0].item, ranked, ambiguous: false, reason: "single" };
  }
  const matched = ranked.filter((item) => item.score > 0);
  if (!matched.length) {
    return { ranked, ambiguous: false, reason: "missing" };
  }
  const [best, second] = matched;
  if (!second || best.score >= second.score + 4) {
    return { selected: best.item, ranked: matched, ambiguous: false, reason: "matched" };
  }
  return { ranked: matched, ambiguous: true, reason: "ambiguous" };
}

export function scoreDatasourceCandidate(datasource: DataSourceOptionView, phrase: string, tokens: string[]) {
  return scoreByCandidateTexts([
    datasource.name,
    datasource.typeCode,
    datasource.id,
  ], phrase, tokens);
}

export function scoreModelCandidate(model: DataModelDatasourceOptionView, phrase: string, tokens: string[]) {
  return scoreByCandidateTexts([
    model.name,
    model.physicalLocator,
    model.id,
  ], phrase, tokens);
}

export function createFieldMappingRows(sourceFields: string[], targetFields: string[]): AssistantFieldMappingRow[] {
  const sourceByLowercase = new Map(sourceFields.map((field) => [field.toLowerCase(), field]));
  return targetFields.map((targetField) => {
    const exactMatch = sourceFields.find((field) => field === targetField);
    const matchedSource = exactMatch ?? sourceByLowercase.get(targetField.toLowerCase()) ?? "";
    return {
      targetField,
      sourceField: matchedSource,
      matchedAutomatically: Boolean(matchedSource),
      skipped: false,
    };
  });
}

export function toFieldMappings(rows: AssistantFieldMappingRow[]): FieldMappingDefinition[] {
  return rows
    .filter((row) => !row.skipped && row.sourceField && row.targetField)
    .map((row) => ({
      sourceAlias: SINGLE_TABLE_SOURCE_ALIAS,
      sourceField: row.sourceField,
      targetField: row.targetField,
      transformers: [],
    }));
}

export function unresolvedMappingRows(rows: AssistantFieldMappingRow[]) {
  return rows.filter((row) => !row.skipped && (!row.sourceField || !row.targetField));
}

export function validateSingleTableDraft(draft: SingleTableAssistantDraft, sourceFields: string[], targetFields: string[]) {
  const errors: string[] = [];
  if (!draft.name.trim()) {
    errors.push("请填写任务名");
  }
  if (!draft.sourceDatasourceId) {
    errors.push("请选择源数据源");
  }
  if (!draft.sourceModelId) {
    errors.push("请选择源模型/表");
  }
  if (!draft.targetDatasourceId) {
    errors.push("请选择目标数据源");
  }
  if (!draft.targetModelId) {
    errors.push("请选择目标模型/表");
  }
  if (!sourceFields.length) {
    errors.push("源模型没有可映射字段");
  }
  if (!targetFields.length) {
    errors.push("目标模型没有可映射字段");
  }
  const unresolvedRows = unresolvedMappingRows(draft.fieldMappings);
  if (unresolvedRows.length) {
    errors.push("存在未确认的字段映射");
  }
  if (toFieldMappings(draft.fieldMappings).length < 1) {
    errors.push("至少需要保留一个字段映射");
  }
  return errors;
}

export function buildSingleTablePayload(draft: SingleTableAssistantDraft, context: BuildPayloadContext): CollectionTaskSaveRequest {
  const readerOptions = mergeRuntimeDefaults({}, context.runtimeSchemas?.reader?.fields ?? []);
  const writerOptions = mergeRuntimeDefaults({}, context.runtimeSchemas?.writer?.fields ?? []);
  return {
    name: draft.name.trim(),
    sourceBindings: [
      {
        sourceAlias: SINGLE_TABLE_SOURCE_ALIAS,
        datasourceId: draft.sourceDatasourceId as EntityId,
        datasourceName: context.sourceDatasource?.name,
        datasourceTypeCode: context.sourceDatasource?.typeCode,
        modelId: draft.sourceModelId as EntityId,
        modelName: context.sourceModel?.name,
        modelPhysicalLocator: context.sourceModel?.physicalLocator,
        readerOptions,
        incremental: {
          enabled: false,
          incrModel: ">",
        },
      },
    ],
    targetBinding: {
      datasourceId: draft.targetDatasourceId as EntityId,
      datasourceName: context.targetDatasource?.name,
      datasourceTypeCode: context.targetDatasource?.typeCode,
      modelId: draft.targetModelId as EntityId,
      modelName: context.targetModel?.name,
      modelPhysicalLocator: context.targetModel?.physicalLocator,
      writerOptions,
    },
    fieldMappings: toFieldMappings(draft.fieldMappings),
    executionOptions: {
      collectionMode: DEFAULT_COLLECTION_MODE,
    },
    schedule: {
      enabled: false,
    },
  };
}

export function resolveFields(model: DataModelDefinition | undefined) {
  return resolveFieldsByModel(model);
}

export function compactText(value: unknown) {
  return String(value ?? "")
    .trim()
    .toLowerCase()
    .replace(/[!"#$%&'()*+,./:;<=>?@[\\\]^`{|}~\s_\-，。；：、（）【】《》“”‘’]/g, "");
}

function resolveAllowedDatasourceTypes(capabilityMatrix: CapabilityMatrix | null | undefined, role: "source" | "target") {
  const sourceCapabilities = capabilityMatrix?.sourceCapabilities ?? [];
  const typesFromRows = sourceCapabilities
    .filter((item) => (role === "source" ? item.readable : item.writable))
    .map((item) => normalizeTypeCode(item.typeCode))
    .filter(Boolean);
  if (typesFromRows.length) {
    return new Set(typesFromRows);
  }
  const fallbackTypes = role === "source"
    ? capabilityMatrix?.executableSourceTypes ?? capabilityMatrix?.executableDatasourceTypes ?? []
    : capabilityMatrix?.executableTargetTypes ?? capabilityMatrix?.executableDatasourceTypes ?? [];
  return new Set(fallbackTypes.map(normalizeTypeCode).filter(Boolean));
}

function normalizeTypeCode(value: unknown) {
  return String(value ?? "").trim().toLowerCase();
}

function cleanIntentPhrase(value: unknown) {
  return String(value ?? "")
    .replace(/我想|帮我|请|需要|创建|新建|配置|一个|一条|把|将|从|全量|增量|单表|采集任务|同步任务|采集|同步|任务/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function tokenizeIntentPhrase(value: string) {
  const cleaned = cleanIntentPhrase(value);
  const tokens = [
    cleaned,
    compactText(cleaned),
    ...cleaned.split(/[\s,，。；;:：/\\|]+|的/g),
  ]
    .map((item) => String(item ?? "").trim())
    .filter(Boolean)
    .flatMap((item) => [item, compactText(item)])
    .filter((item) => item.length > 0);
  return Array.from(new Set(tokens));
}

function shortPhrase(value: string) {
  return cleanIntentPhrase(value)
    .replace(/[，。；;]+$/g, "")
    .slice(0, 48)
    .trim();
}

function scoreByCandidateTexts(candidateTexts: unknown[], phrase: string, tokens: string[]) {
  const phraseText = compactText(phrase);
  const tokenTexts = tokens.map(compactText).filter(Boolean);
  const candidateParts = candidateTexts.map(compactText).filter(Boolean);
  let score = 0;
  for (const candidate of candidateParts) {
    if (!candidate) {
      continue;
    }
    if (phraseText && phraseText.includes(candidate)) {
      score += 12;
    }
    if (phraseText && candidate.includes(phraseText)) {
      score += 8;
    }
    for (const token of tokenTexts) {
      if (!token) {
        continue;
      }
      if (candidate === token) {
        score += 10;
      } else if (candidate.includes(token)) {
        score += 5;
      } else if (token.includes(candidate)) {
        score += 4;
      }
    }
  }
  return score;
}

function paramsMatch(actual: Record<string, unknown>, expected: Record<string, unknown>) {
  return Object.entries(expected).every(([key, value]) => String(actual[key] ?? "") === String(value ?? ""));
}

function summarizeToolData(data: unknown): unknown {
  if (Array.isArray(data)) {
    return {
      kind: "array",
      count: data.length,
      items: data.slice(0, MAX_PLANNER_RESULT_ITEMS).map((item) => summarizeToolItem(item)),
    };
  }
  if (data && typeof data === "object") {
    const record = data as Record<string, unknown>;
    if (Array.isArray(record.items)) {
      return {
        ...pickSummaryFields(record),
        items: record.items.slice(0, MAX_PLANNER_RESULT_ITEMS).map((item) => summarizeToolItem(item)),
        total: record.total ?? record.totalCount ?? record.items.length,
      };
    }
    return summarizeToolItem(record);
  }
  return data;
}

function summarizeToolItem(item: unknown): unknown {
  if (!item || typeof item !== "object") {
    return item;
  }
  const record = item as Record<string, unknown>;
  const summary = pickSummaryFields(record);
  appendFieldSummary(summary, record);
  appendRowSummary(summary, record);
  for (const key of SUMMARY_ARRAY_KEYS) {
    const value = record[key];
    if (Array.isArray(value)) {
      summary[key] = summarizeArrayValue(key, value);
    }
  }
  for (const key of SUMMARY_METADATA_KEYS) {
    const value = record[key];
    if (value && typeof value === "object" && !Array.isArray(value)) {
      summary[key] = summarizeMetadata(value as Record<string, unknown>);
    }
  }
  const sourceCapabilities = record.sourceCapabilities;
  if (Array.isArray(sourceCapabilities)) {
    summary.sourceCapabilities = sourceCapabilities.slice(0, MAX_PLANNER_RESULT_ITEMS).map((capability) => pickSummaryFields(capability as Record<string, unknown>));
  }
  return summary;
}

function summarizeField(field: unknown) {
  if (!field || typeof field !== "object") {
    return field;
  }
  const record = field as Record<string, unknown>;
  return cleanSummaryObject({
    name: record.name ?? record.fieldName ?? record.columnName ?? record.column,
    cnName: record.cnName ?? record.label ?? record.displayName,
    type: record.type ?? record.dataType ?? record.fieldType ?? record.valueType,
    size: record.size ?? record.length ?? record.precision,
    scale: record.scale,
    nullable: record.nullable,
    required: record.required,
    primaryKey: record.primaryKey,
    autoIncrement: record.autoIncrement,
    partitionColumn: record.partitionColumn,
    defaultValue: record.defaultValue,
    remarks: record.remarks ?? record.comment ?? record.description,
  });
}

function pickSummaryFields(record: Record<string, unknown>) {
  const keys = [
    "id",
    "name",
    "code",
    "typeCode",
    "datasourceId",
    "modelKind",
    "physicalLocator",
    "enabled",
    "readable",
    "writable",
    "status",
    "description",
    "remark",
    "remarks",
    "createdAt",
    "updatedAt",
    "sourceType",
    "physicalName",
    "catalog",
    "schema",
    "tableType",
    "columnCount",
    "partitioned",
    "externalTable",
  ];
  const summary: Record<string, unknown> = {};
  for (const key of keys) {
    if (record[key] !== undefined) {
      summary[key] = record[key];
    }
  }
  if (Object.keys(summary).length < 4) {
    for (const [key, value] of Object.entries(record)) {
      if (summary[key] !== undefined || !isSummarizablePrimitive(value)) {
        continue;
      }
      summary[key] = value;
      if (Object.keys(summary).length >= MAX_PLANNER_OBJECT_FIELDS) {
        break;
      }
    }
  }
  return summary;
}

function summarizeArrayValue(key: string, value: unknown[]) {
  const items = value.slice(0, MAX_PLANNER_NESTED_ITEMS);
  if (isFieldLikeArray(key, items)) {
    return items.map((field) => summarizeField(field));
  }
  return items.map((item) => summarizeToolItem(item));
}

function summarizeMetadata(metadata: Record<string, unknown>) {
  const summary = pickSummaryFields(metadata);
  appendFieldSummary(summary, metadata);
  appendRowSummary(summary, metadata);
  for (const key of SUMMARY_ARRAY_KEYS) {
    const value = metadata[key];
    if (Array.isArray(value)) {
      summary[key] = summarizeArrayValue(key, value);
    }
  }
  return summary;
}

function isFieldLikeArray(key: string, items: unknown[]) {
  if (/field|column/i.test(key)) {
    return true;
  }
  return items.some((item) => {
    if (!item || typeof item !== "object") {
      return false;
    }
    const record = item as Record<string, unknown>;
    return record.name != null || record.fieldName != null || record.columnName != null || record.type != null || record.dataType != null;
  });
}

function appendFieldSummary(summary: Record<string, unknown>, record: Record<string, unknown>) {
  const fields = resolveFieldLikeArray(record);
  if (!fields.length) {
    return;
  }
  const names = fields
    .map(fieldName)
    .filter(Boolean);
  summary.fieldSummary = cleanSummaryObject({
    count: fields.length,
    names: names.slice(0, MAX_PLANNER_NESTED_ITEMS),
    fields: fields.slice(0, MAX_PLANNER_NESTED_ITEMS).map((field: unknown) => summarizeField(field)),
  });
}

function appendRowSummary(summary: Record<string, unknown>, record: Record<string, unknown>) {
  const rows = resolveRowLikeArray(record);
  if (!rows.length) {
    return;
  }
  summary.rowSummary = cleanSummaryObject({
    count: rows.length,
    columns: rowColumnNames(rows).slice(0, MAX_PLANNER_OBJECT_FIELDS),
    sampleRows: rows.slice(0, MAX_PLANNER_SAMPLE_ROWS).map((row) => summarizeToolItem(row)),
  });
}

function resolveFieldLikeArray(record: Record<string, unknown>): unknown[] {
  for (const key of SUMMARY_FIELD_KEYS) {
    const value = record[key];
    if (Array.isArray(value) && isFieldLikeArray(key, value)) {
      return value;
    }
  }
  for (const key of SUMMARY_METADATA_KEYS) {
    const value = record[key];
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      continue;
    }
    const nestedFields: unknown[] = resolveFieldLikeArray(value as Record<string, unknown>);
    if (nestedFields.length) {
      return nestedFields;
    }
  }
  return [];
}

function resolveRowLikeArray(record: Record<string, unknown>) {
  for (const key of SUMMARY_ROW_KEYS) {
    const value = record[key];
    if (Array.isArray(value) && isRowLikeArray(value)) {
      return value;
    }
  }
  return [];
}

function isRowLikeArray(items: unknown[]) {
  return items.some((item) => {
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      return false;
    }
    return Object.values(item as Record<string, unknown>).some(isSummarizablePrimitive);
  });
}

function rowColumnNames(rows: unknown[]) {
  const names = new Set<string>();
  for (const row of rows) {
    if (!row || typeof row !== "object" || Array.isArray(row)) {
      continue;
    }
    for (const key of Object.keys(row as Record<string, unknown>)) {
      names.add(key);
      if (names.size >= MAX_PLANNER_OBJECT_FIELDS) {
        return Array.from(names);
      }
    }
  }
  return Array.from(names);
}

function fieldName(field: unknown) {
  if (typeof field === "string") {
    return field;
  }
  if (!field || typeof field !== "object") {
    return "";
  }
  const record = field as Record<string, unknown>;
  return String(record.name ?? record.fieldName ?? record.columnName ?? record.column ?? "").trim();
}

function cleanSummaryObject(value: Record<string, unknown>) {
  const result: Record<string, unknown> = {};
  for (const [key, item] of Object.entries(value)) {
    if (item !== undefined && item !== null && item !== "") {
      result[key] = item;
    }
  }
  return result;
}

function isSummarizablePrimitive(value: unknown) {
  return value == null || ["string", "number", "boolean"].includes(typeof value);
}
