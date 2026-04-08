import type { EntityId, MetadataSchemaDefinition } from "@studio/api-sdk";

export const META_MODEL_CONFIG_PREFIX = "META_MODEL_CONFIG:";
export const BUSINESS_META_MODELS_KEY = "__metaModels";
export const BUSINESS_SCHEMA_VERSION_KEY = "__businessSchemaVersionId";

export type MetaModelDomain = "TECHNICAL" | "BUSINESS";
export type MetaModelDisplayMode = "SINGLE" | "MULTIPLE";

export interface MetaModelConfig {
  domain: MetaModelDomain;
  datasourceType?: string;
  directoryCode?: string;
  directoryName?: string;
  metaModelCode: string;
  metaModelName?: string;
  displayMode?: MetaModelDisplayMode;
  required?: boolean;
  syncStrategy?: string;
}

export interface ParsedMetaModelSchema {
  config: MetaModelConfig;
  plainDescription: string;
}

export interface BusinessMetaModelEntry {
  schemaVersionId?: EntityId;
  schemaCode?: string;
  schemaName?: string;
  directoryCode?: string;
  directoryName?: string;
  metaModelCode?: string;
  displayMode?: MetaModelDisplayMode;
  values?: Record<string, unknown>;
  rows?: Record<string, unknown>[];
}

export interface ResolvedBusinessMetaModelSection {
  schema: MetadataSchemaDefinition;
  config: MetaModelConfig;
  entry: BusinessMetaModelEntry;
}

export function parseMetaModelSchema(schema: MetadataSchemaDefinition): ParsedMetaModelSchema {
  const description = schema.description ?? "";
  const lines = description.split(/\r?\n/);
  const firstLine = lines[0] ?? "";
  if (firstLine.startsWith(META_MODEL_CONFIG_PREFIX)) {
    try {
      const config = JSON.parse(firstLine.slice(META_MODEL_CONFIG_PREFIX.length).trim()) as MetaModelConfig;
      return {
        config: normalizeConfig(config, schema),
        plainDescription: lines.slice(1).join("\n").trim(),
      };
    } catch (error) {
      return {
        config: normalizeConfig(inferConfigFromSchema(schema), schema),
        plainDescription: description,
      };
    }
  }
  return {
    config: normalizeConfig(inferConfigFromSchema(schema), schema),
    plainDescription: description,
  };
}

export function hasExplicitMetaModelConfig(schema: MetadataSchemaDefinition) {
  const description = schema.description ?? "";
  return description.startsWith(META_MODEL_CONFIG_PREFIX);
}

export function encodeMetaModelDescription(config: MetaModelConfig, plainDescription?: string) {
  const normalized = normalizeConfig(config);
  return `${META_MODEL_CONFIG_PREFIX}${JSON.stringify(normalized)}\n${plainDescription?.trim() ?? ""}`.trim();
}

export function normalizeConfig(config: Partial<MetaModelConfig>, schema?: MetadataSchemaDefinition): MetaModelConfig {
  const inferred = schema ? inferConfigFromSchema(schema) : undefined;
  return {
    domain: (config.domain ?? inferred?.domain ?? "TECHNICAL") as MetaModelDomain,
    datasourceType: config.datasourceType ?? inferred?.datasourceType,
    directoryCode: config.directoryCode ?? inferred?.directoryCode,
    directoryName: config.directoryName ?? inferred?.directoryName,
    metaModelCode: config.metaModelCode ?? inferred?.metaModelCode ?? "table",
    metaModelName: config.metaModelName ?? inferred?.metaModelName,
    displayMode: (config.displayMode ?? inferred?.displayMode ?? "SINGLE") as MetaModelDisplayMode,
    required: config.required ?? inferred?.required ?? false,
    syncStrategy: config.syncStrategy ?? inferred?.syncStrategy,
  };
}

