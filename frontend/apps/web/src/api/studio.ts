import { createStudioApi } from "@studio/api-sdk";

export const STUDIO_TOKEN_KEY = "studio_token";
export const STUDIO_USERNAME_KEY = "studio_username";

export function getStoredToken() {
  return window.localStorage.getItem(STUDIO_TOKEN_KEY);
}

export function setStoredToken(token: string) {
  window.localStorage.setItem(STUDIO_TOKEN_KEY, token);
}

export function clearStoredToken() {
  window.localStorage.removeItem(STUDIO_TOKEN_KEY);
  window.localStorage.removeItem(STUDIO_USERNAME_KEY);
}

export const studioApi = createStudioApi({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "/api/v1",
  getToken: getStoredToken,
  onUnauthorized: () => {
    clearStoredToken();
    if (window.location.pathname !== "/login") {
      window.location.assign("/login");
    }
  },
});
