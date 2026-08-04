<template>
  <el-drawer v-model="drawerVisible" size="min(860px, 92vw)" class="studio-assistant-drawer" append-to-body>
    <template #header>
      <div class="assistant-header">
        <div>
          <strong>Studio AI 助手</strong>
          <span>{{ authStore.currentProjectName || "未选择项目" }}</span>
        </div>
        <div class="assistant-header__controls">
          <el-radio-group v-model="assistantMode" class="assistant-header__radio" size="small">
            <el-radio-button
              v-for="option in assistantModeOptions"
              :key="option.value"
              :value="option.value"
              :data-testid="`studio-assistant-mode-${option.value}`"
            >
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
          <el-radio-group v-model="responseLanguage" class="assistant-header__radio" size="small">
            <el-radio-button
              v-for="option in responseLanguageOptions"
              :key="option.value"
              :value="option.value"
              :data-testid="`studio-assistant-language-${option.value}`"
            >
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
          <el-tag type="success" round>{{ assistantModeLabel }}</el-tag>
        </div>
      </div>
    </template>

    <div class="assistant-root" data-testid="studio-assistant-root">
      <section ref="threadRef" class="assistant-thread" aria-label="assistant conversation" data-testid="studio-assistant-thread">
        <div
          v-for="message in messages"
          :key="message.id"
          class="assistant-message"
          :class="`assistant-message--${message.role}`"
          :data-testid="`studio-assistant-message-${message.role}`"
        >
          <span class="assistant-message__role">{{ message.role === "user" ? "你" : "助手" }}</span>
          <div class="assistant-message__content">
            <div
              v-if="message.role === 'assistant'"
              class="assistant-markdown"
              v-html="renderMarkdown(message.content)"
            />
            <p v-else>{{ message.content }}</p>
            <span v-if="message.streaming" class="assistant-caret"></span>

          </div>
        </div>
      </section>

      <section
        v-if="processEvents.length"
        class="assistant-process"
        :class="{ 'assistant-process--expanded': processPanelExpanded }"
        aria-label="assistant execution process"
        data-testid="studio-assistant-process"
      >
        <button
          class="assistant-process__summary"
          :class="`assistant-process__summary--${processSummaryStatus}`"
          type="button"
          @click="processPanelExpanded = !processPanelExpanded"
        >
          <span class="assistant-process__dot"></span>
          <span class="assistant-process__summary-copy">
            <strong>{{ processSummaryTitle }}</strong>
            <span>{{ processSummaryDetail }}</span>
          </span>
          <span class="assistant-process__summary-action">{{ processPanelExpanded ? "收起" : "展开" }}</span>
        </button>
        <div v-if="processPanelExpanded" class="assistant-process__list">
          <div
            v-for="event in visibleProcessEvents"
            :key="event.id"
            class="assistant-process__item"
            :class="`assistant-process__item--${event.status}`"
          >
            <span class="assistant-process__dot"></span>
            <div>
              <strong>{{ event.title }}</strong>
              <span v-if="event.detail">{{ event.detail }}</span>
            </div>
          </div>
        </div>
      </section>

      <section v-if="activeComposerControls.length" class="assistant-composer-controls" aria-label="assistant pending questions">
        <div
          v-for="control in activeComposerControls"
          :key="control.id"
          class="assistant-chat-control"
        >
          <strong>{{ control.title }}</strong>
          <span v-if="control.detail">{{ control.detail }}</span>
          <div v-if="isTextControl(control)" class="assistant-chat-control__input">
            <el-input
              v-model="selectedControlValues[control.id]"
              :type="control.type === 'textarea' ? 'textarea' : 'text'"
              :autosize="control.type === 'textarea' ? { minRows: 2, maxRows: 5 } : undefined"
              :placeholder="control.placeholder || '请输入'"
              :disabled="assistantBusy || consumedControlIds[control.id]"
              @keydown.ctrl.enter.prevent="handleChatControlSelect(control)"
            />
            <el-button
              type="primary"
              size="small"
              :disabled="assistantBusy || consumedControlIds[control.id] || !selectedControlValues[control.id]?.trim()"
              @click="handleChatControlSelect(control)"
            >
              确认
            </el-button>
          </div>
          <el-select
            v-else-if="control.type === 'select' || control.options.length > 5"
            v-model="selectedControlValues[control.id]"
            filterable
            :placeholder="control.placeholder || '请选择'"
            class="assistant-chat-control__select"
            :disabled="assistantBusy || consumedControlIds[control.id]"
            @change="handleChatControlSelect(control)"
          >
            <el-option
              v-for="option in control.options"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            >
              <span>{{ option.label }}</span>
              <small v-if="option.description">{{ option.description }}</small>
            </el-option>
          </el-select>
          <div v-else class="assistant-chat-control__options">
            <el-button
              v-for="option in control.options"
              :key="option.value"
              size="small"
              plain
              :disabled="assistantBusy || consumedControlIds[control.id]"
              @click="handleChatControlClick(control, option.value)"
            >
              {{ option.label }}
            </el-button>
          </div>
        </div>
      </section>

      <section class="assistant-composer" data-testid="studio-assistant-composer">
        <el-input
          v-model="prompt"
          type="textarea"
          data-testid="studio-assistant-prompt"
          :autosize="{ minRows: 2, maxRows: 4 }"
          resize="none"
          placeholder="可以问 Studio 能做什么，也可以说：创建 mysql1 的 order 表到 doris1 的 ods_order 表的单表采集"
          :disabled="assistantBusy"
          @keydown.ctrl.enter.prevent="submitPrompt"
        />
        <el-button type="primary" data-testid="studio-assistant-send" :loading="assistantBusy" :disabled="!prompt.trim()" @click="submitPrompt">
          发送
        </el-button>
      </section>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import type {
  AssistantToolExecutionResult,
  AssistantToolCall,
  AssistantStudioOperation,
  CapabilityMatrix,
  DataModelDatasourceOptionView,
  DataModelDefinition,
  DataSourceOptionView,
  PluginRuntimeOptionSchemaView,
  ScriptType,
} from "@studio/api-sdk";
import { streamAssistantChat, studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import {
  createAssistantMessage,
  createToolResult,
  resolveFields,
  summarizeToolResultForPlanner,
  type AssistantChatControl,
  type AssistantChatControlOption,
  type AssistantLogMessage,
  type AssistantToolResult,
} from "./assistantOrchestrator";
import { useAssistantPageContext, type AssistantPageBusinessObject } from "./assistantPageContext";

interface AssistantProcessEvent {
  id: string;
  stage: string;
  title: string;
  detail: string;
  status: "running" | "done" | "warning" | "error";
  createdAt: number;
}

interface AssistantActionPayload {
  toolCalls: AssistantToolCall[];
  uiControls: AssistantActionControl[];
  plan?: AssistantProtocolPlan;
  loop?: AssistantProtocolLoop;
  protocolViolations: AssistantProtocolViolation[];
}

interface AssistantProtocolViolation {
  code: "missingPlan" | "invalidPlan" | "missingLoop" | "invalidLoop" | "invalidProtocolFields";
  detail: string;
}

interface AssistantActionControl {
  id?: string;
  type?: string;
  title?: string;
  detail?: string;
  paramKey?: string;
  interfaceCode?: string;
  placeholder?: string;
  options?: AssistantChatControlOption[];
}

interface AssistantToolExecutionOutput {
  data: unknown;
  execution?: Record<string, unknown>;
}

interface AssistantProtocolLoop {
  mode?: string;
  status?: string;
  autoContinue?: boolean;
  questions?: unknown[];
  next?: unknown[];
  evidence?: unknown[];
  stopReason?: string;
}

interface AssistantProtocolPlan {
  intent?: string;
  basis?: unknown[];
  requiredObjects?: unknown[];
  nextActions?: unknown[];
  confidence?: string;
}

const STUDIO_ASSISTANT_PROTOCOL = "studio-assistant.v1";
const PROTOCOL_ACTION_TYPES = new Set(["frontendTool", "backendTool"]);
const PROTOCOL_CONTROL_TYPES = new Set(["select", "choices", "text", "textarea", "confirm"]);

type AssistantMode = "chat" | "plan" | "goal";
type ResponseLanguage = "zh" | "en";

interface ToolCacheState {
  capabilityMatrix: CapabilityMatrix | null;
  datasources: DataSourceOptionView[];
  modelOptions: Record<string, DataModelDatasourceOptionView[]>;
  modelDetails: Record<string, DataModelDefinition>;
  runtimeSchemas: Record<string, PluginRuntimeOptionSchemaView>;
}

type AssistantMemoryEntityKind =
  | "runtimeCluster"
  | "datasource"
  | "physicalTable"
  | "model"
  | "task"
  | "service"
  | "rule"
  | "run"
  | "workflow"
  | "script"
  | "notification"
  | "unknown";

interface AssistantMemoryEntity {
  kind: AssistantMemoryEntityKind;
  id?: string;
  name: string;
  typeCode?: string;
  physicalLocator?: string;
  modelKind?: string;
  sourcePath?: string;
  raw?: Record<string, unknown>;
  selectedAt?: string;
}

interface AssistantWorkingMemory {
  lastRuntimeClusterList: AssistantMemoryEntity[];
  selectedRuntimeCluster?: AssistantMemoryEntity;
  lastDatasourceList: AssistantMemoryEntity[];
  selectedDatasource?: AssistantMemoryEntity;
  lastPhysicalTableList: AssistantMemoryEntity[];
  selectedPhysicalTable?: AssistantMemoryEntity;
  lastModelList: AssistantMemoryEntity[];
  selectedModel?: AssistantMemoryEntity;
  recentEntitiesByPath: Record<string, AssistantMemoryEntity[]>;
  selectedEntitiesByPath: Record<string, AssistantMemoryEntity | undefined>;
  lastEntityListPath?: string;
  selectedEntity?: AssistantMemoryEntity;
}

interface AssistantFeature {
  group: string;
  path: string;
  label: string;
  caption?: string;
}

interface AssistantFrontendToolDefinition {
  interfaceCode: string;
  purpose: string;
  mutation: boolean;
  requiredValues?: string[];
  optionalValues?: string[];
  featurePaths?: string[];
}

interface AssistantFeatureActionDefinition {
  path: string;
  action: string;
  purpose: string;
  mutation: boolean;
  requiredValues?: string[];
  optionalValues?: string[];
  resource?: string;
  aliases?: string[];
}

interface PendingToolConfirmation {
  id: string;
  interfaceCode: string;
  params: Record<string, unknown>;
  title: string;
  detail: string;
  createdAt: number;
}

interface AssistantFeatureOperation {
  operation: "open" | "list" | "get" | "action";
  interfaceCode: string;
  purpose: string;
  requiredValues?: string[];
  optionalValues?: string[];
  defaultParams?: Record<string, unknown>;
}

interface AssistantFeatureToolSpec {
  capabilityCode: string;
  description: string;
  list?: {
    purpose: string;
    requiredValues?: string[];
    optionalValues?: string[];
    defaultParams?: Record<string, unknown>;
  };
  get?: {
    purpose: string;
    requiredValues?: string[];
    optionalValues?: string[];
  };
  writePolicy?: string;
}

interface AssistantCapability {
  capabilityCode: string;
  group: string;
  path: string;
  label: string;
  description?: string;
  operations: AssistantFeatureOperation[];
  writePolicy: string;
}

const MAX_TOOL_FOLLOW_UP_DEPTH = 8;
const ASSISTANT_STREAM_IDLE_TIMEOUT_MS = 45_000;

const FORBIDDEN_ASSISTANT_ACTION_PATTERN = /(?:^|[._-])(delete|remove|drop|truncate|purge|destroy|batchdelete|unfollow|cancel)(?:$|[._-])|删除|移除|清空|销毁|批量删除/i;
const UNFINISHED_ASSISTANT_OPERATION_PATTERNS = [
  /(?:^|[\n。！？；，,])\s*(?:我(?:先|会先|将先|现在|这就)|正在|将|即将|继续|接下来(?:我会|我将)?|然后(?:我会|我将)?)\s*(?:为你|帮你)?\s*(?:先)?\s*(?:确认|查询|读取|获取|查找|搜索|检查|检索|加载|调用|打开|预览|执行|处理|发现)/u,
  /(?:^|[\n.!?;,])\s*(?:i(?:'ll| will)(?: first)?|i(?:'m| am) (?:now )?|let me|now|next i(?:'ll| will)|continuing to)\s+(?:first\s+)?(?:check|query|read|fetch|retrieve|search|look up|inspect|load|call|open|preview|execute|run)/i,
];

const ASSISTANT_FRONTEND_TOOL_DEFINITIONS: AssistantFrontendToolDefinition[] = [
  {
    interfaceCode: "assistant.context.observe",
    purpose: "观察当前 Studio 页面状态、路由、项目上下文、最近候选和助手工作记忆；只返回上下文，不执行业务 API。",
    mutation: false,
    optionalValues: ["path"],
  },
  {
    interfaceCode: "assistant.context.read",
    purpose: "读取当前业务上下文中的 activeObject、已选对象、可见对象、最近候选和分页/筛选摘要；只读前端上下文，不调用业务 API。",
    mutation: false,
    optionalValues: ["path", "kind", "limit"],
  },
  {
    interfaceCode: "assistant.context.search",
    purpose: "按 path、keyword、kind 在当前页面上下文、最近候选和助手工作记忆中搜索业务对象；只返回候选，不调用业务 API。",
    mutation: false,
    requiredValues: ["path"],
    optionalValues: ["keyword", "kind", "limit"],
  },
  {
    interfaceCode: "assistant.memory.select",
    purpose: "在 LLM 已理解用户选择意图后，从最近候选中设置当前会话默认业务对象；只更新助手工作记忆，不调用业务 API。",
    mutation: false,
    requiredValues: ["path"],
    optionalValues: ["id", "name", "physicalLocator", "ordinal"],
  },
  {
    interfaceCode: "studio.navigation.open",
    purpose: "打开 Studio 助手能力目录中的功能页面，只允许 path 来自 assistantCapabilities。",
    mutation: false,
    requiredValues: ["path"],
  },
  {
    interfaceCode: "studio.feature.list",
    purpose: "按 Studio 助手能力目录读取列表、概览、候选值或指标查询；path 必须来自 assistantCapabilities。",
    mutation: false,
    requiredValues: ["path"],
    optionalValues: ["view", "keyword", "status", "pageNo", "pageSize"],
  },
  {
    interfaceCode: "studio.feature.get",
    purpose: "读取 Studio 助手能力目录下的单条记录详情；path 必须来自 assistantCapabilities，且该功能声明了 get 操作。",
    mutation: false,
    requiredValues: ["path", "id"],
    optionalValues: ["view", "resource", "log", "lineageLevel", "preview"],
  },
  {
    interfaceCode: "studio.feature.action",
    purpose: "对 Studio 助手能力目录中的功能执行动作注册表中的非删除动作；必须提供 path 和 action，写入/执行/配置变更动作会被前端拦截为确认控件，确认前不会执行。",
    mutation: true,
    requiredValues: ["path", "action"],
    optionalValues: ["id", "payload", "resource", "subscriptionId", "subscriptionName", "datasourceId", "keyword", "pageNo", "pageSize"],
  },
  {
    interfaceCode: "assistant.script.execute",
    purpose: "在目标 Worker 执行后端登记的助手脚本 skill；只能传 entrypointId、stdin-json input 和必填 runtimeClusterId，不能传 shell 命令或任意脚本文本。",
    mutation: false,
    requiredValues: ["entrypointId", "input", "runtimeClusterId"],
    optionalValues: ["skillId"],
  },
];

const props = defineProps<{
  modelValue: boolean;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
}>();

const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const assistantPageContext = useAssistantPageContext();
const { t } = useI18n();

const prompt = ref("");
const assistantMode = ref<AssistantMode>("chat");
const responseLanguage = ref<ResponseLanguage>("zh");
const messages = ref<AssistantLogMessage[]>([
  createAssistantMessage(
    "assistant",
    "你可以直接问我 Studio 的数据源、模型、采集任务、工作流等概念；当需要查询项目资源或让你选择时，我会在对话里给出候选控件。",
  ),
]);
const chatStreaming = ref(false);
const toolResults = ref<AssistantToolResult[]>([]);
const executingToolCodes = ref<string[]>([]);
const processEvents = ref<AssistantProcessEvent[]>([]);
const processPanelExpanded = ref(false);
const selectedControlValues = reactive<Record<string, string>>({});
const consumedControlIds = reactive<Record<string, boolean>>({});
const pendingToolConfirmations = reactive<Record<string, PendingToolConfirmation>>({});
const threadRef = ref<HTMLElement | null>(null);
const toolCache = reactive<ToolCacheState>({
  capabilityMatrix: null,
  datasources: [],
  modelOptions: {},
  modelDetails: {},
  runtimeSchemas: {},
});
const assistantMemory = reactive<AssistantWorkingMemory>({
  lastRuntimeClusterList: [],
  lastDatasourceList: [],
  lastPhysicalTableList: [],
  lastModelList: [],
  recentEntitiesByPath: {},
  selectedEntitiesByPath: {},
});
const assistantOperationCatalog = ref<AssistantStudioOperation[]>([]);
const assistantOperationCatalogLoaded = ref(false);
const assistantOperationCatalogLoading = ref(false);
let chatAbortController: AbortController | null = null;
let assistantOperationCatalogLoadPromise: Promise<void> | null = null;

const drawerVisible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit("update:modelValue", value),
});
const assistantBusy = computed(() => chatStreaming.value || executingToolCodes.value.length > 0);
const visibleProcessEvents = computed(() => processEvents.value.slice(-20));
const processSummaryEvent = computed(() => {
  const running = [...processEvents.value].reverse().find((event) => event.status === "running");
  return running ?? processEvents.value[processEvents.value.length - 1];
});
const processSummaryStatus = computed(() => {
  const failed = [...processEvents.value].reverse().find((event) => event.status === "error" || event.status === "warning");
  return processSummaryEvent.value?.status === "running"
    ? "running"
    : failed?.status ?? processSummaryEvent.value?.status ?? "done";
});
const processSummaryTitle = computed(() => {
  const event = processSummaryEvent.value;
  if (!event) {
    return "后台步骤";
  }
  if (processSummaryStatus.value === "error") {
    return `后台步骤中断 · ${event.title}`;
  }
  if (processSummaryStatus.value === "warning") {
    return `后台步骤需要处理 · ${event.title}`;
  }
  if (assistantBusy.value || event.status === "running") {
    return `后台处理中 · ${event.title}`;
  }
  return `后台步骤完成 · ${event.title}`;
});
const processSummaryDetail = computed(() => {
  const event = processSummaryEvent.value;
  const count = processEvents.value.length;
  if (!event) {
    return "暂无后台步骤。";
  }
  return `${event.detail || "等待下一步。"} 共 ${count} 个步骤，可展开查看。`;
});
const activeComposerControls = computed(() =>
  messages.value.flatMap((message) => message.controls ?? [])
    .filter((control) => !consumedControlIds[control.id]),
);
const assistantFeatureToolSpecs = computed(() => resolveAssistantFeatureToolSpecs());
const assistantFeatureActionDefinitions = computed(() => resolveAssistantFeatureActionDefinitions());
const assistantFeatures = computed(() => resolveAssistantFeatures());
const assistantCapabilities = computed(() => resolveAssistantCapabilities());
const availableFeatureActions = computed(() => resolveAvailableFeatureActions());
const availableFrontendTools = computed(() => resolveAvailableFrontendTools());
const assistantModeOptions = [
  { label: "Chat", value: "chat" },
  { label: "Plan", value: "plan" },
  { label: "Goal", value: "goal" },
];
const responseLanguageOptions = [
  { label: "中文", value: "zh" },
  { label: "EN", value: "en" },
];
const assistantModeLabel = computed(() => {
  if (assistantMode.value === "goal") {
    return responseLanguage.value === "en" ? "Goal mode" : "目标模式";
  }
  if (assistantMode.value === "plan") {
    return responseLanguage.value === "en" ? "Plan mode" : "计划模式";
  }
  return responseLanguage.value === "en" ? "Chat mode" : "问答模式";
});

watch(drawerVisible, (visible) => {
  if (visible) {
    void ensureAssistantOperationCatalog();
  }
}, { immediate: true });

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], () => {
  assistantMemory.lastRuntimeClusterList = [];
  delete assistantMemory.recentEntitiesByPath["/runtime-clusters"];
  clearRuntimeClusterMemorySelection();
});

async function ensureAssistantOperationCatalog(force = false) {
  if (assistantOperationCatalogLoaded.value && !force) {
    return;
  }
  if (assistantOperationCatalogLoading.value && assistantOperationCatalogLoadPromise && !force) {
    await assistantOperationCatalogLoadPromise;
    return;
  }
  assistantOperationCatalogLoading.value = true;
  assistantOperationCatalogLoadPromise = (async () => {
    pushProcessEvent({
      stage: "assistant.operations.load",
      title: "加载 Studio 功能目录",
      detail: "正在从后端读取 portable operation catalog。",
      status: "running",
    });
    try {
      const operations = await studioApi.assistant.operations();
      assistantOperationCatalog.value = Array.isArray(operations)
        ? operations.filter((operation) => normalizeRoutePath(operation.path))
        : [];
      assistantOperationCatalogLoaded.value = true;
      markProcessDone(
        "assistant.operations.load",
        assistantOperationCatalog.value.length ? "后端功能目录已加载" : "后端功能目录为空",
        assistantOperationCatalog.value.length
          ? `已加载 ${assistantOperationCatalog.value.length} 个 Studio operation。`
          : "本轮不开放本地 Studio 操作目录。",
        assistantOperationCatalog.value.length ? "done" : "warning",
      );
    } catch (error) {
      assistantOperationCatalogLoaded.value = true;
      markProcessDone(
        "assistant.operations.load",
        "后端功能目录暂不可用",
        `${resolveErrorMessage(error, "读取失败")}；本轮不开放本地 Studio 操作目录。`,
        "warning",
      );
    }
  })();
  try {
    await assistantOperationCatalogLoadPromise;
  } finally {
    assistantOperationCatalogLoading.value = false;
    assistantOperationCatalogLoadPromise = null;
  }
}

type SubmitPromptOptions = {
  preserveToolResults?: boolean;
};

