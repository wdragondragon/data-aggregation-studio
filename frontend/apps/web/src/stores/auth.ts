import { computed, ref } from "vue";
import { defineStore } from "pinia";
import type { AuthProfile, AuthProject, AuthTenant, EntityId, LoginResponse } from "@studio/api-sdk";
import {
  clearStoredToken,
  getStoredProjectId,
  getStoredTenantId,
  getStoredToken,
  setStoredProjectId,
  setStoredTenantId,
  setStoredToken,
  STUDIO_USERNAME_KEY,
  studioApi,
} from "@/api/studio";

export const useAuthStore = defineStore("studio-auth", () => {
  const token = ref<string | null>(getStoredToken());
  const userId = ref<EntityId | null>(null);
  const username = ref<string | null>(window.localStorage.getItem(STUDIO_USERNAME_KEY));
  const displayName = ref<string | null>(null);
  const currentTenantId = ref<string | null>(getStoredTenantId());
  const currentProjectId = ref<EntityId | null>(getStoredProjectId());
  const tenants = ref<AuthTenant[]>([]);
  const projects = ref<AuthProject[]>([]);
  const systemRoleCodes = ref<string[]>([]);
  const effectiveRoleCodes = ref<string[]>([]);
  const ready = ref(false);

  const isAuthenticated = computed(() => Boolean(token.value));
  const currentTenant = computed(() => tenants.value.find((item) => item.tenantId === currentTenantId.value) ?? null);
  const currentProject = computed(() => projects.value.find((item) => sameId(item.projectId, currentProjectId.value)) ?? null);
  const currentTenantName = computed(() => currentTenant.value?.tenantName ?? null);
  const currentProjectName = computed(() => currentProject.value?.projectName ?? null);

  function hydrateProfile(profile: AuthProfile | LoginResponse) {
    userId.value = profile.userId ?? null;
    username.value = profile.username ?? null;
    displayName.value = profile.displayName ?? null;
    currentTenantId.value = profile.currentTenantId ?? null;
    currentProjectId.value = profile.currentProjectId ?? null;
    tenants.value = Array.isArray(profile.tenants) ? profile.tenants : [];
    projects.value = Array.isArray(profile.projects) ? profile.projects : [];
    systemRoleCodes.value = Array.isArray(profile.systemRoleCodes) ? profile.systemRoleCodes : [];
    effectiveRoleCodes.value = Array.isArray(profile.effectiveRoleCodes) ? profile.effectiveRoleCodes : [];

    if (username.value) {
      window.localStorage.setItem(STUDIO_USERNAME_KEY, username.value);
    } else {
      window.localStorage.removeItem(STUDIO_USERNAME_KEY);
    }
    setStoredTenantId(currentTenantId.value);
    setStoredProjectId(currentProjectId.value);
  }

  async function requestProfile(allowContextReset = true) {
    try {
      const profile = await studioApi.auth.me();
      hydrateProfile(profile);
      return profile;
    } catch (error) {
      if (allowContextReset && (getStoredTenantId() || getStoredProjectId())) {
        setStoredTenantId(null);
        setStoredProjectId(null);
        const profile = await studioApi.auth.me();
        hydrateProfile(profile);
        return profile;
      }
      throw error;
    }
  }

  async function bootstrap() {
    if (ready.value) {
      return;
    }
    if (!token.value) {
      ready.value = true;
      return;
    }
    try {
      await requestProfile(true);
    } catch {
      clearStoredToken();
      resetState();
    } finally {
      ready.value = true;
    }
  }

  async function login(form: { username: string; password: string }) {
    const response = await studioApi.auth.login(form);
    token.value = response.token;
    setStoredToken(response.token);
    hydrateProfile(response);
  }

  async function refreshProfile() {
    if (!token.value) {
      resetState();
      return;
    }
    await requestProfile(true);
  }

  async function selectTenant(tenantId: string) {
    setStoredTenantId(tenantId);
    setStoredProjectId(null);
    await refreshProfile();
  }

  async function selectProject(projectId: EntityId) {
    setStoredProjectId(projectId);
    await refreshProfile();
  }

  function resetState() {
    token.value = null;
    userId.value = null;
    username.value = null;
    displayName.value = null;
    currentTenantId.value = null;
    currentProjectId.value = null;
    tenants.value = [];
    projects.value = [];
    systemRoleCodes.value = [];
    effectiveRoleCodes.value = [];
    window.localStorage.removeItem(STUDIO_USERNAME_KEY);
  }

  function logout() {
    clearStoredToken();
    resetState();
  }

  return {
    token,
    userId,
    username,
    displayName,
    currentTenantId,
    currentProjectId,
    tenants,
    projects,
    systemRoleCodes,
    effectiveRoleCodes,
    currentTenant,
    currentProject,
    currentTenantName,
    currentProjectName,
    ready,
    isAuthenticated,
    bootstrap,
    login,
    refreshProfile,
    selectTenant,
    selectProject,
    logout,
  };
});

function sameId(left: EntityId | null | undefined, right: EntityId | null | undefined) {
  if (left == null || right == null) {
    return false;
  }
  return String(left) === String(right);
}
