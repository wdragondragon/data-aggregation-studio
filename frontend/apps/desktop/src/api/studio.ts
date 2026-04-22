import { createStudioApi } from "@studio/api-sdk";

export const DESKTOP_TOKEN_KEY = "studio_desktop_token";
export const DESKTOP_USERNAME_KEY = "studio_desktop_username";
export const DESKTOP_TENANT_KEY = "studio_desktop_current_tenant";
export const DESKTOP_PROJECT_KEY = "studio_desktop_current_project";
export const STUDIO_TOKEN_KEY = DESKTOP_TOKEN_KEY;
export const STUDIO_USERNAME_KEY = DESKTOP_USERNAME_KEY;
export const STUDIO_TENANT_KEY = DESKTOP_TENANT_KEY;
export const STUDIO_PROJECT_KEY = DESKTOP_PROJECT_KEY;

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
