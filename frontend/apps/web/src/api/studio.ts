import { createStudioApi } from "@studio/api-sdk";

export const STUDIO_TOKEN_KEY = "studio_token";
export const STUDIO_USERNAME_KEY = "studio_username";
export const STUDIO_TENANT_KEY = "studio_current_tenant";
export const STUDIO_PROJECT_KEY = "studio_current_project";
export const STUDIO_MANUAL_LOGOUT_KEY = "studio_manual_logout";
const DEFAULT_API_BASE_PATH = "api/v1";

export function getStoredToken() {
  return window.localStorage.getItem(STUDIO_TOKEN_KEY);
}

export function setStoredToken(token: string) {
  window.localStorage.setItem(STUDIO_TOKEN_KEY, token);
}

export function clearStoredToken() {
  window.localStorage.removeItem(STUDIO_TOKEN_KEY);
  window.localStorage.removeItem(STUDIO_USERNAME_KEY);
  window.localStorage.removeItem(STUDIO_TENANT_KEY);
  window.localStorage.removeItem(STUDIO_PROJECT_KEY);
}

export function markManualLogout() {
  window.localStorage.setItem(STUDIO_MANUAL_LOGOUT_KEY, "1");
}

export function clearManualLogout() {
  window.localStorage.removeItem(STUDIO_MANUAL_LOGOUT_KEY);
}

export function hasManualLogout() {
  return window.localStorage.getItem(STUDIO_MANUAL_LOGOUT_KEY) === "1";
}

export function getStoredTenantId() {
  return window.localStorage.getItem(STUDIO_TENANT_KEY);
}

export function setStoredTenantId(tenantId: string | null | undefined) {
  if (!tenantId) {
    window.localStorage.removeItem(STUDIO_TENANT_KEY);
    return;
  }
  window.localStorage.setItem(STUDIO_TENANT_KEY, tenantId);
}

export function getStoredProjectId() {
  return window.localStorage.getItem(STUDIO_PROJECT_KEY);
}

export function setStoredProjectId(projectId: string | number | null | undefined) {
  if (projectId == null || projectId === "") {
    window.localStorage.removeItem(STUDIO_PROJECT_KEY);
    return;
  }
  window.localStorage.setItem(STUDIO_PROJECT_KEY, String(projectId));
}

export function resolveStudioApiBaseUrl(path = "") {
  const baseUrl = resolveApiBaseUrl(import.meta.env.VITE_API_BASE_URL);
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

  const apiBaseUrl = normalizeBaseUrl(resolveStudioApiBaseUrl());
  const gatewayStudioBaseUrl = resolveGatewayStudioBaseUrl(apiBaseUrl);
  if (gatewayStudioBaseUrl) {
    return gatewayStudioBaseUrl;
  }

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
    return "http://127.0.0.1:18080";
  }

  return window.location.origin;
}

function resolveGatewayStudioBaseUrl(apiBaseUrl: string) {
  const routePrefix = "/data-aggregation-studio";
  const routePrefixIndex = apiBaseUrl.indexOf(routePrefix);
  if (routePrefixIndex < 0) {
    return "";
  }
  return apiBaseUrl.slice(0, routePrefixIndex + routePrefix.length);
}

function normalizeBaseUrl(value: unknown) {
  if (typeof value !== "string") {
    return "";
  }
  return value.trim().replace(/\/$/, "");
}

function resolveApiBaseUrl(value: unknown) {
  const configured = normalizeBaseUrl(value);
  if (!configured) {
    return resolveAppRoutePath(DEFAULT_API_BASE_PATH);
  }
  if (/^(https?:)?\/\//i.test(configured) || configured.startsWith("/")) {
    return configured;
  }
  return resolveAppRoutePath(configured);
}

function resolveAppRoutePath(path: string) {
  const base = import.meta.env.BASE_URL || "/";
  const normalizedBase = base.endsWith("/") ? base.slice(0, -1) : base;
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${normalizedBase}${normalizedPath}` || normalizedPath;
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

export const studioApi = createStudioApi({
  baseURL: resolveStudioApiBaseUrl(),
  getToken: getStoredToken,
  getTenantId: getStoredTenantId,
  getProjectId: getStoredProjectId,
  onUnauthorized: () => {
    clearStoredToken();
    const loginPath = resolveAppRoutePath("/login");
    if (window.location.pathname !== loginPath) {
      window.location.assign(loginPath);
    }
  },
});
