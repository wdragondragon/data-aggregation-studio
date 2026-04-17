<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ workflowId ? t("web.workflows.editorTitle") : t("web.workflows.createTitle") }}</h3>
        <p>{{ t("web.workflows.editorDescription") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button @click="router.push('/workflows')">{{ t("common.backToList") }}</el-button>
        <el-button plain @click="loadReferenceData">{{ t("common.refresh") }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveWorkflow">{{ t("common.saveDraft") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.workflows.basicsTitle')" :description="t('web.workflows.basicsDescription')">
      <div class="studio-form-grid">
        <el-form-item :label="t('web.workflows.workflowCode')">
          <el-input v-model="form.code" :placeholder="t('web.workflows.placeholderWorkflowCode')" />
        </el-form-item>
        <el-form-item :label="t('web.workflows.workflowName')">
          <el-input v-model="form.name" :placeholder="t('web.workflows.placeholderWorkflowName')" />
        </el-form-item>
        <el-form-item :label="t('web.workflows.cronExpression')" class="cron-form-item">
          <CronExpressionPicker
            v-model="form.schedule.cronExpression"
            :label="t('web.workflows.cronExpression')"
          />
        </el-form-item>
        <el-form-item :label="t('web.workflows.timezone')">
          <el-input v-model="form.schedule.timezone" placeholder="Asia/Shanghai" />
        </el-form-item>
        <el-form-item :label="t('web.workflows.scheduleEnabled')">
          <el-switch v-model="form.schedule.enabled" inline-prompt :active-text="t('common.on')" :inactive-text="t('common.off')" />
        </el-form-item>
      </div>
    </SectionCard>

    <SectionCard :title="t('web.workflows.canvasTitle')" :description="t('web.workflows.canvasDescription')">
      <WorkflowCanvas
        :nodes="form.nodes"
        :edges="form.edges"
        :palette-types="['COLLECTION_TASK', 'QUALITY_TASK', 'DATA_SCRIPT', 'HTTP', 'SHELL']"
        @update:nodes="form.nodes = $event"
        @update:edges="form.edges = $event"
        @select-node="selectedNodeCode = $event"
      />
    </SectionCard>

    <div class="studio-grid columns-2">
      <SectionCard :title="t('web.workflows.selectedNodeTitle')" :description="t('web.workflows.selectedNodeDescription')">
        <template v-if="selectedNode">
          <div class="soft-panel node-header">
            <div>
              <strong>{{ selectedNode.nodeName }}</strong>
              <p>{{ formatNodeType(t, selectedNode.nodeType) }}</p>
            </div>
            <el-button type="danger" plain @click="removeSelectedNode">{{ t("web.workflows.removeNode") }}</el-button>
          </div>

          <el-form-item :label="t('web.workflows.nodeName')">
            <el-input :model-value="selectedNode.nodeName" @update:model-value="updateSelectedNode('nodeName', $event)" />
          </el-form-item>

          <template v-if="selectedNode.nodeType === 'COLLECTION_TASK'">
            <el-form-item :label="t('web.workflows.collectionTaskBinding')">
              <el-button type="primary" plain :loading="collectionTasksLoading" @click="openCollectionTaskDialog">
                {{ selectedBoundTask ? t("web.workflows.reselectCollectionTask") : t("web.workflows.selectCollectionTask") }}
              </el-button>
            </el-form-item>
            <div class="soft-panel" v-if="selectedBoundTask">
              <strong>{{ selectedBoundTask.name }}</strong>
              <p>{{ formatCollectionTaskType(t, selectedBoundTask.taskType) }} · {{ selectedBoundTask.sourceCount }} {{ t("web.collectionTasks.sourceCountUnit") }}</p>
            </div>
          </template>

          <template v-else-if="selectedNode.nodeType === 'QUALITY_TASK'">
            <el-form-item :label="t('web.workflows.qualityTaskBinding')">
              <el-button type="primary" plain :loading="qualityTasksLoading" @click="openQualityTaskDialog">
                {{ selectedBoundQualityTask ? t("web.workflows.reselectQualityTask") : t("web.workflows.selectQualityTask") }}
              </el-button>
            </el-form-item>
            <div class="soft-panel" v-if="selectedBoundQualityTask">
              <strong>{{ selectedBoundQualityTask.taskName }}</strong>
              <p>
                {{ selectedBoundQualityTask.ruleName || t("common.none") }}
                · {{ formatQualityDimension(t, selectedBoundQualityTask.ruleDimension) }}
                · {{ formatQualityGranularity(t, selectedBoundQualityTask.granularity) }}
              </p>
              <p>
                {{ selectedBoundQualityTask.datasourceName || t("common.none") }}
                · {{ selectedBoundQualityTask.modelName || t("common.none") }}
                · {{ selectedBoundQualityTask.columnName || (selectedBoundQualityTask.granularity === "COLUMN" ? t("common.none") : t("web.workflows.qualityTaskWholeTable")) }}
              </p>
            </div>
          </template>

          <template v-else-if="selectedNode.nodeType === 'DATA_SCRIPT'">
            <el-form-item :label="t('web.workflows.dataScriptBinding')">
              <el-button type="primary" plain :loading="scriptsLoading || scriptTreeLoading" @click="openScriptDialog">
                {{ selectedBoundScript ? t("web.workflows.reselectDataScript") : t("web.workflows.selectDataScript") }}
              </el-button>
            </el-form-item>
            <div class="soft-panel" v-if="selectedBoundScript">
              <strong>{{ selectedBoundScript.fileName }}</strong>
              <p>{{ selectedBoundScript.datasourceName || t("common.none") }} · {{ formatScriptType(t, selectedBoundScript.scriptType) }}</p>
              <p>{{ t("web.workflows.dataScriptSummary") }}</p>
            </div>
            <el-form-item v-if="selectedBoundScript && selectedBoundScript.scriptType !== 'SQL'" :label="t('web.workflows.dataScriptArguments')">
              <el-input
                v-model="dataScriptArgumentsText"
                type="textarea"
                :rows="6"
                :placeholder="t('web.workflows.dataScriptArgumentsPlaceholder')"
                @blur="applySelectedDataScriptArguments"
              />
            </el-form-item>
            <el-form-item v-else-if="selectedBoundScript" :label="t('web.workflows.dataScriptMaxRows')">
              <el-input-number
                :model-value="selectedScriptMaxRows"
                :min="1"
                controls-position="right"
                :placeholder="t('web.workflows.dataScriptMaxRowsPlaceholder')"
                @update:model-value="updateSelectedDataScriptMaxRows"
              />
            </el-form-item>
          </template>

          <template v-else-if="selectedNode.nodeType === 'HTTP'">
            <div class="http-node-editor">
              <div class="http-node-editor__grid">
                <el-form-item :label="t('web.workflows.httpUrl')">
                  <el-input
                    :model-value="selectedHttpUrl"
                    :placeholder="t('web.workflows.httpUrlPlaceholder')"
                    @update:model-value="updateSelectedHttpConfig({ url: $event })"
                  />
                </el-form-item>
                <el-form-item :label="t('web.workflows.httpMethod')">
                  <el-select
                    :model-value="selectedHttpMethod"
                    :placeholder="t('web.workflows.httpMethodPlaceholder')"
                    @update:model-value="updateSelectedHttpConfig({ method: $event })"
                  >
                    <el-option label="GET" value="GET" />
                    <el-option label="POST" value="POST" />
                    <el-option label="PUT" value="PUT" />
                    <el-option label="PATCH" value="PATCH" />
                    <el-option label="DELETE" value="DELETE" />
                  </el-select>
                </el-form-item>
              </div>

              <div class="http-param-section">
                <div class="http-param-section__header">
                  <div>
                    <strong>{{ t("web.workflows.httpQueryParams") }}</strong>
                    <p>{{ t("web.workflows.httpQueryParamsDescription") }}</p>
                  </div>
                  <el-button size="small" plain @click="appendHttpParam('queryParams')">
                    {{ t("web.workflows.addHttpParam") }}
                  </el-button>
                </div>
                <el-table :data="selectedHttpQueryParams" border size="small" table-layout="fixed" class="http-param-table">
                  <el-table-column :label="t('web.workflows.httpParamEnabled')" width="88" align="center">
                    <template #default="{ row, $index }">
                      <el-switch
                        :model-value="row.enabled !== false"
                        @update:model-value="updateHttpParam('queryParams', $index, 'enabled', $event)"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('web.workflows.httpParamName')" min-width="150">
                    <template #default="{ row, $index }">
                      <el-input
                        :model-value="row.name"
                        :placeholder="t('web.workflows.httpParamNamePlaceholder')"
                        @update:model-value="updateHttpParam('queryParams', $index, 'name', $event)"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('web.workflows.httpParamValue')" min-width="180">
                    <template #default="{ row, $index }">
                      <el-input
                        :model-value="row.value"
                        :placeholder="t('web.workflows.httpParamValuePlaceholder')"
                        @update:model-value="updateHttpParam('queryParams', $index, 'value', $event)"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('common.actions')" width="88" fixed="right" align="center">
                    <template #default="{ $index }">
                      <el-button link type="danger" @click="removeHttpParam('queryParams', $index)">
                        {{ t("common.delete") }}
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>

              <div class="http-param-section">
                <div class="http-param-section__header">
                  <div>
                    <strong>{{ t("web.workflows.httpHeaders") }}</strong>
                    <p>{{ t("web.workflows.httpHeadersDescription") }}</p>
                  </div>
                  <el-button size="small" plain @click="appendHttpParam('headers')">
                    {{ t("web.workflows.addHttpHeader") }}
                  </el-button>
                </div>
                <el-table :data="selectedHttpHeaders" border size="small" table-layout="fixed" class="http-param-table">
                  <el-table-column :label="t('web.workflows.httpParamEnabled')" width="88" align="center">
                    <template #default="{ row, $index }">
                      <el-switch
                        :model-value="row.enabled !== false"
                        @update:model-value="updateHttpParam('headers', $index, 'enabled', $event)"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('web.workflows.httpParamName')" min-width="150">
                    <template #default="{ row, $index }">
                      <el-input
                        :model-value="row.name"
                        placeholder="Content-Type"
                        @update:model-value="updateHttpParam('headers', $index, 'name', $event)"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('web.workflows.httpParamValue')" min-width="180">
                    <template #default="{ row, $index }">
                      <el-input
                        :model-value="row.value"
                        placeholder="application/json"
                        @update:model-value="updateHttpParam('headers', $index, 'value', $event)"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('common.actions')" width="88" fixed="right" align="center">
                    <template #default="{ $index }">
                      <el-button link type="danger" @click="removeHttpParam('headers', $index)">
                        {{ t("common.delete") }}
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>

              <el-form-item :label="t('web.workflows.httpBody')">
                <el-input
                  v-model="httpBodyText"
                  type="textarea"
                  :rows="8"
                  :placeholder="t('web.workflows.httpBodyPlaceholder')"
                  @blur="applySelectedHttpBody"
                />
              </el-form-item>
            </div>
          </template>

          <template v-else>
            <MetaFormRenderer
              :fields="selectedNodeFields"
              :model-value="selectedNodeConfig"
              @update:model-value="selectedNodeConfig = $event"
            />
          </template>
        </template>

        <div v-else class="soft-panel">
          {{ t("web.workflows.emptySelectedNode") }}
        </div>
      </SectionCard>

      <SectionCard :title="t('web.workflows.edgeTitle')" :description="t('web.workflows.edgeDescription')">
        <el-table :data="form.edges" border>
          <el-table-column prop="fromNodeCode" :label="t('web.workflows.from')" min-width="120" />
          <el-table-column prop="toNodeCode" :label="t('web.workflows.to')" min-width="120" />
          <el-table-column :label="t('web.workflows.condition')" min-width="160">
            <template #default="{ row, $index }">
              <el-select :model-value="row.condition" @update:model-value="updateEdgeCondition($index, $event)">
                <el-option label="ON_SUCCESS" value="ON_SUCCESS" />
                <el-option label="ON_FAILURE" value="ON_FAILURE" />
                <el-option label="ALWAYS" value="ALWAYS" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>
    </div>

    <el-dialog
      v-model="collectionTaskDialogVisible"
      :title="t('web.workflows.collectionTaskDialogTitle')"
      width="960px"
      destroy-on-close
      class="workflow-resource-dialog"
    >
      <div class="resource-dialog-toolbar">
        <el-input
          v-model="collectionTaskKeyword"
          clearable
          :placeholder="t('web.workflows.searchCollectionTaskPlaceholder')"
          @input="collectionTaskPagination.page = 1"
        />
        <el-button plain :loading="collectionTasksLoading" @click="ensureCollectionTasksLoaded(true)">
          {{ t("common.refresh") }}
        </el-button>
      </div>
      <el-table
        v-loading="collectionTasksLoading"
        :data="pagedCollectionTasks"
        border
        height="420"
        highlight-current-row
        @row-click="pendingCollectionTaskId = String($event.id)"
      >
        <el-table-column width="52" align="center">
          <template #default="{ row }">
            <el-radio :model-value="pendingCollectionTaskId" :value="String(row.id)" @change="pendingCollectionTaskId = String(row.id)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('common.sequence')" width="76" align="center">
          <template #default="{ $index }">
            {{ getDialogRowIndex(collectionTaskPagination, $index) }}
          </template>
        </el-table-column>
        <el-table-column prop="name" :label="t('web.workflows.collectionTaskNameColumn')" min-width="220" show-overflow-tooltip />
        <el-table-column :label="t('web.workflows.collectionTaskTypeColumn')" min-width="120">
          <template #default="{ row }">
            {{ formatCollectionTaskType(t, row.taskType) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('web.workflows.sourceCountColumn')" width="110" align="center">
          <template #default="{ row }">
            {{ row.sourceCount ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column :label="t('web.workflows.updatedAtColumn')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ formatNullableText(row.updatedAt) }}
          </template>
        </el-table-column>
      </el-table>
      <div class="resource-dialog-pagination">
        <el-pagination
          v-model:current-page="collectionTaskPagination.page"
          v-model:page-size="collectionTaskPagination.pageSize"
          :total="filteredCollectionTasks.length"
          :page-sizes="[8, 10, 20, 50]"
          layout="total, sizes, prev, pager, next"
        />
      </div>
      <template #footer>
        <el-button @click="collectionTaskDialogVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :disabled="!pendingCollectionTaskId" @click="confirmCollectionTaskSelection">
          {{ t("common.confirm") }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="qualityTaskDialogVisible"
      :title="t('web.workflows.qualityTaskDialogTitle')"
      width="1080px"
      destroy-on-close
      class="workflow-resource-dialog"
    >
      <div class="resource-dialog-toolbar">
        <el-input
          v-model="qualityTaskKeyword"
          clearable
          :placeholder="t('web.workflows.searchQualityTaskPlaceholder')"
          @input="handleQualityTaskKeywordInput"
        />
        <el-button plain :loading="qualityTasksLoading" @click="loadQualityTasks">
          {{ t("common.refresh") }}
        </el-button>
      </div>
      <el-table
        v-loading="qualityTasksLoading"
        :data="onlineQualityTasks"
        border
        height="420"
        highlight-current-row
        @row-click="pendingQualityTaskId = String($event.id)"
      >
        <el-table-column width="52" align="center">
          <template #default="{ row }">
            <el-radio :model-value="pendingQualityTaskId" :value="String(row.id)" @change="pendingQualityTaskId = String(row.id)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('common.sequence')" width="76" align="center">
          <template #default="{ $index }">
            {{ getDialogRowIndex(qualityTaskPagination, $index) }}
          </template>
        </el-table-column>
        <el-table-column prop="taskName" :label="t('web.workflows.qualityTaskNameColumn')" min-width="210" show-overflow-tooltip />
        <el-table-column :label="t('web.workflows.qualityTaskRuleSummary')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.ruleName || t("common.none") }} · {{ formatQualityDimension(t, row.ruleDimension) }} · {{ formatQualityGranularity(t, row.granularity) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('web.workflows.qualityTaskScope')" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.datasourceName || t("common.none") }} · {{ row.modelName || t("common.none") }} · {{ row.columnName || (row.granularity === "COLUMN" ? t("common.none") : t("web.workflows.qualityTaskWholeTable")) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('web.workflows.updatedAtColumn')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ formatNullableText(row.updatedAt) }}
          </template>
        </el-table-column>
      </el-table>
      <div class="resource-dialog-pagination">
        <el-pagination
          v-model:current-page="qualityTaskPagination.page"
          v-model:page-size="qualityTaskPagination.pageSize"
          :total="qualityTaskTotal"
          :page-sizes="[8, 10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="handleQualityTaskPageChange"
          @size-change="handleQualityTaskPageSizeChange"
        />
      </div>
      <template #footer>
        <el-button @click="qualityTaskDialogVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :disabled="!pendingQualityTaskId" @click="confirmQualityTaskSelection">
          {{ t("common.confirm") }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="scriptDialogVisible"
      :title="t('web.workflows.dataScriptDialogTitle')"
      width="1180px"
      destroy-on-close
      class="workflow-resource-dialog"
    >
      <div class="script-selector-layout">
        <div class="script-selector-tree soft-panel">
          <div class="script-selector-tree__header">
            <strong>{{ t("web.workflows.scriptDirectoryTitle") }}</strong>
            <el-button size="small" plain :loading="scriptTreeLoading" @click="ensureScriptTreeLoaded(true)">
              {{ t("common.refresh") }}
            </el-button>
          </div>
          <el-input
            v-model="scriptKeyword"
            clearable
            :placeholder="t('web.workflows.searchDataScriptPlaceholder')"
          />
          <el-tree
            v-loading="scriptTreeLoading"
            class="script-selector-tree__body"
            :data="filteredScriptTreeData"
            node-key="nodeKey"
            :props="{ label: 'name', children: 'children' }"
            default-expand-all
            highlight-current
            @node-click="handleScriptTreeClick"
          >
            <template #default="{ data }">
              <div class="script-tree-node" :class="{ 'is-selected': String(data.scriptId ?? '') === pendingScriptId }">
                <span>{{ data.name }}</span>
                <el-tag v-if="data.nodeType === 'SCRIPT' && data.scriptType" size="small" effect="plain">
                  {{ formatScriptType(t, data.scriptType) }}
                </el-tag>
              </div>
            </template>
          </el-tree>
        </div>
        <div class="script-selector-preview">
          <template v-if="previewScript">
            <div class="soft-panel script-preview-meta">
              <div>
                <strong>{{ previewScript.fileName }}</strong>
                <p>
                  {{ formatScriptType(t, previewScript.scriptType) }}
                  · {{ previewScript.datasourceName || t("common.none") }}
                </p>
              </div>
              <el-tag effect="plain">{{ t("web.workflows.scriptPreviewReadonly") }}</el-tag>
            </div>
            <ScriptEditorPanel
              v-model="previewScriptContent"
              :script-type="previewScript.scriptType"
              :placeholder="t('web.dataDevelopment.contentPlaceholder')"
              readonly
            />
          </template>
          <div v-else class="soft-panel script-preview-empty" v-loading="scriptPreviewLoading">
            {{ t("web.workflows.scriptPreviewEmpty") }}
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="scriptDialogVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :disabled="!pendingScriptId" @click="confirmScriptSelection">
          {{ t("common.confirm") }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  CollectionTaskDefinitionView,
  DataDevelopmentScript,
  DataDevelopmentTreeNode,
  DataSourceDefinition,
  MetadataFieldDefinition,
  QualityTaskDefinitionView,
  WorkflowDefinitionView,
  WorkflowNodeDefinition,
  WorkflowSaveRequest,
} from "@studio/api-sdk";
import { WorkflowCanvas } from "@studio/workflow-designer";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard } from "@studio/ui";
import { studioApi } from "@/api/studio";
import CronExpressionPicker from "@web/components/CronExpressionPicker.vue";
import ScriptEditorPanel from "@web/components/data-development/ScriptEditorPanel.vue";
import {
  cloneDeep,
  formatCollectionTaskType,
  formatNodeType,
  formatScriptType,
  prettyJson,
} from "@/utils/studio";

interface WorkflowEditor extends WorkflowSaveRequest {
  definitionId?: string | number;
  schedule: {
    cronExpression?: string;
    enabled?: boolean;
    timezone?: string;
  };
}

interface DialogPagination {
  page: number;
  pageSize: number;
}

type HttpParamSection = "queryParams" | "headers";
type HttpParamField = "enabled" | "name" | "value";

interface HttpParamRow {
  enabled?: boolean;
  name: string;
  value: string;
}

const { t } = useI18n();
const route = useRoute();
const router = useRouter();

const workflowId = computed(() => route.params.workflowId as string | undefined);
const datasources = ref<DataSourceDefinition[]>([]);
const onlineCollectionTasks = ref<CollectionTaskDefinitionView[]>([]);
const onlineQualityTasks = ref<QualityTaskDefinitionView[]>([]);
const scripts = ref<DataDevelopmentScript[]>([]);
const selectedNodeCode = ref<string | null>(null);
const saving = ref(false);
const collectionTasksLoading = ref(false);
const qualityTasksLoading = ref(false);
const qualityTaskReloadPending = ref(false);
const scriptsLoading = ref(false);
const collectionTasksLoaded = ref(false);
const qualityTasksLoaded = ref(false);
const scriptsLoaded = ref(false);
const collectionTaskDialogVisible = ref(false);
const qualityTaskDialogVisible = ref(false);
const scriptDialogVisible = ref(false);
const collectionTaskKeyword = ref("");
const qualityTaskKeyword = ref("");
const scriptKeyword = ref("");
const pendingCollectionTaskId = ref<string>();
const pendingQualityTaskId = ref<string>();
const pendingScriptId = ref<string>();
const scriptTreeData = ref<DataDevelopmentTreeNode[]>([]);
const scriptTreeLoading = ref(false);
const scriptTreeLoaded = ref(false);
const scriptPreviewLoading = ref(false);
const previewScript = ref<DataDevelopmentScript | null>(null);
const collectionTaskPagination = reactive<DialogPagination>({ page: 1, pageSize: 8 });
const qualityTaskPagination = reactive<DialogPagination>({ page: 1, pageSize: 8 });
const qualityTaskTotal = ref(0);
const dataScriptArgumentsText = ref("{}");
const httpBodyText = ref("");
const form = reactive<WorkflowEditor>({
  code: "",
  name: "",
  schedule: {
    cronExpression: "0 */30 * * * ?",
    enabled: false,
    timezone: "Asia/Shanghai",
  },
  nodes: [],
  edges: [],
});

const datasourceOptions = computed(() => datasources.value.map((item) => item.name));
const selectedNode = computed(() => form.nodes.find((node) => node.nodeCode === selectedNodeCode.value));
const filteredCollectionTasks = computed(() =>
  onlineCollectionTasks.value.filter((task) =>
    matchesKeyword(collectionTaskKeyword.value, [task.name, task.taskType, task.status, task.id]),
  ),
);
const pagedCollectionTasks = computed(() => paginateItems(filteredCollectionTasks.value, collectionTaskPagination));
const filteredScriptTreeData = computed(() => filterScriptTree(scriptTreeData.value, scriptKeyword.value));
const previewScriptContent = computed({
  get: () => previewScript.value?.content ?? "",
  set: (value: string) => {
    if (previewScript.value) {
      previewScript.value = {
        ...previewScript.value,
        content: value,
      };
    }
  },
});
const selectedCollectionTaskId = computed(() => {
  if (!selectedNode.value?.config?.collectionTaskId) {
    return undefined;
  }
  return String(selectedNode.value.config.collectionTaskId);
});
const selectedBoundTask = computed(() =>
  onlineCollectionTasks.value.find((item) => String(item.id) === selectedCollectionTaskId.value),
);
const selectedQualityTaskId = computed(() => {
  if (!selectedNode.value?.config?.qualityTaskId) {
    return undefined;
  }
  return String(selectedNode.value.config.qualityTaskId);
});
const selectedBoundQualityTask = computed(() =>
  onlineQualityTasks.value.find((item) => String(item.id) === selectedQualityTaskId.value) ?? buildSelectedQualityTaskFallback(),
);
const selectedScriptId = computed(() => {
  if (!selectedNode.value?.config?.scriptId) {
    return undefined;
  }
  return String(selectedNode.value.config.scriptId);
});
const selectedBoundScript = computed(() =>
  scripts.value.find((item) => String(item.id) === selectedScriptId.value),
);

const selectedNodeConfig = computed<Record<string, unknown>>({
  get() {
    return selectedNode.value?.config ?? {};
  },
  set(value) {
    updateSelectedNode("config", value);
  },
});

const selectedHttpUrl = computed(() => String(selectedNode.value?.config?.url ?? ""));
const selectedHttpMethod = computed(() => {
  const method = String(selectedNode.value?.config?.method ?? "").trim();
  return method ? method.toUpperCase() : "GET";
});
const selectedHttpQueryParams = computed(() => normalizeHttpParamRows(selectedNode.value?.config?.queryParams));
const selectedHttpHeaders = computed(() => normalizeHttpParamRows(selectedNode.value?.config?.headers));
const selectedScriptMaxRows = computed(() => {
  const rawValue = selectedNode.value?.config?.maxRows;
  if (typeof rawValue === "number") {
    return rawValue;
  }
  if (typeof rawValue === "string" && rawValue.trim()) {
    return Number(rawValue);
  }
  return 100;
});

const selectedNodeFields = computed<MetadataFieldDefinition[]>(() => {
  if (!selectedNode.value?.nodeType) {
    return [];
  }
  if (selectedNode.value.nodeType === "HTTP") {
    return [
      { fieldKey: "url", fieldName: "URL", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "method", fieldName: "Method", scope: "TECHNICAL", componentType: "SELECT", valueType: "STRING", options: ["GET", "POST", "PUT", "DELETE"] },
      { fieldKey: "payload", fieldName: "Payload", scope: "TECHNICAL", componentType: "JSON_EDITOR", valueType: "JSON" },
    ];
  }
  if (selectedNode.value.nodeType === "SHELL") {
    return [
      { fieldKey: "command", fieldName: "Command", scope: "TECHNICAL", componentType: "CODE_EDITOR", valueType: "STRING", required: true },
      { fieldKey: "workingDirectory", fieldName: "Working Directory", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
      { fieldKey: "timeoutSeconds", fieldName: "Timeout Seconds", scope: "TECHNICAL", componentType: "NUMBER", valueType: "INTEGER" },
    ];
  }
  return [
    { fieldKey: "sourceDatasource", fieldName: "Source Datasource", scope: "TECHNICAL", componentType: "SELECT", valueType: "STRING", options: datasourceOptions.value },
    { fieldKey: "sourceModel", fieldName: "Source Model", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
    { fieldKey: "targetDatasource", fieldName: "Target Datasource", scope: "TECHNICAL", componentType: "SELECT", valueType: "STRING", options: datasourceOptions.value },
    { fieldKey: "targetModel", fieldName: "Target Model", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
    { fieldKey: "sourceFields", fieldName: "Source Fields", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING" },
    { fieldKey: "targetFields", fieldName: "Target Fields", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING" },
    { fieldKey: "ruleId", fieldName: "Rule ID", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
    { fieldKey: "compareFields", fieldName: "Compare Fields", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING" },
  ];
});

function buildSelectedQualityTaskFallback(): QualityTaskDefinitionView | undefined {
  const config = selectedNode.value?.config;
  if (!config?.qualityTaskId) {
    return undefined;
  }
  const taskName = readSelectedNodeConfigText("qualityTaskName")
    ?? readSelectedNodeConfigText("qualityTaskCode")
    ?? String(config.qualityTaskId);
  return {
    id: config.qualityTaskId as QualityTaskDefinitionView["id"],
    taskName,
    taskCode: readSelectedNodeConfigText("qualityTaskCode") ?? taskName,
    status: "ONLINE",
    ruleId: config.ruleId as QualityTaskDefinitionView["ruleId"],
    ruleName: readSelectedNodeConfigText("ruleName"),
    ruleDimension: config.ruleDimension as QualityTaskDefinitionView["ruleDimension"],
    granularity: config.granularity as QualityTaskDefinitionView["granularity"],
    datasourceId: config.datasourceId as QualityTaskDefinitionView["datasourceId"],
    datasourceName: readSelectedNodeConfigText("datasourceName"),
    modelId: config.modelId as QualityTaskDefinitionView["modelId"],
    modelName: readSelectedNodeConfigText("modelName"),
    columnName: readSelectedNodeConfigText("columnName"),
    parameterBindings: [],
    outputParams: [],
    alertConfigs: [],
  };
}

function readSelectedNodeConfigText(key: string) {
  const value = selectedNode.value?.config?.[key];
  return value == null || value === "" ? undefined : String(value);
}

function resetWorkflow() {
  form.definitionId = undefined;
  form.code = "";
  form.name = "";
  form.schedule = {
    cronExpression: "0 */30 * * * ?",
    enabled: false,
    timezone: "Asia/Shanghai",
  };
  form.nodes = [];
  form.edges = [];
  selectedNodeCode.value = null;
}

async function loadReferenceData() {
  try {
    const [datasourceData] = await Promise.all([
      studioApi.datasources.list(),
    ]);
    datasources.value = datasourceData;
    await refreshSelectedNodeCandidates();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  }
}

async function refreshSelectedNodeCandidates() {
  if (selectedNode.value?.nodeType === "COLLECTION_TASK") {
    await ensureCollectionTasksLoaded(true);
    return;
  }
  if (selectedNode.value?.nodeType === "QUALITY_TASK") {
    await ensureQualityTasksLoaded(true);
    return;
  }
  if (selectedNode.value?.nodeType === "DATA_SCRIPT") {
    await ensureScriptsLoaded(true);
  }
}

async function ensureCollectionTasksLoaded(force = false) {
  if ((collectionTasksLoaded.value && !force) || collectionTasksLoading.value) {
    return;
  }
  collectionTasksLoading.value = true;
  try {
    onlineCollectionTasks.value = await studioApi.collectionTasks.listOnline();
    collectionTasksLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  } finally {
    collectionTasksLoading.value = false;
  }
}

async function ensureQualityTasksLoaded(force = false) {
  if ((qualityTasksLoaded.value && !force) || qualityTasksLoading.value) {
    return;
  }
  await loadQualityTasks();
}

async function loadQualityTasks() {
  if (qualityTasksLoading.value) {
    qualityTaskReloadPending.value = true;
    return;
  }
  qualityTasksLoading.value = true;
  try {
    let page = await studioApi.qualityTasks.listPage({
      pageNo: qualityTaskPagination.page,
      pageSize: qualityTaskPagination.pageSize,
      keyword: qualityTaskKeyword.value.trim() || undefined,
      status: "ONLINE",
    });
    const maxPage = Math.max(1, Math.ceil(page.total / qualityTaskPagination.pageSize));
    if (qualityTaskPagination.page > maxPage) {
      qualityTaskPagination.page = maxPage;
      page = await studioApi.qualityTasks.listPage({
        pageNo: qualityTaskPagination.page,
        pageSize: qualityTaskPagination.pageSize,
        keyword: qualityTaskKeyword.value.trim() || undefined,
        status: "ONLINE",
      });
    }
    onlineQualityTasks.value = page.items;
    qualityTaskTotal.value = page.total;
    qualityTasksLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  } finally {
    qualityTasksLoading.value = false;
    if (qualityTaskReloadPending.value) {
      qualityTaskReloadPending.value = false;
      await loadQualityTasks();
    }
  }
}

async function ensureScriptsLoaded(force = false) {
  if ((scriptsLoaded.value && !force) || scriptsLoading.value) {
    return;
  }
  scriptsLoading.value = true;
  try {
    scripts.value = await studioApi.dataDevelopment.listScripts();
    scriptsLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  } finally {
    scriptsLoading.value = false;
  }
}

async function ensureScriptTreeLoaded(force = false) {
  if ((scriptTreeLoaded.value && !force) || scriptTreeLoading.value) {
    return;
  }
  scriptTreeLoading.value = true;
  try {
    scriptTreeData.value = await studioApi.dataDevelopment.tree();
    scriptTreeLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  } finally {
    scriptTreeLoading.value = false;
  }
}

async function openCollectionTaskDialog() {
  collectionTaskDialogVisible.value = true;
  pendingCollectionTaskId.value = selectedCollectionTaskId.value;
  collectionTaskPagination.page = 1;
  await ensureCollectionTasksLoaded();
}

function confirmCollectionTaskSelection() {
  bindCollectionTask(pendingCollectionTaskId.value);
  collectionTaskDialogVisible.value = false;
}

async function openQualityTaskDialog() {
  qualityTaskDialogVisible.value = true;
  pendingQualityTaskId.value = selectedQualityTaskId.value;
  qualityTaskPagination.page = 1;
  await loadQualityTasks();
}

function confirmQualityTaskSelection() {
  bindQualityTask(pendingQualityTaskId.value);
  qualityTaskDialogVisible.value = false;
}

function handleQualityTaskKeywordInput() {
  qualityTaskPagination.page = 1;
  void loadQualityTasks();
}

function handleQualityTaskPageChange() {
  void loadQualityTasks();
}

function handleQualityTaskPageSizeChange() {
  qualityTaskPagination.page = 1;
  void loadQualityTasks();
}

async function openScriptDialog() {
  scriptDialogVisible.value = true;
  pendingScriptId.value = selectedScriptId.value;
  previewScript.value = null;
  await Promise.all([ensureScriptsLoaded(), ensureScriptTreeLoaded()]);
  if (pendingScriptId.value) {
    await loadScriptPreview(pendingScriptId.value);
  }
}

async function handleScriptTreeClick(node: DataDevelopmentTreeNode) {
  if (node.nodeType !== "SCRIPT" || !node.scriptId) {
    return;
  }
  pendingScriptId.value = String(node.scriptId);
  await loadScriptPreview(node.scriptId);
}

async function loadScriptPreview(scriptId: string | number) {
  scriptPreviewLoading.value = true;
  previewScript.value = null;
  try {
    const script = await studioApi.dataDevelopment.getScript(scriptId);
    previewScript.value = script;
    upsertScript(script);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  } finally {
    scriptPreviewLoading.value = false;
  }
}

function confirmScriptSelection() {
  bindScript(pendingScriptId.value);
  scriptDialogVisible.value = false;
}

function upsertScript(script: DataDevelopmentScript) {
  const index = scripts.value.findIndex((item) => String(item.id) === String(script.id));
  if (index >= 0) {
    scripts.value = scripts.value.map((item, itemIndex) => itemIndex === index ? script : item);
    return;
  }
  scripts.value = [script, ...scripts.value];
}

async function loadWorkflow() {
  if (!workflowId.value) {
    resetWorkflow();
    return;
  }
  try {
    const workflow = await studioApi.workflows.get(workflowId.value);
    applyWorkflow(workflow);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.loadFailed"));
  }
}

function applyWorkflow(workflow: WorkflowDefinitionView) {
  const copied = cloneDeep(workflow);
  form.definitionId = copied.id;
  form.code = copied.code;
  form.name = copied.name;
  form.schedule = copied.schedule ?? {
    cronExpression: "0 */30 * * * ?",
    enabled: false,
    timezone: "Asia/Shanghai",
  };
  form.nodes = copied.nodes ?? [];
  form.edges = copied.edges ?? [];
  selectedNodeCode.value = form.nodes[0]?.nodeCode ?? null;
}

function updateSelectedNode(key: keyof WorkflowNodeDefinition, value: unknown) {
  form.nodes = form.nodes.map((node) =>
    node.nodeCode === selectedNodeCode.value
      ? {
          ...node,
          [key]: value,
        }
      : node,
  );
}

function bindCollectionTask(value?: string) {
  if (!value) {
    return;
  }
  const task = onlineCollectionTasks.value.find((item) => String(item.id) === String(value));
  if (!selectedNode.value || !task) {
    return;
  }
  form.nodes = form.nodes.map((node) =>
    node.nodeCode === selectedNodeCode.value
      ? {
          ...node,
          nodeName: task.name,
          config: {
            ...(node.config ?? {}),
            collectionTaskId: task.id,
            collectionTaskName: task.name,
            collectionTaskType: task.taskType,
          },
        }
      : node,
  );
}

function bindQualityTask(value?: string) {
  if (!value) {
    return;
  }
  const task = onlineQualityTasks.value.find((item) => String(item.id) === String(value));
  if (!selectedNode.value || !task) {
    return;
  }
  form.nodes = form.nodes.map((node) =>
    node.nodeCode === selectedNodeCode.value
      ? {
          ...node,
          nodeName: task.taskName,
          config: {
            ...(node.config ?? {}),
            qualityTaskId: task.id,
            qualityTaskName: task.taskName,
            qualityTaskCode: task.taskCode,
            ruleId: task.ruleId,
            ruleName: task.ruleName,
            ruleDimension: task.ruleDimension,
            granularity: task.granularity,
            datasourceId: task.datasourceId,
            datasourceName: task.datasourceName,
            modelId: task.modelId,
            modelName: task.modelName,
            columnName: task.columnName,
          },
        }
      : node,
  );
}

function bindScript(value?: string) {
  if (!value) {
    return;
  }
  const script = scripts.value.find((item) => String(item.id) === String(value));
  if (!selectedNode.value || !script) {
    return;
  }
  const nextConfig: Record<string, unknown> = {
    ...(selectedNode.value.config ?? {}),
    scriptId: script.id,
    scriptName: script.fileName,
    scriptType: script.scriptType,
    datasourceId: script.datasourceId,
    datasourceName: script.datasourceName,
  };
  if (script.scriptType !== "SQL") {
    delete nextConfig.maxRows;
    if (nextConfig.arguments == null) {
      nextConfig.arguments = {};
    }
  } else {
    delete nextConfig.arguments;
    if (nextConfig.maxRows == null) {
      nextConfig.maxRows = 100;
    }
  }
  form.nodes = form.nodes.map((node) =>
    node.nodeCode === selectedNodeCode.value
      ? {
          ...node,
          nodeName: script.fileName,
          config: nextConfig,
        }
      : node,
  );
}

function updateSelectedDataScriptMaxRows(value: number | undefined) {
  updateSelectedNode("config", {
    ...(selectedNode.value?.config ?? {}),
    maxRows: value == null ? 100 : value,
  });
}

function applySelectedDataScriptArguments() {
  if (!selectedBoundScript.value || selectedBoundScript.value.scriptType === "SQL") {
    return;
  }
  try {
    updateSelectedNode("config", {
      ...(selectedNode.value?.config ?? {}),
      arguments: parseDataScriptArguments(dataScriptArgumentsText.value),
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.dataScriptArgumentsInvalid"));
  }
}

function updateSelectedHttpConfig(patch: Record<string, unknown>) {
  if (!selectedNode.value || selectedNode.value.nodeType !== "HTTP") {
    return;
  }
  updateSelectedNode("config", {
    ...(selectedNode.value.config ?? {}),
    ...patch,
  });
}

function appendHttpParam(section: HttpParamSection) {
  const rows = normalizeHttpParamRows(selectedNode.value?.config?.[section]);
  rows.push({
    enabled: true,
    name: "",
    value: "",
  });
  updateSelectedHttpConfig({ [section]: rows });
}

function updateHttpParam(section: HttpParamSection, index: number, field: HttpParamField, value: unknown) {
  const rows = normalizeHttpParamRows(selectedNode.value?.config?.[section]);
  rows[index] = {
    ...(rows[index] ?? { enabled: true, name: "", value: "" }),
    [field]: field === "enabled" ? Boolean(value) : String(value ?? ""),
  };
  updateSelectedHttpConfig({ [section]: rows });
}

function removeHttpParam(section: HttpParamSection, index: number) {
  const rows = normalizeHttpParamRows(selectedNode.value?.config?.[section]);
  rows.splice(index, 1);
  updateSelectedHttpConfig({ [section]: rows });
}

function applySelectedHttpBody() {
  if (!selectedNode.value || selectedNode.value.nodeType !== "HTTP") {
    return;
  }
  try {
    updateSelectedHttpConfig({ body: parseHttpBody(httpBodyText.value) });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.httpBodyInvalid"));
    throw error;
  }
}

function removeSelectedNode() {
  if (!selectedNodeCode.value) {
    return;
  }
  const removingCode = selectedNodeCode.value;
  form.nodes = form.nodes.filter((node) => node.nodeCode !== removingCode);
  form.edges = form.edges.filter((edge) => edge.fromNodeCode !== removingCode && edge.toNodeCode !== removingCode);
  selectedNodeCode.value = null;
}

function updateEdgeCondition(index: number, condition: string) {
  form.edges = form.edges.map((edge, edgeIndex) =>
    edgeIndex === index
      ? {
          ...edge,
          condition: condition as WorkflowEditor["edges"][number]["condition"],
        }
      : edge,
  );
}

async function saveWorkflow() {
  saving.value = true;
  try {
    if (selectedNode.value?.nodeType === "HTTP") {
      applySelectedHttpBody();
    }
    const saved = await studioApi.workflows.save(buildWorkflowPayload());
    ElMessage.success(t("web.workflows.saveSuccess"));
    applyWorkflow(saved);
    await router.push("/workflows");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.workflows.saveFailed"));
  } finally {
    saving.value = false;
  }
}

watch(workflowId, async () => {
  await loadWorkflow();
}, { immediate: true });

watch(
  () => selectedNode.value?.nodeType,
  (nodeType) => {
    if (nodeType === "COLLECTION_TASK") {
      void ensureCollectionTasksLoaded();
      return;
    }
    if (nodeType === "QUALITY_TASK") {
      void ensureQualityTasksLoaded();
      return;
    }
    if (nodeType === "DATA_SCRIPT") {
      void ensureScriptsLoaded();
    }
  },
  { immediate: true },
);

watch(
  () => [selectedNodeCode.value, selectedBoundScript.value?.id, selectedNode.value?.config?.arguments] as const,
  () => {
    if (selectedBoundScript.value && selectedBoundScript.value.scriptType !== "SQL") {
      dataScriptArgumentsText.value = prettyJson(selectedNode.value?.config?.arguments ?? {});
      return;
    }
    dataScriptArgumentsText.value = "{}";
  },
  { immediate: true },
);

watch(
  () => [selectedNodeCode.value, selectedNode.value?.nodeType, selectedNode.value?.config?.body, selectedNode.value?.config?.payload] as const,
  () => {
    if (selectedNode.value?.nodeType !== "HTTP") {
      httpBodyText.value = "";
      return;
    }
    const body = selectedNode.value.config?.body ?? selectedNode.value.config?.payload;
    httpBodyText.value = body == null || body === "" ? "" : prettyJson(body);
  },
  { immediate: true },
);

onMounted(loadReferenceData);

function buildWorkflowPayload() {
  const payload = cloneDeep(form);
  payload.nodes = payload.nodes.map((node) => {
    if (node.nodeType === "HTTP") {
      const config = { ...(node.config ?? {}) };
      const legacyBody = config.payload;
      if (config.body == null && legacyBody != null) {
        config.body = legacyBody;
      }
      delete config.payload;
      return {
        ...node,
        config: {
          ...config,
          method: String(config.method ?? "").trim() ? String(config.method).toUpperCase() : "GET",
          queryParams: normalizeHttpParamRows(config.queryParams).filter((item) => item.name.trim()),
          headers: normalizeHttpParamRows(config.headers).filter((item) => item.name.trim()),
        },
      };
    }
    if (node.nodeType !== "DATA_SCRIPT") {
      return node;
    }
    const scriptType = String(node.config?.scriptType ?? "").toUpperCase();
    if (scriptType !== "SQL") {
      return {
        ...node,
        config: {
          ...(node.config ?? {}),
          arguments: parseDataScriptArguments(node.config?.arguments),
        },
      };
    }
    return {
      ...node,
      config: {
        ...(node.config ?? {}),
        maxRows: normalizeMaxRows(node.config?.maxRows),
      },
    };
  });
  return payload;
}

function parseDataScriptArguments(value: unknown) {
  if (value == null) {
    return {};
  }
  if (typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  const text = String(value).trim();
  if (!text) {
    return {};
  }
  const parsed = JSON.parse(text);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(t("web.workflows.dataScriptArgumentsInvalid"));
  }
  return parsed as Record<string, unknown>;
}

function normalizeMaxRows(value: unknown) {
  if (typeof value === "number") {
    return value;
  }
  if (typeof value === "string" && value.trim()) {
    return Number(value);
  }
  return 100;
}

function normalizeHttpParamRows(value: unknown): HttpParamRow[] {
  if (Array.isArray(value)) {
    return value
      .filter((item) => item != null)
      .map((item) => {
        if (typeof item === "object" && !Array.isArray(item)) {
          const record = item as Record<string, unknown>;
          return {
            enabled: record.enabled == null ? true : Boolean(record.enabled),
            name: String(record.name ?? record.key ?? ""),
            value: String(record.value ?? ""),
          };
        }
        return {
          enabled: true,
          name: "",
          value: String(item),
        };
      });
  }
  if (value && typeof value === "object") {
    return Object.entries(value as Record<string, unknown>).map(([name, itemValue]) => ({
      enabled: true,
      name,
      value: itemValue == null ? "" : String(itemValue),
    }));
  }
  return [];
}

function parseHttpBody(value: string) {
  const text = value.trim();
  if (!text) {
    return undefined;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new Error(t("web.workflows.httpBodyInvalid"));
  }
}

function matchesKeyword(keyword: string, values: unknown[]) {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) {
    return true;
  }
  return values
    .filter((value) => value != null)
    .some((value) => String(value).toLowerCase().includes(normalized));
}

function paginateItems<T>(items: T[], pagination: DialogPagination) {
  const start = (pagination.page - 1) * pagination.pageSize;
  return items.slice(start, start + pagination.pageSize);
}

function getDialogRowIndex(pagination: DialogPagination, index: number) {
  return (pagination.page - 1) * pagination.pageSize + index + 1;
}

function filterScriptTree(nodes: DataDevelopmentTreeNode[], keyword: string): DataDevelopmentTreeNode[] {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) {
    return nodes;
  }
  return nodes
    .map((node) => {
      const children = filterScriptTree(node.children ?? [], keyword);
      const selfMatched = [
        node.name,
        node.scriptType,
        node.datasourceName,
        node.permissionCode,
        node.scriptId,
        node.directoryId,
      ]
        .filter((value) => value != null)
        .some((value) => String(value).toLowerCase().includes(normalized));
      if (selfMatched || children.length > 0) {
        return {
          ...node,
          children,
        };
      }
      return null;
    })
    .filter((node): node is DataDevelopmentTreeNode => node != null);
}

function formatNullableText(value: unknown) {
  return value == null || value === "" ? "-" : String(value);
}

function formatQualityDimension(t: (key: string) => string, value?: string | null) {
  const mapping: Record<string, string> = {
    CONSISTENCY: "web.workflows.qualityDimensionConsistency",
    ACCURACY: "web.workflows.qualityDimensionAccuracy",
    UNIQUENESS: "web.workflows.qualityDimensionUniqueness",
    TIMELINESS: "web.workflows.qualityDimensionTimeliness",
    COMPLETENESS: "web.workflows.qualityDimensionCompleteness",
    VALIDITY: "web.workflows.qualityDimensionValidity",
  };
  return value && mapping[value] ? t(mapping[value]) : String(value ?? t("common.none"));
}

function formatQualityGranularity(t: (key: string) => string, value?: string | null) {
  if (value === "COLUMN") {
    return t("web.workflows.qualityGranularityColumn");
  }
  if (value === "TABLE") {
    return t("web.workflows.qualityGranularityTable");
  }
  return String(value ?? t("common.none"));
}
</script>

<style scoped>
.cron-form-item {
  grid-column: 1 / -1;
}
</style>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.http-node-editor {
  display: grid;
  gap: 18px;
}

.http-node-editor__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 160px;
  gap: 14px;
}

.http-param-section {
  display: grid;
  gap: 10px;
}

.http-param-section__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.http-param-section__header strong {
  display: block;
  margin-bottom: 3px;
}

.http-param-table :deep(.cell) {
  overflow: visible;
}

.resource-dialog-toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) auto;
  gap: 12px;
  margin-bottom: 12px;
}

.resource-dialog-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.script-selector-layout {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  min-height: 560px;
}

.script-selector-tree {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 12px;
  min-height: 0;
}

.script-selector-tree__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.script-selector-tree__body {
  min-height: 0;
  max-height: 500px;
  overflow: auto;
}

.script-tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-width: 0;
}

.script-tree-node span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.script-selector-preview {
  min-width: 0;
}

.script-preview-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.script-preview-empty {
  display: grid;
  min-height: 520px;
  place-items: center;
  text-align: center;
}

@media (max-width: 1080px) {
  .http-node-editor__grid {
    grid-template-columns: 1fr;
  }

  .script-selector-layout {
    grid-template-columns: 1fr;
  }
}
</style>
