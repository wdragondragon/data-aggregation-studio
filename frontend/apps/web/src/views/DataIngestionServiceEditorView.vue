<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ form.id ? "编辑数据接入服务" : "新建数据接入服务" }}</h3>
        <p>配置开放请求的字段来源、目标模型和写入映射。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/data-ingestion-services')">返回列表</el-button>
        <el-button type="primary" :loading="saving" @click="saveService">保存</el-button>
        <el-button v-if="form.id" type="success" :loading="publishing" @click="publishService">发布</el-button>
      </div>
    </div>

    <div class="service-wizard">
      <button
        v-for="(step, index) in wizardSteps"
        :key="step.title"
        class="service-wizard-step"
        :class="{ active: activeStep === index, done: activeStep > index }"
        type="button"
        @click="goStep(index)"
      >
        <span class="step-index">{{ index + 1 }}</span>
        <span>
          <strong>{{ step.title }}</strong>
          <small>{{ step.description }}</small>
        </span>
      </button>
    </div>

    <SectionCard v-if="activeStep === 0" title="一、基础信息和写入目标配置" description="配置开放 API 基础信息、写入目标模型和 Writer 参数。">
      <div class="section-toolbar">
        <div>
          <strong>基础信息</strong>
          <p>服务编码会组成开放 API 地址，保存后自动生成 serviceKey。</p>
        </div>
      </div>
      <el-form label-width="120px" class="ingestion-form-grid">
        <el-form-item label="服务名称" required>
          <el-input v-model="form.serviceName" placeholder="例如：客户资料接入" />
        </el-form-item>
        <el-form-item label="服务编码" required>
          <el-input v-model="form.serviceCode" placeholder="customer_ingest" :disabled="Boolean(form.id)" />
        </el-form-item>
        <el-form-item label="批量数据节点">
          <el-input v-model="form.dataNodePath" placeholder="例如：data.items" />
        </el-form-item>
        <el-form-item label="单次最大行数">
          <el-input-number v-model="form.maxBatchSize" :min="1" :max="500" />
        </el-form-item>
        <el-form-item label="访问 Token">
          <el-switch v-model="form.tokenRequired" active-text="需要 Token" inactive-text="免 Token" />
        </el-form-item>
        <el-form-item v-if="!form.tokenRequired" label="默认订阅方">
          <el-input v-model="form.defaultSubscriptionName" placeholder="免 Token 调用" />
        </el-form-item>
      </el-form>
      <el-input :model-value="resolveEndpoint()" readonly placeholder="保存后生成接入地址" />

      <div class="section-toolbar section-toolbar--spaced">
        <div>
          <strong>写入目标配置</strong>
          <p>先选择数据源类型，再选择数据源和目标模型；Writer 参数会按目标插件自动渲染。</p>
        </div>
      </div>
      <el-form label-width="120px" class="ingestion-form-grid">
        <el-form-item label="目标类型">
          <el-radio-group v-model="form.targetType" @change="handleTargetTypeChange">
            <el-radio-button value="DATABASE">数据库表</el-radio-button>
            <el-radio-button value="FILE">文件模型</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="数据源类型" required>
          <el-select v-model="targetDatasourceType" filterable clearable placeholder="选择数据源类型" @change="handleDatasourceTypeChange">
            <el-option
              v-for="item in datasourceTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="数据源" required>
          <el-select v-model="form.datasourceId" filterable clearable :disabled="!targetDatasourceType" placeholder="选择数据源" @change="handleDatasourceChange">
            <el-option v-for="item in filteredDatasources" :key="item.id" :label="`${item.name} / ${item.typeCode}`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型" required>
          <el-select v-model="form.modelId" filterable clearable :disabled="!form.datasourceId" placeholder="选择目标模型" @change="handleModelChange">
            <el-option v-for="item in models" :key="item.id" :label="`${item.name} / ${item.physicalLocator}`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="字段">
          <el-button plain :disabled="!form.datasourceId || !form.modelId" :loading="resolving" @click="resolveFields">解析目标字段</el-button>
        </el-form-item>
      </el-form>
      <div class="writer-options-panel">
        <div class="writer-options-header">
          <div>
            <strong>高级写入参数</strong>
            <p>{{ runtimeSchemaTitle("writer", form.datasourceId) || "选择数据源后加载 Writer 参数表单" }}</p>
          </div>
          <el-tag
            v-if="runtimeSchemaFor('writer', form.datasourceId)"
            :type="runtimeStatusType('writer', form.datasourceId)"
          >
            {{ runtimeStatusLabel("writer", form.datasourceId) }}
          </el-tag>
        </div>

        <div v-if="writerRuntimeLoading" class="writer-options-loading">正在加载 Writer 参数...</div>
        <HttpRequestOptionsEditor
          v-else-if="writerAdvancedFields.length && isHttpWriterTarget()"
          :fields="writerAdvancedFields"
          :model-value="form.writerOptions ?? {}"
          :dynamic-function-fields="writerDynamicFunctionFields()"
          @update:model-value="updateWriterOptions($event)"
        />
        <MetaFormRenderer
          v-else-if="writerAdvancedFields.length"
          :fields="writerAdvancedFields"
          :model-value="form.writerOptions ?? {}"
          :dynamic-function-fields="writerDynamicFunctionFields()"
          @update:model-value="updateWriterOptions($event)"
        />
        <el-alert
          v-else-if="runtimeSchemaFor('writer', form.datasourceId) && !runtimeSchemaFor('writer', form.datasourceId)?.runtimeSupported"
          type="warning"
          :closable="false"
          show-icon
          title="当前 Writer 插件暂不支持运行参数表单"
        />
        <el-alert
          v-else-if="runtimeSchemaFor('writer', form.datasourceId)"
          type="info"
          :closable="false"
          show-icon
          title="当前 Writer 插件没有额外运行参数"
        />
        <el-alert
          v-else-if="!form.datasourceId"
          type="info"
          :closable="false"
          show-icon
          title="选择数据源后会按 Writer 插件自动渲染运行参数"
        />
        <div v-else class="writer-options-fallback">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="未获取到 Writer 参数 schema，可临时使用 JSON 参数覆盖"
          />
          <JsonEditor v-model="writerOptionsText" title="Writer 运行参数" description="支持 writeMode、fileName 等 writer 参数覆盖。" height="180px" />
        </div>
      </div>
    </SectionCard>

    <SectionCard v-if="activeStep === 1" title="二、字段映射配置" description="按字段配置取值位置，可混合使用 JSON Body、Form Body、Query 和 Header。">
      <StudioTableShell min-width="1040px">
        <el-table :data="form.fieldMappings" border>
          <el-table-column label="序号" width="70" align="center">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column label="取值位置" width="150">
            <template #default="{ row }">
              <el-select v-model="row.sourcePosition">
                <el-option v-for="item in sourcePositionOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="请求字段" min-width="180">
            <template #default="{ row }">
              <el-input v-model="row.sourceField" placeholder="支持 a.b 路径" />
            </template>
          </el-table-column>
          <el-table-column label="目标字段" min-width="180">
            <template #default="{ row }">
              <el-input v-model="row.targetField" />
            </template>
          </el-table-column>
          <el-table-column label="类型" width="130">
            <template #default="{ row }">
              <el-select v-model="row.valueType">
                <el-option v-for="item in valueTypes" :key="item" :label="item" :value="item" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="必填" width="90" align="center">
            <template #default="{ row }">
              <el-switch v-model="row.required" />
            </template>
          </el-table-column>
          <el-table-column label="默认值" min-width="150">
            <template #default="{ row }">
              <el-input v-model="row.defaultValue" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeMapping($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </StudioTableShell>
      <div class="mapping-actions">
        <el-button plain @click="addMapping">新增映射</el-button>
      </div>
    </SectionCard>

    <SectionCard v-if="activeStep === 2" title="三、发布调试" description="表单模式按字段映射生成请求样例，原始模式可直接编辑完整请求。">
      <el-alert
        v-if="!form.id"
        class="debug-alert"
        type="info"
        show-icon
        :closable="false"
        title="请先保存服务，保存后会生成接入地址并允许发送调试请求。"
      />
      <div class="debug-toolbar">
        <el-radio-group v-model="debugMode" size="small">
          <el-radio-button value="form">表单模式</el-radio-button>
          <el-radio-button value="raw">原始模式</el-radio-button>
        </el-radio-group>
        <div class="debug-actions">
          <el-button v-if="debugMode === 'form'" plain @click="resetDebugValues">重置样例</el-button>
          <el-button type="primary" :loading="debugging" @click="debugService">发送调试</el-button>
        </div>
      </div>

      <el-alert
        v-if="usesBothJsonBodyAndForm"
        class="debug-alert"
        type="warning"
        show-icon
        :closable="false"
        title="同一个开放 HTTP 请求通常只能选择 JSON Body 或 Form Body；Query 和 Header 可与任一 Body 类型混合。"
      />

      <template v-if="debugMode === 'form'">
        <div v-if="debugFieldGroups.length" class="debug-form-layout">
          <section v-for="group in debugFieldGroups" :key="group.position" class="debug-field-group">
            <div class="debug-field-group__header">
              <strong>{{ group.title }}</strong>
              <span>{{ group.description }}</span>
            </div>
            <el-form label-width="160px" class="debug-field-form">
              <el-form-item v-for="item in group.rows" :key="item.key" :label="item.label" :required="item.required">
                <el-switch
                  v-if="item.valueType === 'BOOLEAN'"
                  :model-value="booleanDebugValue(item.key)"
                  @update:model-value="setDebugValue(item.key, $event)"
                />
                <el-input-number
                  v-else-if="isNumericType(item.valueType)"
                  :model-value="numberDebugValue(item.key)"
                  :controls="false"
                  class="debug-number-input"
                  @update:model-value="setDebugValue(item.key, $event)"
                />
                <el-input
                  v-else-if="isJsonLikeType(item.valueType)"
                  type="textarea"
                  :rows="3"
                  :model-value="stringDebugValue(item.key)"
                  @update:model-value="setDebugValue(item.key, $event)"
                />
                <el-input
                  v-else
                  :model-value="stringDebugValue(item.key)"
                  @update:model-value="setDebugValue(item.key, $event)"
                />
                <span class="debug-field-meta">写入 {{ item.targetField }}</span>
              </el-form-item>
            </el-form>
          </section>
        </div>
        <el-empty v-else description="请先配置字段映射" />
      </template>

      <template v-else>
        <div class="debug-raw-grid">
          <JsonEditor v-model="debugHeaders" title="Headers" height="180px" />
          <JsonEditor v-model="debugQuery" title="Query" height="180px" />
          <JsonEditor v-model="debugForm" title="Form" height="180px" />
          <JsonEditor v-model="debugBody" title="Body" height="220px" />
        </div>
      </template>

      <JsonEditor v-model="debugResult" title="调试结果" readonly height="180px" />
    </SectionCard>

    <div class="wizard-footer">
      <el-button :disabled="activeStep === 0" @click="previousStep">上一步</el-button>
      <el-button v-if="activeStep < wizardSteps.length - 1" type="primary" @click="nextStep">下一步</el-button>
      <el-button v-else type="primary" :loading="saving" @click="saveService">保存服务</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import type {
  DataIngestionFieldMapping,
  DataIngestionRequestFormat,
  DataIngestionServiceSaveRequest,
  DataIngestionServiceView,
  DataIngestionSourcePosition,
  DataModelDefinition,
  DataSourceDefinition,
  DatasourceTypeCapabilityView,
  EntityId,
  FieldValueType,
  MetadataFieldDefinition,
  PluginRuntimeOptionSchemaView,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard, StudioTableShell } from "@studio/ui";
import { resolveDataServiceOpenUrl, studioApi } from "@/api/studio";
import JsonEditor from "@/components/JsonEditor.vue";
import HttpRequestOptionsEditor from "@/components/HttpRequestOptionsEditor.vue";
import {
  fileWriterDatasourceTypes,
  fileWriterDynamicFunctionFields,
  hasConfiguredArrayValue,
  httpWriterDynamicFunctionFields,
  mergeRuntimeDefaults,
  resolveFieldsByModel,
  resolvePrimaryKeyFieldsByModel,
  type RuntimeOptionRole,
} from "@/components/collection-task/collectionTaskEditorSupport";

type DebugMode = "form" | "raw";
type DebugFieldValue = string | number | boolean | null | undefined;

interface SourcePositionOption {
  label: string;
  value: DataIngestionSourcePosition;
}

interface WizardStep {
  title: string;
  description: string;
}

interface DatasourceTypeOption {
  label: string;
  value: string;
}

interface DebugFieldItem {
  key: string;
  label: string;
  targetField: string;
  required: boolean;
  valueType?: FieldValueType;
  mapping: DataIngestionFieldMapping;
}

interface DebugFieldGroup {
  position: DataIngestionSourcePosition;
  title: string;
  description: string;
  rows: DebugFieldItem[];
}

const route = useRoute();
const router = useRouter();
const valueTypes: FieldValueType[] = ["STRING", "BOOLEAN", "INTEGER", "LONG", "DECIMAL", "ARRAY", "OBJECT", "JSON"];
const sourcePositionOptions: SourcePositionOption[] = [
  { label: "JSON Body", value: "BODY" },
  { label: "Form Body", value: "FORM" },
  { label: "Query", value: "QUERY" },
  { label: "Header", value: "HEADER" },
];
const wizardSteps: WizardStep[] = [
  { title: "基础与目标", description: "服务信息、数据源和 Writer 参数" },
  { title: "字段映射", description: "请求字段到目标字段" },
  { title: "发布调试", description: "样例请求和调试结果" },
];
const sourcePositionOrder: DataIngestionSourcePosition[] = ["BODY", "FORM", "QUERY", "HEADER"];
const serviceId = computed(() => route.params.serviceId as EntityId | undefined);
const activeStep = ref(0);
const datasources = ref<DataSourceDefinition[]>([]);
const datasourceTypes = ref<DatasourceTypeCapabilityView[]>([]);
const models = ref<DataModelDefinition[]>([]);
const runtimeSchemaCache = ref<Record<string, PluginRuntimeOptionSchemaView>>({});
const runtimeSchemaLoading = ref<Record<string, boolean>>({});
const saving = ref(false);
const publishing = ref(false);
const resolving = ref(false);
const debugging = ref(false);
const writerOptionsText = ref("{}");
const endpointPath = ref("");
const debugMode = ref<DebugMode>("form");
const debugHeaders = ref("{}");
const debugQuery = ref("{}");
const debugForm = ref("{}");
const debugBody = ref("{\n  \"id\": 1,\n  \"name\": \"demo\"\n}");
const debugResult = ref("{}");
const debugValues = reactive<Record<string, DebugFieldValue>>({});
const targetDatasourceType = ref("");

const form = reactive<DataIngestionServiceSaveRequest>({
  serviceCode: "",
  serviceName: "",
  requestFormat: "JSON",
  payloadMode: "OBJECT",
  dataNodePath: "",
  targetType: "DATABASE",
  datasourceId: undefined,
  modelId: undefined,
  maxBatchSize: 500,
  tokenRequired: true,
  defaultSubscriptionName: "",
  writerOptions: {},
  fieldMappings: [],
});

const targetModel = computed(() =>
  models.value.find((item) => String(item.id) === String(form.modelId ?? "")),
);
const datasourceTypeOptions = computed<DatasourceTypeOption[]>(() => {
  const configuredTypes = new Set(datasources.value.map((item) => item.typeCode).filter(Boolean));
  const options = datasourceTypes.value
    .filter((item) => configuredTypes.has(item.typeCode) && isDatasourceTypeAllowedForTarget(item.typeCode))
    .map((item) => ({
      value: item.typeCode,
      label: item.typeName ? `${item.typeName} / ${item.typeCode}` : item.typeCode,
    }));
  if (options.length) {
    return options;
  }
  return Array.from(configuredTypes)
    .filter((typeCode) => isDatasourceTypeAllowedForTarget(typeCode))
    .sort()
    .map((typeCode) => ({ value: typeCode, label: typeCode }));
});
const filteredDatasources = computed(() => {
  if (!targetDatasourceType.value) {
    return [];
  }
  if (!isDatasourceTypeAllowedForTarget(targetDatasourceType.value)) {
    return [];
  }
  return datasources.value.filter((item) => item.typeCode === targetDatasourceType.value);
});
const targetFieldOptions = computed(() => resolveFieldsByModel(targetModel.value));
const targetPrimaryKeyFields = computed(() => resolvePrimaryKeyFieldsByModel(targetModel.value));
const writerAdvancedFields = computed<MetadataFieldDefinition[]>(() =>
  (runtimeSchemaFor("writer", form.datasourceId)?.fields ?? []).map(enhanceWriterAdvancedField),
);
const writerRuntimeLoading = computed(() => {
  const datasourceType = resolveDatasourceTypeCode(form.datasourceId);
  return Boolean(datasourceType && runtimeSchemaLoading.value[runtimeSchemaKey("writer", datasourceType)]);
});

const debugFieldGroups = computed<DebugFieldGroup[]>(() => {
  const groups = new Map<DataIngestionSourcePosition, DebugFieldItem[]>();
  form.fieldMappings.forEach((mapping, index) => {
    if (!mapping.targetField) {
      return;
    }
    const position = normalizeSourcePosition(mapping.sourcePosition);
    const rows = groups.get(position) ?? [];
    rows.push({
      key: debugFieldKey(mapping, index),
      label: mapping.sourceField?.trim() || mapping.targetField,
      targetField: mapping.targetField,
      required: Boolean(mapping.required),
      valueType: mapping.valueType ?? "STRING",
      mapping,
    });
    groups.set(position, rows);
  });
  return sourcePositionOrder
    .filter((position) => groups.has(position))
    .map((position) => ({
      position,
      title: sourcePositionTitle(position),
      description: sourcePositionDescription(position),
      rows: groups.get(position) ?? [],
    }));
});

const usesBothJsonBodyAndForm = computed(() => {
  const positions = new Set(form.fieldMappings.map((mapping) => normalizeSourcePosition(mapping.sourcePosition)));
  return positions.has("BODY") && positions.has("FORM");
});

onMounted(async () => {
  const [datasourceData, datasourceTypeData] = await Promise.all([
    studioApi.datasources.list(),
    studioApi.catalog.datasourceTypes(),
  ]);
  datasources.value = datasourceData;
  datasourceTypes.value = datasourceTypeData;
  if (serviceId.value) {
    await loadService(serviceId.value);
  }
  if (route.query.debug) {
    activeStep.value = 2;
    syncDebugValues(false);
  }
});

watch(
  () => form.fieldMappings,
  () => syncDebugValues(false),
  { deep: true, immediate: true },
);

async function loadService(id: EntityId) {
  const detail = await studioApi.dataIngestionServices.get(id);
  applyDetail(detail);
  if (detail.datasourceId) {
    await Promise.all([
      loadModels(detail.datasourceId),
      ensureRuntimeSchemaForDatasource("writer", detail.datasourceId),
    ]);
    applyRuntimeDefaultsForWriter();
  }
}

function applyDetail(detail: DataIngestionServiceView) {
  form.id = detail.id;
  form.serviceCode = detail.serviceCode;
  form.serviceName = detail.serviceName;
  form.requestFormat = detail.requestFormat ?? "JSON";
  form.payloadMode = detail.payloadMode ?? "OBJECT";
  form.dataNodePath = detail.dataNodePath ?? "";
  form.targetType = detail.targetType ?? "DATABASE";
  form.datasourceId = detail.datasourceId;
  form.modelId = detail.modelId;
  form.maxBatchSize = detail.maxBatchSize ?? 500;
  form.tokenRequired = detail.tokenRequired !== false;
  form.defaultSubscriptionName = detail.defaultSubscriptionName ?? "";
  form.writerOptions = detail.writerOptions ?? {};
  targetDatasourceType.value = detail.datasourceTypeCode ?? resolveDatasourceTypeCode(detail.datasourceId);
  form.fieldMappings = (detail.fieldMappings ?? []).map((item) => ({
    ...item,
    sourcePosition: normalizeSourcePosition(item.sourcePosition),
    required: Boolean(item.required),
  }));
  endpointPath.value = detail.endpointPath ?? "";
  writerOptionsText.value = JSON.stringify(form.writerOptions ?? {}, null, 2);
  syncDebugValues(false);
}

function handleTargetTypeChange() {
  if (!targetDatasourceType.value || isDatasourceTypeAllowedForTarget(targetDatasourceType.value)) {
    return;
  }
  handleDatasourceTypeChange("");
}

function handleDatasourceTypeChange(value?: string) {
  targetDatasourceType.value = value ?? "";
  form.datasourceId = undefined;
  form.modelId = undefined;
  form.fieldMappings = [];
  models.value = [];
  form.writerOptions = {};
  syncWriterOptionsText();
}

async function handleDatasourceChange(value?: EntityId) {
  form.datasourceId = value;
  form.modelId = undefined;
  form.fieldMappings = [];
  form.writerOptions = {};
  syncWriterOptionsText();
  const datasourceType = resolveDatasourceTypeCode(value);
  if (datasourceType) {
    targetDatasourceType.value = datasourceType;
  }
  if (!value) {
    models.value = [];
    return;
  }
  await Promise.all([
    loadModels(value),
    ensureRuntimeSchemaForDatasource("writer", value),
  ]);
  applyRuntimeDefaultsForWriter();
}

function handleModelChange(value: EntityId) {
  form.modelId = value;
  applyRuntimeDefaultsForWriter();
}

async function loadModels(datasourceId: EntityId) {
  models.value = await studioApi.models.listByDatasource(datasourceId, { pageNo: 1, pageSize: 5000 });
}

async function ensureRuntimeSchemaForDatasource(role: RuntimeOptionRole, datasourceId: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return;
  }
  await ensureRuntimeSchemaForType(role, datasourceType);
}

