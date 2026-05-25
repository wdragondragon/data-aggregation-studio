import type { ModelSyncTaskView } from "@studio/api-sdk";

export const modelSyncTaskStatusOptions = ["PENDING", "RUNNING", "STOPPING", "SUCCESS", "FAILED", "STOPPED"];

export function canStopSyncTask(task: ModelSyncTaskView) {
  return ["PENDING", "RUNNING", "STOPPING"].includes(String(task.status || "").toUpperCase());
}

export function canDeleteSyncTask(task: ModelSyncTaskView) {
  return ["SUCCESS", "FAILED", "STOPPED"].includes(String(task.status || "").toUpperCase());
}

export function formatSyncTaskDurationMs(durationMs: number | undefined, emptyText: string) {
  if (durationMs == null || Number.isNaN(Number(durationMs))) {
    return emptyText;
  }
  if (durationMs < 1000) {
    return `${durationMs} ms`;
  }
  const seconds = durationMs / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(seconds >= 10 ? 1 : 2)} s`;
  }
  const minutes = Math.floor(seconds / 60);
  const remainderSeconds = Math.round(seconds % 60);
  return `${minutes}m ${remainderSeconds}s`;
}

export function normalizeModelListTab(value: unknown) {
  return value === "sync-tasks" ? "sync-tasks" : "models";
}
