<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ serviceId ? "编辑数据服务" : "新建数据服务" }}</h3>
        <p>把项目模型表或 SELECT 查询包装成稳定的订阅 API。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/data-services')">返回列表</el-button>
        <template v-if="detailLoadError && serviceId">
          <el-button type="primary" plain @click="retryLoadService">刷新</el-button>
        </template>
        <template v-else>
          <el-button type="primary" :loading="saving" @click="saveService">保存</el-button>
          <el-button type="success" :disabled="!form.id" :loading="publishing" @click="publishService">发布</el-button>
        </template>
      </div>
    </div>

    <SectionCard v-if="detailLoadError && serviceId" title="数据服务不可用" description="该数据服务可能已被删除、取消共享，或当前项目无权访问。">
      <el-result
        icon="warning"
        title="数据服务不可用"
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

      <SectionCard v-if="activeStep === 0" title="一、服务基础信息" description="服务代理作为后续能力占位；REST 返回 JSON，启用 SOAP 后 WebService 返回 XML。">
      <div class="studio-form-grid">
        <el-form-item label="服务 Code">
          <el-input v-model="form.serviceCode" :disabled="Boolean(form.id)" placeholder="例如：order_query_api" />
        </el-form-item>
        <el-form-item label="服务名称">
          <el-input v-model="form.serviceName" placeholder="例如：订单查询服务" />
        </el-form-item>
        <el-form-item label="服务类型">
          <el-select v-model="form.serviceType">
            <el-option label="模型发布" value="MODEL_PUBLISH" />
            <el-option label="服务代理（暂不支持）" value="SERVICE_PROXY" disabled />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-input :model-value="statusLabel" disabled />
        </el-form-item>
      </div>
    </SectionCard>

    <SectionCard v-if="activeStep === 1" title="二、服务开发" description="选择数据来源并解析字段，再配置请求参数和返回参数。">
      <div class="studio-form-grid">
        <el-form-item label="获取方式">
          <el-radio-group v-model="form.sourceType" @change="handleSourceTypeChange">
            <el-radio-button value="TABLE">数据表</el-radio-button>
            <el-radio-button value="SQL">SQL 语句</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="数据源">
          <el-select
            :model-value="form.datasourceId == null ? '' : String(form.datasourceId)"
            filterable
            clearable
            placeholder="选择支持 SQL 执行的数据源"
            @update:model-value="handleDatasourceChange"
          >
            <el-option v-for="datasource in datasources" :key="String(datasource.id)" :label="`${datasource.name} / ${datasource.typeCode}`" :value="String(datasource.id)" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.sourceType === 'TABLE'" label="数据表">
          <el-select
            :model-value="form.modelId == null ? '' : String(form.modelId)"
            filterable
            clearable
            placeholder="选择模型"
            @update:model-value="handleModelChange"
          >
            <el-option
              v-for="model in models"
              :key="String(model.id)"
              :label="`${model.name}${model.physicalLocator ? ` / ${model.physicalLocator}` : ''}`"
              :value="String(model.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="字段解析">
          <el-button plain :loading="resolvingFields" @click="resolveFields">解析字段</el-button>
        </el-form-item>
      </div>

      <el-form-item v-if="form.sourceType === 'SQL'" label="自定义 SQL">
        <el-input v-model="form.customSql" type="textarea" :rows="8" placeholder="仅支持 SELECT 语句，例如：select id, name from ods_user" />
      </el-form-item>

      <div class="section-toolbar">
        <div>
          <strong>请求参数</strong>
          <p>pageNum 与 pageSize 为固定分页参数，其他参数绑定模型字段；如需对外改名，请在发布参数映射中调整。</p>
        </div>
        <el-button plain @click="addRequestParam">新增请求参数</el-button>
      </div>
      <el-table :data="form.requestParams" border>
        <el-table-column label="绑定模型字段" min-width="180">
          <template #default="{ row }">
            <el-select v-if="!row.fixedParam" v-model="row.paramName" allow-create filterable default-first-option placeholder="参数名" @change="handleRequestParamNameChange(row)">
              <el-option v-for="field in fieldOptions" :key="field.fieldName" :label="field.fieldName" :value="field.fieldName" />
            </el-select>
            <el-input v-else v-model="row.paramName" disabled />
          </template>
        </el-table-column>
        <el-table-column label="参数类型" width="150">
          <template #default="{ row }">
            <el-select v-model="row.valueType" :disabled="row.fixedParam" @change="handleRequestParamTypeChange(row)">
              <el-option v-for="item in valueTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="查询类型" width="160">
          <template #default="{ row }">
            <el-select v-model="row.queryOperator" :disabled="row.fixedParam">
              <el-option v-for="item in resolveOperatorOptions(row.valueType)" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="必填" width="90" align="center" header-align="center">
          <template #default="{ row }">
            <el-switch v-model="row.required" :disabled="row.fixedParam" @change="syncPublishParams" />
          </template>
        </el-table-column>
        <el-table-column label="参数说明" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.description" placeholder="说明" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center" header-align="center">
          <template #default="{ $index, row }">
            <el-button link type="danger" :disabled="row.fixedParam" @click="removeRequestParam($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="section-toolbar">
        <div>
          <strong>返回参数</strong>
          <p>默认拉出模型字段，通过“启用”控制对外暴露字段。</p>
        </div>
      </div>
      <el-table :data="form.responseParams" border>
        <el-table-column label="启用" width="90" align="center" header-align="center">
          <template #default="{ row }"><el-switch v-model="row.enabled" /></template>
        </el-table-column>
        <el-table-column label="参数名" min-width="160">
          <template #default="{ row }"><el-input v-model="row.paramName" /></template>
        </el-table-column>
        <el-table-column label="返回字段" min-width="180">
          <template #default="{ row }">
            <el-select v-model="row.fieldName" filterable allow-create default-first-option>
              <el-option v-for="field in fieldOptions" :key="field.fieldName" :label="field.fieldName" :value="field.fieldName" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="示例值" min-width="140">
          <template #default="{ row }"><el-input v-model="row.exampleValue" /></template>
        </el-table-column>
        <el-table-column label="说明" min-width="180">
          <template #default="{ row }"><el-input v-model="row.description" /></template>
        </el-table-column>
        <el-table-column label="转换规则" min-width="260">
          <template #default="{ row, $index }">
            <div class="service-transformers">
              <span class="service-transformers__summary">{{ responseTransformerSummary(row.transformers) }}</span>
              <el-button link type="primary" @click="openResponseTransformerDialog($index)">配置</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </SectionCard>

    <SectionCard v-if="activeStep === 2" title="三、服务发布" description="发布地址保存后自动生成；请求方式影响新增映射的默认参数位置。">
      <div class="studio-form-grid">
        <el-form-item label="请求方式">
          <el-select v-model="form.requestMethod" @change="syncPublishParams">
            <el-option label="GET" value="GET" />
            <el-option label="POST" value="POST" />
          </el-select>
        </el-form-item>
        <el-form-item label="返回类型">
          <el-select v-model="displayResponseType" :disabled="form.webserviceEnabled">
            <el-option label="JSON" value="JSON" />
            <el-option label="XML" value="XML" :disabled="!form.webserviceEnabled" />
            <el-option label="文件（暂不支持）" value="FILE" disabled />
          </el-select>
        </el-form-item>
        <el-form-item label="是否缓存">
          <el-switch v-model="form.cacheEnabled" active-text="开启 60 秒缓存" inactive-text="实时查询" />
        </el-form-item>
        <el-form-item label="访问 Token">
          <el-switch v-model="form.tokenRequired" active-text="需要 Token" inactive-text="免 Token" />
        </el-form-item>
        <el-form-item v-if="!form.tokenRequired" label="默认订阅方">
          <el-input v-model="form.defaultSubscriptionName" placeholder="免 Token 调用" />
        </el-form-item>
      </div>
      <el-form-item label="服务地址">
        <el-input :model-value="endpointUrl" readonly placeholder="保存后生成服务地址" />
      </el-form-item>

      <el-collapse v-model="webserviceCollapseNames" class="webservice-collapse">
        <el-collapse-item name="webservice">
          <template #title>
            <span class="webservice-title">WebService 设置</span>
          </template>
          <div class="studio-form-grid">
            <el-form-item label="启用 WebService">
              <el-switch v-model="form.webserviceEnabled" active-text="启用 SOAP" inactive-text="关闭" @change="handleWebServiceToggle" />
            </el-form-item>
            <el-form-item label="SOAP 版本">
              <el-select v-model="form.webserviceConfig.soapVersion" :disabled="!form.webserviceEnabled">
                <el-option label="SOAP 1.1" value="SOAP_11" />
                <el-option label="SOAP 1.2" value="SOAP_12" />
              </el-select>
            </el-form-item>
            <el-form-item label="SOAP 返回类型">
              <el-input model-value="XML" disabled />
            </el-form-item>
            <el-form-item label="Namespace">
              <el-input v-model="form.webserviceConfig.namespaceUri" :disabled="!form.webserviceEnabled" placeholder="默认按服务编码生成" />
            </el-form-item>
            <el-form-item label="Operation">
              <el-input v-model="form.webserviceConfig.operationName" :disabled="!form.webserviceEnabled" placeholder="默认使用服务 Code" />
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
            <el-form-item label="响应数据节点">
              <el-input v-model="form.webserviceConfig.responseDataNodePath" :disabled="!form.webserviceEnabled" placeholder="默认 table.row" />
            </el-form-item>
          </div>
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

      <div class="section-toolbar">
        <div>
          <strong>发布参数映射</strong>
          <p>定义前端入参与后端请求参数的映射关系，以及参数位置。</p>
        </div>
        <div class="section-actions">
          <el-button plain @click="addPublishParam">新增映射</el-button>
          <el-button plain @click="syncPublishParams">同步请求参数</el-button>
        </div>
      </div>
      <el-table :data="form.publishParams" border>
        <el-table-column label="参数名称" min-width="160">
          <template #default="{ row }"><el-input v-model="row.frontendParamName" /></template>
        </el-table-column>
        <el-table-column label="绑定请求参数" min-width="150">
          <template #default="{ row }"><el-input v-model="row.backendParamName" disabled /></template>
        </el-table-column>
        <el-table-column label="位置" width="130">
          <template #default="{ row }">
            <el-select v-model="row.position">
              <el-option label="Query" value="QUERY" />
              <el-option label="Body" value="BODY" />
              <el-option label="Header" value="HEADER" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template #default="{ row }"><el-input :model-value="resolveValueTypeLabel(row.valueType)" disabled /></template>
        </el-table-column>
        <el-table-column label="示例值" min-width="140">
          <template #default="{ row }"><el-input v-model="row.exampleValue" disabled /></template>
        </el-table-column>
        <el-table-column label="默认值" min-width="140">
          <template #default="{ row }"><el-input v-model="row.defaultValue" /></template>
        </el-table-column>
        <el-table-column label="必填" width="90" align="center" header-align="center">
          <template #default="{ row }"><el-switch v-model="row.required" /></template>
        </el-table-column>
        <el-table-column label="描述" min-width="180">
          <template #default="{ row }"><el-input v-model="row.description" /></template>
        </el-table-column>
      </el-table>
    </SectionCard>

    <OpenServiceDebugPanel
      v-if="activeStep === 3"
      v-model:mode="debugMode"
      :soap-envelope="soapEnvelope"
      :soap-headers="soapDebugHeaders"
      title="四、接口调试"
      description="表单模式按发布映射生成请求样例，原始模式按 Header、Query、Body 分段编辑。"
      disabled-hint="请先保存服务，保存后会生成服务地址并允许发送调试请求。"
      :can-debug="Boolean(form.id)"
      :debugging="debugging || webserviceDebugging"
      :endpoint-url="endpointUrl"
      :form-groups="debugPanelGroups"
      :raw-sections="debugRawSections"
      empty-form-description="请先在服务发布中配置发布参数映射"
      show-curl
      :curl-command="curlCommand"
      :show-soap-mode="form.webserviceEnabled"
      :soap-preview-loading="webservicePreviewLoading"
      :wsdl-url="webserviceWsdlUrl"
      soap-description="SOAP Envelope 是完整 XML 请求 Body，当前请求会调用数据服务 WebService。"
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

    <TransformerBindingEditor
      v-model:visible="responseTransformerDialogVisible"
      :model-value="editingResponseTransformers"
      :rule-options="onlineSafeFieldMappingRules"
      title="返回字段转换规则"
      description="配置后会在数据服务查询结果返回前对该字段执行转换；REST 与 SOAP 返回共用此规则。"
      @save="saveResponseTransformers"
    />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import type {
  DataModelListView,
  DataServiceDefinitionView,
  DataServiceFieldView,
  DataServiceParamPosition,
  DataServicePublishParam,
  DataServiceResponseParam,
  DataServiceResponseType,
  DataServiceRequestParam,
  DataServiceSaveRequest,
  DataServiceValueType,
  DataSourceOptionView,
  EntityId,
  FieldMappingRuleView,
  TransformerBinding,
  WebServiceConfig,
  WebServicePreviewView,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { resolveDataServiceOpenUrl, studioApi } from "@/api/studio";
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
  parseJsonObjectText,
  parseSoapEnvelope,
  prettyJsonValue,
  stringifyDebugValue,
  type DebugObject,
  type SoapFieldSpec,
} from "@/components/open-service/openServiceDebugSupport";
import TransformerBindingEditor from "@/components/TransformerBindingEditor.vue";
import {
  buildBashCurl,
  buildBashRawBodyCurl,
  buildCmdCurl,
  buildCmdRawBodyCurl,
  buildFieldsFromResponseParams,
  copyTextFallback,
  debugBodyPlaceholder,
  debugHeaderPlaceholder,
  debugQueryPlaceholder,
  defaultExampleValue,
  defaultFixedParams,
  ensureOpenApiHeaders,
  nextCustomRequestParamName,
  resolveOperatorOptions,
  resolveValueType,
  resolveValueTypeLabel,
  valueTypeOptions,
  wizardSteps,
} from "@/components/data-service/dataServiceEditorSupport";
import { prettyJson } from "@/utils/studio";
import { resolveErrorMessage } from "@/composables/useAsyncAction";