async function ensureRuntimeSchemaForType(role: RuntimeOptionRole, datasourceType: string) {
  const key = runtimeSchemaKey(role, datasourceType);
  if (runtimeSchemaCache.value[key] || runtimeSchemaLoading.value[key]) {
    return;
  }
  runtimeSchemaLoading.value[key] = true;
  try {
    runtimeSchemaCache.value[key] = await studioApi.catalog.runtimeOptionSchema({ role, datasourceType });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Writer 参数加载失败");
  } finally {
    runtimeSchemaLoading.value[key] = false;
  }
}

function runtimeSchemaFor(role: RuntimeOptionRole, datasourceId: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return undefined;
  }
  return runtimeSchemaCache.value[runtimeSchemaKey(role, datasourceType)];
}

function runtimeSchemaTitle(role: RuntimeOptionRole, datasourceId: unknown) {
  const datasourceType = resolveDatasourceTypeCode(datasourceId);
  if (!datasourceType) {
    return "";
  }
  const schema = runtimeSchemaFor(role, datasourceId);
  return schema?.pluginType ? `${schema.pluginType}${role}` : datasourceType;
}

function runtimeStatusType(role: RuntimeOptionRole, datasourceId: unknown) {
  const schema = runtimeSchemaFor(role, datasourceId);
  if (!schema?.runtimeSupported) {
    return "warning";
  }
  return schema.fields?.length ? "success" : "info";
}

