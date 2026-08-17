import type { MetadataFieldDefinition } from "@studio/api-sdk";

export const MODEL_FIELD_IMPORT_MAX_BYTES = 5 * 1024 * 1024;
export const MODEL_FIELD_IMPORT_MAX_VISIBLE_ISSUES = 100;
export const MODEL_FIELD_TABLE_PAGE_SIZE = 50;

export type ModelFieldImportIssueCode =
  | "TEXT_TOO_LARGE"
  | "INVALID_JSON"
  | "INVALID_ROOT"
  | "UNEXPECTED_ROOT_KEY"
  | "EMPTY_ROWS"
  | "INVALID_ROW"
  | "UNKNOWN_FIELD"
  | "MISSING_REQUIRED"
  | "INVALID_TYPE"
  | "INVALID_OPTION"
  | "DUPLICATE_NAME";

export interface ModelFieldImportIssue {
  code: ModelFieldImportIssueCode;
  path: string;
  fieldName?: string;
  actualType?: string;
  expectedType?: string;
  allowedOptions?: string;
  duplicateValue?: string;
  firstPath?: string;
  detail?: string;
}

export interface ModelFieldImportResult {
  rows: Record<string, unknown>[];
  issues: ModelFieldImportIssue[];
}

export function parseAndValidateModelFieldJson(
  source: string,
  fields: MetadataFieldDefinition[],
  maxBytes = MODEL_FIELD_IMPORT_MAX_BYTES,
): ModelFieldImportResult {
  const rawSource = String(source ?? "");
  if (utf8ByteLength(rawSource) > maxBytes) {
    return issueResult({ code: "TEXT_TOO_LARGE", path: "$" });
  }
  const normalizedSource = stripBom(rawSource);

  let parsed: unknown;
  try {
    parsed = JSON.parse(normalizedSource);
  } catch (error) {
    return issueResult({
      code: "INVALID_JSON",
      path: "$",
      detail: error instanceof Error ? error.message : undefined,
    });
  }

  const rootIssues: ModelFieldImportIssue[] = [];
  const sourceRows = resolveSourceRows(parsed, rootIssues);
  if (!sourceRows) {
    return { rows: [], issues: rootIssues };
  }
  if (sourceRows.length === 0) {
    rootIssues.push({ code: "EMPTY_ROWS", path: "$" });
  }

  const fieldByKey = new Map(fields.map((field) => [field.fieldKey, field]));
  const normalizedRows: Record<string, unknown>[] = [];
  const issues = [...rootIssues];
  const nameOccurrences = new Map<string, string>();

  sourceRows.forEach((sourceRow, rowIndex) => {
    const rowPath = `$[${rowIndex}]`;
    if (!isPlainObject(sourceRow)) {
      issues.push({ code: "INVALID_ROW", path: rowPath, actualType: describeJsonType(sourceRow) });
      return;
    }

    Object.keys(sourceRow).forEach((fieldKey) => {
      if (!fieldByKey.has(fieldKey)) {
        issues.push({ code: "UNKNOWN_FIELD", path: `${rowPath}.${fieldKey}`, fieldName: fieldKey });
      }
    });

    const normalizedRow: Record<string, unknown> = {};
    fields.forEach((field) => {
      const fieldPath = `${rowPath}.${field.fieldKey}`;
      const hasValue = Object.prototype.hasOwnProperty.call(sourceRow, field.fieldKey);
      const value = hasValue ? sourceRow[field.fieldKey] : undefined;

      if (!hasValue) {
        const defaultValue = parseFieldDefaultValue(field);
        if (defaultValue !== undefined) {
          normalizedRow[field.fieldKey] = defaultValue;
        }
        if (field.required) {
          issues.push({ code: "MISSING_REQUIRED", path: fieldPath, fieldName: field.fieldName });
        }
        return;
      }

      normalizedRow[field.fieldKey] = value;
      if (value === null) {
        if (field.required) {
          issues.push({ code: "MISSING_REQUIRED", path: fieldPath, fieldName: field.fieldName });
        }
        return;
      }
      if (field.required && typeof value === "string" && value.trim() === "") {
        issues.push({ code: "MISSING_REQUIRED", path: fieldPath, fieldName: field.fieldName });
        return;
      }

      const expectedType = normalizeFieldValueType(field.valueType);
      if (!matchesFieldValueType(value, expectedType)) {
        issues.push({
          code: "INVALID_TYPE",
          path: fieldPath,
          fieldName: field.fieldName,
          expectedType,
          actualType: describeJsonType(value),
        });
        return;
      }

      const invalidOption = findInvalidOption(value, field);
      if (invalidOption !== undefined) {
        issues.push({
          code: "INVALID_OPTION",
          path: fieldPath,
          fieldName: field.fieldName,
          allowedOptions: (field.options ?? []).join(", "),
          detail: String(invalidOption),
        });
      }
    });

    const nameValue = sourceRow.name;
    if (typeof nameValue === "string" && nameValue.trim()) {
      const duplicateKey = nameValue.trim();
      const namePath = `${rowPath}.name`;
      const firstPath = nameOccurrences.get(duplicateKey);
      if (firstPath) {
        issues.push({
          code: "DUPLICATE_NAME",
          path: namePath,
          duplicateValue: duplicateKey,
          firstPath,
        });
      } else {
        nameOccurrences.set(duplicateKey, namePath);
      }
    }

    normalizedRows.push(normalizedRow);
  });

  return { rows: issues.length === 0 ? normalizedRows : [], issues };
}