const route = useRoute();
const router = useRouter();
const serviceId = computed(() => route.params.serviceId as string | undefined);
type DebugMode = "form" | "raw" | "soap";
type DebugFieldValue = string | number | boolean | null | undefined;

interface DebugFieldItem {
  key: string;
  label: string;
  backendParamName: string;
  required: boolean;
  valueType?: DataServiceValueType;
  param: DataServicePublishParam;
}

interface DebugFieldGroup {
  position: DataServiceParamPosition;
  title: string;
  description: string;
  rows: DebugFieldItem[];
}

const ONLINE_UNSAFE_TRANSFORMER_CODES = new Set([
  "dx_filter",
  "range_number_filter",
  "string_operation_filter",
  "number_operation_filter",
  "date_filter",
  "date_operation_filter",
  "null_value_filter",
  "dx_groovy",
  "dx_fackgroovy",
]);

const form = reactive<DataServiceSaveRequest & {
  status?: string;
  endpointPath?: string;
  serviceKey?: string;
  webserviceEnabled: boolean;
  webserviceConfig: WebServiceConfig;
}>({
  serviceCode: "",
  serviceName: "",
  serviceType: "MODEL_PUBLISH",
  sourceType: "TABLE",
  requestMethod: "GET",
  responseType: "JSON",
  cacheEnabled: false,
  tokenRequired: true,
  defaultSubscriptionName: "",
  webserviceEnabled: false,
  webserviceConfig: defaultWebServiceConfig(),
  requestParams: [],
  responseParams: [],
  publishParams: [],
});

