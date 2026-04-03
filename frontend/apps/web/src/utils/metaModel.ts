import type { EntityId, MetadataSchemaDefinition } from "@studio/api-sdk";

export const META_MODEL_CONFIG_PREFIX = "META_MODEL_CONFIG:";
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

export function parseBusinessSchemaVersionId(metadata?: Record<string, unknown>) {
  return parseEntityId(metadata?.[BUSINESS_SCHEMA_VERSION_KEY]);
}

export function withBusinessSchemaVersionId(metadata: Record<string, unknown>, schemaVersionId?: EntityId) {
  const next = { ...metadata };
  if (schemaVersionId == null || String(schemaVersionId).trim() === "") {
    delete next[BUSINESS_SCHEMA_VERSION_KEY];
    return next;
  }
  next[BUSINESS_SCHEMA_VERSION_KEY] = schemaVersionId;
  return next;
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
