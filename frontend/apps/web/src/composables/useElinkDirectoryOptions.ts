import { ref } from "vue";
import type { ElinkGroupOptionView, ElinkUserOptionView, EntityId } from "@studio/api-sdk";
import { studioApi } from "@/api/studio";

const DIRECTORY_PAGE_SIZE = 100;
const DIRECTORY_CACHE_TTL_MS = 30_000;

export function useElinkDirectoryOptions() {
  const userOptions = ref<ElinkUserOptionView[]>([]);
  const groupOptions = ref<ElinkGroupOptionView[]>([]);
  const userLoading = ref(false);
  const groupLoading = ref(false);
  let userRequestSequence = 0;
  let groupRequestSequence = 0;
  let userAbortController: AbortController | undefined;
  let groupAbortController: AbortController | undefined;
  let userInFlight: Promise<void> | undefined;
  let groupInFlight: Promise<void> | undefined;
  let userInFlightKey = "";
  let groupInFlightKey = "";
  let usersLoadedAt = 0;
  let groupsLoadedAt = 0;

  function seedUsers(userIds: string[] = [], userNames: string[] = []) {
    userOptions.value = seedSelectedUsers(userOptions.value, userIds, userNames);
  }

  function seedGroup(groupId?: EntityId | null, groupName?: string) {
    groupOptions.value = seedSelectedGroup(groupOptions.value, groupId, groupName);
  }

  function queryUsers(keyword = "", selectedUserIds: string[] = [], selectedUserNames: string[] = []) {
    const normalizedKeyword = keyword.trim();
    seedUsers(selectedUserIds, selectedUserNames);
    if (userInFlight && userInFlightKey === normalizedKeyword) {
      return userInFlight.then(() => seedUsers(selectedUserIds, selectedUserNames));
    }
    if (normalizedKeyword) usersLoadedAt = 0;
    const requestSequence = ++userRequestSequence;
    userAbortController?.abort();
    const controller = new AbortController();
    userAbortController = controller;
    userInFlightKey = normalizedKeyword;
    userLoading.value = true;
    const task = (async () => {
      try {
        const page = await studioApi.elink.queryUsers({
          keyword: normalizedKeyword || undefined,
          pageNo: 1,
          pageSize: DIRECTORY_PAGE_SIZE,
        }, { signal: controller.signal, studioSkipGlobalLoading: true });
        if (requestSequence === userRequestSequence) {
          userOptions.value = mergeUsers(userOptions.value, page.items || [], selectedUserIds, selectedUserNames);
          if (!normalizedKeyword) usersLoadedAt = Date.now();
        }
      } catch (error) {
        if (!controller.signal.aborted && requestSequence === userRequestSequence && !isCanceledRequest(error)) throw error;
      } finally {
        if (userAbortController === controller) {
          userAbortController = undefined;
          userInFlight = undefined;
          userInFlightKey = "";
          userLoading.value = false;
        }
      }
    })();
    userInFlight = task;
    return task;
  }

  function queryGroups(keyword = "", selectedGroupId?: EntityId | null, selectedGroupName?: string) {
    const normalizedKeyword = keyword.trim();
    seedGroup(selectedGroupId, selectedGroupName);
    if (groupInFlight && groupInFlightKey === normalizedKeyword) {
      return groupInFlight.then(() => seedGroup(selectedGroupId, selectedGroupName));
    }
    if (normalizedKeyword) groupsLoadedAt = 0;
    const requestSequence = ++groupRequestSequence;
    groupAbortController?.abort();
    const controller = new AbortController();
    groupAbortController = controller;
    groupInFlightKey = normalizedKeyword;
    groupLoading.value = true;
    const task = (async () => {
      try {
        const page = await studioApi.elink.queryGroups({
          keyword: normalizedKeyword || undefined,
          pageNo: 1,
          pageSize: DIRECTORY_PAGE_SIZE,
        }, { signal: controller.signal, studioSkipGlobalLoading: true });
        if (requestSequence === groupRequestSequence) {
          groupOptions.value = mergeGroups(groupOptions.value, page.items || [], selectedGroupId, selectedGroupName);
          if (!normalizedKeyword) groupsLoadedAt = Date.now();
        }
      } catch (error) {
        if (!controller.signal.aborted && requestSequence === groupRequestSequence && !isCanceledRequest(error)) throw error;
      } finally {
        if (groupAbortController === controller) {
          groupAbortController = undefined;
          groupInFlight = undefined;
          groupInFlightKey = "";
          groupLoading.value = false;
        }
      }
    })();
    groupInFlight = task;
    return task;
  }

  function ensureUsers(selectedUserIds: string[] = [], selectedUserNames: string[] = []) {
    seedUsers(selectedUserIds, selectedUserNames);
    if (userInFlight && !userInFlightKey) return queryUsers("", selectedUserIds, selectedUserNames);
    if (userInFlight) cancelUserQuery();
    if (Date.now() - usersLoadedAt < DIRECTORY_CACHE_TTL_MS) return Promise.resolve();
    return queryUsers("", selectedUserIds, selectedUserNames);
  }

  function ensureGroups(selectedGroupId?: EntityId | null, selectedGroupName?: string) {
    seedGroup(selectedGroupId, selectedGroupName);
    if (groupInFlight && !groupInFlightKey) return queryGroups("", selectedGroupId, selectedGroupName);
    if (groupInFlight) cancelGroupQuery();
    if (Date.now() - groupsLoadedAt < DIRECTORY_CACHE_TTL_MS) return Promise.resolve();
    return queryGroups("", selectedGroupId, selectedGroupName);
  }

  function cancelUserQuery() {
    userRequestSequence += 1;
    userAbortController?.abort();
    userAbortController = undefined;
    userInFlight = undefined;
    userInFlightKey = "";
    userLoading.value = false;
  }

  function cancelGroupQuery() {
    groupRequestSequence += 1;
    groupAbortController?.abort();
    groupAbortController = undefined;
    groupInFlight = undefined;
    groupInFlightKey = "";
    groupLoading.value = false;
  }

  function cancelQueries() {
    cancelUserQuery();
    cancelGroupQuery();
  }

  function userLabel(option: ElinkUserOptionView) {
    return option.name ? `${option.name} (${option.userId})` : option.userId;
  }

  function groupLabel(option: ElinkGroupOptionView) {
    const name = option.name || String(option.id);
    return option.memberCount == null ? name : `${name} (${option.memberCount})`;
  }

  return {
    userOptions,
    groupOptions,
    userLoading,
    groupLoading,
    seedUsers,
    seedGroup,
    queryUsers,
    queryGroups,
    ensureUsers,
    ensureGroups,
    cancelUserQuery,
    cancelGroupQuery,
    cancelQueries,
    userLabel,
    groupLabel,
  };
}