function runtimeStatusLabel(role: RuntimeOptionRole, datasourceId: unknown) {
  const schema = runtimeSchemaFor(role, datasourceId);
  if (!schema?.runtimeSupported) {
    return "未支持";
  }
  return schema.fields?.length ? "已加载" : "无额外参数";
}

function runtimeSchemaKey(role: RuntimeOptionRole, datasourceType: string) {
  return `${role}:${datasourceType}`;
}

function resolveDatasourceTypeCode(datasourceId: unknown) {
  const datasource = datasources.value.find((item) => String(item.id) === String(datasourceId ?? ""));
  return datasource?.typeCode ?? "";
}

function isDatasourceTypeAllowedForTarget(typeCode: string) {
  const category = datasourceTypes.value.find((item) => item.typeCode === typeCode)?.sourceCategory ?? "";
  const fileDatasource = category.toUpperCase() === "FILE_SYSTEM" || fileWriterDatasourceTypes.has(typeCode);
  if (form.targetType === "FILE") {
    return fileDatasource;
  }
  return !fileDatasource;
}

function writerDynamicFunctionFields() {
  const datasourceType = resolveDatasourceTypeCode(form.datasourceId);
  if (fileWriterDatasourceTypes.has(datasourceType)) {
    return fileWriterDynamicFunctionFields;
  }
  if (datasourceType === "http") {
    return httpWriterDynamicFunctionFields;
  }
  return [];
}