async function submitPrompt(options?: SubmitPromptOptions | Event) {
  const message = prompt.value.trim();
  if (!message || assistantBusy.value) {
    return;
  }

  const preserveToolResults = Boolean(
    options
      && !(options instanceof Event)
      && "preserveToolResults" in options
      && options.preserveToolResults,
  );
  processEvents.value = [];
  processPanelExpanded.value = false;
  if (!preserveToolResults) {
    toolResults.value = [];
  }
  messages.value.push(createAssistantMessage("user", message));
  prompt.value = "";
  await nextTick(scrollThreadToBottom);
  await ensureAssistantOperationCatalog();

  const assistantMessage = await streamAssistantReply(message, conversationForRequest());
  await handleAssistantActions(assistantMessage, 0);
}

async function handleAssistantMemoryControl(control: AssistantChatControl, value: string, option?: AssistantChatControlOption) {
  const datasource = knownDatasourceEntities().find((item) => String(item.id) === String(value));
  if (!datasource) {
    messages.value.push(createAssistantMessage("assistant", "我没有在最近的数据源候选里找到这个选项，请重新读取数据源列表后再试。"));
    await nextTick(scrollThreadToBottom);
    return;
  }
  messages.value.push(createAssistantMessage("user", option?.label || datasource.name));
  selectDatasourceMemory(datasource, "control");
  const instruction = `用户已经选择当前数据源：${JSON.stringify(summarizeMemoryEntity(datasource))}。请基于 currentContext.assistantMemory 继续判断下一步；如果需要读取真实项目数据，请输出 studio-assistant-protocol；如果已有足够信息，直接回答。`;
  const assistantMessage = await streamAssistantReply(
    instruction,
    conversationForRequest(instruction),
  );
  await handleAssistantActions(assistantMessage, 0);
  await nextTick(scrollThreadToBottom);
}

async function handleGenericMemoryControl(control: AssistantChatControl, value: string, option?: AssistantChatControlOption) {
  const path = normalizeRoutePath(String(control.paramKey ?? "").replace(/^assistantMemory\.selectedEntity:/, ""));
  const entity = knownEntitiesForPath(path).find((item) => String(item.id) === String(value));
  if (!entity) {
    messages.value.push(createAssistantMessage("assistant", "我没有在最近候选里找到这个选项，请重新读取列表后再试。"));
    await nextTick(scrollThreadToBottom);
    return;
  }
  messages.value.push(createAssistantMessage("user", option?.label || entity.name));
  selectGenericMemoryEntity(path, entity, "control");
  const instruction = `用户已经选择当前${memoryPathLabel(path)}：${JSON.stringify(summarizeMemoryEntity(entity))}。请基于 currentContext.assistantMemory 继续判断下一步；如果需要读取真实项目数据，请输出 studio-assistant-protocol；如果已有足够信息，直接回答。`;
  const assistantMessage = await streamAssistantReply(
    instruction,
    conversationForRequest(instruction),
  );
  await handleAssistantActions(assistantMessage, 0);
  await nextTick(scrollThreadToBottom);
}

function selectDatasourceMemory(datasource: AssistantMemoryEntity, source: string) {
  assistantMemory.selectedDatasource = {
    ...datasource,
    selectedAt: new Date().toISOString(),
    sourcePath: datasource.sourcePath || source,
  };
  selectGenericMemoryEntity("/datasources", assistantMemory.selectedDatasource, source);
}

async function streamAssistantReply(message: string, requestMessages: Array<{ role: string; content: string }>) {
  const assistantMessage = createAssistantMessage("assistant", "");
  assistantMessage.streaming = true;
  messages.value.push(assistantMessage);
  chatStreaming.value = true;
  chatAbortController?.abort();
  chatAbortController = new AbortController();
  const activeAbortController = chatAbortController;
  let rawAssistantContent = "";
  let streamErrorMessage = "";
  let streamIdleTimer: ReturnType<typeof setTimeout> | null = null;
  let streamTimedOut = false;
  let streamSettledByWatchdog = false;
  const clearStreamWatchdog = () => {
    if (streamIdleTimer) {
      clearTimeout(streamIdleTimer);
      streamIdleTimer = null;
    }
  };
  const settleStreamByWatchdog = () => {
    if (streamSettledByWatchdog) {
      return;
    }
    streamSettledByWatchdog = true;
    streamErrorMessage = "assistant stream idle timeout";
    settleRunningProcessEvents("warning", "本轮对话已中断，后台步骤已停止等待。");
    markProcessDone("chat.stream", "流式对话中断", "助手长时间没有返回新内容，已释放输入框并保留已完成步骤。", "warning");
    applyAssistantStreamFailure(assistantMessage, streamErrorMessage, rawAssistantContent);
    assistantMessage.rawContent = assistantMessage.content;
    assistantMessage.content = stripAssistantActionBlocks(assistantMessage.content);
    assistantMessage.streaming = false;
    chatStreaming.value = false;
    if (chatAbortController === activeAbortController) {
      chatAbortController = null;
    }
    void nextTick(scrollThreadToBottom);
  };
  const refreshStreamWatchdog = () => {
    clearStreamWatchdog();
    streamIdleTimer = setTimeout(() => {
      streamTimedOut = true;
      settleStreamByWatchdog();
      activeAbortController.abort();
    }, ASSISTANT_STREAM_IDLE_TIMEOUT_MS);
  };

  try {
    pushProcessEvent({
      stage: "chat.stream",
      title: "准备流式对话",
      detail: "正在把当前项目、最近对话和工具结果发给助手服务。",
      status: "running",
    });
    refreshStreamWatchdog();
    await streamAssistantChat({
      message,
      assistantMode: assistantMode.value,
      responseLanguage: responseLanguage.value,
      protocolVersion: "studio-assistant.v1",
      messages: requestMessages,
      context: buildRuntimeContext("general_chat"),
      collectedInputs: collectCurrentInputs(),
      toolResults: toolResults.value.map(summarizeToolResultForPlanner),
    }, {
      onDelta: (content) => {
        if (streamSettledByWatchdog) {
          return;
        }
        refreshStreamWatchdog();
        rawAssistantContent += content;
        assistantMessage.content = stripAssistantActionBlocks(rawAssistantContent);
        void nextTick(scrollThreadToBottom);
      },
      onProgress: (event) => {
        if (streamSettledByWatchdog) {
          return;
        }
        refreshStreamWatchdog();
        pushProcessEvent({
          stage: event.stage || "backend.progress",
          title: event.title || "助手服务进度",
          detail: event.detail || "",
          status: normalizeProcessStatus(event.status),
        });
      },
      onError: (messageText) => {
        if (streamSettledByWatchdog) {
          return;
        }
        refreshStreamWatchdog();
        streamErrorMessage = messageText || "network error";
      },
    }, activeAbortController.signal);
    if (streamSettledByWatchdog) {
      return assistantMessage;
    }
    if (streamErrorMessage) {
      settleRunningProcessEvents("warning", "本轮对话已中断，后台步骤已停止等待。");
      markProcessDone("chat.stream", "流式对话中断", "对话连接中断，已保留已完成的后台步骤。", "warning");
      applyAssistantStreamFailure(assistantMessage, streamErrorMessage, rawAssistantContent);
    } else {
      settleRunningProcessEvents("done", "该阶段已随本轮对话完成。");
      markProcessDone("chat.stream", "流式对话完成", "已收到助手回复。");
    }
  } catch (error) {
    if (streamSettledByWatchdog) {
      return assistantMessage;
    }
    streamErrorMessage = streamTimedOut ? "assistant stream idle timeout" : resolveErrorMessage(error, "助手对话失败");
    settleRunningProcessEvents("warning", "本轮对话已中断，后台步骤已停止等待。");
    markProcessDone("chat.stream", "流式对话中断", "对话连接中断，已保留已完成的后台步骤。", "warning");
    applyAssistantStreamFailure(assistantMessage, streamErrorMessage, rawAssistantContent);
  } finally {
    clearStreamWatchdog();
    if (streamSettledByWatchdog) {
      return assistantMessage;
    }
    if (streamErrorMessage) {
      assistantMessage.rawContent = assistantMessage.content;
      assistantMessage.content = stripAssistantActionBlocks(assistantMessage.content);
    } else {
      assistantMessage.rawContent = rawAssistantContent || assistantMessage.content;
      assistantMessage.content = stripAssistantActionBlocks(rawAssistantContent || assistantMessage.content);
    }
    assistantMessage.streaming = false;
    if (!assistantMessage.content.trim()) {
      assistantMessage.content = responseLanguage.value === "en"
        ? "I did not receive a valid response. Please try again."
        : "我现在没有拿到有效回复，请稍后再试。";
    }
    chatStreaming.value = false;
    if (chatAbortController === activeAbortController) {
      chatAbortController = null;
    }
    await nextTick(scrollThreadToBottom);
  }

  return assistantMessage;
}

function applyAssistantStreamFailure(message: AssistantLogMessage, error: string, partialContent: string) {
  const partial = stripAssistantActionBlocks(partialContent).trim();
  const reason = summarizeStreamFailure(error);
  message.content = partial
    ? `${partial}\n\n${reason}。已完成的后台接口结果会继续保留，你可以选择继续处理或重新生成本次回复。`
    : `${reason}。已完成的后台接口结果会继续保留，你可以选择继续处理或重新生成本次回复。`;
  appendControlsToMessage(message, [createStreamRecoveryControl()]);
}

function summarizeStreamFailure(error: string) {
  const text = String(error ?? "");
  if (/idle timeout|timeout|timed?\s*out/i.test(text)) {
    return "助手响应等待超时";
  }
  if (/network|fetch|timeout|timed?\s*out|connection|econn|socket/i.test(text)) {
    return "对话连接中断";
  }
  if (/abort|cancel/i.test(text)) {
    return "本次对话已停止";
  }
  return "助手回复没有完整返回";
}

function createStreamRecoveryControl(): AssistantChatControl {
  return {
    id: `assistant-recovery-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    type: "choices",
    title: "对话没有完整返回，下一步怎么处理？",
    detail: "后台已完成的接口读取结果会保留，不需要从头再查。",
    paramKey: "assistantRecoveryAction",
    options: [
      { label: "继续处理", value: "continue", description: "基于已完成步骤继续下一步。" },
      { label: "重新生成", value: "retry", description: "让助手重新组织上一轮回复。" },
    ],
  };
}

function createLoopRecoveryControl(): AssistantChatControl {
  return {
    id: `assistant-loop-recovery-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    type: "choices",
    title: "助手还没有完成当前任务，下一步怎么处理？",
    detail: "已完成的接口结果会保留，继续处理不会从头开始。",
    paramKey: "assistantRecoveryAction",
    options: [
      { label: "继续处理", value: "continue", description: "基于现有上下文和工具结果继续。" },
      { label: "重新生成", value: "retry", description: "重新规划当前任务的后续步骤。" },
    ],
  };
}

function appendControlsToMessage(message: AssistantLogMessage, controls: AssistantChatControl[]) {
  message.controls = controls;
  messages.value = [...messages.value];
}

function appendAssistantLoopLimitRecovery(content: string, detail: string) {
  const message = createAssistantMessage("assistant", content);
  message.controls = [createLoopRecoveryControl()];
  messages.value.push(message);
  pushProcessEvent({
    stage: `assistant.loop.limit.${Date.now()}`,
    title: "自动处理达到安全上限",
    detail,
    status: "warning",
  });
  void nextTick(scrollThreadToBottom);
}

onBeforeUnmount(() => {
  chatAbortController?.abort();
});

async function handleAssistantActions(message: AssistantLogMessage, depth: number) {
  const parsed = extractAssistantActions(message.rawContent || message.content);
  appendAssistantPlanProcessEvent(parsed.plan);
  appendAssistantLoopProcessEvents(parsed.loop, parsed.toolCalls.length, parsed.uiControls.length);
  if (parsed.protocolViolations.length > 0) {
    await continueAfterProtocolViolation(parsed.protocolViolations[0], depth + 1);
    return;
  }
  if (shouldRepairUnfinishedAssistantReply(message, parsed)) {
    await continueAfterUnfinishedAssistantReply(message, parsed.content, depth + 1);
    return;
  }
  const actionOnly = parsed.toolCalls.length > 0 && parsed.uiControls.length === 0 && !parsed.content.trim();
  if (actionOnly) {
    removeAssistantMessage(message);
  } else {
    message.content = resolveVisibleActionMessage(parsed, message.content);
  }

  let waitingForUser = appendActionControls(parsed.uiControls);
  if (!parsed.toolCalls.length) {
    return;
  }

  pushProcessEvent({
    stage: "llm.actions",
    title: "LLM 请求调用接口",
    detail: `检测到 ${parsed.toolCalls.length} 个接口调用指令，正在按白名单执行。`,
    status: "running",
  });

  let executedCount = 0;
  let failedCount = 0;
  let confirmationCount = 0;
  for (const call of parsed.toolCalls) {
    if (!call?.interfaceCode) {
      continue;
    }
    const params = resolveToolParams(call);
    if (!params.ok) {
      recordAssistantToolFailure(call.interfaceCode, call.params ?? {}, params.error);
      failedCount += 1;
      continue;
    }
    if (requiresToolConfirmation(call.interfaceCode, params.value)) {
      queueToolConfirmation(call.interfaceCode, params.value);
      confirmationCount += 1;
      continue;
    }
    const result = await executeAssistantTool(call.interfaceCode, params.value);
    if (!result.ok) {
      failedCount += 1;
      continue;
    }
    executedCount += 1;
    appendToolResultMessage(call.interfaceCode, params.value, result.data);
    const runtimeClusterDecision = handleRuntimeClusterListResult(call.interfaceCode, params.value);
    if (runtimeClusterDecision === "waiting_for_user") {
      waitingForUser = true;
      break;
    }
    if (runtimeClusterDecision === "blocked") {
      break;
    }
  }

  markProcessDone(
    "llm.actions",
    "接口调用处理完成",
    failedCount > 0
      ? "接口返回了校验或执行错误，已交给助手根据失败上下文继续分析。"
      : confirmationCount > 0
      ? "写入或执行动作已进入确认队列。"
      : waitingForUser
        ? "候选值已作为对话控件输出。"
        : "接口结果已返回给对话上下文。",
  );
  finalizeAssistantLoopProcessEvent(parsed.loop, executedCount, failedCount, confirmationCount, waitingForUser);

  if (failedCount > 0 && !waitingForUser && confirmationCount === 0) {
    if (depth < MAX_TOOL_FOLLOW_UP_DEPTH) {
      await continueAfterToolFailure(depth + 1);
    } else {
      appendAssistantLoopLimitRecovery("最近一次接口调用仍未恢复，自动处理已达到安全上限。", "保留已有工具结果，等待你决定继续处理或重新生成。");
    }
    return;
  }

  if (executedCount > 0 && !waitingForUser && confirmationCount === 0) {
    if (depth < MAX_TOOL_FOLLOW_UP_DEPTH) {
      await continueAfterToolResults(depth + 1);
    } else {
      appendAssistantLoopLimitRecovery("本步接口已经完成，但助手尚未给出最终答复，自动处理已达到安全上限。", "已完成的接口结果不会丢失，可从当前结果继续。");
    }
  }
}

function removeAssistantMessage(message: AssistantLogMessage) {
  messages.value = messages.value.filter((item) => item.id !== message.id);
}

