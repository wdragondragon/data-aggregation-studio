<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ serviceId ? "编辑数据接入服务" : "新建数据接入服务" }}</h3>
        <p>配置开放请求的字段来源、目标模型和写入映射。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/data-ingestion-services')">返回列表</el-button>
        <template v-if="detailLoadError && serviceId">
          <el-button type="primary" plain @click="retryLoadService">刷新</el-button>
        </template>
        <template v-else>
          <el-button type="primary" :loading="saving" @click="saveService">保存</el-button>
          <el-button v-if="form.id" type="success" :loading="publishing" @click="publishService">发布</el-button>
        </template>
      </div>
    </div>

    <SectionCard v-if="detailLoadError && serviceId" title="数据接入服务不可用" description="该数据接入服务可能已被删除、取消共享，或当前项目无权访问。">
      <el-result
        icon="warning"
        title="数据接入服务不可用"
        :sub-title="detailLoadError"
      />
    </SectionCard>

    <template v-else>
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

      <el-collapse v-model="webserviceCollapseNames" class="webservice-collapse">
        <el-collapse-item name="webservice">
          <template #title>
            <span class="webservice-title">WebService 设置</span>
          </template>
          <el-form label-width="120px" class="ingestion-form-grid">
            <el-form-item label="启用 WebService">
              <el-switch v-model="form.webserviceEnabled" active-text="启用 SOAP" inactive-text="关闭" @change="handleWebServiceToggle" />
            </el-form-item>
            <el-form-item label="SOAP 版本">
              <el-select v-model="form.webserviceConfig.soapVersion" :disabled="!form.webserviceEnabled">
                <el-option label="SOAP 1.1" value="SOAP_11" />
                <el-option label="SOAP 1.2" value="SOAP_12" />
              </el-select>
            </el-form-item>
            <el-form-item label="SOAP 请求类型">
              <el-input model-value="XML" disabled />
            </el-form-item>
            <el-form-item label="SOAP 响应类型">
              <el-input model-value="XML" disabled />
            </el-form-item>
            <el-form-item label="Namespace">
              <el-input v-model="form.webserviceConfig.namespaceUri" :disabled="!form.webserviceEnabled" placeholder="默认按服务编码生成" />
            </el-form-item>
            <el-form-item label="Operation">
              <el-input v-model="form.webserviceConfig.operationName" :disabled="!form.webserviceEnabled" placeholder="默认使用服务编码" />
            </el-form-item>
            <el-form-item label="SOAP Action">
              <el-input v-model="form.webserviceConfig.soapAction" :disabled="!form.webserviceEnabled" placeholder="默认 namespace/operation" />
            </el-form-item>
            <el-form-item label="请求根节点">
              <el-input v-model="form.webserviceConfig.requestRootName" :disabled="!form.webserviceEnabled" placeholder="默认使用 Operation" />
            </el-form-item>
            <el-form-item label="响应根节点">
              <el-input v-model="form.webserviceConfig.responseRootName" :disabled="!form.webserviceEnabled" placeholder="默认 OperationResponse" />
            </el-form-item>
          </el-form>
          <el-form-item label="WSDL 地址">
            <el-input :model-value="webserviceWsdlUrl" readonly placeholder="保存并启用后可预览 WSDL">
              <template #append>
                <el-button :disabled="!form.id || !form.webserviceEnabled" :loading="webservicePreviewLoading" @click="previewWebService()">预览</el-button>
              </template>
            </el-input>
          </el-form-item>
          <el-input
            v-if="webservicePreview?.wsdl"
            :model-value="webservicePreview.wsdl"
            class="xml-textarea"
            type="textarea"
            :rows="10"
            readonly
            placeholder="点击预览后展示 WSDL"
          />
        </el-collapse-item>
      </el-collapse>

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
          <el-button plain :disabled="!form.datasourceId || !form.modelId || modelDetailLoading" :loading="resolving || modelDetailLoading" @click="resolveFields">解析目标字段</el-button>
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

    <SectionCard v-if="activeStep === 1" title="二、字段映射配置" description="按字段配置取值位置，可混合使用 Body、Form、Query 和 Header；SOAP 模式下 Body 表示 XML 节点路径。">
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

    <OpenServiceDebugPanel
      v-if="activeStep === 2"
      v-model:mode="debugMode"
      :soap-envelope="soapEnvelope"
      :soap-headers="soapDebugHeaders"
      title="三、发布调试"
      description="表单模式按字段映射生成请求样例，原始模式按 Header、Query、Form、Body 分段编辑。"
      disabled-hint="请先保存服务，保存后会生成接入地址并允许发送调试请求。"
      :can-debug="Boolean(form.id)"
      :debugging="debugging || webserviceDebugging"
      :warning-message="usesBothJsonBodyAndForm ? '同一个开放 HTTP 请求通常只能选择 JSON Body 或 Form Body；Query 和 Header 可与任一 Body 类型混合。' : ''"
      endpoint-label="接入地址"
      :endpoint-url="endpointUrl"
      :form-groups="debugPanelGroups"
      :raw-sections="debugRawSections"
      show-curl
      :curl-command="curlCommand"
      :show-soap-mode="form.webserviceEnabled"
      :soap-preview-loading="webservicePreviewLoading"
      :wsdl-url="webserviceWsdlUrl"
      soap-description="SOAP Envelope 是完整 XML 请求 Body；字段路径按 SOAP Body 转换后的节点读取，请求会调用数据接入服务 WebService。"
      :soap-headers-placeholder="'{\n  &quot;X-Data-Ingestion-Token&quot;: &quot;...&quot;\n}'"
      :soap-envelope-error="soapEnvelopeError"
      :soap-headers-mode="soapHeadersMode"
      :soap-headers-error="soapHeadersError"
      :soap-header-rows="soapHeaderRows"
      :soap-field-groups="soapFieldGroups"
      :debug-result="debugResult"
      @update-field-value="setDebugValue"
      @update-soap-field-value="setSoapFieldValue"
      @update-soap-header-value="setSoapHeaderValue"
      @update:soap-envelope="updateSoapEnvelope"
      @update:soap-headers="updateSoapHeaders"
      @update:soap-headers-mode="soapHeadersMode = $event"
      @update-raw-section="updateDebugRawSection"
      @reset-form="resetDebugValues"
      @generate-curl="generateCurlCommand"
      @copy-curl="copyCurlCommand"
      @generate-soap-sample="previewWebService(true)"
      @format-soap-response="formatSoapResponse"
      @debug="debugCurrentMode"
    />

    <div class="wizard-footer">
      <el-button :disabled="activeStep === 0" @click="previousStep">上一步</el-button>
      <el-button v-if="activeStep < wizardSteps.length - 1" type="primary" @click="nextStep">下一步</el-button>
      <el-button v-else type="primary" :loading="saving" @click="saveService">保存服务</el-button>
    </div>
    </template>
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
  DataModelListView,
  DataSourceListView,
  DatasourceTypeCapabilityView,
  EntityId,
  FieldValueType,
  MetadataFieldDefinition,
  PluginRuntimeOptionSchemaView,
  WebServiceConfig,
  WebServicePreviewView,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard, StudioTableShell } from "@studio/ui";
