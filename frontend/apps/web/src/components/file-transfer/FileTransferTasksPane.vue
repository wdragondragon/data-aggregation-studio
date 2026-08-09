<template>
  <div class="transfer-tasks">
    <div class="section-toolbar transfer-tasks__toolbar">
      <div class="transfer-task-filters">
        <el-input v-model="filters.keyword" clearable placeholder="任务名称或编码" @keyup.enter="searchTasks" />
        <el-select v-model="filters.status" clearable placeholder="全部状态">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="ONLINE" />
          <el-option label="已下线" value="OFFLINE" />
        </el-select>
        <el-button type="primary" @click="searchTasks">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <div>
        <el-button :icon="Refresh" :loading="loading" @click="loadTasks">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openEditor()">新建任务</el-button>
      </div>
    </div>

    <StudioTableShell min-width="1320px">
      <el-table v-loading="loading" :data="tasks" size="small" table-layout="fixed" max-height="calc(100vh - 300px)">
        <el-table-column label="任务名称 / 编码" min-width="220">
          <template #default="{ row }">
            <div class="task-main-cell">
              <el-button link type="primary" @click="openEditor(row)">{{ row.name }}</el-button>
              <span class="studio-mono">{{ row.code }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="210">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ row.sourceDatasourceName || `#${row.sourceDatasourceId}` }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="目标" min-width="210">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ row.targetDatasourceName || `#${row.targetDatasourceId}` }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="选择 / 映射" min-width="260">
          <template #default="{ row }">
            <div class="stack-cell studio-mono">
              <span :title="String(row.selection?.rootPath || '/')">{{ row.selection?.rootPath || "/" }}</span>
              <span :title="String(row.mapping?.targetRootPath || '/')">→ {{ row.mapping?.targetRootPath || "/" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="策略 / 并发" min-width="180">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ conflictLabel(row.policy?.conflictPolicy) }}</span>
              <span>{{ sourceActionLabel(row.policy?.sourceSuccessAction) }} · {{ row.runtime?.concurrency || 4 }} 文件</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="调度" min-width="190">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ row.schedule?.enabled ? row.schedule.cronExpression || '--' : '未启用' }}</span>
              <span>{{ row.schedule?.timezone || "Asia/Shanghai" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="90" align="center">
          <template #default="{ row }">v{{ row.version || 1 }} / {{ row.publishedVersion || '--' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <StatusPill :label="fileTransferStatusLabel(row.status)" :tone="fileTransferStatusTone(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="172">
          <template #default="{ row }">{{ formatTransferDate(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="92" fixed="right" align="center">
          <template #default="{ row }">
            <OverflowActionGroup :items="taskActions(row)" />
          </template>
        </el-table-column>
      </el-table>
    </StudioTableShell>

    <div class="table-pagination">
      <el-pagination
        v-model:current-page="pagination.pageNo"
        v-model:page-size="pagination.pageSize"
        background
        layout="total, sizes, prev, pager, next"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        @current-change="loadTasks"
        @size-change="handlePageSizeChange"
      />
    </div>

    <el-dialog
      v-model="editorVisible"
      :title="form.id ? '编辑文件传输任务' : '新建文件传输任务'"
      width="min(1120px, calc(100vw - 24px))"
      top="4vh"
      destroy-on-close
      class="transfer-task-editor-dialog"
    >
      <el-steps :active="editorStep" finish-status="success" simple class="transfer-task-steps">
        <el-step title="基本信息" />
        <el-step title="来源与目标" />
        <el-step title="文件选择" />
        <el-step title="路径映射" />
        <el-step title="冲突与后处理" />
        <el-step title="运行参数" />
        <el-step title="调度与发布" />
      </el-steps>

      <el-form label-position="top" class="transfer-task-form">
        <div v-show="editorStep === 0" class="editor-step-grid columns-2">
          <el-form-item label="任务名称" required>
            <el-input v-model="form.name" maxlength="120" show-word-limit />
          </el-form-item>
          <el-form-item label="任务编码" required>
            <el-input v-model="form.code" class="studio-mono" :disabled="Boolean(form.id)" />
          </el-form-item>
        </div>

        <div v-show="editorStep === 1" class="endpoint-editor-grid">
          <div class="editor-wide-alert">
            <el-form-item label="运行集群" required>
              <el-select v-model="form.runtimeClusterId" filterable placeholder="请选择运行集群" @change="handleTaskRuntimeClusterChange">
                <el-option v-for="item in runtimeClusters" :key="String(item.id)" :label="item.name || item.code" :value="item.id" />
              </el-select>
            </el-form-item>
          </div>
          <section>
            <h4>来源</h4>
            <el-form-item label="文件数据源" required>
              <el-select v-model="form.sourceDatasourceId" filterable>
                <el-option v-for="item in sourceDatasources" :key="String(item.id)" :label="`${item.name} (${item.typeCode})`" :value="item.id" />
              </el-select>
            </el-form-item>
          </section>
          <section>
            <h4>目标</h4>
            <el-form-item label="文件数据源" required>
              <el-select v-model="form.targetDatasourceId" filterable>
                <el-option v-for="item in targetDatasources" :key="String(item.id)" :label="`${item.name} (${item.typeCode})`" :value="item.id" />
              </el-select>
            </el-form-item>
          </section>
        </div>

        <div v-show="editorStep === 2" class="editor-step-grid columns-2">
          <el-form-item label="源根路径" required>
            <el-input v-model="form.sourceRootPath" class="studio-mono" />
          </el-form-item>
          <el-form-item label="最多发现文件数">
            <el-input-number v-model="form.maxFiles" :min="1" :max="1000000" controls-position="right" />
          </el-form-item>
          <el-form-item label="指定相对路径">
            <el-input v-model="form.pathsText" type="textarea" :rows="4" placeholder="每行一个文件或目录；留空扫描根路径" />
          </el-form-item>
          <el-form-item label="包含 Glob">
            <el-input v-model="form.includeGlobsText" type="textarea" :rows="4" placeholder="每行一个，例如 **/*.csv" />
          </el-form-item>
          <el-form-item label="包含正则">
            <el-input v-model="form.includeRegex" class="studio-mono" clearable />
          </el-form-item>
          <el-form-item label="排除 Glob">
            <el-input v-model="form.excludeGlobsText" type="textarea" :rows="3" placeholder="每行一个，例如 **/.tmp/**" />
          </el-form-item>
          <el-form-item label="文件大小范围（字节）">
            <div class="inline-number-range">
              <el-input-number v-model="form.minSize" :min="0" controls-position="right" placeholder="最小" />
              <span>至</span>
              <el-input-number v-model="form.maxSize" :min="0" controls-position="right" placeholder="最大" />
            </div>
          </el-form-item>
          <el-form-item label="修改时间范围">
            <el-date-picker
              v-model="form.modifiedRange"
              type="datetimerange"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              value-format="YYYY-MM-DD HH:mm:ss"
            />
          </el-form-item>
          <el-form-item label="递归目录">
            <el-switch v-model="form.recursive" />
          </el-form-item>
        </div>

        <div v-show="editorStep === 3" class="editor-step-grid columns-2">
          <el-form-item label="目标根路径" required>
            <el-input v-model="form.targetRootPath" class="studio-mono" />
          </el-form-item>
          <el-form-item label="目标相对路径模板">
            <el-input v-model="form.targetPathTemplate" class="studio-mono" placeholder="${relativePath}" />
          </el-form-item>
          <el-form-item label="保留相对目录">
            <el-switch v-model="form.preserveRelativePath" />
          </el-form-item>
        </div>

        <div v-show="editorStep === 4" class="editor-step-grid columns-2">
          <el-form-item label="内容冲突策略">
            <el-select v-model="form.conflictPolicy">
              <el-option label="失败" value="FAIL" />
              <el-option label="覆盖" value="OVERWRITE" />
              <el-option label="备份后覆盖" value="BACKUP_THEN_OVERWRITE" />
            </el-select>
          </el-form-item>
          <el-form-item label="摘要算法">
            <el-input model-value="SHA-256" disabled />
          </el-form-item>
          <el-form-item label="目标成功后的源端动作">
            <el-select v-model="form.sourceSuccessAction">
              <el-option label="保留源文件" value="KEEP" />
              <el-option label="删除源文件" value="DELETE" />
              <el-option label="备份源文件" value="BACKUP" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.sourceSuccessAction === 'BACKUP'" label="源端备份根路径" required>
            <el-input v-model="form.sourceBackupRootPath" class="studio-mono" />
          </el-form-item>
          <el-alert class="editor-wide-alert" type="info" :closable="false" title="目标 SHA-256 与源文件一致时固定跳过，不受冲突策略影响。" />
        </div>

        <div v-show="editorStep === 5" class="editor-step-grid columns-2">
          <el-form-item label="并行文件数">
            <el-input-number v-model="form.concurrency" :min="1" :max="32" controls-position="right" />
          </el-form-item>
          <el-form-item label="最大重试次数">
            <el-input-number v-model="form.maxRetries" :min="0" :max="20" controls-position="right" />
          </el-form-item>
          <el-form-item label="重试退避（秒）">
            <el-input v-model="form.retryBackoffText" class="studio-mono" placeholder="1, 5, 15" />
          </el-form-item>
          <el-form-item label="带宽上限（MiB/s，0 为不限）">
            <el-input-number v-model="form.maxMiBPerSecond" :min="0" :max="102400" controls-position="right" />
          </el-form-item>
          <el-form-item label="传输块大小">
            <el-select v-model="form.chunkSizeMiB">
              <el-option v-for="size in [1, 2, 4, 8, 16, 32, 64]" :key="size" :label="`${size} MiB`" :value="size" />
            </el-select>
          </el-form-item>
          <el-form-item label="检查点时间间隔（毫秒）">
            <el-input-number v-model="form.checkpointIntervalMillis" :min="100" :max="60000" :step="100" controls-position="right" />
          </el-form-item>
        </div>

        <div v-show="editorStep === 6" class="editor-step-grid columns-2">
          <el-form-item label="启用定时调度">
            <el-switch v-model="form.scheduleEnabled" />
          </el-form-item>
          <el-form-item label="时区">
            <el-select v-model="form.timezone" filterable allow-create>
              <el-option label="Asia/Shanghai" value="Asia/Shanghai" />
              <el-option label="UTC" value="UTC" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.scheduleEnabled" label="Cron 表达式" required>
            <el-input v-model="form.cronExpression" class="studio-mono" />
          </el-form-item>
          <div class="editor-final-actions">
            <el-button :disabled="!form.id" @click="validateCurrentTask">校验配置</el-button>
            <el-button :disabled="!form.id" @click="previewCurrentTask">预览选中文件</el-button>
          </div>
        </div>
      </el-form>

      <template #footer>
        <div class="editor-footer">
          <el-button :disabled="editorStep === 0" @click="editorStep -= 1">上一步</el-button>
          <div>
            <el-button @click="editorVisible = false">取消</el-button>
            <el-button v-if="editorStep < 6" type="primary" @click="editorStep += 1">下一步</el-button>
            <template v-else>
              <el-button :loading="saving" @click="saveTask(false)">保存草稿</el-button>
              <el-button type="primary" :loading="saving" @click="saveTask(true)">保存并发布</el-button>
            </template>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-drawer v-model="previewVisible" title="文件选择预览" size="min(760px, 94vw)">
      <div v-if="preview" class="preview-summary">
        <div><span>预计文件</span><strong>{{ formatTransferNumber(preview.totalFiles) }}</strong></div>
        <div><span>预计字节</span><strong>{{ formatBytes(preview.totalBytes) }}</strong></div>
        <div><span>样本</span><strong>{{ preview.sampleCount || 0 }}</strong></div>
      </div>
      <StudioTableShell min-width="680px">
        <el-table v-loading="previewLoading" :data="preview?.sample || []" size="small" table-layout="fixed" height="calc(100vh - 220px)">
          <el-table-column label="源路径" min-width="260" show-overflow-tooltip prop="sourcePath" />
          <el-table-column label="目标路径" min-width="260" show-overflow-tooltip prop="targetPath" />
          <el-table-column label="大小" width="110" align="right">
            <template #default="{ row }">{{ formatBytes(row.size) }}</template>
          </el-table-column>
          <el-table-column label="修改时间" width="170">
            <template #default="{ row }">{{ formatTransferDate(Number(row.modifiedAtMillis || 0)) }}</template>
          </el-table-column>
        </el-table>
      </StudioTableShell>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { Plus, Refresh } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type {
  DataSourceOptionView,
  EntityId,
  FileTransferSelectionPreviewView,
  FileTransferTaskDefinitionView,
  FileTransferTaskSaveRequest,
  RuntimeClusterView,
} from "@studio/api-sdk";
import { OverflowActionGroup, StatusPill, StudioTableShell, type OverflowActionItem } from "@studio/ui";
import { studioApi } from "@/api/studio";
import {
  fileTransferStatusLabel,
  fileTransferStatusTone,
  formatBytes,
  formatTransferDate,
  formatTransferNumber,
  isFileTransferDatasourceType,
  normalizeTransferPath,
  toTransferNumber,
} from "@/utils/fileTransfer";

interface TaskEditorForm {
  id?: EntityId;
  name: string;
  code: string;
  runtimeClusterId: EntityId | "";
  sourceDatasourceId: EntityId | "";
  targetDatasourceId: EntityId | "";
  sourceRootPath: string;
  pathsText: string;
  recursive: boolean;
  includeGlobsText: string;
  includeRegex: string;
  excludeGlobsText: string;
  minSize?: number;
  maxSize?: number;
  modifiedRange: string[];
  maxFiles: number;
  targetRootPath: string;
  preserveRelativePath: boolean;
  targetPathTemplate: string;
  conflictPolicy: "FAIL" | "OVERWRITE" | "BACKUP_THEN_OVERWRITE";
  sourceSuccessAction: "KEEP" | "DELETE" | "BACKUP";
  sourceBackupRootPath: string;
  concurrency: number;
  maxRetries: number;
  retryBackoffText: string;
  maxMiBPerSecond: number;
  chunkSizeMiB: number;
  checkpointIntervalMillis: number;
  scheduleEnabled: boolean;
  cronExpression: string;
  timezone: string;
}

const tasks = ref<FileTransferTaskDefinitionView[]>([]);
const runtimeClusters = ref<RuntimeClusterView[]>([]);
const sourceDatasources = ref<DataSourceOptionView[]>([]);
const targetDatasources = ref<DataSourceOptionView[]>([]);
const loading = ref(false);
const saving = ref(false);
const total = ref(0);
const filters = reactive({ keyword: "", status: "" });
const pagination = reactive({ pageNo: 1, pageSize: 20 });
const editorVisible = ref(false);
const editorStep = ref(0);
const form = reactive<TaskEditorForm>(emptyForm());
const previewVisible = ref(false);
const previewLoading = ref(false);
const preview = ref<FileTransferSelectionPreviewView>();

function emptyForm(): TaskEditorForm {
  return {
    name: "",
    code: "",
    runtimeClusterId: "",
    sourceDatasourceId: "",
    targetDatasourceId: "",
    sourceRootPath: "/",
    pathsText: "",
    recursive: true,
    includeGlobsText: "",
    includeRegex: "",
    excludeGlobsText: "",
    minSize: undefined,
    maxSize: undefined,
    modifiedRange: [],
    maxFiles: 100000,
    targetRootPath: "/",
    preserveRelativePath: true,
    targetPathTemplate: "",
    conflictPolicy: "FAIL",
    sourceSuccessAction: "KEEP",
    sourceBackupRootPath: "",
    concurrency: 4,
    maxRetries: 3,
    retryBackoffText: "1, 5, 15",
    maxMiBPerSecond: 0,
    chunkSizeMiB: 8,
    checkpointIntervalMillis: 1000,
    scheduleEnabled: false,
    cronExpression: "0 0 * * * ?",
    timezone: "Asia/Shanghai",
  };
}

async function loadReferenceData() {
  runtimeClusters.value = (await studioApi.runtimeClusters.options()).filter((item) => item.enabled !== false);
}

async function loadTasks() {
  loading.value = true;
  try {
    const page = await studioApi.fileTransfer.tasks.list({
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      keyword: filters.keyword.trim() || undefined,
      status: filters.status || undefined,
    });
    tasks.value = page.items;
    total.value = page.total;
  } catch (error) {
    showError(error, "加载预设任务失败");
  } finally {
    loading.value = false;
  }
}

function searchTasks() {
  pagination.pageNo = 1;
  void loadTasks();
}

function resetFilters() {
  filters.keyword = "";
  filters.status = "";
  searchTasks();
}

function handlePageSizeChange() {
  pagination.pageNo = 1;
  void loadTasks();
}

async function openEditor(task?: FileTransferTaskDefinitionView) {
  Object.assign(form, emptyForm());
  editorStep.value = 0;
  if (task) applyTaskToForm(task);
  editorVisible.value = true;
  await Promise.all([loadSourceDatasources(), loadTargetDatasources()]);
}

function applyTaskToForm(task: FileTransferTaskDefinitionView) {
  form.id = task.id;
  form.name = task.name;
  form.code = task.code;
  form.runtimeClusterId = task.runtimeClusterId ?? task.sourceRuntimeClusterId ?? "";
  form.sourceDatasourceId = task.sourceDatasourceId ?? "";
  form.targetDatasourceId = task.targetDatasourceId ?? "";
  form.sourceRootPath = String(task.selection?.rootPath ?? "/");
  form.pathsText = stringList(task.selection?.paths).join("\n");
  form.recursive = task.selection?.recursive !== false;
  form.includeGlobsText = stringList(task.selection?.includeGlobs).join("\n");
  form.includeRegex = String(task.selection?.includeRegex ?? "");
  form.excludeGlobsText = stringList(task.selection?.excludeGlobs).join("\n");
  form.minSize = optionalNumber(task.selection?.minSize);
  form.maxSize = optionalNumber(task.selection?.maxSize);
  const modifiedAfter = optionalNumber(task.selection?.modifiedAfterMillis);
  const modifiedBefore = optionalNumber(task.selection?.modifiedBeforeMillis);
  form.modifiedRange = modifiedAfter != null && modifiedBefore != null
    ? [formatEditorDate(modifiedAfter), formatEditorDate(modifiedBefore)]
    : [];
  form.maxFiles = optionalNumber(task.selection?.maxFiles) ?? 100000;
  form.targetRootPath = String(task.mapping?.targetRootPath ?? "/");
  form.preserveRelativePath = task.mapping?.preserveRelativePath !== false;
  form.targetPathTemplate = String(task.mapping?.targetPathTemplate ?? "");
  form.conflictPolicy = String(task.policy?.conflictPolicy ?? "FAIL") as TaskEditorForm["conflictPolicy"];
  form.sourceSuccessAction = String(task.policy?.sourceSuccessAction ?? "KEEP") as TaskEditorForm["sourceSuccessAction"];
  form.sourceBackupRootPath = String(task.policy?.sourceBackupRootPath ?? "");
  form.concurrency = optionalNumber(task.runtime?.concurrency) ?? 4;
  form.maxRetries = optionalNumber(task.runtime?.maxRetries) ?? 3;
  form.retryBackoffText = stringList(task.runtime?.retryBackoffMillis).map((item) => String(toTransferNumber(item) / 1000)).join(", ") || "1, 5, 15";
  form.maxMiBPerSecond = Math.round((optionalNumber(task.runtime?.maxBytesPerSecond) ?? 0) / 1024 / 1024);
  form.chunkSizeMiB = Math.max(1, Math.round((optionalNumber(task.runtime?.chunkSizeBytes) ?? 8 * 1024 * 1024) / 1024 / 1024));
  form.checkpointIntervalMillis = optionalNumber(task.runtime?.checkpointIntervalMillis) ?? 1000;
  form.scheduleEnabled = Boolean(task.schedule?.enabled);
  form.cronExpression = task.schedule?.cronExpression || "0 0 * * * ?";
  form.timezone = task.schedule?.timezone || "Asia/Shanghai";
}

async function loadSourceDatasources() {
  sourceDatasources.value = await fileDatasourceOptions(form.runtimeClusterId);
}

async function loadTargetDatasources() {
  targetDatasources.value = await fileDatasourceOptions(form.runtimeClusterId);
}

async function handleTaskRuntimeClusterChange() {
  form.sourceDatasourceId = "";
  form.targetDatasourceId = "";
  sourceDatasources.value = [];
  targetDatasources.value = [];
  if (form.runtimeClusterId === "") return;
  await Promise.all([loadSourceDatasources(), loadTargetDatasources()]);
}

async function fileDatasourceOptions(clusterId: EntityId | "") {
  if (clusterId === "") return [];
  return (await studioApi.datasources.optionsByRuntimeCluster(clusterId))
    .filter((item) => item.enabled !== false && item.executable !== false && isFileTransferDatasourceType(item.typeCode));
}

function buildPayload(): FileTransferTaskSaveRequest {
  if (!form.name.trim() || !form.code.trim()) throw new Error("请填写任务名称和编码");
  if ([form.runtimeClusterId, form.sourceDatasourceId, form.targetDatasourceId].some((item) => item === "")) {
    throw new Error("请选择运行集群、来源数据源和目标数据源");
  }
  if (!form.sourceRootPath.trim() || !form.targetRootPath.trim()) throw new Error("请填写源根路径和目标根路径");
  if (form.sourceSuccessAction === "BACKUP" && !form.sourceBackupRootPath.trim()) throw new Error("请填写源端备份根路径");
  const retryBackoffMillis = splitValues(form.retryBackoffText).map((item) => {
    const seconds = Number(item);
    if (!Number.isFinite(seconds) || seconds < 0) throw new Error("重试退避必须是非负秒数");
    return Math.round(seconds * 1000);
  });
  return {
    id: form.id,
    name: form.name.trim(),
    code: form.code.trim(),
    runtimeClusterId: form.runtimeClusterId as EntityId,
    sourceRuntimeClusterId: form.runtimeClusterId as EntityId,
    sourceDatasourceId: form.sourceDatasourceId as EntityId,
    targetRuntimeClusterId: form.runtimeClusterId as EntityId,
    targetDatasourceId: form.targetDatasourceId as EntityId,
    selection: compact({
      rootPath: normalizeTransferPath(form.sourceRootPath),
      paths: splitValues(form.pathsText),
      recursive: form.recursive,
      includeGlobs: splitValues(form.includeGlobsText),
      includeRegex: form.includeRegex.trim() || undefined,
      excludeGlobs: splitValues(form.excludeGlobsText),
      minSize: form.minSize,
      maxSize: form.maxSize,
      modifiedAfterMillis: form.modifiedRange[0] ? new Date(form.modifiedRange[0]).getTime() : undefined,
      modifiedBeforeMillis: form.modifiedRange[1] ? new Date(form.modifiedRange[1]).getTime() : undefined,
      maxFiles: form.maxFiles,
    }),
    mapping: compact({
      targetRootPath: normalizeTransferPath(form.targetRootPath),
      preserveRelativePath: form.preserveRelativePath,
      targetPathTemplate: form.targetPathTemplate.trim() || undefined,
    }),
    policy: compact({
      conflictPolicy: form.conflictPolicy,
      checksumAlgorithm: "SHA-256",
      sourceSuccessAction: form.sourceSuccessAction,
      sourceBackupRootPath: form.sourceSuccessAction === "BACKUP" ? normalizeTransferPath(form.sourceBackupRootPath) : undefined,
    }),
    runtime: {
      concurrency: form.concurrency,
      maxRetries: form.maxRetries,
      retryBackoffMillis,
      chunkSizeBytes: form.chunkSizeMiB * 1024 * 1024,
      checkpointIntervalMillis: form.checkpointIntervalMillis,
      maxBytesPerSecond: form.maxMiBPerSecond * 1024 * 1024,
    },
    schedule: {
      enabled: form.scheduleEnabled,
      cronExpression: form.scheduleEnabled ? form.cronExpression.trim() : undefined,
      timezone: form.timezone,
    },
  };
}

async function saveTask(publish: boolean) {
  saving.value = true;
  try {
    const payload = buildPayload();
    let saved = form.id
      ? await studioApi.fileTransfer.tasks.update(form.id, payload)
      : await studioApi.fileTransfer.tasks.create(payload);
    form.id = saved.id;
    if (publish && saved.id) saved = await studioApi.fileTransfer.tasks.publish(saved.id);
    ElMessage.success(publish ? "任务已保存并发布" : "任务草稿已保存");
    editorVisible.value = false;
    await loadTasks();
  } catch (error) {
    showError(error, "保存文件传输任务失败");
  } finally {
    saving.value = false;
  }
}

async function validateCurrentTask() {
  if (!form.id) return;
  try {
    await studioApi.fileTransfer.tasks.validate(form.id);
    ElMessage.success("任务配置校验通过");
  } catch (error) {
    showError(error, "任务配置校验失败");
  }
}

async function previewCurrentTask(task?: FileTransferTaskDefinitionView) {
  const taskId = task?.id ?? form.id;
  if (!taskId) return;
  previewVisible.value = true;
  previewLoading.value = true;
  preview.value = undefined;
  try {
    preview.value = await studioApi.fileTransfer.tasks.preview(taskId, { limit: 200 });
  } catch (error) {
    showError(error, "预览文件选择失败");
  } finally {
    previewLoading.value = false;
  }
}

function taskActions(task: FileTransferTaskDefinitionView): OverflowActionItem[] {
  return [
    { key: "edit", label: "编辑", type: "primary", onClick: () => openEditor(task) },
    { key: "preview", label: "预览文件", onClick: () => previewCurrentTask(task) },
    { key: "validate", label: "校验", onClick: () => validateTask(task) },
    task.status === "ONLINE"
      ? { key: "offline", label: "下线", type: "warning", onClick: () => offlineTask(task) }
      : { key: "publish", label: "发布", type: "success", onClick: () => publishTask(task) },
    { key: "trigger", label: "立即执行", type: "primary", disabled: task.status !== "ONLINE", onClick: () => triggerTask(task) },
    { key: "delete", label: "删除", type: "danger", disabled: task.status === "ONLINE", onClick: () => deleteTask(task) },
  ];
}

async function validateTask(task: FileTransferTaskDefinitionView) {
  if (!task.id) return;
  await taskMutation(() => studioApi.fileTransfer.tasks.validate(task.id as EntityId), "任务配置校验通过", false);
}

async function publishTask(task: FileTransferTaskDefinitionView) {
  if (!task.id) return;
  await taskMutation(() => studioApi.fileTransfer.tasks.publish(task.id as EntityId), "任务已发布");
}

async function offlineTask(task: FileTransferTaskDefinitionView) {
  if (!task.id) return;
  await taskMutation(() => studioApi.fileTransfer.tasks.offline(task.id as EntityId), "任务已下线");
}

async function triggerTask(task: FileTransferTaskDefinitionView) {
  if (!task.id) return;
  await taskMutation(() => studioApi.fileTransfer.tasks.trigger(task.id as EntityId), "任务已进入执行队列", false);
}

async function deleteTask(task: FileTransferTaskDefinitionView) {
  if (!task.id) return;
  try {
    await ElMessageBox.confirm(`确认删除任务“${task.name}”？`, "删除任务", { type: "warning" });
  } catch {
    return;
  }
  await taskMutation(() => studioApi.fileTransfer.tasks.delete(task.id as EntityId), "任务已删除");
}

async function taskMutation(action: () => Promise<unknown>, message: string, reload = true) {
  try {
    await action();
    ElMessage.success(message);
    if (reload) await loadTasks();
  } catch (error) {
    showError(error, "任务操作失败");
  }
}

function conflictLabel(value: unknown) {
  return ({ FAIL: "冲突失败", OVERWRITE: "覆盖", BACKUP_THEN_OVERWRITE: "备份后覆盖" } as Record<string, string>)[String(value ?? "FAIL")] || String(value ?? "FAIL");
}

function sourceActionLabel(value: unknown) {
  return ({ KEEP: "保留源", DELETE: "删除源", BACKUP: "备份源" } as Record<string, string>)[String(value ?? "KEEP")] || String(value ?? "KEEP");
}

function splitValues(value: string) {
  return value.split(/[\r\n,]+/).map((item) => item.trim()).filter(Boolean);
}

function stringList(value: unknown) {
  return Array.isArray(value) ? value.map(String) : [];
}

function optionalNumber(value: unknown) {
  if (value == null || value === "") return undefined;
  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
}

function compact(value: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined && item !== ""));
}

