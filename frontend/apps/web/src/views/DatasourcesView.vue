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
        <el-table :data="datasources" border highlight-current-row @row-click="setActiveDatasource">
          <el-table-column :label="t('common.sequence')" width="72" align="center" header-align="center">
            <template #default="{ $index }">
              {{ getPaginatedRowNumber(datasourcePagination, $index) }}
            </template>
          </el-table-column>
          <el-table-column prop="name" :label="t('web.datasources.nameColumn')" min-width="180" />
        <el-table-column prop="typeCode" :label="t('web.datasources.typeColumn')" width="120" />
        <el-table-column :label="t('web.datasources.applicableClustersColumn')" min-width="180">
          <template #default="{ row }">
            <div v-if="datasourceClusterLabels(row).length" class="tag-row datasource-cluster-tags">
              <el-tag v-for="cluster in datasourceClusterLabels(row)" :key="cluster" size="small" effect="plain">{{ cluster }}</el-tag>
            </div>
            <span v-else class="cell-subtle">{{ t("web.datasources.noApplicableClusters") }}</span>
          </template>
        </el-table-column>
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
        <el-table-column :label="t('web.datasources.connectionStatusColumn')" min-width="260" align="center" header-align="center">
          <template #default="{ row }">
            <div v-if="row.clusterHealth?.length" class="cluster-health-list">
              <button
                v-for="health in row.clusterHealth"
                :key="String(health.runtimeClusterId)"
                class="cluster-health-item"
                type="button"
                :title="health.lastConnectionTestMessage || t('web.datasources.connectionTrendTitle')"
                @click="openConnectionHistory(row, health.runtimeClusterId)"
              >
                <span class="cluster-health-name">{{ clusterHealthName(health) }}</span>
                <StatusPill :label="clusterHealthStatusLabel(health)" :tone="clusterHealthStatusTone(health)" />
              </button>
            </div>
            <div v-else class="stack-cell status-stack">
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

    <el-drawer v-model="drawerOpen" size="min(1120px, 96vw)" :title="form.id ? t('web.datasources.drawerEditTitle') : t('web.datasources.drawerCreateTitle')">
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
            <el-form-item :label="t('web.datasources.applicableClusters')" class="datasource-cluster-select">
              <el-select
                v-model="form.applicableClusterIds"
                multiple
                filterable
                :disabled="singleRuntimeCluster"
                :placeholder="t('web.datasources.applicableClustersPlaceholder')"
              >
                <el-option
                  v-for="cluster in runtimeClusterSelectionOptions"
                  :key="cluster.id"
                  :label="runtimeClusterLabel(cluster)"
                  :value="String(cluster.id)"
                  :disabled="runtimeClusterOptionDisabled(cluster)"
                />
              </el-select>
              <p v-if="singleRuntimeCluster" class="form-item-hint">{{ t("web.datasources.singleClusterAutoSelected") }}</p>
              <p v-else class="form-item-hint">{{ t("web.datasources.applicableClustersHint") }}</p>
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
          <el-form-item :label="t('web.datasources.testRuntimeCluster')">
            <el-select
              v-model="testRuntimeClusterId"
              clearable
              :disabled="applicableRuntimeClusters.length === 0"
              :placeholder="t('web.datasources.testRuntimeClusterPlaceholder')"
            >
              <el-option
                v-for="cluster in applicableRuntimeClusters"
                :key="cluster.id"
                :label="runtimeClusterLabel(cluster)"
                :value="String(cluster.id)"
              />
            </el-select>
          </el-form-item>
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
          :saved-sensitive-field-keys="form.savedSensitiveFieldKeys"
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
              <StudioTableShell min-width="720px">
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
              </StudioTableShell>
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
        <el-button :disabled="!testRuntimeClusterId" @click="testCurrent">{{ t("web.datasources.testConnection") }}</el-button>
        <el-button :disabled="applicableRuntimeClusters.length === 0" @click="testAllApplicableClusters">
          {{ t("web.datasources.testAllClusters") }}
        </el-button>
        <el-button type="primary" :loading="saving" @click="saveDatasource">{{ t("common.save") }}</el-button>
      </div>

      <SectionCard v-if="clusterTestResults.length" :title="t('web.datasources.clusterTestResults')">
        <StudioTableShell min-width="620px">
        <el-table :data="clusterTestResults" border>
          <el-table-column prop="clusterName" :label="t('web.datasources.testRuntimeCluster')" min-width="180" />
          <el-table-column :label="t('web.datasources.historyStatusColumn')" width="130">
            <template #default="{ row }"><StatusPill :label="recordStatusLabel(row.result)" :tone="recordStatusTone(row.result)" /></template>
          </el-table-column>
          <el-table-column prop="result.message" :label="t('web.datasources.historyMessageColumn')" min-width="260" show-overflow-tooltip />
        </el-table>
        </StudioTableShell>
      </SectionCard>

      <SectionCard v-if="testResult" :title="t('web.datasources.testResultTitle')" :description="t('web.datasources.testResultDescription')">
        <pre class="json-block studio-mono">{{ prettyJson(testResult) }}</pre>
      </SectionCard>
    </el-drawer>

    <el-dialog v-model="discoverClusterDialogOpen" :title="t('web.datasources.discoverClusterDialogTitle')" width="min(520px, 94vw)">
      <el-form-item :label="t('web.datasources.testRuntimeCluster')">
        <el-select v-model="discoverRuntimeClusterId" filterable :placeholder="t('web.datasources.testRuntimeClusterPlaceholder')">
          <el-option v-for="cluster in discoveryRuntimeClusters" :key="cluster.id" :label="runtimeClusterLabel(cluster)" :value="String(cluster.id)" />
        </el-select>
      </el-form-item>
      <template #footer>
        <el-button @click="discoverClusterDialogOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="discovering" :disabled="!discoverRuntimeClusterId" @click="confirmDiscoverModels">{{ t("common.confirm") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="discoverDialogOpen" :title="t('web.datasources.discoveredModelsTitle')" width="min(960px, 94vw)">
      <StudioTableShell min-width="620px">
      <el-table :data="discoveredModels" border>
        <el-table-column prop="name" :label="t('web.datasources.modelNameColumn')" min-width="180" />
        <el-table-column :label="t('web.datasources.modelKindColumn')" width="120" align="center" header-align="center">
          <template #default="{ row }">
            {{ formatModelKind(t, row.modelKind) }}
          </template>
        </el-table-column>
        <el-table-column prop="physicalLocator" :label="t('web.datasources.physicalLocatorColumn')" min-width="220" />
      </el-table>
      </StudioTableShell>
    </el-dialog>

    <el-dialog v-model="connectionHistoryDialogOpen" :title="connectionHistoryDialogTitle" width="min(760px, 94vw)">
      <div class="history-dialog">
        <div class="history-summary">
          <StatusPill
            :label="selectedHistoryClusterHealth ? clusterHealthStatusLabel(selectedHistoryClusterHealth) : t('web.datasources.connectionUnknown')"
            :tone="selectedHistoryClusterHealth ? clusterHealthStatusTone(selectedHistoryClusterHealth) : 'neutral'"
          />
          <span class="cell-subtle">{{ t("web.datasources.connectionHistorySubtitle") }}</span>
          <el-select
            v-model="historyRuntimeClusterId"
            :placeholder="t('web.datasources.testRuntimeClusterPlaceholder')"
            class="history-cluster-select"
            @change="loadConnectionHistory"
          >
            <el-option v-for="cluster in historyRuntimeClusters" :key="cluster.id" :label="runtimeClusterLabel(cluster)" :value="String(cluster.id)" />
          </el-select>
        </div>
        <StudioTableShell min-width="980px">
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
        </StudioTableShell>
        <div v-if="!connectionHistoryLoading && connectionHistoryRecords.length === 0" class="soft-panel empty-hint history-empty">
          {{ t("web.datasources.connectionHistoryEmpty") }}
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { ElInput, ElInputNumber, ElMessage, ElMessageBox, ElSelect, ElSwitch } from "element-plus";
import { useI18n } from "vue-i18n";
import type {
  CapabilityMatrix,
  ConnectionTestResult,
  DataSourceDefinition,
  DataSourceListView,
  DataSourceSaveRequest,
  DatasourceClusterHealthView,
  DatasourceConnectionTestRecordView,
  DatasourceConnectionTrendPointView,
  EntityId,
  MetadataFieldDefinition,
  MetadataSchemaDefinition,
  ModelDiscoveryOptionResult,
  RuntimeClusterView,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { OverflowActionGroup, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { clearAssistantPageContext, setAssistantPageContext, type AssistantPageBusinessObject } from "@/components/assistant/assistantPageContext";
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
import { resolveRuntimeClusterSelectionState } from "@/utils/runtimeClusters";

interface DataSourceForm extends DataSourceDefinition {
  name: string;
  typeCode: string;
  technicalMetadata: Record<string, unknown>;
  businessMetadata: Record<string, unknown>;
  applicableClusterIds: EntityId[];
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
const runtimeClusters = ref<RuntimeClusterView[]>([]);
const schemaDetails = ref<Record<string, MetadataSchemaDefinition>>({});
const capabilityMatrix = reactive<CapabilityMatrix>({
  executableSourceTypes: [],
});
const executableDatasourceTypes = computed(() => capabilityMatrix.executableDatasourceTypes ?? capabilityMatrix.executableSourceTypes);
const drawerOpen = ref(false);
const saving = ref(false);
const testResult = ref<ConnectionTestResult | null>(null);
const clusterTestResults = ref<Array<{ clusterId: EntityId; clusterName: string; result: ConnectionTestResult }>>([]);
const testingDatasourceIds = ref<string[]>([]);
const activeDatasource = ref<DataSourceListView | null>(null);
const discoverDialogOpen = ref(false);
const discoveredModels = ref<ModelDiscoveryOptionResult["models"]>([]);
const discoverClusterDialogOpen = ref(false);
const discoverRuntimeClusterId = ref("");
const discoveryDatasource = ref<DataSourceListView | null>(null);
const discovering = ref(false);
const connectionHistoryDialogOpen = ref(false);
const connectionHistoryLoading = ref(false);
const historyDatasource = ref<DataSourceListView | null>(null);
const connectionHistoryRecords = ref<DatasourceConnectionTestRecordView[]>([]);
const historyRuntimeClusterId = ref("");
const testRuntimeClusterId = ref<string>("");
let pageLoadSequence = 0;
let datasourceListLoadSequence = 0;
let runtimeClusterLoadSequence = 0;
let editLoadSequence = 0;
let connectionHistoryLoadSequence = 0;
let discoverLoadSequence = 0;
let currentTestSequence = 0;
let allClustersTestSequence = 0;
const form = reactive<DataSourceForm>({
  name: "",
  typeCode: "",
  enabled: true,
  executable: false,
  schemaVersionId: undefined,
  manualConnectionTestTimeoutSeconds: undefined,
  scheduledConnectionTestTimeoutSeconds: undefined,
  applicableClusterIds: [],
  applicableClusters: [],
  technicalMetadata: {},
  businessMetadata: {},
});

const authorizedRuntimeClusterIds = computed(() => new Set(
  runtimeClusters.value.filter((cluster) => cluster.id != null).map((cluster) => String(cluster.id)),
));
const runtimeClusterSelectionOptions = computed(() => {
  const result = [...runtimeClusters.value];
  for (const cluster of form.applicableClusters ?? []) {
    if (cluster.id != null && !result.some((item) => String(item.id) === String(cluster.id))) {
      result.push(cluster);
    }
  }
  return result;
});
const hasUnavailableSelectedCluster = computed(() => (
  (form.applicableClusterIds ?? []).some((clusterId) => !authorizedRuntimeClusterIds.value.has(String(clusterId)))
));
const singleRuntimeCluster = computed(() => runtimeClusters.value.length === 1 && !hasUnavailableSelectedCluster.value);
const applicableRuntimeClusters = computed(() => {
  const selected = new Set((form.applicableClusterIds ?? []).map((item) => String(item)));
  return runtimeClusters.value.filter((cluster) => cluster.id != null && selected.has(String(cluster.id)));
});
const discoveryRuntimeClusters = computed(() => datasourceRuntimeClusters(discoveryDatasource.value)
  .filter((cluster) => cluster.id != null && authorizedRuntimeClusterIds.value.has(String(cluster.id))));
const historyRuntimeClusters = computed(() => datasourceRuntimeClusters(historyDatasource.value));
const selectedHistoryClusterHealth = computed(() =>
  (historyDatasource.value?.clusterHealth ?? []).find(
    (health) => String(health.runtimeClusterId ?? "") === historyRuntimeClusterId.value,
  ),
);

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
  if (form.typeCode === "kafka") {
    return [
      { fieldKey: "bootstrap.servers", fieldName: "Bootstrap Servers", scope: "TECHNICAL", componentType: "TEXTAREA", valueType: "STRING", required: true },
      { fieldKey: "username", fieldName: t("web.login.usernameLabel"), scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
      { fieldKey: "password", fieldName: t("web.login.passwordLabel"), scope: "TECHNICAL", componentType: "PASSWORD", valueType: "STRING", sensitive: true },
      { fieldKey: "kerberos", fieldName: "启用 Kerberos", scope: "TECHNICAL", componentType: "SWITCH", valueType: "BOOLEAN", defaultValue: "false" },
      { fieldKey: "principal", fieldName: "Kerberos Principal", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
      { fieldKey: "kerberosKeytabFilePath", fieldName: "Keytab 路径", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
      { fieldKey: "krb5Conf", fieldName: "krb5.conf 路径", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
      { fieldKey: "kerberosDomain", fieldName: "Kerberos 域", scope: "TECHNICAL", componentType: "INPUT", valueType: "STRING" },
    ];
  }
  if (["rocketmq", "rabbitmq"].includes(form.typeCode)) {
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

function datasourceToAssistantObject(datasource: DataSourceListView): AssistantPageBusinessObject {
  return {
    type: "datasource",
    path: "/datasources",
    id: datasource.id,
    name: datasource.name,
    label: datasource.name,
    typeCode: datasource.typeCode,
    status: datasource.connectionStatus,
    metadata: {
      enabled: datasource.enabled,
      executable: datasource.executable,
      projectId: datasource.projectId,
      connectionStale: datasource.connectionStale,
    },
  };
}

function discoveredModelToAssistantObject(model: ModelDiscoveryOptionResult["models"][number]): AssistantPageBusinessObject {
  const record = model as unknown as Record<string, unknown>;
  const physicalLocator = String(model.physicalLocator ?? record.physicalName ?? record.tableName ?? model.name ?? "");
  return {
    type: "physicalTable",
    path: "/datasources:discover",
    id: model.id,
    name: model.name || physicalLocator,
    label: model.name || physicalLocator,
    physicalLocator,
    status: String(model.modelKind ?? record.tableType ?? ""),
    metadata: {
      datasourceId: activeDatasource.value?.id,
      datasourceName: activeDatasource.value?.name,
      tableType: record.tableType,
      comment: record.comment,
    },
  };
}

function updateAssistantPageContext() {
  setAssistantPageContext({
    source: "datasources-view",
    path: "/datasources",
    label: t("web.datasources.heading"),
    summary: activeDatasource.value
      ? `当前数据源页，最近选中或操作的数据源是 ${activeDatasource.value.name}。`
      : "当前数据源页，尚未在页面中选中具体数据源。",
    activeObject: activeDatasource.value ? datasourceToAssistantObject(activeDatasource.value) : undefined,
    selectedObjects: activeDatasource.value ? [datasourceToAssistantObject(activeDatasource.value)] : [],
    visibleObjects: datasources.value.slice(0, 20).map(datasourceToAssistantObject),
    relatedObjects: discoverDialogOpen.value ? discoveredModels.value.slice(0, 50).map(discoveredModelToAssistantObject) : [],
    pagination: {
      pageNo: datasourcePagination.page,
      pageSize: datasourcePagination.pageSize,
      total: datasourceTotal.value,
    },
  });
}

function setActiveDatasource(datasource: DataSourceListView) {
  activeDatasource.value = datasource;
  updateAssistantPageContext();
}

function refreshActiveDatasourceFromVisibleRows() {
  if (activeDatasource.value?.id != null) {
    const matched = datasources.value.find((item) => sameEntityId(item.id, activeDatasource.value?.id));
    if (matched) {
      activeDatasource.value = matched;
    }
  }
  updateAssistantPageContext();
}

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

function runtimeClusterLabel(cluster: RuntimeClusterView) {
  const label = cluster.code ? `${cluster.name || cluster.code} (${cluster.code})` : cluster.name || String(cluster.id ?? "-");
  return runtimeClusterOptionUnavailable(cluster)
    ? `${label} - ${t("web.runtimeClusterSelection.unavailableOption")}`
    : label;
}

function runtimeClusterOptionUnavailable(cluster: RuntimeClusterView) {
  return runtimeClusterSelectionState(cluster).unavailable;
}

function runtimeClusterOptionDisabled(cluster: RuntimeClusterView) {
  return runtimeClusterSelectionState(cluster).disabled;
}

function runtimeClusterSelectionState(cluster: RuntimeClusterView) {
  return resolveRuntimeClusterSelectionState(
    cluster.id,
    form.applicableClusterIds ?? [],
    authorizedRuntimeClusterIds.value,
  );
}

function datasourceClusterLabels(datasource: DataSourceListView) {
  if (datasource.applicableClusters?.length) {
    return datasource.applicableClusters.map(runtimeClusterLabel);
  }
  const selected = new Set((datasource.applicableClusterIds ?? []).map((item) => String(item)));
  return runtimeClusters.value.filter((cluster) => cluster.id != null && selected.has(String(cluster.id))).map(runtimeClusterLabel);
}

function datasourceRuntimeClusters(datasource: DataSourceListView | null) {
  if (!datasource) return [];
  if (datasource.applicableClusters?.length) return datasource.applicableClusters;
  const ids = new Set((datasource.applicableClusterIds ?? []).map((id) => String(id)));
  return runtimeClusters.value.filter((cluster) => cluster.id != null && ids.has(String(cluster.id)));
}

function applySingleRuntimeClusterSelection() {
  if (runtimeClusters.value.length !== 1 || runtimeClusters.value[0]?.id == null) {
    return;
  }
  const clusterId = String(runtimeClusters.value[0].id);
  if (!form.applicableClusterIds?.length) {
    form.applicableClusterIds = [clusterId];
  }
  if (!testRuntimeClusterId.value) {
    testRuntimeClusterId.value = clusterId;
  }
}

watch(() => (form.applicableClusterIds ?? []).map(String), (clusterIds) => {
  currentTestSequence += 1;
  allClustersTestSequence += 1;
  testResult.value = null;
  clusterTestResults.value = [];
  if (!testRuntimeClusterId.value || clusterIds.includes(testRuntimeClusterId.value)) {
    return;
  }
  testRuntimeClusterId.value = clusterIds.length === 1 ? clusterIds[0] : "";
});

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

function clusterHealthName(health: DatasourceClusterHealthView) {
  return health.runtimeClusterCode
    ? `${health.runtimeClusterName || health.runtimeClusterCode} (${health.runtimeClusterCode})`
    : health.runtimeClusterName || String(health.runtimeClusterId ?? "-");
}

function clusterHealthStatusLabel(health: DatasourceClusterHealthView) {
  if (health.connectionTesting) return t("web.datasources.connectionTesting");
  if (health.connectionStatus === "AVAILABLE") return t("web.datasources.connectionAvailable");
  if (health.connectionStatus === "UNAVAILABLE") return t("web.datasources.connectionUnavailable");
  return t("web.datasources.connectionUnknown");
}

function clusterHealthStatusTone(health: DatasourceClusterHealthView) {
  if (health.connectionTesting) return "primary";
  if (health.connectionStatus === "AVAILABLE") return "success";
  if (health.connectionStatus === "UNAVAILABLE") return "danger";
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
const technicalFields = computed(() => {
  const fields = matchedSchema.value?.fields?.filter((field) => field.scope !== "BUSINESS") ?? fallbackTechnicalFields.value;
  if (form.typeCode !== "kafka") {
    return fields;
  }
  // Topic and consumer group identify a model/task, not the shared Kafka
  // connection. Hide legacy schema fields while older server metadata is
  // still present; saveDatasource also strips their values from the payload.
  return fields.filter((field) => !["topic", "group.id", "groupId", "consumerGroup"].includes(field.fieldKey));
});

function resetForm() {
  editLoadSequence += 1;
  currentTestSequence += 1;
  allClustersTestSequence += 1;
  form.id = undefined;
  form.name = "";
  form.typeCode = "";
  form.enabled = true;
  form.executable = false;
  form.schemaVersionId = undefined;
  form.manualConnectionTestTimeoutSeconds = undefined;
  form.scheduledConnectionTestTimeoutSeconds = undefined;
  form.applicableClusterIds = [];
  form.applicableClusters = [];
  form.technicalMetadata = {};
  form.businessMetadata = {};
  testResult.value = null;
  clusterTestResults.value = [];
  testRuntimeClusterId.value = "";
  applySingleRuntimeClusterSelection();
}

function openCreate() {
  resetForm();
  drawerOpen.value = true;
}

async function editDatasource(item: DataSourceListView) {
  if (!item.id) {
    return;
  }
  const sequence = ++editLoadSequence;
  setActiveDatasource(item);
  try {
    const detail = await studioApi.datasources.get(item.id);
    if (sequence !== editLoadSequence) {
      return;
    }
    Object.assign(form, cloneDeep(detail));
    form.applicableClusterIds = (detail.applicableClusterIds ?? detail.applicableClusters?.map((cluster) => cluster.id).filter((id): id is EntityId => id != null) ?? []).map(String);
    testRuntimeClusterId.value = form.applicableClusterIds.length === 1
      ? String(form.applicableClusterIds[0])
      : "";
    applySingleRuntimeClusterSelection();
    await ensureDatasourceSchemaDetails(form.typeCode);
    applyBusinessMetadataDefaults();
    testResult.value = null;
    clusterTestResults.value = [];
    drawerOpen.value = true;
  } catch (error) {
    if (sequence === editLoadSequence) {
      ElMessage.error(error instanceof Error ? error.message : t("web.datasources.loadFailed"));
    }
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
  const sequence = ++datasourceListLoadSequence;
  const pageNo = datasourcePagination.page;
  const page = await studioApi.datasources.listPage({
    pageNo,
    pageSize: datasourcePagination.pageSize,
  });
  if (sequence !== datasourceListLoadSequence) {
    return;
  }
  const maxPage = page.total > 0 ? Math.ceil(page.total / datasourcePagination.pageSize) : 1;
  if (datasourcePagination.page > maxPage) {
    datasourcePagination.page = maxPage;
    await loadDatasources();
    return;
  }
  datasources.value = page.items;
  datasourceTotal.value = page.total;
  refreshActiveDatasourceFromVisibleRows();
}

async function loadPage() {
  const sequence = ++pageLoadSequence;
  const datasourceSequence = ++datasourceListLoadSequence;
  try {
    const [datasourceData, schemaData, capabilityData] = await Promise.all([
      studioApi.datasources.listPage({
        pageNo: datasourcePagination.page,
        pageSize: datasourcePagination.pageSize,
      }),
      studioApi.metaSchemas.list({ includeFields: false }),
      studioApi.catalog.capabilities(),
    ]);
    if (sequence !== pageLoadSequence) {
      return;
    }
    if (datasourceSequence === datasourceListLoadSequence) {
      datasources.value = datasourceData.items;
      datasourceTotal.value = datasourceData.total;
      refreshActiveDatasourceFromVisibleRows();
    }
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
    if (sequence === pageLoadSequence) {
      ElMessage.error(error instanceof Error ? error.message : t("web.datasources.loadFailed"));
    }
  }
}

async function loadRuntimeClusters() {
  const sequence = ++runtimeClusterLoadSequence;
  const projectId = authStore.currentProjectId;
  try {
    const options = await studioApi.runtimeClusters.options(projectId ?? undefined);
    if (sequence !== runtimeClusterLoadSequence || String(authStore.currentProjectId ?? "") !== String(projectId ?? "")) {
      return;
    }
    runtimeClusters.value = options;
    applySingleRuntimeClusterSelection();
  } catch {
    if (sequence !== runtimeClusterLoadSequence) {
      return;
    }
    // Keep the datasource center usable during a rolling upgrade before the cluster module is deployed.
    runtimeClusters.value = [];
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
  if (runtimeClusters.value.length > 0 && form.applicableClusterIds.length === 0) {
    ElMessage.warning(t("web.datasources.applicableClustersRequired"));
    return;
  }
  const payload: DataSourceSaveRequest = cloneDeep(form);
  if (form.id != null) {
    try {
      const impact = await studioApi.datasources.clusterBindingImpact(form.id, form.applicableClusterIds);
      const affected = impact.affectedResources ?? [];
      if (affected.length > 0) {
        const preview = affected.slice(0, 5)
          .map((item) => `${item.resourceName || `${item.resourceType ?? "RESOURCE"}#${item.resourceId ?? "-"}`} (project ${item.projectId ?? "-"})`)
          .join("\n");
        await ElMessageBox.confirm(
          `${t("web.datasources.clusterImpactConfirmMessage", { count: affected.length })}${preview ? `\n\n${preview}` : ""}`,
          t("web.datasources.clusterImpactConfirmTitle"),
          { type: "warning", distinguishCancelAndClose: true },
        );
        payload.confirmClusterBindingImpact = true;
      }
    } catch (error) {
      if (error === "cancel" || error === "close") {
        return;
      }
      ElMessage.error(error instanceof Error ? error.message : t("web.datasources.saveFailed"));
      return;
    }
  }
  saving.value = true;
  try {
    if (form.typeCode === "kafka") {
      // Topic and consumer group are model/task settings. Strip legacy values
      // that may still be present when editing an older datasource record.
      delete payload.technicalMetadata.topic;
      delete payload.technicalMetadata["group.id"];
      delete payload.technicalMetadata.groupId;
      delete payload.technicalMetadata.consumerGroup;
    }
    const saved = await studioApi.datasources.save(payload);
    Object.assign(form, saved);
    // Public save responses never include secrets. Replace rather than merge
    // metadata so a newly entered password does not remain in the form state.
    form.technicalMetadata = cloneDeep(saved.technicalMetadata ?? {});
    form.applicableClusterIds = (saved.applicableClusterIds ?? saved.applicableClusters?.map((cluster) => cluster.id).filter((id): id is EntityId => id != null) ?? []).map(String);
    form.applicableClusters = saved.applicableClusters ?? [];
    applySingleRuntimeClusterSelection();
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
  const index = datasources.value.findIndex((item) => sameEntityId(item.id, saved.id));
  const next = toDataSourceListView(saved, index >= 0 ? datasources.value[index] : undefined);
  if (index >= 0) {
    Object.assign(datasources.value[index], next);
    return;
  }
  datasources.value = [...datasources.value, next].sort((left, right) => left.name.localeCompare(right.name));
  datasourceTotal.value += 1;
}

function toDataSourceListView(source: DataSourceDefinition, previous?: DataSourceListView): DataSourceListView {
  const previousHealthByClusterId = new Map(
    (previous?.clusterHealth ?? [])
      .filter((item) => item.runtimeClusterId != null)
      .map((item) => [String(item.runtimeClusterId), item]),
  );
  const applicableClusterIds = source.applicableClusterIds
    ?? source.applicableClusters?.map((cluster) => cluster.id).filter((id): id is EntityId => id != null)
    ?? [];
  const clusterHealth = applicableClusterIds.map((runtimeClusterId) => {
    const cluster = source.applicableClusters?.find((item) => sameEntityId(item.id, runtimeClusterId));
    const previousHealth = previousHealthByClusterId.get(String(runtimeClusterId));
    return {
      ...previousHealth,
      runtimeClusterId,
      runtimeClusterCode: cluster?.code ?? previousHealth?.runtimeClusterCode,
      runtimeClusterName: cluster?.name ?? previousHealth?.runtimeClusterName,
      connectionStatus: previousHealth?.connectionStatus ?? "UNKNOWN",
    } satisfies DatasourceClusterHealthView;
  });
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
    applicableClusterIds: source.applicableClusterIds,
    applicableClusters: source.applicableClusters,
    clusterHealth,
    recentConnectionTests: toConnectionTrendPoints(source.recentConnectionTests ?? []),
  };
}

async function testDatasource(item: DataSourceListView) {
  if (!item.id) {
    return;
  }
  setActiveDatasource(item);
  setDatasourceTesting(item, true);
  try {
    const clusters = datasourceRuntimeClusters(item);
    if (clusters.length === 0) {
      ElMessage.warning(t("web.datasources.applicableClustersRequired"));
      return;
    }
    const results: ConnectionTestResult[] = [];
    for (const cluster of clusters) {
      if (cluster.id == null) continue;
      const result = await studioApi.datasources.test(item.id, cluster.id);
      results.push(result);
      applyClusterConnectionTestResult(item, cluster.id, result);
    }
    const aggregate = aggregateConnectionResults(results);
    applyConnectionTestResult(item, aggregate);
    showConnectionTestMessage(aggregate);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.datasources.testFailed"));
  } finally {
    setDatasourceTesting(item, false);
  }
}

async function testCurrent() {
  if (!testRuntimeClusterId.value) {
    ElMessage.warning(t("web.datasources.testRuntimeClusterPlaceholder"));
    return;
  }
  const runtimeClusterId = testRuntimeClusterId.value;
  const sequence = ++currentTestSequence;
  try {
    const result = await studioApi.datasources.testCurrent(cloneDeep(form), runtimeClusterId);
    if (sequence !== currentTestSequence || testRuntimeClusterId.value !== runtimeClusterId) {
      return;
    }
    testResult.value = result;
    const cluster = applicableRuntimeClusters.value.find((item) => String(item.id) === runtimeClusterId);
    clusterTestResults.value = [{
      clusterId: runtimeClusterId,
      clusterName: cluster ? runtimeClusterLabel(cluster) : runtimeClusterId,
      result,
    }];
    showConnectionTestMessage(result);
  } catch (error) {
    if (sequence === currentTestSequence) {
      ElMessage.error(error instanceof Error ? error.message : t("web.datasources.testFailed"));
    }
  }
}

async function testAllApplicableClusters() {
  const sequence = ++allClustersTestSequence;
  const clusters = [...applicableRuntimeClusters.value];
  const payload = cloneDeep(form);
  clusterTestResults.value = [];
  for (const cluster of clusters) {
    if (cluster.id == null) continue;
    try {
      const result = await studioApi.datasources.testCurrent(payload, cluster.id);
      if (sequence !== allClustersTestSequence) {
        return;
      }
      clusterTestResults.value.push({ clusterId: cluster.id, clusterName: runtimeClusterLabel(cluster), result });
    } catch (error) {
      if (sequence !== allClustersTestSequence) {
        return;
      }
      clusterTestResults.value.push({
        clusterId: cluster.id,
        clusterName: runtimeClusterLabel(cluster),
        result: { success: false, status: "UNKNOWN", message: error instanceof Error ? error.message : t("web.datasources.testFailed") },
      });
    }
  }
  if (sequence !== allClustersTestSequence) {
    return;
  }
  const aggregate = aggregateConnectionResults(clusterTestResults.value.map((item) => item.result));
  testResult.value = aggregate;
  showConnectionTestMessage(aggregate);
}

function aggregateConnectionResults(results: ConnectionTestResult[]) {
  const statuses = results.map((item) => item.status ?? (item.success ? "AVAILABLE" : "UNAVAILABLE"));
  const status = statuses.every((item) => item === "AVAILABLE")
    ? "AVAILABLE"
    : statuses.some((item) => item === "UNAVAILABLE") ? "UNAVAILABLE" : "UNKNOWN";
  return {
    success: status === "AVAILABLE",
    status,
    message: results.map((item) => item.message).filter(Boolean).join("; ") || status,
    lastTestAt: new Date().toISOString(),
  } as ConnectionTestResult;
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

function applyClusterConnectionTestResult(item: DataSourceListView, runtimeClusterId: EntityId, result: ConnectionTestResult) {
  const cluster = datasourceRuntimeClusters(item).find((candidate) => String(candidate.id) === String(runtimeClusterId));
  const health: DatasourceClusterHealthView = (item.clusterHealth ?? []).find((candidate) => String(candidate.runtimeClusterId) === String(runtimeClusterId)) ?? {
    runtimeClusterId,
    runtimeClusterCode: cluster?.code,
    runtimeClusterName: cluster?.name,
  };
  health.connectionStatus = result.status ?? (result.success ? "AVAILABLE" : "UNAVAILABLE");
  health.lastConnectionTestAt = result.lastTestAt;
  health.lastConnectionTestMessage = result.message;
  health.lastConnectionTestDurationMs = result.durationMs;
  health.connectionTesting = Boolean(result.testing);
  health.connectionStale = Boolean(result.stale);
  health.nextConnectionProbeAt = result.nextProbeAt;
  if (!(item.clusterHealth ?? []).some((candidate) => String(candidate.runtimeClusterId) === String(runtimeClusterId))) {
    item.clusterHealth = [...(item.clusterHealth ?? []), health];
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
  setActiveDatasource(item);
  discoveryDatasource.value = item;
  const clusters = discoveryRuntimeClusters.value;
  if (clusters.length === 0) {
    ElMessage.warning(t("web.datasources.discoverClusterRequired"));
    return;
  }
  if (clusters.length > 1) {
    discoverRuntimeClusterId.value = "";
    discoverClusterDialogOpen.value = true;
    return;
  }
  discoverRuntimeClusterId.value = String(clusters[0]?.id ?? "");
  await executeDiscoverModels();
}

async function confirmDiscoverModels() {
  if (!discoverRuntimeClusterId.value) {
    ElMessage.warning(t("web.datasources.discoverClusterRequired"));
    return;
  }
  if (await executeDiscoverModels()) {
    discoverClusterDialogOpen.value = false;
  }
}

async function executeDiscoverModels() {
  const item = discoveryDatasource.value;
  const runtimeClusterId = discoverRuntimeClusterId.value;
  if (!item?.id || !runtimeClusterId) {
    return false;
  }
  const sequence = ++discoverLoadSequence;
  discovering.value = true;
  try {
    const result = await studioApi.datasources.discoverOptions(item.id, { runtimeClusterId });
    if (sequence !== discoverLoadSequence
      || String(discoveryDatasource.value?.id ?? "") !== String(item.id)
      || discoverRuntimeClusterId.value !== runtimeClusterId) {
      return false;
    }
    discoveredModels.value = result.models;
    discoverDialogOpen.value = true;
    updateAssistantPageContext();
    return true;
  } catch (error) {
    if (sequence === discoverLoadSequence) {
      ElMessage.error(error instanceof Error ? error.message : t("web.datasources.discoverFailed"));
    }
    return false;
  } finally {
    if (sequence === discoverLoadSequence) {
      discovering.value = false;
    }
  }
}

async function openConnectionHistory(item: DataSourceListView, runtimeClusterId?: EntityId) {
  if (!item.id) {
    return;
  }
  setActiveDatasource(item);
  historyDatasource.value = item;
  connectionHistoryDialogOpen.value = true;
  connectionHistoryRecords.value = [];
  const clusters = datasourceRuntimeClusters(item);
  historyRuntimeClusterId.value = runtimeClusterId != null
    ? String(runtimeClusterId)
    : clusters.length === 1 ? String(clusters[0]?.id ?? "") : "";
  if (historyRuntimeClusterId.value) {
    await loadConnectionHistory();
  }
}

async function loadConnectionHistory() {
  if (!historyDatasource.value?.id || !historyRuntimeClusterId.value) {
    connectionHistoryLoadSequence += 1;
    connectionHistoryLoading.value = false;
    connectionHistoryRecords.value = [];
    return;
  }
  const datasourceId = historyDatasource.value.id;
  const runtimeClusterId = historyRuntimeClusterId.value;
  const sequence = ++connectionHistoryLoadSequence;
  connectionHistoryLoading.value = true;
  try {
    const records = await studioApi.datasources.connectionHistory(datasourceId, {
      days: 7,
      limit: 1000,
      runtimeClusterId,
    });
    if (sequence === connectionHistoryLoadSequence
      && String(historyDatasource.value?.id ?? "") === String(datasourceId)
      && historyRuntimeClusterId.value === runtimeClusterId) {
      connectionHistoryRecords.value = records;
    }
  } catch (error) {
    if (sequence === connectionHistoryLoadSequence) {
      ElMessage.error(error instanceof Error ? error.message : t("web.datasources.connectionHistoryFailed"));
    }
  } finally {
    if (sequence === connectionHistoryLoadSequence) {
      connectionHistoryLoading.value = false;
    }
  }
}

async function deleteDatasource(item: DataSourceListView) {
  if (!item.id) {
    return;
  }
  setActiveDatasource(item);
  try {
    const impact = await studioApi.datasources.clusterBindingImpact(item.id, []);
    const affected = impact.affectedResources ?? [];
    const preview = affected.slice(0, 5)
      .map((resource) => resource.resourceName || `${resource.resourceType ?? "RESOURCE"}#${resource.resourceId ?? "-"}`)
      .join("\n");
    const message = affected.length > 0
      ? `${t("web.datasources.deleteImpactConfirmMessage", { count: affected.length })}${preview ? `\n\n${preview}` : ""}`
      : t("web.datasources.deleteConfirmMessage", { name: item.name });
    await ElMessageBox.confirm(
      message,
      affected.length > 0 ? t("web.datasources.deleteImpactConfirmTitle") : t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.datasources.delete(item.id);
    if (form.id === item.id) {
      drawerOpen.value = false;
      resetForm();
    }
    ElMessage.success(t("web.datasources.deleteSuccess"));
    removeDatasourceRow(item);
    if (sameEntityId(activeDatasource.value?.id, item.id)) {
      activeDatasource.value = null;
      updateAssistantPageContext();
    }
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.datasources.deleteFailed"));
    }
  }
}

onMounted(async () => {
  await Promise.all([loadPage(), loadRuntimeClusters()]);
});

onBeforeUnmount(() => clearAssistantPageContext("datasources-view"));

watch(
  [
    datasources,
    activeDatasource,
    discoveredModels,
    discoverDialogOpen,
    () => datasourcePagination.page,
    () => datasourcePagination.pageSize,
    () => datasourceTotal.value,
  ],
  updateAssistantPageContext,
  { deep: true, immediate: true },
);

watch([() => authStore.currentTenantId, () => authStore.currentProjectId], () => {
  if (authStore.isAuthenticated) {
    editLoadSequence += 1;
    discoverLoadSequence += 1;
    connectionHistoryLoadSequence += 1;
    currentTestSequence += 1;
    allClustersTestSequence += 1;
    drawerOpen.value = false;
    discoverClusterDialogOpen.value = false;
    discoverDialogOpen.value = false;
    connectionHistoryDialogOpen.value = false;
    datasourcePagination.page = 1;
    loadPage();
    loadRuntimeClusters();
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

.cluster-health-list {
  display: grid;
  gap: 6px;
  width: 100%;
}

.cluster-health-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 2px 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.cluster-health-name {
  overflow: hidden;
  color: var(--studio-text-soft);
  font-size: 12px;
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

.datasource-cluster-tags {
  gap: 6px;
}

.datasource-cluster-select :deep(.el-select) {
  width: 100%;
}

.form-item-hint {
  margin: 6px 0 0;
  color: var(--studio-text-soft);
  font-size: 12px;
  line-height: 1.45;
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
  flex-wrap: wrap;
}

.history-cluster-select {
  width: min(280px, 100%);
  margin-left: auto;
}

.history-empty {
  text-align: center;
}

@media (max-width: 640px) {
  .drawer-actions,
  .datasource-meta-section__header {
    align-items: stretch;
    flex-direction: column;
  }

  .drawer-actions .el-button,
  .multiple-section-actions .el-button {
    width: 100%;
    margin-left: 0;
  }

  .table-pagination,
  .history-summary {
    justify-content: flex-start;
    overflow-x: auto;
  }

  .history-cluster-select {
    width: 100%;
    margin-left: 0;
  }
}
</style>
