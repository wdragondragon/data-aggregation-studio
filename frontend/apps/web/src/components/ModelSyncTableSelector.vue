<template>
  <div class="sync-selector">
    <div class="sync-selector__toolbar">
      <div class="sync-selector__title">
        <strong>{{ title || t("web.models.syncTables") }}</strong>
        <span class="sync-selector__summary">
          {{ t("web.models.syncSelectedSummary", { count: selectedLocators.length, total }) }}
        </span>
      </div>
      <div class="sync-selector__search">
        <el-input
          v-model="keyword"
          clearable
          :disabled="!datasourceId || disabled"
          :placeholder="t('web.models.syncTableSearchPlaceholder')"
          @keyup.enter="reloadFirstPage"
          @clear="reloadFirstPage"
        />
        <el-button plain :disabled="!datasourceId || disabled" :loading="loading" @click="reloadFirstPage">
          {{ t("common.search") }}
        </el-button>
      </div>
    </div>

    <div class="sync-selector__body">
      <div class="sync-selector__table">
        <el-table
          ref="tableRef"
          v-loading="loading"
          :data="rows"
          :empty-text="emptyText"
          border
          row-key="physicalLocator"
          @select="handleRowSelect"
          @select-all="handleSelectAll"
        >
          <el-table-column type="selection" width="52" />
          <el-table-column prop="name" :label="t('web.models.syncTableName')" min-width="180" />
          <el-table-column prop="physicalLocator" :label="t('web.models.syncTableLocator')" min-width="220" show-overflow-tooltip />
        </el-table>
        <div class="table-pagination">
          <el-pagination
            v-model:current-page="pageNo"
            v-model:page-size="pageSize"
            background
            layout="total, sizes, prev, pager, next"
            :page-sizes="[20, 50, 100, 200]"
            :total="total"
            @current-change="loadPage"
            @size-change="handlePageSizeChange"
          />
        </div>
      </div>

      <div class="sync-selector__selected soft-panel">
        <div class="sync-selector__selected-header">
          <strong>{{ t("web.models.syncSelectedTables") }}</strong>
          <el-button link type="danger" :disabled="selectedLocators.length === 0 || disabled" @click="clearSelection">
            {{ t("common.reset") }}
          </el-button>
        </div>
        <div v-if="selectedLocators.length === 0" class="sync-selector__selected-empty">
          {{ t("web.models.syncNoSelection") }}
        </div>
        <div v-else class="sync-selector__selected-list">
          <div v-for="locator in selectedLocators" :key="locator" class="sync-selector__selected-item">
            <span>{{ locator }}</span>
            <el-button link type="danger" :disabled="disabled" @click="removeLocator(locator)">
              {{ t("common.remove") }}
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import type { DataModelDefinition, EntityId } from "@studio/api-sdk";
import { studioApi } from "@/api/studio";

const props = withDefaults(defineProps<{
  datasourceId?: EntityId;
  modelValue?: string[];
  disabled?: boolean;
  title?: string;
}>(), {
  modelValue: () => [],
  disabled: false,
  title: "",
});

const emit = defineEmits<{
  (event: "update:modelValue", value: string[]): void;
}>();

const { t } = useI18n();
const tableRef = ref();
const rows = ref<DataModelDefinition[]>([]);
const loading = ref(false);
const keyword = ref("");
const pageNo = ref(1);
const pageSize = ref(20);
const total = ref(0);

const selectedLocators = computed(() => props.modelValue ?? []);
const selectedSet = computed(() => new Set(selectedLocators.value.map((item) => String(item))));
const emptyText = computed(() =>
  keyword.value.trim()
    ? t("web.models.syncNoSearchResults")
    : t("web.models.syncNoTables"),
);

async function loadPage() {
  if (!props.datasourceId) {
    rows.value = [];
    total.value = 0;
    return;
  }
  loading.value = true;
  try {
    const result = await studioApi.datasources.discover(props.datasourceId, {
      keyword: keyword.value.trim() || undefined,
      pageNo: pageNo.value,
      pageSize: pageSize.value,
    });
    rows.value = result.models ?? [];
    total.value = Number(result.total ?? 0);
    await restoreSelections();
  } finally {
    loading.value = false;
  }
}

async function restoreSelections() {
  await nextTick();
  if (!tableRef.value || !Array.isArray(rows.value)) {
    return;
  }
  tableRef.value.clearSelection?.();
  rows.value.forEach((row) => {
    if (selectedSet.value.has(String(row.physicalLocator))) {
      tableRef.value.toggleRowSelection?.(row, true);
    }
  });
}

function updateSelection(nextValues: string[]) {
  emit("update:modelValue", Array.from(new Set(nextValues.map((item) => String(item)))));
}

function handleRowSelect(selection: DataModelDefinition[], row: DataModelDefinition) {
  const locator = String(row.physicalLocator);
  const next = new Set(selectedSet.value);
  const selectedOnPage = selection.some((item) => String(item.physicalLocator) === locator);
  if (selectedOnPage) {
    next.add(locator);
  } else {
    next.delete(locator);
  }
  updateSelection(Array.from(next));
}

function handleSelectAll(selection: DataModelDefinition[]) {
  const next = new Set(selectedSet.value);
  const pageLocators = rows.value.map((item) => String(item.physicalLocator));
  const selectedPageLocators = new Set(selection.map((item) => String(item.physicalLocator)));
  pageLocators.forEach((locator) => {
    if (selectedPageLocators.has(locator)) {
      next.add(locator);
    } else {
      next.delete(locator);
    }
  });
  updateSelection(Array.from(next));
}

function removeLocator(locator: string) {
  const next = selectedLocators.value.filter((item) => String(item) !== String(locator));
  updateSelection(next);
  void restoreSelections();
}

function clearSelection() {
  updateSelection([]);
  void restoreSelections();
}

function reloadFirstPage() {
  pageNo.value = 1;
  void loadPage();
}

function handlePageSizeChange(value: number) {
  pageSize.value = value;
  pageNo.value = 1;
  void loadPage();
}

watch(() => props.datasourceId, () => {
  keyword.value = "";
  pageNo.value = 1;
  rows.value = [];
  total.value = 0;
  if (props.datasourceId) {
    void loadPage();
  }
}, { immediate: true });

watch(() => props.modelValue, () => {
  void restoreSelections();
});
</script>

<style scoped>
.sync-selector {
  display: grid;
  gap: 12px;
}

.sync-selector__toolbar {
  display: grid;
  gap: 10px;
}

.sync-selector__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.sync-selector__summary {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.sync-selector__search {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--studio-border);
  border-radius: 14px;
  background: rgba(248, 250, 252, 0.92);
}

.sync-selector__body {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(260px, 1fr);
  gap: 12px;
  align-items: start;
}

.sync-selector__table {
  display: grid;
  gap: 12px;
}

.sync-selector__selected {
  display: grid;
  gap: 10px;
  min-height: 320px;
}

.sync-selector__selected-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.sync-selector__selected-empty {
  color: var(--studio-text-soft);
}

.sync-selector__selected-list {
  display: grid;
  gap: 8px;
  max-height: 420px;
  overflow: auto;
}

.sync-selector__selected-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid var(--studio-border);
  background: rgba(255, 255, 255, 0.72);
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 980px) {
  .sync-selector__search {
    grid-template-columns: 1fr;
  }

  .sync-selector__body {
    grid-template-columns: 1fr;
  }

  .table-pagination {
    justify-content: flex-start;
  }
}
</style>