function isHttpWriterTarget() {
  return resolveDatasourceTypeCode(form.datasourceId) === "http";
}

function updateWriterOptions(value: Record<string, unknown>) {
  form.writerOptions = value ?? {};
  syncWriterOptionsText();
}

function applyRuntimeDefaultsForWriter() {
  if (!writerAdvancedFields.value.length) {
    return;
  }
  form.writerOptions = mergeTargetPrimaryKeyDefault(
    mergeRuntimeDefaults(form.writerOptions, writerAdvancedFields.value),
  );
  syncWriterOptionsText();
}

function mergeTargetPrimaryKeyDefault(writerOptions: Record<string, unknown>) {
  if (hasConfiguredArrayValue(writerOptions.pkColumn) || !targetPrimaryKeyFields.value.length) {
    return writerOptions;
  }
  return {
    ...writerOptions,
    pkColumn: [...targetPrimaryKeyFields.value],
  };
}

function enhanceWriterAdvancedField(field: MetadataFieldDefinition): MetadataFieldDefinition {
  if (field.fieldKey !== "pkColumn") {
    return field;
  }
  return {
    ...field,
    valueType: "ARRAY",
    componentType: "SELECT",
    options: [...targetFieldOptions.value],
  };
}

function syncWriterOptionsText() {
  writerOptionsText.value = JSON.stringify(form.writerOptions ?? {}, null, 2);
}

