<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ ruleId ? "编辑质量规则" : "新建质量规则" }}</h3>
        <p>定义规则基础信息、SQL 逻辑模板、输入参数和输出参数，供质量任务复用。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/quality-rules')">返回列表</el-button>
        <el-button plain @click="loadRule">刷新</el-button>
        <el-button v-if="!(detailLoadError && ruleId)" type="primary" :loading="saving" @click="saveRule">保存</el-button>
      </div>
    </div>

    <SectionCard v-if="detailLoadError && ruleId" title="质量规则不可用" description="该质量规则可能已被删除、取消共享，或当前项目无权访问。">
      <el-result
        icon="warning"
        title="质量规则不可用"
        :sub-title="detailLoadError"
      />
    </SectionCard>

    <template v-else>
    <SectionCard title="基础信息" description="规则作用域决定它是系统级公共规则，还是当前项目私有规则。">
      <div class="studio-form-grid">
        <el-form-item label="规则名称">
          <el-input v-model="form.ruleName" placeholder="例如：主键唯一性校验" />
        </el-form-item>
        <el-form-item label="规则编码">
          <el-input v-model="form.ruleCode" placeholder="例如：DQ_UNIQUE_PK" />
        </el-form-item>
        <el-form-item label="规则作用域">
          <el-select v-model="form.scopeType">
            <el-option label="系统级" value="SYSTEM" />
            <el-option label="项目级" value="PROJECT" />
          </el-select>
        </el-form-item>
        <el-form-item label="规则维度">
          <el-select v-model="form.ruleDimension">
            <el-option v-for="option in dimensionOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="规则粒度">
          <el-select v-model="form.granularity">
            <el-option label="表级" value="TABLE" />
            <el-option label="字段级" value="COLUMN" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" inline-prompt active-text="启用" inactive-text="停用" />
        </el-form-item>
      </div>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="3" placeholder="补充说明规则用途、适用场景和注意事项" />
      </el-form-item>
      <el-form-item label="适配数据源类型">
        <el-checkbox-group v-model="form.supportedDatasourceTypes">
          <el-checkbox v-for="option in datasourceTypeOptions" :key="option" :value="option">{{ option }}</el-checkbox>
        </el-checkbox-group>
        <div class="datasource-type-hint">
          <span class="datasource-type-hint__icon">i</span>
          <span>不勾选时表示该规则默认兼容所有支持 SQL 执行的数据源类型。</span>
        </div>
      </el-form-item>
    </SectionCard>

    <SectionCard title="定义逻辑" description="表级支持 ${Schema_Table}，字段级额外支持 ${Column}，自定义参数统一使用 ${paramName}。">
      <div class="section-toolbar">
        <div>
          <strong>SQL 模板</strong>
          <p>支持动态函数和占位符，参数解析会根据 SQL 模板自动刷新输入参数。</p>
        </div>
        <div class="studio-toolbar-actions">
          <el-button plain @click="validateSql">语义校验</el-button>
          <el-button type="primary" plain @click="parseParams">参数解析</el-button>
        </div>
      </div>
      <div class="logic-sql-actions">
        <span class="logic-sql-actions__label">快速插入</span>
        <el-button plain size="small" @click="insertLogicText('${Schema_Table}')">表参数 ${Schema_Table}</el-button>
        <el-button plain size="small" :disabled="form.granularity !== 'COLUMN'" @click="insertLogicText('${Column}')">
          字段参数 ${Column}
        </el-button>
        <el-button type="primary" plain size="small" @click="openDynamicFunctionDialog">动态函数</el-button>
      </div>
      <textarea
        ref="logicSqlTextareaRef"
        v-model="form.logicSql"
        class="logic-sql-textarea"
        placeholder="请输入质量规则 SQL，例如：select count(*) as invalid_count from ${Schema_Table}"
        spellcheck="false"
        @click="syncLogicSelection"
        @focus="syncLogicSelection"
        @keyup="syncLogicSelection"
        @select="syncLogicSelection"
      />
      <div v-if="parseWarnings.length" class="inline-message warning">
        <div v-for="(item, index) in parseWarnings" :key="index">{{ item }}</div>
      </div>
      <div v-if="validateMessage" class="inline-message" :class="validatePassed ? 'success' : 'danger'">
        {{ validateMessage }}
      </div>
    </SectionCard>

    <QualityDynamicFunctionDialog
      v-model="dynamicFunctionDialogVisible"
      v-model:selected-name="selectedDynamicFunctionName"
      :catalog="dynamicFunctionCatalog"
      :selected-function="selectedDynamicFunction"
      :selected-input-snippet="selectedLogicSnippet"
      :args="dynamicFunctionArgs"
      :preview="dynamicFunctionPreview"
      @confirm="confirmDynamicFunctionInsert"
    />

    <SectionCard title="输入参数" description="输入参数会优先按 SQL 模板解析；保留参数删除后如果 SQL 仍存在占位符，保存时会重新生成。">
      <div class="section-toolbar">
        <div>
          <strong>输入定义</strong>
          <p>除自动解析外，也可以手工补充参数含义，便于后续任务填写和理解。</p>
        </div>
        <el-button type="primary" plain @click="appendInputParam">新增输入参数</el-button>
      </div>
      <el-table :data="form.inputParams" border>
        <el-table-column label="序号" width="90" align="center" header-align="center">
          <template #default="{ row }">
            <el-input-number v-model="row.paramOrder" :min="1" class="full-width" />
          </template>
        </el-table-column>
        <el-table-column label="参数名称" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.paramName" />
          </template>
        </el-table-column>
        <el-table-column label="参数类型" min-width="140">
          <template #default="{ row }">
            <el-select v-model="row.paramType">
              <el-option label="字段" value="COLUMN" />
              <el-option label="数据表" value="TABLE" />
              <el-option label="自定义参数" value="CUSTOM" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="参数含义" min-width="220">
          <template #default="{ row }">
            <el-input v-model="row.paramMeaning" />
          </template>
        </el-table-column>
              <el-table-column label="操作" width="90" align="center" header-align="center" fixed="right">
          <template #default="{ $index }">
            <el-button link type="danger" @click="form.inputParams.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>

    <SectionCard title="输出参数" description="如果静态解析不足，可以手工补充输出字段，供任务配置告警时引用。">
      <div class="section-toolbar">
        <div>
          <strong>输出定义</strong>
          <p>推荐在 SELECT 子句中显式使用 AS 别名，便于解析输出字段。</p>
        </div>
        <el-button type="primary" plain @click="appendOutputParam">新增输出参数</el-button>
      </div>
      <el-table :data="form.outputParams" border>
        <el-table-column label="序号" width="90" align="center" header-align="center">
          <template #default="{ row }">
            <el-input-number v-model="row.outputOrder" :min="1" class="full-width" />
          </template>
        </el-table-column>
        <el-table-column label="结果字段" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.resultField" />
          </template>
        </el-table-column>
        <el-table-column label="输出类型" min-width="140">
          <template #default="{ row }">
            <el-select v-model="row.outputType">
              <el-option label="数值" value="NUMBER" />
              <el-option label="字符串" value="STRING" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="输出说明" min-width="220">
          <template #default="{ row }">
            <el-input v-model="row.outputDescription" />
          </template>
        </el-table-column>
              <el-table-column label="操作" width="90" align="center" header-align="center" fixed="right">
          <template #default="{ $index }">
            <el-button link type="danger" @click="form.outputParams.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import type {
  QualityRuleInputParamSaveRequest,
  QualityRuleOutputParamSaveRequest,
  QualityRuleSaveRequest,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import QualityDynamicFunctionDialog from "@/components/quality/QualityDynamicFunctionDialog.vue";
