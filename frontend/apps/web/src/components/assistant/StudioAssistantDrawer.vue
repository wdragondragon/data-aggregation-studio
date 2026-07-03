<template>
  <el-drawer v-model="drawerVisible" size="min(860px, 92vw)" class="studio-assistant-drawer" append-to-body>
    <template #header>
      <div class="assistant-header">
        <div>
          <strong>Studio AI 助手</strong>
          <span>{{ authStore.currentProjectName || "未选择项目" }}</span>
        </div>
        <el-tag type="success" round>LLM 对话</el-tag>
      </div>
    </template>

    <div class="assistant-root">
      <section ref="threadRef" class="assistant-thread" aria-label="assistant conversation">
        <div
          v-for="message in messages"
          :key="message.id"
          class="assistant-message"
          :class="`assistant-message--${message.role}`"
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

      <section class="assistant-composer">
        <el-input
          v-model="prompt"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          resize="none"
          placeholder="可以问 Studio 能做什么，也可以说：创建 mysql1 的 order 表到 doris1 的 ods_order 表的单表采集"
          :disabled="assistantBusy"
          @keydown.ctrl.enter.prevent="submitPrompt"
        />
        <el-button type="primary" :loading="assistantBusy" :disabled="!prompt.trim()" @click="submitPrompt">
          发送
        </el-button>
      </section>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import type {
  AssistantToolCall,
  CapabilityMatrix,
  CollectionTaskSaveRequest,
  DataModelStatisticsOptionsRequest,
  DataModelDatasourceOptionView,
  DataModelDefinition,
  DataSourceOptionView,
  PluginRuntimeOptionSchemaView,
  ScriptType,
  WorkflowSaveRequest,
} from "@studio/api-sdk";
import { streamAssistantChat, studioApi } from "@/api/studio";
import { resolveStudioMenus } from "@/router";
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

interface ToolCacheState {
  capabilityMatrix: CapabilityMatrix | null;
  datasources: DataSourceOptionView[];
  modelOptions: Record<string, DataModelDatasourceOptionView[]>;
  modelDetails: Record<string, DataModelDefinition>;
  runtimeSchemas: Record<string, PluginRuntimeOptionSchemaView>;
}