async function resolveFields() {
  resolving.value = true;
  try {
    const resolved = await studioApi.dataIngestionServices.resolveFields({
      datasourceId: form.datasourceId,
      modelId: form.modelId,
    });
    form.fieldMappings = resolved.fieldMappings.map((item) => ({
      ...item,
      sourcePosition: normalizeSourcePosition(item.sourcePosition),
      required: Boolean(item.required),
    }));
    ElMessage.success(`已解析 ${resolved.fields.length} 个目标字段`);
  } finally {
    resolving.value = false;
  }
}

function addMapping() {
  form.fieldMappings.push({
    sortOrder: form.fieldMappings.length,
    sourcePosition: "BODY",
    sourceField: "",
    targetField: "",
    valueType: "STRING",
    required: false,
    defaultValue: "",
  });
}

function removeMapping(index: number) {
  form.fieldMappings.splice(index, 1);
}

function nextStep() {
  if (!validateStep(activeStep.value)) {
    return;
  }
  activeStep.value = Math.min(wizardSteps.length - 1, activeStep.value + 1);
  if (activeStep.value === 2) {
    syncDebugValues(false);
  }
}

function previousStep() {
  activeStep.value = Math.max(0, activeStep.value - 1);
}

function goStep(index: number) {
  if (index > activeStep.value) {
    for (let step = activeStep.value; step < index; step += 1) {
      if (!validateStep(step)) {
        return;
      }
    }
  }
  activeStep.value = index;
  if (activeStep.value === 2) {
    syncDebugValues(false);
  }
}