export function inferConfigFromSchema(schema: MetadataSchemaDefinition): MetaModelConfig {
  const schemaCode = normalize(schema.schemaCode);
  const typeCode = normalize(schema.typeCode);
  const objectType = normalize(schema.objectType);

  if (schemaCode.startsWith("business:")) {
    const parts = schemaCode.split(":");
    const directoryCode = parts[1] || typeCode.split(".")[0] || "business";
    const metaModelCode = parts[2] || typeCode.split(".").slice(1).join(".") || "business-model";
    return {
      domain: "BUSINESS",
      directoryCode,
      directoryName: directoryCode,
      metaModelCode,
      metaModelName: schema.schemaName,
      displayMode: "SINGLE",
      required: false,
    };
  }

  if (objectType === "datasource") {
    return {
      domain: "TECHNICAL",
      datasourceType: schema.typeCode,
      metaModelCode: "source",
      metaModelName: schema.schemaName,
      displayMode: "SINGLE",
      required: true,
      syncStrategy: "DATASOURCE_CONNECTION",
    };
  }

  if (objectType === "model") {
    const parts = typeCode.split(".");
    const datasourceType = parts[0] || schema.typeCode;
    const metaModelCode = parts[1] || "table";
    return {
      domain: "TECHNICAL",
      datasourceType,
      metaModelCode,
      metaModelName: schema.schemaName,
      displayMode: metaModelCode === "field" ? "MULTIPLE" : "SINGLE",
      required: metaModelCode === "source" || metaModelCode === "table" || metaModelCode === "field",
      syncStrategy: metaModelCode === "field" ? "COLUMN_DISCOVERY" : "OBJECT_DISCOVERY",
    };
  }

  const directoryParts = typeCode.split(".");
  return {
    domain: objectType === "business" ? "BUSINESS" : "TECHNICAL",
    directoryCode: objectType === "business" ? directoryParts[0] : undefined,
    directoryName: objectType === "business" ? directoryParts[0] : undefined,
    metaModelCode: directoryParts[1] || schema.typeCode || "meta-model",
    metaModelName: schema.schemaName,
    displayMode: "SINGLE",
  };
}

export function sameEntityId(left?: EntityId, right?: EntityId) {
  if (left == null || right == null) {
    return false;
  }
  return String(left) === String(right);
}

export function parseBusinessMetaModelEntries(metadata?: Record<string, unknown>) {
  const entries = metadata?.[BUSINESS_META_MODELS_KEY];
  if (!Array.isArray(entries)) {
    return [] as BusinessMetaModelEntry[];
  }
  return entries
    .filter((entry): entry is Record<string, unknown> => Boolean(entry) && typeof entry === "object" && !Array.isArray(entry))
    .map((entry) => normalizeBusinessMetaModelEntry(entry));
}

export function buildBusinessMetaModelMetadata(entries: BusinessMetaModelEntry[]) {
  return {
    [BUSINESS_META_MODELS_KEY]: entries.map((entry) => normalizeBusinessMetaModelEntry(entry)),
  } as Record<string, unknown>;
}

export function normalizeBusinessMetaModelEntry(
  entry: Partial<BusinessMetaModelEntry> | Record<string, unknown>,
  schema?: MetadataSchemaDefinition,
) {
  const parsed = schema ? parseMetaModelSchema(schema) : undefined;
  const displayMode = (entry.displayMode ?? parsed?.config.displayMode ?? "SINGLE") as MetaModelDisplayMode;
  return {
    schemaVersionId: parseEntityId(entry.schemaVersionId ?? (schema?.currentVersionId ?? schema?.id)),
    schemaCode: stringOrUndefined(entry.schemaCode ?? schema?.schemaCode),
    schemaName: stringOrUndefined(entry.schemaName ?? schema?.schemaName),
    directoryCode: stringOrUndefined(entry.directoryCode ?? parsed?.config.directoryCode),
    directoryName: stringOrUndefined(entry.directoryName ?? parsed?.config.directoryName ?? parsed?.config.directoryCode),
    metaModelCode: stringOrUndefined(entry.metaModelCode ?? parsed?.config.metaModelCode),
    displayMode,
    values: displayMode === "MULTIPLE" ? {} : normalizeBusinessMetaModelValues(entry.values),
    rows: displayMode === "MULTIPLE" ? normalizeBusinessMetaModelRows(entry.rows) : [],
  } as BusinessMetaModelEntry;
}

