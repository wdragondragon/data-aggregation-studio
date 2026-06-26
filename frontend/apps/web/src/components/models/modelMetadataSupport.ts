import { computed, type ComputedRef, type Ref } from "vue";
import { ElInput, ElInputNumber, ElSelect, ElSwitch } from "element-plus";
import type {
  DataModelDefinition,
  DataModelListView,
  DataModelQueryCondition,
  DataModelQueryGroup,
  DataModelQueryRequest,
  DataSourceDefinition,
  EntityId,
  MetadataFieldDefinition,
  MetadataSchemaDefinition,
  ModelKind,
} from "@studio/api-sdk";
import {
  ensureBusinessMetaModelEntries,
  getBusinessMetaModelRows,
  getBusinessMetaModelValues,
  parseMetaModelSchema,
  setBusinessMetaModelRows,
  setBusinessMetaModelValues,
} from "@/utils/metaModel";
import type { ModelFormState, ModelMetaSection, ModelQueryConditionState, ModelQueryGroupState } from "./modelViewTypes";

const DATABASE_TYPE_HINTS = [
  "mysql",
  "oracle",
  "postgres",
  "postgresql",
  "sqlserver",
  "clickhouse",
  "kingbase",
  "dm",
  "db2",
  "hive",
  "gauss",
  "tidb",
  "phoenix",
  "greenplum",
  "starrocks",
  "doris",
  "sqlite",
  "odps",
];

export function normalizeModelPagePayload(payload: unknown) {
  if (Array.isArray(payload)) {
    return {
      items: payload as DataModelListView[],
      total: payload.length,
    };
  }
  if (payload && typeof payload === "object") {
    const candidate = payload as { items?: unknown; total?: unknown };
    if (Array.isArray(candidate.items)) {
      return {
        items: candidate.items as DataModelListView[],
        total: Number(candidate.total ?? candidate.items.length ?? 0),
      };
    }
  }
  return {
    items: [] as DataModelListView[],
    total: 0,
  };
}

export function sameId(left?: EntityId, right?: EntityId) {
  if (left == null || right == null) {
    return false;
  }
  return String(left) === String(right);
}

export function normalizeTypeCode(value?: string) {
  return value?.trim().toLowerCase() ?? "";
}

export function isDatabaseDatasourceType(typeCode?: string) {
  const normalized = normalizeTypeCode(typeCode);
  if (!normalized) {
    return false;
  }
  if (["ftp", "sftp", "minio", "oss", "file", "kafka", "rabbitmq", "rocketmq"].some((item) => normalized.includes(item))) {
    return false;
  }
  return DATABASE_TYPE_HINTS.some((item) => normalized.includes(item));
}

interface ModelMetadataSupportOptions {
  schemas: Ref<MetadataSchemaDefinition[]>;
  modelForm: ModelFormState;
  selectedModel: Ref<DataModelDefinition | undefined>;
  selectedDatasource: ComputedRef<DataSourceDefinition | undefined>;
  editorDatasource: ComputedRef<DataSourceDefinition | undefined>;
  activeQueryDatasourceType: ComputedRef<string>;
  queryGroups: Ref<ModelQueryGroupState[]>;
  t: (key: string) => string;
}

