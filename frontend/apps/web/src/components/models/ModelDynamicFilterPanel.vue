<template>
  <div class="soft-panel model-query-panel">
    <div class="model-query-panel__header">
      <div>
        <strong>{{ t("web.models.dynamicFiltersTitle") }}</strong>
        <p>{{ t("web.models.dynamicFiltersDescription") }}</p>
      </div>
      <div class="model-query-panel__actions">
        <el-button plain :disabled="!activeQueryDatasourceType" @click="actions.appendQueryGroup">{{ t("common.addFilter") }}</el-button>
        <el-button type="primary" @click="actions.searchModels">{{ t("common.search") }}</el-button>
        <el-button plain @click="actions.resetQueryFilters">{{ t("common.reset") }}</el-button>
      </div>
    </div>

    <div v-if="groups.length === 0" class="soft-panel empty-hint section-empty">
      {{ t("web.models.dynamicFiltersEmpty") }}
    </div>

    <div
      v-for="group in groups"
      :key="group.key"
      class="soft-panel model-query-group"
    >
      <div class="model-query-group__header">
        <div class="model-query-group__meta">
          <el-select
            v-model="group.metaSchemaCode"
            clearable
            :placeholder="t('web.models.filterMetaModelPlaceholder')"
            @change="actions.handleQuerySchemaChange(group)"
          >
            <el-option
              v-for="schema in querySchemaOptions"
              :key="schema.id ?? schema.schemaCode"
              :label="actions.querySchemaLabel(schema)"
              :value="schema.schemaCode"
            />
          </el-select>
        </div>
        <div v-if="actions.isMultipleQuerySchema(group)" class="model-query-group__meta model-query-group__meta--compact">
          <el-select v-model="group.rowMatchMode" :placeholder="t('web.models.filterRowMatchMode')">
            <el-option :label="t('web.models.filterRowMatchSameItem')" value="SAME_ITEM" />
            <el-option :label="t('web.models.filterRowMatchAnyItem')" value="ANY_ITEM" />
          </el-select>
        </div>
        <div class="model-query-group__actions">
          <el-button type="primary" plain @click="actions.appendQueryCondition(group)">{{ t("common.addCondition") }}</el-button>
          <el-button link type="danger" @click="actions.removeQueryGroup(group.key)">{{ t("common.remove") }}</el-button>
        </div>
      </div>

      <div v-if="group.conditions.length === 0" class="soft-panel empty-hint section-empty">
        {{ t("web.models.dynamicFiltersEmpty") }}
      </div>

      <div v-else class="model-query-group__conditions">
        <div
          v-for="(row, index) in group.conditions"
          :key="`${group.key}-${index}`"
          class="soft-panel model-query-condition"
        >
          <div class="model-query-condition__line">
            <div class="model-query-condition__segment">
              <el-select
                v-model="row.fieldKey"
                clearable
                :placeholder="t('web.models.filterFieldPlaceholder')"
                @change="actions.handleQueryFieldChange(group, row)"
              >
                <el-option
                  v-for="field in actions.querySchemaFields(group)"
                  :key="field.fieldKey"
                  :label="field.fieldName"
                  :value="field.fieldKey"
                />
              </el-select>
            </div>
            <div class="model-query-condition__segment model-query-condition__segment--operator">
              <el-select
                v-model="row.operator"
                clearable
                :placeholder="t('web.models.filterOperatorPlaceholder')"
              >
                <el-option
                  v-for="operator in actions.queryConditionOperators(group, row)"
                  :key="operator"
                  :label="operator"
                  :value="operator"
                />
              </el-select>
            </div>
            <div class="model-query-condition__segment model-query-condition__segment--value">
              <div class="query-condition-value">
                <el-input
                  v-if="row.operator === 'IN'"
                  v-model="row.multiValueText"
                  :placeholder="t('web.models.filterValuesPlaceholder')"
                />
                <template v-else-if="row.operator === 'BETWEEN'">
                  <component
                    :is="actions.isNumericQueryField(actions.queryConditionField(group, row)) ? ElInputNumber : ElInput"
                    :model-value="actions.queryConditionInputValue(row.value)"
                    class="query-condition-value__input"
                    :placeholder="t('web.models.filterValuePlaceholder')"
                    @update:model-value="actions.setQueryConditionValue(row, $event)"
                  />
                  <span class="query-condition-value__divider">-</span>
                  <component
                    :is="actions.isNumericQueryField(actions.queryConditionField(group, row)) ? ElInputNumber : ElInput"
                    :model-value="actions.queryConditionInputValue(row.valueTo)"
                    class="query-condition-value__input"
                    :placeholder="t('web.models.filterValueToPlaceholder')"
                    @update:model-value="actions.setQueryConditionValueTo(row, $event)"
                  />
                </template>
                <el-select
                  v-else-if="actions.queryConditionField(group, row)?.valueType === 'BOOLEAN'"
                  :model-value="actions.queryConditionBooleanValue(row.value)"
                  clearable
                  :placeholder="t('web.models.filterValuePlaceholder')"
                  @update:model-value="actions.setQueryConditionValue(row, $event)"
                >
                  <el-option :label="t('common.yes')" :value="true" />
                  <el-option :label="t('common.no')" :value="false" />
                </el-select>
                <component
                  :is="actions.isNumericQueryField(actions.queryConditionField(group, row)) ? ElInputNumber : ElInput"
                  v-else
                  :model-value="actions.queryConditionInputValue(row.value)"
                  class="query-condition-value__input"
                  :placeholder="t('web.models.filterValuePlaceholder')"
                  @update:model-value="actions.setQueryConditionValue(row, $event)"
                />
              </div>
            </div>
            <el-button link type="danger" class="model-query-condition__remove" @click="actions.removeQueryCondition(group, index)">
              {{ t("common.remove") }}
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ElInput, ElInputNumber } from "element-plus";
import { useI18n } from "vue-i18n";
import type { MetadataSchemaDefinition } from "@studio/api-sdk";
import type { ModelDynamicFilterActions, ModelQueryGroupState } from "./modelViewTypes";