export function ensureBusinessMetaModelEntries(
  metadata: Record<string, unknown> | undefined,
  schemas: MetadataSchemaDefinition[],
) {
  const currentEntries = parseBusinessMetaModelEntries(metadata);
  const nextEntries: BusinessMetaModelEntry[] = [];
  const seen = new Set<string>();
  const sortedSchemas = [...schemas].sort(compareBusinessSchemas);
  for (const schema of sortedSchemas) {
    const existing = currentEntries.find((entry) => matchesBusinessSchema(entry, schema));
    const normalized = normalizeBusinessMetaModelEntry(existing ?? {}, schema);
    nextEntries.push(normalized);
    if (normalized.schemaVersionId != null) {
      seen.add(String(normalized.schemaVersionId));
    }
  }
  for (const entry of currentEntries) {
    const schemaKey = entry.schemaVersionId == null ? "" : String(entry.schemaVersionId);
    if (!schemaKey || seen.has(schemaKey)) {
      continue;
    }
    nextEntries.push(normalizeBusinessMetaModelEntry(entry));
  }
  return buildBusinessMetaModelMetadata(nextEntries);
}

export function resolveBusinessMetaModelSections(
  metadata: Record<string, unknown> | undefined,
  schemas: MetadataSchemaDefinition[],
) {
  return [...schemas]
    .sort(compareBusinessSchemas)
    .map((schema) => ({
      schema,
      config: parseMetaModelSchema(schema).config,
      entry: normalizeBusinessMetaModelEntry(
        parseBusinessMetaModelEntries(metadata).find((candidate) => matchesBusinessSchema(candidate, schema)) ?? {},
        schema,
      ),
    })) as ResolvedBusinessMetaModelSection[];
}

export function getBusinessMetaModelValues(
  metadata: Record<string, unknown> | undefined,
  schema: MetadataSchemaDefinition,
) {
  return normalizeBusinessMetaModelEntry(
    parseBusinessMetaModelEntries(metadata).find((entry) => matchesBusinessSchema(entry, schema)) ?? {},
    schema,
  ).values ?? {};
}

export function setBusinessMetaModelValues(
  metadata: Record<string, unknown> | undefined,
  schema: MetadataSchemaDefinition,
  values: Record<string, unknown>,
) {
  return updateBusinessMetaModelEntry(metadata, schema, (entry) => ({
    ...entry,
    values: normalizeBusinessMetaModelValues(values),
    rows: [],
  }));
}

export function getBusinessMetaModelRows(
  metadata: Record<string, unknown> | undefined,
  schema: MetadataSchemaDefinition,
) {
  return normalizeBusinessMetaModelEntry(
    parseBusinessMetaModelEntries(metadata).find((entry) => matchesBusinessSchema(entry, schema)) ?? {},
    schema,
  ).rows ?? [];
}

export function setBusinessMetaModelRows(
  metadata: Record<string, unknown> | undefined,
  schema: MetadataSchemaDefinition,
  rows: Record<string, unknown>[],
) {
  return updateBusinessMetaModelEntry(metadata, schema, (entry) => ({
    ...entry,
    values: {},
    rows: normalizeBusinessMetaModelRows(rows),
  }));
}

export function parseBusinessSchemaVersionId(metadata?: Record<string, unknown>) {
  return parseBusinessMetaModelEntries(metadata)[0]?.schemaVersionId;
}

export function withBusinessSchemaVersionId(metadata: Record<string, unknown>, schemaVersionId?: EntityId) {
  const entries = parseBusinessMetaModelEntries(metadata);
  if (schemaVersionId == null || String(schemaVersionId).trim() === "") {
    return buildBusinessMetaModelMetadata(entries.slice(1));
  }
  if (entries.length === 0) {
    return buildBusinessMetaModelMetadata([{ schemaVersionId }]);
  }
  const [first, ...rest] = entries;
  return buildBusinessMetaModelMetadata([{ ...first, schemaVersionId }, ...rest]);
}

