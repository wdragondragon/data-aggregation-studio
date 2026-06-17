<template>
  <section class="protocol-json-editor" :class="{ 'protocol-json-editor--compact': props.compact }">
    <div class="protocol-json-editor__header">
      <div class="protocol-json-editor__main">
        <div class="protocol-json-editor__title">
          <span v-if="index" class="protocol-json-editor__index">{{ index }}</span>
          <strong>{{ title }}</strong>
        </div>
        <p v-if="description">{{ description }}</p>
      </div>
      <div class="protocol-json-editor__actions">
        <el-button v-if="mode === 'form'" plain size="small" :icon="Plus" @click="appendRow">添加参数</el-button>
        <el-radio-group v-model="mode" size="small">
          <el-radio-button value="form">键值表格</el-radio-button>
          <el-radio-button value="raw">原始 JSON</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <div class="protocol-json-editor__body">
      <template v-if="mode === 'form'">
        <StudioTableShell v-if="rows.length" min-width="620px">
          <el-table :data="rows" border size="small" table-layout="fixed" class="protocol-json-editor__table">
            <el-table-column label="序号" width="72" align="center" header-align="center">
              <template #default="{ $index }">{{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column label="参数名" min-width="190">
              <template #default="{ row, $index }">
                <el-input
                  :model-value="row.name"
                  :placeholder="keyPlaceholder"
                  @update:model-value="updateRow($index, 'name', String($event ?? ''))"
                />
              </template>
            </el-table-column>
            <el-table-column label="值" min-width="240">
              <template #default="{ row, $index }">
                <div class="protocol-json-editor__value-cell">
                  <el-input
                    :model-value="row.value"
                    :placeholder="valuePlaceholder"
                    @update:model-value="updateRow($index, 'value', String($event ?? ''))"
                  />
                  <el-dropdown v-if="placeholderOptions.length" trigger="click" @command="applyPlaceholder($index, $event)">
                    <el-button plain size="small">占位</el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item v-for="item in placeholderOptions" :key="item.value" :command="item.value">
                          {{ item.label }}
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="86" align="center" header-align="center" fixed="right">
              <template #default="{ $index }">
                <el-button link type="danger" :icon="Delete" aria-label="删除" @click="removeRow($index)" />
              </template>
            </el-table-column>
          </el-table>
        </StudioTableShell>
        <p v-else class="protocol-json-editor__empty">
          暂无参数，可点击“添加参数”或切换到原始 JSON 填写。
        </p>
      </template>

      <template v-else>
        <el-alert
          v-if="parseError"
          class="protocol-json-editor__alert"
          type="error"
          show-icon
          :closable="false"
          :title="parseError"
        />
        <el-input
          :model-value="rawText"
          type="textarea"
          :rows="rowsCount"
          :placeholder="rawPlaceholder"
          @update:model-value="updateRawText(String($event ?? ''))"
        />
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { Delete, Plus } from "@element-plus/icons-vue";
import { StudioTableShell } from "@studio/ui";

interface KeyValueRow {
  name: string;
  value: string;
}

interface PlaceholderOption {
  label: string;
  value: string;
}

const props = withDefaults(defineProps<{
  modelValue?: Record<string, unknown>;
  title: string;
  description?: string;
  index?: string;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  rawPlaceholder?: string;
  rowsCount?: number;
  placeholderOptions?: PlaceholderOption[];
  compact?: boolean;
}>(), {
  modelValue: () => ({}),
  description: "",
  index: "",
  keyPlaceholder: "参数名",
  valuePlaceholder: "参数值",
  rawPlaceholder: "{\n  \"key\": \"value\"\n}",
  rowsCount: 6,
  placeholderOptions: () => [],
  compact: false,
});

const emit = defineEmits<{
  "update:modelValue": [value: Record<string, unknown>];
  "valid-change": [valid: boolean];
}>();

const mode = ref<"form" | "raw">("form");
const rows = ref<KeyValueRow[]>([]);
const rawText = ref("{}");
const parseError = ref("");
const pendingText = ref<string | undefined>();

watch(
  () => props.modelValue,
  (value) => syncFromModel(value ?? {}),
  { immediate: true, deep: true },
);

function syncFromModel(value: Record<string, unknown>) {
  const text = JSON.stringify(value ?? {}, null, 2);
  if (pendingText.value === text) {
    pendingText.value = undefined;
    return;
  }
  rawText.value = text;
  rows.value = Object.entries(value ?? {}).map(([name, item]) => ({
    name,
    value: item == null ? "" : String(item),
  }));
  parseError.value = "";
  emit("valid-change", true);
}