function resolveVisibleActionMessage(
  parsed: { content: string } & AssistantActionPayload,
  defaultMessage: string,
) {
  const visible = parsed.content.trim();
  if (parsed.uiControls.length && (!visible || /^[A-Za-z`_[\]\s]+$/.test(visible))) {
    return "请根据下面的问题继续。";
  }
  if (visible) {
    return parsed.content;
  }
  if (parsed.uiControls.length) {
    return "请根据下面的问题继续。";
  }
  if (parsed.toolCalls.length) {
    return "我会先读取必要的项目上下文，再继续判断下一步。";
  }
  return defaultMessage;
}

async function continueAfterToolResults(depth: number) {
  const assistantMessage = await streamAssistantReply(
    "接口结果已返回，请结合 toolResults 继续回答或判断下一步是否还需要调用接口。",
    conversationForRequest("接口结果已返回，请结合 toolResults 继续回答或判断下一步是否还需要调用接口。"),
  );
  await handleAssistantActions(assistantMessage, depth);
}

async function continueAfterToolFailure(depth: number) {
  const instruction = "最近一次接口或参数校验失败。请只根据 toolResults 中的 error、params、已有对话和可用能力自检原因：如果可以从上下文修正参数，请输出修正后的 studio-assistant-protocol；如果缺少必须由用户决定的值，请输出 controls 让用户选择或填写；不要直接复述原始接口错误。";
  const assistantMessage = await streamAssistantReply(
    instruction,
    conversationForRequest(instruction),
  );
  await handleAssistantActions(assistantMessage, depth);
}

async function continueAfterProtocolViolation(violation: AssistantProtocolViolation, depth: number) {
  pushProcessEvent({
    stage: "llm.protocol.invalid",
    title: "协议结构不完整",
    detail: violation.detail,
    status: "warning",
  });
  if (depth >= MAX_TOOL_FOLLOW_UP_DEPTH) {
    appendAssistantLoopLimitRecovery("助手连续返回了不完整的执行协议，自动修复已达到安全上限。", "现有对话和工具结果已保留，等待你决定继续处理或重新生成。");
    return;
  }
  const instruction = `上一条助手回复输出了 studio-assistant.v1 协议块，但协议结构缺失、不完整、包含旧执行字段，或没有放入 fenced studio-assistant-protocol 代码块。协议问题：${violation.detail} 请基于最新用户问题、currentContext、assistantCapabilities、assistantMemory 和 toolResults 重新判断，并只输出一个包含完整 plan{intent,basis,requiredObjects,nextActions}、loop{mode,status,autoContinue,questions,next,evidence,stopReason}、actions、controls 的 fenced studio-assistant-protocol；即使没有下一步动作、只是结束 loop，也必须保留 actions: []、controls: []、结构化计划和 loop 决策；每个 action 必须显式包含 type、tool、params；每个 control 必须显式包含 type、title、paramKey；不要使用 toolCalls、backendToolCalls 或 uiControls。`;
  const assistantMessage = await streamAssistantReply(
    instruction,
    conversationForRequest(instruction),
  );
  markProcessDone("llm.protocol.invalid", "协议自检完成", "已要求模型补齐结构化计划后再继续。", "warning");
  await handleAssistantActions(assistantMessage, depth);
}

async function continueAfterUnfinishedAssistantReply(message: AssistantLogMessage, content: string, depth: number) {
  pushProcessEvent({
    stage: `llm.protocol.missing-action.${Date.now()}`,
    title: "执行承诺缺少动作",
    detail: "模型只描述了将要执行的 Studio 步骤，没有返回可执行协议；正在自动要求补齐动作或用户控件。",
    status: "warning",
  });
  if (depth >= MAX_TOOL_FOLLOW_UP_DEPTH) {
    appendControlsToMessage(message, [createLoopRecoveryControl()]);
    return;
  }
  removeAssistantMessage(message);
  const unfinishedSummary = String(content ?? "").trim().slice(0, 500);
  const instruction = `上一条助手回复只有执行承诺或进度描述，却没有提供可执行的 studio-assistant.v1 协议动作，也没有提供需要用户回答的 controls，因此任务尚未完成。未完成回复：${JSON.stringify(unfinishedSummary)}。请基于最新用户问题、currentContext、assistantCapabilities、assistantMemory 和 toolResults 立即继续：如果还需要 Studio 操作，只输出一个包含完整 plan{intent,basis,requiredObjects,nextActions}、loop{mode,status,autoContinue,questions,next,evidence,stopReason}、actions、controls 的 fenced studio-assistant-protocol；如果必须由用户决定，输出 controls；如果已有足够证据，直接给出最终结果，不要再次只说“我先查询”“正在读取”或“继续获取”。`;
  const assistantMessage = await streamAssistantReply(
    instruction,
    conversationForRequest(instruction),
  );
  await handleAssistantActions(assistantMessage, depth);
}

function shouldRepairUnfinishedAssistantReply(
  message: AssistantLogMessage,
  parsed: { content: string } & AssistantActionPayload,
) {
  if (message.controls?.length || parsed.toolCalls.length || parsed.uiControls.length) {
    return false;
  }
  const visible = String(parsed.content ?? "").trim();
  return Boolean(visible) && UNFINISHED_ASSISTANT_OPERATION_PATTERNS.some((pattern) => pattern.test(visible));
}

function extractAssistantActions(content: string): { content: string } & AssistantActionPayload {
  const toolCalls: AssistantToolCall[] = [];
  const uiControls: AssistantActionControl[] = [];
  const plans: AssistantProtocolPlan[] = [];
  const loops: AssistantProtocolLoop[] = [];
  const protocolViolations: AssistantProtocolViolation[] = [];
  const cleanedProtocol = String(content ?? "").replace(/```studio[-_]assistant[-_]protocol\s*([\s\S]*?)```/gi, (_match, rawJson) => {
    appendAssistantActionPayload(String(rawJson).trim(), toolCalls, uiControls, plans, loops, protocolViolations);
    return "";
  });
  let legacyActionBlockCount = 0;
  const cleaned = cleanedProtocol.replace(/```assistant[-_]action\s*[\s\S]*?```/gi, () => {
    legacyActionBlockCount += 1;
    return "";
  });
  if (legacyActionBlockCount > 0) {
    appendPlanViolation(
      protocolViolations,
      "invalidProtocolFields",
      "助手输出了旧格式动作协议块。已阻止执行并要求 LLM 使用 fenced studio-assistant-protocol + studio-assistant.v1 重新输出。",
    );
  }
  if (hasLooseAssistantActionPayload(cleaned)) {
    appendPlanViolation(
      protocolViolations,
      "invalidProtocolFields",
      "助手输出了未放入 fenced studio-assistant-protocol 代码块的结构化动作 JSON。已阻止执行或采纳该松散 JSON，并要求 LLM 使用标准协议块重新输出。",
    );
  }
  return {
    content: stripAssistantActionBlocks(cleaned),
    toolCalls,
    uiControls,
    plan: plans.length ? plans[plans.length - 1] : undefined,
    loop: loops.length ? loops[loops.length - 1] : undefined,
    protocolViolations,
  };
}

function appendAssistantActionPayload(
  rawJson: string,
  toolCalls: AssistantToolCall[],
  uiControls: AssistantActionControl[],
  plans: AssistantProtocolPlan[],
  loops: AssistantProtocolLoop[],
  protocolViolations: AssistantProtocolViolation[],
) {
  try {
    const payload = JSON.parse(rawJson) as Partial<AssistantActionPayload> & {
      actions?: unknown;
      controls?: unknown;
      plan?: unknown;
      loop?: unknown;
    };
    if (Array.isArray(payload)) {
      appendMissingPlanViolation(protocolViolations, "studio-assistant.v1 协议块必须是包含 protocol/plan/loop/actions/controls 的对象，不能直接输出控件数组。已阻止渲染控件并要求 LLM 重新规划。");
      return;
    }
    if (isLikelyActionControl(payload)) {
      appendMissingPlanViolation(protocolViolations, "studio-assistant.v1 协议块必须是包含 protocol/plan/loop/actions/controls 的对象，不能直接输出单个 control。已阻止渲染控件并要求 LLM 重新规划。");
      return;
    }
    const isStudioProtocol = String((payload as { protocol?: unknown }).protocol ?? "").trim() === STUDIO_ASSISTANT_PROTOCOL;
    if (!isStudioProtocol) {
      appendPlanViolation(
        protocolViolations,
        "invalidProtocolFields",
        "fenced studio-assistant-protocol 必须显式声明 protocol=studio-assistant.v1。已阻止执行或采纳该协议，并要求 LLM 重新规划。",
      );
      return;
    }
    const legacyFields = ["toolCalls", "backendToolCalls", "uiControls"]
      .filter((field) => Array.isArray((payload as Record<string, unknown>)[field]));
    if (legacyFields.length) {
      appendPlanViolation(
        protocolViolations,
        "invalidProtocolFields",
        `studio-assistant.v1 协议使用了旧执行字段：${legacyFields.join(", ")}；必须把可执行调用放入 actions，把对话控件放入 controls。已阻止执行并要求 LLM 重新规划。`,
      );
      return;
    }
    const localToolCalls: AssistantToolCall[] = [];
    const localControls: AssistantActionControl[] = [];
    const actionsValidation = validateAssistantProtocolActions(payload.actions);
    if (!actionsValidation.ok) {
      appendPlanViolation(protocolViolations, actionsValidation.code, actionsValidation.detail);
      return;
    }
    localToolCalls.push(...actionsValidation.toolCalls);
    const controlsValidation = validateAssistantProtocolControls(payload.controls);
    if (!controlsValidation.ok) {
      appendPlanViolation(protocolViolations, controlsValidation.code, controlsValidation.detail);
      return;
    }
    localControls.push(...controlsValidation.controls);
    const planValidation = validateAssistantProtocolPlan(payload.plan);
    if (!planValidation.ok) {
      const detail = localToolCalls.length > 0
        ? `studio-assistant.v1 协议包含 actions，但 ${planValidation.detail}，已阻止执行并要求 LLM 重新规划。`
        : localControls.length > 0
          ? `studio-assistant.v1 协议包含 controls，但 ${planValidation.detail}，已阻止渲染控件并要求 LLM 重新规划。`
          : `studio-assistant.v1 协议 ${planValidation.detail}；即使是 loop-only/actionless/completed loop 协议也必须带完整 plan。已阻止采纳该协议并要求 LLM 重新规划。`;
      appendPlanViolation(protocolViolations, planValidation.code, detail);
      return;
    }
    const loopValidation = validateAssistantProtocolLoop(payload.loop);
    if (!loopValidation.ok) {
      const detail = localToolCalls.length > 0
        ? `studio-assistant.v1 协议包含 actions，但 ${loopValidation.detail}，已阻止执行并要求 LLM 重新规划。`
        : localControls.length > 0
          ? `studio-assistant.v1 协议包含 controls，但 ${loopValidation.detail}，已阻止渲染控件并要求 LLM 重新规划。`
          : `studio-assistant.v1 协议 ${loopValidation.detail}；即使是 actionless/completed 协议也必须带完整 loop 决策。已阻止采纳该协议并要求 LLM 重新规划。`;
      appendPlanViolation(protocolViolations, loopValidation.code, detail);
      return;
    }
    const plan = normalizeAssistantProtocolPlan(payload.plan);
    const loop = normalizeAssistantProtocolLoop(payload.loop);
    const flowValidation = validateAssistantProtocolFlow(
      loop,
      Array.isArray(payload.actions) ? payload.actions.length : 0,
      localControls.length,
    );
    if (!flowValidation.ok) {
      appendPlanViolation(protocolViolations, flowValidation.code, flowValidation.detail);
      return;
    }
    toolCalls.push(...localToolCalls);
    uiControls.push(...localControls);
    if (plan) {
      plans.push(plan);
    }
    if (loop) {
      loops.push(loop);
    }
  } catch {
    appendPlanViolation(
      protocolViolations,
      "invalidProtocolFields",
      "studio-assistant.v1 协议块不是合法 JSON，已阻止执行或采纳该协议，并要求 LLM 使用完整 fenced studio-assistant-protocol 重新输出。",
    );
  }
}

function appendMissingPlanViolation(protocolViolations: AssistantProtocolViolation[], detail?: string) {
  appendPlanViolation(
    protocolViolations,
    "missingPlan",
    detail || "studio-assistant.v1 协议缺少 plan，已阻止执行或采纳该协议，并要求 LLM 重新规划。",
  );
}

function appendPlanViolation(protocolViolations: AssistantProtocolViolation[], code: AssistantProtocolViolation["code"], detail: string) {
  protocolViolations.push({
    code,
    detail,
  });
}

function actionToToolCall(action: Record<string, unknown>): AssistantToolCall | undefined {
  const type = String(action.type ?? "").trim();
  if (type !== "frontendTool") {
    return undefined;
  }
  const interfaceCode = String(action.tool ?? "").trim();
  if (!interfaceCode) {
    return undefined;
  }
  return {
    id: String(action.id ?? `protocol-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`),
    interfaceCode,
    reason: String(action.reason ?? ""),
    params: isPlainRecord(action.params) ? action.params : {},
  };
}

function normalizeAssistantProtocolLoop(value: unknown): AssistantProtocolLoop | undefined {
  if (!isPlainRecord(value)) {
    return undefined;
  }
  return {
    mode: firstLoopText(value, ["mode"]),
    status: firstLoopText(value, ["status"]),
    autoContinue: value.autoContinue === true,
    questions: Array.isArray(value.questions) ? value.questions : [],
    next: Array.isArray(value.next) ? value.next : [],
    evidence: Array.isArray(value.evidence) ? value.evidence : [],
    stopReason: firstLoopText(value, ["stopReason"]),
  };
}

function validateAssistantProtocolActions(value: unknown): { ok: true; toolCalls: AssistantToolCall[] } | { ok: false; code: AssistantProtocolViolation["code"]; detail: string } {
  if (!Array.isArray(value)) {
    return { ok: false, code: "invalidProtocolFields", detail: "studio-assistant.v1 协议缺少必需的 actions 数组；无动作时也必须输出 actions: []。已阻止执行并要求 LLM 重新规划。" };
  }
  const invalid: string[] = [];
  const toolCalls: AssistantToolCall[] = [];
  value.forEach((item, index) => {
    if (!isPlainRecord(item)) {
      invalid.push(`actions[${index}] 必须是对象`);
      return;
    }
    const hasOwn = (key: string) => Object.prototype.hasOwnProperty.call(item, key);
    const type = String(item.type ?? "").trim();
    const tool = String(item.tool ?? "").trim();
    if (!hasOwn("type") || !type) {
      invalid.push(`actions[${index}].type 必须显式声明为 frontendTool 或 backendTool`);
    } else if (!PROTOCOL_ACTION_TYPES.has(type)) {
      invalid.push(`actions[${index}].type 不支持：${type}`);
    }
    if (!hasOwn("tool") || !tool) {
      invalid.push(`actions[${index}].tool 必须是非空文本`);
    }
    if (!hasOwn("params") || !isPlainRecord(item.params)) {
      invalid.push(`actions[${index}].params 必须是显式对象；无参数时使用 {}`);
    }
    if (!invalid.length && type === "frontendTool") {
      const call = actionToToolCall(item);
      if (call) {
        toolCalls.push(call);
      }
    }
  });
  if (invalid.length) {
    return {
      ok: false,
      code: "invalidProtocolFields",
      detail: `studio-assistant.v1 协议 actions 字段不合规：${invalid.join("；")}。已阻止执行并要求 LLM 重新规划。`,
    };
  }
  return { ok: true, toolCalls };
}

function validateAssistantProtocolControls(value: unknown): { ok: true; controls: AssistantActionControl[] } | { ok: false; code: AssistantProtocolViolation["code"]; detail: string } {
  if (!Array.isArray(value)) {
    return { ok: false, code: "invalidProtocolFields", detail: "studio-assistant.v1 协议缺少必需的 controls 数组；无需用户输入时也必须输出 controls: []。已阻止渲染控件并要求 LLM 重新规划。" };
  }
  const invalid: string[] = [];
  const controls: AssistantActionControl[] = [];
  value.forEach((item, index) => {
    if (!isPlainRecord(item)) {
      invalid.push(`controls[${index}] 必须是对象`);
      return;
    }
    const hasOwn = (key: string) => Object.prototype.hasOwnProperty.call(item, key);
    const type = String(item.type ?? "").trim();
    const title = String(item.title ?? "").trim();
    const paramKey = String(item.paramKey ?? "").trim();
    if (!hasOwn("type") || !type) {
      invalid.push(`controls[${index}].type 必须显式声明`);
    } else if (!PROTOCOL_CONTROL_TYPES.has(type)) {
      invalid.push(`controls[${index}].type 不支持：${type}`);
    }
    if (!title) {
      invalid.push(`controls[${index}].title 必须是非空文本`);
    }
    if (!paramKey) {
      invalid.push(`controls[${index}].paramKey 必须是非空文本`);
    }
    if (type === "select" || type === "choices" || type === "confirm") {
      const options = Array.isArray(item.options) ? item.options : [];
      const validOptionCount = options.filter((option) => isPlainRecord(option) && option.value != null && String(option.label ?? "").trim()).length;
      if (validOptionCount < 2) {
        invalid.push(`controls[${index}].options 至少需要 2 个有效选项`);
      }
    }
    controls.push(item as AssistantActionControl);
  });
  if (invalid.length) {
    return {
      ok: false,
      code: "invalidProtocolFields",
      detail: `studio-assistant.v1 协议 controls 字段不合规：${invalid.join("；")}。已阻止渲染控件并要求 LLM 重新规划。`,
    };
  }
  return { ok: true, controls };
}

function normalizeAssistantProtocolPlan(value: unknown): AssistantProtocolPlan | undefined {
  if (!isPlainRecord(value)) {
    return undefined;
  }
  const intent = firstLoopText(value, ["intent"]);
  const basis = toProtocolArray(value.basis);
  const requiredObjects = toProtocolArray(value.requiredObjects);
  const nextActions = toProtocolArray(value.nextActions);
  const confidence = firstLoopText(value, ["confidence"]);
  if (!intent && !basis.length && !requiredObjects.length && !nextActions.length) {
    return undefined;
  }
  return {
    intent,
    basis,
    requiredObjects,
    nextActions,
    confidence,
  };
}

function validateAssistantProtocolPlan(value: unknown): { ok: true } | { ok: false; code: AssistantProtocolViolation["code"]; detail: string } {
  if (!isPlainRecord(value)) {
    return { ok: false, code: "missingPlan", detail: "缺少必需的 plan" };
  }
  const missing: string[] = [];
  const invalid: string[] = [];
  const hasOwn = (key: string) => Object.prototype.hasOwnProperty.call(value, key);
  if (!hasOwn("intent")) {
    missing.push("intent");
  } else if (!String(value.intent ?? "").trim()) {
    invalid.push("intent 必须是非空文本");
  }
  for (const key of ["basis", "requiredObjects", "nextActions"]) {
    if (!hasOwn(key)) {
      missing.push(key);
      continue;
    }
    if (!Array.isArray(value[key])) {
      invalid.push(`${key} 必须是数组`);
      continue;
    }
    if (key !== "requiredObjects" && value[key].length === 0) {
      invalid.push(`${key} 不能为空数组`);
    }
  }
  if (!missing.length && !invalid.length) {
    return { ok: true };
  }
  const parts = [
    missing.length ? `缺少必需的 plan 字段：${missing.join(", ")}` : "",
    invalid.length ? `plan 字段不合规：${invalid.join(", ")}` : "",
  ].filter(Boolean);
  return { ok: false, code: missing.length ? "missingPlan" : "invalidPlan", detail: parts.join("；") };
}

function validateAssistantProtocolLoop(value: unknown): { ok: true } | { ok: false; code: AssistantProtocolViolation["code"]; detail: string } {
  if (!isPlainRecord(value)) {
    return { ok: false, code: "missingLoop", detail: "缺少必需的 loop" };
  }
  const missing: string[] = [];
  const invalid: string[] = [];
  const hasOwn = (key: string) => Object.prototype.hasOwnProperty.call(value, key);
  if (!hasOwn("status")) {
    missing.push("status");
  } else if (!String(value.status ?? "").trim()) {
    invalid.push("status 必须是非空文本");
  }
  if (!hasOwn("autoContinue")) {
    missing.push("autoContinue");
  } else if (typeof value.autoContinue !== "boolean") {
    invalid.push("autoContinue 必须是布尔值");
  }
  if (!hasOwn("stopReason")) {
    missing.push("stopReason");
  } else if (!String(value.stopReason ?? "").trim()) {
    invalid.push("stopReason 必须是非空文本");
  }
  for (const key of ["questions", "next", "evidence"]) {
    if (!hasOwn(key)) {
      missing.push(key);
      continue;
    }
    if (!Array.isArray(value[key])) {
      invalid.push(`${key} 必须是数组`);
    }
  }
  if (!missing.length && !invalid.length) {
    return { ok: true };
  }
  const parts = [
    missing.length ? `缺少必需的 loop 字段：${missing.join(", ")}` : "",
    invalid.length ? `loop 字段不合规：${invalid.join(", ")}` : "",
  ].filter(Boolean);
  return { ok: false, code: missing.length ? "missingLoop" : "invalidLoop", detail: parts.join("；") };
}

function validateAssistantProtocolFlow(
  loop: AssistantProtocolLoop | undefined,
  actionCount: number,
  controlCount: number,
): { ok: true } | { ok: false; code: AssistantProtocolViolation["code"]; detail: string } {
  if (!loop) {
    return { ok: false, code: "missingLoop", detail: "缺少可执行的 loop 决策" };
  }
  if (loop.autoContinue && actionCount === 0) {
    return {
      ok: false,
      code: "invalidLoop",
      detail: "loop.autoContinue=true 时必须至少提供一个 action，否则客户端没有可继续执行的步骤。",
    };
  }
  if (isUserWaitingLoop(loop) && actionCount === 0 && controlCount === 0) {
    return {
      ok: false,
      code: "invalidLoop",
      detail: "loop 声明等待用户输入时必须提供 action 或 controls，否则用户无法恢复任务。",
    };
  }
  if (!isCompletedLoop(loop) && !isUserWaitingLoop(loop) && actionCount === 0 && controlCount === 0) {
    return {
      ok: false,
      code: "invalidLoop",
      detail: "未完成的 loop 必须提供 action 或 control，不能在没有后续步骤时停止。",
    };
  }
  return { ok: true };
}

function toProtocolArray(value: unknown) {
  if (Array.isArray(value)) {
    return value;
  }
  if (value == null || value === "") {
    return [];
  }
  return [value];
}

function appendAssistantPlanProcessEvent(plan: AssistantProtocolPlan | undefined) {
  if (!plan) {
    return;
  }
  const parts = [
    plan.intent ? `意图：${plan.intent}` : "",
    plan.basis?.length ? `依据：${summarizePlanItems(plan.basis, ["text", "basis", "source", "description"])}` : "",
    plan.requiredObjects?.length ? `对象：${summarizePlanItems(plan.requiredObjects, ["name", "label", "id", "path", "type", "description"])}` : "",
    plan.nextActions?.length ? `下一步：${summarizePlanItems(plan.nextActions, ["description", "action", "tool", "path", "reason"])}` : "",
  ].filter(Boolean);
  if (!parts.length) {
    return;
  }
  pushProcessEvent({
    stage: "assistant.plan.structured",
    title: "LLM 结构化计划",
    detail: parts.join("；"),
    status: "done",
  });
}

function summarizePlanItems(items: unknown[], keys: string[]) {
  return items
    .map((item) => describePlanItem(item, keys))
    .filter(Boolean)
    .slice(0, 4)
    .join("，");
}

function describePlanItem(item: unknown, keys: string[]) {
  if (isPlainRecord(item)) {
    const text = firstLoopText(item, keys);
    if (text) {
      return text;
    }
    const compact = Object.entries(item)
      .filter(([, value]) => value != null && ["string", "number", "boolean"].includes(typeof value))
      .slice(0, 3)
      .map(([key, value]) => `${key}=${String(value)}`)
      .join("/");
    return compact;
  }
  return String(item ?? "").trim();
}

function appendAssistantLoopProcessEvents(
  loop: AssistantProtocolLoop | undefined,
  toolCallCount: number,
  controlCount: number,
) {
  if (!loop) {
    return;
  }
  const questions = summarizeAssistantLoopQuestions(loop.questions);
  if (questions) {
    pushProcessEvent({
      stage: "assistant.loop.questions",
      title: "问题拆解",
      detail: questions,
      status: "done",
    });
  }
  const nextDetail = summarizeAssistantLoopNext(loop, toolCallCount, controlCount);
  if (nextDetail) {
    pushProcessEvent({
      stage: "assistant.loop.next",
      title: resolveAssistantLoopTitle(loop, toolCallCount, controlCount),
      detail: nextDetail,
      status: resolveAssistantLoopStatus(loop, toolCallCount, controlCount),
    });
  }
}

function finalizeAssistantLoopProcessEvent(
  loop: AssistantProtocolLoop | undefined,
  executedCount: number,
  failedCount: number,
  confirmationCount: number,
  waitingForUser: boolean,
) {
  if (!loop) {
    return;
  }
  if (failedCount > 0) {
    markProcessDone("assistant.loop.next", "Loop 等待恢复", "工具执行失败已写入上下文，助手会根据失败原因继续恢复。", "warning");
    return;
  }
  if (confirmationCount > 0 || waitingForUser) {
    markProcessDone("assistant.loop.next", "Loop 等待用户输入", "下一步需要用户确认或补充信息后才能继续。", "warning");
    return;
  }
  if (executedCount > 0) {
    markProcessDone("assistant.loop.next", "Loop 已回灌工具结果", "工具结果已写入对话上下文，助手会自动继续总结或判断下一步。", "done");
  }
}

function summarizeAssistantLoopQuestions(questions: unknown[] | undefined) {
  const items = (questions ?? [])
    .map((item) => {
      if (isPlainRecord(item)) {
        return firstLoopText(item, ["input", "question", "title", "description"]);
      }
      return String(item ?? "").trim();
    })
    .filter(Boolean)
    .slice(0, 4);
  return items.length ? items.map((item, index) => `${index + 1}. ${item}`).join("；") : "";
}

function summarizeAssistantLoopNext(
  loop: AssistantProtocolLoop,
  toolCallCount: number,
  controlCount: number,
) {
  const nextItems = (loop.next ?? [])
    .map((item) => {
      if (isPlainRecord(item)) {
        return firstLoopText(item, ["description", "title", "input", "step"]);
      }
      return String(item ?? "").trim();
    })
    .filter(Boolean)
    .slice(0, 3);
  if (nextItems.length) {
    return nextItems.join("；");
  }
  if (isCompletedLoop(loop)) {
    return "LLM 已判断当前工具结果足够，无需继续调用接口。";
  }
  if (controlCount > 0 || isUserWaitingLoop(loop)) {
    return "等待用户在对话控件中确认或补充信息。";
  }
  if (toolCallCount > 0 || loop.autoContinue) {
    return "执行受控工具后，将把结果回灌给助手继续总结。";
  }
  return "";
}

function resolveAssistantLoopTitle(
  loop: AssistantProtocolLoop,
  toolCallCount: number,
  controlCount: number,
) {
  if (isCompletedLoop(loop)) {
    return "Loop 已由 LLM 结束";
  }
  if (controlCount > 0 || isUserWaitingLoop(loop)) {
    return "Loop 等待用户输入";
  }
  if (toolCallCount > 0 || loop.autoContinue) {
    return "工具结果自动继续";
  }
  return "Loop 状态已记录";
}

function resolveAssistantLoopStatus(
  loop: AssistantProtocolLoop,
  toolCallCount: number,
  controlCount: number,
): AssistantProcessEvent["status"] {
  if (isCompletedLoop(loop)) {
    return "done";
  }
  if (controlCount > 0 || isUserWaitingLoop(loop)) {
    return "warning";
  }
  if (toolCallCount > 0 || loop.autoContinue) {
    return "running";
  }
  return "done";
}

function isUserWaitingLoop(loop: AssistantProtocolLoop) {
  const text = `${loop.status ?? ""} ${loop.stopReason ?? ""}`.toLowerCase();
  return text.includes("user") || text.includes("confirm") || text.includes("control");
}

function isCompletedLoop(loop: AssistantProtocolLoop) {
  const rawText = `${loop.status ?? ""} ${loop.stopReason ?? ""}`;
  const text = rawText.toLowerCase();
  return text.includes("completed")
    || text.includes("complete")
    || text.includes("finished")
    || text.includes("final")
    || text.includes("answer_complete")
    || text.includes("no_more_action")
    || text.includes("no more action")
    || text.includes("answered")
    || /完成|结束/.test(rawText);
}

function firstLoopText(record: Record<string, unknown>, keys: string[]) {
  for (const key of keys) {
    const value = record[key];
    if (value == null) {
      continue;
    }
    const text = String(value).trim();
    if (text) {
      return text;
    }
  }
  return "";
}

function isLikelyActionControl(value: unknown): value is AssistantActionControl {
  if (!value || typeof value !== "object") {
    return false;
  }
  const item = value as AssistantActionControl;
  return Boolean(item.title && (item.paramKey || item.options?.length || item.placeholder));
}

function stripAssistantActionBlocks(content: string) {
  const withoutProtocolBlocks = String(content ?? "").replace(/```studio[-_]assistant[-_]protocol\s*[\s\S]*?```/gi, "");
  const withoutCompleteBlocks = withoutProtocolBlocks.replace(/```assistant[-_]action\s*[\s\S]*?```/gi, "");
  const partialBlockStart = withoutCompleteBlocks.search(/```(?:studio[-_]assistant[-_]protocol|assistant[-_]action)/iu);
  if (partialBlockStart >= 0) {
    return withoutCompleteBlocks.slice(0, partialBlockStart).trim();
  }
  return stripLooseAssistantActionJson(withoutCompleteBlocks).trim();
}

function stripLooseAssistantActionJson(content: string) {
  const text = String(content ?? "");
  const markerIndex = firstActionJsonMarker(text);
  if (markerIndex < 0) {
    return text;
  }
  const jsonStart = findLooseActionJsonStart(text, markerIndex);
  if (jsonStart < 0) {
    return text.slice(0, markerIndex);
  }
  const removeStart = trimLooseActionPrefix(text, jsonStart);
  return text.slice(0, removeStart);
}

function hasLooseAssistantActionPayload(content: string) {
  const parsed = parseLooseAssistantActionPayload(content);
  return parsed != null && isAssistantActionPayloadShape(parsed);
}

function parseLooseAssistantActionPayload(content: string): unknown | undefined {
  const text = String(content ?? "");
  let fromIndex = 0;
  while (fromIndex < text.length) {
    const markerIndex = firstActionJsonMarker(text, fromIndex);
    if (markerIndex < 0) {
      return undefined;
    }
    const jsonStart = findLooseActionJsonStart(text, markerIndex);
    if (jsonStart < 0) {
      fromIndex = markerIndex + 1;
      continue;
    }
    const jsonEnd = findLooseActionJsonEnd(text, jsonStart);
    if (jsonEnd < 0) {
      return undefined;
    }
    try {
      return JSON.parse(text.slice(jsonStart, jsonEnd));
    } catch {
      fromIndex = markerIndex + 1;
    }
  }
  return undefined;
}

function findLooseActionJsonEnd(text: string, start: number) {
  const first = text[start];
  if (first !== "{" && first !== "[") {
    return -1;
  }
  const stack: string[] = [first === "{" ? "}" : "]"];
  let inString = false;
  let escaped = false;
  for (let index = start + 1; index < text.length; index += 1) {
    const char = text[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (char === "\\") {
        escaped = true;
      } else if (char === "\"") {
        inString = false;
      }
      continue;
    }
    if (char === "\"") {
      inString = true;
      continue;
    }
    if (char === "{" || char === "[") {
      stack.push(char === "{" ? "}" : "]");
      continue;
    }
    if (char === "}" || char === "]") {
      if (stack.pop() !== char) {
        return -1;
      }
      if (!stack.length) {
        return index + 1;
      }
    }
  }
  return -1;
}

function isAssistantActionPayloadShape(value: unknown): boolean {
  if (Array.isArray(value)) {
    return value.some(isAssistantActionPayloadShape);
  }
  if (!isPlainRecord(value)) {
    return false;
  }
  return ["protocol", "actions", "controls", "toolCalls", "backendToolCalls", "uiControls"]
    .some((key) => Object.prototype.hasOwnProperty.call(value, key))
    || isLikelyActionControl(value);
}

function firstActionJsonMarker(text: string, fromIndex = 0) {
  const markers = ['"toolCalls"', '"backendToolCalls"', '"uiControls"', '"paramKey"', '"actions"', '"controls"', '"protocol"', '"plan"'];
  const indexes = markers
    .map((marker) => text.indexOf(marker, fromIndex))
    .filter((index) => index >= 0)
    .sort((left, right) => left - right);
  return indexes.length ? indexes[0] : -1;
}

function findLooseActionJsonStart(text: string, marker: number) {
  const objectStart = text.lastIndexOf("{", marker);
  const arrayStart = text.lastIndexOf("[", marker);
  if (arrayStart >= 0) {
    const boundary = Math.max(
      text.lastIndexOf("\n", arrayStart - 1),
      text.lastIndexOf("。", arrayStart - 1),
      text.lastIndexOf("！", arrayStart - 1),
      text.lastIndexOf("？", arrayStart - 1),
      text.lastIndexOf(".", arrayStart - 1),
      text.lastIndexOf("!", arrayStart - 1),
      text.lastIndexOf("?", arrayStart - 1),
    );
    if (arrayStart > boundary) {
      return arrayStart;
    }
  }
  return objectStart >= 0 ? objectStart : arrayStart;
}

function trimLooseActionPrefix(text: string, jsonStart: number) {
  const prefix = text.slice(0, jsonStart);
  const lastBoundary = Math.max(
    prefix.lastIndexOf("\n"),
    prefix.lastIndexOf("。"),
    prefix.lastIndexOf("！"),
    prefix.lastIndexOf("？"),
    prefix.lastIndexOf("."),
    prefix.lastIndexOf("!"),
    prefix.lastIndexOf("?"),
  );
  if (lastBoundary < 0) {
    return jsonStart;
  }
  const tail = prefix.slice(lastBoundary + 1);
  return /action|assistant|```|[\u0c80-\u0cff]/i.test(tail) ? lastBoundary + 1 : jsonStart;
}

function appendActionControls(controls: AssistantActionControl[]) {
  let appended = false;
  for (const control of controls) {
    const normalized = normalizeActionControl(control);
    if (!normalized) {
      continue;
    }
    const message = createAssistantMessage("assistant", normalized.detail || "请选择一个候选项继续。");
    message.controls = [normalized];
    messages.value.push(message);
    appended = true;
  }
  if (appended) {
    void nextTick(scrollThreadToBottom);
  }
  return appended;
}

function handleRuntimeClusterListResult(
  interfaceCode: string,
  params: Record<string, unknown>,
): "not_applicable" | "resolved" | "waiting_for_user" | "blocked" {
  if (interfaceCode !== "studio.feature.list" || normalizeRoutePath(String(params.path ?? "")) !== "/runtime-clusters") {
    return "not_applicable";
  }
  const candidates = assistantMemory.lastRuntimeClusterList;
  if (candidates.length === 1) {
    selectGenericMemoryEntity("/runtime-clusters", candidates[0], "unique-runtime-cluster");
    pushProcessEvent({
      stage: `runtime-cluster.unique.${Date.now()}`,
      title: "运行集群已唯一确定",
      detail: `已根据项目授权与资源适用范围唯一确定 ${runtimeClusterCandidateLabel(candidates[0])}。`,
      status: "done",
    });
    return "resolved";
  }
  if (candidates.length === 0) {
    clearRuntimeClusterMemorySelection();
    messages.value.push(createAssistantMessage(
      "assistant",
      "没有找到同时满足项目授权和关联数据源适用范围的运行集群。请先修复项目集群授权或数据源适用集群绑定。",
    ));
    void nextTick(scrollThreadToBottom);
    return "blocked";
  }
  clearRuntimeClusterMemorySelection();
  const appended = appendActionControls([{
    type: "select",
    title: "选择运行集群",
    detail: "存在多个可用候选，请明确选择。默认标记、在线状态和列表顺序仅供参考，不会自动代选。",
    paramKey: "assistantMemory.selectedEntity:/runtime-clusters",
    options: candidates.map((candidate) => ({
      label: runtimeClusterCandidateLabel(candidate),
      value: String(candidate.id),
      description: runtimeClusterCandidateDescription(candidate),
    })),
  }]);
  return appended ? "waiting_for_user" : "blocked";
}

function clearRuntimeClusterMemorySelection() {
  assistantMemory.selectedRuntimeCluster = undefined;
  delete assistantMemory.selectedEntitiesByPath["/runtime-clusters"];
  if (normalizeMemoryEntityPath(assistantMemory.selectedEntity?.sourcePath || "") === "/runtime-clusters") {
    assistantMemory.selectedEntity = undefined;
  }
}

function runtimeClusterCandidateLabel(candidate: AssistantMemoryEntity) {
  const raw = isPlainRecord(candidate.raw) ? (candidate.raw as Record<string, unknown>) : {};
  const id = candidate.id || firstText(raw, ["id"]) || "-";
  const name = candidate.name || firstText(raw, ["name"]) || "未命名集群";
  const code = firstText(raw, ["code", "typeCode"]) || candidate.typeCode || "-";
  const status = firstText(raw, ["status"]) || "UNKNOWN";
  return `${name} | code=${code} | id=${id} | status=${status}`;
}

function runtimeClusterCandidateDescription(candidate: AssistantMemoryEntity) {
  const raw = isPlainRecord(candidate.raw) ? (candidate.raw as Record<string, unknown>) : {};
  return `preferred=${String(raw.preferred === true)}; allowManualOverride=${String(raw.allowManualOverride === true)}`;
}

function appendToolResultMessage(interfaceCode: string, params: Record<string, unknown>, data: unknown) {
  const summary = describeToolChatResult(interfaceCode, params, data);
  if (summary) {
    pushProcessEvent({
      stage: `tool.summary.${interfaceCode}.${Date.now()}`,
      title: "后台结果摘要",
      detail: summary,
      status: "done",
    });
  }
  return false;
}

function normalizeActionControl(control: AssistantActionControl): AssistantChatControl | undefined {
  const rawType = String(control.type || "").trim().toLowerCase();
  const type = normalizeControlType(rawType, control.options);
  const options = normalizeControlOptions(type === "confirm" && !control.options?.length
    ? [
        { label: "确认", value: "confirmed" },
        { label: "取消", value: "cancelled" },
      ]
    : control.options);
  if (!control.title) {
    return undefined;
  }
  if (!isInputControlType(type) && options.length < 2) {
    return undefined;
  }
  return {
    id: control.id || `llm-control-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    type,
    title: control.title,
    detail: control.detail,
    interfaceCode: control.interfaceCode,
    paramKey: control.paramKey || "value",
    placeholder: control.placeholder,
    options,
  };
}

function normalizeControlType(type: string, options: AssistantChatControlOption[] | undefined): AssistantChatControl["type"] {
  if (type === "select" || type === "text" || type === "textarea" || type === "confirm") {
    return type;
  }
  if (type === "input") {
    return "text";
  }
  return options?.length ? "choices" : "text";
}

function isInputControlType(type: AssistantChatControl["type"]) {
  return type === "text" || type === "textarea";
}

function isTextControl(control: AssistantChatControl) {
  return isInputControlType(control.type);
}

function normalizeControlOptions(options: AssistantChatControlOption[] | undefined) {
  return (options ?? [])
    .filter((item) => item && item.value != null && item.label)
    .slice(0, 50)
    .map((item) => ({
      label: String(item.label),
      value: String(item.value),
      description: item.description == null ? undefined : String(item.description),
    }));
}

function resolveToolParams(call: AssistantToolCall): { ok: true; value: Record<string, unknown> } | { ok: false; error: string } {
  const params = { ...(call.params ?? {}) } as Record<string, unknown>;
  const code = call.interfaceCode;
  if (!isFrontendToolAvailable(code)) {
    return { ok: false, error: `当前用户不可调用或未注册的助手工具：${code}` };
  }
  const frontendToolDefinition = findFrontendToolDefinition(code);
  if (frontendToolDefinition && isAssistantLocalContextTool(code)) {
    const declaredParamError = validateDeclaredFrontendToolParams(code, params, frontendToolDefinition);
    if (declaredParamError) {
      return { ok: false, error: declaredParamError };
    }
  }
  if (code === "assistant.context.observe") {
    return {
      ok: true,
      value: cleanObject({
        path: normalizeRoutePath(firstText(params, ["path"])),
      }),
    };
  }
  if (code === "assistant.context.read") {
    return {
      ok: true,
      value: cleanObject({
        path: normalizeMemoryEntityPath(firstText(params, ["path"])),
        kind: firstText(params, ["kind"]),
        limit: readOptionalNumberParam(params, "limit", 1, 50),
      }),
    };
  }
  if (code === "assistant.context.search") {
    const path = normalizeMemoryEntityPath(firstText(params, ["path"]));
    if (!path) {
      return { ok: false, error: "搜索当前上下文对象前需要提供 path。" };
    }
    return {
      ok: true,
      value: cleanObject({
        path,
        keyword: firstText(params, ["keyword"]),
        kind: firstText(params, ["kind"]),
        limit: readOptionalNumberParam(params, "limit", 1, 50),
      }),
    };
  }
  if (code === "assistant.memory.select") {
    const path = normalizeMemoryEntityPath(firstText(params, ["path"]));
    if (!path) {
      return { ok: false, error: "更新助手记忆前需要提供 path。" };
    }
    const id = firstText(params, ["id"]);
    const name = firstText(params, ["name"]);
    const physicalLocator = firstText(params, ["physicalLocator"]);
    const ordinal = readOptionalNumberParam(params, "ordinal", 1, 1000);
    if (!id && !name && !physicalLocator && ordinal == null) {
      return { ok: false, error: "更新助手记忆前需要提供 id、name、physicalLocator 或 ordinal。" };
    }
    return {
      ok: true,
      value: cleanObject({
        path,
        id,
        name,
        physicalLocator,
        ordinal,
      }),
    };
  }
  if (code === "studio.navigation.open") {
    const path = normalizeRoutePath(firstText(params, ["path"]));
    if (!path) {
      return { ok: false, error: "导航前需要提供 path。" };
    }
    if (!findAssistantFeature(path)) {
      return { ok: false, error: `当前用户没有进入 ${path} 的可见功能权限。` };
    }
    return { ok: true, value: { path } };
  }
  if (code === "studio.feature.list") {
    const path = normalizeRoutePath(firstText(params, ["path"]));
    const feature = findAssistantFeature(path);
    if (!path || !feature) {
      return { ok: false, error: "读取功能数据前需要提供当前用户可见的 path。" };
    }
    const spec = assistantFeatureToolSpecs.value[normalizeRoutePath(feature.path)];
    if (!spec?.list) {
      return { ok: false, error: `${feature.label} 暂未开放可自动读取的列表或概览工具。` };
    }
    const declaredParamError = validateDeclaredFeatureToolParams("studio.feature.list", feature.label, params, spec.list);
    if (declaredParamError) {
      return { ok: false, error: declaredParamError };
    }
    const value = normalizeFeatureToolParams(params, path);
    const runtimeClusterError = validateAssistantRuntimeClusterDecision(readRuntimeClusterId(value));
    return runtimeClusterError ? { ok: false, error: runtimeClusterError } : { ok: true, value };
  }
  if (code === "studio.feature.get") {
    const path = normalizeRoutePath(firstText(params, ["path"]));
    const feature = findAssistantFeature(path);
    if (!path || !feature) {
      return { ok: false, error: "读取详情前需要提供当前用户可见的 path。" };
    }
    const spec = assistantFeatureToolSpecs.value[normalizeRoutePath(feature.path)];
    if (!spec?.get) {
      return { ok: false, error: `${feature.label} 暂未开放可自动读取的详情工具。` };
    }
    const id = firstText(params, ["id"]);
    if (!id) {
      return { ok: false, error: `读取 ${feature.label} 详情前需要提供 id。` };
    }
    const declaredParamError = validateDeclaredFeatureToolParams("studio.feature.get", feature.label, params, spec.get);
    if (declaredParamError) {
      return { ok: false, error: declaredParamError };
    }
    const value = normalizeFeatureToolParams({ ...params, id }, path);
    const runtimeClusterError = validateAssistantRuntimeClusterDecision(readRuntimeClusterId(value));
    return runtimeClusterError ? { ok: false, error: runtimeClusterError } : { ok: true, value };
  }
  if (code === "studio.feature.action") {
    return resolveFeatureActionParams(params);
  }
  if (code === "assistant.script.execute") {
    const entrypointId = firstText(params, ["entrypointId"]);
    const input = params.input;
    const runtimeClusterId = readOptionalPositiveIntegerParam(params, "runtimeClusterId");
    if (!entrypointId) {
      return { ok: false, error: "执行助手脚本前需要提供 entrypointId。" };
    }
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      return { ok: false, error: "执行助手脚本前需要提供 input 对象。" };
    }
    if (params.runtimeClusterId == null || params.runtimeClusterId === "") {
      return { ok: false, error: "执行助手脚本前需要提供 runtimeClusterId。" };
    }
    if (params.runtimeClusterId != null && params.runtimeClusterId !== "" && runtimeClusterId == null) {
      return { ok: false, error: "执行助手脚本时 runtimeClusterId 必须是正整数。" };
    }
    const runtimeClusterError = validateAssistantRuntimeClusterDecision(runtimeClusterId);
    if (runtimeClusterError) {
      return { ok: false, error: runtimeClusterError };
    }
    return {
      ok: true,
      value: {
        entrypointId,
        input,
        skillId: firstText(params, ["skillId"]),
        runtimeClusterId,
      },
    };
  }
  return { ok: false, error: `未注册或不允许自动执行的接口工具：${code}` };
}