import { resolveDataServiceOpenUrl, studioApi } from "@/api/studio";
import JsonEditor from "@/components/JsonEditor.vue";
import HttpRequestOptionsEditor from "@/components/HttpRequestOptionsEditor.vue";
import OpenServiceDebugPanel from "@/components/open-service/OpenServiceDebugPanel.vue";
import type {
  DebugControlType,
  HeaderEditorMode,
  OpenServiceDebugGroup,
  OpenServiceRawSection,
} from "@/components/open-service/OpenServiceDebugPanel.vue";
import {
  buildSoapEnvelope,
  formatXmlText,
  firstArrayItemOrSelf,
  parseJsonObjectText,
  parseJsonValueText,
  parseSoapEnvelope,
  prettyJsonValue,
  readPath as readDebugPath,
  stringifyDebugValue,
  type DebugObject,
  type SoapFieldSpec,
} from "@/components/open-service/openServiceDebugSupport";
import {
  buildBashCurl,
  buildBashRawBodyCurl,
  buildCmdCurl,
  buildCmdRawBodyCurl,
  copyTextFallback,
  ensureOpenApiHeaders,
} from "@/components/data-service/dataServiceEditorSupport";
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
import { resolveErrorMessage } from "@/composables/useAsyncAction";

type DebugMode = "form" | "raw" | "soap";
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
  { label: "Body 路径", value: "BODY" },
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
const datasources = ref<DataSourceListView[]>([]);
const datasourceTypes = ref<DatasourceTypeCapabilityView[]>([]);
const models = ref<DataModelListView[]>([]);
const modelDetailCache = ref<Record<string, DataModelDefinition>>({});
const modelDetailLoading = ref(false);
const runtimeSchemaCache = ref<Record<string, PluginRuntimeOptionSchemaView>>({});
const runtimeSchemaLoading = ref<Record<string, boolean>>({});
const saving = ref(false);
const publishing = ref(false);
const resolving = ref(false);
const debugging = ref(false);
const webservicePreviewLoading = ref(false);
const webserviceDebugging = ref(false);
const detailLoadError = ref("");
const writerOptionsText = ref("{}");
const endpointPath = ref("");
const debugMode = ref<DebugMode>("form");
const debugHeaders = ref("{}");
const debugQuery = ref("{}");
const debugForm = ref("{}");
const debugBody = ref("{\n  \"id\": 1,\n  \"name\": \"demo\"\n}");
const debugResult = ref("{}");
const curlCommand = ref("");
const debugValues = reactive<Record<string, DebugFieldValue>>({});
const restDebugPayload = reactive<{
  headers: DebugObject;
  query: DebugObject;
  form: DebugObject;
  body: unknown;
}>({
  headers: {},
  query: {},
  form: {},
  body: {},
});
const debugRawErrors = reactive<Record<"headers" | "query" | "form" | "body", string>>({
  headers: "",
  query: "",
  form: "",
  body: "",
});
const targetDatasourceType = ref("");
const webserviceCollapseNames = ref<string[]>([]);
const webservicePreview = ref<WebServicePreviewView | null>(null);
const soapEnvelope = ref("");
const soapDebugHeaders = ref("{}");
const soapEnvelopeError = ref("");
const soapHeadersMode = ref<HeaderEditorMode>("form");
const soapHeadersError = ref("");
const soapHttpHeaders = reactive<DebugObject>({});
const soapFieldValues = reactive<Record<string, DebugFieldValue>>({});
const SOAP_TOKEN_FIELD_KEY = "__soap_header_token";

