<template>
  <div class="studio-page statistics-center">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.statistics.heading") }}</h3>
        <p>{{ t("web.statistics.description") }}</p>
        <div class="statistics-context">
          <StatusPill :label="`${t('common.tenant')}: ${authStore.currentTenantName || '-'}`" tone="primary" />
          <StatusPill :label="`${t('common.project')}: ${authStore.currentProjectName || '-'}`" tone="success" />
          <StatusPill :label="`${t('web.statistics.datasourceTypeLabel')}: ${activeDatasourceType || '-'}`" tone="neutral" />
        </div>
      </div>
    </div>

    <SectionCard :title="t('web.statistics.setupTitle')" :show-description="false">
      <template #actions>
        <el-button plain :loading="workspaceLoading" @click="loadWorkspace">{{ t("common.refresh") }}</el-button>
        <el-button plain @click="toggleSetupPanel">
          {{ setupExpanded ? t("web.statistics.hideSetup") : t("web.statistics.editSetup") }}
        </el-button>
        <el-button type="primary" :disabled="!canRunAnalysis" :loading="runningAnalysis" @click="runAnalysis">
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
          <el-form-item :label="t('web.statistics.targetScopeLabel')">
            <el-select v-model="selectedTargetScope" @change="handleTargetScopeChange">
              <el-option :label="t('web.statistics.targetScopeBusiness')" value="BUSINESS" />
              <el-option :label="t('web.statistics.targetScopeTechnical')" value="TECHNICAL" />
            </el-select>
          </el-form-item>

          <el-form-item :label="t('web.statistics.datasourceTypeLabel')">
            <el-select
              v-model="selectedDatasourceType"
              clearable
              :placeholder="t('web.models.datasourceTypePlaceholder')"
              @change="handleDatasourceTypeChange"
            >
              <el-option v-for="typeCode in datasourceTypes" :key="typeCode" :label="typeCode" :value="typeCode" />
            </el-select>
          </el-form-item>

          <el-form-item :label="t('web.models.datasourceLabel')">
            <el-select
              v-model="selectedDatasourceId"
              clearable
              filterable
              :placeholder="t('web.models.datasourcePlaceholder')"
              @change="handleDatasourceChange"
            >
              <el-option
                v-for="item in filteredDatasourceOptions"
                :key="String(item.id)"
                :label="`${item.name} (${item.typeCode})`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>

          <el-form-item :label="t('web.statistics.targetMetaModelLabel')">
            <el-select
              v-model="targetMetaSchemaCode"
              clearable
              filterable
              :disabled="targetSchemaOptions.length === 0"
              :placeholder="t('web.statistics.targetMetaModelPlaceholder')"
            >
              <el-option
                v-for="schema in targetSchemaOptions"
                :key="schema.schemaCode"
                :label="schemaLabel(schema)"
                :value="schema.schemaCode"
              />
            </el-select>
          </el-form-item>

          <el-form-item :label="t('web.statistics.targetFieldLabel')">
            <el-select
              v-model="targetFieldKey"
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
            <el-button plain :disabled="querySchemaOptions.length === 0" @click="appendQueryGroup">{{ t("common.addFilter") }}</el-button>
            <el-button plain @click="resetQueryFilters">{{ t("common.reset") }}</el-button>
          </div>
        </div>

        <div v-if="queryGroups.length === 0" class="soft-panel statistics-empty statistics-empty--compact">
          {{ t("web.models.dynamicFiltersEmpty") }}
        </div>

        <div v-for="group in queryGroups" :key="group.key" class="soft-panel model-query-group">
          <div class="studio-form-grid">
            <el-form-item :label="t('web.models.filterMetaModel')">
              <el-select
                v-model="group.metaSchemaCode"
                clearable
                filterable
                :placeholder="t('web.models.filterMetaModelPlaceholder')"
                @change="handleQuerySchemaChange(group)"
              >
                <el-option
                  v-for="schema in querySchemaOptions"
                  :key="schema.schemaCode"
                  :label="schemaLabel(schema)"
                  :value="schema.schemaCode"
                />
              </el-select>
            </el-form-item>

            <el-form-item v-if="isMultipleQuerySchema(group)" :label="t('web.models.filterRowMatchMode')">
              <el-select v-model="group.rowMatchMode">
                <el-option :label="t('web.models.filterRowMatchSameItem')" value="SAME_ITEM" />
                <el-option :label="t('web.models.filterRowMatchAnyItem')" value="ANY_ITEM" />
              </el-select>
            </el-form-item>
          </div>

          <div class="multiple-section-actions">
            <el-button type="primary" plain @click="appendQueryCondition(group)">{{ t("common.addCondition") }}</el-button>
            <el-button link type="danger" @click="removeQueryGroup(group.key)">{{ t("common.remove") }}</el-button>
          </div>

          <el-table :data="group.conditions" border>
            <el-table-column :label="t('web.models.filterField')" min-width="160">
              <template #default="{ row }">
                <el-select
                  v-model="row.fieldKey"
                  clearable
                  :placeholder="t('web.models.filterFieldPlaceholder')"
                  @change="handleQueryFieldChange(group, row)"
                >
                  <el-option
                    v-for="field in querySchemaFields(group)"
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
                    v-for="operator in queryConditionOperators(group, row)"
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
                      :is="isNumericField(queryConditionField(group, row)) ? ElInputNumber : ElInput"
                      v-model="row.value"
                      class="query-condition-value__input"
                      :placeholder="t('web.models.filterValuePlaceholder')"
                    />
                    <span class="query-condition-value__divider">-</span>
                    <component
                      :is="isNumericField(queryConditionField(group, row)) ? ElInputNumber : ElInput"
                      v-model="row.valueTo"
                      class="query-condition-value__input"
                      :placeholder="t('web.models.filterValueToPlaceholder')"
                    />
                  </template>
                  <el-select
                    v-else-if="queryConditionField(group, row)?.valueType === 'BOOLEAN'"
                    v-model="row.value"
                    clearable
                    :placeholder="t('web.models.filterValuePlaceholder')"
                  >
                    <el-option :label="t('common.yes')" :value="true" />
                    <el-option :label="t('common.no')" :value="false" />
                  </el-select>
                  <component
                    :is="isNumericField(queryConditionField(group, row)) ? ElInputNumber : ElInput"
                    v-else
                    v-model="row.value"
                    class="query-condition-value__input"
                    :placeholder="t('web.models.filterValuePlaceholder')"
                  />
                </div>
              </template>
            </el-table-column>

            <el-table-column :label="t('web.metadata.actions')" width="100">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeQueryCondition(group, $index)">{{ t("common.remove") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </SectionCard>

    <SectionCard :title="t('web.statistics.overviewTitle')" :description="t('web.statistics.overviewDescription')" :show-description="true">
      <div v-if="metricCards.length === 0" class="soft-panel statistics-empty">{{ t("web.statistics.chartEmpty") }}</div>
      <div v-else class="statistics-metrics">
        <MetricCard
          v-for="metric in metricCards"
          :key="metric.key"
          :label="metric.label"
          :value="metric.value"
          :tone="metric.tone"
        />
      </div>
    </SectionCard>

    <div class="statistics-chart-grid" :class="chartGridClass">
      <SectionCard
        class="statistics-chart-card statistics-chart-card--trend"
        :title="t('web.statistics.chartTrendTitle')"
        :description="t('web.statistics.chartTrendDescription')"
        :show-description="true"
      >
        <template #actions>
          <div class="statistics-card-control">
            <span>{{ t("web.statistics.daysLabel") }}</span>
            <el-select v-model="trendDays" class="statistics-card-control__select">
              <el-option :label="'7'" :value="7" />
              <el-option :label="'14'" :value="14" />
              <el-option :label="'30'" :value="30" />
              <el-option :label="'90'" :value="90" />
            </el-select>
          </div>
        </template>
        <template v-if="canRenderChart(chartResults.TREND)">
          <EChartPanel :option="trendOption" :height="trendChartHeight" />
        </template>
        <div v-else class="soft-panel statistics-empty">{{ chartMessage(chartResults.TREND) }}</div>
      </SectionCard>

      <SectionCard
        class="statistics-chart-card statistics-chart-card--bar"
        :title="t('web.statistics.chartBarTitle')"
        :description="t('web.statistics.chartBarDescription')"
        :show-description="true"
      >
        <template v-if="canRenderChart(chartResults.BAR)">
          <EChartPanel :option="barOption" :height="barChartHeight" />
        </template>
        <div v-else class="soft-panel statistics-empty">{{ chartMessage(chartResults.BAR) }}</div>
      </SectionCard>

      <SectionCard
        class="statistics-chart-card statistics-chart-card--pie"
        :title="t('web.statistics.chartPieTitle')"
        :description="t('web.statistics.chartPieDescription')"
        :show-description="true"
      >
        <template v-if="canRenderChart(chartResults.PIE)">
          <EChartPanel :option="pieOption" :height="pieChartHeight" />
        </template>
        <div v-else class="soft-panel statistics-empty">{{ chartMessage(chartResults.PIE) }}</div>
      </SectionCard>

      <SectionCard
        class="statistics-chart-card statistics-chart-card--topn"
        :title="t('web.statistics.chartTopNTitle')"
        :description="t('web.statistics.chartTopNDescription')"
        :show-description="true"
      >
        <template #actions>
          <div class="statistics-card-control">
            <span>{{ t("web.models.statisticsTopNLabel") }}</span>
            <el-input-number v-model="topN" :min="1" :max="100" controls-position="right" class="statistics-card-control__number" />
          </div>
        </template>
        <div v-if="chartResults.TOPN?.disabledReason" class="soft-panel statistics-empty">{{ chartResults.TOPN.disabledReason }}</div>
        <div v-else-if="topNDisplayRows.length === 0" class="soft-panel statistics-empty">{{ t("web.statistics.chartEmpty") }}</div>
        <div v-else class="ranking-board">
          <div class="ranking-board__header">
            <span>{{ t("common.sequence") }}</span>
            <div class="ranking-board__content-head ranking-board__content-head--header">
              <span>{{ targetField?.fieldName || t("web.statistics.targetFieldLabel") }}</span>
              <span>{{ t("web.statistics.rankingCount") }}</span>
              <span>{{ t("web.statistics.rankingRatio") }}</span>
            </div>
          </div>
          <article v-for="row in topNDisplayRows" :key="row.key || row.displayLabel" class="ranking-board__row">
            <div class="ranking-board__rank">#{{ row.rank }}</div>
            <div class="ranking-board__content">
              <div class="ranking-board__content-head">
                <strong class="ranking-board__label">{{ row.displayLabel }}</strong>
                <span class="ranking-board__count">{{ formatInteger(row.countValue) }}</span>
                <span class="ranking-board__ratio">{{ formatPercent(row.ratioValue) }}</span>
              </div>
              <div class="ranking-board__track">
                <div class="ranking-board__fill" :style="{ width: `${row.barPercent}%` }" />
              </div>
            </div>
          </article>
        </div>
      </SectionCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElInput, ElInputNumber, ElMessage } from "element-plus";
