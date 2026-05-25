<template>
  <SectionCard :title="t('web.statistics.setupTitle')" :show-description="false">
    <template #actions>
      <el-button plain :loading="workspaceLoading" @click="setupActions.loadWorkspace">{{ t("common.refresh") }}</el-button>
      <el-button plain @click="setupActions.toggleSetupPanel">
        {{ setupExpanded ? t("web.statistics.hideSetup") : t("web.statistics.editSetup") }}
      </el-button>
      <el-button type="primary" :disabled="!canRunAnalysis" :loading="runningAnalysis" @click="setupActions.runAnalysis">
        {{ t("web.statistics.run") }}
      </el-button>
    </template>

    <div class="statistics-setup-summary">
      <StatusPill
        v-for="item in setupSummaryItems"
        :key="item.key"
        :label="item.label"
        :tone="item.tone"
      />
    </div>

    <div v-if="scopeHintText" class="soft-panel statistics-setup-hint">
      {{ scopeHintText }}
    </div>
    <div class="statistics-field-hint">
      <span>{{ fieldSummaryText }}</span>
    </div>
    <div v-if="numericAutoBucketText" class="soft-panel statistics-bucket-note">
      {{ numericAutoBucketText }}
    </div>

    <div v-show="setupExpanded" class="statistics-setup-panel">
      <div class="studio-form-grid">
        <el-form-item>
          <el-select
            v-model="selectedTargetScopeModel"
            :placeholder="t('web.statistics.targetScopeLabel')"
            @change="setupActions.handleTargetScopeChange"
          >
            <el-option :label="t('web.statistics.targetScopeBusiness')" value="BUSINESS" />
            <el-option :label="t('web.statistics.targetScopeTechnical')" value="TECHNICAL" />
          </el-select>
        </el-form-item>

        <el-form-item>
          <el-select
            v-model="selectedDatasourceTypeModel"
            clearable
            :placeholder="t('web.models.datasourceTypePlaceholder')"
            @change="setupActions.handleDatasourceTypeChange"
          >
            <el-option v-for="typeCode in datasourceTypes" :key="typeCode" :label="typeCode" :value="typeCode" />
          </el-select>
        </el-form-item>

        <el-form-item>
          <el-select
            v-model="selectedDatasourceIdModel"
            clearable
            filterable
            :placeholder="t('web.models.datasourcePlaceholder')"
            @change="setupActions.handleDatasourceChange"
          >
            <el-option
              v-for="item in filteredDatasourceOptions"
              :key="String(item.id)"
              :label="`${item.name} (${item.typeCode})`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item>
          <el-select
            v-model="targetMetaSchemaCodeModel"
            clearable
            filterable
            :disabled="targetSchemaOptions.length === 0"
            :placeholder="t('web.statistics.targetMetaModelPlaceholder')"
          >
            <el-option
              v-for="schema in targetSchemaOptions"
              :key="schema.schemaCode"
              :label="setupActions.schemaLabel(schema)"
              :value="schema.schemaCode"
            />
          </el-select>
        </el-form-item>

        <el-form-item>
          <el-select
            v-model="targetFieldKeyModel"
            clearable
            :disabled="targetFieldOptions.length === 0"
            :placeholder="t('web.statistics.targetFieldPlaceholder')"
          >
            <el-option
              v-for="field in targetFieldOptions"
              :key="field.fieldKey"
              :label="field.fieldName"
              :value="field.fieldKey"
            />
          </el-select>
        </el-form-item>
      </div>

      <div class="statistics-setup-divider">
        <div>
          <h4>{{ t("web.models.dynamicFiltersTitle") }}</h4>
          <p>{{ t("web.models.dynamicFiltersDescription") }}</p>
        </div>
        <div class="statistics-setup-divider__actions">
          <el-button plain :disabled="querySchemaOptions.length === 0" @click="setupActions.appendQueryGroup">{{ t("common.addFilter") }}</el-button>
          <el-button plain @click="setupActions.resetQueryFilters">{{ t("common.reset") }}</el-button>
        </div>
      </div>

      <div v-if="queryGroups.length === 0" class="soft-panel statistics-empty statistics-empty--compact">
        {{ t("web.models.dynamicFiltersEmpty") }}
      </div>

      <div v-for="group in queryGroups" :key="group.key" class="soft-panel model-query-group">
        <div class="studio-form-grid">
          <el-form-item>
            <el-select
              v-model="group.metaSchemaCode"
              clearable
              filterable
              :placeholder="t('web.models.filterMetaModelPlaceholder')"
              @change="setupActions.handleQuerySchemaChange(group)"
            >
              <el-option
                v-for="schema in querySchemaOptions"
                :key="schema.schemaCode"
                :label="setupActions.schemaLabel(schema)"
                :value="schema.schemaCode"
              />
            </el-select>
          </el-form-item>

          <el-form-item v-if="setupActions.isMultipleQuerySchema(group)">
            <el-select v-model="group.rowMatchMode" :placeholder="t('web.models.filterRowMatchMode')">
              <el-option :label="t('web.models.filterRowMatchSameItem')" value="SAME_ITEM" />
              <el-option :label="t('web.models.filterRowMatchAnyItem')" value="ANY_ITEM" />
            </el-select>
          </el-form-item>
        </div>

        <div class="multiple-section-actions">
          <el-button type="primary" plain @click="setupActions.appendQueryCondition(group)">{{ t("common.addCondition") }}</el-button>
          <el-button link type="danger" @click="setupActions.removeQueryGroup(group.key)">{{ t("common.remove") }}</el-button>
        </div>

        <StudioTableShell min-width="760px">
          <el-table :data="group.conditions" border>
            <el-table-column :label="t('web.models.filterField')" min-width="160">
              <template #default="{ row }">
                <el-select
                  v-model="row.fieldKey"
                  clearable
                  :placeholder="t('web.models.filterFieldPlaceholder')"
                  @change="setupActions.handleQueryFieldChange(group, row)"
                >
                  <el-option
                    v-for="field in setupActions.querySchemaFields(group)"
                    :key="field.fieldKey"
                    :label="field.fieldName"
                    :value="field.fieldKey"
                  />
                </el-select>
              </template>
            </el-table-column>

            <el-table-column :label="t('web.models.filterOperator')" width="140">
              <template #default="{ row }">
                <el-select v-model="row.operator" clearable :placeholder="t('web.models.filterOperatorPlaceholder')">
                  <el-option
                    v-for="operator in setupActions.queryConditionOperators(group, row)"
                    :key="operator"
                    :label="operator"
                    :value="operator"
                  />
                </el-select>
              </template>
            </el-table-column>

            <el-table-column :label="t('web.models.filterValue')" min-width="260">
              <template #default="{ row }">
                <div class="query-condition-value">
                  <el-input
                    v-if="row.operator === 'IN'"
                    v-model="row.multiValueText"
                    :placeholder="t('web.models.filterValuesPlaceholder')"
                  />
                  <template v-else-if="row.operator === 'BETWEEN'">
                    <component
                      :is="setupActions.isNumericField(setupActions.queryConditionField(group, row)) ? ElInputNumber : ElInput"
                      v-model="row.value"
                      class="query-condition-value__input"
                      :placeholder="t('web.models.filterValuePlaceholder')"
                    />
                    <span class="query-condition-value__divider">-</span>
                    <component
                      :is="setupActions.isNumericField(setupActions.queryConditionField(group, row)) ? ElInputNumber : ElInput"
                      v-model="row.valueTo"
                      class="query-condition-value__input"
                      :placeholder="t('web.models.filterValueToPlaceholder')"
                    />
                  </template>
                  <el-select
                    v-else-if="setupActions.queryConditionField(group, row)?.valueType === 'BOOLEAN'"
                    v-model="row.value"
                    clearable
                    :placeholder="t('web.models.filterValuePlaceholder')"
                  >
                    <el-option :label="t('common.yes')" :value="true" />
                    <el-option :label="t('common.no')" :value="false" />
                  </el-select>
                  <component
                    :is="setupActions.isNumericField(setupActions.queryConditionField(group, row)) ? ElInputNumber : ElInput"
                    v-else
                    v-model="row.value"
                    class="query-condition-value__input"
                    :placeholder="t('web.models.filterValuePlaceholder')"
                  />
                </div>
              </template>
            </el-table-column>

            <el-table-column :label="t('web.metadata.actions')" width="100" fixed="right">
              <template #default="{ $index }">
                <el-button link type="danger" @click="setupActions.removeQueryCondition(group, $index)">{{ t("common.remove") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </StudioTableShell>
      </div>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { ElInput, ElInputNumber } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  DataModelStatisticsFieldOptionView,
  DataModelStatisticsSchemaOptionView,
  DataSourceDefinition,
  EntityId,
} from "@studio/api-sdk";
import { SectionCard, StatusPill, StudioTableShell } from "@studio/ui";

type TargetScope = "BUSINESS" | "TECHNICAL";

interface QueryConditionState {
  fieldKey: string;
  operator: string;
  value?: unknown;
  valueTo?: unknown;
  multiValueText: string;
}

interface QueryGroupState {
  key: string;
  metaSchemaCode: string;
  rowMatchMode: "SAME_ITEM" | "ANY_ITEM";
  conditions: QueryConditionState[];
}

interface SummaryItem {
  key: string;
  label: string;
  tone: "primary" | "success" | "warning" | "danger" | "neutral";
}

interface SetupActions {
  loadWorkspace: () => void | Promise<void>;
  toggleSetupPanel: () => void;
  runAnalysis: () => void | Promise<void>;
  handleTargetScopeChange: () => void | Promise<void>;
  handleDatasourceTypeChange: () => void | Promise<void>;
  handleDatasourceChange: () => void | Promise<void>;
  schemaLabel: (schema: DataModelStatisticsSchemaOptionView) => string;
  appendQueryGroup: () => void;
  resetQueryFilters: () => void;
  isMultipleQuerySchema: (group: QueryGroupState) => boolean;
  handleQuerySchemaChange: (group: QueryGroupState) => void;
  appendQueryCondition: (group: QueryGroupState) => void;
  removeQueryGroup: (groupKey: string) => void;
  querySchemaFields: (group: QueryGroupState) => DataModelStatisticsFieldOptionView[];
  handleQueryFieldChange: (group: QueryGroupState, condition: QueryConditionState) => void;
  queryConditionOperators: (group: QueryGroupState, condition: QueryConditionState) => string[];
  queryConditionField: (group: QueryGroupState, condition: QueryConditionState) => DataModelStatisticsFieldOptionView | undefined;
  isNumericField: (field?: Pick<DataModelStatisticsFieldOptionView, "valueType">) => boolean;
  removeQueryCondition: (group: QueryGroupState, index: number) => void;
}

const props = defineProps<{
  workspaceLoading: boolean;
  setupExpanded: boolean;
  canRunAnalysis: boolean;
  runningAnalysis: boolean;
  setupSummaryItems: SummaryItem[];
  scopeHintText: string;
  fieldSummaryText: string;
  numericAutoBucketText: string;
  selectedTargetScope: TargetScope;
  datasourceTypes: string[];
  selectedDatasourceType: string;
  filteredDatasourceOptions: DataSourceDefinition[];
  selectedDatasourceId?: EntityId;
  targetMetaSchemaCode: string;
  targetSchemaOptions: DataModelStatisticsSchemaOptionView[];
  targetFieldKey: string;
  targetFieldOptions: DataModelStatisticsFieldOptionView[];
  querySchemaOptions: DataModelStatisticsSchemaOptionView[];
  queryGroups: QueryGroupState[];
  setupActions: SetupActions;
}>();

const emit = defineEmits<{
  "update:selectedTargetScope": [value: TargetScope];
  "update:selectedDatasourceType": [value: string];
  "update:selectedDatasourceId": [value: EntityId | undefined];
  "update:targetMetaSchemaCode": [value: string];
  "update:targetFieldKey": [value: string];
}>();

const { t } = useI18n();

const selectedTargetScopeModel = computed({
  get: () => props.selectedTargetScope,
  set: (value: TargetScope) => emit("update:selectedTargetScope", value),
});

const selectedDatasourceTypeModel = computed({
  get: () => props.selectedDatasourceType,
  set: (value: string) => emit("update:selectedDatasourceType", value),
});

const selectedDatasourceIdModel = computed({
  get: () => props.selectedDatasourceId,
  set: (value: EntityId | undefined) => emit("update:selectedDatasourceId", value),
});

const targetMetaSchemaCodeModel = computed({
  get: () => props.targetMetaSchemaCode,
  set: (value: string) => emit("update:targetMetaSchemaCode", value),
});

const targetFieldKeyModel = computed({
  get: () => props.targetFieldKey,
  set: (value: string) => emit("update:targetFieldKey", value),
});
</script>

<style scoped>
.statistics-setup-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.statistics-setup-hint {
  margin-bottom: 10px;
  color: var(--studio-warning);
}

.statistics-setup-panel {
  display: grid;
  gap: 14px;
  margin-top: 14px;
}

.statistics-setup-divider {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-top: 4px;
}

.statistics-setup-divider h4 {
  margin: 0 0 4px;
  color: var(--studio-text);
}

.statistics-setup-divider p {
  margin: 0;
  color: var(--studio-text-soft);
}

.statistics-setup-divider__actions {
  display: flex;
  gap: 8px;
}

.statistics-field-hint {
  color: var(--studio-text-soft);
  font-size: 13px;
}

.statistics-bucket-note {
  margin-top: 10px;
  color: var(--studio-primary);
}

.statistics-empty {
  min-height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--studio-text-soft);
}

.statistics-empty--compact {
  min-height: 72px;
}

.query-condition-value {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.query-condition-value__input {
  flex: 1;
  min-width: 0;
}

.query-condition-value__divider {
  color: var(--studio-text-soft);
}

@media (max-width: 960px) {
  .statistics-setup-divider {
    flex-direction: column;
  }
}
</style>