const form = reactive<DataIngestionServiceSaveRequest & {
  webserviceEnabled: boolean;
  webserviceConfig: WebServiceConfig;
}>({
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
  webserviceEnabled: false,
  webserviceConfig: defaultWebServiceConfig(),
  writerOptions: {},
  fieldMappings: [],
});

const webserviceEndpointPath = computed(() => {
  if (webservicePreview.value?.endpointPath) {
    return webservicePreview.value.endpointPath;
  }
  if (!form.serviceCode || !endpointPath.value) {
    return "";
  }
  const serviceKey = endpointPath.value.split("/").filter(Boolean).pop();
  if (!serviceKey) {
    return "";
  }
  return `/openapi/ws/data-ingestion-services/${form.serviceCode}/${serviceKey}`;
});

const webserviceWsdlUrl = computed(() => {
  const path = webservicePreview.value?.wsdlPath
    || (webserviceEndpointPath.value ? `${webserviceEndpointPath.value}?wsdl` : "");
  return resolveDataServiceOpenUrl(path);
});
const webserviceInvokeUrl = computed(() => {
  if (!webserviceEndpointPath.value) {
    return "";
  }
  return resolveDataServiceOpenUrl(webserviceEndpointPath.value);
});
const endpointUrl = computed(() => resolveEndpoint());

const soapHeaderRows = computed(() => [
  {
    key: "X-Data-Ingestion-Token",
    label: "X-Data-Ingestion-Token",
    meta: "HTTP Header Token；也可使用 SOAP Header token",
    required: Boolean(form.tokenRequired),
    readonly: true,
    controlType: "text" as DebugControlType,
    value: soapHttpHeaders["X-Data-Ingestion-Token"] as DebugFieldValue,
  },
]);