export function useModelMetadataSupport(options: ModelMetadataSupportOptions) {
  const isEditingModel = computed(() => Boolean(options.modelForm.id));
  const availableModelSchemas = computed(() =>
    filterModelSchemas(options.editorDatasource.value?.typeCode).filter((schema) => parseMetaModelSchema(schema).config.metaModelCode !== "field"),
  );
  const selectedModelSchema = computed(() => findSelectedModelSchema(availableModelSchemas.value, options.modelForm.schemaVersionId));
  const querySchemaOptions = computed(() => buildQuerySchemaOptions());
  const editorTechnicalSections = computed(() => buildTechnicalSections(options.editorDatasource.value?.typeCode, options.modelForm.schemaVersionId));
  const previewTechnicalSections = computed(() => buildTechnicalSections(options.selectedDatasource.value?.typeCode, options.selectedModel.value?.schemaVersionId));
  const editorBusinessSections = computed(() => buildBusinessSections(resolveBusinessSchemas(editorTechnicalSections.value.map((section) => section.metaModelCode))));
  const previewBusinessSections = computed(() => buildBusinessSections(resolveBusinessSchemas(previewTechnicalSections.value.map((section) => section.metaModelCode))));
  const editorSections = computed(() => buildOrderedSections(editorTechnicalSections.value, editorBusinessSections.value));
  const previewSections = computed(() => buildOrderedSections(previewTechnicalSections.value, previewBusinessSections.value));
  const showManualDatasourceHint = computed(
    () => Boolean(options.editorDatasource.value && isDatabaseDatasourceType(options.editorDatasource.value.typeCode) && !isEditingModel.value),
  );
  const isHttpModelEditor = computed(() => normalizeTypeCode(options.editorDatasource.value?.typeCode) === "http");
  const modelNameEditorLabel = computed(() => isHttpModelEditor.value ? options.t("web.models.interfaceNameLabel") : options.t("web.models.modelNameLabel"));
  const modelNameEditorPlaceholder = computed(() => isHttpModelEditor.value ? options.t("web.models.interfaceNamePlaceholder") : options.t("web.models.modelNamePlaceholder"));
  const physicalLocatorEditorLabel = computed(() => isHttpModelEditor.value ? options.t("web.models.requestPathLabel") : options.t("web.models.physicalLocatorLabel"));
  const physicalLocatorEditorPlaceholder = computed(() => isHttpModelEditor.value ? options.t("web.models.requestPathPlaceholder") : options.t("web.models.physicalLocatorPlaceholder"));

  function filterModelSchemas(datasourceTypeCode?: string) {
    const normalizedType = normalizeTypeCode(datasourceTypeCode);
    if (!normalizedType) {
      return [];
    }
    return options.schemas.value.filter((schema) => {
      if (normalizeTypeCode(schema.objectType) !== "model") {
        return false;
      }
      const schemaType = normalizeTypeCode(schema.typeCode);
      return schemaType === normalizedType || schemaType.startsWith(`${normalizedType}.`);
    });
  }

  function activeBusinessMetaModelCodesForQuery() {
    const typeCode = options.activeQueryDatasourceType.value;
    if (!typeCode) {
      return undefined;
    }
    return new Set(
      filterModelSchemas(typeCode)
        .map((schema) => normalizeTypeCode(parseMetaModelSchema(schema).config.metaModelCode))
        .filter((metaModelCode) => Boolean(metaModelCode) && metaModelCode !== "source"),
    );
  }

  function buildQuerySchemaOptions() {
    const allowedBusinessMetaModels = activeBusinessMetaModelCodesForQuery();
    return options.schemas.value
      .filter((schema) => normalizeTypeCode(schema.objectType) === "model" || parseMetaModelSchema(schema).config.domain === "BUSINESS")
      .filter((schema) => parseMetaModelSchema(schema).config.metaModelCode !== "source")
      .filter((schema) => searchableFields(schema).length > 0)
      .filter((schema) => {
        const parsed = parseMetaModelSchema(schema).config;
        if (parsed.domain === "TECHNICAL") {
          if (!options.activeQueryDatasourceType.value) {
            return false;
          }
          return normalizeTypeCode(parsed.datasourceType) === normalizeTypeCode(options.activeQueryDatasourceType.value);
        }
        if (!allowedBusinessMetaModels) {
          return false;
        }
        return allowedBusinessMetaModels.has(normalizeTypeCode(parsed.metaModelCode));
      })
      .sort((left, right) => querySchemaLabel(left).localeCompare(querySchemaLabel(right)));
  }

  function findSelectedModelSchema(schemaOptions: MetadataSchemaDefinition[], schemaVersionId?: EntityId) {
    if (schemaVersionId != null) {
      const matched = schemaOptions.find((schema) => sameId(schema.id, schemaVersionId) || sameId(schema.currentVersionId, schemaVersionId));
      if (matched) {
        return matched;
      }
    }
    return schemaOptions[0];
  }

  function resolveBusinessSchemas(metaModelCodes: string[]) {
    const allowedCodes = new Set(metaModelCodes.map((code) => normalizeTypeCode(code)).filter(Boolean));
    return options.schemas.value
      .filter((schema) => {
        const parsed = parseMetaModelSchema(schema).config;
        return parsed.domain === "BUSINESS" && allowedCodes.has(normalizeTypeCode(parsed.metaModelCode));
      })
      .sort((left, right) => {
        const leftConfig = parseMetaModelSchema(left).config;
        const rightConfig = parseMetaModelSchema(right).config;
        const directoryCompare = `${leftConfig.directoryName || leftConfig.directoryCode || ""}`
          .localeCompare(`${rightConfig.directoryName || rightConfig.directoryCode || ""}`);
        if (directoryCompare !== 0) {
          return directoryCompare;
        }
        return left.schemaName.localeCompare(right.schemaName);
      });
  }

  function resolveSectionCollectionKey(metaModelCode?: string) {
    if (metaModelCode === "field") {
      return "columns";
    }
    if (!metaModelCode) {
      return "items";
    }
    return metaModelCode.endsWith("s") ? metaModelCode : `${metaModelCode}s`;
  }

  function metaModelRank(metaModelCode?: string) {
    switch (metaModelCode) {
      case "table":
        return 0;
      case "field":
        return 1;
      default:
        return 10;
    }
  }

  function isRelevantTechnicalSchema(schema: MetadataSchemaDefinition, activeSchemaVersionId?: EntityId) {
    const metaModelCode = parseMetaModelSchema(schema).config.metaModelCode;
    return metaModelCode === "field"
      || sameId(schema.id, activeSchemaVersionId)
      || sameId(schema.currentVersionId, activeSchemaVersionId)
      || (!activeSchemaVersionId && metaModelCode === "table");
  }

  function buildTechnicalSections(datasourceTypeCode?: string, activeSchemaVersionId?: EntityId) {
    const candidates = filterModelSchemas(datasourceTypeCode)
      .filter((schema) => parseMetaModelSchema(schema).config.metaModelCode !== "source")
      .filter((schema) => isRelevantTechnicalSchema(schema, activeSchemaVersionId))
      .sort((left, right) => {
        const leftActive = sameId(left.id, activeSchemaVersionId) || sameId(left.currentVersionId, activeSchemaVersionId);
        const rightActive = sameId(right.id, activeSchemaVersionId) || sameId(right.currentVersionId, activeSchemaVersionId);
        if (leftActive !== rightActive) {
          return leftActive ? -1 : 1;
        }
        const leftCode = parseMetaModelSchema(left).config.metaModelCode;
        const rightCode = parseMetaModelSchema(right).config.metaModelCode;
        const rankCompare = metaModelRank(leftCode) - metaModelRank(rightCode);
        if (rankCompare !== 0) {
          return rankCompare;
        }
        return left.schemaName.localeCompare(right.schemaName);
      });

    const multipleFieldKeys = new Set<string>();
    for (const schema of candidates) {
      const parsed = parseMetaModelSchema(schema);
      if (parsed.config.displayMode === "MULTIPLE") {
        multipleFieldKeys.add(resolveSectionCollectionKey(parsed.config.metaModelCode));
      }
    }

    return candidates.map((schema) => {
      const parsed = parseMetaModelSchema(schema);
      const displayMode = parsed.config.displayMode ?? (parsed.config.metaModelCode === "field" ? "MULTIPLE" : "SINGLE");
      const fields = (schema.fields ?? [])
        .filter((field) => field.scope !== "BUSINESS")
        .filter((field) => !(displayMode !== "MULTIPLE" && multipleFieldKeys.has(field.fieldKey)));
      return {
        key: `technical:${schema.id ?? schema.schemaCode}`,
        schema,
        title: parsed.config.metaModelName || schema.schemaName,
        description: parsed.plainDescription || schema.schemaCode,
        binding: "TECHNICAL" as const,
        displayMode,
        metaModelCode: parsed.config.metaModelCode,
        fields,
        collectionKey: displayMode === "MULTIPLE" ? resolveSectionCollectionKey(parsed.config.metaModelCode) : undefined,
      } satisfies ModelMetaSection;
    });
  }

  function buildBusinessSections(businessSchemas: MetadataSchemaDefinition[]) {
    if (businessSchemas.length === 0) {
      return [] as ModelMetaSection[];
    }
    return businessSchemas.map((schema) => {
      const parsed = parseMetaModelSchema(schema);
      const displayMode = parsed.config.displayMode ?? "SINGLE";
      return {
        key: `business:${schema.id ?? schema.schemaCode}`,
        schema,
        title: `${parsed.config.directoryName || parsed.config.directoryCode || options.t("web.models.businessMetaModelTitle")} / ${schema.schemaName}`,
        description: parsed.plainDescription || schema.schemaCode,
        binding: "BUSINESS" as const,
        displayMode,
        metaModelCode: parsed.config.metaModelCode,
        fields: schema.fields ?? [],
        collectionKey: displayMode === "MULTIPLE" ? resolveSectionCollectionKey(parsed.config.metaModelCode) : undefined,
      } satisfies ModelMetaSection;
    });
  }

  function buildOrderedSections(technicalSections: ModelMetaSection[], businessSections: ModelMetaSection[]) {
    const ordered = [] as ModelMetaSection[];
    const businessByCode = new Map<string, ModelMetaSection[]>();
    for (const section of businessSections) {
      const code = normalizeTypeCode(section.metaModelCode);
      if (!businessByCode.has(code)) {
        businessByCode.set(code, []);
      }
      businessByCode.get(code)?.push(section);
    }
    for (const technicalSection of technicalSections) {
      ordered.push(technicalSection);
      const code = normalizeTypeCode(technicalSection.metaModelCode);
      ordered.push(...(businessByCode.get(code) ?? []));
      businessByCode.delete(code);
    }
    for (const remainingSections of businessByCode.values()) {
      ordered.push(...remainingSections);
    }
    return ordered;
  }

  function deriveModelKindFromDatasource(datasource?: DataSourceDefinition): ModelKind | undefined {
    const type = normalizeTypeCode(datasource?.typeCode);
    if (!type) {
      return undefined;
    }
    if (type === "http" || type.includes("api") || type.includes("rest")) {
      return "API" as ModelKind;
    }
    if (isDatabaseDatasourceType(type)) {
      return "TABLE";
    }
    if (type.includes("file") || type.includes("ftp") || type.includes("sftp") || type.includes("oss") || type.includes("minio")) {
      return "FILE";
    }
    if (type.includes("mq") || type.includes("kafka") || type.includes("rabbit") || type.includes("rocket")) {
      return "TOPIC";
    }
    return "CUSTOM" as ModelKind;
  }

  function deriveModelKindFromSchema(schema?: MetadataSchemaDefinition, datasource?: DataSourceDefinition): ModelKind | undefined {
    const parsed = schema ? parseMetaModelSchema(schema).config : undefined;
    const code = normalizeTypeCode(parsed?.metaModelCode);
    if (code === "table") {
      return "TABLE";
    }
    if (code === "topic") {
      return "TOPIC";
    }
    if (code === "file") {
      return "FILE";
    }
    if (code === "api" || code === "interface") {
      return "API" as ModelKind;
    }
    return deriveModelKindFromDatasource(datasource);
  }

  function searchableFields(schema?: MetadataSchemaDefinition) {
    return (schema?.fields ?? []).filter((field) => field.searchable);
  }

  function querySchemaLabel(schema: MetadataSchemaDefinition) {
    const parsed = parseMetaModelSchema(schema).config;
    if (parsed.domain === "BUSINESS") {
      return `${parsed.directoryName || parsed.directoryCode || options.t("web.models.businessMetaModelTitle")} / ${schema.schemaName}`;
    }
    return `${schema.typeCode || ""} / ${schema.schemaName}`;
  }

  function findQuerySchema(schemaCode?: string) {
    if (!schemaCode) {
      return undefined;
    }
    return querySchemaOptions.value.find((schema) => schema.schemaCode === schemaCode);
  }

  function querySchemaFields(group: ModelQueryGroupState) {
    return searchableFields(findQuerySchema(group.metaSchemaCode));
  }

  function queryConditionField(group: ModelQueryGroupState, condition: ModelQueryConditionState) {
    return querySchemaFields(group).find((field) => field.fieldKey === condition.fieldKey);
  }

  function querySchemaOperatorOptions(field?: MetadataFieldDefinition) {
    const configured = (field?.queryOperators ?? []).map((item) => String(item)).filter(Boolean);
    if (configured.length > 0) {
      return configured;
    }
    if (field?.valueType === "BOOLEAN") {
      return ["EQ", "IN"];
    }
    if (isNumericQueryField(field)) {
      return ["EQ", "IN", "GT", "GE", "LT", "LE", "BETWEEN"];
    }
    return ["EQ", "LIKE", "IN"];
  }

  function defaultQueryOperator(field?: MetadataFieldDefinition) {
    return String(field?.queryDefaultOperator ?? querySchemaOperatorOptions(field)[0] ?? "EQ");
  }

  function queryConditionOperators(group: ModelQueryGroupState, condition: ModelQueryConditionState) {
    return querySchemaOperatorOptions(queryConditionField(group, condition));
  }

  function queryConditionInputValue(value: unknown): string | number | undefined {
    return typeof value === "string" || typeof value === "number" ? value : undefined;
  }

  function queryConditionBooleanValue(value: unknown): boolean | undefined {
    return typeof value === "boolean" ? value : undefined;
  }

  function setQueryConditionValue(condition: ModelQueryConditionState, value: string | number | boolean | null | undefined) {
    condition.value = value == null ? undefined : value;
  }

  function setQueryConditionValueTo(condition: ModelQueryConditionState, value: string | number | null | undefined) {
    condition.valueTo = value == null ? undefined : value;
  }

  function isMultipleQuerySchema(group: ModelQueryGroupState) {
    const schema = findQuerySchema(group.metaSchemaCode);
    return schema ? parseMetaModelSchema(schema).config.displayMode === "MULTIPLE" : false;
  }

  function isNumericQueryField(field?: MetadataFieldDefinition) {
    return field?.valueType === "INTEGER" || field?.valueType === "LONG" || field?.valueType === "DECIMAL";
  }

  let queryGroupSeed = 0;

  function nextQueryGroupKey() {
    queryGroupSeed += 1;
    return `query-group-${queryGroupSeed}`;
  }

  function createDefaultQueryCondition(schema?: MetadataSchemaDefinition): ModelQueryConditionState {
    const field = searchableFields(schema)[0];
    return {
      fieldKey: field?.fieldKey ?? "",
      operator: defaultQueryOperator(field),
      value: undefined,
      valueTo: undefined,
      multiValueText: "",
    };
  }

  function createQueryGroup(schemaCode?: string): ModelQueryGroupState {
    const schema = schemaCode ? findQuerySchema(schemaCode) : querySchemaOptions.value[0];
    return {
      key: nextQueryGroupKey(),
      metaSchemaCode: schema?.schemaCode ?? schemaCode ?? "",
      rowMatchMode: schema && parseMetaModelSchema(schema).config.displayMode === "MULTIPLE" ? "SAME_ITEM" : "ANY_ITEM",
      conditions: [createDefaultQueryCondition(schema)],
    };
  }

  function normalizeQueryGroupsForDatasource() {
    const availableCodes = new Set(querySchemaOptions.value.map((schema) => schema.schemaCode));
    options.queryGroups.value = options.queryGroups.value.filter((group) => !group.metaSchemaCode || availableCodes.has(group.metaSchemaCode));
  }

  function appendQueryGroup() {
    options.queryGroups.value.push(createQueryGroup());
  }

  function removeQueryGroup(groupKey: string) {
    options.queryGroups.value = options.queryGroups.value.filter((group) => group.key !== groupKey);
  }

  function handleQuerySchemaChange(group: ModelQueryGroupState) {
    const schema = findQuerySchema(group.metaSchemaCode);
    group.rowMatchMode = schema && parseMetaModelSchema(schema).config.displayMode === "MULTIPLE" ? "SAME_ITEM" : "ANY_ITEM";
    group.conditions = [createDefaultQueryCondition(schema)];
  }

  function appendQueryCondition(group: ModelQueryGroupState) {
    group.conditions.push(createDefaultQueryCondition(findQuerySchema(group.metaSchemaCode)));
  }

  function removeQueryCondition(group: ModelQueryGroupState, index: number) {
    group.conditions.splice(index, 1);
    if (group.conditions.length === 0) {
      group.conditions.push(createDefaultQueryCondition(findQuerySchema(group.metaSchemaCode)));
    }
  }

  function handleQueryFieldChange(group: ModelQueryGroupState, condition: ModelQueryConditionState) {
    const field = queryConditionField(group, condition);
    condition.operator = defaultQueryOperator(field);
    condition.value = undefined;
    condition.valueTo = undefined;
    condition.multiValueText = "";
  }

  function parseQueryValue(value: unknown, field?: MetadataFieldDefinition) {
    if (value === undefined || value === null || (typeof value === "string" && value.trim() === "")) {
      return undefined;
    }
    if (field?.valueType === "BOOLEAN") {
      if (typeof value === "boolean") {
        return value;
      }
      return String(value).trim().toLowerCase() === "true";
    }
    if (isNumericQueryField(field)) {
      const numberValue = Number(value);
      return Number.isNaN(numberValue) ? undefined : numberValue;
    }
    return value;
  }

  function buildModelQueryRequest(selectedDatasourceId?: EntityId, selectedDatasourceType?: string): DataModelQueryRequest {
    const groups: DataModelQueryGroup[] = [];
    for (const group of options.queryGroups.value) {
      const schema = findQuerySchema(group.metaSchemaCode);
      if (!schema) {
        continue;
      }
      const conditions: DataModelQueryCondition[] = [];
      for (const condition of group.conditions) {
        const field = queryConditionField(group, condition);
        if (!field || !condition.fieldKey || !condition.operator) {
          continue;
        }
        if (condition.operator === "IN") {
          const values = condition.multiValueText
            .split(",")
            .map((item) => parseQueryValue(item.trim(), field))
            .filter((item) => item !== undefined);
          if (values.length === 0) {
            continue;
          }
          conditions.push({ fieldKey: condition.fieldKey, operator: condition.operator, values });
          continue;
        }
        if (condition.operator === "BETWEEN") {
          const lower = parseQueryValue(condition.value, field);
          const upper = parseQueryValue(condition.valueTo, field);
          if (lower === undefined || upper === undefined) {
            continue;
          }
          conditions.push({ fieldKey: condition.fieldKey, operator: condition.operator, values: [lower, upper] });
          continue;
        }
        const value = parseQueryValue(condition.value, field);
        if (value === undefined) {
          continue;
        }
        conditions.push({ fieldKey: condition.fieldKey, operator: condition.operator, value });
      }
      if (conditions.length === 0) {
        continue;
      }
      groups.push({
        scope: parseMetaModelSchema(schema).config.domain as DataModelQueryGroup["scope"],
        metaSchemaCode: schema.schemaCode,
        rowMatchMode: group.rowMatchMode,
        conditions,
      });
    }
    return {
      datasourceId: selectedDatasourceId,
      datasourceType: selectedDatasourceType || undefined,
      groups,
    };
  }

  function parseDefaultValue(field: MetadataFieldDefinition) {
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

  function buildDefaultMetadata(fields: MetadataFieldDefinition[]) {
    const defaults: Record<string, unknown> = {};
    for (const field of fields) {
      if (!field.fieldKey) {
        continue;
      }
      const defaultValue = parseDefaultValue(field);
      if (defaultValue !== undefined) {
        defaults[field.fieldKey] = defaultValue;
      }
    }
    return defaults;
  }

  function mergeMetadataDefaults(current: Record<string, unknown> | undefined, fields: MetadataFieldDefinition[]) {
    return {
      ...buildDefaultMetadata(fields),
      ...(current ?? {}),
    };
  }

  function parseFieldRows(value: unknown, fields: MetadataFieldDefinition[] = []) {
    if (!Array.isArray(value)) {
      return [] as Record<string, unknown>[];
    }
    const defaults = buildDefaultMetadata(fields);
    return value
      .filter((item) => item && typeof item === "object" && !Array.isArray(item))
      .map((item) => normalizeFieldRow({ ...defaults, ...(item as Record<string, unknown>) }, fields));
  }

  function normalizeFieldRow(row: Record<string, unknown>, fields: MetadataFieldDefinition[]) {
    if (fields.length === 0) {
      return row;
    }
    const normalized = { ...row };
    for (const field of fields) {
      if (!field.fieldKey || normalized[field.fieldKey] === undefined || normalized[field.fieldKey] === null) {
        continue;
      }
      normalized[field.fieldKey] = normalizeMetadataValue(normalized[field.fieldKey], field);
    }
    return normalized;
  }

  function normalizeMetadataValue(value: unknown, field: MetadataFieldDefinition) {
    if (field.valueType === "BOOLEAN") {
      if (typeof value === "boolean") {
        return value;
      }
      return String(value).trim().toLowerCase() === "true";
    }
    if (field.valueType === "INTEGER" || field.valueType === "LONG" || field.valueType === "DECIMAL") {
      if (typeof value === "number") {
        return value;
      }
      if (typeof value === "string" && value.trim() === "") {
        return undefined;
      }
      const numberValue = Number(value);
      return Number.isNaN(numberValue) ? value : numberValue;
    }
    if ((field.valueType === "JSON" || field.valueType === "OBJECT" || field.valueType === "ARRAY") && typeof value === "string") {
      try {
        return JSON.parse(value);
      } catch {
        return value;
      }
    }
    return value;
  }

  function sectionValue(section: ModelMetaSection, fieldKey?: string, editor = false) {
    if (!fieldKey) {
      return undefined;
    }
    if (section.binding === "TECHNICAL") {
      const metadata = editor ? options.modelForm.technicalMetadata : options.selectedModel.value?.technicalMetadata;
      return metadata?.[fieldKey];
    }
    return sectionModelValue(section, editor)?.[fieldKey];
  }

  function sectionModelValue(section: ModelMetaSection, editor = false) {
    if (section.binding === "TECHNICAL") {
      return (editor ? options.modelForm.technicalMetadata : options.selectedModel.value?.technicalMetadata) ?? {};
    }
    const metadata = editor ? options.modelForm.businessMetadata : options.selectedModel.value?.businessMetadata;
    return getBusinessMetaModelValues(metadata, section.schema);
  }

  function previewSectionRows(section: ModelMetaSection) {
    if (section.binding === "BUSINESS") {
      return getBusinessMetaModelRows(options.selectedModel.value?.businessMetadata, section.schema);
    }
    return parseFieldRows(sectionValue(section, section.collectionKey, false), section.fields);
  }

  function editorSectionRows(section: ModelMetaSection) {
    if (section.binding === "BUSINESS") {
      return getBusinessMetaModelRows(options.modelForm.businessMetadata, section.schema);
    }
    return parseFieldRows(sectionValue(section, section.collectionKey, true), section.fields);
  }

  function updateSectionModelValue(section: ModelMetaSection, value: Record<string, unknown>) {
    if (section.binding === "TECHNICAL") {
      options.modelForm.technicalMetadata = value;
      return;
    }
    options.modelForm.businessMetadata = setBusinessMetaModelValues(options.modelForm.businessMetadata, section.schema, value);
  }

  function setSectionRows(section: ModelMetaSection, rows: Record<string, unknown>[]) {
    if (!section.collectionKey) {
      return;
    }
    if (section.binding === "TECHNICAL") {
      const container = { ...(options.modelForm.technicalMetadata ?? {}) };
      container[section.collectionKey] = rows;
      options.modelForm.technicalMetadata = container;
      return;
    }
    options.modelForm.businessMetadata = setBusinessMetaModelRows(options.modelForm.businessMetadata, section.schema, rows);
  }

  function appendSectionRow(section: ModelMetaSection) {
    const rows = editorSectionRows(section);
    rows.push(buildDefaultMetadata(section.fields));
    setSectionRows(section, rows);
  }

  function removeSectionRow(section: ModelMetaSection, index: number) {
    const rows = editorSectionRows(section);
    rows.splice(index, 1);
    setSectionRows(section, rows);
  }

  function updateSectionRowField(section: ModelMetaSection, index: number, fieldKey: string, value: unknown) {
    const rows = editorSectionRows(section);
    rows[index] = {
      ...(rows[index] ?? {}),
      [fieldKey]: value,
    };
    setSectionRows(section, rows);
  }

  function resolveRowEditorComponent(field: MetadataFieldDefinition) {
    switch (field.componentType) {
      case "SWITCH":
        return ElSwitch;
      case "SELECT":
        return ElSelect;
      case "NUMBER":
        return ElInputNumber;
      default:
        return ElInput;
    }
  }

  function resolveRowEditorProps(field: MetadataFieldDefinition) {
    switch (field.componentType) {
      case "SWITCH":
        return {
          inlinePrompt: true,
          activeText: options.t("common.on"),
          inactiveText: options.t("common.off"),
        };
      case "SELECT":
        return {
          clearable: true,
          filterable: true,
          placeholder: field.placeholder ?? field.fieldName,
        };
      case "NUMBER":
        return {
          controlsPosition: "right",
        };
      case "TEXTAREA":
      case "JSON_EDITOR":
      case "SQL_EDITOR":
      case "CODE_EDITOR":
        return {
          type: "textarea",
          rows: 2,
          placeholder: field.placeholder ?? field.fieldName,
        };
      default:
        return {
          placeholder: field.placeholder ?? field.fieldName,
        };
    }
  }

  function formatDisplayValue(value: unknown) {
    if (value === null || value === undefined || value === "") {
      return "-";
    }
    if (typeof value === "boolean") {
      return value ? options.t("common.yes") : options.t("common.no");
    }
    if (Array.isArray(value)) {
      return value.length === 0 ? "-" : JSON.stringify(value);
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

  function applyModelSchemaContext(applyOptions: { resetMetadata?: boolean; forceKind?: boolean } = {}) {
    const schema = selectedModelSchema.value;
    if (schema) {
      options.modelForm.schemaVersionId = schema.currentVersionId ?? schema.id;
    }
    const technicalDefaultFields = (schema?.fields ?? []).filter((field) => field.scope !== "BUSINESS");
    if (applyOptions.resetMetadata) {
      options.modelForm.technicalMetadata = buildDefaultMetadata(technicalDefaultFields);
    } else {
      options.modelForm.technicalMetadata = mergeMetadataDefaults(options.modelForm.technicalMetadata, technicalDefaultFields);
    }
    for (const section of buildTechnicalSections(options.editorDatasource.value?.typeCode, options.modelForm.schemaVersionId)) {
      if (section.displayMode === "MULTIPLE") {
        setSectionRows(section, editorSectionRows(section));
      }
    }
    if ((applyOptions.forceKind || !options.modelForm.modelKind) && options.editorDatasource.value) {
      options.modelForm.modelKind = deriveModelKindFromSchema(schema, options.editorDatasource.value);
    }
    applyBusinessMetadataDefaults();
  }

  function applyBusinessMetadataDefaults() {
    options.modelForm.businessMetadata = ensureBusinessMetaModelEntries(
      options.modelForm.businessMetadata,
      editorBusinessSections.value.map((section) => section.schema),
    );
    for (const section of editorBusinessSections.value) {
      if (section.displayMode === "MULTIPLE") {
        setSectionRows(section, editorSectionRows(section));
        continue;
      }
      updateSectionModelValue(section, {
        ...buildDefaultMetadata(section.fields),
        ...sectionModelValue(section, true),
      });
    }
  }

  function resetModelForm(prefillDatasourceId?: EntityId) {
    options.modelForm.id = undefined;
    options.modelForm.datasourceId = prefillDatasourceId;
    options.modelForm.name = "";
    options.modelForm.physicalLocator = "";
    options.modelForm.modelKind = undefined;
    options.modelForm.schemaVersionId = undefined;
    options.modelForm.technicalMetadata = {};
    options.modelForm.businessMetadata = {};
    if (prefillDatasourceId) {
      applyModelSchemaContext({ resetMetadata: true, forceKind: true });
    }
  }

  return {
    isEditingModel,
    availableModelSchemas,
    selectedModelSchema,
    querySchemaOptions,
    editorSections,
    previewSections,
    showManualDatasourceHint,
    modelNameEditorLabel,
    modelNameEditorPlaceholder,
    physicalLocatorEditorLabel,
    physicalLocatorEditorPlaceholder,
    filterModelSchemas,
    querySchemaLabel,
    querySchemaFields,
    queryConditionField,
    queryConditionOperators,
    queryConditionInputValue,
    queryConditionBooleanValue,
    setQueryConditionValue,
    setQueryConditionValueTo,
    isMultipleQuerySchema,
    isNumericQueryField,
    normalizeQueryGroupsForDatasource,
    appendQueryGroup,
    removeQueryGroup,
    handleQuerySchemaChange,
    appendQueryCondition,
    removeQueryCondition,
    handleQueryFieldChange,
    buildModelQueryRequest,
    appendSectionRow,
    editorSectionRows,
    resolveRowEditorComponent,
    resolveRowEditorProps,
    updateSectionRowField,
    removeSectionRow,
    sectionValue,
    sectionModelValue,
    updateSectionModelValue,
    previewSectionRows,
    formatDisplayValue,
    applyModelSchemaContext,
    resetModelForm,
  };
}