import { dynamicFunctionCatalog, type DynamicFunctionParamSchema } from "@/components/quality/qualityTaskDynamicFunctions";
import { resolveErrorMessage } from "@/composables/useAsyncAction";

const route = useRoute();
const router = useRouter();

const ruleId = computed(() => route.params.ruleId as string | undefined);
const saving = ref(false);
const detailLoadError = ref("");
const parseWarnings = ref<string[]>([]);
const validateMessage = ref("");
const validatePassed = ref(false);
const datasourceTypeOptions = ref<string[]>([]);
const logicSqlTextareaRef = ref<HTMLTextAreaElement | null>(null);
const logicSqlSelection = ref<{ start: number; end: number } | null>(null);
const dynamicFunctionDialogVisible = ref(false);
const selectedDynamicFunctionName = ref("getCurrentTime");
const selectedLogicSnippet = ref("");
const dynamicFunctionArgs = reactive<Record<string, string>>({});

const dimensionOptions = [
  { label: "一致性", value: "CONSISTENCY" },
  { label: "准确性", value: "ACCURACY" },
  { label: "唯一性", value: "UNIQUENESS" },
  { label: "及时性", value: "TIMELINESS" },
  { label: "完整性", value: "COMPLETENESS" },
  { label: "有效性", value: "VALIDITY" },
];

