<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.notifications.title") }}</h3>
        <p>{{ t("web.notifications.centerDescription") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="loadNotifications">{{ t("common.refresh") }}</el-button>
        <el-button type="primary" plain :disabled="notificationStore.unreadCount < 1" @click="markAllRead">
          {{ t("web.notifications.markAllRead") }}
        </el-button>
      </div>
    </div>

    <SectionCard :title="t('web.notifications.filterTitle')" :description="t('web.notifications.filterDescription')">
      <div class="notification-filter-grid">
        <el-select v-model="unreadOnly" style="max-width: 220px" @change="handleFilterChange">
          <el-option :label="t('web.notifications.filterAll')" :value="false" />
          <el-option :label="t('web.notifications.filterUnread')" :value="true" />
        </el-select>
      </div>
    </SectionCard>

    <SectionCard :title="t('web.notifications.listTitle')" :description="t('web.notifications.listDescription')">
      <div v-if="notifications.length" class="notification-center-list">
        <button
          v-for="item in notifications"
          :key="String(item.id)"
          class="notification-center-item"
          :class="{ 'notification-center-item--unread': !item.read }"
          type="button"
          @click="openNotification(item)"
        >
          <div class="notification-center-item__main">
            <div class="notification-center-item__header">
              <strong>{{ item.title || t("common.none") }}</strong>
              <span class="notification-center-item__meta">
                <span v-if="!item.read" class="notification-center-item__dot" />
                {{ item.createdAt || t("common.none") }}
              </span>
            </div>
            <p>{{ item.content || t("common.none") }}</p>
          </div>
          <div class="notification-center-item__actions">
            <el-button
              v-if="item.id && !item.read"
              text
              type="primary"
              @click.stop="markRead(item.id)"
            >
              {{ t("web.notifications.markRead") }}
            </el-button>
          </div>
        </button>
      </div>
      <el-empty v-else :description="t('web.notifications.empty')" />

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          @current-change="loadNotifications"
          @size-change="handlePageSizeChange"
        />
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import type { NotificationView } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { useNotificationStore } from "@/stores/notifications";
import { openNotificationTarget } from "@/utils/notifications";

const { t } = useI18n();
const router = useRouter();
const authStore = useAuthStore();
const notificationStore = useNotificationStore();

const notifications = ref<NotificationView[]>([]);
const total = ref(0);
const unreadOnly = ref(false);
const pagination = ref({
  page: 1,
  pageSize: 20,
});

async function loadNotifications() {
  try {
    const result = await studioApi.notifications.list({
      pageNo: pagination.value.page,
      pageSize: pagination.value.pageSize,
      unreadOnly: unreadOnly.value || undefined,
    });
    notifications.value = result.items;
    total.value = Number(result.total ?? 0);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.notifications.loadFailed"));
  }
}

function handleFilterChange() {
  pagination.value.page = 1;
  void loadNotifications();
}

function handlePageSizeChange(pageSize: number) {
  pagination.value.page = 1;
  pagination.value.pageSize = pageSize;
  void loadNotifications();
}

async function markRead(id: string | number) {
  try {
    await notificationStore.markRead(id);
    await loadNotifications();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.notifications.loadFailed"));
  }
}

async function markAllRead() {
  try {
    await notificationStore.markAllRead();
    await loadNotifications();
    ElMessage.success(t("web.notifications.markAllReadSuccess"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.notifications.loadFailed"));
  }
}

async function openNotification(notification: NotificationView) {
  try {
    if (notification.id && !notification.read) {
      await notificationStore.markRead(notification.id);
    }
    await loadNotifications();
    await openNotificationTarget(notification, authStore, router);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.notifications.loadFailed"));
  }
}

onMounted(async () => {
  await Promise.all([notificationStore.loadSnapshot(), loadNotifications()]);
});
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.notification-filter-grid {
  display: flex;
  gap: 12px;
  align-items: center;
}

.notification-center-list {
  display: grid;
  gap: 12px;
}

.notification-center-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  border-radius: 18px;
  text-align: left;
  background: rgba(255, 255, 255, 0.9);
  cursor: pointer;
}

.notification-center-item--unread {
  background: rgba(239, 246, 255, 0.95);
  border-color: rgba(37, 99, 235, 0.18);
}

.notification-center-item__main {
  display: grid;
  gap: 8px;
}

.notification-center-item__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.notification-center-item__header strong {
  line-height: 1.4;
}

.notification-center-item__meta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.notification-center-item__dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #2563eb;
}

.notification-center-item__main p {
  line-height: 1.6;
}

.notification-center-item__actions {
  display: flex;
  align-items: center;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

@media (max-width: 880px) {
  .notification-center-item {
    grid-template-columns: minmax(0, 1fr);
  }

  .notification-center-item__header {
    flex-direction: column;
  }

  .table-pagination {
    justify-content: flex-start;
  }
}
</style>