defineProps<{
  groups: ModelQueryGroupState[];
  activeQueryDatasourceType?: string;
  querySchemaOptions: MetadataSchemaDefinition[];
  actions: ModelDynamicFilterActions;
}>();

const { t } = useI18n();
</script>

<style scoped>
p {
  margin: 0;
  color: var(--studio-text-soft);
}

.model-query-panel {
  display: grid;
  gap: 10px;
  margin-bottom: 12px;
}

.model-query-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.model-query-panel__header p {
  display: none;
}

.model-query-panel__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.model-query-group {
  display: grid;
  gap: 12px;
}

.model-query-group__header {
  display: grid;
  grid-template-columns: minmax(280px, 1.4fr) minmax(220px, 0.9fr) auto;
  align-items: end;
  gap: 10px;
}

.model-query-group__meta {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.model-query-group__meta--compact {
  max-width: 320px;
}

.model-query-group__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.model-query-group__conditions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(560px, 1fr));
  gap: 10px;
}

.model-query-condition {
  min-width: 0;
}

.model-query-condition__line {
  display: grid;
  grid-template-columns: minmax(180px, 1.2fr) minmax(130px, 0.8fr) minmax(220px, 1.5fr) auto;
  align-items: end;
  gap: 10px;
}

.model-query-condition__segment {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.model-query-condition__segment--operator {
  max-width: 180px;
}

.model-query-condition__segment--value {
  min-width: 0;
}

.model-query-condition__remove {
  align-self: end;
  white-space: nowrap;
}

.query-condition-value {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.query-condition-value__input {
  flex: 1 1 0;
  min-width: 0;
}

.query-condition-value__divider {
  flex: 0 0 auto;
  color: var(--studio-text-soft);
}

.model-query-condition :deep(.el-select),
.model-query-condition :deep(.el-input),
.model-query-condition :deep(.el-input-number) {
  width: 100%;
}

.section-empty {
  margin-bottom: 0;
}

@media (max-width: 980px) {
  .model-query-panel__header {
    flex-direction: column;
  }

  .model-query-group__header {
    grid-template-columns: minmax(0, 1fr);
  }

  .model-query-group__actions {
    justify-content: flex-start;
  }

  .model-query-group__conditions {
    grid-template-columns: minmax(0, 1fr);
  }

  .model-query-condition__line {
    grid-template-columns: minmax(0, 1fr);
  }

  .model-query-condition__segment--operator {
    max-width: none;
  }
}
</style>
