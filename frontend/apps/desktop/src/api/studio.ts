import { createStudioApi } from "@studio/api-sdk";
import type { AssistantPlanRequest } from "@studio/api-sdk";

export const DESKTOP_TOKEN_KEY = "studio_desktop_token";
export const DESKTOP_USERNAME_KEY = "studio_desktop_username";
export const DESKTOP_TENANT_KEY = "studio_desktop_current_tenant";
export const DESKTOP_PROJECT_KEY = "studio_desktop_current_project";
export const DESKTOP_MANUAL_LOGOUT_KEY = "studio_desktop_manual_logout";
export const STUDIO_TOKEN_KEY = DESKTOP_TOKEN_KEY;
export const STUDIO_USERNAME_KEY = DESKTOP_USERNAME_KEY;
export const STUDIO_TENANT_KEY = DESKTOP_TENANT_KEY;
export const STUDIO_PROJECT_KEY = DESKTOP_PROJECT_KEY;
export const STUDIO_MANUAL_LOGOUT_KEY = DESKTOP_MANUAL_LOGOUT_KEY;

export function getStoredToken() {
  return window.localStorage.getItem(DESKTOP_TOKEN_KEY);
}

export function setStoredToken(token: string) {
  window.localStorage.setItem(DESKTOP_TOKEN_KEY, token);
}

export function clearStoredToken() {
  window.localStorage.removeItem(DESKTOP_TOKEN_KEY);
  window.localStorage.removeItem(DESKTOP_USERNAME_KEY);
  window.localStorage.removeItem(DESKTOP_TENANT_KEY);
  window.localStorage.removeItem(DESKTOP_PROJECT_KEY);
}

export function markManualLogout() {
  window.localStorage.setItem(DESKTOP_MANUAL_LOGOUT_KEY, "1");
}

export function clearManualLogout() {
  window.localStorage.removeItem(DESKTOP_MANUAL_LOGOUT_KEY);
}

export function hasManualLogout() {
  return window.localStorage.getItem(DESKTOP_MANUAL_LOGOUT_KEY) === "1";
}

export function getStoredTenantId() {
  return window.localStorage.getItem(DESKTOP_TENANT_KEY);
}

export function setStoredTenantId(tenantId: string | null | undefined) {
  if (!tenantId) {
    window.localStorage.removeItem(DESKTOP_TENANT_KEY);
    return;
  }
  window.localStorage.setItem(DESKTOP_TENANT_KEY, tenantId);
}

export function getStoredProjectId() {
  return window.localStorage.getItem(DESKTOP_PROJECT_KEY);
}

export function setStoredProjectId(projectId: string | number | null | undefined) {
  if (projectId == null || projectId === "") {
    window.localStorage.removeItem(DESKTOP_PROJECT_KEY);
    return;
  }
  window.localStorage.setItem(DESKTOP_PROJECT_KEY, String(projectId));
}

export function resolveStudioApiBaseUrl(path = "") {
  const baseUrl = import.meta.env.VITE_API_BASE_URL ?? "/api/v1";
  if (!path) {
    return baseUrl;
  }
  if (path.startsWith("/")) {
    return `${baseUrl}${path}`;
  }
  return `${baseUrl}/${path}`;
}

export function resolveDataServiceOpenUrl(endpointPath?: string) {
  if (!endpointPath) {
    return "";
  }
  return `${resolveDataServiceOpenBaseUrl()}${endpointPath.startsWith("/") ? endpointPath : `/${endpointPath}`}`;
}

function resolveDataServiceOpenBaseUrl() {
  const configured = normalizeBaseUrl(import.meta.env.VITE_DATA_SERVICE_OPEN_BASE_URL);
  if (configured) {
    return configured;
  }

  const apiBaseUrl = resolveStudioApiBaseUrl();
  if (/^https?:\/\//i.test(apiBaseUrl)) {
    try {
      const url = new URL(apiBaseUrl);
      const pathname = url.pathname.replace(/\/api\/v1\/?$/i, "").replace(/\/api\/?$/i, "");
      return `${url.origin}${pathname}`.replace(/\/$/, "");
    } catch {
      // Fall through to the environment defaults below.
    }
  }

  if (import.meta.env.DEV) {
    return "http://127.0.0.1:18180";
  }

  return window.location.origin;
}

function normalizeBaseUrl(value: unknown) {
  if (typeof value !== "string") {
    return "";
  }
  return value.trim().replace(/\/$/, "");
}

export function isGatewayStudioMode() {
  const baseUrl = resolveStudioApiBaseUrl();
  return baseUrl.indexOf("/data-aggregation-studio/") >= 0
    || baseUrl.indexOf("/data-aggregation-studio") === 0
    || baseUrl.indexOf("data-aggregation-studio/api/v1") >= 0;
}