const selectedDynamicFunction = computed(() =>
  dynamicFunctionCatalog.find((item) => item.name === selectedDynamicFunctionName.value) ?? dynamicFunctionCatalog[0],
);

const dynamicFunctionPreview = computed(() => buildDynamicFunctionExpression());

const form = reactive<QualityRuleSaveRequest>({
  ruleName: "",
  ruleCode: "",
  scopeType: "PROJECT",
  ruleDimension: "CONSISTENCY",
  description: "",
  supportedDatasourceTypes: [],
  granularity: "TABLE",
  logicSql: "",
  enabled: true,
  inputParams: [],
  outputParams: [],
});

async function loadRule() {
  await loadDatasourceTypes();
  if (!ruleId.value) {
    detailLoadError.value = "";
    resetForm();
    return;
  }
  try {
    detailLoadError.value = "";
    const detail = await studioApi.qualityRules.get(ruleId.value);
    form.id = detail.id;
    form.ruleName = detail.ruleName;
    form.ruleCode = detail.ruleCode;
    form.scopeType = detail.scopeType ?? "PROJECT";
    form.ruleDimension = detail.ruleDimension ?? "CONSISTENCY";
    form.description = detail.description ?? "";
    form.supportedDatasourceTypes = [...(detail.supportedDatasourceTypes ?? [])];
    form.granularity = detail.granularity ?? "TABLE";
    form.logicSql = detail.logicSql;
    form.enabled = Boolean(detail.enabled);
    form.inputParams = (detail.inputParams ?? []).map((item) => ({
      id: item.id,
      paramOrder: item.paramOrder,
      paramName: item.paramName,
      paramType: item.paramType,
      paramMeaning: item.paramMeaning,
    }));
    form.outputParams = (detail.outputParams ?? []).map((item) => ({
      id: item.id,
      outputOrder: item.outputOrder,
      resultField: item.resultField,
      outputType: item.outputType,
      outputDescription: item.outputDescription,
    }));
  } catch (error) {
    const message = resolveErrorMessage(error, "加载规则详情失败");
    detailLoadError.value = message;
    ElMessage.error(message);
  }
}

async function loadDatasourceTypes() {
  try {
    const types = await studioApi.dataDevelopment.listSqlDatasourceTypes();
    datasourceTypeOptions.value = uniqueDatasourceTypes(types);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载数据源类型失败");
  }
}

function uniqueDatasourceTypes(values: Array<string | null | undefined>) {
  const result: string[] = [];
  const seen = new Set<string>();
  values.forEach((value) => {
    const normalized = value?.trim();
    if (!normalized || seen.has(normalized)) {
      return;
    }
    seen.add(normalized);
    result.push(normalized);
  });
  return result;
}