import type { EChartsOption } from "echarts";
import { useI18n } from "vue-i18n";
import { useRoute } from "vue-router";
import type {
  DataModelQueryCondition,
  DataModelQueryGroup,
  DataModelStatisticsChartRequest,
  DataModelStatisticsChartView,
  DataModelStatisticsFieldOptionView,
  DataModelStatisticsOptionsView,
  DataModelStatisticsRequest,
  DataModelStatisticsSchemaOptionView,
  DataModelStatisticsView,
  DataSourceDefinition,
  EntityId,
  StatisticsChartType,
} from "@studio/api-sdk";
import { MetricCard, SectionCard, StatusPill } from "@studio/ui";
import EChartPanel from "@/components/EChartPanel.vue";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";

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

interface MetricCardItem {
  key: string;
  label: string;
  value: string | number;
  tone: "primary" | "accent" | "success" | "warning";
}

const route = useRoute();
const { locale, t } = useI18n();
const authStore = useAuthStore();

const chartTypes: StatisticsChartType[] = ["TREND", "BAR", "PIE", "TOPN"];

const datasources = ref<DataSourceDefinition[]>([]);
const workspaceLoading = ref(false);
const runningAnalysis = ref(false);
const setupExpanded = ref(true);
const selectedDatasourceType = ref("");
const selectedDatasourceId = ref<EntityId>();
const selectedTargetScope = ref<"BUSINESS" | "TECHNICAL">("BUSINESS");
const targetMetaSchemaCode = ref("");
const targetFieldKey = ref("");
const trendDays = ref(30);
const topN = ref(10);
const workspaceOptions = ref<DataModelStatisticsOptionsView>({
  datasourceType: undefined,
  querySchemas: [],
  targetSchemas: [],
});
const rawStatistics = ref<DataModelStatisticsView>();
const queryGroups = ref<QueryGroupState[]>([]);
const chartResults = reactive<Record<StatisticsChartType, DataModelStatisticsChartView | undefined>>({
  TREND: undefined,
  BAR: undefined,
  PIE: undefined,
  TOPN: undefined,
});

