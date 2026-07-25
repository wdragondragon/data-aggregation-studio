<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('web.runtimeClusterSelection.manualRunTitle')"
    width="min(480px, 94vw)"
    append-to-body
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
    @opened="loadOptions"
  >
    <div class="manual-run-dialog">
      <p>{{ t("web.runtimeClusterSelection.manualRunDescription", { name: resourceName }) }}</p>
      <el-alert
        v-if="shared"
        :title="t('web.runtimeClusterSelection.sharedDefaultOnly')"
        type="info"
        :closable="false"
        show-icon
      />
      <el-form label-position="top">
        <el-form-item :label="t('web.runtimeClusterSelection.runtimeCluster')">
          <el-select
            v-model="selectedClusterId"
            :loading="loading"
            :disabled="selectableClusters.length <= 1"
            class="cluster-select"
          >
            <el-option
              v-for="cluster in selectableClusters"
              :key="String(cluster.id)"
              :label="formatRuntimeClusterLabel(selectableClusters, cluster.id, cluster.code)"
              :value="cluster.id"
            >
              <div class="cluster-option">
                <span>{{ formatRuntimeClusterLabel(selectableClusters, cluster.id, cluster.code) }}</span>
                <el-tag v-if="sameEntityId(cluster.id, defaultClusterId)" size="small" type="info">
                  {{ t("web.runtimeClusterSelection.savedDefault") }}
                </el-tag>
                <el-tag v-else size="small" type="warning">
                  {{ t("web.runtimeClusterSelection.manualOverride") }}
                </el-tag>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
      </el-form>
      <span class="manual-run-dialog__hint">{{ t("web.runtimeClusterSelection.noFailoverHint") }}</span>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="submitting" :disabled="loading || selectedClusterId == null" @click="confirmSelection">
        {{ t("common.trigger") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { EntityId, RuntimeClusterView } from "@studio/api-sdk";
import { studioApi } from "@/api/studio";
import { formatRuntimeClusterLabel, sameEntityId } from "@/utils/runtimeClusters";

const props = defineProps<{
  modelValue: boolean;
  resourceName: string;
  defaultClusterId?: EntityId;
  defaultClusterName?: string;
  shared?: boolean;
  submitting?: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  confirm: [runtimeClusterId?: EntityId];
}>();

const { t } = useI18n();
const loading = ref(false);
const runtimeClusters = ref<RuntimeClusterView[]>([]);
const selectedClusterId = ref<EntityId | undefined>();

const selectableClusters = computed(() => {
  const allowed = runtimeClusters.value.filter((cluster) => {
    if (cluster.id == null) return false;
    if (sameEntityId(cluster.id, props.defaultClusterId)) return true;
    return !props.shared && cluster.enabled !== false && cluster.allowManualOverride === true;
  });
  if (props.defaultClusterId != null && !allowed.some((cluster) => sameEntityId(cluster.id, props.defaultClusterId))) {
    allowed.unshift({ id: props.defaultClusterId, name: props.defaultClusterName });
  }
  return allowed;
});

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) selectedClusterId.value = props.defaultClusterId;
  },
  { immediate: true },
);

async function loadOptions() {
  loading.value = true;
  try {
    runtimeClusters.value = await studioApi.runtimeClusters.options();
    selectedClusterId.value = props.defaultClusterId;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.runtimeClusters.clusterLoadFailed"));
  } finally {
    loading.value = false;
  }
}

function confirmSelection() {
  emit("confirm", selectedClusterId.value);
}
</script>

<style scoped>
.manual-run-dialog {
  display: grid;
  gap: 16px;
}

.manual-run-dialog p {
  margin: 0;
  color: var(--studio-text-soft);
  line-height: 1.6;
}

.cluster-select {
  width: 100%;
}

.cluster-option {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.cluster-option > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.manual-run-dialog__hint {
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.5;
}
</style>
