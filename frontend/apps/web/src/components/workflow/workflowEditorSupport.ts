import type { DataDevelopmentTreeNode } from "@studio/api-sdk";

export interface DialogPagination {
  page: number;
  pageSize: number;
}

export type HttpParamSection = "queryParams" | "headers";
export type HttpParamField = "enabled" | "name" | "value";

export interface HttpParamRow {
  enabled?: boolean;
  name: string;
  value: string;
}

export function parseDataScriptArguments(value: unknown, invalidMessage: string) {
  if (value == null) {
    return {};
  }
  if (typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  const text = String(value).trim();
  if (!text) {
    return {};
  }
  const parsed = JSON.parse(text);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(invalidMessage);
  }
  return parsed as Record<string, unknown>;
}

export function normalizeMaxRows(value: unknown) {
  if (typeof value === "number") {
    return value;
  }
  if (typeof value === "string" && value.trim()) {
    return Number(value);
  }
  return 100;
}

export function normalizeHttpParamRows(value: unknown): HttpParamRow[] {
  if (Array.isArray(value)) {
    return value
      .filter((item) => item != null)
      .map((item) => {
        if (typeof item === "object" && !Array.isArray(item)) {
          const record = item as Record<string, unknown>;
          return {
            enabled: record.enabled == null ? true : Boolean(record.enabled),
            name: String(record.name ?? record.key ?? ""),
            value: String(record.value ?? ""),
          };
        }
        return {
          enabled: true,
          name: "",
          value: String(item),
        };
      });
  }
  if (value && typeof value === "object") {
    return Object.entries(value as Record<string, unknown>).map(([name, itemValue]) => ({
      enabled: true,
      name,
      value: itemValue == null ? "" : String(itemValue),
    }));
  }
  return [];
}

export function parseHttpBody(value: string, invalidMessage: string) {
  const text = value.trim();
  if (!text) {
    return undefined;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new Error(invalidMessage);
  }
}

export function matchesKeyword(keyword: string, values: unknown[]) {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) {
    return true;
  }
  return values
    .filter((value) => value != null)
    .some((value) => String(value).toLowerCase().includes(normalized));
}

export function paginateItems<T>(items: T[], pagination: DialogPagination) {
  const start = (pagination.page - 1) * pagination.pageSize;
  return items.slice(start, start + pagination.pageSize);
}

export function getDialogRowIndex(pagination: DialogPagination, index: number) {
  return (pagination.page - 1) * pagination.pageSize + index + 1;
}

export function filterScriptTree(nodes: DataDevelopmentTreeNode[], keyword: string): DataDevelopmentTreeNode[] {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) {
    return nodes;
  }
  return nodes
    .map((node) => {
      const children = filterScriptTree(node.children ?? [], keyword);
      const selfMatched = [
        node.name,
        node.scriptType,
        node.datasourceName,
        node.permissionCode,
        node.scriptId,
        node.directoryId,
      ]
        .filter((value) => value != null)
        .some((value) => String(value).toLowerCase().includes(normalized));
      if (selfMatched || children.length > 0) {
        return {
          ...node,
          children,
        };
      }
      return null;
    })
    .filter((node): node is DataDevelopmentTreeNode => node != null);
}

export function formatNullableText(value: unknown) {
  return value == null || value === "" ? "-" : String(value);
}

export function formatQualityDimension(t: (key: string) => string, value?: string | null) {
  const mapping: Record<string, string> = {
    CONSISTENCY: "web.workflows.qualityDimensionConsistency",
    ACCURACY: "web.workflows.qualityDimensionAccuracy",
    UNIQUENESS: "web.workflows.qualityDimensionUniqueness",
    TIMELINESS: "web.workflows.qualityDimensionTimeliness",
    COMPLETENESS: "web.workflows.qualityDimensionCompleteness",
    VALIDITY: "web.workflows.qualityDimensionValidity",
  };
  return value && mapping[value] ? t(mapping[value]) : String(value ?? t("common.none"));
}

export function formatQualityGranularity(t: (key: string) => string, value?: string | null) {
  if (value === "COLUMN") {
    return t("web.workflows.qualityGranularityColumn");
  }
  if (value === "TABLE") {
    return t("web.workflows.qualityGranularityTable");
  }
  return String(value ?? t("common.none"));
}