const targetModel = computed(() =>
  modelDetailCache.value[String(form.modelId ?? "")],
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

const debugPanelGroups = computed<OpenServiceDebugGroup[]>(() =>
  debugFieldGroups.value.map((group) => ({
    key: group.position,
    title: group.title,
    description: group.description,
    rows: group.rows.map((row) => ({
      key: row.key,
      label: row.label,
      meta: `写入 ${row.targetField}`,
      required: row.required,
      controlType: debugControlType(row.valueType),
      value: debugValues[row.key],
    })),
  })),
);

const soapFieldGroups = computed<OpenServiceDebugGroup[]>(() => {
  const headerRows = debugFieldGroups.value
    .filter((group) => group.position === "HEADER")
    .flatMap((group) => group.rows.map((row) => ({
      key: row.key,
      label: row.label,
      meta: `SOAP Header / 写入 ${row.targetField}`,
      required: row.required,
      controlType: debugControlType(row.valueType),
      value: soapFieldValues[row.key],
    })));
  const bodyRows = debugFieldGroups.value
    .filter((group) => group.position !== "HEADER")
    .flatMap((group) => group.rows.map((row) => ({
      key: row.key,
      label: row.label,
      meta: `${sourcePositionTitle(group.position)} / 写入 ${row.targetField}`,
      required: row.required,
      controlType: debugControlType(row.valueType),
      value: soapFieldValues[row.key],
    })));
  const groups: OpenServiceDebugGroup[] = [
    {
      key: "soap-header",
      title: "SOAP Header",
      description: "会写入 Envelope Header；Token 可使用 token / dataIngestionToken。",
      rows: [
        {
          key: SOAP_TOKEN_FIELD_KEY,
          label: "token",
          meta: "SOAP Header token",
          required: Boolean(form.tokenRequired),
          controlType: "text",
          value: soapFieldValues[SOAP_TOKEN_FIELD_KEY],
        },
        ...headerRows,
      ],
    },
  ];
  if (bodyRows.length) {
    groups.push({
      key: "soap-body",
      title: "SOAP Body 参数",
      description: "会写入当前 WebService 操作节点；存在 dataNodePath 时会自动包装到对应节点。",
      rows: bodyRows,
    });
  }
  return groups;
});

const debugRawSections = computed<OpenServiceRawSection[]>(() => [
  {
    key: "headers",
    title: "Header 参数",
    description: "HTTP 请求头 JSON 对象。",
    value: debugHeaders.value,
    placeholder: "{\n  \"X-Trace-Id\": \"debug-001\"\n}",
    rows: 5,
    parseError: debugRawErrors.headers,
  },
  {
    key: "query",
    title: "Query 参数",
    description: "拼接在接入地址后的查询参数 JSON 对象。",
    value: debugQuery.value,
    placeholder: "{\n  \"source\": \"demo\"\n}",
    rows: 5,
    parseError: debugRawErrors.query,
  },
  {
    key: "form",
    title: "Form Body",
    description: "以 x-www-form-urlencoded 提交的表单字段 JSON 对象。",
    value: debugForm.value,
    placeholder: "{\n  \"name\": \"demo\"\n}",
    rows: 5,
    parseError: debugRawErrors.form,
  },
  {
    key: "body",
    title: "Body 参数",
    description: "JSON Body 原文；可填写对象、数组或 dataNodePath 指向的数据结构。",
    value: debugBody.value,
    placeholder: "{\n  \"id\": 1,\n  \"name\": \"demo\"\n}",
    rows: 8,
    parseError: debugRawErrors.body,
  },
]);

const usesBothJsonBodyAndForm = computed(() => {
  const positions = new Set(form.fieldMappings.map((mapping) => normalizeSourcePosition(mapping.sourcePosition)));
  return positions.has("BODY") && positions.has("FORM");
});

function defaultWebServiceConfig(config?: WebServiceConfig, enabled = false): WebServiceConfig {
  return {
    enabled,
    soapVersion: config?.soapVersion || "SOAP_11",
    namespaceUri: config?.namespaceUri || "",
    operationName: config?.operationName || "",
    soapAction: config?.soapAction || "",
    requestRootName: config?.requestRootName || "",
    responseRootName: config?.responseRootName || "",
  };
}

onMounted(async () => {
  const [datasourceData, datasourceTypeData] = await Promise.all([
    studioApi.datasources.list(),
    studioApi.catalog.datasourceTypes(),
  ]);
  datasources.value = datasourceData;
  datasourceTypes.value = datasourceTypeData;
  if (serviceId.value) {
    const loaded = await loadService(serviceId.value);
    if (!loaded) {
      return;
    }
  }
  if (route.query.debug) {
    activeStep.value = 2;
    syncDebugValues(false);
    syncRestPayloadFromFields();
    syncSoapValues(false);
  }
});

watch(
  () => form.fieldMappings,
  () => {
    syncDebugValues(false);
    syncSoapValues(false);
    syncRestPayloadFromFields();
    if (form.webserviceEnabled && (!soapEnvelope.value.trim() || !soapEnvelopeError.value)) {
      rebuildSoapEnvelopeFromFields();
    }
  },
  { deep: true, immediate: true },
);

watch(
  () => form.webserviceEnabled,
  (enabled) => {
    if (enabled) {
      debugMode.value = "soap";
      return;
    }
    if (debugMode.value === "soap") {
      debugMode.value = "form";
    }
    soapEnvelope.value = "";
    soapEnvelopeError.value = "";
  },
);

async function loadService(id: EntityId) {
  try {
    detailLoadError.value = "";
    const detail = await studioApi.dataIngestionServices.get(id);
    applyDetail(detail);
    if (detail.datasourceId) {
      await Promise.all([
        loadModels(detail.datasourceId),
        detail.modelId ? loadModelDetail(detail.modelId) : Promise.resolve(),
        ensureRuntimeSchemaForDatasource("writer", detail.datasourceId),
      ]);
      ensureSelectedModelOption();
      applyRuntimeDefaultsForWriter();
    }
    return true;
  } catch (error) {
    const message = resolveErrorMessage(error, "加载数据接入服务失败");
    detailLoadError.value = message;
    ElMessage.error(message);
    return false;
  }
}

async function retryLoadService() {
  if (!serviceId.value) {
    return;
  }
  await loadService(serviceId.value);
}

function applyDetail(detail: DataIngestionServiceView) {
  form.id = detail.id;
  form.serviceCode = detail.serviceCode;
  form.serviceName = detail.serviceName;
  form.payloadMode = detail.payloadMode ?? "OBJECT";
  form.dataNodePath = detail.dataNodePath ?? "";
  form.targetType = detail.targetType ?? "DATABASE";
  form.datasourceId = detail.datasourceId;
  form.modelId = detail.modelId;
  form.maxBatchSize = detail.maxBatchSize ?? 500;
  form.tokenRequired = detail.tokenRequired !== false;
  form.defaultSubscriptionName = detail.defaultSubscriptionName ?? "";
  form.webserviceEnabled = Boolean(detail.webserviceEnabled);
  form.requestFormat = form.webserviceEnabled ? "SOAP" : detail.requestFormat ?? "JSON";
  form.webserviceConfig = defaultWebServiceConfig(detail.webserviceConfig, form.webserviceEnabled);
  form.writerOptions = detail.writerOptions ?? {};
  targetDatasourceType.value = detail.datasourceTypeCode ?? resolveDatasourceTypeCode(detail.datasourceId);
  form.fieldMappings = (detail.fieldMappings ?? []).map((item) => ({
    ...item,
    sourcePosition: normalizeSourcePosition(item.sourcePosition),
    required: Boolean(item.required),
  }));
  endpointPath.value = detail.endpointPath ?? "";
  webservicePreview.value = null;
  if (!form.webserviceEnabled) {
    soapEnvelope.value = "";
  }
  writerOptionsText.value = JSON.stringify(form.writerOptions ?? {}, null, 2);
  syncDebugValues(false);
  syncRestPayloadFromFields();
  syncSoapValues(false);
  if (form.webserviceEnabled && (!soapEnvelope.value.trim() || !soapEnvelopeError.value)) {
    rebuildSoapEnvelopeFromFields();
  }
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

async function handleModelChange(value?: EntityId) {
  form.modelId = value;
  await loadModelDetail(value);
  applyRuntimeDefaultsForWriter();
}

async function loadModels(datasourceId: EntityId) {
  models.value = await studioApi.models.listSummariesByDatasource(datasourceId, { pageNo: 1, pageSize: 5000 });
  ensureSelectedModelOption();
}

async function loadModelDetail(modelId?: EntityId) {
  const key = String(modelId ?? "");
  if (!key || modelDetailCache.value[key]) {
    return;
  }
  modelDetailLoading.value = true;
  try {
    const detail = await studioApi.models.get(modelId as EntityId);
    modelDetailCache.value = {
      ...modelDetailCache.value,
      [key]: detail,
    };
    ensureSelectedModelOption();
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, "加载目标模型详情失败"));
  } finally {
    modelDetailLoading.value = false;
  }
}