const datasourceTypes = computed(() =>
  Array.from(new Set(datasources.value.map((item) => item.typeCode).filter(Boolean))).sort((left, right) => left.localeCompare(right)),
);

const filteredDatasourceOptions = computed(() => {
  if (!selectedDatasourceType.value) {
    return datasources.value;
  }
  const expectedType = normalizeTypeCode(selectedDatasourceType.value);
  return datasources.value.filter((item) => normalizeTypeCode(item.typeCode) === expectedType);
});

const selectedDatasource = computed(() => findDatasourceById(selectedDatasourceId.value));
const activeDatasourceType = computed(() => selectedDatasource.value?.typeCode ?? selectedDatasourceType.value);
const requiresDatasourceType = computed(() => selectedTargetScope.value === "TECHNICAL");

const querySchemaOptions = computed(() => {
  return workspaceOptions.value.querySchemas ?? [];
});

const targetSchemaOptions = computed(() => {
  return (workspaceOptions.value.targetSchemas ?? []).filter((schema) => (schema.scope ?? "BUSINESS") === selectedTargetScope.value);
});

const targetSchema = computed(() => findTargetSchema(targetMetaSchemaCode.value));
const targetFieldOptions = computed(() => targetSchema.value?.fields ?? []);
const targetField = computed(() => targetFieldOptions.value.find((field) => field.fieldKey === targetFieldKey.value));
const showNumericAutoBucket = computed(() => isNumericField(targetField.value));
const canRunAnalysis = computed(() =>
  Boolean(targetSchema.value && targetField.value)
  && (!requiresDatasourceType.value || Boolean(activeDatasourceType.value))
  && !workspaceLoading.value,
);
const scopeLabel = computed(() => (
  selectedTargetScope.value === "TECHNICAL"
    ? t("web.statistics.targetScopeTechnical")
    : t("web.statistics.targetScopeBusiness")
));
const datasourceTypeSummary = computed(() => {
  if (activeDatasourceType.value) {
    return activeDatasourceType.value;
  }
  return requiresDatasourceType.value ? t("web.statistics.summaryNeedDatasourceType") : t("web.statistics.summaryAllBusiness");
});
const datasourceSummary = computed(() => selectedDatasource.value?.name || t("web.statistics.summaryAllDatasources"));
const setupSummaryItems = computed(() => [
  {
    key: "scope",
    label: `${t("web.statistics.targetScopeLabel")}: ${scopeLabel.value}`,
    tone: "primary" as const,
  },
  {
    key: "datasourceType",
    label: `${t("web.statistics.datasourceTypeLabel")}: ${datasourceTypeSummary.value}`,
    tone: "neutral" as const,
  },
  {
    key: "datasource",
    label: `${t("web.models.datasourceLabel")}: ${datasourceSummary.value}`,
    tone: "success" as const,
  },
  {
    key: "schema",
    label: `${t("web.statistics.targetMetaModelLabel")}: ${targetSchema.value ? schemaLabel(targetSchema.value) : "-"}`,
    tone: "neutral" as const,
  },
  {
    key: "field",
    label: `${t("web.statistics.targetFieldLabel")}: ${targetField.value?.fieldName || "-"}`,
    tone: "neutral" as const,
  },
]);
const scopeHintText = computed(() => {
  if (requiresDatasourceType.value && !activeDatasourceType.value) {
    return t("web.statistics.datasourceTypeRequiredHint");
  }
  return "";
});

const fieldSummaryText = computed(() => {
  if (requiresDatasourceType.value && !activeDatasourceType.value) {
    return t("web.statistics.datasourceTypeRequiredHint");
  }
  if (!targetField.value && !requiresDatasourceType.value && !activeDatasourceType.value) {
    return t("web.statistics.businessFieldHint");
  }
  if (!targetField.value) {
    return t("web.statistics.noTarget");
  }
  const supported = (targetField.value.supportedChartTypes ?? []).map((item) => String(item)).join(", ");
  if (!supported) {
    return t("web.statistics.chartDisabled");
  }
  if (showNumericAutoBucket.value) {
    return locale.value.startsWith("zh")
      ? "这是数值字段，系统会自动完成区间分组；趋势图是否可用取决于元模型是单值还是多值。"
      : "This is a numeric field. The system groups ranges automatically, and trend availability depends on whether the target meta model is SINGLE or MULTIPLE.";
  }
  return locale.value.startsWith("zh")
    ? `字段类型 ${targetField.value.valueType || "-"}，支持图版：${supported}`
    : `Field type ${targetField.value.valueType || "-"} with chart support: ${supported}`;
});

