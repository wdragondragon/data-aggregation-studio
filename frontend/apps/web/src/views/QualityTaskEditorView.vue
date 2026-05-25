<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ taskId ? "编辑质量任务" : "新建质量任务" }}</h3>
        <p>基于质量规则为具体数据表或字段配置可执行的数据质量校验任务。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/quality-tasks')">返回列表</el-button>
        <el-button plain @click="loadTask">刷新</el-button>
        <el-button plain :disabled="!form.id" @click="openRunLogs">运行日志</el-button>
        <el-button plain :loading="previewLoading" @click="previewSql">SQL 预览</el-button>
        <el-button plain :loading="validateLoading" @click="validateTask">语义校验</el-button>
        <el-button type="primary" :loading="saving" @click="saveTask">保存草稿</el-button>
        <el-button type="success" :disabled="!form.id" :loading="publishing" @click="publishTask">发布</el-button>
        <el-button type="warning" :disabled="!form.id" :loading="triggering" @click="triggerTask">手动执行</el-button>
      </div>
    </div>

    <SectionCard title="基本信息" description="先确定任务编码、规则维度与校验粒度，后续规则会按条件过滤。">
      <div class="studio-form-grid">
        <el-form-item label="任务名称">
          <el-input v-model="form.taskName" placeholder="例如：订单表主键唯一性巡检" />
        </el-form-item>
        <el-form-item label="任务编码">
          <el-input v-model="form.taskCode" placeholder="例如：DQ_TASK_ORDER_PK" />
        </el-form-item>
        <el-form-item label="任务状态">
          <el-input :model-value="form.id ? (taskStatus === 'ONLINE' ? '已发布' : '草稿') : '未保存'" disabled />
        </el-form-item>
        <el-form-item label="规则维度">
          <el-select v-model="form.ruleDimension" @change="handleRuleDimensionChange">
            <el-option v-for="option in dimensionOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="校验粒度">
          <el-select v-model="form.granularity" @change="handleGranularityChange">
            <el-option label="表级" value="TABLE" />
            <el-option label="字段级" value="COLUMN" />
          </el-select>
        </el-form-item>
      </div>
    </SectionCard>

    <SectionCard title="任务绑定" description="质量任务需要明确目标数据源、模型；字段级任务还需要绑定具体字段。">
      <div class="studio-form-grid">
        <el-form-item label="数据源">
          <el-select
            :model-value="String(form.datasourceId || '')"
            filterable
            clearable
            placeholder="请选择支持 SQL 执行的数据源"
            @update:model-value="handleDatasourceChange"
          >
            <el-option v-for="datasource in datasources" :key="String(datasource.id)" :label="datasource.name" :value="String(datasource.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型">
          <el-select
            :model-value="String(form.modelId || '')"
            filterable
            clearable
            placeholder="请选择目标模型"
            @update:model-value="handleModelChange"
          >
            <el-option
              v-for="model in currentDatasourceModels"
              :key="String(model.id)"
              :label="`${model.name}${model.physicalLocator ? ` / ${model.physicalLocator}` : ''}`"
              :value="String(model.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.granularity === 'COLUMN'" label="字段">
          <el-select v-model="form.columnName" filterable clearable placeholder="请选择目标字段" @change="handleColumnChange">
            <el-option v-for="column in currentColumnOptions" :key="column" :label="column" :value="column" />
          </el-select>
        </el-form-item>
      </div>
    </SectionCard>

    <SectionCard title="规则选择" description="仅展示当前维度、粒度和数据源类型下可用的已启用质量规则。">
      <div class="section-toolbar">
        <div>
          <strong>匹配规则</strong>
          <p>选中规则后会自动加载 SQL 模板、输入参数和输出参数。</p>
        </div>
        <el-button plain :loading="rulesLoading" @click="loadRuleOptions">刷新规则</el-button>
      </div>
      <div class="studio-form-grid">
        <el-form-item label="质量规则">
          <el-select
            :model-value="String(form.ruleId || '')"
            filterable
            clearable
            placeholder="请选择质量规则"
            @update:model-value="handleRuleChange"
          >
            <el-option
              v-for="rule in ruleOptions"
              :key="String(rule.id)"
              :label="`${rule.ruleName} / ${rule.ruleCode}`"
              :value="String(rule.id)"
            >
              <div class="rule-option">
                <span>{{ rule.ruleName }}</span>
                <span class="cell-subtle">{{ rule.scopeType === "SYSTEM" ? "系统级" : "项目级" }} / {{ resolveDimensionLabel(rule.ruleDimension) }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="规则来源">
          <el-input :model-value="selectedRuleDetail ? (selectedRuleDetail.scopeType === 'SYSTEM' ? '系统级规则' : '项目级规则') : '-'" disabled />
        </el-form-item>
        <el-form-item label="适配类型">
          <el-input :model-value="resolveSupportedDatasourceTypesLabel(selectedRuleDetail)" disabled />
        </el-form-item>
      </div>
      <el-form-item label="定义逻辑 SQL">
        <el-input :model-value="selectedRuleDetail?.logicSql || ''" type="textarea" :rows="8" readonly placeholder="选择规则后展示定义逻辑 SQL" />
      </el-form-item>
    </SectionCard>

    <QualityParameterBindingsSection
      :bindings="form.parameterBindings"
      :actions="parameterBindingActions"
    />

    <SectionCard title="SQL 预览与校验" description="where 子句支持动态函数；SQL 预览会替换参数与函数后生成当前时刻可执行语句。">
      <div class="section-toolbar">
        <div>
          <strong>执行约束</strong>
          <p>where 子句会拼接到规则 SQL 外层，最终 SQL 可直接用于执行校验。</p>
        </div>
        <div class="studio-toolbar-actions">
          <el-button plain @click="openDynamicFunctionDialog({ type: 'whereClause' })">插入动态函数</el-button>
          <el-button plain :loading="previewLoading" @click="previewSql">SQL 预览</el-button>
          <el-button type="primary" plain :loading="validateLoading" @click="validateTask">语义校验</el-button>
        </div>
      </div>
      <el-form-item label="Where 子句">
        <textarea
          ref="whereClauseTextareaRef"
          v-model="form.whereClause"
          class="task-where-textarea"
          placeholder="例如：dt = $getCurrentTime('yyyy-MM-dd', '-1d') 或 status = 'VALID'"
          spellcheck="false"
          @click="syncWhereClauseSelection"
          @focus="syncWhereClauseSelection"
          @keyup="syncWhereClauseSelection"
          @select="syncWhereClauseSelection"
        />
      </el-form-item>
      <el-form-item label="最终执行 SQL">
        <el-input v-model="form.resolvedSqlPreview" type="textarea" :rows="10" readonly placeholder="点击 SQL 预览后生成实际语句" />
      </el-form-item>
      <div v-if="previewWarnings.length" class="inline-message warning">
        <div v-for="(warning, index) in previewWarnings" :key="`preview-${index}`">{{ warning }}</div>
      </div>
      <div v-if="validationResult?.message" class="inline-message" :class="validationResult.valid ? 'success' : 'danger'">
        {{ validationResult.message }}
      </div>
      <div v-if="validationWarnings.length" class="inline-message warning">
        <div v-for="(warning, index) in validationWarnings" :key="`validate-${index}`">{{ warning }}</div>
      </div>
    </SectionCard>

    <QualityAlertConfigsSection
      :configs="form.alertConfigs"
      :actions="alertConfigActions"
    />

    <QualityScheduleSection :schedule="form.schedule" />

    <QualityValidationResultSection :validation-result="validationResult" />

    <QualityDynamicFunctionDialog
      v-model="dynamicFunctionDialogVisible"
      v-model:selected-name="selectedDynamicFunctionName"
      :catalog="dynamicFunctionCatalog"
      :selected-function="selectedDynamicFunction"
      :selected-input-snippet="selectedInputSnippet"
      :args="dynamicFunctionArgs"
      :preview="dynamicFunctionPreview"
      @confirm="confirmDynamicFunctionInsert"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import type {
  DataModelDefinition,
  DataSourceDefinition,
  QualityRuleDimension,
  QualityRuleGranularity,
  QualityRuleOutputParamView,
  QualityRuleOutputType,
  QualityRuleParamType,
  QualityRuleView,
  QualityTaskAlertConfig,
  QualityTaskAlertOperator,
  QualityTaskValidationView,
  QualityTaskParamBinding,
  QualityTaskSaveRequest,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import QualityAlertConfigsSection from "@/components/quality/QualityAlertConfigsSection.vue";
import QualityDynamicFunctionDialog from "@/components/quality/QualityDynamicFunctionDialog.vue";
import QualityParameterBindingsSection from "@/components/quality/QualityParameterBindingsSection.vue";
import QualityScheduleSection from "@/components/quality/QualityScheduleSection.vue";
import QualityValidationResultSection from "@/components/quality/QualityValidationResultSection.vue";
import { dynamicFunctionCatalog } from "@/components/quality/qualityTaskDynamicFunctions";
import type { DynamicFunctionInsertTarget, DynamicFunctionParamSchema } from "@/components/quality/qualityTaskDynamicFunctions";

interface QualityTaskEditorForm {
  id?: string | number;
  taskName: string;
  taskCode: string;
  ruleDimension: QualityRuleDimension;
  granularity: QualityRuleGranularity;
  datasourceId: string | number;
  modelId: string | number;
  columnName: string;
  ruleId: string | number;
  whereClause: string;
  resolvedSqlPreview: string;
  parameterBindings: QualityTaskParamBinding[];
  alertConfigs: QualityTaskAlertConfig[];
  schedule: {
    enabled: boolean;
    cronExpression: string;
    timezone: string;
  };
}

const route = useRoute();
const router = useRouter();

const taskId = computed(() => route.params.taskId as string | undefined);
const saving = ref(false);
const previewLoading = ref(false);
const validateLoading = ref(false);
const publishing = ref(false);
const triggering = ref(false);
const rulesLoading = ref(false);
const datasources = ref<DataSourceDefinition[]>([]);
const modelCache = ref<Record<string, DataModelDefinition[]>>({});
const ruleOptions = ref<QualityRuleView[]>([]);
const selectedRuleDetail = ref<QualityRuleView | null>(null);
const outputParams = ref<QualityRuleOutputParamView[]>([]);
const taskStatus = ref<"DRAFT" | "ONLINE">("DRAFT");
const previewWarnings = ref<string[]>([]);
const validationWarnings = ref<string[]>([]);
const validationResult = ref<QualityTaskValidationView | null>(null);
const dynamicFunctionDialogVisible = ref(false);
const selectedDynamicFunctionName = ref("getCurrentTime");
const selectedInputSnippet = ref("");
const dynamicFunctionArgs = reactive<Record<string, string>>({});
const dynamicFunctionTarget = ref<DynamicFunctionInsertTarget | null>(null);
const whereClauseTextareaRef = ref<HTMLTextAreaElement | null>(null);
const whereClauseSelection = ref<{ start: number; end: number } | null>(null);
const bindingSelections = ref<Record<string, { start: number; end: number }>>({});
const bindingInputRefs = new Map<string, HTMLInputElement>();

const dimensionOptions: Array<{ label: string; value: QualityRuleDimension }> = [
  { label: "一致性", value: "CONSISTENCY" },
  { label: "准确性", value: "ACCURACY" },
  { label: "唯一性", value: "UNIQUENESS" },
  { label: "及时性", value: "TIMELINESS" },
  { label: "完整性", value: "COMPLETENESS" },
  { label: "有效性", value: "VALIDITY" },
];

const singleValueOperators: Array<{ label: string; value: QualityTaskAlertOperator }> = [
  { label: "R = 等于", value: "EQ" },
  { label: "R ≠ 不等于", value: "NE" },
  { label: "R < 小于", value: "LT" },
  { label: "R ≤ 小于等于", value: "LE" },
  { label: "R > 大于", value: "GT" },
  { label: "R ≥ 大于等于", value: "GE" },
];

const rangeOperators: Array<{ label: string; value: QualityTaskAlertOperator }> = [
  { label: "< R < 大于最小值，小于最大值", value: "LT_R_LT" },
  { label: "< R ≤ 大于最小值，小于等于最大值", value: "LT_R_LE" },
  { label: "≤ R < 大于等于最小值，小于最大值", value: "LE_R_LT" },
  { label: "≤ R ≤ 大于等于最小值，小于等于最大值", value: "LE_R_LE" },
];

const form = reactive<QualityTaskEditorForm>({
  taskName: "",
  taskCode: "",
  ruleDimension: "CONSISTENCY",
  granularity: "TABLE",
  datasourceId: "",
  modelId: "",
  columnName: "",
  ruleId: "",
  whereClause: "",
  resolvedSqlPreview: "",
  parameterBindings: [],
  alertConfigs: [],
  schedule: {
    enabled: false,
    cronExpression: "0 */30 * * * ?",
    timezone: "Asia/Shanghai",
  },
});

const currentDatasource = computed(() =>
  datasources.value.find((item) => String(item.id ?? "") === String(form.datasourceId || "")) ?? null,
);

const currentDatasourceModels = computed(() => resolveModelsByDatasource(form.datasourceId));

const currentModel = computed(() =>
  currentDatasourceModels.value.find((item) => String(item.id ?? "") === String(form.modelId || "")) ?? findModelById(form.modelId),
);

const currentColumnOptions = computed(() => resolveFieldsByModel(currentModel.value));
const selectedDynamicFunction = computed(() =>
  dynamicFunctionCatalog.find((item) => item.name === selectedDynamicFunctionName.value) ?? dynamicFunctionCatalog[0],
);
const dynamicFunctionPreview = computed(() => buildDynamicFunctionExpression());
const parameterBindingActions = {
  resolveParamTypeLabel,
  registerBindingInputRef,
  syncBindingSelection,
  openBindingFunction: (key: string) => openDynamicFunctionDialog({ type: "binding", key }),
};
const alertConfigActions = {
  resolveAlertOperators,
  isRangeOperator,
  handleAlertOperatorChange,
};

function resetForm() {
  form.id = undefined;
  form.taskName = "";
  form.taskCode = "";
  form.ruleDimension = "CONSISTENCY";
  form.granularity = "TABLE";
  form.datasourceId = "";
  form.modelId = "";
  form.columnName = "";
  form.ruleId = "";
  form.whereClause = "";
  form.resolvedSqlPreview = "";
  form.parameterBindings = [];
  form.alertConfigs = [];
  form.schedule = {
    enabled: false,
    cronExpression: "0 */30 * * * ?",
    timezone: "Asia/Shanghai",
  };
  selectedRuleDetail.value = null;
  outputParams.value = [];
  taskStatus.value = "DRAFT";
  previewWarnings.value = [];
  validationWarnings.value = [];
  validationResult.value = null;
  dynamicFunctionDialogVisible.value = false;
  selectedInputSnippet.value = "";
  dynamicFunctionTarget.value = null;
  whereClauseSelection.value = null;
  bindingSelections.value = {};
}

async function loadTask() {
  await loadDatasources();
  if (!taskId.value) {
    resetForm();
    return;
  }
  try {
    const detail = await studioApi.qualityTasks.get(taskId.value);
    form.id = detail.id;
    form.taskName = detail.taskName;
    form.taskCode = detail.taskCode;
    form.ruleDimension = detail.ruleDimension ?? "CONSISTENCY";
    form.granularity = detail.granularity ?? "TABLE";
    form.datasourceId = detail.datasourceId ?? "";
    form.modelId = detail.modelId ?? "";
    form.columnName = detail.columnName ?? "";
    form.ruleId = detail.ruleId ?? "";
    form.whereClause = detail.whereClause ?? "";
    form.resolvedSqlPreview = detail.resolvedSqlPreview ?? "";
    form.parameterBindings = (detail.parameterBindings ?? []).map((item) => ({ ...item }));
    form.alertConfigs = (detail.alertConfigs ?? []).map((item) => ({ ...item }));
    form.schedule = {
      enabled: Boolean(detail.schedule?.enabled),
      cronExpression: detail.schedule?.cronExpression || "0 */30 * * * ?",
      timezone: detail.schedule?.timezone || "Asia/Shanghai",
    };
    outputParams.value = (detail.outputParams ?? []).map((item) => ({ ...item }));
    taskStatus.value = detail.status ?? "DRAFT";
    previewWarnings.value = [];
    validationWarnings.value = [];
    validationResult.value = null;
    if (form.datasourceId) {
      await ensureModels(form.datasourceId);
    }
    await loadRuleOptions();
    if (form.ruleId) {
      await ensureSelectedRuleLoaded(form.ruleId);
    }
    syncParameterBindings(form.parameterBindings);
    syncAlertConfigs(form.alertConfigs);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量任务详情失败");
  }
}

async function loadDatasources() {
  try {
    datasources.value = await studioApi.dataDevelopment.listSqlDatasources();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载数据源失败");
  }
}

async function loadRuleOptions() {
  rulesLoading.value = true;
  try {
    ruleOptions.value = await studioApi.qualityRules.options({
      ruleDimension: form.ruleDimension,
      granularity: form.granularity,
      datasourceType: currentDatasource.value?.typeCode,
      enabledOnly: true,
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量规则失败");
  } finally {
    rulesLoading.value = false;
  }
}

async function ensureSelectedRuleLoaded(ruleId: string | number) {
  const matched = ruleOptions.value.find((item) => String(item.id ?? "") === String(ruleId));
  if (matched) {
    selectedRuleDetail.value = matched;
    return;
  }
  try {
    const detail = await studioApi.qualityRules.get(ruleId);
    selectedRuleDetail.value = detail;
    if (!ruleOptions.value.some((item) => String(item.id ?? "") === String(detail.id ?? ""))) {
      ruleOptions.value = [...ruleOptions.value, detail];
    }
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : "当前质量规则已不可用");
  }
}

async function ensureModels(datasourceId: unknown) {
  const key = String(datasourceId ?? "");
  if (!key || key === "undefined" || key === "null" || modelCache.value[key]) {
    return;
  }
  modelCache.value[key] = await studioApi.models.listByDatasource(key);
}

function resolveModelsByDatasource(datasourceId: unknown) {
  return modelCache.value[String(datasourceId ?? "")] ?? [];
}

function findModelById(modelId: unknown) {
  const id = String(modelId ?? "");
  if (!id) {
    return null;
  }
  const models = Object.values(modelCache.value).flat();
  return models.find((item) => String(item.id ?? "") === id) ?? null;
}

function resolveFieldsByModel(model: DataModelDefinition | null) {
  const columns = model?.technicalMetadata?.columns;
  if (!Array.isArray(columns)) {
    return [];
  }
  return columns
    .map((item) => {
      if (typeof item === "string") {
        return item.trim();
      }
      if (item && typeof item === "object") {
        const map = item as Record<string, unknown>;
        return String(map.name ?? map.columnName ?? map.fieldName ?? map.physicalName ?? "").trim();
      }
      return "";
    })
    .filter(Boolean);
}

async function handleDatasourceChange(value: string) {
  form.datasourceId = value;
  form.modelId = "";
  form.columnName = "";
  clearRuleSelection();
  await ensureModels(value);
  await loadRuleOptions();
}

function handleModelChange(value: string) {
  form.modelId = value;
  if (form.granularity === "COLUMN" && currentColumnOptions.value.length > 0 && !currentColumnOptions.value.includes(form.columnName)) {
    form.columnName = "";
  }
  syncParameterBindings(form.parameterBindings);
}

function handleColumnChange() {
  syncParameterBindings(form.parameterBindings);
}

function registerBindingInputRef(key: string, element: HTMLInputElement | null) {
  if (!key) {
    return;
  }
  if (element) {
    bindingInputRefs.set(key, element);
    return;
  }
  bindingInputRefs.delete(key);
}

function syncBindingSelection(key: string, event: Event) {
  if (!key) {
    return;
  }
  const target = event.target as HTMLInputElement | null;
  if (!target) {
    return;
  }
  bindingSelections.value = {
    ...bindingSelections.value,
    [key]: {
      start: target.selectionStart ?? target.value.length,
      end: target.selectionEnd ?? target.value.length,
    },
  };
}

function syncWhereClauseSelection() {
  const textarea = whereClauseTextareaRef.value;
  if (!textarea) {
    return;
  }
  whereClauseSelection.value = {
    start: textarea.selectionStart ?? form.whereClause.length,
    end: textarea.selectionEnd ?? form.whereClause.length,
  };
}

function resolveBindingValue(key: string) {
  return form.parameterBindings.find((item) => item.paramName === key)?.paramValue ?? "";
}

function resolveInputSelectionRange(target: DynamicFunctionInsertTarget) {
  if (target.type === "whereClause") {
    const textarea = whereClauseTextareaRef.value;
    if (textarea && document.activeElement === textarea) {
      return {
        start: textarea.selectionStart ?? form.whereClause.length,
        end: textarea.selectionEnd ?? form.whereClause.length,
      };
    }
    return whereClauseSelection.value ?? {
      start: form.whereClause.length,
      end: form.whereClause.length,
    };
  }
  const input = bindingInputRefs.get(target.key);
  const currentValue = resolveBindingValue(target.key);
  if (input && document.activeElement === input) {
    return {
      start: input.selectionStart ?? currentValue.length,
      end: input.selectionEnd ?? currentValue.length,
    };
  }
  return bindingSelections.value[target.key] ?? {
    start: currentValue.length,
    end: currentValue.length,
  };
}

function resolveSelectedInputSnippet(target: DynamicFunctionInsertTarget) {
  const range = resolveInputSelectionRange(target);
  const currentValue = target.type === "whereClause"
    ? form.whereClause
    : resolveBindingValue(target.key);
  return range.end > range.start ? currentValue.slice(range.start, range.end) : "";
}

function resetDynamicFunctionArgs() {
  const nextValues: Record<string, string> = {};
  selectedDynamicFunction.value.params.forEach((param) => {
    const selectedValue = param.useSelectedSnippet && selectedInputSnippet.value ? selectedInputSnippet.value : "";
    nextValues[param.key] = selectedValue || param.defaultValue || "";
  });
  Object.keys(dynamicFunctionArgs).forEach((key) => {
    delete dynamicFunctionArgs[key];
  });
  Object.assign(dynamicFunctionArgs, nextValues);
}

function openDynamicFunctionDialog(target: DynamicFunctionInsertTarget) {
  if (target.type === "binding" && !target.key) {
    return;
  }
  if (target.type === "whereClause") {
    syncWhereClauseSelection();
  }
  dynamicFunctionTarget.value = target;
  selectedInputSnippet.value = resolveSelectedInputSnippet(target);
  resetDynamicFunctionArgs();
  dynamicFunctionDialogVisible.value = true;
}

function formatDynamicFunctionArg(param: DynamicFunctionParamSchema, rawValue: string | undefined) {
  const trimmed = rawValue?.trim() ?? "";
  if (!trimmed) {
    return param.required === false ? null : "";
  }
  if (param.autoQuote === false) {
    return trimmed;
  }
  if (trimmed.startsWith("'")
    || trimmed.startsWith('"')
    || trimmed.startsWith("$")
    || trimmed.startsWith("${")) {
    return trimmed;
  }
  return `'${trimmed}'`;
}

function buildDynamicFunctionExpression() {
  const args = selectedDynamicFunction.value.params
    .map((param) => formatDynamicFunctionArg(param, dynamicFunctionArgs[param.key]))
    .filter((value): value is string => value !== null);
  return `$${selectedDynamicFunction.value.name}(${args.join(", ")})`;
}

async function insertIntoBindingValue(key: string, content: string) {
  const currentValue = resolveBindingValue(key);
  const range = resolveInputSelectionRange({ type: "binding", key });
  const nextValue = `${currentValue.slice(0, range.start)}${content}${currentValue.slice(range.end)}`;
  const row = form.parameterBindings.find((item) => item.paramName === key);
  if (!row) {
    return;
  }
  row.paramValue = nextValue;
  const nextCursor = range.start + content.length;
  bindingSelections.value = {
    ...bindingSelections.value,
    [key]: { start: nextCursor, end: nextCursor },
  };
  await nextTick();
  const input = bindingInputRefs.get(key);
  input?.focus();
  input?.setSelectionRange(nextCursor, nextCursor);
}

async function insertIntoWhereClause(content: string) {
  const range = resolveInputSelectionRange({ type: "whereClause" });
  form.whereClause = `${form.whereClause.slice(0, range.start)}${content}${form.whereClause.slice(range.end)}`;
  const nextCursor = range.start + content.length;
  whereClauseSelection.value = { start: nextCursor, end: nextCursor };
  await nextTick();
  whereClauseTextareaRef.value?.focus();
  whereClauseTextareaRef.value?.setSelectionRange(nextCursor, nextCursor);
}

async function confirmDynamicFunctionInsert() {
  const target = dynamicFunctionTarget.value;
  if (!target) {
    return;
  }
  const missingParam = selectedDynamicFunction.value.params.find((param) =>
    param.required !== false && !(dynamicFunctionArgs[param.key] ?? "").trim());
  if (missingParam) {
    ElMessage.warning(`请先填写${missingParam.label}`);
    return;
  }
  const expression = buildDynamicFunctionExpression();
  if (target.type === "whereClause") {
    await insertIntoWhereClause(expression);
  } else {
    await insertIntoBindingValue(target.key, expression);
  }
  dynamicFunctionDialogVisible.value = false;
}

async function handleGranularityChange(value: QualityRuleGranularity) {
  form.granularity = value;
  if (value !== "COLUMN") {
    form.columnName = "";
  }
  clearRuleSelection();
  await loadRuleOptions();
}

async function handleRuleDimensionChange() {
  clearRuleSelection();
  await loadRuleOptions();
}

function handleRuleChange(value: string) {
  form.ruleId = value;
  selectedRuleDetail.value = ruleOptions.value.find((item) => String(item.id ?? "") === String(value)) ?? null;
  outputParams.value = (selectedRuleDetail.value?.outputParams ?? []).map((item) => ({ ...item }));
  syncParameterBindings(form.parameterBindings);
  syncAlertConfigs(form.alertConfigs);
  previewWarnings.value = [];
  validationWarnings.value = [];
  validationResult.value = null;
  form.resolvedSqlPreview = "";
}

function clearRuleSelection() {
  form.ruleId = "";
  selectedRuleDetail.value = null;
  outputParams.value = [];
  form.parameterBindings = [];
  form.alertConfigs = [];
  form.resolvedSqlPreview = "";
  previewWarnings.value = [];
  validationWarnings.value = [];
  validationResult.value = null;
}

function syncParameterBindings(existing: QualityTaskParamBinding[] = form.parameterBindings) {
  const rule = selectedRuleDetail.value;
  if (!rule) {
    form.parameterBindings = [];
    return;
  }
  const existingMap = new Map<string, QualityTaskParamBinding>();
  existing.forEach((item) => {
    if (item?.paramName) {
      existingMap.set(item.paramName, item);
    }
  });
  const modelPhysicalLocator = currentModel.value?.physicalLocator || "";
  form.parameterBindings = (rule.inputParams ?? []).map((item) => {
    const current = existingMap.get(item.paramName);
    const binding: QualityTaskParamBinding = {
      paramOrder: item.paramOrder,
      paramName: item.paramName,
      paramType: item.paramType,
      paramMeaning: item.paramMeaning,
      editable: item.paramType === "CUSTOM",
    };
    if (item.paramType === "TABLE") {
      binding.paramValue = modelPhysicalLocator;
      binding.editable = false;
    } else if (item.paramType === "COLUMN") {
      binding.paramValue = form.columnName || "";
      binding.editable = false;
    } else {
      binding.paramValue = current?.paramValue ?? "";
      binding.editable = true;
    }
    return binding;
  });
}

function syncAlertConfigs(existing: QualityTaskAlertConfig[] = form.alertConfigs) {
  const existingMap = new Map<string, QualityTaskAlertConfig>();
  existing.forEach((item) => {
    existingMap.set(alertConfigKey(item.outputOrder, item.resultField), item);
  });
  form.alertConfigs = outputParams.value.map((item) => {
    const current = existingMap.get(alertConfigKey(item.outputOrder, item.resultField));
    const alertConfig: QualityTaskAlertConfig = {
      outputOrder: item.outputOrder,
      resultField: item.resultField,
      outputType: item.outputType,
      enabled: Boolean(current?.enabled),
      operator: current?.operator,
      expectedValue: current?.expectedValue,
      minValue: current?.minValue,
      maxValue: current?.maxValue,
    };
    normalizeAlertConfig(alertConfig);
    return alertConfig;
  });
}

function alertConfigKey(outputOrder?: number, resultField?: string) {
  return `${outputOrder ?? ""}:${resultField ?? ""}`;
}

function normalizeAlertConfig(alertConfig: QualityTaskAlertConfig) {
  const operatorOptions = resolveAlertOperators(alertConfig.outputType);
  if (alertConfig.operator && !operatorOptions.some((item) => item.value === alertConfig.operator)) {
    alertConfig.operator = undefined;
  }
  if (isRangeOperator(alertConfig.operator)) {
    alertConfig.expectedValue = undefined;
  } else {
    alertConfig.minValue = undefined;
    alertConfig.maxValue = undefined;
  }
}

function resolveParamTypeLabel(type?: QualityRuleParamType) {
  if (type === "TABLE") {
    return "数据表";
  }
  if (type === "COLUMN") {
    return "字段";
  }
  return "自定义参数";
}

function resolveDimensionLabel(value?: string) {
  return dimensionOptions.find((item) => item.value === value)?.label ?? value ?? "-";
}

function resolveSupportedDatasourceTypesLabel(rule?: QualityRuleView | null) {
  const items = rule?.supportedDatasourceTypes ?? [];
  return items.length ? items.join(", ") : "全部支持";
}

function resolveAlertOperators(outputType?: QualityRuleOutputType) {
  if (outputType === "STRING") {
    return singleValueOperators.filter((item) => item.value === "EQ" || item.value === "NE");
  }
  return [...singleValueOperators, ...rangeOperators];
}

function isRangeOperator(operator?: QualityTaskAlertOperator) {
  return operator === "LT_R_LT" || operator === "LT_R_LE" || operator === "LE_R_LT" || operator === "LE_R_LE";
}

function handleAlertOperatorChange(row: QualityTaskAlertConfig) {
  normalizeAlertConfig(row);
}

function buildSavePayload() {
  if (!form.taskName.trim()) {
    ElMessage.warning("请先填写任务名称");
    return null;
  }
  if (!form.taskCode.trim()) {
    ElMessage.warning("请先填写任务编码");
    return null;
  }
  if (!form.datasourceId) {
    ElMessage.warning("请选择数据源");
    return null;
  }
  if (!form.modelId) {
    ElMessage.warning("请选择模型");
    return null;
  }
  if (form.granularity === "COLUMN" && !form.columnName.trim()) {
    ElMessage.warning("字段级任务必须选择字段");
    return null;
  }
  if (!form.ruleId) {
    ElMessage.warning("请选择质量规则");
    return null;
  }
  return {
    id: form.id,
    taskName: form.taskName.trim(),
    taskCode: form.taskCode.trim(),
    ruleId: form.ruleId,
    granularity: form.granularity,
    datasourceId: form.datasourceId,
    modelId: form.modelId,
    columnName: form.granularity === "COLUMN" ? form.columnName.trim() : undefined,
    whereClause: form.whereClause.trim() || undefined,
    resolvedSqlPreview: form.resolvedSqlPreview.trim() || undefined,
    parameterBindings: form.parameterBindings.map((item) => ({
      paramOrder: item.paramOrder,
      paramName: item.paramName,
      paramType: item.paramType,
      paramMeaning: item.paramMeaning,
      paramValue: item.paramValue?.trim() || undefined,
      editable: item.editable,
    })),
    alertConfigs: form.alertConfigs.map((item) => ({
      outputOrder: item.outputOrder,
      resultField: item.resultField,
      outputType: item.outputType,
      enabled: Boolean(item.enabled),
      operator: item.operator,
      expectedValue: item.expectedValue?.trim() || undefined,
      minValue: item.minValue?.trim() || undefined,
      maxValue: item.maxValue?.trim() || undefined,
    })),
    schedule: {
      enabled: Boolean(form.schedule.enabled),
      cronExpression: form.schedule.cronExpression.trim() || undefined,
      timezone: form.schedule.timezone.trim() || undefined,
    },
  } satisfies QualityTaskSaveRequest;
}

async function previewSql() {
  const payload = buildSavePayload();
  if (!payload) {
    return;
  }
  previewLoading.value = true;
  try {
    const result = await studioApi.qualityTasks.preview(payload);
    form.resolvedSqlPreview = result.resolvedSql ?? "";
    previewWarnings.value = result.warnings ?? [];
    ElMessage.success("SQL 预览完成");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "SQL 预览失败");
  } finally {
    previewLoading.value = false;
  }
}

async function validateTask() {
  const payload = buildSavePayload();
  if (!payload) {
    return;
  }
  validateLoading.value = true;
  try {
    const result = await studioApi.qualityTasks.validate(payload);
    validationResult.value = result;
    validationWarnings.value = result.warnings ?? [];
    form.resolvedSqlPreview = result.resolvedSql ?? form.resolvedSqlPreview;
    if (result.outputParams?.length) {
      outputParams.value = result.outputParams.map((item) => ({ ...item }));
      syncAlertConfigs(form.alertConfigs);
    }
    ElMessage[result.valid ? "success" : "warning"](result.message || (result.valid ? "语义校验通过" : "语义校验失败"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "语义校验失败");
  } finally {
    validateLoading.value = false;
  }
}

async function saveTask() {
  const saved = await saveTaskInternal();
  if (saved?.id) {
    await router.replace(`/quality-tasks/${saved.id}/edit`);
    await loadTask();
  }
}

async function saveTaskInternal() {
  const payload = buildSavePayload();
  if (!payload) {
    return null;
  }
  saving.value = true;
  try {
    const saved = await studioApi.qualityTasks.save(payload);
    ElMessage.success("质量任务保存成功");
    form.id = saved.id;
    taskStatus.value = saved.status ?? "DRAFT";
    return saved;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存质量任务失败");
    return null;
  } finally {
    saving.value = false;
  }
}

async function publishTask() {
  let currentId = form.id;
  if (!currentId) {
    const saved = await saveTaskInternal();
    currentId = saved?.id;
  }
  if (!currentId) {
    return;
  }
  publishing.value = true;
  try {
    await studioApi.qualityTasks.publish(currentId);
    ElMessage.success("质量任务已发布");
    if (String(taskId.value ?? "") !== String(currentId)) {
      await router.replace(`/quality-tasks/${currentId}/edit`);
    }
    await loadTask();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "发布质量任务失败");
  } finally {
    publishing.value = false;
  }
}

async function triggerTask() {
  if (!form.id) {
    ElMessage.warning("请先保存任务后再执行");
    return;
  }
  triggering.value = true;
  try {
    await studioApi.qualityTasks.trigger(form.id);
    ElMessage.success("已触发质量任务执行");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "触发质量任务失败");
  } finally {
    triggering.value = false;
  }
}

function openRunLogs() {
  if (!form.id) {
    return;
  }
  router.push({
    path: "/quality-task-runs",
    query: {
      qualityTaskId: String(form.id),
    },
  });
}

onMounted(loadTask);

watch(selectedDynamicFunctionName, () => {
  if (!dynamicFunctionDialogVisible.value) {
    return;
  }
  resetDynamicFunctionArgs();
});
</script>

<style scoped>
.rule-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.task-where-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--studio-border);
  border-radius: 12px;
  background: #fff;
  color: var(--studio-text);
  font: 13px/1.6 "Cascadia Code", "Consolas", monospace;
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.task-where-textarea:focus {
  border-color: var(--studio-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.task-where-textarea {
  min-height: 108px;
  resize: vertical;
}

.inline-message {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.04);
  color: var(--studio-text-soft);
}

.inline-message.success {
  background: rgba(34, 197, 94, 0.12);
  color: #166534;
}

.inline-message.warning {
  background: rgba(245, 158, 11, 0.12);
  color: #92400e;
}

.inline-message.danger {
  background: rgba(239, 68, 68, 0.12);
  color: #991b1b;
}

</style>
