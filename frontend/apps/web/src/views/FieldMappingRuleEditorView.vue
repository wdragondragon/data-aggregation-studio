<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ ruleId ? t("web.fieldMappingRules.editTitle") : t("web.fieldMappingRules.createTitle") }}</h3>
        <p>{{ t("web.fieldMappingRules.editorDescription") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/field-mapping-rules')">{{ t("common.backToList") }}</el-button>
        <el-button plain @click="loadRule">{{ t("common.refresh") }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveRule">{{ t("common.save") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.fieldMappingRules.basicTitle')" :description="t('web.fieldMappingRules.basicDescription')">
      <div class="studio-form-grid">
        <el-form-item :label="t('web.fieldMappingRules.mappingName')">
          <el-input v-model="form.mappingName" :placeholder="t('web.fieldMappingRules.mappingNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('web.fieldMappingRules.mappingType')">
          <el-select v-model="form.mappingType" :placeholder="t('web.fieldMappingRules.mappingTypePlaceholder')">
            <el-option v-for="option in mappingTypeOptions" :key="option" :label="option" :value="option" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('web.fieldMappingRules.mappingCode')">
          <el-input v-model="form.mappingCode" :placeholder="t('web.fieldMappingRules.mappingCodePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('web.fieldMappingRules.enabled')">
          <el-switch v-model="form.enabled" inline-prompt :active-text="t('common.yes')" :inactive-text="t('common.no')" />
        </el-form-item>
      </div>
      <el-form-item :label="t('web.fieldMappingRules.descriptionLabel')">
        <el-input v-model="form.description" type="textarea" :rows="4" :placeholder="t('web.fieldMappingRules.descriptionPlaceholder')" />
      </el-form-item>
    </SectionCard>

    <SectionCard :title="t('web.fieldMappingRules.paramsTitle')" :description="t('web.fieldMappingRules.paramsDescription')">
      <div class="section-toolbar">
        <div>
          <strong>{{ t("web.fieldMappingRules.paramsToolbarTitle") }}</strong>
          <p>{{ t("web.fieldMappingRules.paramsToolbarDescription") }}</p>
        </div>
        <el-button type="primary" plain @click="appendParam">{{ t("web.fieldMappingRules.addParam") }}</el-button>
      </div>

      <el-table :data="form.params" border>
        <el-table-column :label="t('web.fieldMappingRules.paramName')" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.paramName" :placeholder="t('web.fieldMappingRules.paramNamePlaceholder')" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.fieldMappingRules.paramOrder')" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.paramOrder" :min="1" :controls-position="'right'" class="full-width" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.fieldMappingRules.componentType')" min-width="180">
          <template #default="{ row }">
            <el-select
              v-model="row.componentType"
              :placeholder="t('web.fieldMappingRules.componentTypePlaceholder')"
              @change="handleComponentTypeChange(row)"
            >
              <el-option v-for="option in componentTypeOptions" :key="option" :label="option" :value="option" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.fieldMappingRules.paramValueJson')" min-width="240">
          <template #default="{ row }">
            <el-select
              v-if="isOptionValueComponent(row.componentType)"
              v-model="row.optionValues"
              multiple
              filterable
              allow-create
              default-first-option
              clearable
              class="full-width"
              :reserve-keyword="false"
              :placeholder="t('web.fieldMappingRules.paramValueJsonOptionPlaceholder')"
              @change="handleOptionValuesChange(row)"
            >
              <el-option
                v-for="option in row.optionValues"
                :key="option"
                :label="option"
                :value="option"
              />
            </el-select>
            <el-input
              v-else-if="isLongTextValueComponent(row.componentType)"
              v-model="row.paramValueJson"
              type="textarea"
              :rows="3"
              :placeholder="resolveParamValuePlaceholder(row.componentType)"
            />
            <el-input
              v-else
              v-model="row.paramValueJson"
              clearable
              :placeholder="resolveParamValuePlaceholder(row.componentType)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.fieldMappingRules.paramDescription')" min-width="220">
          <template #default="{ row }">
            <el-input v-model="row.description" :placeholder="t('web.fieldMappingRules.paramDescriptionPlaceholder')" />
          </template>
        </el-table-column>
        <el-table-column :label="t('fieldMapping.actions')" width="110" align="center" header-align="center">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeParam($index)">{{ t("common.remove") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type { FieldMappingRuleParamSaveRequest, FieldMappingRuleSaveRequest } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";

const componentTypeOptions = [
  "input",
  "numberPicker",
  "textArea",
  "datePicker",
  "dateTimePicker",
  "rangePicker",
  "select",
  "checkbox",
  "radioGroup",
];

const mappingTypeOptions = [
  "过滤",
  "规整",
  "脱敏",
  "加密",
];

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const saving = ref(false);
const ruleId = computed(() => route.params.ruleId as string | undefined);

interface FieldMappingRuleFormParam extends FieldMappingRuleParamSaveRequest {
  optionValues: string[];
}

interface FieldMappingRuleFormState extends Omit<FieldMappingRuleSaveRequest, "params"> {
  params: FieldMappingRuleFormParam[];
}

const form = reactive<FieldMappingRuleFormState>({
  mappingName: "",
  mappingType: "",
  mappingCode: "",
  enabled: true,
  description: "",
  params: [],
});

async function loadRule() {
  if (!ruleId.value) {
    resetForm();
    return;
  }
  try {
    const rule = await studioApi.fieldMappingRules.get(ruleId.value);
    form.id = rule.id;
    form.mappingName = rule.mappingName;
    form.mappingType = rule.mappingType;
    form.mappingCode = rule.mappingCode;
    form.enabled = Boolean(rule.enabled);
    form.description = rule.description ?? "";
    form.params = (rule.params ?? []).map((param) => ({
      id: param.id,
      paramName: param.paramName,
      paramOrder: param.paramOrder,
      componentType: param.componentType,
      paramValueJson: param.paramValueJson ?? "",
      optionValues: parseOptionValues(param.paramValueJson),
      description: param.description ?? "",
    }));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.fieldMappingRules.loadDetailFailed"));
  }
}

function resetForm() {
  form.id = undefined;
  form.mappingName = "";
  form.mappingType = "";
  form.mappingCode = "";
  form.enabled = true;
  form.description = "";
  form.params = [];
}

function appendParam() {
  form.params.push({
    paramName: "",
    paramOrder: form.params.length + 1,
    componentType: "input",
    paramValueJson: "",
    optionValues: [],
    description: "",
  });
}

function removeParam(index: number) {
  form.params.splice(index, 1);
}

function isOptionValueComponent(componentType?: string) {
  return componentType === "select" || componentType === "checkbox" || componentType === "radioGroup";
}

function isLongTextValueComponent(componentType?: string) {
  return componentType === "textArea";
}

function resolveParamValuePlaceholder(componentType?: string) {
  if (isOptionValueComponent(componentType)) {
    return t("web.fieldMappingRules.paramValueJsonOptionPlaceholder");
  }
  if (isLongTextValueComponent(componentType)) {
    return t("web.fieldMappingRules.paramValueJsonTextPlaceholder");
  }
  return t("web.fieldMappingRules.paramValueJsonNormalPlaceholder");
}

function parseOptionValues(paramValueJson?: string) {
  if (!paramValueJson?.trim()) {
    return [];
  }
  try {
    const parsed = JSON.parse(paramValueJson);
    if (!Array.isArray(parsed)) {
      return [];
    }
    const values = parsed
      .map((item) => {
        if (typeof item === "string") {
          return item.trim();
        }
        if (item && typeof item === "object") {
          const candidate = "value" in item ? item.value : ("label" in item ? item.label : undefined);
          return typeof candidate === "string" ? candidate.trim() : "";
        }
        return "";
      })
      .filter((item) => item.length > 0);
    return Array.from(new Set(values));
  } catch {
    return [];
  }
}

function normalizeOptionValues(values: string[]) {
  return Array.from(new Set(values
    .map((item) => item.trim())
    .filter((item) => item.length > 0)));
}

function handleComponentTypeChange(row: FieldMappingRuleFormParam) {
  if (isOptionValueComponent(row.componentType)) {
    row.optionValues = normalizeOptionValues(
      row.optionValues.length ? row.optionValues : parseOptionValues(row.paramValueJson),
    );
    row.paramValueJson = row.optionValues.length ? JSON.stringify(row.optionValues) : "";
    return;
  }
  if (!row.paramValueJson?.trim() && row.optionValues.length) {
    row.paramValueJson = JSON.stringify(normalizeOptionValues(row.optionValues));
  }
}

function handleOptionValuesChange(row: FieldMappingRuleFormParam) {
  row.optionValues = normalizeOptionValues(row.optionValues);
  row.paramValueJson = row.optionValues.length ? JSON.stringify(row.optionValues) : "";
}

async function saveRule() {
  saving.value = true;
  try {
    const params = form.params.map((param) => {
      const optionValues = normalizeOptionValues(param.optionValues ?? []);
      return {
        id: param.id,
        paramName: param.paramName,
        paramOrder: param.paramOrder,
        componentType: param.componentType,
        paramValueJson: isOptionValueComponent(param.componentType)
          ? (optionValues.length ? JSON.stringify(optionValues) : "")
          : param.paramValueJson,
        description: param.description,
      } satisfies FieldMappingRuleParamSaveRequest;
    });
    const saved = await studioApi.fieldMappingRules.save({
      id: form.id,
      mappingName: form.mappingName,
      mappingType: form.mappingType,
      mappingCode: form.mappingCode,
      enabled: form.enabled,
      description: form.description,
      params,
    });
    ElMessage.success(t("web.fieldMappingRules.saveSuccess"));
    router.push(`/field-mapping-rules/${saved.id}/edit`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.fieldMappingRules.saveFailed"));
  } finally {
    saving.value = false;
  }
}

onMounted(loadRule);
</script>

<style scoped>
.full-width {
  width: 100%;
}
</style>
