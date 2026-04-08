import { createStudioApi } from "@studio/api-sdk";

export const STUDIO_TOKEN_KEY = "studio_token";
export const STUDIO_USERNAME_KEY = "studio_username";
export const STUDIO_TENANT_KEY = "studio_current_tenant";
export const STUDIO_PROJECT_KEY = "studio_current_project";

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

export const studioApi = createStudioApi({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "/api/v1",
  getToken: getStoredToken,
  getTenantId: getStoredTenantId,
  getProjectId: getStoredProjectId,
  onUnauthorized: () => {
    clearStoredToken();
    if (window.location.pathname !== "/login") {
      window.location.assign("/login");
    }
  },
});
