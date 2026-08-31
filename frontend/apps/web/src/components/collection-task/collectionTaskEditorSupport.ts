import type {
  CollectionIncrementalDefinition,
  CollectionTaskDefinitionView,
  CollectionTaskExecutionMode,
  CollectionTaskSaveRequest,
  CollectionTaskSourceBinding,
  CollectionTaskTargetBinding,
  CollectionTaskStreamingOptions,
  DataModelDefinition,
  MetadataFieldDefinition,
} from "@studio/api-sdk";

export interface CollectionTaskEditorForm extends Omit<CollectionTaskSaveRequest, "schedule"> {
  schedule: NonNullable<CollectionTaskSaveRequest["schedule"]>;
  executionMode: CollectionTaskExecutionMode;
  streamingOptions: CollectionTaskStreamingOptions;
  desiredState?: "RUNNING" | "STOPPED";
  observedState?: "STARTING" | "RUNNING" | "STOPPING" | "STOPPED" | "RECOVERING" | "FAILED";
}

export type RuntimeOptionRole = "reader" | "writer";

export const legacyStreamingReaderOptionKeys = [
  "groupId",
  "offsetReset",
  "resetOffset",
  "pollTimeoutMs",
] as const;

const STREAMING_ACTIVE_STATES = new Set(["STARTING", "RUNNING", "STOPPING", "RECOVERING"]);

export function isStreamingTaskConfigurationLocked(
  executionMode: unknown,
  desiredState: unknown,
  observedState: unknown,
) {
  if (String(executionMode ?? "").toUpperCase() !== "STREAMING") {
    return false;
  }
  return String(desiredState ?? "").toUpperCase() === "RUNNING"
    || STREAMING_ACTIVE_STATES.has(String(observedState ?? "").toUpperCase());
}

export const fileReaderDatasourceTypes = new Set(["ftp", "sftp", "minio"]);
export const fileReaderDynamicFunctionFields = ["rootPath", "partition"];
export const httpReaderDynamicFunctionFields = ["header", "params", "requestBody"];
export const httpWriterDynamicFunctionFields = ["header", "params", "requestBody"];
export const fileWriterDatasourceTypes = new Set(["ftp", "sftp", "minio"]);
export const fileWriterDynamicFunctionFields = ["rootPath", "fileName", "efile.dataTime", "efile.planDate"];

export function createDefaultCollectionTaskForm(): CollectionTaskEditorForm {
  return {
    name: "",
    runtimeClusterId: "",
    sourceBindings: [
      {
        sourceAlias: "src1",
        datasourceId: "",
        modelId: "",
        readerOptions: {},
        incremental: {
          enabled: false,
          incrModel: ">",
        },
      },
    ],
    targetBinding: {
      datasourceId: "",
      modelId: "",
      writerOptions: {},
    },
    fieldMappings: [],
    executionOptions: {
      collectionMode: "FULL",
      joinKeys: [],
      joinType: "LEFT",
    },
    executionMode: "BATCH",
    streamingOptions: {
      maxBatchRecords: 1000,
      maxBatchBytes: 16 * 1024 * 1024,
      batchRetryCount: 3,
      stopTimeoutMs: 60000,
      maxConsecutiveFailures: 10,
      retryInitialDelayMs: 5000,
      retryMaxDelayMs: 300000,
    },
    schedule: {
      enabled: false,
      cronExpression: "0 */30 * * * ?",
      timezone: "Asia/Shanghai",
    },
  };
}

export function stripLegacyStreamingOptions(
  options: CollectionTaskStreamingOptions | undefined,
): CollectionTaskStreamingOptions {
  const result = { ...(options ?? {}) } as CollectionTaskStreamingOptions & Record<string, unknown>;
  legacyStreamingReaderOptionKeys.forEach((key) => delete result[key]);
  return result;
}

export function migrateLegacyStreamingReaderOptions(
  sourceBindings: CollectionTaskSourceBinding[],
  options: CollectionTaskStreamingOptions | undefined,
) {
  if (!sourceBindings.length || !options) {
    return;
  }
  const source = sourceBindings[0];
  const readerOptions = { ...(source.readerOptions ?? {}) };
  legacyStreamingReaderOptionKeys.forEach((key) => {
    const value = (options as Record<string, unknown>)[key];
    if (value !== undefined && value !== null && String(value).trim() !== ""
      && (readerOptions[key] === undefined || String(readerOptions[key]).trim() === "")) {
      readerOptions[key] = value;
    }
  });
  source.readerOptions = readerOptions;
}

export function resolveFieldsByModel(model: DataModelDefinition | undefined) {
  const columns = model?.technicalMetadata?.columns;
  if (!Array.isArray(columns)) {
    return [];
  }
  return columns
    .map((item) => (typeof item === "object" && item ? String((item as Record<string, unknown>).name ?? "") : ""))
    .filter(Boolean);
}

export function resolvePrimaryKeyFieldsByModel(model: DataModelDefinition | undefined) {
  const columns = model?.technicalMetadata?.columns;
  if (!Array.isArray(columns)) {
    return [];
  }
  return columns
    .filter((item) => typeof item === "object" && item && isTruthyMetadataFlag((item as Record<string, unknown>).primaryKey))
    .map((item) => String((item as Record<string, unknown>).name ?? ""))
    .filter(Boolean);
}

export function inferCollectionMode(task: CollectionTaskDefinitionView, executionOptions: Record<string, unknown>) {
  const explicitMode = String(executionOptions.collectionMode ?? "").trim();
  if (explicitMode) {
    return explicitMode;
  }
  return (task.sourceBindings ?? []).some((item) => item.incremental?.enabled) ? "INCREMENTAL" : "FULL";
}