function ensureSelectedModelOption() {
  const detail = modelDetailCache.value[String(form.modelId ?? "")];
  if (!detail || !form.datasourceId || String(detail.datasourceId) !== String(form.datasourceId)) {
    return;
  }
  if (models.value.some((item) => String(item.id) === String(detail.id))) {
    return;
  }
  models.value = [
    ...models.value,
    toModelListView(detail),
  ];
}

function toModelListView(model: DataModelDefinition): DataModelListView {
  return {
    id: model.id,
    tenantId: model.tenantId,
    projectId: model.projectId,
    deleted: model.deleted,
    createdAt: model.createdAt,
    updatedAt: model.updatedAt,
    datasourceId: model.datasourceId,
    name: model.name,
    modelKind: model.modelKind,
    physicalLocator: model.physicalLocator,
    schemaVersionId: model.schemaVersionId,
  };
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
    syncRestPayloadFromFields();
    syncSoapValues(false);
    if (form.webserviceEnabled && (!soapEnvelope.value.trim() || !soapEnvelopeError.value)) {
      rebuildSoapEnvelopeFromFields();
    }
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
    syncRestPayloadFromFields();
    syncSoapValues(false);
    if (form.webserviceEnabled && (!soapEnvelope.value.trim() || !soapEnvelopeError.value)) {
      rebuildSoapEnvelopeFromFields();
    }
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
  if (detailLoadError.value && serviceId.value) {
    ElMessage.error(detailLoadError.value);
    return;
  }
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
      webserviceEnabled: Boolean(form.webserviceEnabled),
      webserviceConfig: normalizeWebServiceConfigForSave(),
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

function normalizeWebServiceConfigForSave(): WebServiceConfig {
  const config = defaultWebServiceConfig(form.webserviceConfig, form.webserviceEnabled);
  return {
    enabled: Boolean(form.webserviceEnabled),
    soapVersion: config.soapVersion || "SOAP_11",
    namespaceUri: config.namespaceUri?.trim() || undefined,
    operationName: config.operationName?.trim() || undefined,
    soapAction: config.soapAction?.trim() || undefined,
    requestRootName: config.requestRootName?.trim() || undefined,
    responseRootName: config.responseRootName?.trim() || undefined,
  };
}

function handleWebServiceToggle() {
  form.webserviceConfig = defaultWebServiceConfig(form.webserviceConfig, form.webserviceEnabled);
  form.requestFormat = form.webserviceEnabled ? "SOAP" : deriveRequestFormat(normalizedMappings());
  webservicePreview.value = null;
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
  if (detailLoadError.value && serviceId.value) {
    ElMessage.error(detailLoadError.value);
    return;
  }
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
    const payload = buildCurrentRestDebugPayload();
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

async function debugCurrentMode() {
  if (debugMode.value === "soap") {
    await debugWebService();
    return;
  }
  await debugService();
}

function buildCurrentRestDebugPayload() {
  if (debugMode.value === "form") {
    return buildDebugPayloadFromForm();
  }
  const invalidSection = Object.values(debugRawErrors).find(Boolean);
  if (invalidSection) {
    throw new Error(invalidSection);
  }
  return {
    headers: parseJsonObject(debugHeaders.value, "Headers"),
    query: parseJsonObject(debugQuery.value, "Query"),
    form: parseJsonObject(debugForm.value, "Form"),
    body: parseJsonValue(debugBody.value, "Body"),
  };
}

function updateDebugRawSection(key: string, value: string) {
  if (key === "headers") {
    debugHeaders.value = value;
    updateRawObjectSection("headers", value, "Headers");
  } else if (key === "query") {
    debugQuery.value = value;
    updateRawObjectSection("query", value, "Query");
  } else if (key === "form") {
    debugForm.value = value;
    updateRawObjectSection("form", value, "Form");
  } else if (key === "body") {
    debugBody.value = value;
    updateRawBodySection(value);
  }
  curlCommand.value = "";
}

async function generateCurlCommand(mode: "bash" | "cmd") {
  if (debugMode.value === "soap") {
    await generateSoapCurlCommand(mode);
    return;
  }
  if (!endpointUrl.value) {
    ElMessage.warning("请先保存服务生成接入地址");
    return;
  }
  try {
    const payload = buildCurrentRestDebugPayload();
    const headers = ensureOpenApiHeaders(payload.headers, "X-Data-Ingestion-Token");
    curlCommand.value = mode === "bash"
      ? buildBashCurl(endpointUrl.value, "POST", headers, payload.query, payload.body, payload.form)
      : buildCmdCurl(endpointUrl.value, "POST", headers, payload.query, payload.body, payload.form);
    ElMessage.success(`已生成 cURL(${mode === "bash" ? "bash" : "cmd"})`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "生成 cURL 失败");
  }
}

async function generateSoapCurlCommand(mode: "bash" | "cmd") {
  if (!form.webserviceEnabled || !webserviceInvokeUrl.value) {
    ElMessage.warning("请先启用 WebService 并保存服务");
    return;
  }
  try {
    if (!soapEnvelope.value.trim()) {
      await previewWebService(true);
    }
    if (soapEnvelopeError.value) {
      throw new Error(soapEnvelopeError.value);
    }
    if (!soapEnvelope.value.trim()) {
      throw new Error("SOAP Envelope 不能为空");
    }
    const headers = buildSoapCurlHeaders();
    const contentType = resolveSoapContentType();
    curlCommand.value = mode === "bash"
      ? buildBashRawBodyCurl(webserviceInvokeUrl.value, "POST", headers, soapEnvelope.value, contentType)
      : buildCmdRawBodyCurl(webserviceInvokeUrl.value, "POST", headers, soapEnvelope.value, contentType);
    ElMessage.success(`已生成 SOAP cURL(${mode === "bash" ? "bash" : "cmd"})`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "生成 SOAP cURL 失败");
  }
}

function buildSoapCurlHeaders() {
  const headers: Record<string, unknown> = { ...currentSoapHttpHeaders() };
  setHeaderIfMissing(headers, "Accept", "text/xml");
  if (form.tokenRequired) {
    setHeaderIfMissing(headers, "X-Data-Ingestion-Token", "<订阅Token>");
  }
  const soapAction = resolveSoapAction();
  if (soapAction) {
    setHeaderIfMissing(headers, "SOAPAction", soapAction);
  }
  return compactCurlHeaders(headers);
}

function resolveSoapContentType() {
  return form.webserviceConfig.soapVersion === "SOAP_12"
    ? "application/soap+xml;charset=UTF-8"
    : "text/xml;charset=UTF-8";
}

function resolveSoapAction() {
  const configured = form.webserviceConfig.soapAction?.trim();
  if (configured) {
    return configured;
  }
  const namespaceUri = form.webserviceConfig.namespaceUri?.trim() || webservicePreview.value?.namespaceUri || "";
  const operationName = form.webserviceConfig.operationName?.trim() || webservicePreview.value?.operationName || form.serviceCode || "";
  return namespaceUri && operationName ? `${namespaceUri}/${operationName}` : "";
}

function setHeaderIfMissing(headers: Record<string, unknown>, name: string, value: string) {
  const existingKey = Object.keys(headers).find((key) => key.toLowerCase() === name.toLowerCase());
  if (!existingKey || isBlankHeaderValue(headers[existingKey])) {
    headers[existingKey || name] = value;
  }
}

function isBlankHeaderValue(value: unknown) {
  return value == null || (typeof value === "string" && !value.trim());
}

function compactCurlHeaders(headers: Record<string, unknown>) {
  const compacted: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(headers)) {
    if (!key || isBlankHeaderValue(value)) {
      continue;
    }
    compacted[key] = value;
  }
  return compacted;
}

async function copyCurlCommand() {
  if (!curlCommand.value) {
    return;
  }
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(curlCommand.value);
    } else {
      copyTextFallback(curlCommand.value);
    }
    ElMessage.success("cURL 已复制");
  } catch {
    copyTextFallback(curlCommand.value);
    ElMessage.success("cURL 已复制");
  }
}