function isCanceledRequest(error: unknown) {
  return Boolean(error && typeof error === "object" && (error as { code?: unknown }).code === "ERR_CANCELED");
}

function seedSelectedUsers(current: ElinkUserOptionView[], selectedUserIds: string[], selectedUserNames: string[]) {
  const merged = new Map<string, ElinkUserOptionView>();
  for (const item of current) {
    if (item.userId) merged.set(item.userId, item);
  }
  selectedUserIds.forEach((userId, index) => {
    if (userId && !merged.has(userId)) {
      merged.set(userId, { userId, name: selectedUserNames[index] || undefined });
    }
  });
  return Array.from(merged.values());
}

function seedSelectedGroup(current: ElinkGroupOptionView[], selectedGroupId?: EntityId | null, selectedGroupName?: string) {
  const merged = new Map<string, ElinkGroupOptionView>();
  for (const item of current) {
    if (item.id != null) merged.set(String(item.id), item);
  }
  const selectedId = selectedGroupId == null || selectedGroupId === "" ? null : String(selectedGroupId);
  if (selectedId != null && !merged.has(selectedId)) {
    merged.set(selectedId, { id: selectedGroupId as EntityId, name: selectedGroupName || undefined });
  }
  return Array.from(merged.values());
}

function mergeUsers(
  current: ElinkUserOptionView[],
  incoming: ElinkUserOptionView[],
  selectedUserIds: string[],
  selectedUserNames: string[],
) {
  const selected = new Set(selectedUserIds);
  const merged = new Map<string, ElinkUserOptionView>();
  for (const item of current) {
    if (selected.has(item.userId)) merged.set(item.userId, item);
  }
  selectedUserIds.forEach((userId, index) => {
    if (userId && !merged.has(userId)) {
      merged.set(userId, { userId, name: selectedUserNames[index] || undefined });
    }
  });
  for (const item of incoming) {
    if (item.userId) merged.set(item.userId, item);
  }
  return Array.from(merged.values());
}

function mergeGroups(
  current: ElinkGroupOptionView[],
  incoming: ElinkGroupOptionView[],
  selectedGroupId?: EntityId | null,
  selectedGroupName?: string,
) {
  const selectedId = selectedGroupId == null || selectedGroupId === "" ? null : String(selectedGroupId);
  const merged = new Map<string, ElinkGroupOptionView>();
  for (const item of current) {
    if (selectedId != null && String(item.id) === selectedId) merged.set(String(item.id), item);
  }
  if (selectedId != null && !merged.has(selectedId)) {
    merged.set(selectedId, { id: selectedGroupId as EntityId, name: selectedGroupName || undefined });
  }
  for (const item of incoming) {
    if (item.id != null) merged.set(String(item.id), item);
  }
  return Array.from(merged.values());
}