function resetForm() {
  form.id = undefined;
  form.ruleName = "";
  form.ruleCode = "";
  form.scopeType = "PROJECT";
  form.ruleDimension = "CONSISTENCY";
  form.description = "";
  form.supportedDatasourceTypes = [];
  form.granularity = "TABLE";
  form.logicSql = "";
  form.enabled = true;
  form.inputParams = [];
  form.outputParams = [];
  parseWarnings.value = [];
  validateMessage.value = "";
  logicSqlSelection.value = null;
}

function syncLogicSelection() {
  const textarea = logicSqlTextareaRef.value;
  if (!textarea) {
    return;
  }
  logicSqlSelection.value = {
    start: textarea.selectionStart ?? form.logicSql.length,
    end: textarea.selectionEnd ?? form.logicSql.length,
  };
}

function resolveLogicSelectionRange() {
  const textarea = logicSqlTextareaRef.value;
  if (textarea && document.activeElement === textarea) {
    return {
      start: textarea.selectionStart ?? form.logicSql.length,
      end: textarea.selectionEnd ?? form.logicSql.length,
    };
  }
  return logicSqlSelection.value ?? {
    start: form.logicSql.length,
    end: form.logicSql.length,
  };
}

function resolveSelectedLogicSnippet() {
  const range = resolveLogicSelectionRange();
  return range.end > range.start ? form.logicSql.slice(range.start, range.end) : "";
}

async function insertLogicText(content: string) {
  const textarea = logicSqlTextareaRef.value;
  const activeSelection = textarea && document.activeElement === textarea
    ? {
        start: textarea.selectionStart ?? form.logicSql.length,
        end: textarea.selectionEnd ?? form.logicSql.length,
      }
    : logicSqlSelection.value;
  const start = activeSelection?.start ?? form.logicSql.length;
  const end = activeSelection?.end ?? start;
  form.logicSql = `${form.logicSql.slice(0, start)}${content}${form.logicSql.slice(end)}`;
  const nextCursor = start + content.length;
  logicSqlSelection.value = { start: nextCursor, end: nextCursor };
  await nextTick();
  logicSqlTextareaRef.value?.focus();
  logicSqlTextareaRef.value?.setSelectionRange(nextCursor, nextCursor);
}

function resetDynamicFunctionArgs() {
  const nextValues: Record<string, string> = {};
  selectedDynamicFunction.value.params.forEach((param) => {
    const selectedValue = param.useSelectedSnippet && selectedLogicSnippet.value ? selectedLogicSnippet.value : "";
    nextValues[param.key] = selectedValue || param.defaultValue || "";
  });
  Object.keys(dynamicFunctionArgs).forEach((key) => {
    delete dynamicFunctionArgs[key];
  });
  Object.assign(dynamicFunctionArgs, nextValues);
}

function openDynamicFunctionDialog() {
  syncLogicSelection();
  selectedLogicSnippet.value = resolveSelectedLogicSnippet();
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

async function confirmDynamicFunctionInsert() {
  const missingParam = selectedDynamicFunction.value.params.find((param) =>
    param.required !== false && !(dynamicFunctionArgs[param.key] ?? "").trim());
  if (missingParam) {
    ElMessage.warning(`请先填写${missingParam.label}`);
    return;
  }
  await insertLogicText(buildDynamicFunctionExpression());
  dynamicFunctionDialogVisible.value = false;
}

async function parseParams() {
  try {
    const inputMeaningMap = new Map(form.inputParams.map((item) => [item.paramName, item.paramMeaning ?? ""]));
    const outputDescriptionMap = new Map(form.outputParams.map((item) => [item.resultField, item.outputDescription ?? ""]));
    const result = await studioApi.qualityRules.parse({
      granularity: form.granularity,
      logicSql: form.logicSql,
    });
    form.inputParams = result.inputParams.map((item) => ({
      paramOrder: item.paramOrder,
      paramName: item.paramName,
      paramType: item.paramType,
      paramMeaning: inputMeaningMap.get(item.paramName) ?? item.paramMeaning,
    }));
    if (result.outputParams?.length) {
      form.outputParams = result.outputParams.map((item) => ({
        outputOrder: item.outputOrder,
        resultField: item.resultField,
        outputType: item.outputType,
        outputDescription: outputDescriptionMap.get(item.resultField) ?? item.outputDescription,
      }));
    }
    parseWarnings.value = result.warnings ?? [];
    ElMessage.success("参数解析完成");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "参数解析失败");
  }
}

