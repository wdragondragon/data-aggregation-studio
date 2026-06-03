<template>
  <el-dialog
    v-model="dialogVisible"
    :title="title || t('fieldMapping.transformerDialogTitle')"
    width="860px"
    append-to-body
    destroy-on-close
  >
    <div class="mapping-editor__dialog">
      <div class="mapping-editor__toolbar">
        <span>{{ description || t("fieldMapping.transformerDialogDescription") }}</span>
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
        <el-button @click="dialogVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="saveTransformers">{{ t("common.save") }}</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { EntityId, FieldMappingRuleParamView, FieldMappingRuleView, TransformerBinding } from "@studio/api-sdk";
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
    visible: boolean;
    modelValue?: TransformerBinding[];
    ruleOptions?: FieldMappingRuleView[];
    allowedTransformerCodes?: string[];
    title?: string;
    description?: string;
  }>(),
  {
    modelValue: () => [],
    ruleOptions: () => [],
    allowedTransformerCodes: () => [],
    title: "",
    description: "",
  },
);

const emit = defineEmits<{
  "update:visible": [value: boolean];
  save: [value: TransformerBinding[]];
}>();

const { t } = useI18n();
const transformerDrafts = ref<TransformerRuleDraft[]>([]);

const dialogVisible = computed({
  get() {
    return props.visible;
  },
  set(value: boolean) {
    emit("update:visible", value);
  },
});

const effectiveRuleOptions = computed(() => {
  if (!props.allowedTransformerCodes.length) {
    return props.ruleOptions ?? [];
  }
  const allowed = new Set(props.allowedTransformerCodes.map((item) => item.toLowerCase()));
  return (props.ruleOptions ?? []).filter((rule) => rule.mappingCode && allowed.has(rule.mappingCode.toLowerCase()));
});

const mappingTypeOptions = computed(() => {
  const types = new Set<string>();
  for (const rule of effectiveRuleOptions.value) {
    if (rule.mappingType?.trim()) {
      types.add(rule.mappingType.trim());
    }
  }
  return Array.from(types);
});

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      transformerDrafts.value = (props.modelValue ?? []).map(buildDraftFromBinding);
      if (!transformerDrafts.value.length) {
        appendTransformer();
      }
    }
  },
);

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
  return effectiveRuleOptions.value.find((rule) =>
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
  return effectiveRuleOptions.value.find((rule) => sameEntityId(rule.id, draft.mappingRuleId));
}

function resolveRuleOptionsByType(mappingType?: string) {
  if (!mappingType?.trim()) {
    return [];
  }
  return effectiveRuleOptions.value.filter((rule) => rule.mappingType === mappingType.trim());
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
  emit("save", transformers);
  dialogVisible.value = false;
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
.mapping-editor__toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
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