const numericAutoBucketText = computed(() => {
  if (!showNumericAutoBucket.value) {
    return "";
  }
  const summary = chartResults.BAR?.summaryMetrics ?? {};
  const lowerBound = summary.effectiveLowerBound;
  const upperBound = summary.effectiveUpperBound;
  const step = summary.effectiveStep;
  const bucketCount = toNumber(summary.effectiveBucketCount);
  if (lowerBound == null || upperBound == null || step == null || bucketCount <= 0) {
    return t("web.statistics.autoBucketPendingHint");
  }
  const sameBucket = String(lowerBound) === String(upperBound) || bucketCount === 1;
  if (sameBucket) {
    return t("web.statistics.autoBucketSingleHint", {
      value: formatMetricValue(lowerBound),
    });
  }
  return t("web.statistics.autoBucketResolvedHint", {
    lower: formatMetricValue(lowerBound),
    upper: formatMetricValue(upperBound),
    step: formatMetricValue(step),
    count: formatInteger(bucketCount),
  });
});

const metricCards = computed(() => {
  if (!rawStatistics.value) {
    return [] as MetricCardItem[];
  }
  const summary = rawStatistics.value.summaryMetrics ?? {};
  return [
    createMetricCard("matchedModelCount", rawStatistics.value.matchedModelCount, "primary"),
    createMetricCard("matchedItemCount", rawStatistics.value.matchedItemCount, "accent"),
    createMetricCard("count", summary.count, "success"),
    createMetricCard("distinctCount", summary.distinctCount, "warning"),
    createMetricCard("min", summary.min, "primary"),
    createMetricCard("max", summary.max, "accent"),
    createMetricCard("sum", summary.sum, "success"),
    createMetricCard("avg", summary.avg, "warning"),
  ].filter((item): item is MetricCardItem => Boolean(item));
});

const topNRows = computed(() => {
  const rows = chartResults.TOPN?.tableRows ?? [];
  return [...rows].sort((left, right) => Number(left.rank ?? 0) - Number(right.rank ?? 0));
});

const topNDisplayRows = computed(() => {
  const rows = topNRows.value;
  const maxCount = rows.reduce((result, row) => Math.max(result, toNumber(row.count)), 0);
  return rows.map((row) => {
    const countValue = toNumber(row.count);
    return {
      ...row,
      displayLabel: row.label || row.category || "-",
      countValue,
      ratioValue: toNumber(row.ratio),
      barPercent: maxCount > 0 ? Math.max(12, Math.round((countValue / maxCount) * 100)) : 0,
    };
  });
});

const pieCategoryCount = computed(() => {
  const chart = chartResults.PIE;
  if (!chart) {
    return 0;
  }
  if (Array.isArray(chart.xAxis) && chart.xAxis.length > 0) {
    return chart.xAxis.length;
  }
  return chart.tableRows?.length ?? 0;
});

const chartGridMode = computed<"default" | "wide" | "stacked">(() => {
  if (pieCategoryCount.value >= 12) {
    return "stacked";
  }
  if (pieCategoryCount.value >= 8) {
    return "wide";
  }
  return "default";
});

const chartGridClass = computed(() => `statistics-chart-grid--${chartGridMode.value}`);

const trendChartHeight = computed(() => {
  if (chartGridMode.value === "stacked") {
    return "380px";
  }
  if (chartGridMode.value === "wide") {
    return "350px";
  }
  return "320px";
});

const barChartHeight = computed(() => {
  if (chartGridMode.value === "stacked") {
    return "380px";
  }
  if (chartGridMode.value === "wide") {
    return "350px";
  }
  return "320px";
});

const pieChartHeight = computed(() => {
  if (!canRenderChart(chartResults.PIE)) {
    return chartGridMode.value === "stacked" ? "520px" : "360px";
  }
  const baseHeight = chartGridMode.value === "stacked"
    ? 460
    : chartGridMode.value === "wide"
      ? 400
      : 340;
  const extraHeight = Math.max(0, pieCategoryCount.value - 6) * 26;
  return `${Math.min(baseHeight + extraHeight, 760)}px`;
});

const trendOption = computed(() => buildTrendOption(chartResults.TREND));
const barOption = computed(() => buildBarOption(chartResults.BAR));
const pieOption = computed(() => buildPieOption(chartResults.PIE, chartGridMode.value));

watch(querySchemaOptions, () => {
  sanitizeQueryGroups();
}, { deep: true });

watch(targetSchemaOptions, () => {
  normalizeTargetSelection();
}, { deep: true });

watch([selectedDatasourceType, selectedDatasourceId, selectedTargetScope, targetMetaSchemaCode, targetFieldKey, trendDays, topN], () => {
  clearAnalysisResult();
});

watch(queryGroups, () => {
  clearAnalysisResult();
}, { deep: true });

watch(canRunAnalysis, (value) => {
  if (!value) {
    setupExpanded.value = true;
  }
});

onMounted(async () => {
  await loadDatasources();
  applyRouteQuery();
  await loadWorkspace();
});

function normalizeTypeCode(value?: string) {
  return value?.trim().toLowerCase() ?? "";
}

function sameId(left?: EntityId, right?: EntityId) {
  if (left == null || right == null) {
    return false;
  }
  return String(left) === String(right);
}

function toNumber(value: unknown) {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : 0;
  }
  if (typeof value === "string") {
    const parsed = Number(value);
    return Number.isNaN(parsed) ? 0 : parsed;
  }
  return 0;
}

