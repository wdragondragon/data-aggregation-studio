<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.datasources.heading") }}</h3>
        <p>{{ t("web.datasources.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" :disabled="!authStore.currentProjectId" @click="openCreate">{{ t("common.newDatasource") }}</el-button>
        <el-button plain @click="loadPage">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.datasources.tableTitle')" :description="t('web.datasources.tableDescription')">
      <StudioTableShell min-width="1460px">
        <el-table :data="datasources" border>
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">
              {{ getPaginatedRowNumber(datasourcePagination, $index) }}
            </template>
          </el-table-column>
          <el-table-column prop="name" :label="t('web.datasources.nameColumn')" min-width="180" />
        <el-table-column prop="typeCode" :label="t('web.datasources.typeColumn')" width="120" />
        <el-table-column label="所属项目" min-width="170">
          <template #default="{ row }">
            <div class="stack-cell">
              <span>{{ resolveProjectLabel(row.projectId) }}</span>
              <span class="cell-subtle">{{ isSharedDatasource(row) ? "共享来源" : "当前项目" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('web.datasources.enabledColumn')" width="110" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="row.enabled ? t('common.on') : t('common.off')" :tone="row.enabled ? 'success' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.datasources.executableColumn')" width="130" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="row.executable ? t('common.managed') : t('common.unmanaged')" :tone="row.executable ? 'success' : 'warning'" />
          </template>
        </el-table-column>
        <el-table-column :label="t('web.datasources.connectionStatusColumn')" min-width="210" align="center" header-align="center">
          <template #default="{ row }">
            <div class="stack-cell status-stack">
              <StatusPill :label="connectionStatusLabel(row)" :tone="connectionStatusTone(row)" />
              <button class="connection-trend" type="button" :title="t('web.datasources.connectionTrendTitle')" @click="openConnectionHistory(row)">
                <span
                  v-for="(point, index) in connectionTrendPoints(row)"
                  :key="`${row.id ?? row.connectionFingerprint ?? 'row'}:${index}`"
                  class="connection-trend__dot"
                  :class="connectionTrendPointClass(point)"
                />
              </button>
              <span v-if="row.lastConnectionTestAt" class="cell-subtle">{{ formatConnectionTestAt(row.lastConnectionTestAt) }}</span>
              <span v-if="row.connectionStale" class="cell-subtle">{{ t("web.datasources.connectionStale") }}</span>
              <el-tooltip v-if="row.lastConnectionTestMessage" :content="row.lastConnectionTestMessage" placement="top">
                <span class="cell-subtle status-message">{{ row.lastConnectionTestMessage }}</span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="170" align="center" header-align="center" />
        <el-table-column prop="updatedAt" :label="t('web.datasources.updatedColumn')" min-width="170" align="center" header-align="center" />
        <el-table-column :label="t('web.datasources.actionsColumn')" width="150" align="center" header-align="center" fixed="right">
          <template #default="{ row }">
            <OverflowActionGroup :items="buildDatasourceActions(row)" />
          </template>
        </el-table-column>
      </el-table>
      </StudioTableShell>
      <div class="table-pagination">
        <el-pagination
          v-model:current-page="datasourcePagination.page"
          v-model:page-size="datasourcePagination.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="datasourceTotal"
          @current-change="handleDatasourcePageChange"
          @size-change="handleDatasourcePageSizeChange"
        />
      </div>
    </SectionCard>

    <el-drawer v-model="drawerOpen" size="70%" :title="form.id ? t('web.datasources.drawerEditTitle') : t('web.datasources.drawerCreateTitle')">
      <div class="studio-grid columns-2">
        <SectionCard :title="t('web.datasources.identityTitle')" :description="t('web.datasources.identityDescription')">
          <div class="studio-form-grid">
            <el-form-item :label="t('web.datasources.datasourceName')">
              <el-input v-model="form.name" :placeholder="t('web.datasources.datasourceNamePlaceholder')" />
            </el-form-item>
            <el-form-item :label="t('web.datasources.pluginType')">
              <el-select v-model="form.typeCode" :placeholder="t('web.datasources.pluginTypePlaceholder')" @change="handleTypeChange">
                <el-option
                  v-for="pluginName in sourceTypeOptions"
                  :key="pluginName"
                  :label="pluginName"
                  :value="pluginName"
                />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('web.datasources.schemaBinding')">
              <el-select v-model="form.schemaVersionId" clearable :placeholder="t('web.datasources.schemaBindingPlaceholder')">
                <el-option
                  v-for="schema in datasourceSchemas"
                  :key="schema.id"
                  :label="`${schema.schemaName} v${schema.versionNumber ?? 1}`"
                  :value="schema.currentVersionId ?? schema.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('web.datasources.executionSurface')">
              <el-switch v-model="form.executable" inline-prompt :active-text="t('common.managedShort')" :inactive-text="t('common.unmanagedShort')" />
            </el-form-item>
            <el-form-item :label="t('web.datasources.enabled')">
              <el-switch v-model="form.enabled" inline-prompt :active-text="t('common.on')" :inactive-text="t('common.off')" />
            </el-form-item>
          </div>
        </SectionCard>

        <SectionCard :title="t('web.datasources.executionSupportTitle')" :description="t('web.datasources.executionSupportDescription')">
          <div class="soft-panel">
            <p><strong>{{ t("web.datasources.executableSourceFamilies") }}</strong></p>
            <div class="tag-row">
              <StatusPill
                v-for="item in executableDatasourceTypes"
                :key="item"
                :label="item"
                tone="success"
              />
            </div>
            <p class="capability-note">
              {{ t("web.datasources.currentTypeLabel") }} <strong>{{ form.typeCode || t("web.datasources.notSelected") }}</strong>
              {{
                form.typeCode && executableDatasourceTypes.includes(form.typeCode)
                  ? t("web.datasources.currentTypeExecutable")
                  : t("web.datasources.currentTypeCatalogOnly")
              }}
            </p>
          </div>
        </SectionCard>
      </div>

      <SectionCard :title="t('web.datasources.healthConfigTitle')" :description="t('web.datasources.healthConfigDescription')">
        <div class="studio-form-grid">
          <el-form-item :label="t('web.datasources.manualTimeout')">
            <el-input-number
              v-model="form.manualConnectionTestTimeoutSeconds"
              :min="1"
              :max="120"
              :placeholder="t('web.datasources.timeoutPlaceholder')"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item :label="t('web.datasources.scheduledTimeout')">
            <el-input-number
              v-model="form.scheduledConnectionTestTimeoutSeconds"
              :min="1"
              :max="120"
              :placeholder="t('web.datasources.timeoutPlaceholder')"
              controls-position="right"
            />
          </el-form-item>
        </div>
      </SectionCard>

      <SectionCard :title="t('web.datasources.technicalTitle')" :description="t('web.datasources.technicalDescription')">
        <MetaFormRenderer
          :fields="technicalFields"
          :model-value="form.technicalMetadata"
          @update:model-value="form.technicalMetadata = $event"
        />
      </SectionCard>

      <SectionCard :title="t('web.datasources.businessTitle')" :description="t('web.datasources.businessDescription')">
        <div class="meta-section-stack">
          <div v-if="businessSections.length === 0" class="soft-panel empty-hint section-empty">
            {{ t("web.models.metaSectionEmpty") }}
          </div>

          <div
            v-for="section in businessSections"
            :key="section.key"
            class="soft-panel datasource-meta-section"
          >
            <div class="datasource-meta-section__header">
              <div>
                <strong>{{ section.title }}</strong>
                <p>{{ section.description }}</p>
              </div>
              <StatusPill
                :label="section.displayMode === 'MULTIPLE' ? t('web.metadata.displayMultiple') : t('web.metadata.displaySingle')"
                tone="success"
              />
            </div>

            <template v-if="section.displayMode === 'MULTIPLE'">
              <div class="multiple-section-actions">
                <el-button type="primary" plain @click="appendSectionRow(section)">{{ t("common.addRow") }}</el-button>
              </div>
              <el-table :data="sectionRows(section)" border>
                <el-table-column
                  v-for="field in section.fields"
                  :key="field.fieldKey"
                  :label="field.fieldName"
                  min-width="150"
                >
                  <template #default="{ row, $index }">
                    <component
                      :is="resolveRowEditorComponent(field)"
                      v-bind="resolveRowEditorProps(field)"
                      :model-value="row[field.fieldKey]"
                      @update:model-value="updateSectionRowField(section, $index, field.fieldKey, $event)"
                    >
                      <template v-if="field.componentType === 'SELECT'">
                        <el-option
                          v-for="option in field.options ?? []"
                          :key="option"
                          :label="option"
                          :value="option"
                        />
                      </template>
                    </component>
                  </template>
                </el-table-column>
            <el-table-column :label="t('web.metadata.actions')" width="100" fixed="right">
                  <template #default="{ $index }">
                    <el-button link type="danger" @click="removeSectionRow(section, $index)">{{ t("common.remove") }}</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <MetaFormRenderer
              v-else
              :fields="section.fields"
              :model-value="sectionModelValue(section)"
              @update:model-value="updateSectionModelValue(section, $event)"
            />
          </div>
        </div>
      </SectionCard>

      <div class="drawer-actions">
        <el-button @click="drawerOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button @click="testCurrent">{{ t("web.datasources.testConnection") }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveDatasource">{{ t("common.save") }}</el-button>
      </div>

      <SectionCard v-if="testResult" :title="t('web.datasources.testResultTitle')" :description="t('web.datasources.testResultDescription')">
        <pre class="json-block studio-mono">{{ prettyJson(testResult) }}</pre>
      </SectionCard>
    </el-drawer>

    <el-dialog v-model="discoverDialogOpen" :title="t('web.datasources.discoveredModelsTitle')" width="62%">
      <el-table :data="discoveredModels" border>
        <el-table-column prop="name" :label="t('web.datasources.modelNameColumn')" min-width="180" />
        <el-table-column :label="t('web.datasources.modelKindColumn')" width="120" align="center" header-align="center">
          <template #default="{ row }">
            {{ formatModelKind(t, row.modelKind) }}
          </template>
        </el-table-column>
        <el-table-column prop="physicalLocator" :label="t('web.datasources.physicalLocatorColumn')" min-width="220" />
      </el-table>
    </el-dialog>

    <el-dialog v-model="connectionHistoryDialogOpen" :title="connectionHistoryDialogTitle" width="760px">
      <div class="history-dialog">
        <div class="history-summary">
          <StatusPill :label="historyDatasource ? connectionStatusLabel(historyDatasource) : t('web.datasources.connectionUnknown')" :tone="historyDatasource ? connectionStatusTone(historyDatasource) : 'neutral'" />
          <span class="cell-subtle">{{ t("web.datasources.connectionHistorySubtitle") }}</span>
        </div>
        <el-table v-loading="connectionHistoryLoading" :data="connectionHistoryRecords" border>
          <el-table-column :label="t('web.datasources.historyTimeColumn')" min-width="180">
            <template #default="{ row }">{{ formatConnectionTestAt(row.testedAt || row.endedAt) || "--" }}</template>
          </el-table-column>
          <el-table-column :label="t('web.datasources.historyStatusColumn')" width="120" align="center" header-align="center">
            <template #default="{ row }">
              <StatusPill :label="recordStatusLabel(row)" :tone="recordStatusTone(row)" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.datasources.historyModeColumn')" width="120" align="center" header-align="center">
            <template #default="{ row }">{{ probeModeLabel(row.probeMode) }}</template>
          </el-table-column>
          <el-table-column :label="t('web.datasources.historyDurationColumn')" width="120" align="center" header-align="center">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column :label="t('web.datasources.historyTimeoutColumn')" width="120" align="center" header-align="center">
            <template #default="{ row }">{{ formatTimeoutSeconds(row.timeoutSeconds) }}</template>
          </el-table-column>
          <el-table-column prop="message" :label="t('web.datasources.historyMessageColumn')" min-width="220" show-overflow-tooltip />
        </el-table>
        <div v-if="!connectionHistoryLoading && connectionHistoryRecords.length === 0" class="soft-panel empty-hint history-empty">
          {{ t("web.datasources.connectionHistoryEmpty") }}
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElInput, ElInputNumber, ElMessage, ElMessageBox, ElSelect, ElSwitch } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  CapabilityMatrix,
  ConnectionTestResult,
  DataSourceDefinition,
  DataSourceListView,
  DatasourceConnectionTestRecordView,
  DatasourceConnectionTrendPointView,
  EntityId,
  MetadataFieldDefinition,
  MetadataSchemaDefinition,
  ModelDiscoveryResult,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { OverflowActionGroup, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";
import { getPaginatedRowNumber } from "@/composables/useClientPagination";
import {
  ensureBusinessMetaModelEntries,
  getBusinessMetaModelRows,
  getBusinessMetaModelValues,
  parseMetaModelSchema,
  sameEntityId,
  setBusinessMetaModelRows,
  setBusinessMetaModelValues,
} from "@/utils/metaModel";
import { cloneDeep, formatModelKind, isSharedFromAnotherProject, prettyJson, resolveProjectName } from "@/utils/studio";

interface DataSourceForm extends DataSourceDefinition {
  name: string;
  typeCode: string;
  technicalMetadata: Record<string, unknown>;
  businessMetadata: Record<string, unknown>;
}

interface DatasourceBusinessSection {
  key: string;
  schema: MetadataSchemaDefinition;
  title: string;
  description: string;
  displayMode: "SINGLE" | "MULTIPLE";
  fields: MetadataFieldDefinition[];
}

const { t } = useI18n();
const authStore = useAuthStore();

const datasources = ref<DataSourceListView[]>([]);
const datasourceTotal = ref(0);
const datasourcePagination = reactive({ page: 1, pageSize: 20 });
const schemas = ref<MetadataSchemaDefinition[]>([]);
const schemaDetails = ref<Record<string, MetadataSchemaDefinition>>({});
const capabilityMatrix = reactive<CapabilityMatrix>({
  executableSourceTypes: [],
});
const executableDatasourceTypes = computed(() => capabilityMatrix.executableDatasourceTypes ?? capabilityMatrix.executableSourceTypes);
const drawerOpen = ref(false);
const saving = ref(false);
const testResult = ref<ConnectionTestResult | null>(null);
const testingDatasourceIds = ref<string[]>([]);
const discoverDialogOpen = ref(false);
const discoveredModels = ref<ModelDiscoveryResult["models"]>([]);
const connectionHistoryDialogOpen = ref(false);
const connectionHistoryLoading = ref(false);
const historyDatasource = ref<DataSourceListView | null>(null);
const connectionHistoryRecords = ref<DatasourceConnectionTestRecordView[]>([]);
const form = reactive<DataSourceForm>({
  name: "",
  typeCode: "",
  enabled: true,
  executable: false,
  schemaVersionId: undefined,
  manualConnectionTestTimeoutSeconds: undefined,
  scheduledConnectionTestTimeoutSeconds: undefined,
  technicalMetadata: {},
  businessMetadata: {},
});

const sourceTypeOptions = computed(() =>
  Array.from(new Set((capabilityMatrix.sourceCapabilities ?? []).map((item) => item.typeCode).filter(Boolean)))
);

const fallbackTechnicalFields = computed<MetadataFieldDefinition[]>(() => {
  const businessDefault: MetadataFieldDefinition[] = [];
  if (["mysql8", "postgres", "oracle", "dm"].includes(form.typeCode)) {
    return [
      { fieldKey: "host", fieldName: "Host", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "port", fieldName: "Port", scope: "TECHNICAL", componentType: "NUMBER", valueType: "INTEGER", required: true, defaultValue: "3306" },
      { fieldKey: "database", fieldName: "Database", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "userName", fieldName: t("web.login.usernameLabel"), scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "password", fieldName: t("web.login.passwordLabel"), scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", required: true },
      { fieldKey: "usePool", fieldName: "Use Connection Pool", scope: "TECHNICAL", componentType: "SWITCH", valueType: "BOOLEAN", defaultValue: "true" },
      ...businessDefault,
    ];
  }
  if (form.typeCode === "odps") {
    return [
      { fieldKey: "host", fieldName: "MaxCompute Endpoint", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "database", fieldName: "Project 名", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "userName", fieldName: "AccessKey ID", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "password", fieldName: "AccessKey Secret", scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", required: true, sensitive: true },
      { fieldKey: "extraParams", fieldName: "全局参数", scope: "TECHNICAL", componentType: "JSON_EDITOR", valueType: "JSON", defaultValue: "{}" },
      ...businessDefault,
    ];
  }
  if (["kafka", "rocketmq", "rabbitmq"].includes(form.typeCode)) {
    return [
      { fieldKey: "brokers", fieldName: "Brokers", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING", required: true },
      { fieldKey: "username", fieldName: t("web.login.usernameLabel"), scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
      { fieldKey: "password", fieldName: t("web.login.passwordLabel"), scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", sensitive: true },
    ];
  }
  if (form.typeCode === "http") {
    return [
      { fieldKey: "url", fieldName: "URL", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
    ];
  }
  if (["minio", "ftp", "sftp"].includes(form.typeCode)) {
    return [
      { fieldKey: "endpoint", fieldName: "Endpoint", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "bucket", fieldName: "Bucket or Path", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
      { fieldKey: "accessKey", fieldName: "Access Key", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
      { fieldKey: "secretKey", fieldName: "Secret Key", scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", sensitive: true },
    ];
  }
  return [
    { fieldKey: "endpoint", fieldName: "Endpoint", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING", required: true },
    { fieldKey: "username", fieldName: t("web.login.usernameLabel"), scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
    { fieldKey: "password", fieldName: t("web.login.passwordLabel"), scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", sensitive: true },
  ];
});

const datasourceSchemas = computed(() =>
  schemas.value.filter((schema) => {
    if (schema.objectType !== "datasource" || schema.typeCode !== form.typeCode) {
      return false;
    }
    const config = parseMetaModelSchema(schema).config;
    return config.domain === "TECHNICAL" && config.metaModelCode === "source";
  }),
);
const businessSchemas = computed(() =>
  schemas.value.filter((schema) => {
    const config = parseMetaModelSchema(schema).config;
    return form.typeCode && config.domain === "BUSINESS" && config.metaModelCode === "source";
  }).sort((left, right) => businessSchemaLabel(left).localeCompare(businessSchemaLabel(right))),
);
const businessSections = computed(() => businessSchemas.value.map(buildBusinessSection));
const connectionHistoryDialogTitle = computed(() =>
  historyDatasource.value
    ? `${historyDatasource.value.name} / ${t("web.datasources.connectionHistoryTitle")}`
    : t("web.datasources.connectionHistoryTitle"),
);

function buildDatasourceActions(datasource: DataSourceListView) {
  const shared = isSharedDatasource(datasource);
  const testing = isTestingDatasource(datasource);
  return [
    { key: "edit", label: t("common.edit"), type: "primary", disabled: shared, onClick: () => editDatasource(datasource) },
    { key: "test", label: testing ? t("web.datasources.testingConnection") : t("common.test"), type: "success", disabled: !canTestDatasource(datasource) || testing, onClick: () => testDatasource(datasource) },
    { key: "discover", label: t("common.discover"), type: "warning", onClick: () => discoverModels(datasource) },
    { key: "delete", label: t("common.delete"), type: "danger", disabled: shared, onClick: () => deleteDatasource(datasource) },
  ];
}

function resolveProjectLabel(projectId?: string | number) {
  return resolveProjectName(authStore.projects, projectId);
}

function isSharedDatasource(datasource: DataSourceListView) {
  return isSharedFromAnotherProject(authStore.currentProjectId, datasource.projectId);
}

function canTestDatasource(datasource: DataSourceListView) {
  return Boolean(datasource.id && datasource.enabled && datasource.executable);
}

function datasourceTestKey(datasource: DataSourceListView) {
  return String(datasource.id ?? "");
}

function isTestingDatasource(datasource: DataSourceListView) {
  const key = datasourceTestKey(datasource);
  return key.length > 0 && testingDatasourceIds.value.includes(key);
}

function setDatasourceTesting(datasource: DataSourceListView, testing: boolean) {
  const key = datasourceTestKey(datasource);
  if (!key) {
    return;
  }
  if (testing) {
    testingDatasourceIds.value = Array.from(new Set([...testingDatasourceIds.value, key]));
    return;
  }
  testingDatasourceIds.value = testingDatasourceIds.value.filter((item) => item !== key);
}

function connectionStatusLabel(datasource: DataSourceListView) {
  if (!canTestDatasource(datasource)) {
    return t("web.datasources.connectionNotTestable");
  }
  if (datasource.connectionTesting || isTestingDatasource(datasource)) {
    return t("web.datasources.connectionTesting");
  }
  if (datasource.connectionStatus === "AVAILABLE") {
    return t("web.datasources.connectionAvailable");
  }
  if (datasource.connectionStatus === "UNAVAILABLE") {
    return t("web.datasources.connectionUnavailable");
  }
  return t("web.datasources.connectionUnknown");
}

function connectionStatusTone(datasource: DataSourceListView) {
  if (!canTestDatasource(datasource)) {
    return "neutral";
  }
  if (datasource.connectionTesting || isTestingDatasource(datasource)) {
    return "primary";
  }
  if (datasource.connectionStatus === "AVAILABLE") {
    return "success";
  }
  if (datasource.connectionStatus === "UNAVAILABLE") {
    return "danger";
  }
  return "warning";
}

function connectionTrendPoints(datasource: DataSourceListView) {
  const records = [...(datasource.recentConnectionTests ?? [])]
    .sort((left, right) => String(left.testedAt || left.endedAt || "").localeCompare(String(right.testedAt || right.endedAt || "")))
    .slice(-10);
  const padding = Array.from({ length: Math.max(0, 10 - records.length) }, () => ({} as DatasourceConnectionTrendPointView));
  return [...padding, ...records];
}

function connectionTrendPointClass(point: DatasourceConnectionTrendPointView) {
  if (point.status === "AVAILABLE") {
    return "connection-trend__dot--success";
  }
  if (point.status === "UNAVAILABLE") {
    return "connection-trend__dot--danger";
  }
  if (point.status === "UNKNOWN") {
    return "connection-trend__dot--warning";
  }
  return "connection-trend__dot--empty";
}

function recordStatusLabel(record: DatasourceConnectionTestRecordView) {
  if (record.status === "AVAILABLE") {
    return t("web.datasources.connectionAvailable");
  }
  if (record.status === "UNAVAILABLE") {
    return t("web.datasources.connectionUnavailable");
  }
  return t("web.datasources.connectionUnknown");
}

function recordStatusTone(record: DatasourceConnectionTestRecordView) {
  if (record.status === "AVAILABLE") {
    return "success";
  }
  if (record.status === "UNAVAILABLE") {
    return "danger";
  }
  return "warning";
}

function probeModeLabel(value?: string) {
  const normalized = String(value ?? "").toUpperCase();
  if (normalized === "SCHEDULED") {
    return t("web.datasources.scheduledProbe");
  }
  if (normalized === "MANUAL") {
    return t("web.datasources.manualProbe");
  }
  return value || "--";
}

function formatDuration(value?: number | string | null) {
  const duration = normalizeNumber(value);
  return duration == null ? "--" : `${duration} ms`;
}

function formatTimeoutSeconds(value?: number) {
  return typeof value === "number" ? `${value} s` : "--";
}

function normalizeNumber(value?: number | string | null) {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : null;
  }
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function formatConnectionTestAt(value?: string) {
  if (!value) {
    return "";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
}

const matchedSchema = computed(
  () =>
    datasourceSchemas.value.find(
      (schema) => sameEntityId(schema.id, form.schemaVersionId) || sameEntityId(schema.currentVersionId, form.schemaVersionId),
    ) ?? datasourceSchemas.value[0],
);
const technicalFields = computed(
  () => matchedSchema.value?.fields?.filter((field) => field.scope !== "BUSINESS") ?? fallbackTechnicalFields.value,
);

function resetForm() {
  form.id = undefined;
  form.name = "";
  form.typeCode = "";
  form.enabled = true;
  form.executable = false;
  form.schemaVersionId = undefined;
  form.manualConnectionTestTimeoutSeconds = undefined;
  form.scheduledConnectionTestTimeoutSeconds = undefined;
  form.technicalMetadata = {};
  form.businessMetadata = {};
  testResult.value = null;
}

function openCreate() {
  resetForm();
  drawerOpen.value = true;
}

async function editDatasource(item: DataSourceListView) {
  if (!item.id) {
    return;
  }
  try {
    const detail = await studioApi.datasources.get(item.id);
    Object.assign(form, cloneDeep(detail));
    await ensureDatasourceSchemaDetails(form.typeCode);
    applyBusinessMetadataDefaults();
    testResult.value = null;
    drawerOpen.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.loadFailed"));
  }
}

async function handleTypeChange() {
  try {
    await ensureDatasourceSchemaDetails(form.typeCode);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.loadFailed"));
    return;
  }
  const schema = datasourceSchemas.value[0];
  form.schemaVersionId = schema?.currentVersionId ?? schema?.id;
  form.executable = executableDatasourceTypes.value.includes(form.typeCode);
  form.technicalMetadata = buildDefaultMetadata(
    schema?.fields?.filter((field) => field.scope !== "BUSINESS") ?? fallbackTechnicalFields.value,
  );
  form.businessMetadata = {};
  applyBusinessMetadataDefaults();
}

function buildDefaultMetadata(fields: MetadataFieldDefinition[]) {
  const defaults: Record<string, unknown> = {};
  for (const field of fields) {
    if (!field.fieldKey || field.defaultValue === undefined || field.defaultValue === null || field.defaultValue === "") {
      continue;
    }
    defaults[field.fieldKey] = parseDefaultValue(field);
  }
  return defaults;
}

function parseDefaultValue(field: MetadataFieldDefinition) {
  const rawValue = field.defaultValue;
  if (rawValue === undefined || rawValue === null) {
    return undefined;
  }
  if (field.valueType === "BOOLEAN") {
    return rawValue === "true";
  }
  if (field.valueType === "INTEGER" || field.valueType === "LONG" || field.valueType === "DECIMAL") {
    const numberValue = Number(rawValue);
    return Number.isNaN(numberValue) ? rawValue : numberValue;
  }
  if (field.valueType === "JSON" || field.valueType === "OBJECT" || field.valueType === "ARRAY") {
    try {
      return JSON.parse(rawValue);
    } catch (error) {
      return rawValue;
    }
  }
  return rawValue;
}

function buildBusinessSection(schema: MetadataSchemaDefinition): DatasourceBusinessSection {
  const parsed = parseMetaModelSchema(schema);
  return {
    key: `business:${schema.id ?? schema.schemaCode}`,
    schema,
    title: `${parsed.config.directoryName || parsed.config.directoryCode || t("web.datasources.businessTitle")} / ${schema.schemaName}`,
    description: parsed.plainDescription || schema.schemaCode,
    displayMode: (parsed.config.displayMode ?? "SINGLE") as "SINGLE" | "MULTIPLE",
    fields: (schema.fields ?? []).filter((field) => field.scope === "BUSINESS"),
  };
}

function businessSchemaLabel(schema: MetadataSchemaDefinition) {
  const config = parseMetaModelSchema(schema).config;
  return `${config.directoryName || config.directoryCode || "business"} / ${schema.schemaName}`;
}

function sectionModelValue(section: DatasourceBusinessSection) {
  return getBusinessMetaModelValues(form.businessMetadata, section.schema);
}

function updateSectionModelValue(section: DatasourceBusinessSection, value: Record<string, unknown>) {
  form.businessMetadata = setBusinessMetaModelValues(form.businessMetadata, section.schema, value);
}

function sectionRows(section: DatasourceBusinessSection) {
  return getBusinessMetaModelRows(form.businessMetadata, section.schema);
}

function setSectionRows(section: DatasourceBusinessSection, rows: Record<string, unknown>[]) {
  form.businessMetadata = setBusinessMetaModelRows(form.businessMetadata, section.schema, rows);
}

function appendSectionRow(section: DatasourceBusinessSection) {
  const rows = sectionRows(section);
  rows.push(buildDefaultMetadata(section.fields));
  setSectionRows(section, rows);
}

function removeSectionRow(section: DatasourceBusinessSection, index: number) {
  const rows = sectionRows(section);
  rows.splice(index, 1);
  setSectionRows(section, rows);
}

function updateSectionRowField(section: DatasourceBusinessSection, index: number, fieldKey: string, value: unknown) {
  const rows = sectionRows(section);
  rows[index] = {
    ...(rows[index] ?? {}),
    [fieldKey]: value,
  };
  setSectionRows(section, rows);
}

function resolveRowEditorComponent(field: MetadataFieldDefinition) {
  switch (field.componentType) {
    case "SWITCH":
      return ElSwitch;
    case "SELECT":
      return ElSelect;
    case "NUMBER":
      return ElInputNumber;
    default:
      return ElInput;
  }
}

function resolveRowEditorProps(field: MetadataFieldDefinition) {
  switch (field.componentType) {
    case "SWITCH":
      return {
        inlinePrompt: true,
        activeText: t("common.on"),
        inactiveText: t("common.off"),
      };
    case "SELECT":
      return {
        clearable: true,
        filterable: true,
        placeholder: field.placeholder ?? field.fieldName,
      };
    case "NUMBER":
      return {
        controlsPosition: "right",
      };
    case "TEXTAREA":
    case "JSON_EDITOR":
    case "SQL_EDITOR":
    case "CODE_EDITOR":
      return {
        type: "textarea",
        rows: 2,
        placeholder: field.placeholder ?? field.fieldName,
      };
    default:
      return {
        placeholder: field.placeholder ?? field.fieldName,
      };
  }
}

function applyBusinessMetadataDefaults() {
  form.businessMetadata = ensureBusinessMetaModelEntries(form.businessMetadata, businessSchemas.value);
  for (const section of businessSections.value) {
    if (section.displayMode === "MULTIPLE") {
      setSectionRows(section, sectionRows(section));
      continue;
    }
    updateSectionModelValue(section, {
      ...buildDefaultMetadata(section.fields),
      ...sectionModelValue(section),
    });
  }
}

function toSchemaSummary(schema: MetadataSchemaDefinition) {
  return {
    ...schema,
    fields: [],
  };
}

function compareSchema(left: MetadataSchemaDefinition, right: MetadataSchemaDefinition) {
  return (left.schemaCode || "").localeCompare(right.schemaCode || "");
}

function upsertSchemaDetails(details: MetadataSchemaDefinition[]) {
  if (details.length === 0) {
    return;
  }
  const nextDetails = { ...schemaDetails.value };
  const nextSchemas = [...schemas.value];
  for (const detail of details) {
    if (!detail.id) {
      continue;
    }
    nextDetails[String(detail.id)] = detail;
    const index = nextSchemas.findIndex((schema) => sameEntityId(schema.id, detail.id));
    if (index >= 0) {
      nextSchemas[index] = detail;
    } else {
      nextSchemas.push(detail);
    }
  }
  schemaDetails.value = nextDetails;
  schemas.value = nextSchemas.sort(compareSchema);
}

function datasourceSchemaDetailCandidates(typeCode?: string) {
  if (!typeCode) {
    return [] as MetadataSchemaDefinition[];
  }
  return schemas.value.filter((schema) => {
    const config = parseMetaModelSchema(schema).config;
    if (schema.objectType === "datasource"
        && schema.typeCode === typeCode
        && config.domain === "TECHNICAL"
        && config.metaModelCode === "source") {
      return true;
    }
    return config.domain === "BUSINESS" && config.metaModelCode === "source";
  });
}

async function ensureDatasourceSchemaDetails(typeCode?: string) {
  const ids = datasourceSchemaDetailCandidates(typeCode)
    .map((schema) => schema.id)
    .filter((id): id is EntityId => id != null)
    .filter((id) => !schemaDetails.value[String(id)]);
  if (ids.length === 0) {
    return;
  }
  const details = await studioApi.metaSchemas.details(ids);
  upsertSchemaDetails(details);
}

function removeDatasourceRow(item: DataSourceListView) {
  datasources.value = datasources.value.filter((datasource) => !sameEntityId(datasource.id, item.id));
  datasourceTotal.value = Math.max(0, datasourceTotal.value - 1);
  if (datasources.value.length === 0 && datasourceTotal.value > 0 && datasourcePagination.page > 1) {
    datasourcePagination.page -= 1;
    void loadDatasources();
  }
}

async function loadDatasources() {
  const page = await studioApi.datasources.listPage({
    pageNo: datasourcePagination.page,
    pageSize: datasourcePagination.pageSize,
  });
  const maxPage = page.total > 0 ? Math.ceil(page.total / datasourcePagination.pageSize) : 1;
  if (datasourcePagination.page > maxPage) {
    datasourcePagination.page = maxPage;
    await loadDatasources();
    return;
  }
  datasources.value = page.items;
  datasourceTotal.value = page.total;
}

async function loadPage() {
  try {
    const [datasourceData, schemaData, capabilityData] = await Promise.all([
      studioApi.datasources.listPage({
        pageNo: datasourcePagination.page,
        pageSize: datasourcePagination.pageSize,
      }),
      studioApi.metaSchemas.list({ includeFields: false }),
      studioApi.catalog.capabilities(),
    ]);
    datasources.value = datasourceData.items;
    datasourceTotal.value = datasourceData.total;
    schemas.value = schemaData.map(toSchemaSummary);
    schemaDetails.value = {};
    capabilityMatrix.executableSourceTypes = capabilityData.executableSourceTypes;
    capabilityMatrix.executableTargetTypes = capabilityData.executableTargetTypes;
    capabilityMatrix.executableDatasourceTypes = capabilityData.executableDatasourceTypes;
    capabilityMatrix.sourceCapabilities = capabilityData.sourceCapabilities;
    if (drawerOpen.value && form.typeCode) {
      await ensureDatasourceSchemaDetails(form.typeCode);
      applyBusinessMetadataDefaults();
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.loadFailed"));
  }
}

async function handleDatasourcePageChange(page: number) {
  datasourcePagination.page = page;
  try {
    await loadDatasources();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.loadFailed"));
  }
}

async function handleDatasourcePageSizeChange(pageSize: number) {
  datasourcePagination.pageSize = pageSize;
  datasourcePagination.page = 1;
  try {
    await loadDatasources();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.loadFailed"));
  }
}

async function saveDatasource(options: { closeAfterSave?: boolean } = {}) {
  saving.value = true;
  try {
    const saved = await studioApi.datasources.save(cloneDeep(form));
    Object.assign(form, saved);
    ElMessage.success(t("web.datasources.saveSuccess"));
    if (options.closeAfterSave !== false) {
      drawerOpen.value = false;
    }
    upsertDatasourceRow(saved);
    return saved;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.saveFailed"));
  } finally {
    saving.value = false;
  }
}

function upsertDatasourceRow(saved: DataSourceDefinition) {
  const next = toDataSourceListView(saved);
  const index = datasources.value.findIndex((item) => item.id === next.id);
  if (index >= 0) {
    Object.assign(datasources.value[index], next);
    return;
  }
  datasources.value = [...datasources.value, next].sort((left, right) => left.name.localeCompare(right.name));
  datasourceTotal.value += 1;
}

function toDataSourceListView(source: DataSourceDefinition): DataSourceListView {
  return {
    id: source.id,
    tenantId: source.tenantId,
    projectId: source.projectId,
    deleted: source.deleted,
    createdAt: source.createdAt,
    updatedAt: source.updatedAt,
    name: source.name,
    typeCode: source.typeCode,
    schemaVersionId: source.schemaVersionId,
    enabled: source.enabled,
    executable: source.executable,
    connectionFingerprint: source.connectionFingerprint,
    connectionStatus: source.connectionStatus,
    lastConnectionTestAt: source.lastConnectionTestAt,
    lastConnectionTestMessage: source.lastConnectionTestMessage,
    lastConnectionTestDurationMs: source.lastConnectionTestDurationMs,
    connectionTesting: source.connectionTesting,
    connectionStale: source.connectionStale,
    nextConnectionProbeAt: source.nextConnectionProbeAt,
    manualConnectionTestTimeoutSeconds: source.manualConnectionTestTimeoutSeconds,
    scheduledConnectionTestTimeoutSeconds: source.scheduledConnectionTestTimeoutSeconds,
    recentConnectionTests: toConnectionTrendPoints(source.recentConnectionTests ?? []),
  };
}

async function testDatasource(item: DataSourceListView) {
  if (!item.id) {
    return;
  }
  setDatasourceTesting(item, true);
  try {
    const result = await studioApi.datasources.test(item.id);
    applyConnectionTestResult(item, result);
    showConnectionTestMessage(result);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.testFailed"));
  } finally {
    setDatasourceTesting(item, false);
  }
}

async function testCurrent() {
  try {
    testResult.value = await studioApi.datasources.testCurrent(cloneDeep(form));
    showConnectionTestMessage(testResult.value);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.testFailed"));
  }
}

function showConnectionTestMessage(result: ConnectionTestResult) {
  if (result.busy) {
    ElMessage.warning(result.message || t("web.datasources.testBusy"));
    return;
  }
  if (result.testing) {
    ElMessage.info(result.message || t("web.datasources.connectionTesting"));
    return;
  }
  if (isConnectionTestSuccessful(result)) {
    ElMessage.success(t("web.datasources.testSuccess"));
    return;
  }
  ElMessage.error(result.message || t("web.datasources.testFailed"));
}

function isConnectionTestSuccessful(result: ConnectionTestResult) {
  if (result.status) {
    return result.status === "AVAILABLE";
  }
  return Boolean(result.success);
}

function applyConnectionTestResult(item: DataSourceListView, result: ConnectionTestResult) {
  const hasPersistedSnapshot = !result.busy && Boolean(result.lastTestAt);
  const status = hasPersistedSnapshot
    ? (result.status ?? (result.success ? "AVAILABLE" : "UNAVAILABLE"))
    : item.connectionStatus;
  const now = result.lastTestAt ?? new Date().toISOString();
  const durationMs = normalizeNumber(result.durationMs);
  const patch: Partial<DataSourceListView> = {
    connectionStatus: status,
    lastConnectionTestAt: hasPersistedSnapshot ? now : item.lastConnectionTestAt,
    lastConnectionTestMessage: hasPersistedSnapshot && typeof result.message === "string" ? result.message : item.lastConnectionTestMessage,
    lastConnectionTestDurationMs: hasPersistedSnapshot && durationMs != null ? durationMs : item.lastConnectionTestDurationMs,
    connectionTesting: Boolean(result.testing),
    connectionStale: Boolean(result.stale),
    nextConnectionProbeAt: result.nextProbeAt,
    recentConnectionTests: result.recentConnectionTests ? toConnectionTrendPoints(result.recentConnectionTests) : item.recentConnectionTests,
  };
  applyConnectionPatch(item, patch);
  if (form.id === item.id) {
    Object.assign(form, patch);
  }
}

function toConnectionTrendPoints(records: DatasourceConnectionTestRecordView[]) {
  return records.map((record) => ({
    status: record.status,
    testedAt: record.testedAt,
    endedAt: record.endedAt,
  }));
}

function applyConnectionPatch(item: DataSourceListView, patch: Partial<DataSourceListView>) {
  const fingerprint = item.connectionFingerprint;
  for (const datasource of datasources.value) {
    if (datasource.id === item.id || (fingerprint && datasource.connectionFingerprint === fingerprint)) {
      Object.assign(datasource, patch);
    }
  }
}

async function discoverModels(item: DataSourceListView) {
  if (!item.id) {
    return;
  }
  try {
    const result = await studioApi.datasources.discover(item.id);
    discoveredModels.value = result.models;
    discoverDialogOpen.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.discoverFailed"));
  }
}

async function openConnectionHistory(item: DataSourceListView) {
  if (!item.id) {
    return;
  }
  historyDatasource.value = item;
  connectionHistoryDialogOpen.value = true;
  connectionHistoryLoading.value = true;
  connectionHistoryRecords.value = [];
  try {
    connectionHistoryRecords.value = await studioApi.datasources.connectionHistory(item.id, { days: 7, limit: 1000 });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.connectionHistoryFailed"));
  } finally {
    connectionHistoryLoading.value = false;
  }
}

async function deleteDatasource(item: DataSourceListView) {
  if (!item.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.datasources.deleteConfirmMessage", { name: item.name }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.datasources.delete(item.id);
    if (form.id === item.id) {
      drawerOpen.value = false;
      resetForm();
    }
    ElMessage.success(t("web.datasources.deleteSuccess"));
    removeDatasourceRow(item);
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.datasources.deleteFailed"));
    }
  }
}

onMounted(loadPage);

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], () => {
  if (authStore.isAuthenticated) {
    datasourcePagination.page = 1;
    loadPage();
  }
});
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.stack-cell {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.cell-subtle {
  color: var(--studio-text-soft);
  font-size: 12px;
}

.status-stack {
  justify-items: center;
}

.status-message {
  display: inline-block;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.connection-trend {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 116px;
  min-height: 18px;
  padding: 2px 4px;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.connection-trend__dot {
  width: 8px;
  height: 8px;
  flex: 0 0 8px;
  border-radius: 50%;
  background: rgba(148, 163, 184, 0.45);
}

.connection-trend__dot--success {
  background: #22c55e;
}

.connection-trend__dot--danger {
  background: #ef4444;
}

.connection-trend__dot--warning {
  background: #f59e0b;
}

.connection-trend__dot--empty {
  background: rgba(148, 163, 184, 0.32);
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.capability-note {
  margin-top: 16px;
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.meta-section-stack,
.datasource-meta-section {
  display: grid;
  gap: 10px;
}

.datasource-meta-section__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.datasource-meta-section__header p {
  display: none;
}

.multiple-section-actions {
  display: flex;
  justify-content: flex-end;
}

.section-empty {
  margin-bottom: 0;
}

.history-dialog {
  display: grid;
  gap: 12px;
}

.history-summary {
  display: flex;
  align-items: center;
  gap: 10px;
}

.history-empty {
  text-align: center;
}
</style>