function validateStep(index: number) {
  if (index === 0) {
    if (!form.serviceName.trim() || !form.serviceCode.trim()) {
      ElMessage.warning("请先填写服务名称和服务编码");
      return false;
    }
    if (!targetDatasourceType.value) {
      ElMessage.warning("请先选择数据源类型");
      return false;
    }
    if (!isDatasourceTypeAllowedForTarget(targetDatasourceType.value)) {
      ElMessage.warning("数据源类型与目标类型不匹配");
      return false;
    }
    if (!form.datasourceId) {
      ElMessage.warning("请先选择数据源");
      return false;
    }
    if (!form.modelId) {
      ElMessage.warning("请先选择目标模型");
      return false;
    }
  }
  if (index === 1 && !normalizedMappings().length) {
    ElMessage.warning("请先配置字段映射");
    return false;
  }
  return true;
}

async function saveService() {
  saving.value = true;
  try {
    const fieldMappings = normalizedMappings();
    const saved = await studioApi.dataIngestionServices.save({
      ...form,
      requestFormat: deriveRequestFormat(fieldMappings),
      payloadMode: form.dataNodePath?.trim() ? "ARRAY" : "OBJECT",
      dataNodePath: form.dataNodePath?.trim() || undefined,
      serviceCode: form.serviceCode.trim(),
      serviceName: form.serviceName.trim(),
      defaultSubscriptionName: form.defaultSubscriptionName?.trim() || undefined,
      writerOptions: resolveWriterOptionsForSave(),
      fieldMappings,
    });
    applyDetail(saved);
    ElMessage.success("数据接入服务已保存");
    if (!serviceId.value) {
      await router.replace(`/data-ingestion-services/${saved.id}/edit`);
    }
  } finally {
    saving.value = false;
  }
}

function resolveWriterOptionsForSave() {
  if (writerAdvancedFields.value.length) {
    return normalizeWriterOptions(form.writerOptions ?? {});
  }
  return normalizeWriterOptions(parseJsonObject(writerOptionsText.value, "Writer 运行参数"));
}

function normalizeWriterOptions(writerOptions: Record<string, unknown>) {
  const next = { ...writerOptions };
  if (Array.isArray(next.pkColumn)) {
    const pkColumn = next.pkColumn.map((item) => String(item).trim()).filter(Boolean);
    if (pkColumn.length) {
      next.pkColumn = pkColumn;
    } else {
      delete next.pkColumn;
    }
  } else if (next.pkColumn !== undefined && next.pkColumn !== null) {
    delete next.pkColumn;
  }
  return next;
}

async function publishService() {
  if (!form.id) {
    await saveService();
  }
  if (!form.id) {
    return;
  }
  publishing.value = true;
  try {
    const published = await studioApi.dataIngestionServices.publish(form.id);
    applyDetail(published);
    ElMessage.success("数据接入服务已发布");
  } finally {
    publishing.value = false;
  }
}

async function debugService() {
  if (!form.id) {
    ElMessage.warning("请先保存服务");
    return;
  }
  debugging.value = true;
  try {
    const payload = debugMode.value === "form"
      ? buildDebugPayloadFromForm()
      : {
          headers: parseJsonObject(debugHeaders.value, "Headers"),
          query: parseJsonObject(debugQuery.value, "Query"),
          form: parseJsonObject(debugForm.value, "Form"),
          body: parseJsonValue(debugBody.value, "Body"),
        };
    const result = await studioApi.dataIngestionServices.debug(form.id, payload);
    debugResult.value = JSON.stringify(result, null, 2);
  } catch (error) {
    if (error instanceof Error) {
      ElMessage.error(error.message);
    }
  } finally {
    debugging.value = false;
  }
}