interface AccessibleAssistantFeature {
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

interface AccessibleAssistantCapability {
  capabilityCode: string;
  group: string;
  path: string;
  label: string;
  description?: string;
  operations: AssistantFeatureOperation[];
  writePolicy: string;
}

const MAX_TOOL_FOLLOW_UP_DEPTH = 4;
const DEFAULT_FEATURE_PAGE_SIZE = 20;
const MAX_FEATURE_PAGE_SIZE = 100;
const ASSISTANT_STREAM_IDLE_TIMEOUT_MS = 45_000;

const ASSISTANT_FEATURE_TOOL_SPECS: Record<string, AssistantFeatureToolSpec> = {
  "/dashboard": {
    capabilityCode: "studio.dashboard.overview",
    description: "查看当前项目的数据源、模型、任务、运行等概览。",
    list: { purpose: "读取项目看板概览。" },
  },
  "/access-center": {
    capabilityCode: "studio.accessCenter.overview",
    description: "查看当前用户可申请或已申请的项目访问入口。",
    list: { purpose: "读取工作区访问概览。" },
    writePolicy: "项目访问申请、取消申请必须由用户确认后执行。",
  },
  "/catalog": {
    capabilityCode: "studio.catalog.capabilities",
    description: "查看 Studio 支持的数据源类型、读写能力和运行参数能力。",
    list: { purpose: "读取数据源能力矩阵。" },
  },
  "/metadata": {
    capabilityCode: "studio.metadata.schemas",
    description: "查看元模型和字段定义。",
    list: { purpose: "列出元模型定义。", optionalValues: ["includeFields"] },
    get: { purpose: "读取单个元模型详情。", requiredValues: ["id"] },
  },
  "/datasources": {
    capabilityCode: "studio.datasources.manage",
    description: "查看数据源列表、详情和连接历史。",
    list: { purpose: "分页读取数据源列表。", optionalValues: ["pageNo", "pageSize"] },
    get: { purpose: "读取数据源详情。", requiredValues: ["id"] },
    writePolicy: "新增、修改、测试、删除数据源必须经过用户确认。",
  },
  "/models": {
    capabilityCode: "studio.models.manage",
    description: "查看数据模型、表字段、预览数据和血缘。",
    list: { purpose: "分页读取模型摘要或按关键词查询模型候选。", optionalValues: ["keyword", "name", "tableName", "datasourceId", "datasourceType", "pageNo", "pageSize"] },
    get: { purpose: "读取模型详情。", requiredValues: ["id"], optionalValues: ["preview", "lineageLevel"] },
    writePolicy: "模型同步、保存、删除、手工血缘变更必须经过用户确认。",
  },
  "/statistics": {
    capabilityCode: "studio.modelStatistics.query",
    description: "查看模型统计配置和统计图表。",
    list: { purpose: "读取模型统计选项或按条件查询统计图表。", optionalValues: ["view", "datasourceId", "modelId", "startTime", "endTime"] },
  },
  "/field-mapping-rules": {
    capabilityCode: "studio.fieldMappingRules.manage",
    description: "查看字段映射规则。",
    list: { purpose: "分页读取字段映射规则。", optionalValues: ["keyword", "mappingType", "enabled", "pageNo", "pageSize"] },
    get: { purpose: "读取字段映射规则详情。", requiredValues: ["id"] },
    writePolicy: "保存、删除字段映射规则必须经过用户确认。",
  },
  "/collection-tasks": {
    capabilityCode: "studio.collectionTasks.manage",
    description: "查看和配置采集任务。",
    list: { purpose: "分页读取采集任务。", optionalValues: ["keyword", "name", "targetDatasource", "targetModel", "pageNo", "pageSize"] },
    get: { purpose: "读取采集任务详情。", requiredValues: ["id"] },
    writePolicy: "保存、上线、触发、删除、调度、重置增量游标必须经过用户确认；默认不生成定时调度。",
  },
  "/collection-task-runs": {
    capabilityCode: "studio.collectionTaskRuns.query",
    description: "查看采集任务运行记录和日志。",
    list: { purpose: "分页读取采集任务运行记录。", optionalValues: ["collectionTaskId", "status", "startTime", "endTime", "pageNo", "pageSize"] },
    get: { purpose: "读取运行记录详情或日志。", requiredValues: ["id"], optionalValues: ["log"] },
  },
  "/run-metrics": {
    capabilityCode: "studio.runMetrics.query",
    description: "查看任务运行指标。",
    list: { purpose: "查询运行指标概览。", optionalValues: ["startTime", "endTime", "executionType", "status"] },
  },
  "/data-development": {
    capabilityCode: "studio.dataDevelopment.manage",
    description: "查看脚本目录、脚本和 SQL 数据源候选。",
    list: { purpose: "读取脚本目录树或脚本列表。", optionalValues: ["view", "scriptType"] },
    get: { purpose: "读取脚本详情。", requiredValues: ["id"] },
    writePolicy: "保存、移动、删除、执行 SQL/脚本必须经过用户确认。",
  },
  "/workflows": {
    capabilityCode: "studio.workflows.manage",
    description: "查看工作流定义和可绑定任务。",
    list: { purpose: "分页读取工作流列表。", optionalValues: ["pageNo", "pageSize"] },
    get: { purpose: "读取工作流详情。", requiredValues: ["id"] },
    writePolicy: "保存、发布、触发、删除工作流必须经过用户确认。",
  },
  "/runs": {
    capabilityCode: "studio.runs.query",
    description: "查看工作流、采集、质量等运行记录和日志。",
    list: { purpose: "分页读取运行记录。", optionalValues: ["collectionTaskId", "qualityTaskId", "workflowDefinitionId", "status", "startTime", "endTime", "pageNo", "pageSize"] },
    get: { purpose: "读取运行记录详情或日志。", requiredValues: ["id"], optionalValues: ["log"] },
  },
  "/data-services": {
    capabilityCode: "studio.dataServices.manage",
    description: "查看开放数据服务及订阅。",
    list: { purpose: "分页读取数据服务。", optionalValues: ["keyword", "status", "serviceType", "pageNo", "pageSize"] },
    get: { purpose: "读取数据服务详情、WebService 预览或订阅。", requiredValues: ["id"], optionalValues: ["view"] },
    writePolicy: "保存、发布、下线、删除、调试、订阅变更必须经过用户确认。",
  },
  "/data-ingestion-services": {
    capabilityCode: "studio.dataIngestionServices.manage",
    description: "查看数据接入服务及订阅。",
    list: { purpose: "分页读取数据接入服务。", optionalValues: ["keyword", "status", "targetType", "pageNo", "pageSize"] },
    get: { purpose: "读取数据接入服务详情、WebService 预览或订阅。", requiredValues: ["id"], optionalValues: ["view"] },
    writePolicy: "保存、发布、下线、删除、调试、订阅变更必须经过用户确认。",
  },
  "/protocol-conversions": {
    capabilityCode: "studio.protocolConversions.manage",
    description: "查看协议转换服务及订阅。",
    list: { purpose: "分页读取协议转换服务。", optionalValues: ["keyword", "status", "pageNo", "pageSize"] },
    get: { purpose: "读取协议转换服务详情或订阅。", requiredValues: ["id"], optionalValues: ["view"] },
    writePolicy: "保存、发布、下线、删除、调试、订阅变更必须经过用户确认。",
  },
  "/data-ingestion-metrics": {
    capabilityCode: "studio.dataIngestionMetrics.query",
    description: "查看数据接入服务指标、接口统计和访问日志。",
    list: { purpose: "查询数据接入指标。", optionalValues: ["view", "startTime", "endTime", "status", "pageNo", "pageSize"] },
  },
  "/data-service-metrics": {
    capabilityCode: "studio.dataServiceMetrics.query",
    description: "查看数据服务指标、接口统计和访问日志。",
    list: { purpose: "查询数据服务指标。", optionalValues: ["view", "startTime", "endTime", "status", "pageNo", "pageSize"] },
  },
  "/quality-rules": {
    capabilityCode: "studio.qualityRules.manage",
    description: "查看质量规则和规则候选。",
    list: { purpose: "分页读取质量规则。", optionalValues: ["keyword", "ruleDimension", "scopeType", "enabled", "pageNo", "pageSize"] },
    get: { purpose: "读取质量规则详情。", requiredValues: ["id"] },
    writePolicy: "保存、启用、禁用、删除质量规则必须经过用户确认。",
  },
  "/quality-tasks": {
    capabilityCode: "studio.qualityTasks.manage",
    description: "查看质量任务和预览/校验配置。",
    list: { purpose: "分页读取质量任务。", optionalValues: ["keyword", "status", "ruleDimension", "granularity", "pageNo", "pageSize"] },
    get: { purpose: "读取质量任务详情。", requiredValues: ["id"] },
    writePolicy: "保存、上线、触发、删除、调度质量任务必须经过用户确认。",
  },
  "/quality-task-runs": {
    capabilityCode: "studio.qualityTaskRuns.query",
    description: "查看质量任务运行记录和日志。",
    list: { purpose: "分页读取质量任务运行记录。", optionalValues: ["qualityTaskId", "status", "startTime", "endTime", "pageNo", "pageSize"] },
    get: { purpose: "读取运行记录详情或日志。", requiredValues: ["id"], optionalValues: ["log"] },
  },
  "/quality-metrics": {
    capabilityCode: "studio.qualityMetrics.query",
    description: "查看质量指标、资产风险和问题详情。",
    list: { purpose: "查询质量指标、资产或问题。", optionalValues: ["view", "datasourceId", "keyword", "status", "startTime", "endTime", "pageNo", "pageSize"] },
    get: { purpose: "读取质量问题或资产详情。", requiredValues: ["id"], optionalValues: ["resource"] },
    writePolicy: "分派问题、更新状态、更新严重级别、评论必须经过用户确认。",
  },
  "/system": {
    capabilityCode: "studio.system.manage",
    description: "查看租户、项目、成员、角色、权限和资源共享。",
    list: { purpose: "分页读取系统管理资源。", optionalValues: ["resource", "projectId", "resourceType", "pageNo", "pageSize"] },
    writePolicy: "系统管理中的保存、审批、驳回、删除必须经过用户确认。",
  },
  "/script-environments": {
    capabilityCode: "studio.scriptEnvironments.manage",
    description: "查看脚本运行环境。",
    list: { purpose: "分页读取脚本运行环境。", optionalValues: ["keyword", "enabled", "pageNo", "pageSize"] },
    get: { purpose: "读取脚本运行环境详情。", requiredValues: ["id"] },
    writePolicy: "保存、启用、禁用、刷新脚本环境必须经过用户确认。",
  },
  "/ops-center": {
    capabilityCode: "studio.opsCenter.query",
    description: "查看运行中心概览、队列、Worker、服务事件和日志事件。",
    list: { purpose: "查询运维中心概览或明细。", optionalValues: ["view", "startTime", "endTime", "executionType", "status", "workerGroupCode", "pageNo", "pageSize"] },
  },
};

const FORBIDDEN_ASSISTANT_ACTION_PATTERN = /(?:^|[._-])(delete|remove|drop|truncate|purge|destroy|batchdelete|unfollow|cancel)(?:$|[._-])|删除|移除|清空|销毁|批量删除/i;

const ASSISTANT_FEATURE_ACTION_DEFINITIONS: AssistantFeatureActionDefinition[] = [
  { path: "/access-center", action: "apply", purpose: "申请进入项目或工作区。", mutation: true, requiredValues: ["payload"], aliases: ["requestAccess"] },

  { path: "/metadata", action: "saveDraft", purpose: "保存元模型草稿。", mutation: true, requiredValues: ["payload"], aliases: ["save"] },
  { path: "/metadata", action: "publish", purpose: "发布元模型。", mutation: true, requiredValues: ["id"] },
  { path: "/metadata", action: "syncTechnical", purpose: "同步指定数据源类型的技术元模型。", mutation: true, requiredValues: ["typeCode"], aliases: ["sync"] },
  { path: "/metadata", action: "syncAllTechnical", purpose: "同步全部技术元模型。", mutation: true, aliases: ["syncAll"] },
  { path: "/metadata", action: "syncStandardRuntimeOptions", purpose: "同步标准运行参数元模型。", mutation: true, aliases: ["syncRuntimeOptions"] },

  { path: "/datasources", action: "save", purpose: "新增或修改数据源。", mutation: true, requiredValues: ["payload"] },
  { path: "/datasources", action: "test", purpose: "测试已保存数据源连接。", mutation: true, requiredValues: ["id"] },
  { path: "/datasources", action: "testCurrent", purpose: "测试尚未保存的数据源配置。", mutation: true, requiredValues: ["payload"] },
  { path: "/datasources", action: "discover", purpose: "发现数据源下的模型候选。", mutation: false, requiredValues: ["id"], optionalValues: ["keyword", "pageNo", "pageSize"] },

  { path: "/models", action: "save", purpose: "保存模型定义。", mutation: true, requiredValues: ["payload"] },
  { path: "/models", action: "sync", purpose: "同步指定数据源的模型。", mutation: true, requiredValues: ["datasourceId"], aliases: ["syncDatasource"] },
  { path: "/models", action: "syncSelected", purpose: "按选择范围同步指定数据源的模型。", mutation: true, requiredValues: ["datasourceId", "payload"] },
  { path: "/models", action: "rebuildIndex", purpose: "重建模型检索索引。", mutation: true, optionalValues: ["datasourceId"] },

  { path: "/field-mapping-rules", action: "save", purpose: "保存字段映射规则。", mutation: true, requiredValues: ["payload"] },

  { path: "/collection-tasks", action: "preview", purpose: "预览采集任务配置。", mutation: false, requiredValues: ["payload"], aliases: ["validatePreview"] },
  { path: "/collection-tasks", action: "save", purpose: "保存采集任务草稿或配置。", mutation: true, requiredValues: ["payload"] },
  { path: "/collection-tasks", action: "publish", purpose: "上线采集任务。", mutation: true, requiredValues: ["id"], aliases: ["online"] },
  { path: "/collection-tasks", action: "trigger", purpose: "立即触发采集任务。", mutation: true, requiredValues: ["id"], aliases: ["run", "execute"] },
  { path: "/collection-tasks", action: "schedule", purpose: "保存采集任务调度配置。", mutation: true, requiredValues: ["id", "payload"] },
  { path: "/collection-tasks", action: "resetIncrementalCursor", purpose: "重置采集任务增量游标。", mutation: true, requiredValues: ["id"], optionalValues: ["sourceAlias", "incrColumn", "incrModel"] },

  { path: "/data-development", action: "saveDirectory", resource: "directories", purpose: "保存脚本目录。", mutation: true, requiredValues: ["payload"], aliases: ["save"] },
  { path: "/data-development", action: "moveDirectory", resource: "directories", purpose: "移动脚本目录。", mutation: true, requiredValues: ["id", "payload"], aliases: ["move"] },
  { path: "/data-development", action: "saveScript", resource: "scripts", purpose: "保存脚本。", mutation: true, requiredValues: ["payload"], aliases: ["save"] },
  { path: "/data-development", action: "moveScript", resource: "scripts", purpose: "移动脚本。", mutation: true, requiredValues: ["id", "payload"], aliases: ["move"] },
  { path: "/data-development", action: "executeSql", resource: "sql", purpose: "执行 SQL。", mutation: true, requiredValues: ["payload"], aliases: ["execute", "run"] },
  { path: "/data-development", action: "executeScript", resource: "scripts", purpose: "执行未保存脚本内容。", mutation: true, requiredValues: ["payload"], aliases: ["executeContent"] },
  { path: "/data-development", action: "executeSavedScript", resource: "scripts", purpose: "执行已保存脚本。", mutation: true, requiredValues: ["id"], optionalValues: ["payload"], aliases: ["executeSaved", "runSaved"] },

  { path: "/workflows", action: "save", purpose: "保存工作流定义。", mutation: true, requiredValues: ["payload"] },
  { path: "/workflows", action: "publish", purpose: "发布工作流。", mutation: true, requiredValues: ["id"] },
  { path: "/workflows", action: "trigger", purpose: "立即触发工作流。", mutation: true, requiredValues: ["id"], aliases: ["run", "execute"] },
  { path: "/workflows", action: "schedule", purpose: "保存工作流调度配置。", mutation: true, requiredValues: ["id", "payload"], aliases: ["timing", "cron"] },

  { path: "/data-services", action: "save", purpose: "保存数据服务。", mutation: true, requiredValues: ["payload"] },
  { path: "/data-services", action: "publish", purpose: "发布数据服务。", mutation: true, requiredValues: ["id"] },
  { path: "/data-services", action: "offline", purpose: "下线数据服务。", mutation: true, requiredValues: ["id"] },
  { path: "/data-services", action: "resolveFields", purpose: "解析数据服务字段。", mutation: false, requiredValues: ["payload"] },
  { path: "/data-services", action: "debug", purpose: "调试数据服务。", mutation: true, requiredValues: ["id", "payload"] },
  { path: "/data-services", action: "debugWebService", purpose: "调试数据服务 WebService。", mutation: true, requiredValues: ["id", "payload"], resource: "webservice", aliases: ["debug"] },
  { path: "/data-services", action: "createSubscription", purpose: "创建数据服务订阅。", mutation: true, requiredValues: ["id", "subscriptionName"], resource: "subscriptions", aliases: ["subscribe"] },
  { path: "/data-services", action: "enableSubscription", purpose: "启用数据服务订阅。", mutation: true, requiredValues: ["id", "subscriptionId"], resource: "subscriptions", aliases: ["enable"] },
  { path: "/data-services", action: "disableSubscription", purpose: "禁用数据服务订阅。", mutation: true, requiredValues: ["id", "subscriptionId"], resource: "subscriptions", aliases: ["disable"] },
  { path: "/data-services", action: "rotateSubscription", purpose: "轮换数据服务订阅密钥。", mutation: true, requiredValues: ["id", "subscriptionId"], resource: "subscriptions", aliases: ["rotate"] },

  { path: "/data-ingestion-services", action: "save", purpose: "保存数据接入服务。", mutation: true, requiredValues: ["payload"] },
  { path: "/data-ingestion-services", action: "publish", purpose: "发布数据接入服务。", mutation: true, requiredValues: ["id"] },
  { path: "/data-ingestion-services", action: "offline", purpose: "下线数据接入服务。", mutation: true, requiredValues: ["id"] },
  { path: "/data-ingestion-services", action: "resolveFields", purpose: "解析数据接入服务字段。", mutation: false, requiredValues: ["payload"] },
  { path: "/data-ingestion-services", action: "debug", purpose: "调试数据接入服务。", mutation: true, requiredValues: ["id", "payload"] },
  { path: "/data-ingestion-services", action: "debugWebService", purpose: "调试数据接入 WebService。", mutation: true, requiredValues: ["id", "payload"], resource: "webservice", aliases: ["debug"] },
  { path: "/data-ingestion-services", action: "createSubscription", purpose: "创建数据接入订阅。", mutation: true, requiredValues: ["id", "subscriptionName"], resource: "subscriptions", aliases: ["subscribe"] },
  { path: "/data-ingestion-services", action: "enableSubscription", purpose: "启用数据接入订阅。", mutation: true, requiredValues: ["id", "subscriptionId"], resource: "subscriptions", aliases: ["enable"] },
  { path: "/data-ingestion-services", action: "disableSubscription", purpose: "禁用数据接入订阅。", mutation: true, requiredValues: ["id", "subscriptionId"], resource: "subscriptions", aliases: ["disable"] },
  { path: "/data-ingestion-services", action: "rotateSubscription", purpose: "轮换数据接入订阅密钥。", mutation: true, requiredValues: ["id", "subscriptionId"], resource: "subscriptions", aliases: ["rotate"] },

  { path: "/protocol-conversions", action: "save", purpose: "保存协议转换服务。", mutation: true, requiredValues: ["payload"] },
  { path: "/protocol-conversions", action: "publish", purpose: "发布协议转换服务。", mutation: true, requiredValues: ["id"] },
  { path: "/protocol-conversions", action: "offline", purpose: "下线协议转换服务。", mutation: true, requiredValues: ["id"] },
  { path: "/protocol-conversions", action: "debug", purpose: "调试协议转换服务。", mutation: true, requiredValues: ["id", "payload"] },
  { path: "/protocol-conversions", action: "createSubscription", purpose: "创建协议转换订阅。", mutation: true, requiredValues: ["id", "subscriptionName"], resource: "subscriptions", aliases: ["subscribe"] },
  { path: "/protocol-conversions", action: "enableSubscription", purpose: "启用协议转换订阅。", mutation: true, requiredValues: ["id", "subscriptionId"], resource: "subscriptions", aliases: ["enable"] },
  { path: "/protocol-conversions", action: "disableSubscription", purpose: "禁用协议转换订阅。", mutation: true, requiredValues: ["id", "subscriptionId"], resource: "subscriptions", aliases: ["disable"] },
  { path: "/protocol-conversions", action: "rotateSubscription", purpose: "轮换协议转换订阅密钥。", mutation: true, requiredValues: ["id", "subscriptionId"], resource: "subscriptions", aliases: ["rotate"] },

  { path: "/quality-rules", action: "save", purpose: "保存质量规则。", mutation: true, requiredValues: ["payload"] },
  { path: "/quality-rules", action: "enable", purpose: "启用质量规则。", mutation: true, requiredValues: ["id"] },
  { path: "/quality-rules", action: "disable", purpose: "禁用质量规则。", mutation: true, requiredValues: ["id"] },
  { path: "/quality-rules", action: "parse", purpose: "解析质量规则参数。", mutation: false, requiredValues: ["payload"] },
  { path: "/quality-rules", action: "validate", purpose: "校验质量规则。", mutation: false, requiredValues: ["payload"] },

  { path: "/quality-tasks", action: "preview", purpose: "预览质量任务配置。", mutation: false, requiredValues: ["payload"] },
  { path: "/quality-tasks", action: "validate", purpose: "校验质量任务配置。", mutation: false, requiredValues: ["payload"] },
  { path: "/quality-tasks", action: "save", purpose: "保存质量任务。", mutation: true, requiredValues: ["payload"] },
  { path: "/quality-tasks", action: "publish", purpose: "上线质量任务。", mutation: true, requiredValues: ["id"], aliases: ["online"] },
  { path: "/quality-tasks", action: "trigger", purpose: "立即触发质量任务。", mutation: true, requiredValues: ["id"], aliases: ["run", "execute"] },
  { path: "/quality-tasks", action: "schedule", purpose: "保存质量任务调度配置。", mutation: true, requiredValues: ["id", "payload"] },

  { path: "/quality-metrics", action: "assignIssue", resource: "issues", purpose: "分派质量问题。", mutation: true, requiredValues: ["id"], optionalValues: ["payload"], aliases: ["assign"] },
  { path: "/quality-metrics", action: "updateIssueStatus", resource: "issues", purpose: "更新质量问题状态。", mutation: true, requiredValues: ["id", "payload"], aliases: ["status"] },
  { path: "/quality-metrics", action: "updateIssueSeverity", resource: "issues", purpose: "更新质量问题严重级别。", mutation: true, requiredValues: ["id", "payload"], aliases: ["severity"] },
  { path: "/quality-metrics", action: "addIssueComment", resource: "issues", purpose: "追加质量问题评论。", mutation: true, requiredValues: ["id", "payload"], aliases: ["comment"] },

  { path: "/script-environments", action: "save", purpose: "保存脚本运行环境。", mutation: true, requiredValues: ["payload"], aliases: ["saveOrUpdateCheck"] },
  { path: "/script-environments", action: "enable", purpose: "启用脚本运行环境。", mutation: true, requiredValues: ["id"] },
  { path: "/script-environments", action: "disable", purpose: "禁用脚本运行环境。", mutation: true, requiredValues: ["id"] },
  { path: "/script-environments", action: "refresh", purpose: "刷新脚本运行环境。", mutation: true, requiredValues: ["id"] },

  { path: "/system", action: "save", resource: "tenants", purpose: "保存租户。", mutation: true, requiredValues: ["payload"] },
  { path: "/system", action: "save", resource: "projects", purpose: "保存项目。", mutation: true, requiredValues: ["payload"] },
  { path: "/system", action: "save", resource: "tenantMembers", purpose: "保存租户成员。", mutation: true, requiredValues: ["payload"] },
  { path: "/system", action: "save", resource: "projectMembers", purpose: "保存项目成员。", mutation: true, requiredValues: ["payload"] },
  { path: "/system", action: "save", resource: "projectMemberRequests", purpose: "保存项目成员申请。", mutation: true, requiredValues: ["payload"] },
  { path: "/system", action: "save", resource: "projectWorkers", purpose: "保存项目 Worker。", mutation: true, requiredValues: ["payload"] },
  { path: "/system", action: "save", resource: "resourceShares", purpose: "保存资源共享配置。", mutation: true, requiredValues: ["payload"] },
  { path: "/system", action: "save", resource: "users", purpose: "保存用户。", mutation: true, requiredValues: ["payload"] },
  { path: "/system", action: "save", resource: "roles", purpose: "保存角色。", mutation: true, requiredValues: ["payload"] },
  { path: "/system", action: "save", resource: "permissions", purpose: "保存权限。", mutation: true, requiredValues: ["payload"] },
  { path: "/system", action: "approve", resource: "userRegistrationRequests", purpose: "通过用户注册申请。", mutation: true, requiredValues: ["id"], optionalValues: ["payload"] },
  { path: "/system", action: "reject", resource: "userRegistrationRequests", purpose: "拒绝用户注册申请。", mutation: true, requiredValues: ["id"], optionalValues: ["payload"] },
];

const ASSISTANT_FRONTEND_TOOL_DEFINITIONS: AssistantFrontendToolDefinition[] = [
  {
    interfaceCode: "studio.navigation.open",
    purpose: "打开当前用户可见功能页面，只允许 path 来自 accessibleCapabilities。",
    mutation: false,
    requiredValues: ["path"],
  },
  {
    interfaceCode: "studio.feature.list",
    purpose: "按当前用户可见功能读取列表、概览、候选值或指标查询；path 必须来自 accessibleCapabilities。",
    mutation: false,
    requiredValues: ["path"],
    optionalValues: ["view", "keyword", "status", "pageNo", "pageSize"],
  },
  {
    interfaceCode: "studio.feature.get",
    purpose: "读取当前用户可见功能下的单条记录详情；path 必须来自 accessibleCapabilities，且该功能声明了 get 操作。",
    mutation: false,
    requiredValues: ["path", "id"],
    optionalValues: ["view", "resource", "log", "lineageLevel", "preview"],
  },
  {
    interfaceCode: "studio.feature.action",
    purpose: "对当前用户可见功能执行前端动作注册表中的非删除动作；必须提供 path 和 action，写入/执行/配置变更动作会被前端拦截为确认控件，确认前不会执行。",
    mutation: true,
    requiredValues: ["path", "action"],
    optionalValues: ["id", "payload", "resource", "subscriptionId", "subscriptionName", "datasourceId", "keyword", "pageNo", "pageSize"],
  },
  {
    interfaceCode: "catalog.capabilities",
    purpose: "读取数据源读写能力矩阵，常用于采集任务编排前判断源端/目标端能力。",
    mutation: false,
    featurePaths: ["/catalog", "/collection-tasks"],
  },
  {
    interfaceCode: "datasources.options",
    purpose: "读取当前项目可选数据源，用于让用户选择源端或目标端。",
    mutation: false,
    featurePaths: ["/datasources", "/models", "/collection-tasks", "/data-development", "/workflows"],
  },
  {
    interfaceCode: "models.datasourceOptions",
    purpose: "读取某个数据源下的模型/表候选。",
    mutation: false,
    requiredValues: ["datasourceId"],
    optionalValues: ["keyword"],
    featurePaths: ["/models", "/collection-tasks", "/data-development"],
  },
  {
    interfaceCode: "models.get",
    purpose: "读取模型字段元数据。",
    mutation: false,
    requiredValues: ["modelId"],
    featurePaths: ["/models", "/collection-tasks", "/data-development"],
  },
  {
    interfaceCode: "catalog.runtimeOptionSchema",
    purpose: "读取 reader/writer 运行参数元模型。",
    mutation: false,
    requiredValues: ["role", "datasourceType"],
    optionalValues: ["protocolMode"],
    featurePaths: ["/catalog", "/collection-tasks"],
  },
  {
    interfaceCode: "collectionTasks.preview",
    purpose: "预览采集任务 JobContainer 配置；只做校验和预览，不保存。",
    mutation: false,
    requiredValues: ["collectionTaskPayload"],
    featurePaths: ["/collection-tasks"],
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
const { t } = useI18n();

const prompt = ref("");
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
let chatAbortController: AbortController | null = null;

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
const accessibleFeatures = computed(() => resolveAccessibleFeatures());
const accessibleCapabilities = computed(() => resolveAccessibleCapabilities());
const availableFeatureActions = computed(() => resolveAvailableFeatureActions());
const availableFrontendTools = computed(() => resolveAvailableFrontendTools());

async function submitPrompt() {
  const message = prompt.value.trim();
  if (!message || assistantBusy.value) {
    return;
  }

  processEvents.value = [];
  processPanelExpanded.value = false;
  messages.value.push(createAssistantMessage("user", message));
  prompt.value = "";
  await nextTick(scrollThreadToBottom);

  const assistantMessage = await streamAssistantReply(message, conversationForRequest());
  await handleAssistantActions(assistantMessage, 0);
  void maybePersistLocalLearning(message, assistantMessage.content);
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
      assistantMessage.content = "我现在没有拿到有效回复，请稍后再试。";
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
  message.controls = [createStreamRecoveryControl()];
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

onBeforeUnmount(() => {
  chatAbortController?.abort();
});

async function handleAssistantActions(message: AssistantLogMessage, depth: number) {
  const parsed = extractAssistantActions(message.rawContent || message.content);
  const actionOnly = parsed.toolCalls.length > 0 && parsed.uiControls.length === 0 && !parsed.content.trim();
  if (actionOnly) {
    removeAssistantMessage(message);
  } else {
    message.content = resolveVisibleActionMessage(parsed, message.content);
  }

  const waitingForUser = appendActionControls(parsed.uiControls);
  if (!parsed.toolCalls.length) {
    if (!waitingForUser && shouldRecoverMissingAssistantAction(message, parsed.content, depth)) {
      await continueAfterMissingAssistantAction(depth + 1);
    }
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

  if (failedCount > 0 && !waitingForUser && confirmationCount === 0 && depth < MAX_TOOL_FOLLOW_UP_DEPTH) {
    await continueAfterToolFailure(depth + 1);
    return;
  }

  if (executedCount > 0 && !waitingForUser && confirmationCount === 0 && depth < MAX_TOOL_FOLLOW_UP_DEPTH) {
    await continueAfterToolResults(depth + 1);
  }
}

function removeAssistantMessage(message: AssistantLogMessage) {
  messages.value = messages.value.filter((item) => item.id !== message.id);
}

function resolveVisibleActionMessage(
  parsed: { content: string } & AssistantActionPayload,
  fallback: string,
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
  return fallback;
}

async function continueAfterToolResults(depth: number) {
  const assistantMessage = await streamAssistantReply(
    "接口结果已返回，请结合 toolResults 继续回答或判断下一步是否还需要调用接口。",
    conversationForRequest("接口结果已返回，请结合 toolResults 继续回答或判断下一步是否还需要调用接口。"),
  );
  await handleAssistantActions(assistantMessage, depth);
}

async function continueAfterToolFailure(depth: number) {
  const instruction = "最近一次接口或参数校验失败。请只根据 toolResults 中的 error、params、已有对话和可用能力自检原因：如果可以从上下文修正参数，请输出修正后的 assistant-action；如果缺少必须由用户决定的值，请输出 uiControls 让用户选择或填写；不要直接复述原始接口错误。";
  const assistantMessage = await streamAssistantReply(
    instruction,
    conversationForRequest(instruction),
  );
  await handleAssistantActions(assistantMessage, depth);
}

async function continueAfterMissingAssistantAction(depth: number) {
  pushProcessEvent({
    stage: "llm.self-check",
    title: "自检缺失的接口调用",
    detail: "模型表达了要继续查询，但没有给出可执行 action，正在要求它补齐。",
    status: "running",
  });
  const instruction = "上一条助手回复表达了要查询、读取、获取、查看、打开、预览或继续处理，但没有输出 assistant-action 或 uiControls，导致编排器无法继续。请基于最新用户问题、currentContext.frontendTools、accessibleCapabilities 和 toolResults 自检：如果需要读取项目资源，输出一个隐藏 assistant-action；如果缺少用户必须决定的值，输出 uiControls；如果已有足够结果，直接给最终答案。不要再只说准备查询。";
  const assistantMessage = await streamAssistantReply(
    instruction,
    conversationForRequest(instruction),
  );
  markProcessDone("llm.self-check", "缺失 action 自检完成", "已让模型重新判断下一步。");
  await handleAssistantActions(assistantMessage, depth);
}

function shouldRecoverMissingAssistantAction(message: AssistantLogMessage, visibleContent: string, depth: number) {
  if (depth >= MAX_TOOL_FOLLOW_UP_DEPTH || message.controls?.length) {
    return false;
  }
  const text = stripAssistantActionBlocks(visibleContent || message.content).trim();
  if (!text || text.length > 120) {
    return false;
  }
  const actionIntent = /(我先|先帮你|我会|将|准备|正在|继续).{0,16}(查询|读取|获取|查看|检索|加载|打开|预览|分析|处理)/;
  const hasFinalSignal = /(如下|以下|结果|表格|列表|没有|暂无|未找到|已完成|可以看到|汇总)/;
  return actionIntent.test(text) && !hasFinalSignal.test(text);
}

function extractAssistantActions(content: string): { content: string } & AssistantActionPayload {
  const toolCalls: AssistantToolCall[] = [];
  const uiControls: AssistantActionControl[] = [];
  const cleaned = String(content ?? "").replace(/```assistant[-_]action\s*([\s\S]*?)```/gi, (_match, rawJson) => {
    appendAssistantActionPayload(String(rawJson).trim(), toolCalls, uiControls);
    return "";
  });
  appendLooseAssistantActionPayloads(cleaned, toolCalls, uiControls);
  return {
    content: stripAssistantActionBlocks(cleaned),
    toolCalls,
    uiControls,
  };
}

function appendAssistantActionPayload(
  rawJson: string,
  toolCalls: AssistantToolCall[],
  uiControls: AssistantActionControl[],
) {
  try {
    const payload = JSON.parse(rawJson) as Partial<AssistantActionPayload> & { controls?: AssistantActionControl[] };
    if (Array.isArray(payload)) {
      uiControls.push(...payload.filter(isLikelyActionControl));
      return;
    }
    if (isLikelyActionControl(payload)) {
      uiControls.push(payload);
      return;
    }
    if (Array.isArray(payload.toolCalls)) {
      toolCalls.push(...payload.toolCalls);
    }
    if (Array.isArray(payload.uiControls)) {
      uiControls.push(...payload.uiControls);
    }
    if (Array.isArray(payload.controls)) {
      uiControls.push(...payload.controls);
    }
  } catch {
    // Malformed action blocks are hidden from the user but otherwise ignored.
  }
}

function isLikelyActionControl(value: unknown): value is AssistantActionControl {
  if (!value || typeof value !== "object") {
    return false;
  }
  const item = value as AssistantActionControl;
  return Boolean(item.title && (item.paramKey || item.options?.length || item.placeholder));
}

function appendLooseAssistantActionPayloads(
  content: string,
  toolCalls: AssistantToolCall[],
  uiControls: AssistantActionControl[],
) {
  const text = String(content ?? "");
  let searchIndex = 0;
  while (searchIndex >= 0 && searchIndex < text.length) {
    const marker = firstActionJsonMarker(text, searchIndex);
    if (marker < 0) {
      break;
    }
    const jsonStart = findLooseActionJsonStart(text, marker);
    if (jsonStart < 0) {
      searchIndex = marker + 1;
      continue;
    }
    const jsonEnd = findJsonValueEnd(text, jsonStart);
    if (jsonEnd < 0) {
      break;
    }
    appendAssistantActionPayload(text.slice(jsonStart, jsonEnd + 1), toolCalls, uiControls);
    searchIndex = jsonEnd + 1;
  }
}

function stripAssistantActionBlocks(content: string) {
  const withoutCompleteBlocks = String(content ?? "").replace(/```assistant[-_]action\s*[\s\S]*?```/gi, "");
  const partialBlockStart = withoutCompleteBlocks.search(/```assistant[-_]action/iu);
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

function firstActionJsonMarker(text: string, fromIndex = 0) {
  const markers = ['"toolCalls"', '"backendToolCalls"', '"uiControls"', '"paramKey"'];
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

function findJsonValueEnd(text: string, start: number) {
  let inString = false;
  let escaped = false;
  let objectDepth = 0;
  let arrayDepth = 0;
  for (let index = start; index < text.length; index += 1) {
    const value = text[index];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (value === "\\" && inString) {
      escaped = true;
      continue;
    }
    if (value === "\"") {
      inString = !inString;
      continue;
    }
    if (inString) {
      continue;
    }
    if (value === "{") {
      objectDepth += 1;
    } else if (value === "[") {
      arrayDepth += 1;
    } else if (value === "}") {
      objectDepth -= 1;
      if (objectDepth === 0 && arrayDepth === 0) {
        return index;
      }
    } else if (value === "]") {
      arrayDepth -= 1;
      if (objectDepth === 0 && arrayDepth === 0) {
        return index;
      }
    }
  }
  return -1;
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
  if (code === "studio.navigation.open") {
    const path = normalizeRoutePath(firstText(params, ["path", "route", "targetPath"]));
    if (!path) {
      return { ok: false, error: "导航前需要提供 path。" };
    }
    if (!findAccessibleFeature(path)) {
      return { ok: false, error: `当前用户没有进入 ${path} 的可见功能权限。` };
    }
    return { ok: true, value: { path } };
  }
  if (code === "studio.feature.list") {
    const path = normalizeRoutePath(firstText(params, ["path", "featurePath", "route"]));
    const feature = findAccessibleFeature(path);
    if (!path || !feature) {
      return { ok: false, error: "读取功能数据前需要提供当前用户可见的 path。" };
    }
    const spec = ASSISTANT_FEATURE_TOOL_SPECS[normalizeRoutePath(feature.path)];
    if (!spec?.list) {
      return { ok: false, error: `${feature.label} 暂未开放可自动读取的列表或概览工具。` };
    }
    return { ok: true, value: normalizeFeatureToolParams(params, path) };
  }
  if (code === "studio.feature.get") {
    const path = normalizeRoutePath(firstText(params, ["path", "featurePath", "route"]));
    const feature = findAccessibleFeature(path);
    if (!path || !feature) {
      return { ok: false, error: "读取详情前需要提供当前用户可见的 path。" };
    }
    const spec = ASSISTANT_FEATURE_TOOL_SPECS[normalizeRoutePath(feature.path)];
    if (!spec?.get) {
      return { ok: false, error: `${feature.label} 暂未开放可自动读取的详情工具。` };
    }
    const id = firstText(params, ["id", "recordId", "entityId", "taskId", "modelId", "serviceId", "runId", "scriptId"]);
    if (!id) {
      return { ok: false, error: `读取 ${feature.label} 详情前需要提供 id。` };
    }
    return { ok: true, value: normalizeFeatureToolParams({ ...params, id }, path) };
  }
  if (code === "studio.feature.action") {
    return resolveFeatureActionParams(params);
  }
  if (code === "catalog.capabilities" || code === "datasources.options") {
    return { ok: true, value: {} };
  }
  if (code === "models.datasourceOptions") {
    const datasourceId = firstText(params, ["datasourceId", "sourceDatasourceId", "targetDatasourceId", "source.datasourceId", "target.datasourceId"]);
    if (!datasourceId) {
      return { ok: false, error: "调用模型候选接口前需要先明确 datasourceId。" };
    }
    return {
      ok: true,
      value: {
        datasourceId,
        keyword: firstText(params, ["keyword", "modelKeyword", "tableKeyword"]),
      },
    };
  }
  if (code === "models.get") {
    const modelId = firstText(params, ["modelId", "sourceModelId", "targetModelId", "source.modelId", "target.modelId"]);
    if (!modelId) {
      return { ok: false, error: "读取模型详情前需要先明确 modelId。" };
    }
    return { ok: true, value: { modelId } };
  }
  if (code === "catalog.runtimeOptionSchema") {
    const role = firstText(params, ["role"]);
    const datasourceType = firstText(params, ["datasourceType", "typeCode"]);
    if (!role || !datasourceType) {
      return { ok: false, error: "读取运行参数元模型前需要 role 和 datasourceType。" };
    }
    return {
      ok: true,
      value: {
        role,
        datasourceType,
        protocolMode: firstText(params, ["protocolMode"]),
      },
    };
  }
  if (code === "collectionTasks.preview") {
    const payload = params.collectionTaskPayload ?? params.payload;
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      return { ok: false, error: "预览采集任务前需要提供 collectionTaskPayload 对象。" };
    }
    return { ok: true, value: { collectionTaskPayload: payload } };
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
    const data = await callAssistantTool(interfaceCode, params);
    toolResults.value.push(createToolResult(interfaceCode, params, data));
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
  const result = await executeAssistantTool(pending.interfaceCode, pending.params);
  if (result.ok) {
    appendToolResultMessage(pending.interfaceCode, pending.params, result.data);
    await continueAfterToolResults(1);
  } else {
    await continueAfterToolFailure(1);
  }
}

function describeToolConfirmation(interfaceCode: string, params: Record<string, unknown>) {
  if (interfaceCode === "studio.feature.action") {
    const feature = findAccessibleFeature(String(params.path ?? ""));
    const resource = firstText(params, ["resource"]);
    const id = firstText(params, ["id"]);
    const action = firstText(params, ["action"]);
    return `${feature?.label ?? String(params.path ?? "")}：${action}${resource ? ` / ${resource}` : ""}${id ? `，目标 ID ${id}` : ""}。`;
  }
  return `${interfaceCode}。`;
}

async function callAssistantTool(interfaceCode: string, params: Record<string, unknown>) {
  switch (interfaceCode) {
    case "studio.navigation.open": {
      const feature = findAccessibleFeature(String(params.path));
      if (!feature) {
        throw new Error(`当前用户没有进入 ${String(params.path)} 的可见功能权限。`);
      }
      await router.push(feature.path);
      return {
        path: feature.path,
        label: feature.label,
        navigated: true,
      };
    }
    case "studio.feature.list":
      return callFeatureListTool(String(params.path), params);
    case "studio.feature.get":
      return callFeatureGetTool(String(params.path), params);
    case "studio.feature.action":
      return callFeatureActionTool(params);
    case "catalog.capabilities":
      return studioApi.catalog.capabilities();
    case "datasources.options":
      return studioApi.datasources.options();
    case "models.datasourceOptions":
      return studioApi.models.listDatasourceOptions(String(params.datasourceId), {
        keyword: String(params.keyword ?? "").trim() || undefined,
        pageNo: 1,
        pageSize: 100,
      });
    case "models.get":
      return studioApi.models.get(String(params.modelId));
    case "catalog.runtimeOptionSchema":
      return studioApi.catalog.runtimeOptionSchema({
        role: String(params.role),
        datasourceType: String(params.datasourceType),
        protocolMode: params.protocolMode ? String(params.protocolMode) : undefined,
      });
    case "collectionTasks.preview":
      return studioApi.collectionTasks.preview(params.collectionTaskPayload as CollectionTaskSaveRequest);
    default:
      throw new Error(`未注册前端接口工具：${interfaceCode}`);
  }
}

function resolveFeatureActionParams(params: Record<string, unknown>): { ok: true; value: Record<string, unknown> } | { ok: false; error: string } {
  const path = normalizeRoutePath(firstText(params, ["path", "featurePath", "route"]));
  const feature = findAccessibleFeature(path);
  if (!path || !feature) {
    return { ok: false, error: "执行功能动作前需要提供当前用户可见的 path。" };
  }

  const rawAction = firstText(params, ["action", "operation", "command"]);
  if (!rawAction) {
    return { ok: false, error: `执行 ${feature.label} 动作前需要提供 action。` };
  }
  if (isForbiddenFeatureAction(rawAction)) {
    return { ok: false, error: "助手不开放删除、移除、清空、取消或类似破坏性动作。" };
  }

  const resource = normalizeFeatureResource(firstText(params, ["resource", "resourceType", "target", "subresource"]));
  const resolved = resolveFeatureActionDefinition(path, rawAction, resource);
  if (!resolved.definition) {
    return { ok: false, error: resolved.error || `当前功能暂未开放动作：${rawAction}` };
  }

  const payload = firstRecordParam(params, [
    "payload",
    "data",
    "body",
    "request",
    "form",
    "collectionTaskPayload",
    "qualityTaskPayload",
    "workflowPayload",
    "servicePayload",
    "scriptPayload",
    "directoryPayload",
    "schedule",
    "debugPayload",
    "reviewPayload",
  ]);
  const normalizedPayload = normalizeFeatureActionPayload(
    path,
    resolved.definition.action,
    resolved.definition.resource || resource,
    payload,
    params,
  );
  const normalizedParams = {
    ...params,
    path,
    action: resolved.definition.action,
    resource: resolved.definition.resource || resource || undefined,
    id: firstText(params, ["id", "recordId", "entityId", "taskId", "modelId", "serviceId", "workflowId", "scriptId", "schemaId", "requestId"]),
    datasourceId: firstText(params, ["datasourceId", "sourceDatasourceId", "targetDatasourceId", "typeDatasourceId"]),
    typeCode: firstText(params, ["typeCode", "datasourceType", "sourceType", "targetType"]),
    subscriptionId: firstText(params, ["subscriptionId", "credentialId", "tokenId"]),
    subscriptionName: firstText(params, ["subscriptionName", "name"]),
    sourceAlias: firstText(params, ["sourceAlias"]),
    incrColumn: firstText(params, ["incrColumn"]),
    incrModel: firstText(params, ["incrModel"]),
    keyword: firstText(params, ["keyword", "name", "search"]),
    payload: normalizedPayload,
  } as Record<string, unknown>;

  const missing = (resolved.definition.requiredValues ?? [])
    .filter((key) => !hasFeatureActionValue(normalizedParams, key));
  if (missing.length) {
    return {
      ok: false,
      error: `${feature.label} 的 ${resolved.definition.action} 动作缺少必要参数：${missing.join(", ")}。`,
    };
  }
  const payloadError = validateFeatureActionPayload(path, resolved.definition.action, resolved.definition.resource || resource, normalizedPayload);
  if (payloadError) {
    return { ok: false, error: payloadError };
  }

  return { ok: true, value: cleanObject(normalizedParams) };
}

function normalizeFeatureActionPayload(
  path: string,
  action: string,
  resource: string,
  payload: Record<string, unknown> | undefined,
  params: Record<string, unknown>,
) {
  if (path === "/data-development") {
    return normalizeDataDevelopmentActionPayload(action, resource, payload, params);
  }
  return payload;
}

function normalizeDataDevelopmentActionPayload(
  action: string,
  resource: string,
  payload: Record<string, unknown> | undefined,
  params: Record<string, unknown>,
) {
  const raw = isPlainRecord(payload) ? payload : {};
  const sources = [raw, params];
  if (resource === "sql" && action === "executeSql") {
    const scriptType = normalizeScriptTypeValue(firstTextFromSources(sources, ["scriptType", "type", "language"]))
      || "SQL";
    return cleanObject({
      datasourceId: firstTextFromSources(sources, [
        "datasourceId",
        "dataSourceId",
        "sourceDatasourceId",
        "targetDatasourceId",
        "datasource.id",
        "source.datasourceId",
        "target.datasourceId",
      ]),
      scriptType,
      content: normalizeScriptContent(firstTextFromSources(sources, ["content", "sql", "script", "query", "statement", "text"])),
      maxRows: readNumberFromSources(sources, "maxRows", 100, 1, 10000),
    });
  }
  if (resource === "scripts" && action === "saveScript") {
    const content = normalizeScriptContent(firstTextFromSources(sources, ["content", "sql", "script", "query", "statement", "text"]));
    const scriptType = normalizeScriptTypeValue(firstTextFromSources(sources, ["scriptType", "type", "language"]))
      || inferScriptTypeFromPayload(content, firstTextFromSources(sources, ["fileName", "filename", "name", "scriptName", "title"]));
    return cleanObject({
      id: firstTextFromSources(sources, ["id", "scriptId", "recordId"]),
      directoryId: firstTextFromSources(sources, ["directoryId", "folderId", "parentId"]),
      fileName: normalizeScriptFileName(
        firstTextFromSources(sources, ["fileName", "filename", "name", "scriptName", "title"]),
        scriptType,
      ),
      scriptType,
      datasourceId: firstTextFromSources(sources, [
        "datasourceId",
        "dataSourceId",
        "sourceDatasourceId",
        "targetDatasourceId",
        "datasource.id",
      ]),
      environmentId: firstTextFromSources(sources, ["environmentId", "runtimeEnvironmentId"]),
      description: firstTextFromSources(sources, ["description", "remark", "comment"]),
      content,
    });
  }
  if (resource === "scripts" && action === "executeScript") {
    const content = normalizeScriptContent(firstTextFromSources(sources, ["content", "sql", "script", "query", "statement", "text"]));
    const scriptType = normalizeScriptTypeValue(firstTextFromSources(sources, ["scriptType", "type", "language"]))
      || inferScriptTypeFromPayload(content, firstTextFromSources(sources, ["fileName", "filename", "name", "scriptName", "title"]));
    return cleanObject({
      scriptType,
      datasourceId: firstTextFromSources(sources, ["datasourceId", "dataSourceId", "datasource.id"]),
      environmentId: firstTextFromSources(sources, ["environmentId", "runtimeEnvironmentId"]),
      content,
      arguments: firstRecordFromSources(sources, ["arguments", "args", "params"]),
      maxRows: readNumberFromSources(sources, "maxRows", 100, 1, 10000),
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
    ? `数据开发 ${resource || "-"} / ${action} 动作缺少接口必填字段：${missing.join(", ")}。请根据上下文补齐，不能推断时用 uiControls 询问用户。`
    : "";
}

async function callFeatureActionTool(params: Record<string, unknown>) {
  const path = normalizeRoutePath(params.path);
  const action = normalizeFeatureActionName(params.action);
  const resource = normalizeFeatureResource(params.resource);
  const id = firstText(params, ["id"]);
  const datasourceId = firstText(params, ["datasourceId", "id"]);
  const typeCode = firstText(params, ["typeCode", "datasourceType", "id"]);
  const payload = params.payload as any;
  switch (path) {
    case "/access-center":
      if (action === "apply") {
        return studioApi.access.apply(payload);
      }
      break;
    case "/metadata":
      if (action === "saveDraft") {
        return studioApi.metaSchemas.saveDraft(payload);
      }
      if (action === "publish") {
        return studioApi.metaSchemas.publish(id);
      }
      if (action === "syncTechnical") {
        return studioApi.metaSchemas.syncTechnical(typeCode);
      }
      if (action === "syncAllTechnical") {
        return studioApi.metaSchemas.syncAllTechnical();
      }
      if (action === "syncStandardRuntimeOptions") {
        return studioApi.metaSchemas.syncStandardRuntimeOptions();
      }
      break;
    case "/datasources":
      if (action === "save") {
        return studioApi.datasources.save(payload);
      }
      if (action === "test") {
        return studioApi.datasources.test(id);
      }
      if (action === "testCurrent") {
        return studioApi.datasources.testCurrent(payload);
      }
      if (action === "discover") {
        return studioApi.datasources.discover(id, cleanObject({
          keyword: firstText(params, ["keyword"]),
          pageNo: readNumberParam(params, "pageNo", 1, 1, 10000),
          pageSize: readNumberParam(params, "pageSize", DEFAULT_FEATURE_PAGE_SIZE, 1, MAX_FEATURE_PAGE_SIZE),
        }));
      }
      break;
    case "/models":
      if (action === "save") {
        return studioApi.models.save(payload);
      }
      if (action === "sync") {
        return studioApi.models.sync(datasourceId);
      }
      if (action === "syncSelected") {
        return studioApi.models.syncSelected(datasourceId, payload);
      }
      if (action === "rebuildIndex") {
        return studioApi.models.rebuildIndex(datasourceId || undefined);
      }
      break;
    case "/field-mapping-rules":
      if (action === "save") {
        return studioApi.fieldMappingRules.save(payload);
      }
      break;
    case "/collection-tasks":
      if (action === "preview") {
        return studioApi.collectionTasks.preview(payload);
      }
      if (action === "save") {
        return studioApi.collectionTasks.save(payload);
      }
      if (action === "publish") {
        return studioApi.collectionTasks.publish(id);
      }
      if (action === "trigger") {
        return studioApi.collectionTasks.trigger(id);
      }
      if (action === "schedule") {
        return studioApi.collectionTasks.saveSchedule(id, payload);
      }
      if (action === "resetIncrementalCursor") {
        return studioApi.collectionTasks.resetIncrementalCursor(id, firstText(params, ["sourceAlias"]) || undefined, cleanObject({
          incrColumn: firstText(params, ["incrColumn"]),
          incrModel: firstText(params, ["incrModel"]),
        }));
      }
      break;
    case "/data-development":
      return callDataDevelopmentActionTool(action, resource, id, payload);
    case "/workflows":
      if (action === "save") {
        return studioApi.workflows.save(payload);
      }
      if (action === "publish") {
        return studioApi.workflows.publish(id);
      }
      if (action === "trigger") {
        return studioApi.workflows.trigger(id);
      }
      if (action === "schedule") {
        return saveWorkflowSchedule(id, payload);
      }
      break;
    case "/data-services":
      return callDataServiceActionTool(action, resource, id, payload, params);
    case "/data-ingestion-services":
      return callDataIngestionServiceActionTool(action, resource, id, payload, params);
    case "/protocol-conversions":
      return callProtocolConversionActionTool(action, resource, id, payload, params);
    case "/quality-rules":
      if (action === "save") {
        return studioApi.qualityRules.save(payload);
      }
      if (action === "enable") {
        return studioApi.qualityRules.enable(id);
      }
      if (action === "disable") {
        return studioApi.qualityRules.disable(id);
      }
      if (action === "parse") {
        return studioApi.qualityRules.parse(payload);
      }
      if (action === "validate") {
        return studioApi.qualityRules.validate(payload);
      }
      break;
    case "/quality-tasks":
      if (action === "preview") {
        return studioApi.qualityTasks.preview(payload);
      }
      if (action === "validate") {
        return studioApi.qualityTasks.validate(payload);
      }
      if (action === "save") {
        return studioApi.qualityTasks.save(payload);
      }
      if (action === "publish") {
        return studioApi.qualityTasks.publish(id);
      }
      if (action === "trigger") {
        return studioApi.qualityTasks.trigger(id);
      }
      if (action === "schedule") {
        return studioApi.qualityTasks.saveSchedule(id, payload);
      }
      break;
    case "/quality-metrics":
      if (resource === "issues" && action === "assignIssue") {
        return studioApi.qualityMetrics.assignIssue(id, payload);
      }
      if (resource === "issues" && action === "updateIssueStatus") {
        return studioApi.qualityMetrics.updateIssueStatus(id, payload);
      }
      if (resource === "issues" && action === "updateIssueSeverity") {
        return studioApi.qualityMetrics.updateIssueSeverity(id, payload);
      }
      if (resource === "issues" && action === "addIssueComment") {
        return studioApi.qualityMetrics.addIssueComment(id, payload);
      }
      break;
    case "/script-environments":
      if (action === "save") {
        return studioApi.scriptEnvironments.saveOrUpdateCheck(payload);
      }
      if (action === "enable") {
        return studioApi.scriptEnvironments.enable(id);
      }
      if (action === "disable") {
        return studioApi.scriptEnvironments.disable(id);
      }
      if (action === "refresh") {
        return studioApi.scriptEnvironments.refresh(id);
      }
      break;
    case "/system":
      return callSystemActionTool(action, resource, id, payload);
    default:
      break;
  }
  throw new Error(`当前功能暂未注册动作接口：${path} / ${action}`);
}

async function saveWorkflowSchedule(id: string, schedule: Record<string, unknown> | undefined) {
  if (!schedule) {
    throw new Error("保存工作流调度前需要提供 schedule payload。");
  }
  const workflow = await studioApi.workflows.get(id);
  const payload: WorkflowSaveRequest = {
    definitionId: workflow.id,
    code: workflow.code,
    name: workflow.name,
    schedule: {
      enabled: readBooleanParam(schedule, "enabled"),
      cronExpression: firstText(schedule, ["cronExpression", "cron", "expression"]) || undefined,
      timezone: firstText(schedule, ["timezone", "timeZone", "zone"]) || undefined,
    },
    nodes: workflow.nodes ?? [],
    edges: workflow.edges ?? [],
  };
  return studioApi.workflows.save(payload);
}

function callDataDevelopmentActionTool(action: string, resource: string, id: string, payload: any) {
  if (resource === "directories" && action === "saveDirectory") {
    return studioApi.dataDevelopment.saveDirectory(payload);
  }
  if (resource === "directories" && action === "moveDirectory") {
    return studioApi.dataDevelopment.moveDirectory(id, payload);
  }
  if (resource === "scripts" && action === "saveScript") {
    return studioApi.dataDevelopment.saveScript(payload);
  }
  if (resource === "scripts" && action === "moveScript") {
    return studioApi.dataDevelopment.moveScript(id, payload);
  }
  if (resource === "sql" && action === "executeSql") {
    return studioApi.dataDevelopment.executeSql(payload);
  }
  if (resource === "scripts" && action === "executeScript") {
    return studioApi.dataDevelopment.executeScript(payload);
  }
  if (resource === "scripts" && action === "executeSavedScript") {
    return studioApi.dataDevelopment.executeSavedScript(id, payload);
  }
  throw new Error(`数据开发暂未注册动作接口：${resource} / ${action}`);
}

function callDataServiceActionTool(action: string, resource: string, id: string, payload: any, params: Record<string, unknown>) {
  if (action === "save") {
    return studioApi.dataServices.save(payload);
  }
  if (action === "publish") {
    return studioApi.dataServices.publish(id);
  }
  if (action === "offline") {
    return studioApi.dataServices.offline(id);
  }
  if (action === "resolveFields") {
    return studioApi.dataServices.resolveFields(payload);
  }
  if (resource === "webservice" && action === "debugWebService") {
    return studioApi.dataServices.debugWebService(id, payload);
  }
  if (action === "debug") {
    return studioApi.dataServices.debug(id, payload);
  }
  if (resource === "subscriptions") {
    return callSubscriptionActionTool(studioApi.dataServices, action, id, params);
  }
  throw new Error(`数据服务暂未注册动作接口：${resource || "-"} / ${action}`);
}

function callDataIngestionServiceActionTool(action: string, resource: string, id: string, payload: any, params: Record<string, unknown>) {
  if (action === "save") {
    return studioApi.dataIngestionServices.save(payload);
  }
  if (action === "publish") {
    return studioApi.dataIngestionServices.publish(id);
  }
  if (action === "offline") {
    return studioApi.dataIngestionServices.offline(id);
  }
  if (action === "resolveFields") {
    return studioApi.dataIngestionServices.resolveFields(payload);
  }
  if (resource === "webservice" && action === "debugWebService") {
    return studioApi.dataIngestionServices.debugWebService(id, payload);
  }
  if (action === "debug") {
    return studioApi.dataIngestionServices.debug(id, payload);
  }
  if (resource === "subscriptions") {
    return callSubscriptionActionTool(studioApi.dataIngestionServices, action, id, params);
  }
  throw new Error(`数据接入服务暂未注册动作接口：${resource || "-"} / ${action}`);
}

function callProtocolConversionActionTool(action: string, resource: string, id: string, payload: any, params: Record<string, unknown>) {
  if (action === "save") {
    return studioApi.protocolConversions.save(payload);
  }
  if (action === "publish") {
    return studioApi.protocolConversions.publish(id);
  }
  if (action === "offline") {
    return studioApi.protocolConversions.offline(id);
  }
  if (action === "debug") {
    return studioApi.protocolConversions.debug(id, payload);
  }
  if (resource === "subscriptions") {
    return callSubscriptionActionTool(studioApi.protocolConversions, action, id, params);
  }
  throw new Error(`协议转换暂未注册动作接口：${resource || "-"} / ${action}`);
}

function callSubscriptionActionTool(
  api: {
    createSubscription: (id: string, subscriptionName: string) => Promise<unknown>;
    enableSubscription: (id: string, subscriptionId: string) => Promise<unknown>;
    disableSubscription: (id: string, subscriptionId: string) => Promise<unknown>;
    rotateSubscription: (id: string, subscriptionId: string) => Promise<unknown>;
  },
  action: string,
  id: string,
  params: Record<string, unknown>,
) {
  const subscriptionId = firstText(params, ["subscriptionId"]);
  const subscriptionName = firstText(params, ["subscriptionName", "name"]);
  if (action === "createSubscription") {
    return api.createSubscription(id, subscriptionName);
  }
  if (action === "enableSubscription") {
    return api.enableSubscription(id, subscriptionId);
  }
  if (action === "disableSubscription") {
    return api.disableSubscription(id, subscriptionId);
  }
  if (action === "rotateSubscription") {
    return api.rotateSubscription(id, subscriptionId);
  }
  throw new Error(`订阅暂未注册动作接口：${action}`);
}

function callSystemActionTool(action: string, resource: string, id: string, payload: any) {
  if (action === "save") {
    switch (resource) {
      case "tenants":
        return studioApi.system.tenants.save(payload);
      case "projects":
        return studioApi.system.projects.save(payload);
      case "tenantMembers":
        return studioApi.system.tenantMembers.save(payload);
      case "projectMembers":
        return studioApi.system.projectMembers.save(payload);
      case "projectMemberRequests":
        return studioApi.system.projectMemberRequests.save(payload);
      case "projectWorkers":
        return studioApi.system.projectWorkers.save(payload);
      case "resourceShares":
        return studioApi.system.resourceShares.save(payload);
      case "users":
        return studioApi.users.save(payload);
      case "roles":
        return studioApi.roles.save(payload);
      case "permissions":
        return studioApi.permissions.save(payload);
      default:
        break;
    }
  }
  if (resource === "userRegistrationRequests" && action === "approve") {
    return studioApi.system.userRegistrationRequests.approve(id, payload);
  }
  if (resource === "userRegistrationRequests" && action === "reject") {
    return studioApi.system.userRegistrationRequests.reject(id, payload);
  }
  throw new Error(`系统管理暂未注册动作接口：${resource || "-"} / ${action}`);
}

async function callFeatureListTool(path: string, params: Record<string, unknown>) {
  const normalizedPath = normalizeRoutePath(path);
  const pageParams = readPageParams(params);
  const keyword = firstText(params, ["keyword", "name", "search"]);
  const status = firstText(params, ["status"]);
  const view = normalizeFeatureView(params.view);
  switch (normalizedPath) {
    case "/dashboard":
      return studioApi.dashboard.overview();
    case "/access-center":
      return studioApi.access.overview();
    case "/catalog":
      if (view === "datasourceTypes") {
        return studioApi.catalog.datasourceTypes();
      }
      return studioApi.catalog.capabilities();
    case "/metadata":
      return studioApi.metaSchemas.list({
        includeFields: readBooleanParam(params, "includeFields"),
      });
    case "/datasources":
      return studioApi.datasources.listPage(pageParams);
    case "/models": {
      const datasourceId = firstText(params, ["datasourceId"]);
      const modelKeyword = firstText(params, ["keyword", "name", "search", "modelName", "tableName", "physicalLocator"]);
      if (modelKeyword) {
        if (datasourceId) {
          return studioApi.models.listDatasourceOptions(datasourceId, {
            ...pageParams,
            keyword: modelKeyword,
          });
        }
        return studioApi.models.listOptions({
          ...pageParams,
          keyword: modelKeyword,
        });
      }
      if (datasourceId) {
        return studioApi.models.listSummaryByDatasourcePage(datasourceId, pageParams);
      }
      return studioApi.models.listSummaryPage({
        ...pageParams,
        datasourceType: firstText(params, ["datasourceType", "typeCode"]) || undefined,
        sortField: firstText(params, ["sortField"]) || undefined,
        sortOrder: firstText(params, ["sortOrder"]) || undefined,
      });
    }
    case "/statistics":
      return studioApi.statistics.options(cleanObject<DataModelStatisticsOptionsRequest>({
        datasourceId: firstText(params, ["datasourceId"]),
        datasourceType: firstText(params, ["datasourceType", "typeCode"]),
        targetScope: firstText(params, ["targetScope", "scope"]),
      }));
    case "/field-mapping-rules":
      return studioApi.fieldMappingRules.list(cleanObject({
        ...pageParams,
        keyword,
        mappingType: firstText(params, ["mappingType"]),
        enabled: readOptionalBooleanParam(params, "enabled"),
      }));
    case "/collection-tasks":
      return studioApi.collectionTasks.listPage(cleanObject({
        ...pageParams,
        name: firstText(params, ["name", "keyword"]),
        targetDatasource: firstText(params, ["targetDatasource", "targetDatasourceName"]),
        targetModel: firstText(params, ["targetModel", "targetModelName"]),
      }));
    case "/collection-task-runs":
      return studioApi.runs.listPage(cleanObject({
        ...pageParams,
        collectionTaskOnly: true,
        collectionTaskId: firstText(params, ["collectionTaskId", "taskId"]),
        status,
        startTime: firstText(params, ["startTime"]),
        endTime: firstText(params, ["endTime"]),
      }));
    case "/run-metrics":
      if (view === "options") {
        return studioApi.runMetrics.options();
      }
      return studioApi.runMetrics.query(buildTimeRangeQuery(params));
    case "/data-development":
      if (view === "scripts") {
        return studioApi.dataDevelopment.listScripts(resolveScriptTypeParam(params));
      }
      if (view === "datasources") {
        return studioApi.dataDevelopment.listSqlDatasourceOptions();
      }
      if (view === "datasourceTypes") {
        return studioApi.dataDevelopment.listSqlDatasourceTypes();
      }
      return studioApi.dataDevelopment.tree();
    case "/workflows":
      return studioApi.workflows.listPage(pageParams);
    case "/runs":
      return studioApi.runs.listPage(cleanObject({
        ...pageParams,
        collectionTaskId: firstText(params, ["collectionTaskId"]),
        qualityTaskId: firstText(params, ["qualityTaskId"]),
        workflowDefinitionId: firstText(params, ["workflowDefinitionId", "workflowId"]),
        status,
        startTime: firstText(params, ["startTime"]),
        endTime: firstText(params, ["endTime"]),
      }));
    case "/data-services":
      return studioApi.dataServices.list(cleanObject({
        ...pageParams,
        keyword,
        status,
        serviceType: firstText(params, ["serviceType"]),
      }));
    case "/data-ingestion-services":
      return studioApi.dataIngestionServices.list(cleanObject({
        ...pageParams,
        keyword,
        status,
        targetType: firstText(params, ["targetType"]),
      }));
    case "/protocol-conversions":
      return studioApi.protocolConversions.list(cleanObject({
        ...pageParams,
        keyword,
        status,
      }));
    case "/data-ingestion-metrics":
      return callDataIngestionMetricListTool(view, params);
    case "/data-service-metrics":
      return callDataServiceMetricListTool(view, params);
    case "/quality-rules":
      return studioApi.qualityRules.list(cleanObject({
        ...pageParams,
        keyword,
        ruleDimension: firstText(params, ["ruleDimension"]),
        scopeType: firstText(params, ["scopeType"]),
        enabled: readOptionalBooleanParam(params, "enabled"),
      }));
    case "/quality-tasks":
      return studioApi.qualityTasks.listPage(cleanObject({
        ...pageParams,
        keyword,
        status,
        ruleDimension: firstText(params, ["ruleDimension"]),
        granularity: firstText(params, ["granularity"]),
      }));
    case "/quality-task-runs":
      return studioApi.runs.listPage(cleanObject({
        ...pageParams,
        qualityTaskOnly: true,
        qualityTaskId: firstText(params, ["qualityTaskId", "taskId"]),
        status,
        startTime: firstText(params, ["startTime"]),
        endTime: firstText(params, ["endTime"]),
      }));
    case "/quality-metrics":
      return callQualityMetricListTool(view, params);
    case "/system":
      return callSystemListTool(view || firstText(params, ["resource", "resourceType"]), params);
    case "/script-environments":
      return studioApi.scriptEnvironments.queryPage(cleanObject({
        pageNum: pageParams.pageNo,
        pageSize: pageParams.pageSize,
        keyword,
        enabled: readOptionalBooleanParam(params, "enabled"),
      }));
    case "/ops-center":
      return callOpsCenterListTool(view, params);
    default:
      throw new Error(`当前功能暂未开放自动读取工具：${normalizedPath}`);
  }
}

async function callFeatureGetTool(path: string, params: Record<string, unknown>) {
  const normalizedPath = normalizeRoutePath(path);
  const id = firstText(params, ["id", "recordId", "entityId", "taskId", "modelId", "serviceId", "runId", "scriptId"]);
  const view = normalizeFeatureView(params.view);
  switch (normalizedPath) {
    case "/metadata":
      return studioApi.metaSchemas.get(id);
    case "/datasources":
      if (view === "history" || view === "connectionHistory") {
        return studioApi.datasources.connectionHistory(id, {
          days: readNumberParam(params, "days", 7, 1, 90),
          limit: readNumberParam(params, "limit", 20, 1, 200),
        });
      }
      return studioApi.datasources.get(id);
    case "/models":
      if (view === "lineage") {
        return studioApi.models.lineage(id, firstText(params, ["lineageLevel", "level"]) || "ONE");
      }
      if (view === "preview" || readBooleanParam(params, "preview")) {
        const limit = readNumberParam(params, "limit", 20, 1, 100);
        const [detail, previewRows] = await Promise.all([
          studioApi.models.get(id),
          studioApi.models.preview(id, limit),
        ]);
        return {
          ...detail,
          previewRows,
          sampleRows: previewRows,
          previewRowCount: previewRows.length,
          previewLimit: limit,
        };
      }
      return studioApi.models.get(id);
    case "/field-mapping-rules":
      return studioApi.fieldMappingRules.get(id);
    case "/collection-tasks":
      return studioApi.collectionTasks.get(id);
    case "/collection-task-runs":
    case "/runs":
    case "/quality-task-runs":
      if (view === "log" || readBooleanParam(params, "log")) {
        return studioApi.runs.getLog(id);
      }
      return studioApi.runs.get(id);
    case "/data-development":
      return studioApi.dataDevelopment.getScript(id);
    case "/workflows":
      return studioApi.workflows.get(id);
    case "/data-services":
      if (view === "subscriptions") {
        return studioApi.dataServices.listSubscriptions(id);
      }
      if (view === "webservicePreview" || view === "preview") {
        return studioApi.dataServices.previewWebService(id);
      }
      return studioApi.dataServices.get(id);
    case "/data-ingestion-services":
      if (view === "subscriptions") {
        return studioApi.dataIngestionServices.listSubscriptions(id);
      }
      if (view === "webservicePreview" || view === "preview") {
        return studioApi.dataIngestionServices.previewWebService(id);
      }
      return studioApi.dataIngestionServices.get(id);
    case "/protocol-conversions":
      if (view === "subscriptions") {
        return studioApi.protocolConversions.listSubscriptions(id);
      }
      return studioApi.protocolConversions.get(id);
    case "/quality-rules":
      return studioApi.qualityRules.get(id);
    case "/quality-tasks":
      return studioApi.qualityTasks.get(id);
    case "/quality-metrics":
      if (view === "asset" || firstText(params, ["resource"]) === "asset") {
        return studioApi.qualityMetrics.getAsset(id, cleanObject({
          startTime: firstText(params, ["startTime"]),
          endTime: firstText(params, ["endTime"]),
        }));
      }
      return studioApi.qualityMetrics.getIssue(id);
    case "/script-environments":
      return studioApi.scriptEnvironments.get(id);
    default:
      throw new Error(`当前功能暂未开放自动读取详情工具：${normalizedPath}`);
  }
}

function callDataServiceMetricListTool(view: string, params: Record<string, unknown>) {
  if (view === "apiStats" || view === "api") {
    return studioApi.dataServiceMetrics.queryApiStats(buildMetricPageQuery(params));
  }
  if (view === "accessLogs" || view === "logs") {
    return studioApi.dataServiceMetrics.queryAccessLogs(buildMetricPageQuery(params));
  }
  if (view === "options") {
    return studioApi.dataServiceMetrics.options();
  }
  return studioApi.dataServiceMetrics.queryDashboard(buildMetricQuery(params));
}

function callDataIngestionMetricListTool(view: string, params: Record<string, unknown>) {
  if (view === "apiStats" || view === "api") {
    return studioApi.dataIngestionMetrics.queryApiStats(buildMetricPageQuery(params));
  }
  if (view === "accessLogs" || view === "logs") {
    return studioApi.dataIngestionMetrics.queryAccessLogs(buildMetricPageQuery(params));
  }
  if (view === "options") {
    return studioApi.dataIngestionMetrics.options();
  }
  return studioApi.dataIngestionMetrics.queryDashboard(buildMetricQuery(params));
}

function callQualityMetricListTool(view: string, params: Record<string, unknown>) {
  if (view === "assets") {
    return studioApi.qualityMetrics.queryAssets(buildMetricPageQuery(params));
  }
  if (view === "issues") {
    return studioApi.qualityMetrics.queryIssuesPage(buildMetricPageQuery(params));
  }
  if (view === "options") {
    return studioApi.qualityMetrics.options();
  }
  return studioApi.qualityMetrics.queryDashboard(buildMetricQuery(params));
}

function callOpsCenterListTool(view: string, params: Record<string, unknown>) {
  if (view === "options") {
    return studioApi.opsCenter.options();
  }
  if (view === "runs") {
    return studioApi.opsCenter.queryRuns(buildOpsCenterQuery(params));
  }
  if (view === "queue") {
    return studioApi.opsCenter.queryQueue(buildOpsCenterQuery(params));
  }
  if (view === "workers") {
    return studioApi.opsCenter.queryWorkers(buildOpsCenterQuery(params));
  }
  if (view === "serviceEvents") {
    return studioApi.opsCenter.queryServiceEvents(buildOpsCenterQuery(params));
  }
  if (view === "ingestionEvents") {
    return studioApi.opsCenter.queryIngestionEvents(buildOpsCenterQuery(params));
  }
  if (view === "logEvents") {
    return studioApi.opsCenter.queryLogEvents(buildOpsCenterQuery(params));
  }
  return studioApi.opsCenter.queryOverview(buildOpsCenterQuery(params));
}

function callSystemListTool(resource: string, params: Record<string, unknown>) {
  const pageParams = readPageParams(params);
  const normalizedResource = normalizeFeatureView(resource || "projects");
  switch (normalizedResource) {
    case "tenants":
      return studioApi.system.tenants.listPage(pageParams);
    case "tenantMembers":
      return studioApi.system.tenantMembers.listPage(pageParams);
    case "projectMembers":
      return studioApi.system.projectMembers.listPage(cleanObject({
        ...pageParams,
        projectId: firstText(params, ["projectId"]),
      }));
    case "projectMemberRequests":
      return studioApi.system.projectMemberRequests.listPage(cleanObject({
        ...pageParams,
        projectId: firstText(params, ["projectId"]),
      }));
    case "projectWorkers":
      return studioApi.system.projectWorkers.listPage(cleanObject({
        ...pageParams,
        projectId: firstText(params, ["projectId"]),
      }));
    case "resourceShares":
      return studioApi.system.resourceShares.listPage(cleanObject({
        ...pageParams,
        projectId: firstText(params, ["projectId"]),
        resourceType: firstText(params, ["resourceType"]),
      }));
    case "userRegistrationRequests":
      return studioApi.system.userRegistrationRequests.listPage(pageParams);
    case "users":
      return studioApi.users.listPage(pageParams);
    case "roles":
      return studioApi.roles.list();
    case "permissions":
      return studioApi.permissions.list();
    case "projects":
    default:
      return studioApi.system.projects.listPage(pageParams);
  }
}

function normalizeFeatureToolParams(params: Record<string, unknown>, path: string) {
  return {
    ...params,
    path: normalizeRoutePath(path),
    pageNo: readNumberParam(params, "pageNo", DEFAULT_FEATURE_PAGE_SIZE / DEFAULT_FEATURE_PAGE_SIZE, 1, 10000),
    pageSize: readNumberParam(params, "pageSize", DEFAULT_FEATURE_PAGE_SIZE, 1, MAX_FEATURE_PAGE_SIZE),
  };
}

function resolveFeatureActionDefinition(path: string, action: unknown, resource?: string) {
  const normalizedPath = normalizeRoutePath(path);
  const normalizedResource = normalizeFeatureResource(resource);
  const actionCandidates = ASSISTANT_FEATURE_ACTION_DEFINITIONS
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
  const actions = ASSISTANT_FEATURE_ACTION_DEFINITIONS
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
    return isPlainRecord(params.payload);
  }
  const aliases: Record<string, string[]> = {
    id: ["id"],
    datasourceId: ["datasourceId", "id"],
    typeCode: ["typeCode", "datasourceType", "id"],
    subscriptionId: ["subscriptionId"],
    subscriptionName: ["subscriptionName", "name", "payload.subscriptionName"],
  };
  if (aliases[key]) {
    return Boolean(firstText(params, aliases[key]));
  }
  const value = readParamValue(params, key);
  if (isPlainRecord(value)) {
    return true;
  }
  return value != null && String(value).trim() !== "";
}

function buildMetricQuery(params: Record<string, unknown>) {
  return cleanObject({
    startTime: firstText(params, ["startTime"]),
    endTime: firstText(params, ["endTime"]),
    serviceId: firstText(params, ["serviceId"]),
    datasourceId: firstText(params, ["datasourceId"]),
    modelId: firstText(params, ["modelId"]),
    status: firstText(params, ["status"]),
    keyword: firstText(params, ["keyword"]),
  });
}

function buildMetricPageQuery(params: Record<string, unknown>) {
  return cleanObject({
    ...buildMetricQuery(params),
    ...readPageParams(params),
  });
}

function buildTimeRangeQuery(params: Record<string, unknown>) {
  return cleanObject({
    startTime: firstText(params, ["startTime"]),
    endTime: firstText(params, ["endTime"]),
    executionType: firstText(params, ["executionType"]),
    status: firstText(params, ["status"]),
  });
}

function buildOpsCenterQuery(params: Record<string, unknown>) {
  return cleanObject({
    ...readPageParams(params),
    startTime: firstText(params, ["startTime"]),
    endTime: firstText(params, ["endTime"]),
    executionType: firstText(params, ["executionType"]),
    status: firstText(params, ["status"]),
    workerGroupCode: firstText(params, ["workerGroupCode"]),
  });
}

function readPageParams(params: Record<string, unknown>) {
  return {
    pageNo: readNumberParam(params, "pageNo", 1, 1, 10000),
    pageSize: readNumberParam(params, "pageSize", DEFAULT_FEATURE_PAGE_SIZE, 1, MAX_FEATURE_PAGE_SIZE),
  };
}

function readNumberParam(params: Record<string, unknown>, key: string, fallback: number, min: number, max: number) {
  const parsed = Number(params[key]);
  if (!Number.isFinite(parsed)) {
    return fallback;
  }
  return Math.min(max, Math.max(min, Math.floor(parsed)));
}

function readBooleanParam(params: Record<string, unknown>, key: string) {
  return params[key] === true || String(params[key] ?? "").toLowerCase() === "true";
}

function readOptionalBooleanParam(params: Record<string, unknown>, key: string) {
  if (params[key] == null || params[key] === "") {
    return undefined;
  }
  return readBooleanParam(params, key);
}

function resolveScriptTypeParam(params: Record<string, unknown>): ScriptType | undefined {
  return normalizeScriptTypeValue(firstText(params, ["scriptType", "type"]));
}

function normalizeScriptTypeValue(value: string): ScriptType | undefined {
  const normalized = String(value ?? "").trim().toUpperCase();
  if (normalized === "PY") {
    return "PYTHON";
  }
  if (normalized === "SQL" || normalized === "JAVA" || normalized === "PYTHON") {
    return normalized;
  }
  return undefined;
}

function firstTextFromSources(sources: Array<Record<string, unknown>>, keys: string[]) {
  for (const source of sources) {
    const value = firstText(source, keys);
    if (value) {
      return value;
    }
  }
  return "";
}

function firstRecordFromSources(sources: Array<Record<string, unknown>>, keys: string[]) {
  for (const source of sources) {
    const value = firstRecordParam(source, keys);
    if (value) {
      return value;
    }
  }
  return undefined;
}

function readNumberFromSources(
  sources: Array<Record<string, unknown>>,
  key: string,
  fallback: number,
  min: number,
  max: number,
) {
  for (const source of sources) {
    const value = readParamValue(source, key);
    if (value == null || value === "") {
      continue;
    }
    const parsed = Number(value);
    if (Number.isFinite(parsed)) {
      return Math.min(max, Math.max(min, Math.floor(parsed)));
    }
  }
  return fallback;
}

function normalizeScriptContent(value: string) {
  const text = String(value ?? "").trim();
  const fenced = /^```[a-zA-Z0-9_-]*\s*([\s\S]*?)\s*```$/.exec(text);
  return fenced?.[1]?.trim() || text;
}

function inferScriptTypeFromPayload(content: string, fileName: string): ScriptType | undefined {
  const name = String(fileName ?? "").trim().toLowerCase();
  if (name.endsWith(".sql")) {
    return "SQL";
  }
  if (name.endsWith(".java")) {
    return "JAVA";
  }
  if (name.endsWith(".py") || name.endsWith(".python")) {
    return "PYTHON";
  }
  const text = String(content ?? "").trim();
  if (/^(select|with|insert|update|delete|merge|create|alter|drop|truncate|show|desc|describe|explain)\b/i.test(text)) {
    return "SQL";
  }
  if (/\b(public\s+class|class\s+\w+|import\s+java\.|System\.out\.)\b/.test(text)) {
    return "JAVA";
  }
  if (/^(from\s+\w+\s+import|import\s+\w+|def\s+\w+\(|print\(|#\!.*python)/i.test(text)) {
    return "PYTHON";
  }
  return undefined;
}

function normalizeScriptFileName(value: string, scriptType: ScriptType | undefined) {
  const name = String(value ?? "").trim();
  if (!name) {
    return "";
  }
  if (/\.[a-z0-9]+$/i.test(name) || !scriptType) {
    return name;
  }
  const extensionMap: Record<ScriptType, string> = {
    SQL: ".sql",
    JAVA: ".java",
    PYTHON: ".py",
  };
  return `${name}${extensionMap[scriptType]}`;
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

function applyToolData(interfaceCode: string, params: Record<string, unknown>, data: unknown) {
  if (interfaceCode === "catalog.capabilities") {
    toolCache.capabilityMatrix = data as CapabilityMatrix;
  }
  if (interfaceCode === "datasources.options") {
    toolCache.datasources = Array.isArray(data) ? data as DataSourceOptionView[] : [];
  }
  if (interfaceCode === "models.datasourceOptions") {
    const datasourceId = String(params.datasourceId ?? "");
    const page = data as { items?: DataModelDatasourceOptionView[] };
    if (datasourceId) {
      toolCache.modelOptions[datasourceId] = page.items ?? [];
    }
  }
  if (interfaceCode === "models.get") {
    const detail = data as DataModelDefinition;
    if (detail?.id != null) {
      toolCache.modelDetails[String(detail.id)] = detail;
    }
  }
  if (interfaceCode === "catalog.runtimeOptionSchema") {
    const key = `${String(params.role ?? "")}:${String(params.datasourceType ?? "")}:${String(params.protocolMode ?? "")}`;
    toolCache.runtimeSchemas[key] = data as PluginRuntimeOptionSchemaView;
  }
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
  prompt.value = buildControlAnswerMessage(control, value, option);
  await nextTick();
  await submitPrompt();
}

async function handleAssistantRecoveryAction(value: string, option?: AssistantChatControlOption) {
  const label = option?.label || (value === "retry" ? "重新生成" : "继续处理");
  messages.value.push(createAssistantMessage("user", label));
  await nextTick(scrollThreadToBottom);
  const instruction = value === "retry"
    ? "上一轮助手回复没有完整返回。请根据已有对话、currentContext 和 toolResults 重新生成上一轮回复；不要重复已经成功完成的接口读取，除非确实缺少必要上下文。"
    : "上一轮助手回复没有完整返回。请根据已有对话、currentContext 和 toolResults 继续处理后续步骤；不要重复已经成功完成的接口读取，必要时输出下一步工具调用或 uiControls。";
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
    mode,
    accessibleFeatures: accessibleFeatures.value,
    accessibleCapabilities: accessibleCapabilities.value,
    frontendTools: availableFrontendTools.value,
    frontendActionRegistry: availableFeatureActions.value,
  };
}

function collectCurrentInputs() {
  return {
    cachedDatasources: toolCache.datasources.length,
    cachedDatasourceModelGroups: Object.keys(toolCache.modelOptions).length,
    cachedModelDetails: Object.keys(toolCache.modelDetails).length,
    cachedRuntimeSchemas: Object.keys(toolCache.runtimeSchemas).length,
  };
}

async function maybePersistLocalLearning(message: string, assistantContent: string) {
  if (!message.trim() || assistantContent.trim().length < 80) {
    return;
  }
  pushProcessEvent({
    stage: "skills.learn",
    title: "沉淀助手技能",
    detail: "正在判断本次问答是否值得写入技能记忆。",
    status: "running",
  });
  try {
    const response = await studioApi.assistant.learn({
      message,
      messages: conversationForRequest(),
      context: buildRuntimeContext("assistant_learning"),
      collectedInputs: collectCurrentInputs(),
      toolResults: toolResults.value.map(summarizeToolResultForPlanner),
      assistantContent,
    });
    markProcessDone(
      "skills.learn",
      response.accepted ? "技能记忆已更新" : "技能记忆未更新",
      response.accepted ? "本次结论已保存，后续问答会优先参考。" : "本次内容未达到沉淀条件。",
      response.accepted ? "done" : "warning",
    );
  } catch {
    markProcessDone("skills.learn", "技能记忆暂不可用", "学习接口失败，本次对话不受影响。", "warning");
  }
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
    const feature = findAccessibleFeature(String(params.path ?? ""));
    return `正在打开 ${feature?.label ?? String(params.path ?? "")}。`;
  }
  if (interfaceCode === "studio.feature.list") {
    const feature = findAccessibleFeature(String(params.path ?? ""));
    return `正在读取 ${feature?.label ?? String(params.path ?? "")} 的数据。`;
  }
  if (interfaceCode === "studio.feature.get") {
    const feature = findAccessibleFeature(String(params.path ?? ""));
    return `正在读取 ${feature?.label ?? String(params.path ?? "")} 的详情。`;
  }
  if (interfaceCode === "studio.feature.action") {
    return `正在执行 ${describeToolConfirmation(interfaceCode, params)}`;
  }
  if (interfaceCode === "catalog.capabilities") {
    return "正在读取数据源读写能力矩阵。";
  }
  if (interfaceCode === "datasources.options") {
    return "正在查询当前项目可用数据源候选。";
  }
  if (interfaceCode === "models.datasourceOptions") {
    return `正在查询数据源 ${String(params.datasourceId ?? "")} 下的模型/表候选。`;
  }
  if (interfaceCode === "models.get") {
    return `正在读取模型 ${String(params.modelId ?? "")} 字段元数据。`;
  }
  if (interfaceCode === "catalog.runtimeOptionSchema") {
    return `正在读取 ${String(params.role ?? "")} 运行参数元模型。`;
  }
  if (interfaceCode === "collectionTasks.preview") {
    return "正在预览采集任务配置。";
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
  if (interfaceCode === "collectionTasks.preview") {
    return "预览接口已返回。";
  }
  return "接口已返回结果。";
}

function describeToolChatResult(interfaceCode: string, params: Record<string, unknown>, data: unknown) {
  if (interfaceCode === "studio.navigation.open" && data && typeof data === "object") {
    const record = data as Record<string, unknown>;
    return `已为你打开 ${String(record.label ?? params.path ?? "目标功能")}。`;
  }
  if (interfaceCode === "studio.feature.list") {
    const feature = findAccessibleFeature(String(params.path ?? ""));
    return `我已读取 ${feature?.label ?? params.path ?? "目标功能"} 的数据，会基于返回结果继续判断。`;
  }
  if (interfaceCode === "studio.feature.get") {
    const feature = findAccessibleFeature(String(params.path ?? ""));
    return `我已读取 ${feature?.label ?? params.path ?? "目标功能"} 的详情，会基于返回结果继续判断。`;
  }
  if (interfaceCode === "studio.feature.action") {
    return `我已完成 ${describeToolConfirmation(interfaceCode, params)} 会基于接口返回结果继续判断。`;
  }
  if (interfaceCode === "datasources.options" && Array.isArray(data) && data.length === 0) {
    return "我查询了当前项目的数据源候选，但暂时没有可选数据源。需要先在数据源中心注册源端和目标端数据源。";
  }
  if (interfaceCode === "models.datasourceOptions" && data && typeof data === "object") {
    const page = data as { items?: unknown[]; total?: number };
    if (!(page.items ?? []).length) {
      return `我查询了数据源 ${String(params.datasourceId ?? "")} 下的模型/表候选，但没有返回可选项。`;
    }
  }
  if (interfaceCode === "models.get" && data && typeof data === "object") {
    const record = data as Record<string, unknown>;
    const fields = resolveFieldSummaryRows(record);
    return `模型 ${String(record.name ?? params.modelId ?? "")} 的字段元数据已读取，字段数 ${fields.length}。`;
  }
  if (interfaceCode === "catalog.capabilities") {
    return "我已读取 Studio 当前支持的数据源读写能力矩阵。";
  }
  if (interfaceCode === "catalog.runtimeOptionSchema") {
    return "我已读取运行参数元模型，可以继续用于拼接预览请求。";
  }
  if (interfaceCode === "collectionTasks.preview") {
    return "采集任务配置预览已生成；如果要保存草稿，需要你明确确认后再开放写接口。";
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

function resolveAccessibleFeatures(): AccessibleAssistantFeature[] {
  const menus = resolveStudioMenus(t, {
    systemRoleCodes: authStore.systemRoleCodes,
    effectiveRoleCodes: authStore.effectiveRoleCodes,
    hasProject: Boolean(authStore.currentProjectId),
  });
  const features: AccessibleAssistantFeature[] = [];
  for (const group of menus) {
    for (const child of group.children ?? []) {
      if (!child.path) {
        continue;
      }
      features.push({
        group: group.label,
        path: child.path,
        label: child.label,
        caption: child.caption,
      });
    }
  }
  return features;
}

function resolveAccessibleCapabilities(): AccessibleAssistantCapability[] {
  return accessibleFeatures.value.map((feature) => {
    const path = normalizeRoutePath(feature.path);
    const spec = ASSISTANT_FEATURE_TOOL_SPECS[path];
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
  return ASSISTANT_FEATURE_ACTION_DEFINITIONS
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
  const pathSet = new Set(accessibleFeatures.value.map((feature) => normalizeRoutePath(feature.path)));
  return ASSISTANT_FEATURE_ACTION_DEFINITIONS
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
  const pathSet = new Set(accessibleFeatures.value.map((feature) => normalizeRoutePath(feature.path)));
  return ASSISTANT_FRONTEND_TOOL_DEFINITIONS.filter((tool) => {
    if (!tool.featurePaths?.length) {
      return accessibleCapabilities.value.length > 0 || tool.interfaceCode === "studio.navigation.open";
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

function findAccessibleFeature(path: string) {
  const normalized = normalizeRoutePath(path);
  return accessibleFeatures.value.find((feature) => normalizeRoutePath(feature.path) === normalized);
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

function resolveErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
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