async function executeAssistantTool(interfaceCode: string, params: Record<string, unknown>) {
  executingToolCodes.value = [...new Set([...executingToolCodes.value, interfaceCode])];
  pushProcessEvent({
    stage: `tool.${interfaceCode}`,
    title: "调用接口工具",
    detail: describeToolStart(interfaceCode, params),
    status: "running",
  });

  try {
    const output = await callAssistantTool(interfaceCode, params);
    const data = output.data;
    toolResults.value.push(createToolResult(interfaceCode, params, data, true, undefined, output.execution));
    applyToolData(interfaceCode, params, data);
    markProcessDone(`tool.${interfaceCode}`, "接口调用完成", describeToolResult(interfaceCode, data));
    return { ok: true as const, data };
  } catch (error) {
    const messageText = resolveErrorMessage(error, `${interfaceCode} 调用失败`);
    markProcessDone(`tool.${interfaceCode}`, "接口返回错误", "助手正在根据失败上下文重新规划。", "warning");
    recordAssistantToolFailure(interfaceCode, params, messageText);
    return { ok: false as const, error: messageText };
  } finally {
    executingToolCodes.value = executingToolCodes.value.filter((code) => code !== interfaceCode);
  }
}

function recordAssistantToolFailure(interfaceCode: string, params: Record<string, unknown>, error: string) {
  toolResults.value.push(createToolResult(interfaceCode, params, null, false, error));
}

function requiresToolConfirmation(interfaceCode: string, params: Record<string, unknown>) {
  if (interfaceCode === "studio.feature.action") {
    const resolved = resolveFeatureActionDefinition(
      String(params.path ?? ""),
      String(params.action ?? ""),
      String(params.resource ?? ""),
    );
    return resolved.definition?.mutation !== false;
  }
  return Boolean(findFrontendToolDefinition(interfaceCode)?.mutation);
}

