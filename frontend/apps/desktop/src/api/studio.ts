import { createStudioApi } from "@studio/api-sdk";

export const DESKTOP_TOKEN_KEY = "studio_desktop_token";
export const DESKTOP_USERNAME_KEY = "studio_desktop_username";
export const STUDIO_TOKEN_KEY = DESKTOP_TOKEN_KEY;
export const STUDIO_USERNAME_KEY = DESKTOP_USERNAME_KEY;

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

function redirectToLogin() {
  if (window.location.hash !== "#/login") {
    window.location.hash = "/login";
  }
}

export const desktopApi = createStudioApi({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "/api/v1",
  getToken: getStoredToken,
  onUnauthorized: () => {
    clearStoredToken();
    redirectToLogin();
  },
});

export const studioApi = desktopApi;