export function buildModelFieldImportTemplate(fields: MetadataFieldDefinition[]) {
  const row: Record<string, unknown> = {};
  fields.forEach((field) => {
    const defaultValue = parseFieldDefaultValue(field);
    row[field.fieldKey] = defaultValue === undefined ? templateValue(field) : defaultValue;
  });
  return JSON.stringify([row], null, 2);
}

export function visibleModelFieldImportIssues(issues: ModelFieldImportIssue[]) {
  return issues.slice(0, MODEL_FIELD_IMPORT_MAX_VISIBLE_ISSUES);
}

export function modelFieldPageCount(totalRows: number, pageSize = MODEL_FIELD_TABLE_PAGE_SIZE) {
  return Math.max(1, Math.ceil(Math.max(0, totalRows) / pageSize));
}

export function normalizeModelFieldPage(page: number, totalRows: number, pageSize = MODEL_FIELD_TABLE_PAGE_SIZE) {
  const normalizedPage = Number.isFinite(page) ? Math.floor(page) : 1;
  return Math.min(Math.max(1, normalizedPage), modelFieldPageCount(totalRows, pageSize));
}

export function modelFieldRowsForPage<T>(rows: T[], page: number, pageSize = MODEL_FIELD_TABLE_PAGE_SIZE) {
  const normalizedPage = normalizeModelFieldPage(page, rows.length, pageSize);
  const startIndex = (normalizedPage - 1) * pageSize;
  return rows.slice(startIndex, startIndex + pageSize);
}

function resolveSourceRows(parsed: unknown, issues: ModelFieldImportIssue[]) {
  if (Array.isArray(parsed)) {
    return parsed;
  }
  if (!isPlainObject(parsed)) {
    issues.push({ code: "INVALID_ROOT", path: "$", actualType: describeJsonType(parsed) });
    return undefined;
  }

  Object.keys(parsed).forEach((key) => {
    if (key !== "columns") {
      issues.push({ code: "UNEXPECTED_ROOT_KEY", path: `$.${key}`, fieldName: key });
    }
  });
  if (!Array.isArray(parsed.columns)) {
    issues.push({ code: "INVALID_ROOT", path: "$.columns", actualType: describeJsonType(parsed.columns) });
    return undefined;
  }
  return parsed.columns;
}

function issueResult(issue: ModelFieldImportIssue): ModelFieldImportResult {
  return { rows: [], issues: [issue] };
}

function stripBom(source: string) {
  return source.charCodeAt(0) === 0xfeff ? source.slice(1) : source;
}

function utf8ByteLength(source: string) {
  return new TextEncoder().encode(source).byteLength;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function normalizeFieldValueType(valueType?: string) {
  return String(valueType || "STRING").trim().toUpperCase();
}

function matchesFieldValueType(value: unknown, valueType: string) {
  switch (valueType) {
    case "BOOLEAN":
      return typeof value === "boolean";
    case "INTEGER":
    case "LONG":
      return typeof value === "number" && Number.isFinite(value) && Number.isInteger(value);
    case "DECIMAL":
      return typeof value === "number" && Number.isFinite(value);
    case "ARRAY":
      return Array.isArray(value);
    case "OBJECT":
      return isPlainObject(value);
    case "JSON":
      return value !== undefined;
    default:
      return typeof value === "string";
  }
}

function findInvalidOption(value: unknown, field: MetadataFieldDefinition) {
  const options = field.options ?? [];
  if (options.length === 0) {
    return undefined;
  }
  if (Array.isArray(value)) {
    return value.find((item) => !options.includes(item as string));
  }
  return options.includes(value as string) ? undefined : value;
}

function parseFieldDefaultValue(field: MetadataFieldDefinition) {
  const rawValue = field.defaultValue;
  if (rawValue === undefined || rawValue === null) {
    return undefined;
  }
  const valueType = normalizeFieldValueType(field.valueType);
  if (valueType === "BOOLEAN") {
    return rawValue === "true";
  }
  if (valueType === "INTEGER" || valueType === "LONG" || valueType === "DECIMAL") {
    const numberValue = Number(rawValue);
    return Number.isNaN(numberValue) ? rawValue : numberValue;
  }
  if (valueType === "JSON" || valueType === "OBJECT" || valueType === "ARRAY") {
    try {
      return JSON.parse(rawValue);
    } catch {
      return rawValue;
    }
  }
  return rawValue;
}

function templateValue(field: MetadataFieldDefinition) {
  const valueType = normalizeFieldValueType(field.valueType);
  const options = field.options ?? [];
  if (valueType === "BOOLEAN") {
    return false;
  }
  if (valueType === "INTEGER" || valueType === "LONG" || valueType === "DECIMAL") {
    return 0;
  }
  if (valueType === "ARRAY") {
    return options.length > 0 ? [options[0]] : [];
  }
  if (valueType === "OBJECT" || valueType === "JSON") {
    return {};
  }
  if (options.length > 0) {
    return options[0];
  }
  if (field.fieldKey === "name") {
    return "example_field";
  }
  if (field.fieldKey === "parentNode") {
    return "root";
  }
  return field.required ? `example_${field.fieldKey}` : "";
}

function describeJsonType(value: unknown) {
  if (value === null) {
    return "null";
  }
  if (Array.isArray(value)) {
    return "array";
  }
  return typeof value;
}
