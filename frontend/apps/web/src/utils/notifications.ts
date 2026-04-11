import type { NotificationView } from "@studio/api-sdk";
import type { Router } from "vue-router";
import type { useAuthStore } from "@/stores/auth";

type AuthStoreLike = ReturnType<typeof useAuthStore>;

function sameId(left: string | number | null | undefined, right: string | number | null | undefined) {
  if (left == null || right == null) {
    return false;
  }
  return String(left) === String(right);
}

export async function openNotificationTarget(
  notification: NotificationView,
  authStore: AuthStoreLike,
  router: Router,
) {
  if (!notification) {
    return;
  }
  if (notification.targetTenantId && notification.targetTenantId !== authStore.currentTenantId) {
    await authStore.selectTenant(notification.targetTenantId);
  }
  if (notification.targetProjectId != null && !sameId(notification.targetProjectId, authStore.currentProjectId)) {
    await authStore.selectProject(notification.targetProjectId);
  }
  await router.push(notification.targetPath || "/notifications");
}