function queueToolConfirmation(interfaceCode: string, params: Record<string, unknown>) {
  const id = `tool-confirm-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const title = "确认执行 Studio 操作";
  const detail = describeToolConfirmation(interfaceCode, params);
  pendingToolConfirmations[id] = {
    id,
    interfaceCode,
    params,
    title,
    detail,
    createdAt: Date.now(),
  };
  const control = normalizeActionControl({
    id: `assistant-confirm-control-${id}`,
    type: "confirm",
    title,
    detail,
    paramKey: "assistantConfirmationId",
    interfaceCode,
    options: [
      { label: "确认执行", value: `confirm:${id}` },
      { label: "取消", value: `cancel:${id}` },
    ],
  });
  if (!control) {
    return;
  }
  const message = createAssistantMessage("assistant", "这个操作会改变系统状态，需要你确认后执行。");
  message.controls = [control];
  messages.value.push(message);
  void nextTick(scrollThreadToBottom);
}

async function handlePendingToolConfirmation(value: string) {
  const [decision, confirmationId] = value.split(":");
  const pending = pendingToolConfirmations[confirmationId];
  if (!pending) {
    messages.value.push(createAssistantMessage("assistant", "待确认操作已经过期或不存在。"));
    return;
  }
  delete pendingToolConfirmations[confirmationId];
  if (decision !== "confirm") {
    messages.value.push(createAssistantMessage("assistant", "已取消该操作，没有调用 Studio 业务接口。"));
    await nextTick(scrollThreadToBottom);
    return;
  }
  messages.value.push(createAssistantMessage("assistant", `已确认，开始执行：${pending.detail}`));
  const confirmedParams = pending.interfaceCode === "studio.feature.action"
    ? { ...pending.params, confirmed: true }
    : pending.params;
  const result = await executeAssistantTool(pending.interfaceCode, confirmedParams);
  if (result.ok) {
    appendToolResultMessage(pending.interfaceCode, confirmedParams, result.data);
    await continueAfterToolResults(1);
  } else {
    await continueAfterToolFailure(1);
  }
}

function describeToolConfirmation(interfaceCode: string, params: Record<string, unknown>) {
  if (interfaceCode === "studio.feature.action") {
    const feature = findAssistantFeature(String(params.path ?? ""));
    const resource = firstText(params, ["resource"]);
    const id = firstText(params, ["id"]);
    const action = firstText(params, ["action"]);
    return `${feature?.label ?? String(params.path ?? "")}：${action}${resource ? ` / ${resource}` : ""}${id ? `，目标 ID ${id}` : ""}。`;
  }
  return `${interfaceCode}。`;
}

function observeAssistantContext(params: Record<string, unknown>) {
  const requestedPath = normalizeRoutePath(firstText(params, ["path"]));
  const currentRoutePath = normalizeRoutePath(route.path || String(route.fullPath ?? "").split("?")[0]);
  const currentFeature = findAssistantFeature(requestedPath || currentRoutePath);
  return cleanObject({
    route: route.fullPath,
    routePath: currentRoutePath,
    requestedPath,
    tenantId: authStore.currentTenantId,
    projectId: authStore.currentProjectId,
    currentFeature: currentFeature ? {
      path: normalizeRoutePath(currentFeature.path),
      label: currentFeature.label,
      group: currentFeature.group,
      caption: currentFeature.caption,
    } : undefined,
    assistantMode: assistantMode.value,
    responseLanguage: responseLanguage.value,
    assistantMemory: summarizeAssistantMemory(),
    pageContext: assistantPageContext.value,
    activeControls: activeComposerControls.value.map((control) => ({
      type: control.type,
      title: control.title,
      paramKey: control.paramKey,
      optionCount: control.options?.length ?? 0,
    })),
  });
}

function readAssistantBusinessContext(params: Record<string, unknown>) {
  const requestedPath = normalizeMemoryEntityPath(firstText(params, ["path"]));
  const currentRoutePath = normalizeMemoryEntityPath(route.path || String(route.fullPath ?? "").split("?")[0]);
  const contextPath = normalizeMemoryEntityPath(assistantPageContext.value?.path || "");
  const path = requestedPath || contextPath || currentRoutePath || assistantMemory.lastEntityListPath || "";
  const normalizedKind = normalizeMemoryText(firstText(params, ["kind"]));
  const limit = readOptionalNumberParam(params, "limit", 1, 50) ?? 10;
  const pageEntities = path ? pageContextEntitiesForPath(path) : [];
  const knownEntities = path ? knownEntitiesForPath(path) : [];
  const filteredKnownEntities = normalizedKind
    ? knownEntities.filter((entity) => normalizeMemoryText(entity.kind) === normalizedKind)
    : knownEntities;
  const selectedEntity = selectedMemoryEntityForPath(path);
  return cleanObject({
    path,
    label: path ? memoryPathLabel(path) : undefined,
    route: route.fullPath,
    routePath: currentRoutePath,
    pageContextSource: assistantPageContext.value?.source,
    pageContextSummary: assistantPageContext.value?.summary,
    filters: assistantPageContext.value?.filters,
    pagination: assistantPageContext.value?.pagination,
    activeObject: pageEntities[0] ? summarizeMemoryEntity(pageEntities[0]) : undefined,
    selectedEntity: selectedEntity ? summarizeMemoryEntity(selectedEntity) : undefined,
    selectedDatasource: assistantMemory.selectedDatasource ? summarizeMemoryEntity(assistantMemory.selectedDatasource) : undefined,
    pageObjectCount: pageEntities.length,
    candidateCount: filteredKnownEntities.length,
    pageObjects: pageEntities.slice(0, limit).map(summarizeMemoryEntity),
    recentCandidates: filteredKnownEntities.slice(0, limit).map(summarizeMemoryEntity),
  });
}

function searchAssistantContextObjects(params: Record<string, unknown>) {
  const path = normalizeMemoryEntityPath(firstText(params, ["path"]));
  if (!path) {
    throw new Error("搜索当前上下文对象前需要提供 path。");
  }
  const keyword = firstText(params, ["keyword"]);
  const normalizedKeyword = normalizeMemoryText(keyword);
  const kind = firstText(params, ["kind"]);
  const normalizedKind = normalizeMemoryText(kind);
  const limit = readOptionalNumberParam(params, "limit", 1, 50) ?? 10;
  const candidates = knownEntitiesForPath(path);
  const matches = candidates.filter((entity) => {
    if (normalizedKind && normalizeMemoryText(entity.kind) !== normalizedKind) {
      return false;
    }
    if (!normalizedKeyword) {
      return true;
    }
    return [
      entity.id,
      entity.name,
      entity.typeCode,
      entity.physicalLocator,
      entity.modelKind,
      entity.sourcePath,
    ].some((value) => normalizeMemoryText(value).includes(normalizedKeyword));
  });
  const ranked = matches.sort((left, right) => entitySearchRank(right, normalizedKeyword) - entitySearchRank(left, normalizedKeyword));
  return cleanObject({
    path,
    label: memoryPathLabel(path),
    keyword,
    kind,
    candidateCount: candidates.length,
    matchedCount: ranked.length,
    items: ranked.slice(0, limit).map(summarizeMemoryEntity),
  });
}

function entitySearchRank(entity: AssistantMemoryEntity, normalizedKeyword: string) {
  if (!normalizedKeyword) {
    return 0;
  }
  const id = normalizeMemoryText(entity.id);
  const name = normalizeMemoryText(entity.name);
  const locator = normalizeMemoryText(entity.physicalLocator);
  if (id === normalizedKeyword || name === normalizedKeyword || locator === normalizedKeyword) {
    return 100;
  }
  if (name.startsWith(normalizedKeyword) || locator.startsWith(normalizedKeyword)) {
    return 60;
  }
  if (name.includes(normalizedKeyword) || locator.includes(normalizedKeyword)) {
    return 30;
  }
  return 1;
}

function selectAssistantMemoryFromTool(params: Record<string, unknown>) {
  const path = normalizeMemoryEntityPath(firstText(params, ["path"]));
  const candidates = knownEntitiesForPath(path);
  const entity = resolveMemoryToolSelection(candidates, params);
  if (!entity) {
    throw new Error(`没有在最近的${memoryPathLabel(path)}候选中找到匹配对象。请先读取候选列表，或让用户选择。`);
  }
  selectGenericMemoryEntity(path, entity, "assistant.memory.select");
  return {
    selected: true,
    path,
    label: memoryPathLabel(path),
    entity: summarizeMemoryEntity(entity),
    candidateCount: candidates.length,
  };
}

function resolveMemoryToolSelection(candidates: AssistantMemoryEntity[], params: Record<string, unknown>) {
  const ordinal = readOptionalNumberParam(params, "ordinal", 1, 1000);
  if (ordinal != null && candidates[ordinal - 1]) {
    return candidates[ordinal - 1];
  }
  const id = firstText(params, ["id"]);
  if (id) {
    const match = candidates.find((item) => String(item.id ?? "") === String(id));
    if (match) {
      return match;
    }
  }
  const physicalLocator = normalizeMemoryText(firstText(params, ["physicalLocator"]));
  if (physicalLocator) {
    const match = candidates.find((item) => normalizeMemoryText(item.physicalLocator || item.name) === physicalLocator);
    if (match) {
      return match;
    }
  }
  const name = normalizeMemoryText(firstText(params, ["name"]));
  if (name) {
    const exact = candidates.find((item) => normalizeMemoryText(item.name) === name);
    if (exact) {
      return exact;
    }
    const fuzzy = candidates.filter((item) => normalizeMemoryText(item.name).includes(name));
    if (fuzzy.length === 1) {
      return fuzzy[0];
    }
  }
  return undefined;
}

async function callAssistantTool(interfaceCode: string, params: Record<string, unknown>) {
  switch (interfaceCode) {
    case "assistant.context.observe":
      return toolOutput(observeAssistantContext(params));
    case "assistant.context.read":
      return toolOutput(readAssistantBusinessContext(params));
    case "assistant.context.search":
      return toolOutput(searchAssistantContextObjects(params));
    case "assistant.memory.select":
      return toolOutput(selectAssistantMemoryFromTool(params));
    case "studio.navigation.open": {
      const feature = findAssistantFeature(String(params.path));
      if (!feature) {
        throw new Error(`当前用户没有进入 ${String(params.path)} 的可见功能权限。`);
      }
      await router.push(feature.path);
      return toolOutput({
        path: feature.path,
        label: feature.label,
        navigated: true,
      });
    }
    case "studio.feature.list":
      return callBackendStudioReadTool(interfaceCode, params);
    case "studio.feature.get":
      return callBackendStudioReadTool(interfaceCode, params);
    case "studio.feature.action":
      return callBackendStudioActionTool(params);
    case "assistant.script.execute":
      return callBackendAssistantScriptTool(params);
    default:
      throw new Error(`未注册前端接口工具：${interfaceCode}`);
  }
}

function toolOutput(data: unknown, execution?: Record<string, unknown>): AssistantToolExecutionOutput {
  return {
    data,
    execution,
  };
}

function backendToolOutput(result: AssistantToolExecutionResult): AssistantToolExecutionOutput {
  return toolOutput(result.data, {
    schema: result.schema,
    interfaceCode: result.interfaceCode,
    path: result.path,
    action: result.action,
    resource: result.resource,
    entrypointId: result.entrypointId,
    executedBy: result.executedBy,
    runtimeClusterId: result.runtimeClusterId,
    mutation: result.mutation,
    requiresConfirmation: result.requiresConfirmation,
    params: result.params,
    effectiveParams: result.effectiveParams,
    defaultedParams: result.defaultedParams,
  });
}

async function callBackendStudioReadTool(
  interfaceCode: string,
  params: Record<string, unknown>,
) {
  try {
    const result = await studioApi.assistant.executeTool({ interfaceCode, params });
    if (result?.executedBy === "backend") {
      pushProcessEvent({
        stage: `tool.backend.${interfaceCode}.${Date.now()}`,
        title: "后端工具网关",
        detail: `${interfaceCode} 已由后端受控执行。`,
        status: "done",
      });
      return backendToolOutput(result);
    }
    throw new Error("后端工具网关没有返回受控执行结果。");
  } catch (error) {
    pushProcessEvent({
      stage: `tool.backend-rejected.${interfaceCode}.${Date.now()}`,
      title: "后端工具网关拒绝",
      detail: `${resolveErrorMessage(error, "后端工具网关拒绝执行")}；本轮不执行该 Studio 操作。`,
      status: "warning",
    });
    throw error;
  }
}

async function callBackendStudioActionTool(
  params: Record<string, unknown>,
) {
  try {
    const result = await studioApi.assistant.executeTool({ interfaceCode: "studio.feature.action", params });
    if (result?.executedBy === "backend") {
      if (result.requiresConfirmation) {
        const error = new Error("后端要求确认后执行该 Studio 操作。") as Error & { backendRequiresConfirmation?: boolean };
        error.backendRequiresConfirmation = true;
        throw error;
      }
      pushProcessEvent({
        stage: `tool.backend.studio.feature.action.${Date.now()}`,
        title: "后端动作网关",
        detail: `${describeToolConfirmation("studio.feature.action", params)} 已由后端受控执行。`,
        status: "done",
      });
      return backendToolOutput(result);
    }
    throw new Error("后端动作网关没有返回受控执行结果。");
  } catch (error) {
    if ((error as Error & { backendRequiresConfirmation?: boolean }).backendRequiresConfirmation) {
      throw error;
    }
    pushProcessEvent({
      stage: `tool.backend-rejected.studio.feature.action.${Date.now()}`,
      title: "后端动作网关拒绝",
      detail: `${resolveErrorMessage(error, "后端动作网关拒绝执行")}；本轮不执行该 Studio 操作。`,
      status: "warning",
    });
    throw error;
  }
}

async function callBackendAssistantScriptTool(params: Record<string, unknown>) {
  try {
    const result = await studioApi.assistant.executeTool({ interfaceCode: "assistant.script.execute", params });
    if (result?.executedBy === "worker") {
      pushProcessEvent({
        stage: `tool.backend.assistant.script.execute.${Date.now()}`,
        title: "Worker 脚本工具",
        detail: `助手脚本 ${String(params.entrypointId ?? "")} 已在运行集群 ${String(result.runtimeClusterId ?? "")} 执行。`,
        status: "done",
      });
      return backendToolOutput(result);
    }
  } catch (error) {
    pushProcessEvent({
      stage: `tool.backend-rejected.assistant.script.execute.${Date.now()}`,
      title: "后端脚本工具拒绝",
      detail: resolveErrorMessage(error, "后端脚本工具拒绝执行"),
      status: "warning",
    });
    throw error;
  }
  throw new Error("后端脚本工具没有返回受控执行结果。");
}

function resolveFeatureActionParams(params: Record<string, unknown>): { ok: true; value: Record<string, unknown> } | { ok: false; error: string } {
  const path = normalizeRoutePath(firstText(params, ["path"]));
  const feature = findAssistantFeature(path);
  if (!path || !feature) {
    return { ok: false, error: "执行功能动作前需要提供当前用户可见的 path。" };
  }

  const rawAction = firstText(params, ["action"]);
  if (!rawAction) {
    return { ok: false, error: `执行 ${feature.label} 动作前需要提供 action。` };
  }
  if (isForbiddenFeatureAction(rawAction)) {
    return { ok: false, error: "助手不开放删除、移除、清空、取消或类似破坏性动作。" };
  }

  const resource = normalizeFeatureResource(firstText(params, ["resource"]));
  const resolved = resolveFeatureActionDefinition(path, rawAction, resource);
  if (!resolved.definition) {
    return { ok: false, error: resolved.error || `当前功能暂未开放动作：${rawAction}` };
  }

  const payload = firstRecordParam(params, ["payload"]);
  const normalizedPayload = normalizeFeatureActionPayload(
    path,
    resolved.definition.action,
    resolved.definition.resource || resource,
    payload,
  );
  const normalizedParams = {
    ...params,
    path,
    action: resolved.definition.action,
    resource: resolved.definition.resource || resource || undefined,
    id: firstText(params, ["id"]),
    datasourceId: firstText(params, ["datasourceId"]),
    physicalLocators: readParamValue(params, "physicalLocators"),
    typeCode: firstText(params, ["typeCode"]),
    subscriptionId: firstText(params, ["subscriptionId"]),
    subscriptionName: firstText(params, ["subscriptionName"]),
    sourceAlias: firstText(params, ["sourceAlias"]),
    incrColumn: firstText(params, ["incrColumn"]),
    incrModel: firstText(params, ["incrModel"]),
    keyword: firstText(params, ["keyword"]),
    payload: normalizedPayload,
  } as Record<string, unknown>;

  const missing = (resolved.definition.requiredValues ?? [])
    .filter((key) => !hasFeatureActionValue(normalizedParams, key));
  const missingError = missing.length
    ? `${feature.label} 的 ${resolved.definition.action} 动作缺少必要参数：${missing.join(", ")}。`
    : "";
  const declaredParamError = validateDeclaredFeatureActionParams(feature.label, params, resolved.definition);
  const payloadError = validateFeatureActionPayload(path, resolved.definition.action, resolved.definition.resource || resource, normalizedPayload);
  const errors = [missingError, declaredParamError, payloadError].filter(Boolean);
  if (errors.length) {
    return { ok: false, error: errors.join(" ") };
  }

  const value = cleanObject(normalizedParams);
  const runtimeClusterError = validateAssistantRuntimeClusterDecision(readRuntimeClusterId(value));
  return runtimeClusterError ? { ok: false, error: runtimeClusterError } : { ok: true, value };
}

function readRuntimeClusterId(params: Record<string, unknown>) {
  const direct = params.runtimeClusterId;
  if (direct != null && String(direct).trim()) {
    return direct;
  }
  const payload = isPlainRecord(params.payload) ? (params.payload as Record<string, unknown>) : undefined;
  return payload?.runtimeClusterId;
}

function validateAssistantRuntimeClusterDecision(runtimeClusterId: unknown) {
  if (runtimeClusterId == null || !String(runtimeClusterId).trim()) {
    return "";
  }
  const requestedId = String(runtimeClusterId).trim();
  const selected = assistantMemory.selectedRuntimeCluster
    || assistantMemory.selectedEntitiesByPath["/runtime-clusters"];
  if (selected?.id) {
    if (String(selected.id) === requestedId) {
      return "";
    }
    return `请求运行集群 ${requestedId} 与用户已选择的集群 ${selected.id} 不一致，请先让用户明确选择覆盖目标。`;
  }

  const pageFilters = isPlainRecord(assistantPageContext.value?.filters)
    ? (assistantPageContext.value?.filters as Record<string, unknown>)
    : {};
  const pageRuntimeClusterId = pageFilters.runtimeClusterId;
  const activeObject = assistantPageContext.value?.activeObject;
  const pageRuntimeInvalid = String(activeObject?.status ?? "").toUpperCase() === "RUNTIME_INVALID"
    || activeObject?.metadata?.runtimeValid === false;
  if (!pageRuntimeInvalid && pageRuntimeClusterId != null && String(pageRuntimeClusterId) === requestedId) {
    return "";
  }

  const candidates = assistantMemory.lastRuntimeClusterList;
  if (candidates.length === 1 && String(candidates[0]?.id ?? "") === requestedId) {
    selectGenericMemoryEntity("/runtime-clusters", candidates[0], "unique-runtime-cluster");
    return "";
  }
  if (candidates.length > 1) {
    return "运行集群候选不唯一，不能按 preferred、在线状态或列表顺序自动选择。请先输出 assistantMemory.selectedEntity:/runtime-clusters 选择控件并等待用户。";
  }
  return "执行集群范围操作前必须先读取 /runtime-clusters，并依据关联数据源/模型适用范围得到唯一候选或让用户选择。";
}

function normalizeFeatureActionPayload(
  path: string,
  action: string,
  resource: string,
  payload: Record<string, unknown> | undefined,
) {
  if (path === "/data-development") {
    return normalizeDataDevelopmentActionPayload(action, resource, payload);
  }
  return payload;
}

function normalizeDataDevelopmentActionPayload(
  action: string,
  resource: string,
  payload: Record<string, unknown> | undefined,
) {
  const raw = isPlainRecord(payload) ? payload : {};
  if (resource === "sql" && action === "executeSql") {
    const scriptType = normalizeScriptTypeValue(firstText(raw, ["scriptType"]));
    return cleanObject({
      ...raw,
      datasourceId: firstText(raw, ["datasourceId"]),
      scriptType,
      content: normalizeScriptContent(firstText(raw, ["content"])),
      maxRows: readOptionalNumberParam(raw, "maxRows", 1, 10000),
    });
  }
  if (resource === "scripts" && action === "saveScript") {
    const content = normalizeScriptContent(firstText(raw, ["content"]));
    const scriptType = normalizeScriptTypeValue(firstText(raw, ["scriptType"]));
    return cleanObject({
      ...raw,
      id: firstText(raw, ["id"]),
      directoryId: firstText(raw, ["directoryId"]),
      fileName: normalizeScriptFileName(firstText(raw, ["fileName"])),
      scriptType,
      datasourceId: firstText(raw, ["datasourceId"]),
      environmentId: firstText(raw, ["environmentId"]),
      description: firstText(raw, ["description"]),
      content,
    });
  }
  if (resource === "scripts" && action === "executeScript") {
    const content = normalizeScriptContent(firstText(raw, ["content"]));
    const scriptType = normalizeScriptTypeValue(firstText(raw, ["scriptType"]));
    return cleanObject({
      ...raw,
      scriptType,
      datasourceId: firstText(raw, ["datasourceId"]),
      environmentId: firstText(raw, ["environmentId"]),
      content,
      arguments: firstRecordParam(raw, ["arguments"]),
      maxRows: readOptionalNumberParam(raw, "maxRows", 1, 10000),
    });
  }
  return payload;
}

function validateFeatureActionPayload(
  path: string,
  action: string,
  resource: string,
  payload: unknown,
) {
  if (path !== "/data-development" || !isPlainRecord(payload)) {
    return "";
  }
  const missing: string[] = [];
  if (resource === "sql" && action === "executeSql") {
    if (!firstText(payload, ["datasourceId"])) {
      missing.push("payload.datasourceId");
    }
    if (!resolveScriptTypeParam(payload)) {
      missing.push("payload.scriptType");
    }
    if (!firstText(payload, ["content"])) {
      missing.push("payload.content");
    }
  }
  if (resource === "scripts" && action === "saveScript") {
    if (!firstText(payload, ["fileName"])) {
      missing.push("payload.fileName");
    }
    if (!resolveScriptTypeParam(payload)) {
      missing.push("payload.scriptType");
    }
    if (!firstText(payload, ["content"])) {
      missing.push("payload.content");
    }
  }
  if (resource === "scripts" && action === "executeScript") {
    if (!resolveScriptTypeParam(payload)) {
      missing.push("payload.scriptType");
    }
    if (!firstText(payload, ["content"])) {
      missing.push("payload.content");
    }
  }
  return missing.length
    ? `数据开发 ${resource || "-"} / ${action} 动作缺少接口必填字段：${missing.join(", ")}。请根据上下文补齐，不能推断时用 controls 询问用户。`
    : "";
}

function normalizeFeatureToolParams(params: Record<string, unknown>, path: string) {
  const normalizedPath = normalizeRoutePath(path);
  const optionalValues = assistantFeatureToolSpecs.value[normalizedPath]?.list?.optionalValues ?? [];
  const normalizedParams: Record<string, unknown> = {
    ...params,
    path: normalizedPath,
  };
  if (optionalValues.includes("pageNo")) {
    const pageNo = readOptionalNumberParam(params, "pageNo", 1, 10000);
    if (pageNo == null) {
      delete normalizedParams.pageNo;
    } else {
      normalizedParams.pageNo = pageNo;
    }
  }
  if (optionalValues.includes("pageSize")) {
    const pageSize = readOptionalNumberParam(params, "pageSize", 1, 100);
    if (pageSize == null) {
      delete normalizedParams.pageSize;
    } else {
      normalizedParams.pageSize = pageSize;
    }
  }
  return normalizedParams;
}

function isAssistantLocalContextTool(interfaceCode: string) {
  return interfaceCode === "assistant.context.observe"
    || interfaceCode === "assistant.context.read"
    || interfaceCode === "assistant.context.search"
    || interfaceCode === "assistant.memory.select";
}

function validateDeclaredFrontendToolParams(
  interfaceCode: string,
  params: Record<string, unknown>,
  definition: AssistantFrontendToolDefinition,
) {
  const allowed = new Set<string>();
  addDeclaredFeatureParamKeys(allowed, definition.requiredValues ?? []);
  addDeclaredFeatureParamKeys(allowed, definition.optionalValues ?? []);
  const missing = (definition.requiredValues ?? [])
    .filter((key) => !hasDeclaredFrontendToolParamValue(params, key));
  const undeclared = Object.keys(params).filter((key) => !allowed.has(key));
  const errors: string[] = [];
  if (missing.length) {
    errors.push(`${interfaceCode} 缺少必需参数：${missing.join(", ")}。`);
  }
  if (undeclared.length) {
    errors.push(`${interfaceCode} 未声明参数：${undeclared.join(", ")}。请让 LLM 使用 frontendTools 中的规范参数，浏览器不能推断或改写。`);
  }
  return errors.join(" ");
}

function hasDeclaredFrontendToolParamValue(params: Record<string, unknown>, key: string) {
  const value = readParamValue(params, key);
  if (isPlainRecord(value)) {
    return Object.keys(value).length > 0;
  }
  if (Array.isArray(value)) {
    return value.length > 0;
  }
  return value != null && String(value).trim() !== "";
}

function validateDeclaredFeatureToolParams(
  interfaceCode: "studio.feature.list" | "studio.feature.get",
  featureLabel: string,
  params: Record<string, unknown>,
  definition: { requiredValues?: string[]; optionalValues?: string[] } | undefined,
) {
  const allowed = new Set(["path"]);
  addDeclaredFeatureParamKeys(allowed, definition?.requiredValues ?? []);
  addDeclaredFeatureParamKeys(allowed, definition?.optionalValues ?? []);
  const undeclared = Object.keys(params).filter((key) => !allowed.has(key));
  return undeclared.length
    ? `${featureLabel} 的 ${interfaceCode} 未声明参数：${undeclared.join(", ")}。请让 LLM 根据 operation catalog 使用规范参数，不能在前端推断或改写。`
    : "";
}

function validateDeclaredFeatureActionParams(
  featureLabel: string,
  params: Record<string, unknown>,
  definition: AssistantFeatureActionDefinition,
) {
  const allowed = new Set([
    "path",
    "action",
    "resource",
    "confirmed",
  ]);
  addDeclaredFeatureParamKeys(allowed, definition.requiredValues ?? []);
  addDeclaredFeatureParamKeys(allowed, definition.optionalValues ?? []);
  const undeclared = Object.keys(params).filter((key) => !allowed.has(key));
  return undeclared.length
    ? `${featureLabel} 的 studio.feature.action ${definition.action} 未声明参数：${undeclared.join(", ")}。请让 LLM 根据 operation catalog 使用规范参数，不能在前端推断或改写。`
    : "";
}

function addDeclaredFeatureParamKeys(allowed: Set<string>, keys: string[]) {
  for (const key of keys) {
    const normalized = String(key ?? "").trim();
    if (!normalized) {
      continue;
    }
    allowed.add(normalized);
    const topLevel = normalized.split(".")[0];
    if (topLevel) {
      allowed.add(topLevel);
    }
  }
}

function resolveFeatureActionDefinition(path: string, action: unknown, resource?: string) {
  const normalizedPath = normalizeRoutePath(path);
  const normalizedResource = normalizeFeatureResource(resource);
  const actionCandidates = assistantFeatureActionDefinitions.value
    .filter((item) => normalizeRoutePath(item.path) === normalizedPath && featureActionMatches(item, action));
  if (!actionCandidates.length) {
    return {
      definition: undefined,
      error: `当前功能暂未开放动作 ${String(action ?? "")}。可用动作：${describeSupportedFeatureActions(normalizedPath)}。`,
    };
  }
  if (normalizedResource) {
    const resourceCandidates = actionCandidates.filter((item) => normalizeFeatureResource(item.resource) === normalizedResource);
    if (resourceCandidates.length === 1) {
      return { definition: resourceCandidates[0] };
    }
    if (resourceCandidates.length > 1) {
      return {
        definition: undefined,
        error: `动作 ${String(action ?? "")} 在 ${normalizedResource} 下有多个候选，请补充更明确的 action。`,
      };
    }
  }
  const unscopedCandidates = actionCandidates.filter((item) => !item.resource);
  if (unscopedCandidates.length === 1) {
    return { definition: unscopedCandidates[0] };
  }
  if (actionCandidates.length === 1) {
    return { definition: actionCandidates[0] };
  }
  return {
    definition: undefined,
    error: `动作 ${String(action ?? "")} 需要补充 resource 才能区分：${[...new Set(actionCandidates.map((item) => item.resource).filter(Boolean))].join(", ")}。`,
  };
}

function featureActionMatches(definition: AssistantFeatureActionDefinition, action: unknown) {
  const normalized = normalizeFeatureActionName(action).toLowerCase();
  if (!normalized) {
    return false;
  }
  const candidates = [definition.action, ...(definition.aliases ?? [])]
    .map((item) => normalizeFeatureActionName(item).toLowerCase());
  return candidates.includes(normalized);
}

function describeSupportedFeatureActions(path: string) {
  const actions = assistantFeatureActionDefinitions.value
    .filter((item) => normalizeRoutePath(item.path) === normalizeRoutePath(path))
    .map((item) => item.resource ? `${item.action}(${item.resource})` : item.action);
  return actions.length ? actions.join(", ") : "无";
}

function isForbiddenFeatureAction(action: unknown) {
  const raw = String(action ?? "");
  const normalized = normalizeFeatureActionName(raw).toLowerCase();
  return FORBIDDEN_ASSISTANT_ACTION_PATTERN.test(raw)
    || ["delete", "remove", "drop", "truncate", "purge", "destroy", "batchdelete", "unfollow", "cancel"]
      .some((keyword) => normalized.includes(keyword));
}

function normalizeFeatureActionName(action: unknown) {
  return normalizeFeatureView(action);
}

function normalizeFeatureResource(resource: unknown) {
  return normalizeFeatureView(resource);
}

function firstRecordParam(params: Record<string, unknown>, keys: string[]) {
  for (const key of keys) {
    const value = readParamValue(params, key);
    if (isPlainRecord(value)) {
      return value;
    }
  }
  return undefined;
}

function hasFeatureActionValue(params: Record<string, unknown>, key: string) {
  if (key === "payload") {
    return isPlainRecord(params.payload) && Object.keys(params.payload).length > 0;
  }
  const aliases: Record<string, string[]> = {
    id: ["id"],
    datasourceId: ["datasourceId"],
    typeCode: ["typeCode"],
    physicalLocators: ["physicalLocators"],
    subscriptionId: ["subscriptionId"],
    subscriptionName: ["subscriptionName"],
  };
  if (aliases[key]) {
    const value = firstText(params, aliases[key]);
    if (value) {
      return true;
    }
    return aliases[key].some((alias) => Array.isArray(readParamValue(params, alias)) && (readParamValue(params, alias) as unknown[]).length > 0);
  }
  const value = readParamValue(params, key);
  if (isPlainRecord(value)) {
    return true;
  }
  return value != null && String(value).trim() !== "";
}

function readOptionalNumberParam(params: Record<string, unknown>, key: string, min: number, max: number) {
  const value = params[key];
  if (value == null || value === "") {
    return undefined;
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return undefined;
  }
  return Math.min(max, Math.max(min, Math.floor(parsed)));
}

function readOptionalPositiveIntegerParam(params: Record<string, unknown>, key: string) {
  const value = params[key];
  if (value == null || value === "") {
    return undefined;
  }
  if (typeof value !== "number" && typeof value !== "string") {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
}

function resolveScriptTypeParam(params: Record<string, unknown>): ScriptType | undefined {
  return normalizeScriptTypeValue(firstText(params, ["scriptType"]));
}

function normalizeScriptTypeValue(value: string): ScriptType | undefined {
  const normalized = String(value ?? "").trim().toUpperCase();
  if (normalized === "PY") {
    return "PYTHON";
  }
  if (normalized === "FLINK QUESTION SQL" || normalized === "SMART_FLINK_SQL") {
    return "FLINK_QUESTION_SQL";
  }
  if (normalized === "SQL" || normalized === "FLINK_QUESTION_SQL" || normalized === "JAVA" || normalized === "PYTHON") {
    return normalized;
  }
  return undefined;
}

function normalizeScriptContent(value: string) {
  const text = String(value ?? "").trim();
  const fenced = /^```[a-zA-Z0-9_-]*\s*([\s\S]*?)\s*```$/.exec(text);
  return fenced?.[1]?.trim() || text;
}

function normalizeScriptFileName(value: string) {
  const name = String(value ?? "").trim();
  return name;
}

function normalizeFeatureView(value: unknown) {
  return String(value ?? "")
    .trim()
    .replace(/[-_\s]+([a-zA-Z0-9])/g, (_match, char: string) => char.toUpperCase());
}

function cleanObject<T extends object>(value: T): Partial<T> {
  const cleaned: Record<string, unknown> = {};
  for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
    if (item == null || item === "") {
      continue;
    }
    cleaned[key] = item;
  }
  return cleaned as Partial<T>;
}

function summarizeAssistantMemory() {
  const recentBusinessObjects = Object.entries(assistantMemory.recentEntitiesByPath)
    .slice(0, 16)
    .map(([path, entities]) => ({
      path,
      label: memoryPathLabel(path),
      count: entities.length,
      items: entities.slice(0, 8).map(summarizeMemoryEntity),
    }));
  const selectedBusinessObjects = Object.entries(assistantMemory.selectedEntitiesByPath)
    .filter(([, entity]) => Boolean(entity))
    .map(([path, entity]) => ({
      path,
      label: memoryPathLabel(path),
      entity: entity ? summarizeMemoryEntity(entity) : undefined,
    }));
  return {
    selectedRuntimeCluster: assistantMemory.selectedRuntimeCluster ? summarizeMemoryEntity(assistantMemory.selectedRuntimeCluster) : undefined,
    selectedDatasource: assistantMemory.selectedDatasource ? summarizeMemoryEntity(assistantMemory.selectedDatasource) : undefined,
    selectedPhysicalTable: assistantMemory.selectedPhysicalTable ? summarizeMemoryEntity(assistantMemory.selectedPhysicalTable) : undefined,
    selectedModel: assistantMemory.selectedModel ? summarizeMemoryEntity(assistantMemory.selectedModel) : undefined,
    selectedEntity: assistantMemory.selectedEntity ? summarizeMemoryEntity(assistantMemory.selectedEntity) : undefined,
    selectedBusinessObjects,
    lastEntityListPath: assistantMemory.lastEntityListPath,
    recentRuntimeClusters: assistantMemory.lastRuntimeClusterList.slice(0, 10).map(summarizeMemoryEntity),
    recentDatasources: assistantMemory.lastDatasourceList.slice(0, 10).map(summarizeMemoryEntity),
    recentPhysicalTables: assistantMemory.lastPhysicalTableList.slice(0, 20).map(summarizeMemoryEntity),
    recentModels: assistantMemory.lastModelList.slice(0, 20).map(summarizeMemoryEntity),
    recentBusinessObjects,
  };
}

function summarizeMemoryEntity(entity: AssistantMemoryEntity) {
  return cleanObject({
    kind: entity.kind,
    id: entity.id,
    name: entity.name,
    typeCode: entity.typeCode,
    physicalLocator: entity.physicalLocator,
    modelKind: entity.modelKind,
    selectedAt: entity.selectedAt,
    sourcePath: entity.sourcePath,
  });
}

function knownDatasourceEntities() {
  const byId = new Map<string, AssistantMemoryEntity>();
  const add = (entity?: AssistantMemoryEntity) => {
    const key = memoryEntityKey(entity);
    if (key && !byId.has(key)) {
      byId.set(key, entity as AssistantMemoryEntity);
    }
  };
  for (const entity of assistantMemory.lastDatasourceList) {
    add(entity);
  }
  for (const item of toolCache.datasources) {
    const entity = toDatasourceMemoryEntity(item as unknown as Record<string, unknown>, "/datasources");
    add(entity);
  }
  for (const entity of pageContextEntitiesForPath("/datasources")) {
    add(entity);
  }
  add(assistantMemory.selectedDatasource);
  return Array.from(byId.values());
}

function knownEntitiesForPath(path: string) {
  const normalizedPath = normalizeMemoryEntityPath(path);
  if (!normalizedPath) {
    return [];
  }
  if (normalizedPath === "/datasources") {
    return knownDatasourceEntities();
  }
  const byId = new Map<string, AssistantMemoryEntity>();
  const add = (entity?: AssistantMemoryEntity) => {
    const key = memoryEntityKey(entity);
    if (key && !byId.has(key)) {
      byId.set(key, entity as AssistantMemoryEntity);
    }
  };
  for (const entity of pageContextEntitiesForPath(normalizedPath)) {
    add(entity);
  }
  for (const entity of assistantMemory.recentEntitiesByPath[normalizedPath] ?? []) {
    add(entity);
  }
  if (normalizedPath === "/models") {
    for (const entity of assistantMemory.lastModelList) {
      add(entity);
    }
  }
  add(assistantMemory.selectedEntitiesByPath[normalizedPath]);
  return Array.from(byId.values());
}

function selectedMemoryEntityForPath(path: string) {
  const normalizedPath = normalizeMemoryEntityPath(path);
  if (!normalizedPath) {
    return undefined;
  }
  if (assistantMemory.selectedEntitiesByPath[normalizedPath]) {
    return assistantMemory.selectedEntitiesByPath[normalizedPath];
  }
  if (normalizedPath === "/datasources") {
    return assistantMemory.selectedDatasource;
  }
  if (normalizedPath === "/datasources:discover") {
    return assistantMemory.selectedPhysicalTable;
  }
  if (normalizedPath === "/models") {
    return assistantMemory.selectedModel;
  }
  return assistantMemory.selectedEntity?.sourcePath === normalizedPath
    ? assistantMemory.selectedEntity
    : undefined;
}

function pageContextEntitiesForPath(path: string) {
  const normalizedPath = normalizeMemoryEntityPath(path);
  const context = assistantPageContext.value;
  if (!context || !normalizedPath) {
    return [];
  }
  const objects: AssistantPageBusinessObject[] = [];
  const addObject = (object?: AssistantPageBusinessObject) => {
    if (object) {
      objects.push(object);
    }
  };
  addObject(context.activeObject);
  for (const object of context.selectedObjects ?? []) {
    addObject(object);
  }
  for (const object of context.visibleObjects ?? []) {
    addObject(object);
  }
  for (const object of context.relatedObjects ?? []) {
    addObject(object);
  }
  return objects
    .map((object) => pageBusinessObjectToMemoryEntity(object, context.path))
    .filter((entity): entity is AssistantMemoryEntity =>
      Boolean(entity && normalizeMemoryEntityPath(entity.sourcePath || "") === normalizedPath),
    );
}

function pageBusinessObjectToMemoryEntity(
  object: AssistantPageBusinessObject,
  fallbackPath: string,
): AssistantMemoryEntity | undefined {
  const sourcePath = normalizeMemoryEntityPath(object.path || fallbackPath);
  if (!sourcePath) {
    return undefined;
  }
  const raw = cleanObject({
    ...object.metadata,
    id: object.id,
    name: object.name,
    label: object.label,
    type: object.type,
    typeCode: object.typeCode,
    physicalLocator: object.physicalLocator,
    status: object.status,
    description: object.description,
  });
  const entity = toGenericMemoryEntity(raw, sourcePath);
  const kindFromPageObject = pageBusinessObjectKind(object.type);
  return {
    ...entity,
    kind: kindFromPageObject || entity.kind,
    id: firstText(raw, ["id"]) || entity.id,
    name: firstText(raw, ["name", "label", "physicalLocator", "id"]) || entity.name,
    typeCode: object.typeCode || entity.typeCode || object.status,
    physicalLocator: object.physicalLocator || entity.physicalLocator,
    sourcePath,
    raw,
  };
}

function pageBusinessObjectKind(type: string): AssistantMemoryEntityKind | undefined {
  const normalized = normalizeMemoryText(type);
  if (!normalized) {
    return undefined;
  }
  if (normalized.includes("runtimecluster") || normalized.includes("executioncluster")) {
    return "runtimeCluster";
  }
  if (normalized.includes("datasource")) {
    return "datasource";
  }
  if (normalized.includes("physicaltable") || normalized.includes("table") || normalized.includes("view")) {
    return "physicalTable";
  }
  if (normalized.includes("model")) {
    return "model";
  }
  if (normalized.includes("task")) {
    return "task";
  }
  if (normalized.includes("service")) {
    return "service";
  }
  if (normalized.includes("rule")) {
    return "rule";
  }
  if (normalized.includes("run") || normalized.includes("log")) {
    return "run";
  }
  if (normalized.includes("workflow")) {
    return "workflow";
  }
  if (normalized.includes("script")) {
    return "script";
  }
  if (normalized.includes("notification")) {
    return "notification";
  }
  return undefined;
}

function memoryEntityKey(entity?: AssistantMemoryEntity) {
  if (!entity) {
    return "";
  }
  if (entity.id) {
    return String(entity.id);
  }
  return `${entity.kind}:${normalizeMemoryText(entity.sourcePath || "")}:${normalizeMemoryText(entity.name)}:${normalizeMemoryText(entity.physicalLocator || "")}`;
}

function updateAssistantMemoryFromToolData(interfaceCode: string, params: Record<string, unknown>, data: unknown) {
  const featurePath = normalizeRoutePath(String(params.path ?? ""));
  if (interfaceCode === "studio.feature.list" && featurePath) {
    recordGenericMemoryEntities(
      featurePath,
      extractResultItems(data)
        .map((item) => toGenericMemoryEntity(item, featurePath))
        .filter((item) => item.id || item.name),
    );
  }
  if (interfaceCode === "studio.feature.get" && featurePath && isPlainRecord(data)) {
    const entity = toGenericMemoryEntity(data as Record<string, unknown>, featurePath);
    if (entity.id || entity.name) {
      selectGenericMemoryEntity(featurePath, entity, "studio.feature.get");
    }
  }
  if (interfaceCode === "studio.feature.list" && normalizeRoutePath(String(params.path ?? "")) === "/datasources") {
    assistantMemory.lastDatasourceList = extractResultItems(data)
      .map((item) => toDatasourceMemoryEntity(item, "/datasources"))
      .filter((item) => item.id || item.name);
    return;
  }
  if (interfaceCode === "studio.feature.list" && normalizeRoutePath(String(params.path ?? "")) === "/runtime-clusters") {
    assistantMemory.lastRuntimeClusterList = extractResultItems(data)
      .map((item) => toGenericMemoryEntity(item, "/runtime-clusters"))
      .filter((item) => item.id || item.name);
    return;
  }
  if (interfaceCode === "studio.feature.list" && normalizeRoutePath(String(params.path ?? "")) === "/models") {
    assistantMemory.lastModelList = extractResultItems(data)
      .map((item) => toModelMemoryEntity(item, "/models"))
      .filter((item) => item.id || item.name);
    return;
  }
  if (interfaceCode === "studio.feature.action"
      && normalizeRoutePath(String(params.path ?? "")) === "/datasources"
      && normalizeFeatureActionName(params.action) === "discover") {
    const datasourceId = String(params.id ?? params.datasourceId ?? assistantMemory.selectedDatasource?.id ?? "");
    assistantMemory.lastPhysicalTableList = extractResultItems(data)
      .map((item) => toPhysicalTableMemoryEntity({ ...item, datasourceId }, "/datasources:discover"))
      .filter((item) => item.name || item.physicalLocator);
    return;
  }
  if (interfaceCode === "studio.feature.get" && normalizeRoutePath(String(params.path ?? "")) === "/models") {
    const entity = toModelMemoryEntity(data as Record<string, unknown>, interfaceCode);
    if (entity.id || entity.name) {
      assistantMemory.selectedModel = entity;
    }
  }
}

function recordGenericMemoryEntities(path: string, entities: AssistantMemoryEntity[]) {
  const normalizedPath = normalizeMemoryEntityPath(path);
  if (!normalizedPath) {
    return;
  }
  const deduped = dedupeMemoryEntities(entities).slice(0, 50);
  assistantMemory.recentEntitiesByPath[normalizedPath] = deduped;
  assistantMemory.lastEntityListPath = normalizedPath;
}

function dedupeMemoryEntities(entities: AssistantMemoryEntity[]) {
  const byKey = new Map<string, AssistantMemoryEntity>();
  for (const entity of entities) {
    const key = entity.id || `${entity.kind}:${normalizeMemoryText(entity.name)}:${normalizeMemoryText(entity.physicalLocator || "")}`;
    if (key && !byKey.has(key)) {
      byKey.set(key, entity);
    }
  }
  return Array.from(byKey.values());
}

function selectGenericMemoryEntity(path: string, entity: AssistantMemoryEntity, source: string) {
  const normalizedPath = normalizeMemoryEntityPath(path || entity.sourcePath || "");
  const selected = {
    ...entity,
    selectedAt: new Date().toISOString(),
    sourcePath: normalizedPath || entity.sourcePath || source,
  };
  assistantMemory.selectedEntity = selected;
  if (normalizedPath) {
    assistantMemory.selectedEntitiesByPath[normalizedPath] = selected;
  }
  if (selected.kind === "runtimeCluster") {
    assistantMemory.selectedRuntimeCluster = selected;
  } else if (selected.kind === "datasource") {
    assistantMemory.selectedDatasource = selected;
  } else if (selected.kind === "model") {
    assistantMemory.selectedModel = selected;
  } else if (selected.kind === "physicalTable") {
    assistantMemory.selectedPhysicalTable = selected;
  }
}

function toDatasourceMemoryEntity(item: Record<string, unknown>, sourcePath: string): AssistantMemoryEntity {
  return {
    kind: "datasource",
    id: firstText(item, ["id", "datasourceId"]),
    name: firstText(item, ["name", "datasourceName", "label"]) || firstText(item, ["id", "datasourceId"]) || "未命名数据源",
    typeCode: firstText(item, ["typeCode", "datasourceType", "type"]),
    sourcePath,
    raw: item,
  };
}

function toGenericMemoryEntity(item: Record<string, unknown>, sourcePath: string): AssistantMemoryEntity {
  const normalizedPath = normalizeMemoryEntityPath(sourcePath);
  if (normalizedPath === "/datasources") {
    return toDatasourceMemoryEntity(item, normalizedPath);
  }
  if (normalizedPath === "/models") {
    return toModelMemoryEntity(item, normalizedPath);
  }
  return {
    kind: inferMemoryEntityKind(normalizedPath, item),
    id: firstText(item, ["id", "entityId", "recordId", "taskId", "serviceId", "ruleId", "runId", "workflowId", "scriptId", "notificationId"]),
    name: firstText(item, [
      "name",
      "label",
      "title",
      "taskName",
      "serviceName",
      "ruleName",
      "workflowName",
      "scriptName",
      "modelName",
      "runName",
      "code",
      "taskCode",
      "serviceCode",
      "ruleCode",
      "workflowCode",
      "executionId",
      "traceId",
      "id",
    ]) || "未命名对象",
    typeCode: firstText(item, ["typeCode", "type", "status", "state", "sourceType"]),
    physicalLocator: firstText(item, ["physicalLocator", "tableName"]),
    modelKind: firstText(item, ["modelKind", "granularity", "taskType", "serviceType", "runType"]),
    sourcePath: normalizedPath,
    raw: item,
  };
}

function toPhysicalTableMemoryEntity(item: Record<string, unknown>, sourcePath: string): AssistantMemoryEntity {
  const physicalLocator = firstText(item, ["physicalLocator", "physicalName", "tableName", "name"]);
  return {
    kind: "physicalTable",
    id: firstText(item, ["id", "modelId"]),
    name: firstText(item, ["name", "tableName", "physicalName"]) || physicalLocator || "未命名表",
    physicalLocator,
    modelKind: firstText(item, ["modelKind", "type", "tableType"]),
    sourcePath,
    raw: item,
  };
}

function toModelMemoryEntity(item: Record<string, unknown>, sourcePath: string): AssistantMemoryEntity {
  return {
    kind: "model",
    id: firstText(item, ["id", "modelId"]),
    name: firstText(item, ["name", "modelName", "label"]) || firstText(item, ["physicalLocator"]) || "未命名模型",
    physicalLocator: firstText(item, ["physicalLocator", "tableName"]),
    modelKind: firstText(item, ["modelKind", "type"]),
    sourcePath,
    raw: item,
  };
}

function inferMemoryEntityKind(path: string, item: Record<string, unknown>): AssistantMemoryEntityKind {
  const normalizedPath = normalizeMemoryEntityPath(path);
  const text = normalizeMemoryText(`${normalizedPath} ${firstText(item, ["type", "kind", "modelKind", "taskType", "serviceType", "ruleCode", "status"])}`);
  if (normalizedPath === "/runtime-clusters" || /runtimecluster|executioncluster|运行集群|执行集群/.test(text)) {
    return "runtimeCluster";
  }
  if (normalizedPath === "/datasources") {
    return "datasource";
  }
  if (normalizedPath === "/models") {
    return "model";
  }
  if (/qualityrules|rules|规则/.test(text) || normalizedPath === "/quality-rules") {
    return "rule";
  }
  if (/task|tasks|任务/.test(text) || ["/collection-tasks", "/quality-tasks"].includes(normalizedPath)) {
    return "task";
  }
  if (/service|services|服务/.test(text) || ["/data-services", "/data-ingestion-services", "/protocol-conversions"].includes(normalizedPath)) {
    return "service";
  }
  if (/run|runs|log|日志|运行/.test(text) || ["/runs", "/collection-task-runs", "/quality-task-runs"].includes(normalizedPath)) {
    return "run";
  }
  if (/workflow|工作流/.test(text) || normalizedPath === "/workflows") {
    return "workflow";
  }
  if (/script|sql|脚本/.test(text) || normalizedPath === "/data-development") {
    return "script";
  }
  if (/notification|通知|消息/.test(text) || normalizedPath === "/notifications") {
    return "notification";
  }
  return "unknown";
}

function resolveMemoryEntityPathFromText(text: string) {
  const normalized = normalizeMemoryText(text);
  if (!normalized) {
    return assistantMemory.lastEntityListPath || "";
  }
  if (/真实表|物理表|库表|表发现/.test(normalized)) {
    return "/datasources:discover";
  }
  if (/运行集群|执行集群|runtimecluster|executioncluster/.test(normalized)) {
    return "/runtime-clusters";
  }
  if (/数据源|datasource|连接/.test(normalized)) {
    return "/datasources";
  }
  if (/已登记模型|数据模型|模型|model/.test(normalized)) {
    return "/models";
  }
  if (/质量规则|校验规则|规则/.test(normalized)) {
    return "/quality-rules";
  }
  if (/质量任务|质量方案/.test(normalized)) {
    return "/quality-tasks";
  }
  if (/采集任务|采集计划/.test(normalized)) {
    return "/collection-tasks";
  }
  if (/质量运行|质量日志/.test(normalized)) {
    return "/quality-task-runs";
  }
  if (/采集运行|采集日志/.test(normalized)) {
    return "/collection-task-runs";
  }
  if (/运行记录|运行日志|执行记录/.test(normalized)) {
    return "/runs";
  }
  if (/工作流|流程/.test(normalized)) {
    return "/workflows";
  }
  if (/数据接入服务|接入服务/.test(normalized)) {
    return "/data-ingestion-services";
  }
  if (/协议转换/.test(normalized)) {
    return "/protocol-conversions";
  }
  if (/数据服务|共享服务|服务/.test(normalized)) {
    return "/data-services";
  }
  if (/脚本|sql|数据开发/.test(normalized)) {
    return "/data-development";
  }
  if (/通知|消息/.test(normalized)) {
    return "/notifications";
  }
  return assistantMemory.lastEntityListPath || "";
}

function normalizeMemoryEntityPath(path: string) {
  const normalized = normalizeRoutePath(path);
  if (normalized === "/datasources:discover") {
    return normalized;
  }
  return normalized;
}

function memoryPathLabel(path: string) {
  const normalizedPath = normalizeMemoryEntityPath(path);
  const labels: Record<string, string> = {
    "/runtime-clusters": "运行集群",
    "/datasources": "数据源",
    "/datasources:discover": "物理表/视图",
    "/models": "模型",
    "/quality-rules": "质量规则",
    "/quality-tasks": "质量任务",
    "/collection-tasks": "采集任务",
    "/collection-task-runs": "采集运行记录",
    "/quality-task-runs": "质量运行记录",
    "/runs": "运行记录",
    "/workflows": "工作流",
    "/data-services": "数据服务",
    "/data-ingestion-services": "数据接入服务",
    "/protocol-conversions": "协议转换",
    "/data-development": "脚本",
    "/notifications": "通知",
  };
  return labels[normalizedPath] || findAssistantFeature(normalizedPath)?.label || "业务对象";
}

function extractResultItems(data: unknown): Record<string, unknown>[] {
  if (Array.isArray(data)) {
    return data.filter(isPlainRecord) as Record<string, unknown>[];
  }
  if (!data || typeof data !== "object") {
    return [];
  }
  const record = data as Record<string, unknown>;
  for (const key of ["items", "records", "list", "models", "rows", "data"]) {
    const value = record[key];
    if (Array.isArray(value)) {
      return value.filter(isPlainRecord) as Record<string, unknown>[];
    }
  }
  return [];
}

function normalizeMemoryText(value: unknown) {
  return String(value ?? "")
    .trim()
    .toLowerCase()
    .replace(/\s+/g, "")
    .replace(/[，。；;：:、,.!?？（）()[\]{}"'`]/g, "");
}

