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
            <component
              :is="resolveComponent(field)"
              v-bind="resolveProps(field)"
              :model-value="fieldValue(field.fieldKey)"
              @update:model-value="updateField(field.fieldKey, $event)"
            >
              <template v-if="field.componentType === 'SELECT'">
                <el-option
                  v-for="option in field.options ?? []"
                  :key="option"
                  :label="option"
                  :value="option"
                />
              </template>
            </component>
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

function fieldValue(fieldKey: string) {
  return localValue.value[fieldKey] as string | number | boolean | Record<string, unknown> | undefined;
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
    case "SELECT":
      return {
        clearable: true,
        filterable: true,
        placeholder: field.placeholder ?? t("metaForm.selectField", { fieldName: field.fieldName }),
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
