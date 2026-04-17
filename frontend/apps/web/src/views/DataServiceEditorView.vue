<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ serviceId ? "编辑数据服务" : "新建数据服务" }}</h3>
        <p>把项目模型表或 SELECT 查询包装成稳定的订阅 API。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/data-services')">返回列表</el-button>
        <el-button type="primary" :loading="saving" @click="saveService">保存</el-button>
        <el-button type="success" :disabled="!form.id" :loading="publishing" @click="publishService">发布</el-button>
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

    <SectionCard v-if="activeStep === 0" title="一、服务基础信息" description="服务代理和非 JSON 返回类型作为后续能力占位，当前仅支持模型发布与 JSON。">
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
            <el-radio-button label="TABLE">数据表</el-radio-button>
            <el-radio-button label="SQL">SQL 语句</el-radio-button>
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
          <p>pageNum 与 pageSize 为固定分页参数，其他参数可从模型字段中选择。</p>
        </div>
        <el-button plain @click="addRequestParam">新增请求参数</el-button>
      </div>
      <el-table :data="form.requestParams" border>
        <el-table-column label="参数名称" min-width="180">
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
          <el-select v-model="form.responseType">
            <el-option label="JSON" value="JSON" />
            <el-option label="XML（暂不支持）" value="XML" disabled />
            <el-option label="文件（暂不支持）" value="FILE" disabled />
          </el-select>
        </el-form-item>
        <el-form-item label="是否缓存">
          <el-switch v-model="form.cacheEnabled" active-text="开启 60 秒缓存" inactive-text="实时查询" />
        </el-form-item>
      </div>
      <el-form-item label="服务地址">
        <el-input :model-value="endpointUrl" readonly placeholder="保存后生成服务地址" />
      </el-form-item>

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
        <el-table-column label="后端参数" min-width="150">
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

    <SectionCard v-if="activeStep === 3" title="四、接口调试" description="按发布映射生成 Header、Query、Body 模板，调试结果会展示统一包装结构。">
      <div class="section-toolbar debug-toolbar">
        <div>
          <strong>调试请求</strong>
          <p>先生成模板，再按实际值补充 Header、Query、Body 后发起调试。</p>
        </div>
        <div class="section-actions">
          <el-button plain @click="generateDebugTemplate">生成调试模板</el-button>
          <el-button plain :disabled="!endpointUrl" @click="generateCurlCommand('bash')">生成 cURL(bash)</el-button>
          <el-button plain :disabled="!endpointUrl" @click="generateCurlCommand('cmd')">生成 cURL(cmd)</el-button>
          <el-button type="primary" :loading="debugging" :disabled="!form.id" @click="debugService">调试</el-button>
        </div>
      </div>
      <el-form-item label="请求 Path">
        <el-input :model-value="endpointUrl" readonly />
      </el-form-item>
      <div class="debug-editor-layout">
        <div class="debug-request-editor">
          <el-tabs v-model="activeDebugRequestPane" class="debug-tabs">
            <el-tab-pane label="Header" name="headers">
              <JsonEditor
                v-model="debugHeaders"
                title="请求 Header"
                description="会作为调试请求的 Header 参数。"
                :placeholder="debugHeaderPlaceholder"
                height="260px"
              />
            </el-tab-pane>
            <el-tab-pane label="Query" name="query">
              <JsonEditor
                v-model="debugQuery"
                title="请求 Query"
                description="GET 参数和 Query 位置的发布参数会填在这里。"
                :placeholder="debugQueryPlaceholder"
                height="260px"
              />
            </el-tab-pane>
            <el-tab-pane label="Body" name="body">
              <JsonEditor
                v-model="debugBody"
                title="请求 Body"
                description="POST Body 位置的发布参数会填在这里。"
                :placeholder="debugBodyPlaceholder"
                height="260px"
              />
            </el-tab-pane>
          </el-tabs>
        </div>
        <JsonEditor
          v-model="debugResult"
          title="返回结果"
          description="展示后端统一 Result 包装后的调试响应。"
          placeholder="调试后展示返回 JSON"
          height="360px"
          readonly
        />
      </div>
      <div class="curl-panel">
        <div class="curl-panel__header">
          <div>
            <strong>cURL 调用命令</strong>
            <p>根据当前请求 Path、Header、Query、Body 生成开放服务调用命令，Token 使用占位符。</p>
          </div>
          <el-button plain :disabled="!curlCommand" @click="copyCurlCommand">复制 cURL</el-button>
        </div>
        <el-input
          v-model="curlCommand"
          type="textarea"
          :rows="8"
          readonly
          placeholder="点击“生成 cURL(bash)”或“生成 cURL(cmd)”后展示命令"
        />
      </div>
    </SectionCard>

    <div class="wizard-footer">
      <el-button :disabled="activeStep === 0" @click="previousStep">上一步</el-button>
      <el-button v-if="activeStep < wizardSteps.length - 1" type="primary" @click="nextStep">下一步</el-button>
      <el-button v-else type="primary" :loading="saving" @click="saveService">保存服务</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import type {
  DataModelDefinition,
  DataServiceDefinitionView,
  DataServiceFieldView,
  DataServicePublishParam,
  DataServiceQueryOperator,
  DataServiceRequestParam,
  DataServiceResponseParam,
  DataServiceSaveRequest,
  DataServiceValueType,
  DataSourceDefinition,
  EntityId,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { resolveDataServiceOpenUrl, studioApi } from "@/api/studio";
import JsonEditor from "@/components/JsonEditor.vue";
import { prettyJson } from "@/utils/studio";

const route = useRoute();
const router = useRouter();
const serviceId = computed(() => route.params.serviceId as string | undefined);

const valueTypeOptions = [
  { label: "string", value: "STRING" },
  { label: "int", value: "INT" },
  { label: "time", value: "TIME" },
  { label: "float", value: "FLOAT" },
  { label: "timestamp", value: "TIMESTAMP" },
  { label: "list", value: "LIST" },
] as const;

const operatorLabels: Record<DataServiceQueryOperator, string> = {
  EQ: "精确",
  LIKE: "模糊",
  NE: "不等于",
  GT: "大于",
  GE: "大于等于",
  LT: "小于",
  LE: "小于等于",
  CONTAINS: "包含",
  NOT_CONTAINS: "不包含",
};

const form = reactive<DataServiceSaveRequest & {
  status?: string;
  endpointPath?: string;
  serviceKey?: string;
}>({
  serviceCode: "",
  serviceName: "",
  serviceType: "MODEL_PUBLISH",
  sourceType: "TABLE",
  requestMethod: "GET",
  responseType: "JSON",
  cacheEnabled: false,
  requestParams: [],
  responseParams: [],
  publishParams: [],
});

const datasources = ref<DataSourceDefinition[]>([]);
const models = ref<DataModelDefinition[]>([]);
const fieldOptions = ref<DataServiceFieldView[]>([]);
const saving = ref(false);
const publishing = ref(false);
const resolvingFields = ref(false);
const debugging = ref(false);
const activeStep = ref(0);
const debugHeaders = ref("{}");
const debugQuery = ref("{}");
const debugBody = ref("{}");
const debugResult = ref("");
const activeDebugRequestPane = ref("query");
const curlCommand = ref("");

const wizardSteps = [
  { title: "基础信息", description: "定义服务身份" },
  { title: "服务开发", description: "选择来源与字段" },
  { title: "服务发布", description: "配置地址与映射" },
  { title: "接口调试", description: "生成模板并验证" },
];

const debugHeaderPlaceholder = `{
  "X-Request-Id": ""
}`;
const debugQueryPlaceholder = `{
  "pageNum": 1,
  "pageSize": 10
}`;
const debugBodyPlaceholder = `{
  "name": ""
}`;

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

async function loadInitialData() {
  datasources.value = await studioApi.dataDevelopment.listSqlDatasources();
  ensureFixedParams();
  if (serviceId.value) {
    await loadService(serviceId.value);
  }
  if (form.datasourceId) {
    await loadModels(form.datasourceId);
  }
  if (!form.id && route.query.debug) {
    syncDebugTemplate({ notify: false });
  }
}

async function loadService(id: EntityId) {
  const detail = await studioApi.dataServices.get(id);
  applyDetail(detail);
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
  form.responseType = detail.responseType || "JSON";
  form.cacheEnabled = Boolean(detail.cacheEnabled);
  form.endpointPath = detail.endpointPath;
  form.serviceKey = detail.serviceKey;
  form.requestParams = detail.requestParams?.length ? detail.requestParams : defaultFixedParams();
  form.responseParams = detail.responseParams || [];
  form.publishParams = detail.publishParams || [];
  fieldOptions.value = buildFieldsFromResponseParams(form.responseParams);
  ensureFixedParams();
  syncPublishParams();
  syncDebugTemplate({ notify: false });
}

async function loadModels(datasourceId: EntityId) {
  const page = await studioApi.models.listByDatasourcePage(datasourceId, { pageNo: 1, pageSize: 500 });
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

function ensureFixedParams() {
  const custom = (form.requestParams || []).filter((item) => item && item.paramName !== "pageNum" && item.paramName !== "pageSize");
  form.requestParams = [...defaultFixedParams(), ...custom].map((item, index) => ({
    ...item,
    sortOrder: index + 1,
  }));
}

function defaultFixedParams(): DataServiceRequestParam[] {
  return [
    { sortOrder: 1, paramName: "pageNum", fieldName: "pageNum", valueType: "INT", queryOperator: "EQ", required: false, description: "页码", fixedParam: true },
    { sortOrder: 2, paramName: "pageSize", fieldName: "pageSize", valueType: "INT", queryOperator: "EQ", required: false, description: "每页条数", fixedParam: true },
  ];
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

  const paramName = nextCustomRequestParamName();
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

function nextCustomRequestParamName() {
  const names = new Set(form.requestParams.map((item) => item.paramName));
  let index = Math.max(1, form.requestParams.length - 1);
  let name = `param${index}`;
  while (names.has(name)) {
    index += 1;
    name = `param${index}`;
  }
  return name;
}

async function saveService() {
  saving.value = true;
  try {
    ensureFixedParams();
    syncPublishParams();
    const saved = await studioApi.dataServices.save({
      ...form,
      serviceCode: form.serviceCode.trim(),
      serviceName: form.serviceName.trim(),
    });
    applyDetail(saved);
    ElMessage.success("数据服务已保存");
    if (!serviceId.value && saved.id) {
      await router.replace(`/data-services/${saved.id}/edit`);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存失败");
  } finally {
    saving.value = false;
  }
}

async function publishService() {
  if (!form.id) {
    return;
  }
  publishing.value = true;
  try {
    const published = await studioApi.dataServices.publish(form.id);
    applyDetail(published);
    ElMessage.success("数据服务已发布");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "发布失败");
  } finally {
    publishing.value = false;
  }
}

function generateDebugTemplate() {
  syncDebugTemplate({ notify: true });
}

function syncDebugTemplate(options: { notify?: boolean } = {}) {
  ensureFixedParams();
  syncPublishParams();
  const headers: Record<string, unknown> = {};
  const query: Record<string, unknown> = {};
  const body: Record<string, unknown> = {};
  for (const param of form.publishParams) {
    const target = param.position === "HEADER" ? headers : param.position === "BODY" ? body : query;
    target[param.frontendParamName] = param.defaultValue || param.exampleValue || "";
  }
  debugHeaders.value = prettyJson(headers);
  debugQuery.value = prettyJson(query);
  debugBody.value = prettyJson(body);
  curlCommand.value = "";
  if (options.notify !== false) {
    ElMessage.success("调试模板已生成");
  }
}

async function debugService() {
  if (!form.id) {
    return;
  }
  debugging.value = true;
  try {
    const result = await studioApi.dataServices.debug(form.id, {
      headers: parseJson(debugHeaders.value, "请求 Header"),
      query: parseJson(debugQuery.value, "请求 Query"),
      body: parseJson(debugBody.value, "请求 Body"),
    });
    debugResult.value = prettyJson(result);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "调试失败");
  } finally {
    debugging.value = false;
  }
}

function generateCurlCommand(mode: "bash" | "cmd") {
  if (!endpointUrl.value) {
    ElMessage.warning("请先保存服务生成请求 Path");
    return;
  }
  try {
    const headers = ensureOpenApiHeaders(parseJson(debugHeaders.value, "请求 Header"));
    const query = parseJson(debugQuery.value, "请求 Query");
    const body = parseJson(debugBody.value, "请求 Body");
    curlCommand.value = mode === "bash"
      ? buildBashCurl(endpointUrl.value, form.requestMethod || "GET", headers, query, body)
      : buildCmdCurl(endpointUrl.value, form.requestMethod || "GET", headers, query, body);
    ElMessage.success(`已生成 cURL(${mode === "bash" ? "bash" : "cmd"})`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "生成 cURL 失败");
  }
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
  } catch (error) {
    copyTextFallback(curlCommand.value);
    ElMessage.success("cURL 已复制");
  }
}

function parseJson(value: string, label = "调试参数") {
  if (!value.trim()) {
    return {};
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch (error) {
    throw new Error(`${label} 不是合法 JSON：${error instanceof Error ? error.message : "解析失败"}`);
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(`${label} 必须是 JSON 对象`);
  }
  return parsed as Record<string, unknown>;
}

function ensureOpenApiHeaders(headers: Record<string, unknown>) {
  const result: Record<string, unknown> = { ...headers };
  const hasAccept = Object.keys(result).some((key) => key.toLowerCase() === "accept");
  const hasToken = Object.keys(result).some((key) => key.toLowerCase() === "x-data-service-token");
  if (!hasAccept) {
    result.Accept = "application/json";
  }
  if (!hasToken) {
    result["X-Data-Service-Token"] = "<订阅Token>";
  }
  return result;
}

function buildBashCurl(url: string, method: string, headers: Record<string, unknown>, query: Record<string, unknown>, body: Record<string, unknown>) {
  const normalizedMethod = normalizeRequestMethod(method);
  const bodyText = normalizeBodyForCurl(normalizedMethod, body);
  const lines = [`curl -X ${normalizedMethod} ${quoteBash(appendQueryParams(url, query))}`];
  for (const [key, value] of Object.entries(normalizeHeadersForCurl(headers, Boolean(bodyText)))) {
    lines.push(`  -H ${quoteBash(`${key}: ${String(value)}`)}`);
  }
  if (bodyText) {
    lines.push(`  --data-raw ${quoteBash(bodyText)}`);
  }
  return lines.join(" \\\n");
}

function buildCmdCurl(url: string, method: string, headers: Record<string, unknown>, query: Record<string, unknown>, body: Record<string, unknown>) {
  const normalizedMethod = normalizeRequestMethod(method);
  const bodyText = normalizeBodyForCurl(normalizedMethod, body);
  const lines = [`curl -X ${normalizedMethod} ${quoteCmd(appendQueryParams(url, query))}`];
  for (const [key, value] of Object.entries(normalizeHeadersForCurl(headers, Boolean(bodyText)))) {
    lines.push(`  -H ${quoteCmd(`${key}: ${String(value)}`)}`);
  }
  if (bodyText) {
    lines.push(`  --data-raw ${quoteCmd(bodyText)}`);
  }
  return lines.join(" ^\n");
}

function normalizeRequestMethod(method?: string) {
  return String(method || "GET").toUpperCase() === "POST" ? "POST" : "GET";
}

function normalizeHeadersForCurl(headers: Record<string, unknown>, hasBody: boolean) {
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(headers)) {
    if (!key || value == null) {
      continue;
    }
    result[key] = value;
  }
  if (hasBody && !Object.keys(result).some((key) => key.toLowerCase() === "content-type")) {
    result["Content-Type"] = "application/json";
  }
  return result;
}

function normalizeBodyForCurl(method: string, body: Record<string, unknown>) {
  if (method !== "POST" || !Object.keys(body).length) {
    return "";
  }
  return JSON.stringify(body);
}

function appendQueryParams(url: string, query: Record<string, unknown>) {
  const parsed = new URL(url);
  for (const [key, value] of Object.entries(query)) {
    if (!key || value == null) {
      continue;
    }
    if (Array.isArray(value)) {
      parsed.searchParams.set(key, value.join(","));
      continue;
    }
    parsed.searchParams.set(key, String(value));
  }
  return parsed.toString();
}

function quoteBash(value: string) {
  return `'${value.replace(/'/g, "'\\''")}'`;
}

function quoteCmd(value: string) {
  return `"${value.replace(/"/g, '\\"')}"`;
}

function copyTextFallback(value: string) {
  const textarea = document.createElement("textarea");
  textarea.value = value;
  textarea.setAttribute("readonly", "true");
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  document.body.appendChild(textarea);
  textarea.select();
  document.execCommand("copy");
  document.body.removeChild(textarea);
}

function buildFieldsFromResponseParams(params: DataServiceResponseParam[]) {
  return (params || []).map((item) => ({
    fieldName: item.fieldName,
    exampleValue: item.exampleValue,
    description: item.description,
  }));
}

function resolveOperatorOptions(valueType?: DataServiceValueType) {
  if (valueType === "LIST") {
    return ["EQ", "CONTAINS", "NOT_CONTAINS"].map((value) => ({ label: operatorLabels[value as DataServiceQueryOperator], value: value as DataServiceQueryOperator }));
  }
  if (valueType === "STRING" || !valueType) {
    return ["EQ", "LIKE", "NE"].map((value) => ({ label: operatorLabels[value as DataServiceQueryOperator], value: value as DataServiceQueryOperator }));
  }
  return ["EQ", "GT", "GE", "LT", "LE", "NE"].map((value) => ({ label: operatorLabels[value as DataServiceQueryOperator], value: value as DataServiceQueryOperator }));
}

function resolveValueType(fieldType?: string): DataServiceValueType {
  const normalized = String(fieldType || "").toLowerCase();
  if (normalized.includes("int") || normalized.includes("long")) {
    return "INT";
  }
  if (normalized.includes("decimal") || normalized.includes("double") || normalized.includes("float")) {
    return "FLOAT";
  }
  if (normalized.includes("timestamp") || normalized.includes("datetime")) {
    return "TIMESTAMP";
  }
  if (normalized.includes("date") || normalized.includes("time")) {
    return "TIME";
  }
  return "STRING";
}

function resolveValueTypeLabel(value?: DataServiceValueType) {
  return valueTypeOptions.find((item) => item.value === value)?.label || value || "-";
}

function defaultExampleValue(valueType?: DataServiceValueType) {
  if (valueType === "INT") {
    return "1";
  }
  if (valueType === "FLOAT") {
    return "1.0";
  }
  if (valueType === "TIME" || valueType === "TIMESTAMP") {
    return "2026-04-16 12:00:00";
  }
  if (valueType === "LIST") {
    return "A,B";
  }
  return "示例";
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

.debug-toolbar {
  margin-top: 0;
}

.section-toolbar p {
  margin: 4px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.debug-editor-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 0.95fr);
  gap: 14px;
  align-items: start;
}

.debug-request-editor {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--studio-border);
  border-radius: 16px;
  background: rgba(248, 250, 252, 0.72);
}

.debug-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.curl-panel {
  display: grid;
  gap: 12px;
  margin-top: 16px;
  padding: 14px;
  border: 1px solid var(--studio-border);
  border-radius: 16px;
  background: #fff;
}

.curl-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.curl-panel__header p {
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

  .debug-editor-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .service-wizard {
    grid-template-columns: 1fr;
  }

  .curl-panel__header {
    flex-direction: column;
  }
}
</style>
