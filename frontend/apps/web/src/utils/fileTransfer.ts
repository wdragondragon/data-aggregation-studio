import type {
  FileTransferFileEntryView,
  FileTransferPolicy,
  FileTransferRunItemView,
  FileTransferRunView,
  FileTransferVerificationMode,
} from "@studio/api-sdk";

export type FileTransferStatusTone = "primary" | "success" | "warning" | "danger" | "neutral";

const STATUS_LABELS: Record<string, string> = {
  DISCOVERING: "发现中",
  QUEUED: "排队中",
  TRANSFERRING: "传输中",
  VERIFYING: "校验中",
  COMMITTING: "提交中",
  POST_ACTION: "源端处理中",
  RUNNING: "运行中",
  PAUSED: "已暂停",
  SUCCESS: "成功",
  PARTIAL_SUCCESS: "部分成功",
  SKIPPED: "摘要一致，已跳过",
  CONFLICT: "目标冲突",
  POST_ACTION_FAILED: "源端处理失败",
  FAILED: "失败",
  CANCELED: "已取消",
  DRAFT: "草稿",
  ONLINE: "已发布",
  OFFLINE: "已下线",
};

export function fileTransferStatusLabel(status?: string | null) {
  const normalized = String(status ?? "").trim().toUpperCase();
  return (STATUS_LABELS[normalized] ?? normalized) || "--";
}

export function fileTransferStatusTone(status?: string | null): FileTransferStatusTone {
  const normalized = String(status ?? "").trim().toUpperCase();
  if (["SUCCESS", "ONLINE", "SKIPPED"].includes(normalized)) return "success";
  if (["FAILED", "CONFLICT", "POST_ACTION_FAILED", "CANCELED"].includes(normalized)) return "danger";
  if (["RUNNING", "TRANSFERRING", "VERIFYING", "COMMITTING", "POST_ACTION"].includes(normalized)) return "primary";
  if (["QUEUED", "DISCOVERING", "PAUSED", "PARTIAL_SUCCESS", "DRAFT", "OFFLINE"].includes(normalized)) return "warning";
  return "neutral";
}

export function fileTransferFailureMessage(message?: string | null) {
  const value = String(message ?? "").trim();
  if (value.startsWith("FILE_TRANSFER_NO_FILES_DISCOVERED")) {
    return "所选目录未发现可传输文件";
  }
  return value || "--";
}

export function toTransferNumber(value: unknown) {
  if (typeof value === "number") return Number.isFinite(value) ? value : 0;
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

export function formatTransferNumber(value: unknown) {
  return new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 0 }).format(toTransferNumber(value));
}

export function formatBytes(value: unknown) {
  const bytes = Math.max(0, toTransferNumber(value));
  if (bytes === 0) return "0 B";
  const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
  const index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  const amount = bytes / 1024 ** index;
  const maximumFractionDigits = amount >= 100 || index === 0 ? 0 : amount >= 10 ? 1 : 2;
  return `${amount.toLocaleString("zh-CN", { maximumFractionDigits })} ${units[index]}`;
}

export function formatTransferSpeed(value: unknown) {
  return `${formatBytes(value)}/s`;
}

export function fileTransferVerificationModeLabel(mode?: string | null) {
  return ({
    NONE: "不校验",
    PARTIAL: "部分校验",
    STRONG: "强校验",
  } as Record<string, string>)[String(mode ?? "STRONG").toUpperCase()] || "强校验";
}

export function fileTransferVerificationSummary(policy?: FileTransferPolicy | null) {
  const mode = String(policy?.verificationMode ?? "STRONG").toUpperCase() as FileTransferVerificationMode;
  if (mode !== "PARTIAL") return fileTransferVerificationModeLabel(mode);
  return `部分校验 · ${policy?.verificationFrameCount ?? 16} 帧 × ${formatBytes(policy?.verificationFrameSizeBytes ?? 1024 * 1024)}`;
}

export function fileTransferItemVerificationLabel(item: FileTransferRunItemView) {
  const configured = String(item.verificationModeConfigured ?? "STRONG").toUpperCase();
  const effective = String(item.verificationModeEffective ?? configured).toUpperCase();
  if (configured === "PARTIAL" && effective === "STRONG") {
    return "强校验（采样范围已覆盖文件）";
  }
  return fileTransferVerificationModeLabel(effective);
}

