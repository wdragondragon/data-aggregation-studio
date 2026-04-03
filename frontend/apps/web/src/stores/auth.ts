import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { clearStoredToken, getStoredToken, setStoredToken, STUDIO_USERNAME_KEY, studioApi } from "@/api/studio";

export const useAuthStore = defineStore("studio-auth", () => {
  const token = ref<string | null>(getStoredToken());
  const username = ref<string | null>(window.localStorage.getItem(STUDIO_USERNAME_KEY));
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
      const profile = await studioApi.auth.me();
      username.value = profile.username;
      if (profile.username) {
        window.localStorage.setItem(STUDIO_USERNAME_KEY, profile.username);
      }
    } catch {
      clearStoredToken();
      token.value = null;
      username.value = null;
    } finally {
      ready.value = true;
    }
  }

  async function login(form: { username: string; password: string }) {
    const response = await studioApi.auth.login(form);
    token.value = response.token;
    username.value = response.username;
    setStoredToken(response.token);
    window.localStorage.setItem(STUDIO_USERNAME_KEY, response.username);
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
