export function toneFromStatus(status?: string | number | boolean) {
  const value = String(status ?? "").toUpperCase();
  if (value === "SUCCESS" || value === "PUBLISHED" || value === "TRUE" || value === "1") {
    return "success";
  }
  if (value === "FAILED" || value === "ERROR" || value === "FALSE" || value === "0") {
    return "danger";
  }
  if (value === "RUNNING" || value === "QUEUED" || value === "DRAFT") {
    return "warning";
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
