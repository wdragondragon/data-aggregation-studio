<template>
  <el-popover placement="bottom-end" :width="360" trigger="click" popper-class="notification-bell-popover">
    <template #reference>
      <button class="notification-bell" type="button" :title="t('web.notifications.title')">
        <span class="notification-bell__icon">铃</span>
        <span class="notification-bell__label">{{ t("web.notifications.title") }}</span>
        <span v-if="notificationStore.unreadCount > 0" class="notification-bell__badge">
          {{ formatUnreadCount(notificationStore.unreadCount) }}
        </span>
      </button>
    </template>

    <div class="notification-panel">
      <div class="notification-panel__header">
        <div>
          <strong>{{ t("web.notifications.recentTitle") }}</strong>
          <p>{{ t("web.notifications.recentDescription") }}</p>
        </div>
        <div class="notification-panel__actions">
          <el-button text type="primary" :disabled="notificationStore.unreadCount < 1" @click="markAllRead">
            {{ t("web.notifications.markAllRead") }}
          </el-button>
          <el-button text type="primary" @click="goToCenter">
            {{ t("web.notifications.openCenter") }}
          </el-button>
        </div>
      </div>

      <div v-if="notificationStore.recentNotifications.length" class="notification-list">
        <button
          v-for="item in notificationStore.recentNotifications"
          :key="String(item.id)"
          class="notification-item"
          :class="{ 'notification-item--unread': !item.read }"
          type="button"
          @click="openNotification(item)"
        >
          <div class="notification-item__title">
            <strong>{{ item.title || t("common.none") }}</strong>
            <span v-if="!item.read" class="notification-item__dot" />
          </div>
          <p>{{ item.content || t("common.none") }}</p>
          <span class="notification-item__time">{{ item.createdAt || t("common.none") }}</span>
        </button>
      </div>
      <el-empty v-else :description="t('web.notifications.empty')" :image-size="88" />
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import type { NotificationView } from "@studio/api-sdk";
import { useAuthStore } from "@/stores/auth";
import { useNotificationStore } from "@/stores/notifications";
import { openNotificationTarget } from "@/utils/notifications";

const { t } = useI18n();
const router = useRouter();
const authStore = useAuthStore();
const notificationStore = useNotificationStore();

function formatUnreadCount(value: number) {
  return value > 99 ? "99+" : String(value);
}

async function markAllRead() {
  try {
    await notificationStore.markAllRead();
    ElMessage.success(t("web.notifications.markAllReadSuccess"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.notifications.loadFailed"));
  }
}

function goToCenter() {
  router.push("/notifications");
}

async function openNotification(notification: NotificationView) {
  try {
    if (notification.id && !notification.read) {
      await notificationStore.markRead(notification.id);
    }
    await openNotificationTarget(notification, authStore, router);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.notifications.loadFailed"));
  }
}
</script>

<style scoped>
.notification-bell {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  border-radius: 999px;
  color: var(--studio-text);
  background: rgba(255, 255, 255, 0.9);
  box-shadow: var(--studio-shadow);
  cursor: pointer;
}

.notification-bell__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--studio-primary);
  background: rgba(37, 99, 235, 0.1);
}

.notification-bell__label {
  font-size: 13px;
  font-weight: 600;
}

.notification-bell__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  background: #dc2626;
}

.notification-panel {
  display: grid;
  gap: 12px;
}

.notification-panel__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.notification-panel__header strong {
  display: block;
  margin-bottom: 4px;
}

.notification-panel__header p {
  margin: 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.5;
}

.notification-panel__actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.notification-list {
  display: grid;
  gap: 8px;
  max-height: 420px;
  overflow: auto;
}

.notification-item {
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  border-radius: 14px;
  text-align: left;
  background: rgba(248, 250, 252, 0.9);
  cursor: pointer;
}

.notification-item--unread {
  background: rgba(239, 246, 255, 0.95);
  border-color: rgba(37, 99, 235, 0.2);
}

.notification-item__title {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.notification-item__dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #2563eb;
  flex-shrink: 0;
}

.notification-item p {
  margin: 0;
  color: var(--studio-text-soft);
  line-height: 1.55;
}

.notification-item__time {
  color: var(--studio-text-soft);
  font-size: 12px;
}
</style>
