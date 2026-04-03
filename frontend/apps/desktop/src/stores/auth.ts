import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { clearStoredToken, DESKTOP_USERNAME_KEY, desktopApi, getStoredToken, setStoredToken } from "@/api/studio";

const useSharedAuthStore = defineStore("studio-desktop-auth", () => {
  const token = ref<string | null>(getStoredToken());
  const username = ref<string | null>(window.localStorage.getItem(DESKTOP_USERNAME_KEY));
  const ready = ref(false);

  const isAuthenticated = computed(() => Boolean(token.value));

  async function bootstrap() {
    if (ready.value) {
      return;
    }
    if (!token.value) {
      ready.value = true;
      return;
    }
    try {
      const profile = await desktopApi.auth.me();
      username.value = profile.username;
    } catch {
      clearStoredToken();
      token.value = null;
      username.value = null;
    } finally {
      ready.value = true;
    }
  }

  async function login(form: { username: string; password: string }) {
    const response = await desktopApi.auth.login(form);
    token.value = response.token;
    username.value = response.username;
    setStoredToken(response.token);
    window.localStorage.setItem(DESKTOP_USERNAME_KEY, response.username);
  }

  function logout() {
    clearStoredToken();
    token.value = null;
    username.value = null;
  }

  return {
    token,
    username,
    ready,
    isAuthenticated,
    bootstrap,
    login,
    logout,
  };
});

export const useDesktopAuthStore = useSharedAuthStore;
export const useAuthStore = useSharedAuthStore;