const datasources = ref<DataSourceOptionView[]>([]);
const models = ref<DataModelListView[]>([]);
const fieldOptions = ref<DataServiceFieldView[]>([]);
const fieldMappingRules = ref<FieldMappingRuleView[]>([]);
const saving = ref(false);
const publishing = ref(false);
const resolvingFields = ref(false);
const debugging = ref(false);
const detailLoadError = ref("");
const activeStep = ref(0);
const debugMode = ref<DebugMode>("form");
const debugHeaders = ref("{}");
const debugQuery = ref("{}");
const debugBody = ref("{}");
const debugResult = ref("");
const curlCommand = ref("");
const debugValues = reactive<Record<string, DebugFieldValue>>({});
const restDebugPayload = reactive<{
  headers: DebugObject;
  query: DebugObject;
  body: DebugObject;
}>({
  headers: {},
  query: {},
  body: {},
});
const debugRawErrors = reactive<Record<"headers" | "query" | "body", string>>({
  headers: "",
  query: "",
  body: "",
});
const webserviceCollapseNames = ref<string[]>([]);
const webservicePreviewLoading = ref(false);
const webserviceDebugging = ref(false);
const webservicePreview = ref<WebServicePreviewView | null>(null);
const soapEnvelope = ref("");
const soapDebugHeaders = ref("{}");
const soapEnvelopeError = ref("");
const soapHeadersMode = ref<HeaderEditorMode>("form");
const soapHeadersError = ref("");
const soapHttpHeaders = reactive<DebugObject>({});
const soapFieldValues = reactive<Record<string, DebugFieldValue>>({});
const responseTransformerDialogVisible = ref(false);
const editingResponseParamIndex = ref<number | null>(null);
const SOAP_TOKEN_FIELD_KEY = "__soap_header_token";