export function fileTransferVerificationPhaseLabel(item: FileTransferRunItemView) {
  return String(item.verificationPhase ?? "").toUpperCase() === "TARGET_SAMPLE"
    ? "目标采样"
    : "目标校验";
}

export function formatDurationSeconds(value: unknown) {
  const totalSeconds = Math.max(0, Math.round(toTransferNumber(value)));
  if (!totalSeconds) return "--";
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours) return `${hours}时 ${minutes}分`;
  if (minutes) return `${minutes}分 ${seconds}秒`;
  return `${seconds}秒`;
}

export function formatTransferDate(value?: string | number | null) {
  if (value == null || value === "") return "--";
  const date = typeof value === "number" ? new Date(value) : new Date(String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).format(date).replace(/\//g, "-");
}

export function formatEntryModifiedAt(entry: FileTransferFileEntryView) {
  const millis = toTransferNumber(entry.modifiedAtMillis);
  return millis > 0 ? formatTransferDate(millis) : "--";
}

export function transferProgress(transferred: unknown, total: unknown) {
  const denominator = toTransferNumber(total);
  if (denominator <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((toTransferNumber(transferred) / denominator) * 100)));
}

export function normalizeTransferPath(path?: string | null) {
  const raw = String(path ?? "/").trim().replace(/\\/g, "/");
  const normalized = raw.replace(/\/{2,}/g, "/");
  if (!normalized || normalized === ".") return "/";
  return normalized.startsWith("/") ? normalized : `/${normalized}`;
}

export function joinTransferPath(parent: string, child: string) {
  const normalizedParent = normalizeTransferPath(parent).replace(/\/$/, "");
  const normalizedChild = String(child ?? "").replace(/\\/g, "/").replace(/^\/+/, "");
  return normalizeTransferPath(`${normalizedParent}/${normalizedChild}`);
}

export function parentTransferPath(path: string) {
  const normalized = normalizeTransferPath(path);
  if (normalized === "/") return "/";
  const segments = normalized.split("/").filter(Boolean);
  segments.pop();
  return segments.length ? `/${segments.join("/")}` : "/";
}

export function transferPathSegments(path: string) {
  const normalized = normalizeTransferPath(path);
  const segments = normalized.split("/").filter(Boolean);
  return segments.map((label: string, index: number) => ({
    label,
    path: `/${segments.slice(0, index + 1).join("/")}`,
  }));
}

export function isFileTransferDatasourceType(typeCode?: string | null) {
  const normalized = String(typeCode ?? "").trim().toLowerCase().replace(/_/g, "-");
  return ["local", "ftp", "sftp", "minio", "oss", "aliyun-oss", "aliyunoss"].includes(normalized);
}

export function fileTransferDirectionLabel(direction?: string | null) {
  return String(direction ?? "").toUpperCase() === "RIGHT_TO_LEFT" ? "右传左" : "左传右";
}

export function fileTransferTriggerLabel(trigger?: string | null) {
  const normalized = String(trigger ?? "").trim().toUpperCase();
  return ({
    MANUAL: "即时",
    MANUAL_TASK: "手动",
    SCHEDULE: "定时",
    WORKFLOW: "流程",
  } as Record<string, string>)[normalized] || normalized || "触发";
}

export function fileTransferRunTriggerName(run: FileTransferRunView) {
  const scheduledAt = typeof run.resolvedSpec?.scheduledFireTime === "string"
    ? run.resolvedSpec.scheduledFireTime
    : undefined;
  const time = formatShortTransferDate(scheduledAt || run.createdAt || run.startedAt);
  return `${time} · ${fileTransferTriggerLabel(run.triggerType)}`;
}

export function formatShortTransferDate(value?: string | number | null) {
  if (value == null || value === "") return "--";
  const date = typeof value === "number" ? new Date(value) : new Date(String(value).replace(" ", "T"));
  if (Number.isNaN(date.getTime())) return String(value);
  const pad = (item: number) => String(item).padStart(2, "0");
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function fileTransferEndpointText(
  _runtimeClusterName?: string | null,
  datasourceName?: string | null,
  path?: string | null,
) {
  return `[${datasourceName || "未知数据源"}] ${path || "--"}`;
}