function formatEditorDate(value: number) {
  const date = new Date(value);
  const pad = (item: number) => String(item).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function showError(error: unknown, fallback: string) {
  ElMessage.error(error instanceof Error && error.message ? error.message : fallback);
}

onMounted(async () => {
  try {
    await Promise.all([loadReferenceData(), loadTasks()]);
  } catch (error) {
    showError(error, "加载文件传输任务失败");
  }
});
</script>

<style scoped>
.transfer-tasks {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.transfer-tasks__toolbar > div:last-child,
.transfer-task-filters,
.editor-footer,
.editor-footer > div,
.editor-final-actions,
.inline-number-range {
  display: flex;
  align-items: center;
  gap: 8px;
}

.transfer-task-filters > .el-input {
  width: min(280px, 42vw);
}

.transfer-task-filters > .el-select {
  width: 150px;
}

.task-main-cell,
.stack-cell {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.task-main-cell .el-button {
  justify-content: flex-start;
  min-width: 0;
  overflow: hidden;
}

.task-main-cell span,
.stack-cell span:last-child {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.stack-cell span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
}

.transfer-task-steps {
  margin-bottom: 16px;
  overflow-x: auto;
}

.transfer-task-form {
  min-height: 390px;
  max-height: calc(100vh - 260px);
  overflow-y: auto;
  padding: 2px 4px 8px;
}

.editor-step-grid,
.endpoint-editor-grid {
  display: grid;
  gap: 12px 16px;
  min-width: 0;
}

.editor-step-grid.columns-2,
.endpoint-editor-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.endpoint-editor-grid section {
  padding: 14px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
}

.endpoint-editor-grid h4 {
  margin: 0 0 12px;
}

.editor-wide-alert,
.editor-final-actions {
  grid-column: 1 / -1;
}

.inline-number-range {
  width: 100%;
}

.inline-number-range .el-input-number {
  flex: 1 1 0;
  min-width: 0;
  width: auto;
}

.editor-final-actions {
  justify-content: flex-start;
  min-height: 72px;
  padding: 14px 0;
}

.editor-footer {
  justify-content: space-between;
}

.preview-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  margin-bottom: 14px;
  overflow: hidden;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  background: var(--studio-border);
}

.preview-summary > div {
  display: grid;
  gap: 8px;
  padding: 14px;
  background: var(--studio-surface-strong);
}

.preview-summary span {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.preview-summary strong {
  font-size: 22px;
}

@media (max-width: 820px) {
  .transfer-tasks__toolbar,
  .transfer-task-filters,
  .transfer-tasks__toolbar > div:last-child {
    align-items: stretch;
    flex-direction: column;
    width: 100%;
  }

  .transfer-task-filters > .el-input,
  .transfer-task-filters > .el-select {
    width: 100%;
  }

  .editor-step-grid.columns-2,
  .endpoint-editor-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .editor-wide-alert,
  .editor-final-actions {
    grid-column: 1;
  }
}

@media (max-width: 520px) {
  .editor-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .editor-footer > div {
    flex-wrap: wrap;
  }

  .preview-summary {
    grid-template-columns: 1fr;
  }
}
</style>