export function parseEntityId(value: unknown): EntityId | undefined {
  if (value == null) {
    return undefined;
  }
  if (typeof value === "string" || typeof value === "number") {
    return value;
  }
  return undefined;
}

function normalize(value?: string) {
  return value?.trim().toLowerCase() ?? "";
}

function updateBusinessMetaModelEntry(
  metadata: Record<string, unknown> | undefined,
  schema: MetadataSchemaDefinition,
  updater: (entry: BusinessMetaModelEntry) => BusinessMetaModelEntry,
) {
  const currentEntries = parseBusinessMetaModelEntries(metadata);
  const nextEntries = currentEntries.map((entry) => (matchesBusinessSchema(entry, schema) ? updater(normalizeBusinessMetaModelEntry(entry, schema)) : entry));
  if (!nextEntries.some((entry) => matchesBusinessSchema(entry, schema))) {
    nextEntries.push(updater(normalizeBusinessMetaModelEntry({}, schema)));
  }
  nextEntries.sort((left, right) => compareBusinessEntries(left, right, schema));
  return buildBusinessMetaModelMetadata(nextEntries);
}

function normalizeBusinessMetaModelValues(values: unknown) {
  if (!values || typeof values !== "object" || Array.isArray(values)) {
    return {} as Record<string, unknown>;
  }
  return { ...(values as Record<string, unknown>) };
}

function normalizeBusinessMetaModelRows(rows: unknown) {
  if (!Array.isArray(rows)) {
    return [] as Record<string, unknown>[];
  }
  return rows
    .filter((row): row is Record<string, unknown> => Boolean(row) && typeof row === "object" && !Array.isArray(row))
    .map((row) => ({ ...row }));
}

function matchesBusinessSchema(entry: BusinessMetaModelEntry | undefined, schema: MetadataSchemaDefinition) {
  if (!entry) {
    return false;
  }
  return sameEntityId(entry.schemaVersionId, schema.currentVersionId ?? schema.id)
    || sameEntityId(entry.schemaVersionId, schema.id)
    || (entry.schemaCode != null && schema.schemaCode === entry.schemaCode);
}

function compareBusinessSchemas(left: MetadataSchemaDefinition, right: MetadataSchemaDefinition) {
  const leftConfig = parseMetaModelSchema(left).config;
  const rightConfig = parseMetaModelSchema(right).config;
  const directoryCompare = normalize(leftConfig.directoryName || leftConfig.directoryCode).localeCompare(
    normalize(rightConfig.directoryName || rightConfig.directoryCode),
  );
  if (directoryCompare !== 0) {
    return directoryCompare;
  }
  return (left.schemaName ?? "").localeCompare(right.schemaName ?? "");
}

function compareBusinessEntries(left: BusinessMetaModelEntry, right: BusinessMetaModelEntry, fallbackSchema?: MetadataSchemaDefinition) {
  const leftDirectory = normalize(left.directoryName || left.directoryCode);
  const rightDirectory = normalize(right.directoryName || right.directoryCode);
  const directoryCompare = leftDirectory.localeCompare(rightDirectory);
  if (directoryCompare !== 0) {
    return directoryCompare;
  }
  const leftName = left.schemaName ?? (fallbackSchema && sameEntityId(left.schemaVersionId, fallbackSchema.currentVersionId ?? fallbackSchema.id) ? fallbackSchema.schemaName : "");
  const rightName = right.schemaName ?? (fallbackSchema && sameEntityId(right.schemaVersionId, fallbackSchema.currentVersionId ?? fallbackSchema.id) ? fallbackSchema.schemaName : "");
  return leftName.localeCompare(rightName);
}

function stringOrUndefined(value: unknown) {
  if (typeof value !== "string") {
    return undefined;
  }
  return value;
}
