import type {
  AssistantInterfaceDefinition,
  DataModelDefinition,
} from "@studio/api-sdk";
import { resolveFieldsByModel } from "@/components/collection-task/collectionTaskEditorSupport";

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
  "models",
  "tables",
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
  execution?: Record<string, unknown>;
  executedAt: string;
}

export interface AssistantToolExecutionCandidate {
  interfaceCode: string;
  interfaceDefinition?: AssistantInterfaceDefinition;
  params: Record<string, unknown>;
}

export function createAssistantMessage(role: AssistantLogMessage["role"], content: string): AssistantLogMessage {
  return {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content,
  };
}

export function createToolResult(
  interfaceCode: string,
  params: Record<string, unknown>,
  data: unknown,
  ok = true,
  error?: string,
  execution?: Record<string, unknown>,
): AssistantToolResult {
  return {
    id: `${interfaceCode}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    interfaceCode,
    ok,
    params,
    data,
    error,
    execution,
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
    execution: summarizeToolExecution(result.execution),
    data: summarizeToolData(result.data),
  };
}

function summarizeToolExecution(execution: Record<string, unknown> | undefined) {
  if (!execution) {
    return undefined;
  }
  return cleanSummaryObject({
    schema: execution.schema,
    interfaceCode: execution.interfaceCode,
    path: execution.path,
    action: execution.action,
    resource: execution.resource,
    entrypointId: execution.entrypointId,
    executedBy: execution.executedBy,
    mutation: execution.mutation,
    requiresConfirmation: execution.requiresConfirmation,
    params: execution.params,
    effectiveParams: execution.effectiveParams,
    defaultedParams: execution.defaultedParams,
  });
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

export function resolveFields(model: DataModelDefinition | undefined) {
  return resolveFieldsByModel(model);
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
        pagination: summarizePagination(record, record.items.length),
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
  const pagination = summarizePagination(record);
  if (pagination) {
    summary.pagination = pagination;
  }
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

function summarizePagination(record: Record<string, unknown>, itemCount?: number) {
  const pageNo = firstFiniteNumber(record, ["pageNo", "pageNum", "page", "currentPage", "current"]);
  const pageSize = firstFiniteNumber(record, ["pageSize", "size", "limit"]);
  const total = firstFiniteNumber(record, ["total", "totalCount", "count"]);
  const explicitPages = firstFiniteNumber(record, ["pages", "totalPages", "pageCount"]);
  const pages = explicitPages ?? (total != null && pageSize ? Math.max(1, Math.ceil(total / pageSize)) : undefined);
  const explicitHasMore = firstBoolean(record, ["hasMore", "hasNext"]);
  let hasMore = explicitHasMore;
  if (hasMore === undefined && pageNo != null && pages != null) {
    hasMore = pageNo < pages;
  }
  if (hasMore === undefined && pageNo != null && pageSize != null && total != null) {
    hasMore = pageNo * pageSize < total;
  }
  const explicitNextPage = firstFiniteNumber(record, ["nextPage", "nextPageNo", "nextPageNum"]);
  const nextPage = explicitNextPage ?? (hasMore && pageNo != null ? pageNo + 1 : undefined);
  const pagination = cleanSummaryObject({
    pageNo,
    pageSize,
    total,
    pages,
    hasMore,
    nextPage,
    cursor: record.cursor,
    nextCursor: record.nextCursor,
    itemCount,
  });
  return Object.keys(pagination).length ? pagination : undefined;
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
    "pageNo",
    "pageNum",
    "page",
    "currentPage",
    "current",
    "pageSize",
    "size",
    "total",
    "totalCount",
    "count",
    "pages",
    "totalPages",
    "pageCount",
    "hasMore",
    "hasNext",
    "nextPage",
    "nextPageNo",
    "nextPageNum",
    "cursor",
    "nextCursor",
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

function firstFiniteNumber(record: Record<string, unknown>, keys: string[]) {
  for (const key of keys) {
    const value = record[key];
    if (value === undefined || value === null || value === "") {
      continue;
    }
    const numberValue = Number(value);
    if (Number.isFinite(numberValue)) {
      return numberValue;
    }
  }
  return undefined;
}

function firstBoolean(record: Record<string, unknown>, keys: string[]) {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "boolean") {
      return value;
    }
    if (typeof value === "string") {
      const normalized = value.trim().toLowerCase();
      if (normalized === "true") {
        return true;
      }
      if (normalized === "false") {
        return false;
      }
    }
  }
  return undefined;
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