function formatInteger(value: unknown) {
  return new Intl.NumberFormat(locale.value.startsWith("zh") ? "zh-CN" : "en-US", {
    maximumFractionDigits: 0,
  }).format(Math.trunc(toNumber(value)));
}

function formatDecimal(value: unknown) {
  const numeric = toNumber(value);
  if (Number.isInteger(numeric)) {
    return formatInteger(numeric);
  }
  return new Intl.NumberFormat(locale.value.startsWith("zh") ? "zh-CN" : "en-US", {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  }).format(numeric);
}

function formatPercent(value: unknown) {
  return new Intl.NumberFormat(locale.value.startsWith("zh") ? "zh-CN" : "en-US", {
    style: "percent",
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(toNumber(value));
}

function formatMetricValue(value: unknown) {
  if (value == null || value === "") {
    return "-";
  }
  if (typeof value === "number") {
    return Number.isInteger(value) ? formatInteger(value) : formatDecimal(value);
  }
  if (typeof value === "string") {
    const trimmed = value.trim();
    if (!trimmed) {
      return "-";
    }
    const numeric = Number(trimmed);
    return Number.isNaN(numeric) ? trimmed : (Number.isInteger(numeric) ? formatInteger(numeric) : formatDecimal(numeric));
  }
  return String(value);
}

function metricLabel(metricKey: string) {
  const zh = {
    matchedModelCount: "命中模型数",
    matchedItemCount: "命中条目数",
    count: "统计值数量",
    distinctCount: "去重值数量",
    min: "最小值",
    max: "最大值",
    sum: "总和",
    avg: "平均值",
  } as Record<string, string>;
  const en = {
    matchedModelCount: "Matched Models",
    matchedItemCount: "Matched Items",
    count: "Value Count",
    distinctCount: "Distinct Values",
    min: "Min",
    max: "Max",
    sum: "Sum",
    avg: "Avg",
  } as Record<string, string>;
  return (locale.value.startsWith("zh") ? zh : en)[metricKey] ?? metricKey;
}

function createMetricCard(key: string, value: unknown, tone: MetricCardItem["tone"]) {
  if (value == null || value === "") {
    return undefined;
  }
  return {
    key,
    label: metricLabel(key),
    value: formatMetricValue(value),
    tone,
  } as MetricCardItem;
}

function schemaLabel(schema: DataModelStatisticsSchemaOptionView) {
  if ((schema.scope ?? "BUSINESS") === "TECHNICAL") {
    return `${schema.datasourceType || "technical"} / ${schema.schemaName}`;
  }
  return `${schema.metaModelCode || "business"} / ${schema.schemaName}`;
}

function findDatasourceById(datasourceId?: EntityId) {
  return datasources.value.find((item) => sameId(item.id, datasourceId));
}

function findQuerySchema(schemaCode?: string) {
  if (!schemaCode) {
    return undefined;
  }
  return querySchemaOptions.value.find((schema) => schema.schemaCode === schemaCode);
}

function findTargetSchema(schemaCode?: string) {
  if (!schemaCode) {
    return undefined;
  }
  return targetSchemaOptions.value.find((schema) => schema.schemaCode === schemaCode);
}

function querySchemaFields(group: QueryGroupState) {
  return findQuerySchema(group.metaSchemaCode)?.fields ?? [];
}

function queryConditionField(group: QueryGroupState, condition: QueryConditionState) {
  return querySchemaFields(group).find((field) => field.fieldKey === condition.fieldKey);
}

function isNumericField(field?: Pick<DataModelStatisticsFieldOptionView, "valueType">) {
  return field?.valueType === "INTEGER" || field?.valueType === "LONG" || field?.valueType === "DECIMAL";
}

function querySchemaOperatorOptions(field?: DataModelStatisticsFieldOptionView) {
  const configured = (field?.queryOperators ?? []).map((item) => String(item)).filter(Boolean);
  if (configured.length > 0) {
    return configured;
  }
  if (field?.valueType === "BOOLEAN") {
    return ["EQ", "IN"];
  }
  if (isNumericField(field)) {
    return ["EQ", "IN", "GT", "GE", "LT", "LE", "BETWEEN"];
  }
  return ["EQ", "LIKE", "IN"];
}

function defaultQueryOperator(field?: DataModelStatisticsFieldOptionView) {
  return String(field?.queryDefaultOperator ?? querySchemaOperatorOptions(field)[0] ?? "EQ");
}

let queryGroupSeed = 0;

function nextQueryGroupKey() {
  queryGroupSeed += 1;
  return `statistics-query-group-${queryGroupSeed}`;
}

function createDefaultQueryCondition(schema?: DataModelStatisticsSchemaOptionView): QueryConditionState {
  const field = (schema?.fields ?? [])[0];
  return {
    fieldKey: field?.fieldKey ?? "",
    operator: defaultQueryOperator(field),
    value: undefined,
    valueTo: undefined,
    multiValueText: "",
  };
}

function createQueryGroup(schemaCode?: string): QueryGroupState {
  const schema = schemaCode ? findQuerySchema(schemaCode) : querySchemaOptions.value[0];
  return {
    key: nextQueryGroupKey(),
    metaSchemaCode: schema?.schemaCode ?? schemaCode ?? "",
    rowMatchMode: schema?.displayMode === "MULTIPLE" ? "SAME_ITEM" : "ANY_ITEM",
    conditions: [createDefaultQueryCondition(schema)],
  };
}

function appendQueryGroup() {
  if (querySchemaOptions.value.length === 0) {
    return;
  }
  queryGroups.value.push(createQueryGroup());
}

function removeQueryGroup(groupKey: string) {
  queryGroups.value = queryGroups.value.filter((group) => group.key !== groupKey);
}

function appendQueryCondition(group: QueryGroupState) {
  group.conditions.push(createDefaultQueryCondition(findQuerySchema(group.metaSchemaCode)));
}

function removeQueryCondition(group: QueryGroupState, index: number) {
  group.conditions.splice(index, 1);
  if (group.conditions.length === 0) {
    group.conditions.push(createDefaultQueryCondition(findQuerySchema(group.metaSchemaCode)));
  }
}

function isMultipleQuerySchema(group: QueryGroupState) {
  return findQuerySchema(group.metaSchemaCode)?.displayMode === "MULTIPLE";
}

function handleQuerySchemaChange(group: QueryGroupState) {
  const schema = findQuerySchema(group.metaSchemaCode);
  group.rowMatchMode = schema?.displayMode === "MULTIPLE" ? "SAME_ITEM" : "ANY_ITEM";
  group.conditions = [createDefaultQueryCondition(schema)];
}

function handleQueryFieldChange(group: QueryGroupState, condition: QueryConditionState) {
  const field = queryConditionField(group, condition);
  condition.operator = defaultQueryOperator(field);
  condition.value = undefined;
  condition.valueTo = undefined;
  condition.multiValueText = "";
}

function queryConditionOperators(group: QueryGroupState, condition: QueryConditionState) {
  return querySchemaOperatorOptions(queryConditionField(group, condition));
}

function parseQueryValue(value: unknown, field?: DataModelStatisticsFieldOptionView) {
  if (value === undefined || value === null || (typeof value === "string" && value.trim() === "")) {
    return undefined;
  }
  if (field?.valueType === "BOOLEAN") {
    if (typeof value === "boolean") {
      return value;
    }
    return String(value).trim().toLowerCase() === "true";
  }
  if (isNumericField(field)) {
    const numeric = Number(value);
    return Number.isNaN(numeric) ? undefined : numeric;
  }
  return typeof value === "string" ? value.trim() : value;
}

function buildQueryGroupsPayload() {
  const result: DataModelQueryGroup[] = [];
  for (const group of queryGroups.value) {
    const schema = findQuerySchema(group.metaSchemaCode);
    if (!schema) {
      continue;
    }
    const conditions: DataModelQueryCondition[] = [];
    for (const condition of group.conditions) {
      const field = queryConditionField(group, condition);
      if (!field || !condition.fieldKey || !condition.operator) {
        continue;
      }
      if (condition.operator === "IN") {
        const values = condition.multiValueText
          .split(",")
          .map((item) => parseQueryValue(item, field))
          .filter((item) => item !== undefined);
        if (values.length === 0) {
          continue;
        }
        conditions.push({
          fieldKey: condition.fieldKey,
          operator: condition.operator,
          values,
        });
        continue;
      }
      if (condition.operator === "BETWEEN") {
        const lower = parseQueryValue(condition.value, field);
        const upper = parseQueryValue(condition.valueTo, field);
        if (lower === undefined || upper === undefined) {
          continue;
        }
        conditions.push({
          fieldKey: condition.fieldKey,
          operator: condition.operator,
          values: [lower, upper],
        });
        continue;
      }
      const value = parseQueryValue(condition.value, field);
      if (value === undefined) {
        continue;
      }
      conditions.push({
        fieldKey: condition.fieldKey,
        operator: condition.operator,
        value,
      });
    }
    if (conditions.length === 0) {
      continue;
    }
    result.push({
      scope: schema.scope,
      metaSchemaCode: schema.schemaCode,
      rowMatchMode: schema.displayMode === "MULTIPLE" ? group.rowMatchMode : "ANY_ITEM",
      conditions,
    });
  }
  return result;
}

function buildBaseStatisticsRequest(): Omit<DataModelStatisticsRequest, "statType" | "topN"> {
  return {
    datasourceId: selectedDatasourceId.value,
    groups: buildQueryGroupsPayload(),
    targetMetaSchemaCode: targetMetaSchemaCode.value,
    targetFieldKey: targetFieldKey.value,
    targetScope: selectedTargetScope.value,
  };
}

function buildRawStatisticsRequest(): DataModelStatisticsRequest {
  return {
    ...buildBaseStatisticsRequest(),
    statType: isNumericField(targetField.value) ? "SUMMARY" : "COUNT_BY_VALUE",
  };
}

function buildChartRequest(chartType: StatisticsChartType): DataModelStatisticsChartRequest {
  return {
    ...buildBaseStatisticsRequest(),
    chartType,
    days: trendDays.value,
    ...(chartType === "TOPN" ? { topN: topN.value } : {}),
    timeMode: "CREATED_AT",
  };
}

function canRenderChart(chart?: DataModelStatisticsChartView) {
  if (!chart || chart.disabledReason) {
    return false;
  }
  return (chart.series ?? []).some((series) => Array.isArray(series.data) && series.data.length > 0);
}

function chartMessage(chart?: DataModelStatisticsChartView) {
  return chart?.disabledReason || t("web.statistics.chartEmpty");
}

function clearAnalysisResult() {
  rawStatistics.value = undefined;
  for (const chartType of chartTypes) {
    chartResults[chartType] = undefined;
  }
}

function sanitizeQueryGroups() {
  const availableSchemas = new Set(querySchemaOptions.value.map((schema) => schema.schemaCode));
  queryGroups.value = queryGroups.value
    .filter((group) => availableSchemas.has(group.metaSchemaCode))
    .map((group) => {
      const schema = findQuerySchema(group.metaSchemaCode);
      if (!schema) {
        return group;
      }
      const availableFields = new Set((schema.fields ?? []).map((field) => field.fieldKey));
      const nextConditions = group.conditions
        .filter((condition) => availableFields.has(condition.fieldKey))
        .map((condition) => {
          const field = (schema.fields ?? []).find((item) => item.fieldKey === condition.fieldKey);
          const operators = querySchemaOperatorOptions(field);
          return {
            ...condition,
            operator: operators.includes(condition.operator) ? condition.operator : defaultQueryOperator(field),
          };
        });
      return {
        ...group,
        rowMatchMode: schema.displayMode === "MULTIPLE" ? group.rowMatchMode : "ANY_ITEM",
        conditions: nextConditions.length > 0 ? nextConditions : [createDefaultQueryCondition(schema)],
      };
    });
}

function normalizeTargetSelection() {
  const schema = findTargetSchema(targetMetaSchemaCode.value);
  if (!schema) {
    targetMetaSchemaCode.value = targetSchemaOptions.value[0]?.schemaCode ?? "";
  }
  const nextSchema = findTargetSchema(targetMetaSchemaCode.value);
  const availableFields = nextSchema?.fields ?? [];
  if (!availableFields.some((field) => field.fieldKey === targetFieldKey.value)) {
    targetFieldKey.value = availableFields[0]?.fieldKey ?? "";
  }
}

function applyRouteQuery() {
  const routeDatasourceId = route.query.datasourceId;
  const routeDatasourceType = route.query.datasourceType;
  if (typeof routeDatasourceType === "string" && routeDatasourceType.trim()) {
    selectedDatasourceType.value = routeDatasourceType.trim();
  }
  if (typeof routeDatasourceId === "string" && routeDatasourceId.trim()) {
    const matched = datasources.value.find((item) => String(item.id) === routeDatasourceId.trim());
    if (matched?.id != null) {
      selectedDatasourceId.value = matched.id;
      selectedDatasourceType.value = matched.typeCode;
    }
  }
}

async function loadDatasources() {
  const items = await studioApi.datasources.list();
  datasources.value = [...items].sort((left, right) => left.name.localeCompare(right.name));
}

async function loadWorkspace() {
  clearAnalysisResult();
  if (requiresDatasourceType.value && !activeDatasourceType.value && !selectedDatasourceId.value) {
    workspaceOptions.value = {
      datasourceType: undefined,
      querySchemas: [],
      targetSchemas: [],
    };
    queryGroups.value = [];
    normalizeTargetSelection();
    return;
  }
  workspaceLoading.value = true;
  try {
    workspaceOptions.value = await studioApi.statistics.options({
      datasourceId: selectedDatasourceId.value,
      datasourceType: activeDatasourceType.value,
      targetScope: selectedTargetScope.value,
    });
    sanitizeQueryGroups();
    normalizeTargetSelection();
  } catch (error) {
    ElMessage.error(`${t("web.statistics.loadFailed")}: ${resolveErrorMessage(error)}`);
  } finally {
    workspaceLoading.value = false;
  }
}

async function handleDatasourceTypeChange() {
  if (selectedDatasource.value && normalizeTypeCode(selectedDatasource.value.typeCode) !== normalizeTypeCode(selectedDatasourceType.value)) {
    selectedDatasourceId.value = undefined;
  }
  queryGroups.value = [];
  await loadWorkspace();
}

async function handleDatasourceChange() {
  if (selectedDatasource.value?.typeCode) {
    selectedDatasourceType.value = selectedDatasource.value.typeCode;
  }
  queryGroups.value = [];
  await loadWorkspace();
}

async function handleTargetScopeChange() {
  setupExpanded.value = true;
  targetMetaSchemaCode.value = "";
  targetFieldKey.value = "";
  await loadWorkspace();
}

function toggleSetupPanel() {
  setupExpanded.value = !setupExpanded.value;
}

function resetQueryFilters() {
  queryGroups.value = [];
}

async function runAnalysis() {
  if (!targetSchema.value || !targetField.value) {
    ElMessage.warning(t("web.statistics.noTarget"));
    return;
  }
  runningAnalysis.value = true;
  try {
    const [statistics, ...charts] = await Promise.all([
      studioApi.models.statistics(buildRawStatisticsRequest()),
      ...chartTypes.map((chartType) => studioApi.statistics.queryChart(buildChartRequest(chartType))),
    ]);
    rawStatistics.value = statistics;
    chartTypes.forEach((chartType, index) => {
      chartResults[chartType] = charts[index];
    });
    setupExpanded.value = false;
  } catch (error) {
    ElMessage.error(`${t("web.statistics.runFailed")}: ${resolveErrorMessage(error)}`);
  } finally {
    runningAnalysis.value = false;
  }
}

function resolveErrorMessage(error: unknown) {
  if (typeof error === "object" && error && "response" in error) {
    const response = (error as { response?: { data?: { message?: string } } }).response;
    const message = response?.data?.message;
    if (message) {
      return message;
    }
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return locale.value.startsWith("zh") ? "未知错误" : "Unknown error";
}

function buildTrendOption(chart?: DataModelStatisticsChartView): EChartsOption {
  if (!chart) {
    return {};
  }
  const series = chart.series?.[0];
  return {
    tooltip: { trigger: "axis" },
    grid: { left: 16, right: 16, top: 24, bottom: 24, containLabel: true },
    xAxis: {
      type: "category",
      data: chart.xAxis ?? [],
      axisLabel: { rotate: chart.xAxis.length > 10 ? 25 : 0 },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
    },
    series: [{
      name: series?.name ?? metricLabel("count"),
      type: "line",
      smooth: true,
      areaStyle: { opacity: 0.18 },
      itemStyle: { color: "#2f6fed" },
      lineStyle: { width: 3 },
      data: (series?.data ?? []).map((item) => toNumber(item)),
    }],
  };
}

function buildBarOption(chart?: DataModelStatisticsChartView): EChartsOption {
  if (!chart) {
    return {};
  }
  const series = chart.series?.[0];
  return {
    tooltip: { trigger: "axis" },
    grid: { left: 16, right: 16, top: 24, bottom: 48, containLabel: true },
    xAxis: {
      type: "category",
      data: chart.xAxis ?? [],
      axisLabel: { rotate: chart.xAxis.length > 6 ? 20 : 0 },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
    },
    series: [{
      name: series?.name ?? metricLabel("count"),
      type: "bar",
      barMaxWidth: 42,
      itemStyle: { color: "#0f9d58", borderRadius: [8, 8, 0, 0] },
      data: (series?.data ?? []).map((item) => toNumber(item)),
    }],
  };
}

function buildPieOption(chart?: DataModelStatisticsChartView, layoutMode: "default" | "wide" | "stacked" = "default"): EChartsOption {
  if (!chart) {
    return {};
  }
  const series = chart.series?.[0];
  const pieData = Array.isArray(series?.data)
    ? series.data.map((item, index) => {
      if (item && typeof item === "object" && "value" in item) {
        const pieItem = item as { name?: string; value?: unknown };
        return {
          name: pieItem.name ?? chart.xAxis[index] ?? `${index + 1}`,
          value: toNumber(pieItem.value),
        };
      }
      return {
        name: chart.xAxis[index] ?? `${index + 1}`,
        value: toNumber(item),
      };
      })
      : [];
  const largeLegend = pieData.length >= 12;
  const pieCenter = layoutMode === "stacked"
    ? (largeLegend ? ["50%", "32%"] : ["50%", "35%"])
    : layoutMode === "wide"
      ? ["50%", "38%"]
      : ["50%", "44%"];
  const pieRadius = layoutMode === "stacked"
    ? (largeLegend ? ["30%", "54%"] : ["34%", "58%"])
    : layoutMode === "wide"
      ? ["36%", "60%"]
      : ["40%", "68%"];
  return {
    tooltip: { trigger: "item" },
    legend: {
      bottom: 0,
      left: "center",
      width: "92%",
      itemGap: 14,
    },
    series: [{
      name: series?.name ?? metricLabel("count"),
      type: "pie",
      radius: pieRadius,
      center: pieCenter,
      itemStyle: {
        borderRadius: 8,
        borderColor: "#fff",
        borderWidth: 2,
      },
      label: {
        formatter: "{b}: {d}%",
      },
      data: pieData,
    }],
  };
}
</script>

<style scoped>
.statistics-center {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.statistics-context {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
}

.statistics-setup-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.statistics-setup-hint {
  margin-top: 14px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}

.statistics-setup-panel {
  margin-top: 18px;
}

.statistics-setup-divider {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin: 4px 0 16px;
}

.statistics-setup-divider h4 {
  margin: 0;
  font-size: 15px;
}

.statistics-setup-divider p {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.statistics-setup-divider__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.statistics-field-hint {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.statistics-bucket-note {
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.statistics-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  color: var(--el-text-color-secondary);
}

.statistics-empty--compact {
  min-height: 88px;
}

.statistics-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}

.statistics-chart-grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 20px;
  align-items: stretch;
}

.statistics-chart-card {
  min-width: 0;
}

.statistics-chart-card--trend,
.statistics-chart-card--bar,
.statistics-chart-card--pie {
  grid-column: span 4;
}

.statistics-chart-grid--wide .statistics-chart-card--trend,
.statistics-chart-grid--wide .statistics-chart-card--bar {
  grid-column: span 6;
}

.statistics-chart-grid--wide .statistics-chart-card--pie {
  grid-column: 1 / -1;
}

.statistics-chart-grid--stacked .statistics-chart-card--trend,
.statistics-chart-grid--stacked .statistics-chart-card--bar,
.statistics-chart-grid--stacked .statistics-chart-card--pie {
  grid-column: 1 / -1;
}

.statistics-chart-card--topn {
  grid-column: 1 / -1;
}

.statistics-card-control {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.statistics-card-control__label {
  white-space: nowrap;
}

.statistics-card-control__select {
  width: 108px;
}

.statistics-card-control__number {
  width: 132px;
}

.query-condition-value {
  display: flex;
  align-items: center;
  gap: 10px;
}

.query-condition-value__input {
  flex: 1;
}

.query-condition-value__divider {
  color: var(--el-text-color-secondary);
}

.ranking-board {
  display: grid;
  gap: 12px;
}

.ranking-board__header,
.ranking-board__row {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
}

.ranking-board__header {
  padding: 0 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.ranking-board__content-head--header span:last-child,
.ranking-board__content-head--header span:nth-last-child(2) {
  text-align: right;
}

.ranking-board__content-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 108px 88px;
  gap: 12px;
  align-items: center;
}

.ranking-board__row {
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(47, 111, 237, 0.08), rgba(47, 111, 237, 0.02)),
    var(--el-fill-color-blank);
}

.ranking-board__content-head--header {
  gap: 12px;
}

.ranking-board__rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 72px;
  padding: 8px 12px;
  border-radius: 999px;
  color: var(--studio-primary-deep);
  font-weight: 700;
  background: rgba(47, 111, 237, 0.12);
}

.ranking-board__content {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.ranking-board__label {
  min-width: 0;
  font-size: 15px;
  line-height: 1.35;
  word-break: break-word;
}

.ranking-board__count,
.ranking-board__ratio {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  text-align: right;
  white-space: nowrap;
}

.ranking-board__track {
  height: 10px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(47, 111, 237, 0.1);
}

.ranking-board__fill {
  height: 100%;
  min-width: 10px;
  border-radius: inherit;
  background: linear-gradient(90deg, #2f6fed 0%, #38bdf8 100%);
}

@media (max-width: 960px) {
  .statistics-chart-grid {
    grid-template-columns: 1fr;
  }

  .statistics-chart-card--trend,
  .statistics-chart-card--bar,
  .statistics-chart-card--pie,
  .statistics-chart-card--topn {
    grid-column: 1 / -1;
  }

  .statistics-setup-divider {
    flex-direction: column;
  }

  .ranking-board__header {
    display: none;
  }

  .ranking-board__row,
  .ranking-board__content-head {
    grid-template-columns: minmax(0, 1fr);
  }

  .ranking-board__count,
  .ranking-board__ratio {
    text-align: left;
  }
}
</style>