function normalizedMappings() {
  return form.fieldMappings
    .map((item, index): DataIngestionFieldMapping => ({
      ...item,
      sortOrder: index,
      sourcePosition: normalizeSourcePosition(item.sourcePosition),
      sourceField: item.sourceField?.trim() || item.targetField?.trim(),
      targetField: item.targetField?.trim() || "",
      valueType: item.valueType ?? "STRING",
      required: Boolean(item.required),
      defaultValue: item.defaultValue?.trim() || undefined,
    }))
    .filter((item) => item.targetField);
}

function deriveRequestFormat(mappings: DataIngestionFieldMapping[]): DataIngestionRequestFormat {
  if (mappings.some((mapping) => normalizeSourcePosition(mapping.sourcePosition) === "BODY")) {
    return "JSON";
  }
  if (mappings.some((mapping) => normalizeSourcePosition(mapping.sourcePosition) === "FORM")) {
    return "FORM";
  }
  return form.requestFormat ?? "JSON";
}

function normalizeSourcePosition(position?: DataIngestionSourcePosition): DataIngestionSourcePosition {
  return position ?? "BODY";
}

function syncDebugValues(force: boolean) {
  form.fieldMappings.forEach((mapping, index) => {
    const key = debugFieldKey(mapping, index);
    if (force || !(key in debugValues)) {
      debugValues[key] = initialDebugValue(mapping);
    }
  });
}

function resetDebugValues() {
  syncDebugValues(true);
}

function debugFieldKey(mapping: DataIngestionFieldMapping, index: number) {
  return `${index}:${normalizeSourcePosition(mapping.sourcePosition)}:${mapping.targetField || ""}:${mapping.sourceField || ""}`;
}

function initialDebugValue(mapping: DataIngestionFieldMapping): DebugFieldValue {
  if (mapping.defaultValue != null && String(mapping.defaultValue).trim()) {
    return defaultDebugValue(mapping.defaultValue, mapping.valueType);
  }
  if (mapping.valueType === "BOOLEAN") {
    return false;
  }
  if (isNumericType(mapping.valueType)) {
    return null;
  }
  if (mapping.valueType === "ARRAY") {
    return "[]";
  }
  if (mapping.valueType === "OBJECT" || mapping.valueType === "JSON") {
    return "{}";
  }
  return "";
}

function defaultDebugValue(value: string, valueType?: FieldValueType): DebugFieldValue {
  if (valueType === "BOOLEAN") {
    return value === "true";
  }
  if (isNumericType(valueType)) {
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : null;
  }
  return value;
}

function buildDebugPayloadFromForm() {
  const headers: Record<string, unknown> = {};
  const query: Record<string, unknown> = {};
  const formValues: Record<string, unknown> = {};
  const bodyRow: Record<string, unknown> = {};
  let hasBody = false;

  form.fieldMappings.forEach((rawMapping, index) => {
    if (!rawMapping.targetField?.trim()) {
      return;
    }
    const mapping: DataIngestionFieldMapping = {
      ...rawMapping,
      sourcePosition: normalizeSourcePosition(rawMapping.sourcePosition),
      sourceField: rawMapping.sourceField?.trim() || rawMapping.targetField.trim(),
      targetField: rawMapping.targetField.trim(),
      valueType: rawMapping.valueType ?? "STRING",
      required: Boolean(rawMapping.required),
    };
    const sourceField = mapping.sourceField?.trim() || mapping.targetField;
    const key = debugFieldKey(mapping, index);
    const value = parseDebugFieldValue(debugValues[key], mapping);
    if (isBlankDebugValue(value) && !mapping.required) {
      return;
    }
    const position = normalizeSourcePosition(mapping.sourcePosition);
    if (position === "HEADER") {
      headers[sourceField] = value;
      return;
    }
    if (position === "QUERY") {
      query[sourceField] = value;
      return;
    }
    if (position === "FORM") {
      formValues[sourceField] = value;
      return;
    }
    hasBody = true;
    assignPath(bodyRow, sourceField, value);
  });

  return {
    headers,
    query,
    form: formValues,
    body: hasBody ? wrapBodyPayload(bodyRow) : {},
  };
}

function wrapBodyPayload(row: Record<string, unknown>) {
  const path = form.dataNodePath?.trim();
  if (!path) {
    return row;
  }
  const root: Record<string, unknown> = {};
  assignPath(root, path, [row]);
  return root;
}

function assignPath(target: Record<string, unknown>, path: string, value: unknown) {
  const segments = path.split(".").map((segment) => segment.trim()).filter(Boolean);
  if (!segments.length) {
    return;
  }
  let current: Record<string, unknown> = target;
  segments.forEach((segment, index) => {
    if (index === segments.length - 1) {
      current[segment] = value;
      return;
    }
    const next = current[segment];
    if (!next || typeof next !== "object" || Array.isArray(next)) {
      current[segment] = {};
    }
    current = current[segment] as Record<string, unknown>;
  });
}