function applyToolData(interfaceCode: string, params: Record<string, unknown>, data: unknown) {
  updateAssistantMemoryFromToolData(interfaceCode, params, data);
}

function handleChatControlClick(control: AssistantChatControl, value: string) {
  selectedControlValues[control.id] = value;
  void handleChatControlSelect(control);
}

async function handleChatControlSelect(control: AssistantChatControl) {
  if (assistantBusy.value || consumedControlIds[control.id]) {
    return;
  }
  const value = String(selectedControlValues[control.id] ?? "").trim();
  const option = control.options.find((item) => item.value === value);
  if (!value || (!isTextControl(control) && !option)) {
    return;
  }
  consumedControlIds[control.id] = true;
  if (control.paramKey === "assistantConfirmationId") {
    await handlePendingToolConfirmation(value);
    return;
  }
  if (control.paramKey === "assistantRecoveryAction") {
    await handleAssistantRecoveryAction(value, option);
    return;
  }
  if (control.paramKey === "assistantMemory.selectedDatasourceId") {
    await handleAssistantMemoryControl(control, value, option);
    return;
  }
  if (control.paramKey?.startsWith("assistantMemory.selectedEntity:")) {
    await handleGenericMemoryControl(control, value, option);
    return;
  }
  prompt.value = buildControlAnswerMessage(control, value, option);
  await nextTick();
  await submitPrompt({ preserveToolResults: true });
}