export function normalizeSourceBinding(binding: CollectionTaskSourceBinding, mode: string, defaultAlias = "src1"): CollectionTaskSourceBinding {
  return {
    ...binding,
    sourceAlias: binding.sourceAlias?.trim() || defaultAlias,
    readerOptions: { ...(binding.readerOptions ?? {}) },
    incremental: normalizeIncremental(binding.incremental, mode),
  };
}

export function normalizeTargetBinding(binding: CollectionTaskTargetBinding): CollectionTaskTargetBinding {
  const writerOptions = { ...(binding.writerOptions ?? {}) };
  if (Array.isArray(writerOptions.pkColumn)) {
    const pkColumn = writerOptions.pkColumn.map((item) => String(item).trim()).filter(Boolean);
    if (pkColumn.length) {
      writerOptions.pkColumn = pkColumn;
    } else {
      delete writerOptions.pkColumn;
    }
  } else if (writerOptions.pkColumn !== undefined && writerOptions.pkColumn !== null) {
    delete writerOptions.pkColumn;
  }
  return {
    ...binding,
    writerOptions,
  };
}

export function migrateLegacyWriteMode(targetBinding: CollectionTaskTargetBinding, executionOptions: Record<string, unknown>) {
  const legacyWriteMode = executionOptions.writeMode;
  delete executionOptions.writeMode;
  if (legacyWriteMode == null || String(legacyWriteMode).trim() === "") {
    return;
  }
  const writerOptions = { ...(targetBinding.writerOptions ?? {}) };
  if (writerOptions.writeMode == null || String(writerOptions.writeMode).trim() === "") {
    writerOptions.writeMode = legacyWriteMode;
  }
  targetBinding.writerOptions = writerOptions;
}

export function normalizeIncremental(incremental: CollectionIncrementalDefinition | undefined, mode: string): CollectionIncrementalDefinition {
  return {
    incrModel: ">",
    ...(incremental ?? {}),
    enabled: mode === "INCREMENTAL",
  };
}

export function stripSystemIncrementalCursor(incremental: CollectionIncrementalDefinition) {
  delete incremental.pkValue;
  delete incremental.valueType;
  delete incremental.lastRunRecordId;
  delete incremental.lastUpdatedAt;
  delete incremental.cursorStates;
}

export function findIncrementalCursorState(incremental: CollectionIncrementalDefinition) {
  const incrColumn = normalizeCursorText(incremental.incrColumn);
  if (!incrColumn) {
    return undefined;
  }
  const incrModel = normalizeIncrModel(incremental.incrModel);
  return incremental.cursorStates?.find((state) => (
    normalizeCursorText(state.incrColumn) === incrColumn
    && normalizeIncrModel(state.incrModel) === incrModel
  ));
}

export function normalizeCursorText(value: unknown) {
  return String(value ?? "").trim();
}

export function normalizeIncrModel(value: unknown) {
  const text = normalizeCursorText(value);
  return text || ">";
}

export function hasConfiguredArrayValue(value: unknown) {
  return Array.isArray(value) && value.some((item) => String(item).trim().length > 0);
}

export function mergeRuntimeDefaults(current: Record<string, unknown> | undefined, fields: MetadataFieldDefinition[]) {
  const next = { ...(current ?? {}) };
  for (const field of fields) {
    if (!field.fieldKey || field.defaultValue === undefined || field.defaultValue === null || field.defaultValue === "") {
      continue;
    }
    if (next[field.fieldKey] !== undefined && next[field.fieldKey] !== null) {
      continue;
    }
    next[field.fieldKey] = parseDefaultValue(field);
  }
  return next;
}

export function parseDefaultValue(field: MetadataFieldDefinition) {
  const rawValue = field.defaultValue;
  if (rawValue === undefined || rawValue === null) {
    return undefined;
  }
  if (field.valueType === "BOOLEAN") {
    return rawValue === "true";
  }
  if (field.valueType === "INTEGER" || field.valueType === "LONG" || field.valueType === "DECIMAL") {
    const numberValue = Number(rawValue);
    return Number.isNaN(numberValue) ? rawValue : numberValue;
  }
  if (field.valueType === "JSON" || field.valueType === "OBJECT" || field.valueType === "ARRAY") {
    try {
      return JSON.parse(rawValue);
    } catch {
      return rawValue;
    }
  }
  return rawValue;
}

export function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

export function hasIncrementalCursor(source: CollectionTaskSourceBinding) {
  const incremental = source.incremental;
  if (!incremental) {
    return false;
  }
  return hasCursorValue(incremental.pkValue)
    || hasCursorValue(incremental.valueType)
    || hasCursorValue(incremental.lastRunRecordId)
    || hasCursorValue(incremental.lastUpdatedAt);
}

export function hasCursorValue(value: unknown) {
  return value !== undefined && value !== null && String(value).trim() !== "";
}

export function formatIncrementalCursorValue(value: unknown, emptyText: string) {
  if (!hasCursorValue(value)) {
    return emptyText;
  }
  if (typeof value === "object") {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

export function formatIncrementalCursorMeta(value: unknown, emptyText: string) {
  return hasCursorValue(value) ? String(value) : emptyText;
}

export function incrementalCursorResetKey(source: CollectionTaskSourceBinding) {
  return source.sourceAlias?.trim() || "src1";
}

function isTruthyMetadataFlag(value: unknown) {
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value === "number") {
    return value === 1;
  }
  return ["true", "1", "yes", "y"].includes(String(value ?? "").trim().toLowerCase());
}