function parseDebugFieldValue(value: DebugFieldValue, mapping: DataIngestionFieldMapping) {
  if (isBlankDebugValue(value)) {
    return "";
  }
  if (mapping.valueType === "BOOLEAN") {
    return Boolean(value);
  }
  if (isNumericType(mapping.valueType)) {
    const numberValue = Number(value);
    if (!Number.isFinite(numberValue)) {
      throw new Error(`字段 ${mapping.targetField} 必须是数字`);
    }
    return numberValue;
  }
  if (isJsonLikeType(mapping.valueType)) {
    try {
      return JSON.parse(String(value));
    } catch {
      throw new Error(`字段 ${mapping.targetField} 不是合法 JSON`);
    }
  }
  return value;
}

function isBlankDebugValue(value: DebugFieldValue) {
  return value == null || (typeof value === "string" && !value.trim());
}

function setDebugValue(key: string, value: DebugFieldValue) {
  debugValues[key] = value;
}

function stringDebugValue(key: string) {
  const value = debugValues[key];
  return value == null ? "" : String(value);
}

function numberDebugValue(key: string) {
  const value = debugValues[key];
  return typeof value === "number" ? value : null;
}

function booleanDebugValue(key: string) {
  return Boolean(debugValues[key]);
}

function isNumericType(valueType?: FieldValueType) {
  return valueType === "INTEGER" || valueType === "LONG" || valueType === "DECIMAL";
}

function isJsonLikeType(valueType?: FieldValueType) {
  return valueType === "ARRAY" || valueType === "OBJECT" || valueType === "JSON";
}

function sourcePositionTitle(position: DataIngestionSourcePosition) {
  if (position === "FORM") {
    return "Form Body";
  }
  if (position === "QUERY") {
    return "Query 参数";
  }
  if (position === "HEADER") {
    return "Header 参数";
  }
  return "JSON Body";
}

function sourcePositionDescription(position: DataIngestionSourcePosition) {
  if (position === "FORM") {
    return "以表单字段提交，适合 x-www-form-urlencoded。";
  }
  if (position === "QUERY") {
    return "拼接在接入地址后的查询参数。";
  }
  if (position === "HEADER") {
    return "随请求头提交的元数据。";
  }
  return form.dataNodePath?.trim() ? `写入 ${form.dataNodePath.trim()} 节点下的 JSON 数据。` : "写入 JSON 请求体。";
}

function parseJsonObject(value: string, label: string) {
  const parsed = parseJsonValue(value, label);
  if (parsed == null) {
    return {};
  }
  if (typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(`${label} 必须是 JSON 对象`);
  }
  return parsed as Record<string, unknown>;
}

function parseJsonValue(value: string, label: string) {
  const text = value.trim();
  if (!text) {
    return {};
  }
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${label} 不是合法 JSON`);
  }
}

function resolveEndpoint() {
  return resolveDataServiceOpenUrl(endpointPath.value);
}
</script>

<style scoped>
.service-wizard {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.service-wizard-step {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 76px;
  padding: 14px 16px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: #fff;
  color: var(--studio-text-soft);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.service-wizard-step:hover,
.service-wizard-step.active {
  border-color: rgba(37, 99, 235, 0.42);
  box-shadow: 0 12px 28px rgba(37, 99, 235, 0.1);
  transform: translateY(-1px);
}

.service-wizard-step.done {
  border-color: rgba(22, 163, 74, 0.32);
}

.service-wizard-step strong {
  display: block;
  color: var(--studio-text);
  font-size: 14px;
}

.service-wizard-step small {
  display: block;
  margin-top: 4px;
  font-size: 12px;
}

.step-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.08);
  color: var(--studio-primary);
  font-weight: 700;
}

.service-wizard-step.done .step-index {
  background: rgba(22, 163, 74, 0.12);
  color: #15803d;
}

.section-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin: 0 0 12px;
}

.section-toolbar--spaced {
  margin-top: 20px;
}

.section-toolbar p {
  margin: 4px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.ingestion-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(260px, 1fr));
  gap: 2px 20px;
}

.studio-toolbar-actions,
.mapping-actions,
.debug-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.mapping-actions {
  padding-top: 14px;
}

.wizard-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}

.writer-options-panel {
  display: grid;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
}

.writer-options-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.writer-options-header p {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.writer-options-loading {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.writer-options-fallback {
  display: grid;
  gap: 12px;
}

.debug-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.debug-alert {
  margin-bottom: 14px;
}

.debug-form-layout {
  display: grid;
  gap: 16px;
  margin-bottom: 14px;
}

.debug-field-group {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
}

.debug-field-group__header {
  display: grid;
  gap: 4px;
}

.debug-field-group__header span,
.debug-field-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.debug-field-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(280px, 1fr));
  column-gap: 18px;
}

.debug-field-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.debug-field-form :deep(.el-form-item__content) {
  align-items: center;
  gap: 8px;
}

.debug-number-input {
  width: 100%;
}

.debug-raw-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

@media (max-width: 960px) {
  .ingestion-form-grid,
  .debug-field-form,
  .debug-raw-grid {
    grid-template-columns: 1fr;
  }

  .service-wizard {
    grid-template-columns: 1fr;
  }

  .debug-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