async function handleAssistantRecoveryAction(value: string, option?: AssistantChatControlOption) {
  const label = option?.label || (value === "retry" ? "重新生成" : "继续处理");
  messages.value.push(createAssistantMessage("user", label));
  await nextTick(scrollThreadToBottom);
  const instruction = value === "retry"
    ? "上一轮助手回复没有完整返回。请根据已有对话、currentContext 和 toolResults 重新生成上一轮回复；不要重复已经成功完成的接口读取，除非确实缺少必要上下文。"
    : "上一轮助手回复没有完整返回。请根据已有对话、currentContext 和 toolResults 继续处理后续步骤；不要重复已经成功完成的接口读取，必要时输出下一步 studio-assistant-protocol 或 controls。";
  const assistantMessage = await streamAssistantReply(instruction, conversationForRequest(instruction));
  await handleAssistantActions(assistantMessage, 0);
}

function buildControlAnswerMessage(
  control: AssistantChatControl,
  value: string,
  option?: AssistantChatControlOption,
) {
  const payload = {
    controlId: control.id,
    title: control.title,
    paramKey: control.paramKey || "value",
    value,
    label: option?.label || value,
  };
  return `我对问答控件做出回答：${JSON.stringify(payload)}。请根据这个答案继续判断下一步是否需要调用接口。`;
}

function conversationForRequest(extraUserMessage?: string) {
  const items = messages.value.map((item) => ({
    role: item.role,
    content: item.content,
  }));
  if (extraUserMessage) {
    items.push({
      role: "user",
      content: extraUserMessage,
    });
  }
  return items;
}

function buildRuntimeContext(mode: string) {
  return {
    route: route.fullPath,
    tenantId: authStore.currentTenantId,
    projectId: authStore.currentProjectId,
    assistantMode: assistantMode.value,
    responseLanguage: responseLanguage.value,
    protocolVersion: "studio-assistant.v1",
    mode,
    operationCatalogSource: assistantOperationCatalog.value.length ? "backend" : "unavailable",
    operationCatalogSize: assistantOperationCatalog.value.length,
    operationCatalog: summarizeAssistantOperationCatalog(),
    assistantFeatures: assistantFeatures.value,
    assistantCapabilities: assistantCapabilities.value,
    frontendTools: availableFrontendTools.value,
    frontendActionRegistry: availableFeatureActions.value,
    assistantMemory: summarizeAssistantMemory(),
    pageContext: assistantPageContext.value,
  };
}

function summarizeAssistantOperationCatalog() {
  return assistantOperationCatalog.value.map((operation) => ({
    schema: operation.schema,
    path: normalizeRoutePath(operation.path),
    label: operation.label,
    capabilityCode: operation.capabilityCode,
    group: operation.group,
    description: operation.description,
    writePolicy: operation.writePolicy,
    readTools: operation.readTools,
    defaultFrontendActions: operation.defaultFrontendActions,
    featureActions: normalizeAssistantOperationFeatureActions(operation).map((action) => ({
      action: action.action,
      resource: action.resource,
      purpose: action.purpose,
      mutation: action.mutation,
      requiredValues: action.requiredValues,
      optionalValues: action.optionalValues,
      aliases: action.aliases,
    })),
  }));
}

function collectCurrentInputs() {
  return {
    cachedDatasources: toolCache.datasources.length,
    cachedDatasourceModelGroups: Object.keys(toolCache.modelOptions).length,
    cachedModelDetails: Object.keys(toolCache.modelDetails).length,
    cachedRuntimeSchemas: Object.keys(toolCache.runtimeSchemas).length,
    selectedRuntimeClusterId: assistantMemory.selectedRuntimeCluster?.id,
    selectedRuntimeClusterName: assistantMemory.selectedRuntimeCluster?.name,
    selectedDatasourceId: assistantMemory.selectedDatasource?.id,
    selectedDatasourceName: assistantMemory.selectedDatasource?.name,
    selectedPhysicalTable: assistantMemory.selectedPhysicalTable?.physicalLocator || assistantMemory.selectedPhysicalTable?.name,
    selectedModelId: assistantMemory.selectedModel?.id,
    selectedEntityPath: assistantMemory.selectedEntity?.sourcePath,
    selectedEntityId: assistantMemory.selectedEntity?.id,
    selectedEntityName: assistantMemory.selectedEntity?.name,
    lastEntityListPath: assistantMemory.lastEntityListPath,
    pageContextSource: assistantPageContext.value?.source,
    pageContextActiveObject: assistantPageContext.value?.activeObject,
  };
}

function resolveFieldSummaryRows(record: Record<string, unknown>) {
  if (record.technicalMetadata && typeof record.technicalMetadata === "object" && !Array.isArray(record.technicalMetadata)) {
    const technicalMetadata = record.technicalMetadata as Record<string, unknown>;
    if (Array.isArray(technicalMetadata.columns)) {
      return technicalMetadata.columns;
    }
    if (Array.isArray(technicalMetadata.fields)) {
      return technicalMetadata.fields;
    }
  }
  if (Array.isArray(record.columns)) {
    return record.columns;
  }
  if (Array.isArray(record.fields)) {
    return record.fields;
  }
  const modelFields = resolveFields(record as unknown as DataModelDefinition);
  return modelFields.length ? modelFields : [];
}

function describeToolStart(interfaceCode: string, params: Record<string, unknown>) {
  if (interfaceCode === "studio.navigation.open") {
    const feature = findAssistantFeature(String(params.path ?? ""));
    return `正在打开 ${feature?.label ?? String(params.path ?? "")}。`;
  }
  if (interfaceCode === "studio.feature.list") {
    const feature = findAssistantFeature(String(params.path ?? ""));
    return `正在读取 ${feature?.label ?? String(params.path ?? "")} 的数据。`;
  }
  if (interfaceCode === "studio.feature.get") {
    const feature = findAssistantFeature(String(params.path ?? ""));
    return `正在读取 ${feature?.label ?? String(params.path ?? "")} 的详情。`;
  }
  if (interfaceCode === "studio.feature.action") {
    return `正在执行 ${describeToolConfirmation(interfaceCode, params)}`;
  }
  if (interfaceCode === "assistant.script.execute") {
    return `正在执行助手脚本 ${String(params.entrypointId ?? "")}。`;
  }
  return `正在调用 ${interfaceCode}。`;
}

function describeToolResult(interfaceCode: string, data: unknown) {
  if (interfaceCode === "studio.navigation.open" && data && typeof data === "object") {
    const record = data as Record<string, unknown>;
    return `已打开 ${String(record.label ?? record.path ?? "目标功能")}。`;
  }
  if ((interfaceCode === "studio.feature.list" || interfaceCode === "studio.feature.get") && data && typeof data === "object") {
    const record = data as Record<string, unknown>;
    if (Array.isArray(record.items)) {
      return `返回 ${String(record.total ?? record.items.length)} 条记录，本次载入 ${record.items.length} 条。`;
    }
  }
  if (interfaceCode === "studio.feature.action") {
    return "动作接口已返回结果。";
  }
  if (interfaceCode === "assistant.context.read" && data && typeof data === "object") {
    const record = data as Record<string, unknown>;
    return `已读取 ${String(record.label ?? record.path ?? "当前业务上下文")}，候选 ${String(record.candidateCount ?? 0)} 个。`;
  }
  if (interfaceCode === "assistant.script.execute" && data && typeof data === "object") {
    const summary = ((data as Record<string, unknown>).data as Record<string, unknown> | undefined)?.summary;
    if (summary && typeof summary === "object") {
      return `脚本已返回结果，映射 ${String((summary as Record<string, unknown>).mappedCount ?? 0)} 项。`;
    }
    return "助手脚本已返回结果。";
  }
  if (Array.isArray(data)) {
    return `返回 ${data.length} 条记录。`;
  }
  if (data && typeof data === "object") {
    const record = data as Record<string, unknown>;
    if (Array.isArray(record.items)) {
      return `返回 ${String(record.total ?? record.items.length)} 条候选，本次载入 ${record.items.length} 条。`;
    }
    const fields = resolveFieldSummaryRows(record);
    if (fields.length) {
      return `读取到 ${fields.length} 个字段。`;
    }
  }
  return "接口已返回结果。";
}

function describeToolChatResult(interfaceCode: string, params: Record<string, unknown>, data: unknown) {
  if (interfaceCode === "studio.navigation.open" && data && typeof data === "object") {
    const record = data as Record<string, unknown>;
    return `已为你打开 ${String(record.label ?? params.path ?? "目标功能")}。`;
  }
  if (interfaceCode === "studio.feature.list") {
    const feature = findAssistantFeature(String(params.path ?? ""));
    return `我已读取 ${feature?.label ?? params.path ?? "目标功能"} 的数据，会基于返回结果继续判断。`;
  }
  if (interfaceCode === "studio.feature.get") {
    const feature = findAssistantFeature(String(params.path ?? ""));
    return `我已读取 ${feature?.label ?? params.path ?? "目标功能"} 的详情，会基于返回结果继续判断。`;
  }
  if (interfaceCode === "studio.feature.action") {
    return `我已完成 ${describeToolConfirmation(interfaceCode, params)} 会基于接口返回结果继续判断。`;
  }
  if (interfaceCode === "assistant.context.search") {
    return "我已搜索当前页面上下文、最近候选和助手记忆，结果会作为工具上下文继续回灌给下一轮回答。";
  }
  if (interfaceCode === "assistant.context.read") {
    return "我已读取当前业务上下文，结果会作为工具上下文继续回灌给下一轮回答。";
  }
  if (interfaceCode === "assistant.script.execute") {
    return "助手脚本已通过后端登记入口执行，结果会作为工具上下文继续回灌给下一轮回答。";
  }
  return "";
}