const onlineSafeFieldMappingRules = computed(() =>
  fieldMappingRules.value.filter((rule) => {
    const code = rule.mappingCode?.trim().toLowerCase();
    return Boolean(code) && !ONLINE_UNSAFE_TRANSFORMER_CODES.has(code);
  }),
);

const editingResponseTransformers = computed(() => {
  if (editingResponseParamIndex.value == null) {
    return [];
  }
  return form.responseParams[editingResponseParamIndex.value]?.transformers ?? [];
});

const debugFieldGroups = computed<DebugFieldGroup[]>(() => {
  const groups = new Map<DataServiceParamPosition, DebugFieldItem[]>();
  form.publishParams.forEach((param, index) => {
    if (!param.frontendParamName?.trim()) {
      return;
    }
    const position = normalizeDebugPosition(param.position);
    const rows = groups.get(position) ?? [];
    rows.push({
      key: debugFieldKey(param, index),
      label: param.frontendParamName.trim(),
      backendParamName: param.backendParamName,
      required: Boolean(param.required),
      valueType: param.valueType,
      param,
    });
    groups.set(position, rows);
  });
  return (["QUERY", "BODY", "HEADER"] as DataServiceParamPosition[])
    .filter((position) => groups.has(position))
    .map((position) => ({
      position,
      title: debugPositionTitle(position),
      description: debugPositionDescription(position),
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
      meta: `映射 ${row.backendParamName}`,
      required: row.required,
      controlType: debugControlType(row.valueType),
      value: debugValues[row.key],
    })),
  })),
);

const debugRawSections = computed<OpenServiceRawSection[]>(() => [
  {
    key: "headers",
    title: "Header 参数",
    description: "HTTP 请求头 JSON 对象。",
    value: debugHeaders.value,
    placeholder: debugHeaderPlaceholder,
    rows: 5,
    parseError: debugRawErrors.headers,
  },
  {
    key: "query",
    title: "Query 参数",
    description: "拼接在服务地址后的查询参数 JSON 对象。",
    value: debugQuery.value,
    placeholder: debugQueryPlaceholder,
    rows: 5,
    parseError: debugRawErrors.query,
  },
  {
    key: "body",
    title: "Body 参数",
    description: "请求 Body JSON 对象；即使 GET 服务配置了 Body 参数，cURL 也会按这里生成请求体。",
    value: debugBody.value,
    placeholder: debugBodyPlaceholder,
    rows: 8,
    parseError: debugRawErrors.body,
  },
]);

const statusLabel = computed(() => {
  if (form.status === "ONLINE") {
    return "已发布";
  }
  if (form.status === "OFFLINE") {
    return "已下线";
  }
  return form.id ? "草稿" : "未保存";
});

const endpointUrl = computed(() => {
  if (!form.endpointPath) {
    return "";
  }
  return resolveDataServiceOpenUrl(form.endpointPath);
});

