<template>
  <div class="http-token-pair-table">
    <div class="http-token-pair-table__header">
      <div>
        <strong>{{ title }}</strong>
        <p>{{ description }}</p>
      </div>
      <el-button size="small" plain :icon="Plus" @click="emit('append')">
        {{ addLabel }}
      </el-button>
    </div>
    <StudioTableShell min-width="560px">
      <el-table :data="rows" border size="small" table-layout="fixed">
        <el-table-column :label="t('web.collectionTasks.httpOptionKey')" min-width="160">
          <template #default="{ row, $index }">
            <el-input
              :model-value="row.name"
              :placeholder="keyPlaceholder"
              @update:model-value="emit('updateRow', $index, 'name', $event)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.collectionTasks.httpOptionValue')" min-width="220">
          <template #default="{ row, $index }">
            <el-input
              :model-value="row.value"
              :placeholder="valuePlaceholder"
              @update:model-value="emit('updateRow', $index, 'value', $event)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="72" align="center" header-align="center">
          <template #default="{ $index }">
            <el-button
              link
              type="danger"
              :icon="Delete"
              :aria-label="t('common.delete')"
              @click="emit('removeRow', $index)"
            />
          </template>
        </el-table-column>
      </el-table>
    </StudioTableShell>
  </div>
</template>

<script setup lang="ts">
import { Delete, Plus } from "@element-plus/icons-vue";
import { StudioTableShell } from "@studio/ui";
import { useI18n } from "vue-i18n";

export interface HttpTokenPairRow {
  name: string;
  value: string;
}

defineProps<{
  rows: HttpTokenPairRow[];
  title: string;
  description: string;
  addLabel: string;
  keyPlaceholder: string;
  valuePlaceholder: string;
}>();

const emit = defineEmits<{
  append: [];
  updateRow: [index: number, field: "name" | "value", value: string | number];
  removeRow: [index: number];
}>();

const { t } = useI18n();
</script>

<style scoped>
.http-token-pair-table {
  display: grid;
  gap: 10px;
}

.http-token-pair-table__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.http-token-pair-table__header > div {
  min-width: 0;
}

.http-token-pair-table__header strong {
  color: var(--studio-text);
  font-size: 13px;
}

.http-token-pair-table__header p {
  margin: 4px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.5;
}

.http-token-pair-table :deep(.el-input) {
  width: 100%;
}
</style>
