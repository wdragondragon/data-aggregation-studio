import type { EntityId } from "@studio/api-sdk";
import { RUNNING_STATUSES, STUDIO_NODE_TYPE, STUDIO_RUN_STATUS, TERMINAL_STATUSES } from "@/constants/studioDomain";

export function toneFromStatus(status?: string | number | boolean) {
  const value = String(status ?? "").toUpperCase();
  if (value === STUDIO_RUN_STATUS.SUCCESS || value === STUDIO_RUN_STATUS.PUBLISHED || value === "TRUE" || value === "1") {
    return "success";
  }
  if (value === STUDIO_RUN_STATUS.FAILED || value === STUDIO_RUN_STATUS.ERROR || value === "FALSE" || value === "0") {
    return "danger";
  }
  if (RUNNING_STATUSES.has(value) || value === STUDIO_RUN_STATUS.DRAFT) {
    return "warning";
  }
  if (value === STUDIO_RUN_STATUS.STOPPED) {
    return "neutral";
  }
  return "primary";
}

export function isRunningStatus(status?: string | number | boolean | null) {
  return RUNNING_STATUSES.has(normalizeEnumValue(status));
}

export function isTerminalStatus(status?: string | number | boolean | null) {
  return TERMINAL_STATUSES.has(normalizeEnumValue(status));
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
    [STUDIO_RUN_STATUS.DRAFT]: "common.statusDraft",
    [STUDIO_RUN_STATUS.ONLINE]: "common.statusOnline",
    [STUDIO_RUN_STATUS.PUBLISHED]: "common.statusPublished",
    [STUDIO_RUN_STATUS.SUCCESS]: "common.statusSuccess",
    [STUDIO_RUN_STATUS.FAILED]: "common.statusFailed",
    [STUDIO_RUN_STATUS.ERROR]: "common.statusError",
    [STUDIO_RUN_STATUS.RUNNING]: "common.statusRunning",
    [STUDIO_RUN_STATUS.PENDING]: "common.statusPending",
    [STUDIO_RUN_STATUS.QUEUED]: "common.statusQueued",
    [STUDIO_RUN_STATUS.STOPPING]: "common.statusStopping",
    [STUDIO_RUN_STATUS.STOPPED]: "common.statusStopped",
    [STUDIO_RUN_STATUS.NOT_RUN]: "common.statusNotRun",
    [STUDIO_RUN_STATUS.UNKNOWN]: "common.unknown",
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
    [STUDIO_NODE_TYPE.COLLECTION_TASK]: "web.workflows.nodeTypeCollectionTask",
    [STUDIO_NODE_TYPE.QUALITY_TASK]: "routes.web.qualityTasks.title",
    [STUDIO_NODE_TYPE.FILE_TRANSFER]: "routes.web.fileTransfer.title",
    [STUDIO_NODE_TYPE.DATA_SCRIPT]: "web.workflows.nodeTypeDataScript",
    [STUDIO_NODE_TYPE.ETL_SINGLE]: "web.workflows.nodeTypeEtlSingle",
    [STUDIO_NODE_TYPE.FUSION]: "web.workflows.nodeTypeFusion",
    [STUDIO_NODE_TYPE.CONSISTENCY]: "web.workflows.nodeTypeConsistency",
    [STUDIO_NODE_TYPE.HTTP]: "web.workflows.nodeTypeHttp",
    [STUDIO_NODE_TYPE.SHELL]: "web.workflows.nodeTypeShell",
  };
  return mapping[value] ? t(mapping[value]) : String(nodeType ?? t("common.none"));
}

export function formatScriptType(t: TranslateFn, scriptType?: string | null) {
  const value = normalizeEnumValue(scriptType);
  const mapping: Record<string, string> = {
    SQL: "web.dataDevelopment.scriptTypeSql",
    FLINK_QUESTION_SQL: "web.dataDevelopment.scriptTypeFlinkQuestionSql",
    JAVA: "web.dataDevelopment.scriptTypeJava",
    PYTHON: "web.dataDevelopment.scriptTypePython",
  };
  return mapping[value] ? t(mapping[value]) : String(scriptType ?? t("common.none"));
}