const webserviceEndpointPath = computed(() => {
  if (webservicePreview.value?.endpointPath) {
    return webservicePreview.value.endpointPath;
  }
  if (!form.serviceCode || !form.serviceKey) {
    return "";
  }
  return `/openapi/ws/data-services/${form.serviceCode}/${form.serviceKey}`;
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

const displayResponseType = computed<DataServiceResponseType>({
  get: () => (form.webserviceEnabled ? "XML" : form.responseType || "JSON"),
  set: (value) => {
    if (!form.webserviceEnabled) {
      form.responseType = value;
    }
  },
});

const soapHeaderRows = computed(() => [
  {
    key: "X-Data-Service-Token",
    label: "X-Data-Service-Token",
    meta: "HTTP Header Token；也可使用 SOAP Header token",
    required: Boolean(form.tokenRequired),
    readonly: true,
    controlType: "text" as DebugControlType,
    value: soapHttpHeaders["X-Data-Service-Token"] as DebugFieldValue,
  },
]);

const soapFieldGroups = computed<OpenServiceDebugGroup[]>(() => {
  const headerRows = form.publishParams
    .map((param, index) => ({ param, index }))
    .filter(({ param }) => param.frontendParamName?.trim() && normalizeDebugPosition(param.position) === "HEADER")
    .map(({ param, index }) => ({
      key: debugFieldKey(param, index),
      label: param.frontendParamName.trim(),
      meta: `SOAP Header / 映射 ${param.backendParamName}`,
      required: Boolean(param.required),
      controlType: debugControlType(param.valueType),
      value: soapFieldValues[debugFieldKey(param, index)],
    }));
  const bodyRows = form.publishParams
    .map((param, index) => ({ param, index }))
    .filter(({ param }) => param.frontendParamName?.trim() && normalizeDebugPosition(param.position) !== "HEADER")
    .map(({ param, index }) => ({
      key: debugFieldKey(param, index),
      label: param.frontendParamName.trim(),
      meta: `SOAP Body / 映射 ${param.backendParamName}`,
      required: Boolean(param.required),
      controlType: debugControlType(param.valueType),
      value: soapFieldValues[debugFieldKey(param, index)],
    }));
  const groups: OpenServiceDebugGroup[] = [
    {
      key: "soap-header",
      title: "SOAP Header",
      description: "会写入 Envelope Header；Token 可使用 token / dataServiceToken。",
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
      description: "会写入当前 WebService 操作节点下，作为完整 XML Body 的业务参数。",
      rows: bodyRows,
    });
  }
  return groups;
});

watch(
  () => form.publishParams,
  () => {
    syncDebugValues(false);
    syncSoapValues(false);
    syncRestPayloadFromFields();
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

function defaultWebServiceConfig(config?: WebServiceConfig, enabled = false): WebServiceConfig {
  return {
    enabled,
    soapVersion: config?.soapVersion || "SOAP_11",
    namespaceUri: config?.namespaceUri || "",
    operationName: config?.operationName || "",
    soapAction: config?.soapAction || "",
    requestRootName: config?.requestRootName || "",
    responseRootName: config?.responseRootName || "",
    responseDataNodePath: config?.responseDataNodePath || "table.row",
  };
}

async function loadInitialData() {
  const [sqlDatasources, rules] = await Promise.all([
    studioApi.dataDevelopment.listSqlDatasourceOptions(),
    studioApi.fieldMappingRules.options(),
  ]);
  datasources.value = sqlDatasources;
  fieldMappingRules.value = rules;
  ensureFixedParams();
  if (serviceId.value) {
    const loaded = await loadService(serviceId.value);
    if (!loaded) {
      return;
    }
  }
  if (form.datasourceId) {
    await loadModels(form.datasourceId);
  }
  if (route.query.debug) {
    activeStep.value = wizardSteps.length - 1;
    syncDebugTemplate({ notify: false });
  }
}

async function loadService(id: EntityId) {
  try {
    detailLoadError.value = "";
    const detail = await studioApi.dataServices.get(id);
    applyDetail(detail);
    return true;
  } catch (error) {
    const message = resolveErrorMessage(error, "加载数据服务失败");
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

function applyDetail(detail: DataServiceDefinitionView) {
  form.id = detail.id;
  form.serviceCode = detail.serviceCode;
  form.serviceName = detail.serviceName;
  form.serviceType = detail.serviceType || "MODEL_PUBLISH";
  form.status = detail.status;
  form.sourceType = detail.sourceType || "TABLE";
  form.datasourceId = detail.datasourceId;
  form.modelId = detail.modelId;
  form.customSql = detail.customSql;
  form.requestMethod = detail.requestMethod || "GET";
  form.cacheEnabled = Boolean(detail.cacheEnabled);
  form.tokenRequired = detail.tokenRequired !== false;
  form.defaultSubscriptionName = detail.defaultSubscriptionName || "";
  form.webserviceEnabled = Boolean(detail.webserviceEnabled);
  form.responseType = form.webserviceEnabled ? "XML" : detail.responseType || "JSON";
  form.webserviceConfig = defaultWebServiceConfig(detail.webserviceConfig, form.webserviceEnabled);
  form.endpointPath = detail.endpointPath;
  form.serviceKey = detail.serviceKey;
  form.requestParams = detail.requestParams?.length ? detail.requestParams : defaultFixedParams();
  form.responseParams = detail.responseParams || [];
  form.publishParams = detail.publishParams || [];
  webservicePreview.value = null;
  if (!form.webserviceEnabled) {
    soapEnvelope.value = "";
  }
  fieldOptions.value = buildFieldsFromResponseParams(form.responseParams);
  normalizeRequestParamAliases();
  ensureFixedParams();
  syncPublishParams();
  syncDebugTemplate({ notify: false });
}

async function loadModels(datasourceId: EntityId) {
  const page = await studioApi.models.listSummaryByDatasourcePage(datasourceId, { pageNo: 1, pageSize: 500 });
  models.value = page.items;
}

async function handleDatasourceChange(value: string) {
  form.datasourceId = value || undefined;
  form.modelId = undefined;
  models.value = [];
  fieldOptions.value = [];
  form.responseParams = [];
  if (form.datasourceId) {
    await loadModels(form.datasourceId);
  }
}

async function handleModelChange(value: string) {
  form.modelId = value || undefined;
  if (form.modelId) {
    await resolveFields();
  }
}

function handleSourceTypeChange() {
  form.modelId = undefined;
  form.customSql = "";
  fieldOptions.value = [];
  form.responseParams = [];
}

async function resolveFields() {
  if (!form.datasourceId) {
    ElMessage.warning("请先选择数据源");
    return;
  }
  if (form.sourceType === "TABLE" && !form.modelId) {
    ElMessage.warning("请先选择数据表");
    return;
  }
  if (form.sourceType === "SQL" && !String(form.customSql || "").trim()) {
    ElMessage.warning("请先填写自定义 SQL");
    return;
  }
  resolvingFields.value = true;
  try {
    const resolved = await studioApi.dataServices.resolveFields({
      sourceType: form.sourceType,
      datasourceId: form.datasourceId,
      modelId: form.modelId,
      customSql: form.customSql,
    });
    fieldOptions.value = resolved.fields || [];
    form.responseParams = resolved.responseParams || [];
    ensureFixedParams();
    syncPublishParams();
    ElMessage.success("字段解析完成");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "字段解析失败");
  } finally {
    resolvingFields.value = false;
  }
}

function addRequestParam() {
  const field = fieldOptions.value[0];
  form.requestParams.push({
    sortOrder: form.requestParams.length + 1,
    paramName: field?.fieldName || "",
    fieldName: field?.fieldName || "",
    valueType: resolveValueType(field?.fieldType),
    queryOperator: "EQ",
    required: false,
    description: field?.description || "",
    fixedParam: false,
  });
  syncPublishParams();
}

function removeRequestParam(index: number) {
  form.requestParams.splice(index, 1);
  ensureFixedParams();
  syncPublishParams();
}

function handleRequestParamTypeChange(row: DataServiceRequestParam) {
  row.queryOperator = resolveOperatorOptions(row.valueType)[0]?.value || "EQ";
  syncPublishParams();
}

function handleRequestParamNameChange(row: DataServiceRequestParam) {
  const field = fieldOptions.value.find((item) => item.fieldName === row.paramName);
  row.fieldName = row.paramName;
  if (field) {
    row.valueType = resolveValueType(field.fieldType);
    row.description = row.description || field.description || "";
  }
  row.queryOperator = resolveOperatorOptions(row.valueType)[0]?.value || "EQ";
  syncPublishParams();
}

function normalizeRequestParamAliases() {
  const aliasMap = new Map<string, string>();
  form.requestParams = (form.requestParams || []).map((param) => {
    if (!param || param.fixedParam) {
      return param;
    }
    const paramName = param.paramName?.trim() || "";
    const fieldName = param.fieldName?.trim() || "";
    const canonicalName = fieldName || paramName;
    if (paramName && fieldName && paramName !== fieldName) {
      aliasMap.set(paramName, fieldName);
    }
    return {
      ...param,
      paramName: canonicalName,
      fieldName: canonicalName,
    };
  });
  if (!aliasMap.size) {
    return;
  }
  form.publishParams = (form.publishParams || []).map((param) => {
    const nextBackendParamName = param.backendParamName ? aliasMap.get(param.backendParamName) : undefined;
    return nextBackendParamName
      ? {
          ...param,
          backendParamName: nextBackendParamName,
        }
      : param;
  });
}

function ensureFixedParams() {
  const custom = (form.requestParams || []).filter((item) => item && item.paramName !== "pageNum" && item.paramName !== "pageSize");
  form.requestParams = [...defaultFixedParams(), ...custom].map((item, index) => ({
    ...item,
    sortOrder: index + 1,
  }));
}

function syncPublishParams() {
  const existing = new Map((form.publishParams || []).map((item) => [item.backendParamName, item]));
  form.publishParams = form.requestParams.map((param, index) => createPublishParam(param, index + 1, existing.get(param.paramName)));
}

function addPublishParam() {
  ensureFixedParams();
  const mapped = new Set((form.publishParams || []).map((item) => item.backendParamName).filter(Boolean));
  const candidate = form.requestParams.find((param) => param.paramName && !mapped.has(param.paramName));
  if (candidate) {
    form.publishParams.push(createPublishParam(candidate, form.publishParams.length + 1));
    ElMessage.success("已新增发布参数映射");
    return;
  }

  const paramName = nextCustomRequestParamName(form.requestParams);
  const requestParam: DataServiceRequestParam = {
    sortOrder: form.requestParams.length + 1,
    paramName,
    fieldName: paramName,
    valueType: "STRING",
    queryOperator: "EQ",
    required: false,
    description: "",
    fixedParam: false,
  };
  form.requestParams.push(requestParam);
  form.publishParams.push(createPublishParam(requestParam, form.publishParams.length + 1));
  ElMessage.info("已同步新增请求参数和发布映射，可在第二步继续调整参数字段");
}

function createPublishParam(param: DataServiceRequestParam, sortOrder: number, old?: DataServicePublishParam): DataServicePublishParam {
  return {
    sortOrder,
    frontendParamName: old?.frontendParamName || param.paramName,
    backendParamName: param.paramName,
    position: old?.position || (form.requestMethod === "POST" ? "BODY" : "QUERY"),
    valueType: param.valueType,
    exampleValue: old?.exampleValue || defaultExampleValue(param.valueType),
    defaultValue: old?.defaultValue ?? (param.paramName === "pageNum" ? "1" : param.paramName === "pageSize" ? "10" : ""),
    required: old?.required ?? param.required,
    description: old?.description || param.description || "",
  };
}

function responseTransformerSummary(transformers?: TransformerBinding[]) {
  if (!transformers?.length) {
    return "未配置";
  }
  return transformers
    .map((item) => item.mappingName || item.mappingCode || item.transformerCode)
    .filter(Boolean)
    .join(", ");
}

function openResponseTransformerDialog(index: number) {
  editingResponseParamIndex.value = index;
  responseTransformerDialogVisible.value = true;
}

function saveResponseTransformers(transformers: TransformerBinding[]) {
  if (editingResponseParamIndex.value == null) {
    responseTransformerDialogVisible.value = false;
    return;
  }
  const row = form.responseParams[editingResponseParamIndex.value] as DataServiceResponseParam | undefined;
  if (row) {
    row.transformers = transformers;
  }
  responseTransformerDialogVisible.value = false;
}

async function saveService() {
  if (detailLoadError.value && serviceId.value) {
    ElMessage.error(detailLoadError.value);
    return;
  }
  saving.value = true;
  try {
    normalizeRequestParamAliases();
    ensureFixedParams();
    syncPublishParams();
    const saved = await studioApi.dataServices.save({
      ...form,
      serviceCode: form.serviceCode.trim(),
      serviceName: form.serviceName.trim(),
      defaultSubscriptionName: form.defaultSubscriptionName?.trim() || undefined,
      webserviceEnabled: Boolean(form.webserviceEnabled),
      webserviceConfig: normalizeWebServiceConfigForSave(),
    });
    applyDetail(saved);
    ElMessage.success("数据服务已保存");
    if (!serviceId.value && saved.id) {
      await router.replace(`/data-services/${saved.id}/edit`);
    }
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, "保存失败"));
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
    responseDataNodePath: config.responseDataNodePath?.trim() || "table.row",
  };
}

function handleWebServiceToggle() {
  form.webserviceConfig = defaultWebServiceConfig(form.webserviceConfig, form.webserviceEnabled);
  form.responseType = form.webserviceEnabled ? "XML" : "JSON";
  webservicePreview.value = null;
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
    webservicePreview.value = await studioApi.dataServices.previewWebService(form.id);
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
    const result = await studioApi.dataServices.debugWebService(form.id, {
      soapEnvelope: soapEnvelope.value,
      soapVersion: form.webserviceConfig.soapVersion || "SOAP_11",
      headers,
    });
    debugResult.value = result.responseEnvelope || prettyJson(result);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "SOAP 调试失败");
  } finally {
    webserviceDebugging.value = false;
  }
}

