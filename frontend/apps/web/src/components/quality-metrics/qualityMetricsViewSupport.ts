import type {
  EntityId,
  QualityAssetRiskView,
  QualityIssueSeverity,
  QualityIssueStatus,
  QualityIssueView,
  QualityMetricDashboardView,
} from "@studio/api-sdk";

export type TimePreset = "24h" | "7d" | "30d" | "custom";
export type PillTone = "primary" | "success" | "warning" | "danger" | "neutral";

export const dimensionOptions = [
  { label: "一致性", value: "CONSISTENCY" },
  { label: "准确性", value: "ACCURACY" },
  { label: "唯一性", value: "UNIQUENESS" },
  { label: "及时性", value: "TIMELINESS" },
  { label: "完整性", value: "COMPLETENESS" },
  { label: "有效性", value: "VALIDITY" },
];

export const severityOptions: Array<{ label: string; value: QualityIssueSeverity }> = [
  { label: "低", value: "LOW" },
  { label: "中", value: "MEDIUM" },
  { label: "高", value: "HIGH" },
  { label: "严重", value: "CRITICAL" },
];

export const issueStatusOptions: Array<{ label: string; value: QualityIssueStatus }> = [
  { label: "待处理", value: "OPEN" },
  { label: "已认领", value: "ACKNOWLEDGED" },
  { label: "排查中", value: "INVESTIGATING" },
  { label: "已缓解", value: "MITIGATED" },
  { label: "已解决", value: "RESOLVED" },
  { label: "误报", value: "FALSE_POSITIVE" },
];

export function presetRange(preset: TimePreset): [string, string] {
  const end = new Date();
  const start = new Date(end);
  if (preset === "24h") {
    start.setHours(start.getHours() - 24);
  } else if (preset === "30d") {
    start.setDate(start.getDate() - 30);
  } else {
    start.setDate(start.getDate() - 7);
  }
  return [formatDateTime(start), formatDateTime(end)];
}

export function emptyDashboard(): QualityMetricDashboardView {
  return {
    summaryMetrics: {},
    scoreTrend: [],
    issueTrend: [],
    dimensionDistribution: [],
    coverageMatrix: [],
    riskyAssets: [],
    noisyTargets: [],
  };
}

export function fieldIssues(group: Record<string, unknown>) {
  const items = group.issues;
  return Array.isArray(items) ? items as QualityIssueView[] : [];
}

export function assetTitle(asset?: Pick<QualityAssetRiskView, "modelName" | "modelPhysicalLocator" | "assetId"> | null) {
  return asset?.modelName || asset?.modelPhysicalLocator || asset?.assetId || "-";
}

export function dimensionLabel(value?: string | null) {
  return dimensionOptions.find((item) => item.value === value)?.label ?? value ?? "-";
}

export function granularityLabel(value?: string | null) {
  if (value === "COLUMN") {
    return "字段级";
  }
  if (value === "TABLE") {
    return "表级";
  }
  return value || "-";
}

export function severityLabel(value?: string | null) {
  return severityOptions.find((item) => item.value === value)?.label ?? value ?? "-";
}

export function issueStatusLabel(value?: string | null) {
  return issueStatusOptions.find((item) => item.value === value)?.label ?? value ?? "-";
}

export function issueTypeLabel(value?: string | null) {
  if (value === "EXECUTION_FAILURE") {
    return "执行失败";
  }
  if (value === "ALERT") {
    return "告警命中";
  }
  return value || "-";
}

export function severityTone(value?: string | null): PillTone {
  if (value === "CRITICAL") {
    return "danger";
  }
  if (value === "HIGH") {
    return "warning";
  }
  if (value === "MEDIUM") {
    return "primary";
  }
  return "neutral";
}

export function issueStatusTone(value?: string | null): PillTone {
  if (value === "RESOLVED") {
    return "success";
  }
  if (value === "FALSE_POSITIVE") {
    return "neutral";
  }
  if (value === "MITIGATED" || value === "ACKNOWLEDGED") {
    return "primary";
  }
  if (value === "INVESTIGATING") {
    return "warning";
  }
  return "danger";
}

export function runStatusTone(value?: string | null): PillTone {
  const status = String(value || "").toUpperCase();
  if (status === "SUCCESS" || status === "ONLINE") {
    return "success";
  }
  if (status.includes("FAIL")) {
    return "danger";
  }
  if (status.includes("RUN")) {
    return "primary";
  }
  return "neutral";
}

export function scorePercent(value?: number) {
  return Math.max(0, Math.min(100, toNumber(value)));
}

export function recordText(row: Record<string, unknown>, key: string) {
  const value = row[key];
  return value == null ? "" : String(value);
}

export function recordNumber(row: Record<string, unknown>, key: string) {
  return toNumber(row[key]);
}

export function recordId(row: Record<string, unknown>, key: string): EntityId | null {
  const value = row[key];
  return typeof value === "string" || typeof value === "number" ? value : null;
}

export function toNumber(value: unknown) {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

export function formatNumber(value: unknown) {
  return toNumber(value).toLocaleString();
}

export function formatJson(value: unknown) {
  if (value == null) {
    return "{}";
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

export function unique(items: string[]) {
  return [...new Set(items.filter(Boolean))];
}

function formatDateTime(value: Date) {
  const pad = (input: number) => String(input).padStart(2, "0");
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())} ${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`;
}