async function previewWebService(fillSample = false) {
  if (!form.id) {
    ElMessage.warning("请先保存服务");
    return;
  }
  if (!form.webserviceEnabled) {
    ElMessage.warning("请先启用 WebService 并保存");
    return;
  }
  webservicePreviewLoading.value = true;
  try {
    webservicePreview.value = await studioApi.dataIngestionServices.previewWebService(form.id);
    if (fillSample || !soapEnvelope.value.trim()) {
      updateSoapEnvelope(webservicePreview.value.sampleRequest || "");
    }
    ElMessage.success("WebService 预览已生成");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "WebService 预览失败");
  } finally {
    webservicePreviewLoading.value = false;
  }
}

async function debugWebService() {
  if (!form.id) {
    ElMessage.warning("请先保存服务");
    return;
  }
  if (!form.webserviceEnabled) {
    ElMessage.warning("请先启用 WebService 并保存");
    return;
  }
  if (!soapEnvelope.value.trim()) {
    await previewWebService(true);
  }
  if (soapEnvelopeError.value) {
    ElMessage.error(soapEnvelopeError.value);
    return;
  }
  if (soapHeadersError.value) {
    ElMessage.error(soapHeadersError.value);
    return;
  }
  if (!soapEnvelope.value.trim()) {
    return;
  }
  webserviceDebugging.value = true;
  try {
    const headers = currentSoapHttpHeaders();
    const result = await studioApi.dataIngestionServices.debugWebService(form.id, {
      soapEnvelope: soapEnvelope.value,
      soapVersion: form.webserviceConfig.soapVersion || "SOAP_11",
      headers,
    });
    debugResult.value = result.responseEnvelope || JSON.stringify(result, null, 2);
  } catch (error) {
    if (error instanceof Error) {
      ElMessage.error(error.message);
    }
  } finally {
    webserviceDebugging.value = false;
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
  if (form.webserviceEnabled) {
    return "SOAP";
  }
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
  syncRestPayloadFromFields();
  syncSoapValues(true);
  rebuildSoapEnvelopeFromFields();
  curlCommand.value = "";
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

function wrapSoapBodyPayload(row: Record<string, unknown>) {
  const path = form.dataNodePath?.trim();
  if (!path) {
    return row;
  }
  const root: Record<string, unknown> = {};
  assignPath(root, path, row);
  return root;
}

function syncRestPayloadFromFields() {
  try {
    const payload = buildDebugPayloadFromForm();
    replaceDebugObject(restDebugPayload.headers, payload.headers);
    replaceDebugObject(restDebugPayload.query, payload.query);
    replaceDebugObject(restDebugPayload.form, payload.form);
    restDebugPayload.body = payload.body;
    syncRestRawTextFromPayload();
    clearRestRawErrors();
    curlCommand.value = "";
  } catch {
    // 保留用户正在填写的非法表单值，发送调试时再提示。
  }
}

function syncRestRawTextFromPayload() {
  debugHeaders.value = prettyJsonValue(restDebugPayload.headers);
  debugQuery.value = prettyJsonValue(restDebugPayload.query);
  debugForm.value = prettyJsonValue(restDebugPayload.form);
  debugBody.value = prettyJsonValue(restDebugPayload.body);
}

function updateRawObjectSection(key: "headers" | "query" | "form", value: string, label: string) {
  const parsed = parseJsonObjectText(value, label);
  if (!parsed.ok) {
    debugRawErrors[key] = parsed.error;
    return;
  }
  debugRawErrors[key] = "";
  replaceDebugObject(restDebugPayload[key], parsed.value);
  syncDebugValuesFromRestPayload();
}

function updateRawBodySection(value: string) {
  const parsed = parseJsonValueText(value, "Body");
  if (!parsed.ok) {
    debugRawErrors.body = parsed.error;
    return;
  }
  debugRawErrors.body = "";
  restDebugPayload.body = parsed.value;
  syncDebugValuesFromRestPayload();
}

function syncDebugValuesFromRestPayload() {
  const bodySource = resolveBodySourceRow(restDebugPayload.body);
  form.fieldMappings.forEach((mapping, index) => {
    if (!mapping.targetField?.trim()) {
      return;
    }
    const normalized: DataIngestionFieldMapping = {
      ...mapping,
      sourcePosition: normalizeSourcePosition(mapping.sourcePosition),
      sourceField: mapping.sourceField?.trim() || mapping.targetField.trim(),
      targetField: mapping.targetField.trim(),
      valueType: mapping.valueType ?? "STRING",
    };
    const sourceField = normalized.sourceField?.trim() || normalized.targetField;
    const position = normalizeSourcePosition(normalized.sourcePosition);
    const source = position === "HEADER"
      ? restDebugPayload.headers
      : position === "QUERY"
        ? restDebugPayload.query
        : position === "FORM"
          ? restDebugPayload.form
          : bodySource;
    const value = position === "BODY" ? readDebugPath(source, sourceField) : source[sourceField];
    if (value !== undefined) {
      debugValues[debugFieldKey(mapping, index)] = toDebugFieldValue(value);
    }
  });
}

function resolveBodySourceRow(body: unknown) {
  const path = form.dataNodePath?.trim();
  const payload = path ? readDebugPath(body, path) : body;
  return firstArrayItemOrSelf(payload) ?? {};
}

function clearRestRawErrors() {
  debugRawErrors.headers = "";
  debugRawErrors.query = "";
  debugRawErrors.form = "";
  debugRawErrors.body = "";
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
  syncRestPayloadFromFields();
  curlCommand.value = "";
}

function setSoapFieldValue(key: string, value: DebugFieldValue) {
  soapFieldValues[key] = value;
  rebuildSoapEnvelopeFromFields();
}

function setSoapHeaderValue(key: string, value: DebugFieldValue) {
  soapHttpHeaders[key] = value ?? "";
  syncSoapHeadersRawFromPayload();
  soapHeadersError.value = "";
}

function updateSoapHeaders(value: string) {
  soapDebugHeaders.value = value;
  const parsed = parseJsonObjectText(value, "HTTP Headers");
  if (!parsed.ok) {
    soapHeadersError.value = parsed.error;
    return;
  }
  soapHeadersError.value = "";
  replaceDebugObject(soapHttpHeaders, parsed.value);
}

function formatSoapResponse() {
  const formatted = formatXmlText(debugResult.value === "{}" ? "" : debugResult.value, "SOAP 响应");
  if (!formatted.ok) {
    ElMessage.error(formatted.error);
    return;
  }
  debugResult.value = formatted.value || "{}";
}

function updateSoapEnvelope(value: string) {
  soapEnvelope.value = value;
  const parsed = parseSoapEnvelope(value);
  if (!parsed.ok) {
    soapEnvelopeError.value = parsed.error;
    return;
  }
  soapEnvelopeError.value = "";
  if (parsed.tokenValue != null) {
    soapFieldValues[SOAP_TOKEN_FIELD_KEY] = parsed.tokenValue;
  }
  syncSoapValuesFromEnvelope(parsed.values, parsed.headerValues);
}

function syncSoapValues(force: boolean) {
  if (force || !(SOAP_TOKEN_FIELD_KEY in soapFieldValues)) {
    soapFieldValues[SOAP_TOKEN_FIELD_KEY] = "your-token";
  }
  form.fieldMappings.forEach((mapping, index) => {
    const key = debugFieldKey(mapping, index);
    if (force || !(key in soapFieldValues)) {
      soapFieldValues[key] = initialDebugValue(mapping);
    }
  });
  if (!Object.keys(soapHttpHeaders).length) {
    soapHttpHeaders["X-Data-Ingestion-Token"] = "";
    syncSoapHeadersRawFromPayload();
  }
}

function syncSoapValuesFromEnvelope(bodyValues: DebugObject, headerValues: DebugObject) {
  const bodySource = resolveBodySourceRow(bodyValues);
  form.fieldMappings.forEach((mapping, index) => {
    if (!mapping.targetField?.trim()) {
      return;
    }
    const sourceField = mapping.sourceField?.trim() || mapping.targetField.trim();
    const position = normalizeSourcePosition(mapping.sourcePosition);
    const source = position === "HEADER" ? headerValues : bodySource;
    const value = position === "BODY" ? readDebugPath(source, sourceField) : source[sourceField];
    if (value !== undefined) {
      soapFieldValues[debugFieldKey(mapping, index)] = toDebugFieldValue(value);
    }
  });
}

function rebuildSoapEnvelopeFromFields() {
  syncSoapValues(false);
  soapEnvelope.value = buildSoapEnvelope({
    soapVersion: form.webserviceConfig.soapVersion || "SOAP_11",
    namespaceUri: form.webserviceConfig.namespaceUri || webservicePreview.value?.namespaceUri || undefined,
    requestRootName: form.webserviceConfig.requestRootName
      || form.webserviceConfig.operationName
      || webservicePreview.value?.operationName
      || form.serviceCode
      || "request",
    tokenElementName: "token",
    tokenValue: stringifyDebugValue(soapFieldValues[SOAP_TOKEN_FIELD_KEY] || "your-token"),
    headerFields: buildSoapFieldSpecs("HEADER"),
    bodyPayload: buildSoapBodyPayload(),
  });
  soapEnvelopeError.value = "";
}

function buildSoapFieldSpecs(target: "HEADER" | "BODY"): SoapFieldSpec[] {
  return form.fieldMappings
    .map((mapping, index) => ({ mapping, index }))
    .filter(({ mapping }) => {
      if (!mapping.targetField?.trim()) {
        return false;
      }
      const position = normalizeSourcePosition(mapping.sourcePosition);
      return target === "HEADER" ? position === "HEADER" : position !== "HEADER";
    })
    .map(({ mapping, index }) => {
      const sourceField = mapping.sourceField?.trim() || mapping.targetField.trim();
      return {
        key: debugFieldKey(mapping, index),
        elementName: sourceField,
        value: soapFieldValues[debugFieldKey(mapping, index)] ?? initialDebugValue(mapping),
      };
    });
}

function buildSoapBodyPayload() {
  const row: DebugObject = {};
  buildSoapFieldSpecs("BODY").forEach((field) => {
    assignPath(row, field.elementName, field.value);
  });
  return wrapSoapBodyPayload(row);
}

function syncSoapHeadersRawFromPayload() {
  soapDebugHeaders.value = prettyJsonValue(soapHttpHeaders);
}

function currentSoapHttpHeaders() {
  if (soapHeadersError.value) {
    throw new Error(soapHeadersError.value);
  }
  if (soapHeadersMode.value === "raw") {
    const parsed = parseJsonObjectText(soapDebugHeaders.value, "HTTP Headers");
    if (!parsed.ok) {
      soapHeadersError.value = parsed.error;
      throw new Error(parsed.error);
    }
    replaceDebugObject(soapHttpHeaders, parsed.value);
  }
  return { ...soapHttpHeaders };
}

function toDebugFieldValue(value: unknown): DebugFieldValue {
  if (value == null || typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return value as DebugFieldValue;
  }
  return stringifyDebugValue(value);
}

function replaceDebugObject(target: DebugObject, source?: Record<string, unknown>) {
  Object.keys(target).forEach((key) => {
    delete target[key];
  });
  Object.assign(target, source ?? {});
}

function isNumericType(valueType?: FieldValueType) {
  return valueType === "INTEGER" || valueType === "LONG" || valueType === "DECIMAL";
}

function isJsonLikeType(valueType?: FieldValueType) {
  return valueType === "ARRAY" || valueType === "OBJECT" || valueType === "JSON";
}

function debugControlType(valueType?: FieldValueType): DebugControlType {
  if (valueType === "BOOLEAN") {
    return "boolean";
  }
  if (isNumericType(valueType)) {
    return "number";
  }
  if (isJsonLikeType(valueType)) {
    return "textarea";
  }
  return "text";
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
  return "Body 路径";
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
  return form.dataNodePath?.trim()
    ? `写入 ${form.dataNodePath.trim()} 节点下的 Body 数据，SOAP 模式下按 XML 节点路径读取。`
    : "写入请求 Body，SOAP 模式下按 XML 节点路径读取。";
}

function parseJsonObject(value: string, label: string) {
  const parsed = parseJsonObjectText(value, label);
  if (!parsed.ok) {
    throw new Error(parsed.error);
  }
  return parsed.value;
}

function parseJsonValue(value: string, label: string) {
  const parsed = parseJsonValueText(value, label);
  if (!parsed.ok) {
    throw new Error(parsed.error);
  }
  return parsed.value;
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
.mapping-actions {
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

.webservice-collapse {
  margin: 14px 0;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  padding: 0 12px;
  background: #fff;
}

.webservice-title {
  font-weight: 700;
  color: var(--studio-text);
}

.xml-textarea :deep(.el-textarea__inner) {
  font-family: "Cascadia Code", "Consolas", monospace;
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 960px) {
  .ingestion-form-grid {
    grid-template-columns: 1fr;
  }

  .service-wizard {
    grid-template-columns: 1fr;
  }
}
</style>