function parseJsonObject(value: string, label: string) {
  const parsed = parseJsonObjectText(value || "{}", label);
  if (!parsed.ok) {
    throw new Error(parsed.error);
  }
  return parsed.value;
}

async function publishService() {
  if (detailLoadError.value && serviceId.value) {
    ElMessage.error(detailLoadError.value);
    return;
  }
  if (!form.id) {
    return;
  }
  publishing.value = true;
  try {
    const published = await studioApi.dataServices.publish(form.id);
    applyDetail(published);
    ElMessage.success("数据服务已发布");
  } catch (error) {
    ElMessage.error(resolveErrorMessage(error, "发布失败"));
  } finally {
    publishing.value = false;
  }
}

function syncDebugTemplate(options: { notify?: boolean } = {}) {
  ensureFixedParams();
  syncPublishParams();
  syncDebugValues(true);
  syncRestPayloadFromFields();
  syncSoapValues(false);
  if (!soapEnvelope.value.trim() || !soapEnvelopeError.value) {
    rebuildSoapEnvelopeFromFields();
  }
  if (options.notify !== false) {
    ElMessage.success("调试模板已生成");
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
    const result = await studioApi.dataServices.debug(form.id, {
      headers: payload.headers,
      query: payload.query,
      body: payload.body,
    });
    debugResult.value = prettyJson(result);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "调试失败");
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

function updateDebugRawSection(key: string, value: string) {
  if (key === "headers") {
    debugHeaders.value = value;
    updateRawObjectSection("headers", value, "Headers");
  } else if (key === "query") {
    debugQuery.value = value;
    updateRawObjectSection("query", value, "Query");
  } else if (key === "body") {
    debugBody.value = value;
    updateRawObjectSection("body", value, "Body");
  }
  curlCommand.value = "";
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
    body: parseJsonObject(debugBody.value, "Body"),
  };
}

