<template>
  <div class="meta-form">
    <section
      v-for="section in scopedSections"
      :key="section.key"
      class="meta-form__section"
    >
      <header class="meta-form__section-header">
        <h4>{{ section.title }}</h4>
        <span>{{ t("common.fields", { count: section.fields.length }) }}</span>
      </header>

      <el-form label-position="top">
        <div class="meta-form__grid">
          <el-form-item
            v-for="field in section.fields"
            :key="field.fieldKey"
            :label="field.fieldName"
            :required="field.required"
          >
            <el-select
              v-if="field.componentType === 'SELECT'"
              :key="`${field.fieldKey}:${field.valueType}`"
              :model-value="fieldValue(field.fieldKey)"
              :multiple="isArraySelectField(field)"
              :collapse-tags="isArraySelectField(field)"
              :collapse-tags-tooltip="isArraySelectField(field)"
              clearable
              filterable
              :placeholder="field.placeholder ?? t('metaForm.selectField', { fieldName: field.fieldName })"
              @update:model-value="updateSelectField(field, $event)"
            >
              <el-option
                v-for="option in field.options ?? []"
                :key="option"
                :label="option"
                :value="option"
              />
            </el-select>
            <component
              v-else
              :is="resolveComponent(field)"
              v-bind="resolveProps(field)"
              :model-value="fieldValue(field.fieldKey)"
              @update:model-value="updateField(field.fieldKey, $event)"
            />
          </el-form-item>
        </div>
      </el-form>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { ElInput, ElInputNumber, ElSelect, ElSwitch } from "element-plus";
import { useI18n } from "vue-i18n";
import type { MetadataFieldDefinition } from "@studio/api-sdk";

const props = withDefaults(
  defineProps<{
    fields: MetadataFieldDefinition[];
    modelValue: Record<string, unknown>;
  }>(),
  {
    fields: () => [],
    modelValue: () => ({}),
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: Record<string, unknown>];
}>();

const { t } = useI18n();
const localValue = computed(() => props.modelValue ?? {});

const scopedSections = computed(() => {
  const technical = props.fields.filter((field) => field.scope === "TECHNICAL" || !field.scope);
  const business = props.fields.filter((field) => field.scope === "BUSINESS");
  return [
    { key: "technical", title: t("metaForm.technicalTitle"), fields: technical },
    { key: "business", title: t("metaForm.businessTitle"), fields: business },
  ].filter((section) => section.fields.length > 0);
});

function updateField(fieldKey: string, value: unknown) {
  emit("update:modelValue", {
    ...localValue.value,
    [fieldKey]: value,
  });
}

function updateSelectField(field: MetadataFieldDefinition, value: unknown) {
  updateField(field.fieldKey, isArraySelectField(field) ? normalizeArrayValue(value) : value);
}

function fieldValue(fieldKey: string) {
  const field = props.fields.find((item) => item.fieldKey === fieldKey);
  const value = localValue.value[fieldKey];
  if (field && isArraySelectField(field)) {
    return normalizeArrayValue(value);
  }
  return value as string | number | boolean | Record<string, unknown> | string[] | undefined;
}

function isArraySelectField(field: MetadataFieldDefinition) {
  return field.componentType === "SELECT" && field.valueType === "ARRAY";
}

function normalizeArrayValue(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => String(item)).filter(Boolean);
  }
  if (value === undefined || value === null) {
    return [];
  }
  const text = String(value).trim();
  if (!text) {
    return [];
  }
  try {
    const parsed = JSON.parse(text) as unknown;
    if (Array.isArray(parsed)) {
      return parsed.map((item) => String(item)).filter(Boolean);
    }
  } catch (error) {
    // Legacy values may be stored as a plain field name instead of JSON.
  }
  return [text];
}

function resolveComponent(field: MetadataFieldDefinition) {
  switch (field.componentType) {
    case "NUMBER":
      return ElInputNumber;
    case "SELECT":
      return ElSelect;
    case "SWITCH":
      return ElSwitch;
    default:
      return ElInput;
  }
}

function resolveProps(field: MetadataFieldDefinition) {
  switch (field.componentType) {
    case "PASSWORD":
      return {
        type: "password",
        showPassword: true,
        placeholder: field.placeholder ?? t("metaForm.enterField", { fieldName: field.fieldName }),
      };
    case "TEXTAREA":
    case "JSON_EDITOR":
    case "SQL_EDITOR":
    case "CODE_EDITOR":
      return {
        type: "textarea",
        rows: 5,
        placeholder: field.placeholder ?? t("metaForm.enterField", { fieldName: field.fieldName }),
      };
    case "SWITCH":
      return {
        inlinePrompt: true,
        activeText: t("common.on"),
        inactiveText: t("common.off"),
      };
    case "NUMBER":
      return {
        controlsPosition: "right",
        placeholder: field.placeholder ?? t("metaForm.enterField", { fieldName: field.fieldName }),
      };
    default:
      return {
        placeholder: field.placeholder ?? t("metaForm.enterField", { fieldName: field.fieldName }),
      };
  }
}
</script>

<style scoped>
.meta-form {
  display: grid;
  gap: 18px;
}

.meta-form__section {
  border: 1px solid var(--studio-border);
  border-radius: 18px;
  padding: 16px 18px 4px;
  background: rgba(255, 255, 255, 0.66);
}

.meta-form__section-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.meta-form__section-header h4 {
  margin: 0;
  font-size: 16px;
}

.meta-form__section-header span {
  font-size: 12px;
  color: var(--studio-text-soft);
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.meta-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

@media (max-width: 860px) {
  .meta-form__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
