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

    <DataServiceDebugSection
      v-if="activeStep === 3"
      v-model:active-request-pane="activeDebugRequestPane"
      v-model:debug-headers="debugHeaders"
      v-model:debug-query="debugQuery"
      v-model:debug-body="debugBody"
      v-model:debug-result="debugResult"
      v-model:curl-command="curlCommand"
      :endpoint-url="endpointUrl"
      :can-debug="Boolean(form.id)"
      :debugging="debugging"
      :debug-header-placeholder="debugHeaderPlaceholder"
      :debug-query-placeholder="debugQueryPlaceholder"
      :debug-body-placeholder="debugBodyPlaceholder"
      :debug-actions="debugSectionActions"
    />

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
  DataServiceRequestParam,
  DataServiceSaveRequest,
  DataSourceDefinition,
  EntityId,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { resolveDataServiceOpenUrl, studioApi } from "@/api/studio";
import DataServiceDebugSection from "@/components/data-service/DataServiceDebugSection.vue";
import {
  buildBashCurl,
  buildCmdCurl,
  buildFieldsFromResponseParams,
  copyTextFallback,
  debugBodyPlaceholder,
  debugHeaderPlaceholder,
  debugQueryPlaceholder,
  defaultExampleValue,
  defaultFixedParams,
  ensureOpenApiHeaders,
  nextCustomRequestParamName,
  parseJson,
  resolveOperatorOptions,
  resolveValueType,
  resolveValueTypeLabel,
  valueTypeOptions,
  wizardSteps,
} from "@/components/data-service/dataServiceEditorSupport";
import { prettyJson } from "@/utils/studio";

const route = useRoute();
const router = useRouter();
const serviceId = computed(() => route.params.serviceId as string | undefined);

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

const debugSectionActions = {
  generateDebugTemplate,
  generateCurlCommand,
  debugService,
  copyCurlCommand,
};

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