async function generateCurlCommand(mode: "bash" | "cmd") {
  if (debugMode.value === "soap") {
    await generateSoapCurlCommand(mode);
    return;
  }
  if (!endpointUrl.value) {
    ElMessage.warning("请先保存服务生成请求 Path");
    return;
  }
  try {
    const payload = buildCurrentRestDebugPayload();
    const headers = ensureOpenApiHeaders(payload.headers);
    curlCommand.value = mode === "bash"
      ? buildBashCurl(endpointUrl.value, form.requestMethod || "GET", headers, payload.query, payload.body)
      : buildCmdCurl(endpointUrl.value, form.requestMethod || "GET", headers, payload.query, payload.body);
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
    setHeaderIfMissing(headers, "X-Data-Service-Token", "<订阅Token>");
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

function buildDebugPayloadFromForm() {
  const headers: Record<string, unknown> = {};
  const query: Record<string, unknown> = {};
  const body: Record<string, unknown> = {};
  form.publishParams.forEach((param, index) => {
    if (!param.frontendParamName?.trim()) {
      return;
    }
    const value = parseDebugFieldValue(debugValues[debugFieldKey(param, index)], param);
    if (isBlankDebugValue(value) && !param.required) {
      return;
    }
    const target = normalizeDebugPosition(param.position) === "HEADER"
      ? headers
      : normalizeDebugPosition(param.position) === "BODY"
        ? body
        : query;
    target[param.frontendParamName.trim()] = value;
  });
  return { headers, query, body };
}

function syncRestPayloadFromFields() {
  try {
    const payload = buildDebugPayloadFromForm();
    replaceDebugObject(restDebugPayload.headers, payload.headers);
    replaceDebugObject(restDebugPayload.query, payload.query);
    replaceDebugObject(restDebugPayload.body, payload.body);
    syncRestRawTextFromPayload();
    clearRestRawErrors();
    curlCommand.value = "";
  } catch {
    // 保留用户正在填写的非法表单值，发送调试时再给出明确错误。
  }
}

function syncRestRawTextFromPayload() {
  debugHeaders.value = prettyJsonValue(restDebugPayload.headers);
  debugQuery.value = prettyJsonValue(restDebugPayload.query);
  debugBody.value = prettyJsonValue(restDebugPayload.body);
}

function updateRawObjectSection(key: "headers" | "query" | "body", value: string, label: string) {
  const parsed = parseJsonObjectText(value, label);
  if (!parsed.ok) {
    debugRawErrors[key] = parsed.error;
    return;
  }
  debugRawErrors[key] = "";
  replaceDebugObject(restDebugPayload[key], parsed.value);
  syncDebugValuesFromRestPayload();
}

function syncDebugValuesFromRestPayload() {
  form.publishParams.forEach((param, index) => {
    if (!param.frontendParamName?.trim()) {
      return;
    }
    const source = normalizeDebugPosition(param.position) === "HEADER"
      ? restDebugPayload.headers
      : normalizeDebugPosition(param.position) === "BODY"
        ? restDebugPayload.body
        : restDebugPayload.query;
    const key = debugFieldKey(param, index);
    debugValues[key] = toDebugFieldValue(source[param.frontendParamName.trim()]);
  });
}

function clearRestRawErrors() {
  debugRawErrors.headers = "";
  debugRawErrors.query = "";
  debugRawErrors.body = "";
}

function syncDebugValues(force: boolean) {
  form.publishParams.forEach((param, index) => {
    const key = debugFieldKey(param, index);
    if (force || !(key in debugValues)) {
      debugValues[key] = initialDebugValue(param);
    }
  });
}

function resetDebugValues() {
  syncDebugValues(true);
  syncRestPayloadFromFields();
  syncSoapValues(true);
  rebuildSoapEnvelopeFromFields();
}

function debugFieldKey(param: DataServicePublishParam, index: number) {
  return `${index}:${normalizeDebugPosition(param.position)}:${param.frontendParamName || ""}:${param.backendParamName || ""}`;
}

function initialDebugValue(param: DataServicePublishParam): DebugFieldValue {
  const value = param.defaultValue || param.exampleValue || "";
  if (isNumericDebugType(param.valueType)) {
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : null;
  }
  return value;
}

function parseDebugFieldValue(value: DebugFieldValue, param: DataServicePublishParam) {
  if (isBlankDebugValue(value)) {
    return "";
  }
  if (isNumericDebugType(param.valueType)) {
    const numberValue = Number(value);
    if (!Number.isFinite(numberValue)) {
      throw new Error(`参数 ${param.frontendParamName} 必须是数字`);
    }
    return numberValue;
  }
  return value;
}

function isBlankDebugValue(value: unknown) {
  return value == null || (typeof value === "string" && !value.trim());
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
  form.publishParams.forEach((param, index) => {
    const key = debugFieldKey(param, index);
    if (force || !(key in soapFieldValues)) {
      soapFieldValues[key] = initialDebugValue(param);
    }
  });
  if (!Object.keys(soapHttpHeaders).length) {
    soapHttpHeaders["X-Data-Service-Token"] = "";
    syncSoapHeadersRawFromPayload();
  }
}

function syncSoapValuesFromEnvelope(bodyValues: DebugObject, headerValues: DebugObject) {
  form.publishParams.forEach((param, index) => {
    if (!param.frontendParamName?.trim()) {
      return;
    }
    const key = debugFieldKey(param, index);
    const source = normalizeDebugPosition(param.position) === "HEADER" ? headerValues : bodyValues;
    const value = source[param.frontendParamName.trim()];
    if (value !== undefined) {
      soapFieldValues[key] = toDebugFieldValue(value);
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
    fields: buildSoapFieldSpecs("BODY"),
  });
  soapEnvelopeError.value = "";
}

function buildSoapFieldSpecs(target: "HEADER" | "BODY"): SoapFieldSpec[] {
  return form.publishParams
    .map((param, index) => ({ param, index }))
    .filter(({ param }) => {
      if (!param.frontendParamName?.trim()) {
        return false;
      }
      const position = normalizeDebugPosition(param.position);
      return target === "HEADER" ? position === "HEADER" : position !== "HEADER";
    })
    .map(({ param, index }) => ({
      key: debugFieldKey(param, index),
      elementName: param.frontendParamName.trim(),
      value: soapFieldValues[debugFieldKey(param, index)] ?? initialDebugValue(param),
    }));
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

function isNumericDebugType(valueType?: DataServiceValueType) {
  return valueType === "INT" || valueType === "FLOAT";
}

function debugControlType(valueType?: DataServiceValueType): DebugControlType {
  if (isNumericDebugType(valueType)) {
    return "number";
  }
  if (valueType === "LIST") {
    return "textarea";
  }
  return "text";
}

function normalizeDebugPosition(position?: DataServiceParamPosition): DataServiceParamPosition {
  return position ?? "QUERY";
}

function debugPositionTitle(position: DataServiceParamPosition) {
  if (position === "BODY") {
    return "Body 参数";
  }
  if (position === "HEADER") {
    return "Header 参数";
  }
  return "Query 参数";
}

function debugPositionDescription(position: DataServiceParamPosition) {
  if (position === "BODY") {
    return "随请求 Body 提交，适合 POST 调用。";
  }
  if (position === "HEADER") {
    return "随请求头提交的参数。";
  }
  return "拼接在服务地址后的查询参数。";
}

function previousStep() {
  activeStep.value = Math.max(0, activeStep.value - 1);
}

function nextStep() {
  if (!validateStep(activeStep.value)) {
    return;
  }
  activeStep.value = Math.min(wizardSteps.length - 1, activeStep.value + 1);
  if (activeStep.value === 3) {
    syncDebugTemplate({ notify: false });
  }
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
  if (activeStep.value === 3) {
    syncDebugTemplate({ notify: false });
  }
}

function validateStep(index: number) {
  if (index === 0) {
    if (!form.serviceCode.trim() || !form.serviceName.trim()) {
      ElMessage.warning("请先填写服务 Code 和服务名称");
      return false;
    }
  }
  if (index === 1) {
    if (!form.datasourceId) {
      ElMessage.warning("请先选择数据源");
      return false;
    }
    if (form.sourceType === "TABLE" && !form.modelId) {
      ElMessage.warning("请先选择数据表");
      return false;
    }
    if (form.sourceType === "SQL" && !String(form.customSql || "").trim()) {
      ElMessage.warning("请先填写自定义 SQL");
      return false;
    }
  }
  if (index === 2 && !form.publishParams.length) {
    ElMessage.warning("请先同步或新增发布参数映射");
    return false;
  }
  return true;
}

onMounted(() => {
  void loadInitialData();
});
</script>

<style scoped>
.section-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin: 12px 0;
}

.service-wizard {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
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
  border-radius: 18px;
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

.section-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.service-transformers {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.service-transformers__summary {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-regular);
  text-overflow: ellipsis;
  white-space: nowrap;
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

.webservice-alert {
  margin-bottom: 14px;
}

.xml-textarea :deep(.el-textarea__inner) {
  font-family: "Cascadia Code", "Consolas", monospace;
  font-size: 13px;
  line-height: 1.6;
}

.section-toolbar p {
  margin: 4px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.wizard-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}

@media (max-width: 1100px) {
  .service-wizard {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .service-wizard {
    grid-template-columns: 1fr;
  }
}
</style>
