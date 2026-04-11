<template>
  <el-button
    :type="following ? 'success' : 'primary'"
    :plain="!following"
    :disabled="disabled || targetId == null"
    :loading="loading"
    size="small"
    @click="toggleFollow"
  >
    {{ following ? t("web.notifications.following") : t("web.notifications.follow") }}
  </el-button>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { studioApi } from "@/api/studio";

const props = defineProps<{
  targetType: string;
  targetId?: string | number | null;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  change: [following: boolean];
}>();

const { t } = useI18n();
const loading = ref(false);
const following = ref(false);

async function loadStatus() {
  if (!props.targetType || props.targetId == null) {
    following.value = false;
    return;
  }
  try {
    const result = await studioApi.follows.status(props.targetType, props.targetId);
    following.value = Boolean(result.following);
  } catch (error) {
    following.value = false;
    ElMessage.error(error instanceof Error ? error.message : t("web.notifications.followLoadFailed"));
  }
}

async function toggleFollow() {
  if (!props.targetType || props.targetId == null) {
    return;
  }
  loading.value = true;
  try {
    if (following.value) {
      await studioApi.follows.unfollow(props.targetType, props.targetId);
      following.value = false;
      ElMessage.success(t("web.notifications.unfollowSuccess"));
    } else {
      const result = await studioApi.follows.follow({
        targetType: props.targetType,
        targetId: props.targetId,
      });
      following.value = Boolean(result.following);
      ElMessage.success(t("web.notifications.followSuccess"));
    }
    emit("change", following.value);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.notifications.followSaveFailed"));
  } finally {
    loading.value = false;
  }
}

watch(() => [props.targetType, props.targetId], () => {
  void loadStatus();
}, { immediate: true });
</script>
