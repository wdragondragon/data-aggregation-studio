<template>
  <div class="http-option-key-value-table">
    <StudioTableShell min-width="720px">
      <el-table :data="rows" border size="small" table-layout="fixed" class="http-option-key-value-table__table">
        <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column :label="t('web.collectionTasks.httpOptionKey')" min-width="180">
          <template #default="{ row, $index }">
            <el-input
              :model-value="row.name"
              :placeholder="keyPlaceholder"
              @update:model-value="emit('updateRow', $index, 'name', $event)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.collectionTasks.httpOptionValue')" min-width="260">
          <template #default="{ row, $index }">
            <div class="http-option-key-value-table__value-input">
              <el-input
                :ref="(element: unknown) => emit('registerValueInputRef', $index, element)"
                :model-value="row.value"
                :placeholder="valuePlaceholder"
                @update:model-value="emit('updateRow', $index, 'value', $event)"
                @click="emit('syncValueSelection', $index, $event)"
                @focus="emit('syncValueSelection', $index, $event)"
                @keyup="emit('syncValueSelection', $index, $event)"
                @select="emit('syncValueSelection', $index, $event)"
              />
              <el-button plain size="small" @click="emit('openDynamicFunction', $index)">
                {{ t("web.collectionTasks.httpDynamicFunctionButton") }}
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="86" align="center" header-align="center" fixed="right">
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
import { Delete } from "@element-plus/icons-vue";
import { StudioTableShell } from "@studio/ui";
import { useI18n } from "vue-i18n";

export interface HttpOptionRow {
  name: string;
  value: string;
}

defineProps<{
  rows: HttpOptionRow[];
  keyPlaceholder: string;
  valuePlaceholder: string;
}>();

const emit = defineEmits<{
  updateRow: [index: number, field: "name" | "value", value: string | number];
  removeRow: [index: number];
  openDynamicFunction: [index: number];
  registerValueInputRef: [index: number, element: unknown];
  syncValueSelection: [index: number, event: Event];
}>();

const { t } = useI18n();
</script>

<style scoped>
.http-option-key-value-table__value-input {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.http-option-key-value-table__table :deep(.cell) {
  white-space: normal;
}

.http-option-key-value-table__table :deep(th.el-table__cell) {
  background: rgba(16, 78, 139, 0.05);
}
</style>