export function getGatewayStudioEntryUrl() {
  const entryUrl = import.meta.env.VITE_GATEWAY_STUDIO_ENTRY_URL;
  if (typeof entryUrl !== "string") {
    return null;
  }
  const trimmed = entryUrl.trim();
  return trimmed ? trimmed : null;
}

export function buildStudioRequestHeaders() {
  const headers: Record<string, string> = {};
  const token = getStoredToken();
  const tenantId = getStoredTenantId();
  const projectId = getStoredProjectId();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (tenantId) {
    headers["X-Tenant-Id"] = tenantId;
  }
  if (projectId) {
    headers["X-Project-Id"] = String(projectId);
  }
  return headers;
}

export interface AssistantStreamHandlers {
  onDelta?: (content: string) => void;
  onProgress?: (event: AssistantProgressEvent) => void;
  onError?: (message: string) => void;
  onDone?: () => void;
}

export interface AssistantProgressEvent {
  stage?: string;
  title?: string;
  detail?: string;
  status?: "running" | "done" | "warning" | "error" | string;
  metadata?: Record<string, unknown>;
}

type AssistantSseFrameStatus = "continue" | "done" | "error";

export async function streamAssistantChat(
  payload: AssistantPlanRequest,
  handlers: AssistantStreamHandlers,
  signal?: AbortSignal,
) {
  const response = await fetch(resolveStudioApiBaseUrl("/assistant/chat/stream"), {
    method: "POST",
    headers: {
      ...buildStudioRequestHeaders(),
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    body: JSON.stringify(payload),
    signal,
  });
  if (!response.ok) {
    throw new Error(`助手流式接口请求失败：HTTP ${response.status}`);
  }
  if (!response.body) {
    throw new Error("助手流式接口没有返回响应体");
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        buffer += decoder.decode();
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() ?? "";
      for (let index = 0; index < frames.length; index++) {
        const status = handleAssistantSseFrame(frames[index], handlers);
        if (status !== "continue") {
          await cancelAssistantStreamReader(reader);
          return;
        }
        if ((index + 1) % 12 === 0) {
          await yieldToBrowser();
        }
      }
      if (frames.length) {
        await yieldToBrowser();
      }
    }
    if (buffer.trim()) {
      const status = handleAssistantSseFrame(buffer, handlers);
      if (status !== "continue") {
        return;
      }
    }
    throw new Error("助手流式响应提前结束，未收到完成事件");
  } finally {
    await cancelAssistantStreamReader(reader);
  }
}

function cancelAssistantStreamReader(reader: ReadableStreamDefaultReader<Uint8Array>) {
  return reader.cancel().catch(() => undefined);
}

function yieldToBrowser() {
  return new Promise<void>((resolve) => {
    if (typeof window !== "undefined" && typeof window.requestAnimationFrame === "function") {
      window.requestAnimationFrame(() => resolve());
      return;
    }
    setTimeout(resolve, 0);
  });
}

function handleAssistantSseFrame(frame: string, handlers: AssistantStreamHandlers): AssistantSseFrameStatus {
  const lines = frame.split(/\r?\n/);
  let eventName = "message";
  const dataLines: string[] = [];
  for (const line of lines) {
    if (line.startsWith("event:")) {
      eventName = line.slice("event:".length).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice("data:".length).trim());
    }
  }
  const rawData = dataLines.join("\n");
  let data: Record<string, unknown> = {};
  try {
    data = rawData ? JSON.parse(rawData) as Record<string, unknown> : {};
  } catch {
    handlers.onError?.("助手流式响应解析失败");
    return "error";
  }
  if (eventName === "delta") {
    const content = String(data.content ?? "");
    if (content) {
      handlers.onDelta?.(content);
    }
  } else if (eventName === "progress") {
    handlers.onProgress?.(data as AssistantProgressEvent);
  } else if (eventName === "error") {
    handlers.onError?.(String(data.message ?? "助手流式响应失败"));
    return "error";
  } else if (eventName === "done") {
    handlers.onDone?.();
    return "done";
  }
  return "continue";
}

export const desktopApi = createStudioApi({
  baseURL: resolveStudioApiBaseUrl(),
  getToken: getStoredToken,
  getTenantId: getStoredTenantId,
  getProjectId: getStoredProjectId,
  onUnauthorized: () => {
    clearStoredToken();
    if (window.location.hash !== "#/login") {
      window.location.hash = "/login";
    }
  },
});

export const studioApi = desktopApi;
