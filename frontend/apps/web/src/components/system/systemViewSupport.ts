export const resourceTypeOptions = [
  { label: "数据源", value: "DATASOURCE" },
  { label: "模型", value: "DATA_MODEL" },
  { label: "采集任务", value: "COLLECTION_TASK" },
  { label: "工作流", value: "WORKFLOW" },
  { label: "数据开发脚本", value: "DATA_DEVELOPMENT_SCRIPT" },
] as const;

export function normalizeResourceType(value?: string) {
  return String(value ?? "").trim().toUpperCase();
}

export function toBooleanFlag(value: boolean | number | null | undefined) {
  return value === true || value === 1;
}

export function toIntegerFlag(value: boolean | number | null | undefined) {
  return toBooleanFlag(value) ? 1 : 0;
}

export function normalizeDeletedFlag<T extends { deleted?: boolean | number }>(payload: T): T {
  if (payload.deleted == null) {
    return { ...payload };
  }
  return { ...payload, deleted: toIntegerFlag(payload.deleted) } as T;
}