function pushProcessEvent(event: {
  stage: string;
  title: string;
  detail?: string;
  status?: AssistantProcessEvent["status"] | string;
}) {
  const normalizedStatus = normalizeProcessStatus(event.status);
  const existingIndex = findProcessEventUpdateIndex(event.stage, normalizedStatus);
  if (existingIndex >= 0) {
    const updatedEvent = {
      ...processEvents.value[existingIndex],
      title: event.title,
      detail: event.detail || processEvents.value[existingIndex].detail,
      status: normalizedStatus,
    };
    processEvents.value = [
      ...processEvents.value.slice(0, existingIndex),
      ...processEvents.value.slice(existingIndex + 1),
      updatedEvent,
    ];
    return;
  }
  processEvents.value.push({
    id: `${event.stage}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    stage: event.stage,
    title: event.title,
    detail: event.detail || "",
    status: normalizedStatus,
    createdAt: Date.now(),
  });
  if (processEvents.value.length > 40) {
    processEvents.value = processEvents.value.slice(-40);
  }
}

function findProcessEventUpdateIndex(stage: string, nextStatus: AssistantProcessEvent["status"]) {
  for (let index = processEvents.value.length - 1; index >= 0; index -= 1) {
    const event = processEvents.value[index];
    if (event.stage !== stage) {
      continue;
    }
    if (event.status === "running" || nextStatus !== "running") {
      return index;
    }
    return -1;
  }
  return -1;
}

function settleRunningProcessEvents(status: AssistantProcessEvent["status"], detail: string) {
  processEvents.value = processEvents.value.map((event) => {
    if (event.status !== "running") {
      return event;
    }
    return {
      ...event,
      detail,
      status,
    };
  });
}

function markProcessDone(
  stage: string,
  title: string,
  detail?: string,
  status: AssistantProcessEvent["status"] = "done",
) {
  const index = [...processEvents.value].reverse().findIndex((item) => item.stage === stage);
  if (index < 0) {
    pushProcessEvent({ stage, title, detail, status });
    return;
  }
  const targetIndex = processEvents.value.length - 1 - index;
  processEvents.value[targetIndex] = {
    ...processEvents.value[targetIndex],
    title,
    detail: detail || processEvents.value[targetIndex].detail,
    status,
  };
}

function normalizeProcessStatus(status: unknown): AssistantProcessEvent["status"] {
  if (status === "done" || status === "warning" || status === "error") {
    return status;
  }
  return "running";
}

function scrollThreadToBottom() {
  const el = threadRef.value;
  if (!el) {
    return;
  }
  el.scrollTop = el.scrollHeight;
  window.requestAnimationFrame(() => {
    el.scrollTop = el.scrollHeight;
  });
}

function renderMarkdown(content: string) {
  const lines = normalizeMarkdownInput(content).split(/\r?\n/);
  const html: string[] = [];
  let inCode = false;
  let codeLines: string[] = [];
  let listType: "ul" | "ol" | null = null;

  const closeList = () => {
    if (listType) {
      html.push(`</${listType}>`);
      listType = null;
    }
  };
  const openList = (type: "ul" | "ol") => {
    if (listType === type) {
      return;
    }
    closeList();
    listType = type;
    html.push(`<${type}>`);
  };

  for (let lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
    const line = lines[lineIndex];
    const codeFence = /^```/.test(line.trim());
    if (codeFence) {
      if (inCode) {
        html.push(`<pre><code>${escapeHtml(codeLines.join("\n"))}</code></pre>`);
        codeLines = [];
        inCode = false;
      } else {
        closeList();
        inCode = true;
      }
      continue;
    }
    if (inCode) {
      codeLines.push(line);
      continue;
    }

    if (!line.trim()) {
      closeList();
      continue;
    }
    if (isMarkdownTableHeader(lines, lineIndex)) {
      closeList();
      const tableRows = collectMarkdownTableRows(lines, lineIndex);
      html.push(renderMarkdownTable(tableRows));
      lineIndex += tableRows.length;
      continue;
    }
    const heading = /^(#{1,4})\s+(.+)$/.exec(line);
    if (heading) {
      closeList();
      const level = Math.min(4, heading[1].length + 2);
      html.push(`<h${level}>${renderInlineMarkdown(heading[2])}</h${level}>`);
      continue;
    }
    const bullet = /^\s*[-*]\s+(.+)$/.exec(line);
    if (bullet) {
      openList("ul");
      html.push(`<li>${renderInlineMarkdown(bullet[1])}</li>`);
      continue;
    }
    const ordered = /^\s*\d+\.\s+(.+)$/.exec(line);
    if (ordered) {
      openList("ol");
      html.push(`<li>${renderInlineMarkdown(ordered[1])}</li>`);
      continue;
    }
    const quote = /^\s*>\s?(.+)$/.exec(line);
    if (quote) {
      closeList();
      html.push(`<blockquote>${renderInlineMarkdown(quote[1])}</blockquote>`);
      continue;
    }
    closeList();
    html.push(`<p>${renderInlineMarkdown(line)}</p>`);
  }
  closeList();
  if (inCode) {
    html.push(`<pre><code>${escapeHtml(codeLines.join("\n"))}</code></pre>`);
  }
  return html.join("");
}

function isMarkdownTableHeader(lines: string[], index: number) {
  const current = lines[index] ?? "";
  const next = lines[index + 1] ?? "";
  const headerCells = splitMarkdownTableRow(current);
  const separatorCells = splitMarkdownTableRow(next);
  return isMarkdownTableRow(current)
    && isMarkdownTableSeparator(next)
    && separatorCells.length === headerCells.length;
}

function collectMarkdownTableRows(lines: string[], headerIndex: number) {
  const rows = [lines[headerIndex]];
  let index = headerIndex + 2;
  while (index < lines.length && isMarkdownTableRow(lines[index]) && !isMarkdownTableSeparator(lines[index])) {
    rows.push(lines[index]);
    index += 1;
  }
  return rows;
}

function renderMarkdownTable(rows: string[]) {
  const [headerLine, ...bodyLines] = rows;
  const headers = splitMarkdownTableRow(headerLine);
  const body = bodyLines.map(splitMarkdownTableRow);
  const headerHtml = headers.map((cell) => `<th>${renderInlineMarkdown(cell)}</th>`).join("");
  const bodyHtml = body
    .map((row) => `<tr>${headers.map((_header, index) => `<td>${renderInlineMarkdown(row[index] ?? "")}</td>`).join("")}</tr>`)
    .join("");
  return `<div class="assistant-markdown-table"><table><thead><tr>${headerHtml}</tr></thead><tbody>${bodyHtml}</tbody></table></div>`;
}

function isMarkdownTableRow(line: string) {
  const cells = splitMarkdownTableRow(line);
  return cells.length > 0 && (hasOuterTablePipe(line) || cells.length > 1);
}

function isMarkdownTableSeparator(line: string) {
  const cells = splitMarkdownTableRow(line);
  return cells.length > 0
    && (hasOuterTablePipe(line) || cells.length > 1)
    && cells.every((cell) => /^:?-{3,}:?$/.test(cell.trim()));
}

function splitMarkdownTableRow(line: string) {
  const trimmed = String(line ?? "").trim();
  if (!trimmed.includes("|")) {
    return [];
  }
  return splitTableCells(trimmed)
    .map((cell) => cell.trim())
    .filter((_cell, index, cells) => !(index === 0 && cells[index] === "") && !(index === cells.length - 1 && cells[index] === ""));
}

function splitTableCells(line: string) {
  const cells: string[] = [];
  let current = "";
  let escaped = false;
  let inCode = false;
  for (const char of line) {
    if (escaped) {
      current += char;
      escaped = false;
      continue;
    }
    if (char === "\\") {
      escaped = true;
      continue;
    }
    if (char === "`") {
      inCode = !inCode;
      current += char;
      continue;
    }
    if (char === "|" && !inCode) {
      cells.push(current);
      current = "";
      continue;
    }
    current += char;
  }
  cells.push(current);
  return cells;
}

function hasOuterTablePipe(line: string) {
  const trimmed = String(line ?? "").trim();
  return trimmed.startsWith("|") || trimmed.endsWith("|");
}

function normalizeMarkdownInput(content: string) {
  return String(content ?? "")
    .replace(/([^\n])```/g, "$1\n```")
    .replace(/```([A-Za-z0-9_-]+)([^\n])/g, "```$1\n$2")
    .replace(/(^|\n)(#{1,6}\s+[^|\n]{1,80}?)\s*(\|[^\n]+\|)(?=\s*\n\s*\|?\s*:?-{3,})/g, "$1$2\n$3")
    .replace(/(^|\n)([^#\n|]{1,48}?)(\|[^\n]+\|)(?=\s*\n\s*\|?\s*:?-{3,})/g, "$1$2\n$3");
}

function resolveAssistantFeatureToolSpecs(): Record<string, AssistantFeatureToolSpec> {
  if (!assistantOperationCatalog.value.length) {
    return {};
  }
  const specs: Record<string, AssistantFeatureToolSpec> = {};
  for (const operation of assistantOperationCatalog.value) {
    const path = normalizeRoutePath(operation.path);
    if (!path) {
      continue;
    }
    const label = String(operation.label ?? featureLabelFromPath(path));
    const listReadTool = findAssistantOperationReadTool(operation, "studio.feature.list");
    const getReadTool = findAssistantOperationReadTool(operation, "studio.feature.get");
    const supportsList = operation.supportsList !== false
      && (operation.supportsList === true || Boolean(listReadTool));
    const supportsGet = operation.supportsGet !== false
      && (operation.supportsGet === true || Boolean(getReadTool));
    const listRequiredValues = toStringArray(listReadTool?.requiredValues);
    const listOptionalValues = toStringArray(listReadTool?.optionalValues);
    const listDefaultParams = toPlainRecordOrUndefined(listReadTool?.defaultParams ?? listReadTool?.params);
    const getRequiredValues = toStringArray(getReadTool?.requiredValues);
    const getOptionalValues = toStringArray(getReadTool?.optionalValues);
    specs[path] = {
      capabilityCode: String(operation.capabilityCode ?? `studio.navigation.${path.replace(/[^a-zA-Z0-9]+/g, ".").replace(/^\.+|\.+$/g, "")}`),
      description: String(operation.description ?? label),
      list: supportsList
        ? {
            purpose: String(listReadTool?.purpose ?? `读取${label}列表或概览。`),
            requiredValues: listRequiredValues.length ? listRequiredValues : ["path"],
            optionalValues: listOptionalValues.length ? listOptionalValues : undefined,
            defaultParams: listDefaultParams,
          }
        : undefined,
      get: supportsGet
        ? {
            purpose: String(getReadTool?.purpose ?? `读取${label}详情。`),
            requiredValues: getRequiredValues.length ? getRequiredValues : ["id"],
            optionalValues: getOptionalValues.length ? getOptionalValues : undefined,
          }
        : undefined,
      writePolicy: String(operation.writePolicy ?? "写操作、配置变更和执行类动作必须由用户明确确认后才可开放。"),
    };
  }
  return specs;
}

function findAssistantOperationReadTool(operation: AssistantStudioOperation, tool: string) {
  return (operation.readTools ?? []).find((item) => String(item.tool ?? "") === tool);
}

function resolveAssistantFeatureActionDefinitions(): AssistantFeatureActionDefinition[] {
  if (!assistantOperationCatalog.value.length) {
    return [];
  }
  const definitions: AssistantFeatureActionDefinition[] = [];
  const seen = new Set<string>();
  const addDefinition = (definition: AssistantFeatureActionDefinition) => {
    const path = normalizeRoutePath(definition.path);
    const action = normalizeFeatureActionName(definition.action).toLowerCase();
    const resource = normalizeFeatureResource(definition.resource).toLowerCase();
    const key = `${path}|${resource}|${action}`;
    if (!path || !action || seen.has(key)) {
      return;
    }
    seen.add(key);
    definitions.push({
      ...definition,
      path,
      action: definition.action,
      resource: definition.resource ? normalizeFeatureResource(definition.resource) : undefined,
    });
  };
  for (const operation of assistantOperationCatalog.value) {
    for (const definition of normalizeAssistantOperationFeatureActions(operation)) {
      addDefinition(definition);
    }
  }
  return definitions;
}

function normalizeAssistantOperationFeatureActions(operation: AssistantStudioOperation): AssistantFeatureActionDefinition[] {
  const path = normalizeRoutePath(operation.path);
  if (!path) {
    return [];
  }
  return (operation.featureActions ?? [])
    .map((item) => normalizeAssistantFeatureActionDefinition(path, item))
    .filter((item): item is AssistantFeatureActionDefinition => Boolean(item));
}

function normalizeAssistantFeatureActionDefinition(path: string, item: Record<string, unknown>): AssistantFeatureActionDefinition | null {
  const action = normalizeFeatureActionName(item.action);
  if (!action || isForbiddenFeatureAction(action)) {
    return null;
  }
  const requiredValues = toStringArray(item.requiredValues);
  const optionalValues = toStringArray(item.optionalValues);
  const aliases = toStringArray(item.aliases).filter((alias) => !isForbiddenFeatureAction(alias));
  const resource = normalizeFeatureResource(item.resource);
  return {
    path,
    action,
    purpose: String(item.purpose ?? `${action} ${path}`),
    mutation: item.mutation === false ? false : true,
    ...(requiredValues.length ? { requiredValues } : {}),
    ...(optionalValues.length ? { optionalValues } : {}),
    ...(resource ? { resource } : {}),
    ...(aliases.length ? { aliases } : {}),
  };
}

function resolveAssistantFeatures(): AssistantFeature[] {
  const features: AssistantFeature[] = [];
  const seen = new Set<string>();
  if (assistantOperationCatalog.value.length) {
    for (const operation of assistantOperationCatalog.value) {
      const path = normalizeRoutePath(operation.path);
      if (!path || seen.has(path)) {
        continue;
      }
      seen.add(path);
      features.push({
        group: String(operation.group ?? "Studio"),
        path,
        label: String(operation.label ?? featureLabelFromPath(path)),
        caption: operation.description,
      });
    }
  }
  return features;
}

function featureLabelFromPath(path: string) {
  const key = normalizeRoutePath(path).replace(/^\//, "");
  if (!key) {
    return "Studio";
  }
  return key
    .split("-")
    .filter(Boolean)
    .map((part) => part.slice(0, 1).toUpperCase() + part.slice(1))
    .join(" ");
}

function resolveAssistantCapabilities(): AssistantCapability[] {
  return assistantFeatures.value.map((feature) => {
    const path = normalizeRoutePath(feature.path);
    const spec = assistantFeatureToolSpecs.value[path];
    const operations: AssistantFeatureOperation[] = [
      {
        operation: "open",
        interfaceCode: "studio.navigation.open",
        purpose: `打开${feature.label}页面。`,
        requiredValues: ["path"],
        defaultParams: { path },
      },
    ];
    if (spec?.list) {
      operations.push({
        operation: "list",
        interfaceCode: "studio.feature.list",
        purpose: spec.list.purpose,
        requiredValues: ["path"],
        optionalValues: spec.list.optionalValues,
        defaultParams: { path, ...(spec.list.defaultParams ?? {}) },
      });
    }
    if (spec?.get) {
      operations.push({
        operation: "get",
        interfaceCode: "studio.feature.get",
        purpose: spec.get.purpose,
        requiredValues: ["path", ...(spec.get.requiredValues ?? ["id"])],
        optionalValues: spec.get.optionalValues,
        defaultParams: { path },
      });
    }
    for (const action of resolveFeatureActionOperations(path)) {
      operations.push(action);
    }
    return {
      capabilityCode: spec?.capabilityCode ?? `studio.navigation.${path.replace(/[^a-zA-Z0-9]+/g, ".").replace(/^\.+|\.+$/g, "")}`,
      group: feature.group,
      path,
      label: feature.label,
      description: spec?.description ?? feature.caption,
      operations,
      writePolicy: spec?.writePolicy ?? "写操作、配置变更和执行类动作必须由用户明确确认后才可开放。",
    };
  });
}

function resolveFeatureActionOperations(path: string): AssistantFeatureOperation[] {
  return assistantFeatureActionDefinitions.value
    .filter((item) => normalizeRoutePath(item.path) === normalizeRoutePath(path))
    .map((item) => ({
      operation: "action" as const,
      interfaceCode: "studio.feature.action",
      purpose: item.purpose,
      requiredValues: ["path", "action", ...(item.requiredValues ?? [])],
      optionalValues: ["resource", ...(item.optionalValues ?? [])],
      defaultParams: {
        path: normalizeRoutePath(item.path),
        action: item.action,
        ...(item.resource ? { resource: item.resource } : {}),
      },
    }));
}

function resolveAvailableFeatureActions() {
  const pathSet = new Set(assistantFeatures.value.map((feature) => normalizeRoutePath(feature.path)));
  return assistantFeatureActionDefinitions.value
    .filter((item) => pathSet.has(normalizeRoutePath(item.path)))
    .map((item) => ({
      path: normalizeRoutePath(item.path),
      action: item.action,
      resource: item.resource,
      purpose: item.purpose,
      mutation: item.mutation,
      requiredValues: item.requiredValues,
      optionalValues: item.optionalValues,
      aliases: item.aliases,
    }));
}

function resolveAvailableFrontendTools(): AssistantFrontendToolDefinition[] {
  const pathSet = new Set(assistantFeatures.value.map((feature) => normalizeRoutePath(feature.path)));
  return ASSISTANT_FRONTEND_TOOL_DEFINITIONS.filter((tool) => {
    if (tool.interfaceCode === "assistant.context.observe"
      || tool.interfaceCode === "assistant.context.read"
      || tool.interfaceCode === "assistant.context.search"
      || tool.interfaceCode === "assistant.memory.select") {
      return true;
    }
    if (!tool.featurePaths?.length) {
      return assistantCapabilities.value.length > 0;
    }
    return tool.featurePaths.some((path) => pathSet.has(normalizeRoutePath(path)));
  });
}

function findFrontendToolDefinition(interfaceCode: string) {
  return availableFrontendTools.value.find((tool) => tool.interfaceCode === interfaceCode);
}

function isFrontendToolAvailable(interfaceCode: string) {
  return Boolean(findFrontendToolDefinition(interfaceCode));
}

function findAssistantFeature(path: string) {
  const normalized = normalizeRoutePath(path);
  return assistantFeatures.value.find((feature) => normalizeRoutePath(feature.path) === normalized);
}

function normalizeRoutePath(path: unknown) {
  const value = String(path ?? "").trim();
  if (!value) {
    return "";
  }
  return value.startsWith("/") ? value : `/${value}`;
}

function renderInlineMarkdown(value: string) {
  return String(value ?? "")
    .split(/(`[^`]*`)/g)
    .map((part) => {
      if (part.startsWith("`") && part.endsWith("`")) {
        return `<code>${escapeHtml(part.slice(1, -1))}</code>`;
      }
      return escapeHtml(part)
        .replace(/\[([^\]]+)]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>')
        .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
        .replace(/\*([^*]+)\*/g, "<em>$1</em>");
    })
    .join("");
}

function escapeHtml(value: string) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function firstText(params: Record<string, unknown>, keys: string[]) {
  for (const key of keys) {
    const value = readParamValue(params, key);
    if (value == null) {
      continue;
    }
    const text = String(value).trim();
    if (text) {
      return text;
    }
  }
  return "";
}

function readParamValue(params: Record<string, unknown>, key: string) {
  if (Object.prototype.hasOwnProperty.call(params, key)) {
    return params[key];
  }
  if (!key.includes(".")) {
    return undefined;
  }
  let cursor: unknown = params;
  for (const part of key.split(".")) {
    if (!isPlainRecord(cursor)) {
      return undefined;
    }
    cursor = cursor[part];
  }
  return cursor;
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function toStringArray(value: unknown) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => String(item ?? "").trim())
    .filter(Boolean);
}

function toPlainRecordOrUndefined(value: unknown) {
  if (!isPlainRecord(value)) {
    return undefined;
  }
  return Object.keys(value).length ? { ...value } : undefined;
}

function resolveErrorMessage(error: unknown, defaultMessage: string) {
  return error instanceof Error && error.message ? error.message : defaultMessage;
}
</script>

<style scoped>
:global(.studio-assistant-drawer .el-drawer__body) {
  padding: 0;
  background: #f8fafc;
  overflow: hidden;
}

.assistant-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 12px;
}

.assistant-header strong {
  display: block;
  color: var(--studio-text);
  font-weight: 700;
}

.assistant-header span {
  display: block;
  margin-top: 4px;
  color: var(--studio-text-soft);
  font-size: 12px;
}

.assistant-header__controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-width: 0;
}

.assistant-header__radio {
  flex: 0 1 auto;
  min-width: 0;
}

.assistant-root {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  min-height: 100%;
  padding: 16px;
  overflow: hidden;
}

.assistant-thread {
  display: grid;
  gap: 10px;
  flex: 1 1 auto;
  min-height: 220px;
  padding-right: 4px;
  overflow: auto;
  align-content: start;
}

.assistant-message {
  display: grid;
  gap: 4px;
  min-width: 0;
  max-width: 88%;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: #fff;
}

.assistant-message--user {
  justify-self: end;
  color: #0f172a;
  background: #e0f2fe;
  border-color: rgba(2, 132, 199, 0.18);
}

.assistant-message--assistant,
.assistant-message--system {
  justify-self: start;
}

.assistant-message__role {
  color: var(--studio-text-soft);
  font-size: 12px;
  font-weight: 700;
}

.assistant-message p {
  margin: 0;
  line-height: 1.55;
  white-space: pre-wrap;
}

.assistant-message__content {
  min-width: 0;
  max-width: 100%;
  line-height: 1.55;
}

.assistant-message__content p {
  margin: 0;
}

.assistant-process {
  display: grid;
  flex: 0 0 auto;
  overflow: hidden;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
}

.assistant-process__summary {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr) auto;
  gap: 9px;
  align-items: start;
  width: 100%;
  padding: 9px 10px;
  border: 0;
  background: transparent;
  color: #475569;
  text-align: left;
  cursor: pointer;
}

.assistant-process__summary:hover {
  background: rgba(241, 245, 249, 0.78);
}

.assistant-process__summary-copy {
  display: grid;
  gap: 2px;
  min-width: 0;
  font-size: 12px;
  line-height: 1.45;
}

.assistant-process__summary-copy strong {
  color: #334155;
  font-weight: 700;
}

.assistant-process__summary-copy span {
  overflow: hidden;
  color: #64748b;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assistant-process__summary-action {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
}

.assistant-process__summary--warning .assistant-process__summary-copy strong {
  color: #92400e;
}

.assistant-process__summary--error .assistant-process__summary-copy strong {
  color: #991b1b;
}

.assistant-process__list {
  display: grid;
  gap: 6px;
  max-height: 156px;
  overflow: auto;
  padding: 0 10px 10px;
}

.assistant-process__item {
  display: grid;
  grid-template-columns: 8px 1fr;
  gap: 8px;
  align-items: start;
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
}

.assistant-process__dot {
  width: 7px;
  height: 7px;
  margin-top: 5px;
  border-radius: 999px;
  background: #38bdf8;
  box-shadow: 0 0 0 3px rgba(56, 189, 248, 0.14);
}

.assistant-process__item--running .assistant-process__dot,
.assistant-process__summary--running .assistant-process__dot {
  animation: assistant-process-pulse 1.2s ease-in-out infinite;
}

.assistant-process__item--done .assistant-process__dot,
.assistant-process__summary--done .assistant-process__dot {
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.12);
}

.assistant-process__item--warning .assistant-process__dot,
.assistant-process__summary--warning .assistant-process__dot {
  background: #f59e0b;
  box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.14);
}

.assistant-process__item--error .assistant-process__dot,
.assistant-process__summary--error .assistant-process__dot {
  background: #ef4444;
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.14);
}

.assistant-process__item strong {
  display: block;
  color: #334155;
  font-weight: 700;
}

.assistant-process__item span {
  display: block;
  margin-top: 1px;
}

.assistant-chat-controls {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}

.assistant-composer-controls {
  display: grid;
  gap: 8px;
  flex: 0 0 auto;
  max-height: 220px;
  overflow: auto;
  padding: 10px;
  border: 1px solid rgba(37, 99, 235, 0.16);
  border-radius: 8px;
  background: rgba(239, 246, 255, 0.72);
}

.assistant-chat-control {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #eff6ff;
}

.assistant-chat-control strong {
  color: #1e3a8a;
  font-size: 13px;
}

.assistant-chat-control > span {
  color: #475569;
  font-size: 12px;
  line-height: 1.5;
}

.assistant-chat-control__select {
  width: 100%;
}

.assistant-chat-control__input {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: start;
}

.assistant-chat-control__options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.assistant-chat-control__select small {
  margin-left: 8px;
  color: #94a3b8;
}

@keyframes assistant-process-pulse {
  50% {
    opacity: 0.45;
    transform: scale(0.78);
  }
}

.assistant-markdown {
  display: grid;
  gap: 6px;
  min-width: 0;
  max-width: 100%;
}

.assistant-markdown :deep(p),
.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol),
.assistant-markdown :deep(blockquote),
.assistant-markdown :deep(pre) {
  margin: 0;
}

.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol) {
  padding-left: 20px;
}

.assistant-markdown :deep(li + li) {
  margin-top: 3px;
}

.assistant-markdown :deep(h3),
.assistant-markdown :deep(h4),
.assistant-markdown :deep(h5),
.assistant-markdown :deep(h6) {
  margin: 2px 0 0;
  color: #0f172a;
  font-size: 14px;
  font-weight: 700;
}

.assistant-markdown :deep(blockquote) {
  padding-left: 10px;
  border-left: 3px solid rgba(2, 132, 199, 0.28);
  color: #475569;
}

.assistant-markdown :deep(.assistant-markdown-table) {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #fff;
}

.assistant-markdown :deep(table) {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  line-height: 1.45;
}

.assistant-markdown :deep(th),
.assistant-markdown :deep(td) {
  padding: 8px 10px;
  border: 1px solid #e2e8f0;
  text-align: left;
  vertical-align: top;
  white-space: normal;
  overflow-wrap: anywhere;
}

.assistant-markdown :deep(th) {
  background: #eff6ff;
  color: #1e3a8a;
  font-weight: 700;
}

.assistant-markdown :deep(code) {
  padding: 1px 5px;
  border-radius: 5px;
  background: #e2e8f0;
  color: #0f172a;
  font-family: ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace;
  font-size: 12px;
}

.assistant-markdown :deep(pre) {
  max-width: 100%;
  padding: 10px;
  overflow: auto;
  border-radius: 8px;
  background: #0f172a;
  color: #e2e8f0;
}

.assistant-markdown :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.assistant-markdown :deep(a) {
  color: #0369a1;
  text-decoration: none;
}

.assistant-caret {
  display: inline-block;
  width: 6px;
  height: 1em;
  margin-left: 2px;
  border-right: 2px solid currentcolor;
  transform: translateY(2px);
  animation: assistant-caret-blink 1s steps(2, start) infinite;
}

@keyframes assistant-caret-blink {
  50% {
    opacity: 0;
  }
}

.assistant-composer {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  flex: 0 0 auto;
  align-items: end;
}

@media (max-width: 760px) {
  .assistant-composer {
    grid-template-columns: 1fr;
  }

  .assistant-message {
    max-width: 100%;
  }
}
</style>