function appendRow() {
  rows.value = [...rows.value, { name: "", value: "" }];
  emitRows();
}

function updateRow(index: number, field: keyof KeyValueRow, value: string) {
  rows.value = rows.value.map((row, rowIndex) => rowIndex === index ? { ...row, [field]: value } : row);
  emitRows();
}

function removeRow(index: number) {
  rows.value = rows.value.filter((_, rowIndex) => rowIndex !== index);
  emitRows();
}

function applyPlaceholder(index: number, value: unknown) {
  updateRow(index, "value", String(value ?? ""));
}

function emitRows() {
  const next: Record<string, unknown> = {};
  for (const row of rows.value) {
    const name = row.name.trim();
    if (!name) {
      continue;
    }
    next[name] = row.value;
  }
  const text = JSON.stringify(next, null, 2);
  rawText.value = text;
  pendingText.value = text;
  parseError.value = "";
  emit("valid-change", true);
  emit("update:modelValue", next);
}

function updateRawText(value: string) {
  rawText.value = value;
  if (!value.trim()) {
    pendingText.value = "{}";
    parseError.value = "";
    rows.value = [];
    emit("valid-change", true);
    emit("update:modelValue", {});
    return;
  }
  try {
    const parsed = JSON.parse(value);
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
      throw new Error("必须是 JSON 对象");
    }
    parseError.value = "";
    rows.value = Object.entries(parsed as Record<string, unknown>).map(([name, item]) => ({
      name,
      value: item == null ? "" : String(item),
    }));
    pendingText.value = JSON.stringify(parsed, null, 2);
    emit("valid-change", true);
    emit("update:modelValue", parsed as Record<string, unknown>);
  } catch (error) {
    parseError.value = error instanceof Error ? error.message : "JSON 解析失败";
    emit("valid-change", false);
  }
}
</script>

<style scoped>
.protocol-json-editor {
  display: block;
  overflow: hidden;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: #fff;
}

.protocol-json-editor__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid rgba(64, 113, 187, 0.12);
  background: rgba(15, 35, 66, 0.025);
}

.protocol-json-editor__main {
  min-width: 0;
}

.protocol-json-editor__title,
.protocol-json-editor__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.protocol-json-editor__title strong {
  color: var(--studio-text);
  font-size: 15px;
  line-height: 1.35;
}

.protocol-json-editor__main p {
  margin: 4px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.45;
}

.protocol-json-editor__index {
  display: inline-grid;
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  place-items: center;
  border: 1px solid rgba(37, 99, 235, 0.18);
  border-radius: 50%;
  color: var(--studio-primary);
  background: rgba(37, 99, 235, 0.08);
  font-size: 12px;
  font-weight: 700;
}

.protocol-json-editor__actions {
  flex-wrap: nowrap;
  justify-content: flex-end;
}

.protocol-json-editor__actions :deep(.el-radio-group) {
  flex-wrap: nowrap;
  white-space: nowrap;
}

.protocol-json-editor__body {
  padding: 12px 16px 14px;
}

.protocol-json-editor__empty {
  display: flex;
  min-height: 34px;
  align-items: center;
  margin: 0;
  padding: 0 2px;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.4;
}

.protocol-json-editor__alert {
  margin-bottom: 8px;
}

.protocol-json-editor__table :deep(th.el-table__cell) {
  background: rgba(16, 78, 139, 0.05);
}

.protocol-json-editor__value-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.protocol-json-editor__value-cell .el-input {
  min-width: 0;
}

.protocol-json-editor--compact {
  border-color: var(--studio-border);
  background: rgba(255, 255, 255, 0.72);
}

.protocol-json-editor--compact .protocol-json-editor__header {
  align-items: flex-start;
  padding: 14px 14px 0;
  gap: 12px;
  border-bottom: 0;
  background: transparent;
}

.protocol-json-editor--compact .protocol-json-editor__title strong {
  font-size: 14px;
}

.protocol-json-editor--compact .protocol-json-editor__main p {
  margin-top: 2px;
  font-size: 12px;
}

.protocol-json-editor--compact .protocol-json-editor__index {
  display: none;
}

.protocol-json-editor--compact .protocol-json-editor__body {
  padding: 12px 14px 14px;
}

.protocol-json-editor--compact .protocol-json-editor__empty {
  min-height: 28px;
  font-size: 12px;
}

@media (max-width: 900px) {
  .protocol-json-editor__header {
    grid-template-columns: 1fr;
  }

  .protocol-json-editor__actions {
    justify-content: flex-start;
  }
}
</style>
