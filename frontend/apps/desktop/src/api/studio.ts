import { createStudioApi } from "@studio/api-sdk";

export const DESKTOP_TOKEN_KEY = "studio_desktop_token";
export const DESKTOP_USERNAME_KEY = "studio_desktop_username";

export function getStoredToken() {
  return window.localStorage.getItem(DESKTOP_TOKEN_KEY);
}

export function setStoredToken(token: string) {
  window.localStorage.setItem(DESKTOP_TOKEN_KEY, token);
}

export function clearStoredToken() {
  window.localStorage.removeItem(DESKTOP_TOKEN_KEY);
  window.localStorage.removeItem(DESKTOP_USERNAME_KEY);
}

export const desktopApi = createStudioApi({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "/api/v1",
  getToken: getStoredToken,
  onUnauthorized: () => {
    clearStoredToken();
    if (window.location.pathname !== "/login") {
      window.location.assign("/login");
    }
  },
});