async function validateSql() {
  try {
    const result = await studioApi.qualityRules.validate({
      granularity: form.granularity,
      logicSql: form.logicSql,
    });
    validatePassed.value = Boolean(result.valid);
    validateMessage.value = result.message ?? (result.valid ? "校验通过" : "校验失败");
    parseWarnings.value = result.warnings ?? [];
    ElMessage[result.valid ? "success" : "warning"](validateMessage.value);
  } catch (error) {
    validatePassed.value = false;
    validateMessage.value = error instanceof Error ? error.message : "语义校验失败";
    ElMessage.error(validateMessage.value);
  }
}

function appendOutputParam() {
  form.outputParams.push({
    outputOrder: form.outputParams.length + 1,
    resultField: "",
    outputType: "STRING",
    outputDescription: "",
  } satisfies QualityRuleOutputParamSaveRequest);
}

function appendInputParam() {
  form.inputParams.push({
    paramOrder: form.inputParams.length + 1,
    paramName: "",
    paramType: "CUSTOM",
    paramMeaning: "",
  } satisfies QualityRuleInputParamSaveRequest);
}

async function saveRule() {
  if (detailLoadError.value && ruleId.value) {
    ElMessage.error(detailLoadError.value);
    return;
  }
  saving.value = true;
  try {
    const payload: QualityRuleSaveRequest = {
      ...form,
      supportedDatasourceTypes: [...form.supportedDatasourceTypes],
      inputParams: form.inputParams.map((item, index) => ({
        id: item.id,
        paramOrder: item.paramOrder ?? index + 1,
        paramName: item.paramName,
        paramType: item.paramType,
        paramMeaning: item.paramMeaning,
      } satisfies QualityRuleInputParamSaveRequest)),
      outputParams: form.outputParams.map((item, index) => ({
        id: item.id,
        outputOrder: item.outputOrder ?? index + 1,
        resultField: item.resultField,
        outputType: item.outputType,
        outputDescription: item.outputDescription,
      } satisfies QualityRuleOutputParamSaveRequest)),
    };
    const saved = await studioApi.qualityRules.save(payload);
    form.id = saved.id;
    ElMessage.success("规则保存成功");
    await router.push(`/quality-rules/${saved.id}/edit`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存规则失败");
  } finally {
    saving.value = false;
  }
}

watch(ruleId, () => {
  void loadRule();
}, { immediate: true });

watch(selectedDynamicFunctionName, () => {
  if (!dynamicFunctionDialogVisible.value) {
    return;
  }
  resetDynamicFunctionArgs();
});
</script>

<style scoped>
.full-width {
  width: 100%;
}

.logic-sql-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.logic-sql-actions__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--studio-text-soft);
}

.datasource-type-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-basis: 100%;
  width: 100%;
  max-width: 100%;
  margin-top: 12px;
  padding: 8px 12px;
  border: 1px solid rgba(37, 99, 235, 0.14);
  border-radius: 10px;
  background: linear-gradient(135deg, rgba(239, 246, 255, 0.9), rgba(248, 250, 252, 0.98));
  color: #475569;
  font-size: 12px;
  line-height: 1.5;
}

.datasource-type-hint__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 18px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(37, 99, 235, 0.12);
  color: var(--studio-primary);
  font-size: 12px;
  font-weight: 700;
  font-style: normal;
}

.logic-sql-textarea {
  width: 100%;
  min-height: 240px;
  padding: 12px 14px;
  border: 1px solid var(--studio-border);
  border-radius: 12px;
  background: #fff;
  color: var(--studio-text);
  font: 13px/1.65 "Cascadia Code", "Consolas", monospace;
  resize: vertical;
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.logic-sql-textarea:focus {
  border-color: var(--studio-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
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
