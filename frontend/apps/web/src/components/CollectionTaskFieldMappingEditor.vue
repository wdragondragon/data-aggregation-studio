<template>
  <div class="mapping-editor">
    <div class="mapping-editor__toolbar">
      <span>{{ t("fieldMapping.title") }}</span>
      <el-button type="primary" plain @click="appendRow">{{ t("common.addMapping") }}</el-button>
    </div>

    <el-table :data="rows" border>
      <el-table-column v-if="showSourceAlias" :label="t('fieldMapping.sourceAlias')" min-width="150">
        <template #default="{ row, $index }">
          <el-select
            :model-value="row.sourceAlias"
            clearable
            filterable
            :placeholder="t('fieldMapping.selectSourceAlias')"
            @update:model-value="updateRow($index, 'sourceAlias', $event)"
          >
            <el-option v-for="alias in sourceAliases" :key="alias" :label="alias" :value="alias" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column :label="t('fieldMapping.sourceField')" min-width="180">
        <template #default="{ row, $index }">
          <el-select
            :model-value="row.sourceField"
            clearable
            filterable
            :placeholder="t('fieldMapping.selectSourceField')"
            @update:model-value="updateRow($index, 'sourceField', $event)"
          >
            <el-option v-for="field in resolveSourceFieldOptions(row)" :key="field" :label="field" :value="field" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column :label="t('fieldMapping.targetField')" min-width="180">
        <template #default="{ row, $index }">
          <el-select
            :model-value="row.targetField"
            clearable
            filterable
            :placeholder="t('fieldMapping.selectTargetField')"
            @update:model-value="updateRow($index, 'targetField', $event)"
          >
            <el-option v-for="field in targetFields" :key="field" :label="field" :value="field" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column v-if="showExpression" :label="t('fieldMapping.expression')" min-width="220">
        <template #default="{ row, $index }">
          <el-input
            :model-value="row.expression"
            :placeholder="t('fieldMapping.optionalExpression')"
            @update:model-value="updateRow($index, 'expression', $event)"
          />
        </template>
      </el-table-column>

      <el-table-column :label="t('fieldMapping.transformers')" min-width="280">
        <template #default="{ row, $index }">
          <div class="mapping-editor__transformers">
            <span class="mapping-editor__transformer-summary">{{ transformerSummary(row.transformers) }}</span>
            <el-button link type="primary" @click="openTransformerDialog($index)">{{ t("fieldMapping.configureTransformers") }}</el-button>
          </div>
        </template>
      </el-table-column>

      <el-table-column :label="t('fieldMapping.actions')" width="110" fixed="right">
        <template #default="{ $index }">
          <el-button type="danger" link @click="removeRow($index)">{{ t("common.remove") }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="transformerDialogVisible"
      :title="t('fieldMapping.transformerDialogTitle')"
      width="860px"
      append-to-body
      destroy-on-close
    >
      <div class="mapping-editor__dialog">
        <div class="mapping-editor__toolbar">
          <span>{{ t("fieldMapping.transformerDialogDescription") }}</span>
          <el-button type="primary" plain @click="appendTransformer">{{ t("fieldMapping.addTransformer") }}</el-button>
        </div>

        <div v-if="!transformerDrafts.length" class="mapping-editor__empty">
          {{ t("fieldMapping.noTransformers") }}
        </div>

        <div v-for="(draft, index) in transformerDrafts" :key="draft.key" class="transformer-draft">
          <div class="transformer-draft__header">
            <div class="transformer-draft__heading">
              <strong>{{ t("fieldMapping.transformerCardTitle", { index: index + 1 }) }}</strong>
              <span class="transformer-draft__summary">{{ transformerDraftSummary(draft) }}</span>
            </div>
            <div class="transformer-draft__actions">
              <el-button link @click="toggleTransformer(draft)">
                {{ draft.expanded ? t("fieldMapping.collapseTransformer") : t("fieldMapping.expandTransformer") }}
              </el-button>
              <el-button link type="danger" @click="removeTransformer(index)">{{ t("common.remove") }}</el-button>
            </div>
          </div>

          <el-collapse-transition>
            <div v-if="draft.expanded" class="transformer-draft__content">
              <el-alert
                v-if="draft.missing"
                type="warning"
                :closable="false"
                :title="t('fieldMapping.missingRuleTitle')"
                :description="draft.missingReason || t('fieldMapping.missingRuleDescription')"
              />

              <div v-if="draft.missing" class="transformer-draft__missing">
                <div class="stack-label">
                  <span>{{ t("fieldMapping.mappingType") }}</span>
                  <strong>{{ draft.mappingType || draft.originalBinding?.mappingType || t("common.none") }}</strong>
                </div>
                <div class="stack-label">
                  <span>{{ t("fieldMapping.mappingMethod") }}</span>
                  <strong>{{ draft.originalBinding?.mappingName || draft.originalBinding?.mappingCode || draft.originalBinding?.transformerCode || t("common.none") }}</strong>
                </div>
                <div class="stack-label">
                  <span>{{ t("fieldMapping.transformerParameters") }}</span>
                  <pre class="json-block">{{ prettyJson(draft.originalBinding?.parameters ?? {}) }}</pre>
                </div>
              </div>

              <template v-else>
                <div class="studio-form-grid transformer-draft__grid">
                  <el-form-item :label="t('fieldMapping.mappingType')">
                    <el-select
                      v-model="draft.mappingType"
                      filterable
                      clearable
                      :placeholder="t('fieldMapping.mappingTypePlaceholder')"
                      @change="handleDraftTypeChange(draft)"
                    >
                      <el-option v-for="type in mappingTypeOptions" :key="type" :label="type" :value="type" />
                    </el-select>
                  </el-form-item>

                  <el-form-item :label="t('fieldMapping.mappingMethod')">
                    <el-select
                      :model-value="draft.mappingRuleId == null ? undefined : String(draft.mappingRuleId)"
                      filterable
                      clearable
                      :placeholder="t('fieldMapping.mappingMethodPlaceholder')"
                      @update:model-value="handleDraftRuleChange(draft, $event)"
                    >
                      <el-option
                        v-for="option in resolveRuleOptionsByType(draft.mappingType)"
                        :key="String(option.id)"
                        :label="option.mappingName"
                        :value="String(option.id)"
                      />
                    </el-select>
                  </el-form-item>
                </div>

                <div v-if="resolveDraftRule(draft)?.params?.length" class="transformer-draft__params">
                  <div
                    v-for="param in sortedParams(resolveDraftRule(draft)?.params)"
                    :key="`${draft.key}_${param.paramName}`"
                    class="transformer-draft__param"
                  >
                    <label>{{ param.paramName }}</label>
                    <div class="transformer-draft__param-control">
                      <el-input
                        v-if="param.componentType === 'input'"
                        v-model="draft.paramValues[param.paramName]"
                        :placeholder="param.description || t('fieldMapping.parameterValuePlaceholder')"
                      />
                      <el-input-number
                        v-else-if="param.componentType === 'numberPicker'"
                        v-model="draft.paramValues[param.paramName]"
                        :controls-position="'right'"
                        class="full-width"
                      />
                      <el-input
                        v-else-if="param.componentType === 'textArea'"
                        v-model="draft.paramValues[param.paramName]"
                        type="textarea"
                        :rows="3"
                        :placeholder="param.description || t('fieldMapping.parameterValuePlaceholder')"
                      />
                      <el-date-picker
                        v-else-if="param.componentType === 'datePicker'"
                        v-model="draft.paramValues[param.paramName]"
                        type="date"
                        value-format="YYYY-MM-DD"
                        class="full-width"
                      />
                      <el-date-picker
                        v-else-if="param.componentType === 'dateTimePicker'"
                        v-model="draft.paramValues[param.paramName]"
                        type="datetime"
                        value-format="YYYY-MM-DD HH:mm:ss"
                        class="full-width"
                      />
                      <el-date-picker
                        v-else-if="param.componentType === 'rangePicker'"
                        v-model="draft.paramValues[param.paramName]"
                        type="daterange"
                        value-format="YYYY-MM-DD"
                        class="full-width"
                      />
                      <el-select
                        v-else-if="param.componentType === 'select'"
                        v-model="draft.paramValues[param.paramName]"
                        clearable
                        class="full-width"
                        :placeholder="param.description || t('fieldMapping.parameterValuePlaceholder')"
                      >
                        <el-option
                          v-for="option in resolveParameterOptions(param)"
                          :key="String(option.value)"
                          :label="option.label"
                          :value="option.value"
                        />
                      </el-select>
                      <el-radio-group
                        v-else-if="param.componentType === 'radioGroup' && resolveParameterOptions(param).length"
                        v-model="draft.paramValues[param.paramName]"
                        class="radio-group"
                      >
                        <el-radio
                          v-for="option in resolveParameterOptions(param)"
                          :key="String(option.value)"
                          :label="option.value"
                        >
                          {{ option.label }}
                        </el-radio>
                      </el-radio-group>
                      <el-input
                        v-else-if="param.componentType === 'radioGroup'"
                        v-model="draft.paramValues[param.paramName]"
                        clearable
                        :placeholder="param.description || t('fieldMapping.parameterValuePlaceholder')"
                      />
                      <el-checkbox-group
                        v-else-if="param.componentType === 'checkbox' && resolveParameterOptions(param).length"
                        v-model="draft.paramValues[param.paramName]"
                        class="checkbox-group"
                      >
                        <el-checkbox
                          v-for="option in resolveParameterOptions(param)"
                          :key="String(option.value)"
                          :label="option.value"
                        >
                          {{ option.label }}
                        </el-checkbox>
                      </el-checkbox-group>
                      <el-checkbox
                        v-else-if="param.componentType === 'checkbox'"
                        v-model="draft.paramValues[param.paramName]"
                      >
                        {{ param.description || t("common.on") }}
                      </el-checkbox>
                      <el-input
                        v-else
                        v-model="draft.paramValues[param.paramName]"
                        :placeholder="param.description || t('fieldMapping.parameterValuePlaceholder')"
                      />
                      <span v-if="param.description" class="param-description">{{ param.description }}</span>
                    </div>
                  </div>
                </div>

                <div v-else-if="resolveDraftRule(draft)" class="mapping-editor__empty">
                  {{ t("fieldMapping.noRuleParameters") }}
                </div>
              </template>
            </div>
          </el-collapse-transition>
        </div>
      </div>

      <template #footer>
        <div class="mapping-editor__dialog-footer">
          <el-button @click="transformerDialogVisible = false">{{ t("common.cancel") }}</el-button>
          <el-button type="primary" @click="saveTransformers">{{ t("common.save") }}</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { EntityId, FieldMappingDefinition, FieldMappingRuleParamView, FieldMappingRuleView, TransformerBinding } from "@studio/api-sdk";
import { cloneDeep, prettyJson, sameEntityId } from "@/utils/studio";

interface ParameterOption {
  label: string;
  value: unknown;
}

interface TransformerRuleDraft {
  key: string;
  mappingType: string;
  mappingRuleId?: EntityId;
  paramValues: Record<string, unknown>;
  expanded: boolean;
  missing?: boolean;
  missingReason?: string;
  originalBinding?: TransformerBinding;
}

const props = withDefaults(
  defineProps<{
    modelValue: FieldMappingDefinition[];
    sourceFields?: string[];
    targetFields?: string[];
    sourceAliases?: string[];
    sourceFieldOptionsByAlias?: Record<string, string[]>;
    ruleOptions?: FieldMappingRuleView[];
    showExpression?: boolean;
  }>(),
  {
    modelValue: () => [],
    sourceFields: () => [],
    targetFields: () => [],
    sourceAliases: () => [],
    sourceFieldOptionsByAlias: () => ({}),
    ruleOptions: () => [],
    showExpression: true,
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: FieldMappingDefinition[]];
}>();

const { t } = useI18n();
const rows = computed(() => props.modelValue ?? []);
const showSourceAlias = computed(() => props.sourceAliases.length > 0);
const transformerDialogVisible = ref(false);
const editingRowIndex = ref<number | null>(null);
const transformerDrafts = ref<TransformerRuleDraft[]>([]);

const mappingTypeOptions = computed(() => {
  const types = new Set<string>();
  for (const rule of props.ruleOptions) {
    if (rule.mappingType?.trim()) {
      types.add(rule.mappingType.trim());
    }
  }
  return Array.from(types);
});

function appendRow() {
  emit("update:modelValue", [
    ...rows.value,
    {
      sourceAlias: props.sourceAliases[0] ?? "",
      sourceField: "",
      targetField: "",
      expression: "",
      transformers: [],
    },
  ]);
}

function updateRow(index: number, key: keyof FieldMappingDefinition, value: unknown) {
  const next = rows.value.map((row, rowIndex) =>
    rowIndex === index
      ? {
          ...row,
          [key]: value,
          ...(key === "sourceAlias" ? { sourceField: "" } : {}),
        }
      : row,
  );
  emit("update:modelValue", next);
}

function removeRow(index: number) {
  emit("update:modelValue", rows.value.filter((_, rowIndex) => rowIndex !== index));
}

function resolveSourceFieldOptions(row: FieldMappingDefinition) {
  if (row.sourceAlias && props.sourceFieldOptionsByAlias[row.sourceAlias]?.length) {
    return props.sourceFieldOptionsByAlias[row.sourceAlias];
  }
  return props.sourceFields;
}

function transformerSummary(transformers: TransformerBinding[]) {
  if (!transformers?.length) {
    return t("fieldMapping.noTransformers");
  }
  return transformers
    .map((item) => item.mappingName || item.mappingCode || item.transformerCode)
    .filter(Boolean)
    .join(", ");
}

function openTransformerDialog(index: number) {
  editingRowIndex.value = index;
  transformerDrafts.value = (rows.value[index]?.transformers ?? []).map(buildDraftFromBinding);
  if (!transformerDrafts.value.length) {
    appendTransformer();
  }
  transformerDialogVisible.value = true;
}

function appendTransformer() {
  transformerDrafts.value.push({
    key: createDraftKey(),
    mappingType: "",
    paramValues: {},
    expanded: true,
  });
}

function removeTransformer(index: number) {
  transformerDrafts.value.splice(index, 1);
}

function buildDraftFromBinding(binding: TransformerBinding): TransformerRuleDraft {
  const matchedRule = resolveRuleForBinding(binding);
  if (!matchedRule) {
    return {
      key: createDraftKey(),
      mappingType: binding.mappingType || "",
      mappingRuleId: binding.mappingRuleId,
      paramValues: {},
      expanded: false,
      missing: true,
      missingReason: t("fieldMapping.missingRuleDescription"),
      originalBinding: cloneDeep(binding),
    };
  }
    return {
      key: createDraftKey(),
      mappingType: matchedRule.mappingType,
      mappingRuleId: matchedRule.id,
      paramValues: extractParamValues(matchedRule, binding.parameters ?? {}),
      expanded: false,
    };
}

function resolveRuleForBinding(binding?: TransformerBinding | null) {
  if (!binding) {
    return undefined;
  }
  return props.ruleOptions.find((rule) =>
    sameEntityId(rule.id, binding.mappingRuleId)
      || (binding.mappingCode && rule.mappingCode === binding.mappingCode)
      || (binding.transformerCode && rule.mappingCode === binding.transformerCode));
}

function extractParamValues(rule: FieldMappingRuleView, parameters: Record<string, unknown>) {
  const result: Record<string, unknown> = {};
  const orderedParams = sortedParams(rule.params);
  const paras = Array.isArray(parameters.paras) ? parameters.paras as unknown[] : [];
  orderedParams.forEach((param, index) => {
    if (Object.prototype.hasOwnProperty.call(parameters, param.paramName)) {
      result[param.paramName] = normalizeDraftParameterValue(param, parameters[param.paramName]);
      return;
    }
    if (index < paras.length) {
      result[param.paramName] = normalizeDraftParameterValue(param, paras[index]);
      return;
    }
    result[param.paramName] = defaultDraftParameterValue(param);
  });
  return result;
}

function resolveDraftRule(draft: TransformerRuleDraft) {
  if (draft.mappingRuleId == null) {
    return undefined;
  }
  return props.ruleOptions.find((rule) => sameEntityId(rule.id, draft.mappingRuleId));
}

function resolveRuleOptionsByType(mappingType?: string) {
  if (!mappingType?.trim()) {
    return [];
  }
  return props.ruleOptions.filter((rule) => rule.mappingType === mappingType.trim());
}

function handleDraftTypeChange(draft: TransformerRuleDraft) {
  draft.mappingRuleId = undefined;
  draft.paramValues = {};
}

function handleDraftRuleChange(draft: TransformerRuleDraft, value?: string | number) {
  if (value == null || value === "") {
    draft.mappingRuleId = undefined;
    draft.paramValues = {};
    return;
  }
  draft.mappingRuleId = String(value);
  const rule = resolveDraftRule(draft);
  draft.paramValues = initializeDraftParamValues(rule);
}

function toggleTransformer(draft: TransformerRuleDraft) {
  draft.expanded = !draft.expanded;
}

function sortedParams(params?: FieldMappingRuleParamView[]) {
  return [...(params ?? [])].sort((left, right) => (left.paramOrder ?? 0) - (right.paramOrder ?? 0));
}

function resolveParameterOptions(param: FieldMappingRuleParamView): ParameterOption[] {
  if (!param.paramValueJson?.trim()) {
    return [];
  }
  try {
    const parsed = JSON.parse(param.paramValueJson);
    if (Array.isArray(parsed)) {
      return parsed.map((item) => {
        if (item && typeof item === "object" && "label" in item && "value" in item) {
          return {
            label: String((item as { label: unknown }).label ?? ""),
            value: (item as { value: unknown }).value,
          };
        }
        return {
          label: String(item),
          value: item,
        };
      });
    }
  } catch {
    return [];
  }
  return [];
}

function defaultDraftParameterValue(param: FieldMappingRuleParamView) {
  if (param.componentType === "checkbox") {
    return resolveParameterOptions(param).length ? [] : false;
  }
  return undefined;
}

function initializeDraftParamValues(rule?: FieldMappingRuleView) {
  const result: Record<string, unknown> = {};
  if (!rule) {
    return result;
  }
  for (const param of sortedParams(rule.params)) {
    const defaultValue = defaultDraftParameterValue(param);
    if (defaultValue !== undefined) {
      result[param.paramName] = defaultValue;
    }
  }
  return result;
}

function normalizeDraftParameterValue(param: FieldMappingRuleParamView, value: unknown) {
  if (param.componentType === "checkbox") {
    if (resolveParameterOptions(param).length) {
      if (Array.isArray(value)) {
        return value;
      }
      if (value == null || value === "") {
        return [];
      }
      return [value];
    }
    if (typeof value === "string") {
      const lowered = value.trim().toLowerCase();
      if (lowered === "true") {
        return true;
      }
      if (lowered === "false") {
        return false;
      }
    }
    return Boolean(value);
  }
  return value;
}

function transformerDraftSummary(draft: TransformerRuleDraft) {
  const mappingType = draft.mappingType?.trim()
    || draft.originalBinding?.mappingType
    || t("common.none");
  const rule = resolveDraftRule(draft);
  const mappingMethod = rule?.mappingName
    || draft.originalBinding?.mappingName
    || draft.originalBinding?.mappingCode
    || draft.originalBinding?.transformerCode
    || t("common.none");
  return `${t("fieldMapping.mappingType")}: ${mappingType} · ${t("fieldMapping.mappingMethod")}: ${mappingMethod}`;
}

function saveTransformers() {
  if (editingRowIndex.value == null) {
    transformerDialogVisible.value = false;
    return;
  }
  const transformers: TransformerBinding[] = [];
  for (const draft of transformerDrafts.value) {
    if (draft.missing) {
      if (draft.originalBinding) {
        transformers.push(cloneDeep(draft.originalBinding));
      }
      continue;
    }
    if (!draft.mappingType?.trim() && draft.mappingRuleId == null) {
      continue;
    }
    if (!draft.mappingType?.trim() || draft.mappingRuleId == null) {
      ElMessage.error(t("fieldMapping.mappingRuleIncomplete"));
      return;
    }
    const rule = resolveDraftRule(draft);
    if (!rule) {
      ElMessage.error(t("fieldMapping.mappingRuleMissing"));
      return;
    }
    transformers.push(buildTransformerBinding(rule, draft.paramValues));
  }
  updateRow(editingRowIndex.value, "transformers", transformers);
  transformerDialogVisible.value = false;
}

function buildTransformerBinding(rule: FieldMappingRuleView, paramValues: Record<string, unknown>): TransformerBinding {
  const orderedParams = sortedParams(rule.params);
  const parameters: Record<string, unknown> = {};
  parameters.paras = orderedParams.map((param) => normalizeParameterValue(paramValues[param.paramName]));
  for (const param of orderedParams) {
    parameters[param.paramName] = normalizeParameterValue(paramValues[param.paramName]);
  }
  return {
    mappingRuleId: rule.id,
    mappingCode: rule.mappingCode,
    mappingName: rule.mappingName,
    mappingType: rule.mappingType,
    transformerCode: rule.mappingCode,
    parameters,
  };
}

function normalizeParameterValue(value: unknown) {
  return value === undefined ? null : value;
}

function createDraftKey() {
  return `${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}
</script>

<style scoped>
.mapping-editor {
  display: grid;
  gap: 12px;
}

.mapping-editor__toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.mapping-editor__transformers {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.mapping-editor__transformer-summary {
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mapping-editor__dialog {
  display: grid;
  gap: 16px;
}

.mapping-editor__dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.mapping-editor__empty {
  padding: 16px;
  border-radius: 12px;
  border: 1px dashed var(--el-border-color);
  color: var(--el-text-color-secondary);
}

.transformer-draft {
  display: grid;
  gap: 14px;
  padding: 16px;
  border-radius: 16px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  background: rgba(248, 250, 252, 0.72);
}

.transformer-draft__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.transformer-draft__heading {
  display: grid;
  gap: 4px;
}

.transformer-draft__summary {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.transformer-draft__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.transformer-draft__content {
  display: grid;
  gap: 14px;
}

.transformer-draft__grid {
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
}

.transformer-draft__params {
  display: grid;
  gap: 14px;
}

.transformer-draft__param {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr);
  align-items: start;
  gap: 12px;
}

.transformer-draft__param label {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  line-height: 32px;
}

.transformer-draft__param-control {
  display: grid;
  gap: 8px;
}

.transformer-draft__missing {
  display: grid;
  gap: 12px;
}

.stack-label {
  display: grid;
  gap: 4px;
}

.stack-label span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.checkbox-group {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
}

.radio-group {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
}

.param-description {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.full-width {
  width: 100%;
}

@media (max-width: 900px) {
  .transformer-draft__param {
    grid-template-columns: minmax(0, 1fr);
  }

  .transformer-draft__param label {
    line-height: 1.5;
  }
}
</style>
