import type { EntityId } from "@studio/api-sdk";

export function toneFromStatus(status?: string | number | boolean) {
  const value = String(status ?? "").toUpperCase();
  if (value === "SUCCESS" || value === "PUBLISHED" || value === "TRUE" || value === "1") {
    return "success";
  }
  if (value === "FAILED" || value === "ERROR" || value === "FALSE" || value === "0") {
    return "danger";
  }
  if (value === "RUNNING" || value === "QUEUED" || value === "DRAFT" || value === "PENDING" || value === "STOPPING") {
    return "warning";
  }
  if (value === "STOPPED") {
    return "neutral";
  }
  return "primary";
}

export function prettyJson(value: unknown) {
  if (value == null) {
    return "{}";
  }
  if (typeof value === "string") {
    return value;
  }
  return JSON.stringify(value, null, 2);
}

export function parseCommaSeparated(value: unknown) {
  if (typeof value !== "string") {
    return [];
  }
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

export function cloneDeep<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

export function sameEntityId(left: EntityId | null | undefined, right: EntityId | null | undefined) {
  if (left == null || right == null) {
    return false;
  }
  return String(left) === String(right);
}

export function resolveProjectName(
  projects: Array<{ projectId?: EntityId; id?: EntityId; projectName?: string }>,
  projectId: EntityId | null | undefined,
  fallback = "-",
) {
  if (projectId == null) {
    return fallback;
  }
  const matched = projects.find((item) => sameEntityId(item.projectId ?? item.id, projectId));
  return matched?.projectName ?? String(projectId);
}

export function isSharedFromAnotherProject(
  currentProjectId: EntityId | null | undefined,
  ownerProjectId: EntityId | null | undefined,
) {
  return currentProjectId != null && ownerProjectId != null && !sameEntityId(currentProjectId, ownerProjectId);
}

type TranslateFn = (key: string, ...args: unknown[]) => string;

function normalizeEnumValue(value?: string | number | boolean | null) {
  return String(value ?? "").trim().toUpperCase();
}

export function formatStatusLabel(t: TranslateFn, status?: string | number | boolean | null) {
  const value = normalizeEnumValue(status);
  const mapping: Record<string, string> = {
    DRAFT: "common.statusDraft",
    ONLINE: "common.statusOnline",
    PUBLISHED: "common.statusPublished",
    SUCCESS: "common.statusSuccess",
    FAILED: "common.statusFailed",
    ERROR: "common.statusError",
    RUNNING: "common.statusRunning",
    PENDING: "common.statusPending",
    QUEUED: "common.statusQueued",
    STOPPING: "common.statusStopping",
    STOPPED: "common.statusStopped",
    NOT_RUN: "common.statusNotRun",
    UNKNOWN: "common.unknown",
  };
  return mapping[value] ? t(mapping[value]) : String(status ?? t("common.none"));
}

export function formatCollectionTaskType(t: TranslateFn, taskType?: string | null) {
  const value = normalizeEnumValue(taskType);
  const mapping: Record<string, string> = {
    SINGLE_TABLE: "web.collectionTasks.typeSingle",
    FUSION: "web.collectionTasks.typeFusion",
  };
  return mapping[value] ? t(mapping[value]) : String(taskType ?? t("common.none"));
}

export function formatModelKind(t: TranslateFn, modelKind?: string | null) {
  const value = normalizeEnumValue(modelKind);
  const mapping: Record<string, string> = {
    TABLE: "web.models.kindTable",
    VIEW: "web.models.kindView",
    FILE: "web.models.kindFile",
    TOPIC: "web.models.kindTopic",
    MEASUREMENT: "web.models.kindMeasurement",
    DATASET: "web.models.kindDataset",
  };
  return mapping[value] ? t(mapping[value]) : String(modelKind ?? t("common.none"));
}

export function formatNodeType(t: TranslateFn, nodeType?: string | null) {
  const value = normalizeEnumValue(nodeType);
  const mapping: Record<string, string> = {
    COLLECTION_TASK: "web.workflows.nodeTypeCollectionTask",
    DATA_SCRIPT: "web.workflows.nodeTypeDataScript",
    ETL_SINGLE: "web.workflows.nodeTypeEtlSingle",
    FUSION: "web.workflows.nodeTypeFusion",
    CONSISTENCY: "web.workflows.nodeTypeConsistency",
    HTTP: "web.workflows.nodeTypeHttp",
    SHELL: "web.workflows.nodeTypeShell",
  };
  return mapping[value] ? t(mapping[value]) : String(nodeType ?? t("common.none"));
}

export function formatScriptType(t: TranslateFn, scriptType?: string | null) {
  const value = normalizeEnumValue(scriptType);
  const mapping: Record<string, string> = {
    SQL: "web.dataDevelopment.scriptTypeSql",
    JAVA: "web.dataDevelopment.scriptTypeJava",
    PYTHON: "web.dataDevelopment.scriptTypePython",
  };
  return mapping[value] ? t(mapping[value]) : String(scriptType ?? t("common.none"));
}
