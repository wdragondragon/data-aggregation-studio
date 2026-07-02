<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.metadata.heading") }}</h3>
        <p>{{ t("web.metadata.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button v-if="canManageMetaSchemas" plain @click="syncAllTechnical">{{ t("common.syncAll") }}</el-button>
        <el-button plain :loading="pageLoading" @click="loadPage">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <div class="studio-grid meta-model-layout">
      <SectionCard :title="t('web.metadata.treeTitle')" :description="t('web.metadata.treeDescription')">
        <el-tree
          node-key="id"
          :data="treeData"
          :props="{ label: 'label', children: 'children' }"
          :expand-on-click-node="false"
          highlight-current
          @node-click="handleNodeClick"
        >
          <template #default="slotProps">
            <div class="tree-node">
              <span>{{ slotProps?.data?.label ?? "" }}</span>
              <StatusPill
                v-if="slotProps?.data?.kind === 'leaf'"
                :label="formatStatusLabel(t, slotProps?.data?.schema?.status ?? (slotProps?.data?.required ? 'DRAFT' : 'UNKNOWN'))"
                :tone="slotProps?.data?.schema ? toneFromStatus(slotProps.data.schema.status) : 'warning'"
              />
            </div>
          </template>
        </el-tree>
      </SectionCard>

      <SectionCard :title="t('web.metadata.detailTitle')" :description="t('web.metadata.detailDescription')">
        <template #actions>
          <el-button
            v-if="canManageMetaSchemas && selectedNode?.kind === 'technical-root'"
            plain
            @click="syncAllTechnical"
          >
            {{ t("common.syncAll") }}
          </el-button>
          <el-button
            v-if="canManageMetaSchemas && selectedNode?.kind === 'technical-type'"
            plain
            @click="syncTechnical(selectedNode.datasourceType)"
          >
            {{ t("common.sync") }}
          </el-button>
          <el-button
            v-if="canManageMetaSchemas && selectedNode && canCreateFromNode(selectedNode)"
            type="primary"
            @click="openCreateFromNode(selectedNode)"
          >
            {{ t("common.newMetaModel") }}
          </el-button>
          <el-button
            v-if="canManageMetaSchemas && selectedNode?.kind === 'leaf' && selectedNode.schema"
            plain
            @click="editSchema(selectedNode.schema)"
          >
            {{ t("common.edit") }}
          </el-button>
          <el-button
            v-if="canManageMetaSchemas && selectedNode?.kind === 'leaf' && selectedNode.schema?.id"
            plain
            @click="publishSchema(selectedNode.schema)"
          >
            {{ t("common.publish") }}
          </el-button>
          <el-button
            v-if="canManageMetaSchemas && selectedNode?.kind === 'leaf' && selectedNode.schema?.id"
            plain
            @click="deleteSchema(selectedNode.schema)"
          >
            {{ t("common.delete") }}
          </el-button>
        </template>

        <div v-if="!selectedNode" class="soft-panel empty-hint">
          {{ t("web.metadata.nodeInstruction") }}
        </div>

        <template v-else>
          <div class="soft-panel detail-head">
            <div>
              <strong>{{ selectedNode.label }}</strong>
              <p>{{ detailDescription }}</p>
            </div>
            <StatusPill
              :label="detailStatusLabel"
              :tone="detailStatusTone"
            />
          </div>

          <el-descriptions :column="2" border class="meta-descriptions">
            <el-descriptions-item :label="t('web.metadata.datasourceType')">
              {{ selectedNode.datasourceType || "-" }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('web.metadata.businessDirectory')">
              {{ selectedNode.directoryName || selectedNode.directoryCode || "-" }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('web.metadata.metaModelCode')">
              {{ selectedNode.metaModelCode || "-" }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('web.metadata.displayMode')">
              {{ formatDisplayMode(selectedNode.displayMode) }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('web.metadata.syncStrategy')">
              {{ selectedNode.syncStrategy || "-" }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('web.metadata.requiredModel')">
              {{ selectedNode.required ? t("common.yes") : t("common.no") }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('web.metadata.schemaCode')">
              {{ selectedNode.schema?.schemaCode || "-" }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('web.metadata.typeCode')">
              {{ selectedNode.schema?.typeCode || "-" }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('web.metadata.runtimeRole')">
              {{ selectedNode.runtimeRole || parseNodeRuntimeConfig(selectedNode).role || "-" }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('web.metadata.pluginType')">
              {{ selectedNode.pluginType || parseNodeRuntimeConfig(selectedNode).pluginType || "-" }}
            </el-descriptions-item>
          </el-descriptions>

          <div v-if="selectedNode.kind === 'leaf' && !selectedNode.schema" class="soft-panel warning-hint">
            {{ t("web.metadata.missingMetaModel") }}
          </div>

          <SectionCard
            v-if="selectedNode.kind === 'leaf' && selectedNode.schema"
            :title="t('web.metadata.previewTitle')"
            :description="t('web.metadata.previewDescription')"
          >
            <MetaFormRenderer :fields="selectedSchemaDetail?.fields ?? []" :model-value="previewModel" @update:model-value="previewModel = $event" />
          </SectionCard>
        </template>
      </SectionCard>
    </div>

    <el-drawer
      v-model="drawerOpen"
      size="74%"
      destroy-on-close
      :title="form.schemaId ? t('web.metadata.drawerEditTitle') : t('web.metadata.drawerCreateTitle')"
    >
      <div class="studio-grid columns-2">
        <SectionCard :title="t('web.metadata.basicsTitle')" :description="t('web.metadata.basicsDescription')">
          <el-form label-position="top">
            <div class="studio-form-grid">
              <el-form-item :label="t('web.metadata.metaModelCategory')">
                <el-select v-model="form.domain">
                  <el-option :label="t('web.metadata.technicalMetaModel')" value="TECHNICAL" />
                  <el-option :label="t('web.metadata.businessMetaModel')" value="BUSINESS" />
                  <el-option :label="t('web.metadata.runtimeOptionMetaModel')" value="RUNTIME" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.domain === 'TECHNICAL'" :label="t('web.metadata.datasourceType')">
                <el-select v-model="form.datasourceType" filterable allow-create default-first-option>
                  <el-option v-for="typeCode in sourceTypeOptions" :key="typeCode" :label="typeCode" :value="typeCode" />
                </el-select>
              </el-form-item>
              <el-form-item v-else-if="form.domain === 'BUSINESS'" :label="t('web.metadata.businessDirectoryCode')">
                <el-input v-model="form.directoryCode" placeholder="sales" />
              </el-form-item>
              <el-form-item v-if="form.domain === 'BUSINESS'" :label="t('web.metadata.businessDirectoryName')">
                <el-input v-model="form.directoryName" placeholder="Sales Domain" />
              </el-form-item>
              <el-form-item v-if="form.domain === 'RUNTIME'" :label="t('web.metadata.runtimeRole')">
                <el-select v-model="form.runtimeRole">
                  <el-option :label="t('web.metadata.reader')" value="reader" />
                  <el-option :label="t('web.metadata.writer')" value="writer" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.domain === 'RUNTIME'" :label="t('web.metadata.pluginType')">
                <el-select v-model="form.pluginType" filterable allow-create default-first-option>
                  <el-option v-for="pluginType in runtimePluginOptions(form.runtimeRole)" :key="pluginType" :label="pluginType" :value="pluginType" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.domain !== 'RUNTIME'" :label="t('web.metadata.metaModelCode')">
                <el-input v-model="form.metaModelCode" placeholder="source / table / field" />
              </el-form-item>
              <el-form-item :label="t('web.metadata.schemaName')">
                <el-input v-model="form.schemaName" :placeholder="t('web.metadata.schemaNamePlaceholder')" />
              </el-form-item>
              <el-form-item v-if="form.domain !== 'RUNTIME'" :label="t('web.metadata.displayMode')">
                <el-select v-model="form.displayMode">
                  <el-option :label="t('web.metadata.displaySingle')" value="SINGLE" />
                  <el-option :label="t('web.metadata.displayMultiple')" value="MULTIPLE" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.domain !== 'RUNTIME'" :label="t('web.metadata.syncStrategy')">
                <el-input v-model="form.syncStrategy" placeholder="OBJECT_DISCOVERY" />
              </el-form-item>
              <el-form-item v-if="form.domain !== 'RUNTIME'" :label="t('web.metadata.requiredModel')">
                <el-switch v-model="form.required" />
              </el-form-item>
              <el-form-item :label="t('web.metadata.schemaCode')">
                <el-input :model-value="derivedSchemaCode" readonly />
              </el-form-item>
              <el-form-item :label="t('web.metadata.objectType')">
                <el-input :model-value="derivedObjectType" readonly />
              </el-form-item>
              <el-form-item :label="t('web.metadata.typeCode')">
                <el-input :model-value="derivedTypeCode" readonly />
              </el-form-item>
              <el-form-item :label="t('web.metadata.descriptionLabel')" style="grid-column: 1 / -1">
                <el-input v-model="form.plainDescription" type="textarea" :rows="4" :placeholder="t('web.metadata.descriptionPlaceholder')" />
              </el-form-item>
            </div>
          </el-form>
        </SectionCard>

        <SectionCard :title="t('web.metadata.previewTitle')" :description="t('web.metadata.previewDescription')">
          <MetaFormRenderer :fields="form.fields" :model-value="previewModel" @update:model-value="previewModel = $event" />
        </SectionCard>
      </div>

      <MetadataSchemaFieldsSection
        :fields="form.fields"
        :component-types="componentTypes"
        :value-types="valueTypes"
        :query-operator-options="queryOperatorOptions"
        :field-actions="fieldSectionActions"
      />

      <div class="drawer-actions">
        <el-button @click="drawerOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button v-if="canManageMetaSchemas" type="primary" :loading="saving" @click="saveDraft">{{ t("common.saveDraft") }}</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { DatasourceTypeCapabilityView, MetadataFieldDefinition, MetadataSchemaDefinition } from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import MetadataSchemaFieldsSection from "@/components/metadata/MetadataSchemaFieldsSection.vue";
import { useAsyncAction } from "@/composables/useAsyncAction";
import { useAuthStore } from "@/stores/auth";
import { encodeMetaModelDescription, hasExplicitMetaModelConfig, parseMetaModelSchema, sameEntityId, type MetaModelConfig, type MetaModelDisplayMode, type MetaModelDomain, type RuntimeOptionRole } from "@/utils/metaModel";
import { cloneDeep, formatStatusLabel, toneFromStatus } from "@/utils/studio";

type TreeNodeKind = "technical-root" | "technical-type" | "business-root" | "business-directory" | "runtime-root" | "runtime-role" | "leaf";

interface MetaModelTreeNode {
  id: string;
  label: string;
  kind: TreeNodeKind;
  domain?: MetaModelDomain;
  datasourceType?: string;
  directoryCode?: string;
  directoryName?: string;
  metaModelCode?: string;
  runtimeRole?: RuntimeOptionRole;
  pluginType?: string;
  displayMode?: MetaModelDisplayMode;
  required?: boolean;
  syncStrategy?: string;
  schema?: MetadataSchemaDefinition;
  children?: MetaModelTreeNode[];
}

interface SchemaDraftForm {
  schemaId?: string | number;
  schemaName: string;
  plainDescription: string;
  domain: MetaModelDomain;
  datasourceType: string;
  directoryCode: string;
  directoryName: string;
  metaModelCode: string;
  runtimeRole: RuntimeOptionRole;
  pluginType: string;
  displayMode: MetaModelDisplayMode;
  required: boolean;
  syncStrategy: string;
  fields: MetadataFieldDefinition[];
}

const componentTypes = ["INPUT", "PASSWORD", "NUMBER", "TEXTAREA", "SELECT", "SWITCH", "JSON_EDITOR", "SQL_EDITOR", "CODE_EDITOR", "CRON"];
const valueTypes = ["STRING", "BOOLEAN", "INTEGER", "LONG", "DECIMAL", "ARRAY", "OBJECT", "JSON"];
const queryOperatorOptions = ["EQ", "LIKE", "IN", "GT", "GE", "LT", "LE", "BETWEEN"];
const requiredTechnicalMetaModels = [
  { code: "source", nameKey: "web.metadata.sourceMetaModel", displayMode: "SINGLE" as MetaModelDisplayMode, syncStrategy: "DATASOURCE_CONNECTION" },
  { code: "table", nameKey: "web.metadata.tableMetaModel", displayMode: "SINGLE" as MetaModelDisplayMode, syncStrategy: "OBJECT_DISCOVERY" },
  { code: "field", nameKey: "web.metadata.fieldMetaModel", displayMode: "MULTIPLE" as MetaModelDisplayMode, syncStrategy: "COLUMN_DISCOVERY" },
];

const { t } = useI18n();
const authStore = useAuthStore();

const schemas = ref<MetadataSchemaDefinition[]>([]);
const schemaDetails = ref<Record<string, MetadataSchemaDefinition>>({});
const datasourceTypes = ref<DatasourceTypeCapabilityView[]>([]);
const selectedNode = ref<MetaModelTreeNode>();
const drawerOpen = ref(false);
const previewModel = ref<Record<string, unknown>>({});
const pageAction = useAsyncAction();
const saveAction = useAsyncAction();
const schemaAction = useAsyncAction();
const pageLoading = pageAction.loading;
const saving = saveAction.loading;
const form = reactive<SchemaDraftForm>({
  schemaName: "",
  plainDescription: "",
  domain: "TECHNICAL",
  datasourceType: "",
  directoryCode: "",
  directoryName: "",
  metaModelCode: "",
  runtimeRole: "reader",
  pluginType: "",
  displayMode: "SINGLE",
  required: false,
  syncStrategy: "",
  fields: [],
});

const fieldSectionActions = {
  appendField,
  removeField,
};

const canManageMetaSchemas = computed(() => {
  const allowedRoles = new Set(["SUPER_ADMIN", "TENANT_ADMIN", "ADMIN", "PROJECT_ADMIN"]);
  return [...(authStore.systemRoleCodes ?? []), ...(authStore.effectiveRoleCodes ?? [])]
    .map((item) => item.toUpperCase())
    .some((item) => allowedRoles.has(item));
});

const sourceTypeOptions = computed(() => {
  const options = new Set<string>();
  for (const datasourceType of datasourceTypes.value) {
    if (datasourceType.typeCode) {
      options.add(datasourceType.typeCode);
    }
  }
  for (const schema of schemas.value) {
    if (!hasExplicitMetaModelConfig(schema)) {
      continue;
    }
    const parsed = parseMetaModelSchema(schema);
    if (parsed.config.domain === "TECHNICAL" && parsed.config.datasourceType) {
      options.add(parsed.config.datasourceType);
    }
  }
  return Array.from(options).sort((left, right) => left.localeCompare(right));
});

function runtimePluginOptions(role: RuntimeOptionRole) {
  const options = new Set<string>();
  for (const datasourceType of datasourceTypes.value) {
    const plugins = role === "writer" ? datasourceType.writerPlugins : datasourceType.readerPlugins;
    for (const plugin of plugins ?? []) {
      const normalized = normalizeRuntimePlugin(plugin);
      if (normalized) {
        options.add(normalized);
      }
    }
  }
  for (const schema of schemas.value) {
    const parsed = parseMetaModelSchema(schema).config;
    if (parsed.domain === "RUNTIME" && parsed.role === role && parsed.pluginType) {
      options.add(normalizeRuntimePlugin(parsed.pluginType));
    }
  }
  return Array.from(options).sort((left, right) => left.localeCompare(right));
}

const treeData = computed<MetaModelTreeNode[]>(() => [
  {
    id: "technical-root",
    label: t("web.metadata.technicalRoot"),
    kind: "technical-root",
    domain: "TECHNICAL",
    children: sourceTypeOptions.value.map((typeCode) => buildTechnicalTypeNode(typeCode)),
  },
  {
    id: "business-root",
    label: t("web.metadata.businessRoot"),
    kind: "business-root",
    domain: "BUSINESS",
    children: buildBusinessDirectoryNodes(),
  },
  {
    id: "runtime-root",
    label: t("web.metadata.runtimeRoot"),
    kind: "runtime-root",
    domain: "RUNTIME",
    children: [
      buildRuntimeRoleNode("reader"),
      buildRuntimeRoleNode("writer"),
    ],
  },
]);

const derivedSchemaCode = computed(() => {
  if (form.domain === "RUNTIME") {
    return `runtime:${form.runtimeRole || "reader"}:${normalizeRuntimePlugin(form.pluginType) || "plugin"}`;
  }
  if (form.domain === "TECHNICAL") {
    return `technical:${form.datasourceType || "type"}:${form.metaModelCode || "meta-model"}`;
  }
  return `business:${form.directoryCode || "directory"}:${form.metaModelCode || "meta-model"}`;
});

const derivedObjectType = computed(() => {
  if (form.domain === "RUNTIME") {
    return "collection-runtime-option";
  }
  if (form.domain === "BUSINESS") {
    return "business";
  }
  return form.metaModelCode === "source" ? "datasource" : "model";
});

const derivedTypeCode = computed(() => {
  if (form.domain === "RUNTIME") {
    return `${form.runtimeRole || "reader"}:${normalizeRuntimePlugin(form.pluginType) || "plugin"}`;
  }
  if (form.domain === "BUSINESS") {
    return `${form.directoryCode || "directory"}.${form.metaModelCode || "meta-model"}`;
  }
  return form.metaModelCode === "source" ? form.datasourceType : `${form.datasourceType}.${form.metaModelCode}`;
});

const detailDescription = computed(() => {
  if (!selectedNode.value) {
    return t("web.metadata.nodeInstruction");
  }
  if (selectedNode.value.kind === "technical-root") {
    return t("web.metadata.technicalGroupHint");
  }
  if (selectedNode.value.kind === "business-root") {
    return t("web.metadata.businessGroupHint");
  }
  if (selectedNode.value.kind === "runtime-root") {
    return t("web.metadata.runtimeGroupHint");
  }
  if (selectedNode.value.kind === "runtime-role") {
    return selectedNode.value.runtimeRole === "writer" ? t("web.metadata.writer") : t("web.metadata.reader");
  }
  if (selectedNode.value.kind === "technical-type") {
    return selectedNode.value.datasourceType || "";
  }
  if (selectedNode.value.kind === "business-directory") {
    return selectedNode.value.directoryName || selectedNode.value.directoryCode || "";
  }
  if (selectedNode.value.schema) {
    return parseMetaModelSchema(selectedNode.value.schema).plainDescription;
  }
  return t("web.metadata.missingMetaModel");
});

const detailStatusLabel = computed(() => {
  if (!selectedNode.value) {
    return t("common.unknown");
  }
  if (selectedNode.value.schema?.status) {
    return formatStatusLabel(t, selectedNode.value.schema.status);
  }
  if (selectedNode.value.required) {
    return formatStatusLabel(t, "DRAFT");
  }
  return t("common.unknown");
});

const detailStatusTone = computed(() => {
  if (!selectedNode.value) {
    return "neutral";
  }
  if (selectedNode.value.schema?.status) {
    return toneFromStatus(selectedNode.value.schema.status);
  }
  return selectedNode.value.required ? "warning" : "primary";
});

const selectedSchemaDetail = computed(() => {
  const schema = selectedNode.value?.schema;
  if (!schema?.id) {
    return undefined;
  }
  return schemaDetails.value[String(schema.id)] ?? schema;
});

function formatDisplayMode(value?: MetaModelDisplayMode) {
  if (value === "SINGLE") {
    return t("web.metadata.displaySingle");
  }
  if (value === "MULTIPLE") {
    return t("web.metadata.displayMultiple");
  }
  return "-";
}

function parseNodeRuntimeConfig(node?: MetaModelTreeNode) {
  if (!node?.schema) {
    return {} as { role?: RuntimeOptionRole; pluginType?: string };
  }
  const config = parseMetaModelSchema(node.schema).config;
  return {
    role: config.role,
    pluginType: config.pluginType,
  };
}

function normalizeRuntimePlugin(value?: string) {
  const normalized = value?.trim().toLowerCase() ?? "";
  if (normalized.endsWith("reader")) {
    return normalized.slice(0, -"reader".length);
  }
  if (normalized.endsWith("writer")) {
    return normalized.slice(0, -"writer".length);
  }
  return normalized;
}

function buildTechnicalTypeNode(datasourceType: string): MetaModelTreeNode {
  const relevant = schemas.value.filter((schema) => {
    if (!hasExplicitMetaModelConfig(schema)) {
      return false;
    }
    const parsed = parseMetaModelSchema(schema).config;
    return parsed.domain === "TECHNICAL" && parsed.datasourceType === datasourceType;
  });
  const requiredLeaves = requiredTechnicalMetaModels.map((item) => {
    const schema = relevant.find((candidate) => parseMetaModelSchema(candidate).config.metaModelCode === item.code);
    return buildLeafNode({
      schema,
      domain: "TECHNICAL",
      datasourceType,
      metaModelCode: item.code,
      required: true,
      displayMode: item.displayMode,
      syncStrategy: item.syncStrategy,
      label: t(item.nameKey),
    });
  });
  const extraLeaves = relevant
    .filter((schema) => !requiredTechnicalMetaModels.some((item) => parseMetaModelSchema(schema).config.metaModelCode === item.code))
    .map((schema) => buildLeafNode({ schema }));
  return {
    id: `technical-type:${datasourceType}`,
    label: datasourceType,
    kind: "technical-type",
    domain: "TECHNICAL",
    datasourceType,
    children: [...requiredLeaves, ...extraLeaves],
  };
}

function buildBusinessDirectoryNodes() {
  const directoryMap = new Map<string, MetaModelTreeNode>();
  for (const schema of schemas.value) {
    if (!hasExplicitMetaModelConfig(schema)) {
      continue;
    }
    const parsed = parseMetaModelSchema(schema);
    if (parsed.config.domain !== "BUSINESS") {
      continue;
    }
    const directoryCode = parsed.config.directoryCode || "business";
    const existing = directoryMap.get(directoryCode);
    const leaf = buildLeafNode({ schema });
    if (existing) {
      existing.children = existing.children ?? [];
      existing.children.push(leaf);
      continue;
    }
    directoryMap.set(directoryCode, {
      id: `business-directory:${directoryCode}`,
      label: parsed.config.directoryName || directoryCode,
      kind: "business-directory",
      domain: "BUSINESS",
      directoryCode,
      directoryName: parsed.config.directoryName || directoryCode,
      children: [leaf],
    });
  }
  return Array.from(directoryMap.values()).sort((left, right) => left.label.localeCompare(right.label));
}

function buildRuntimeRoleNode(role: RuntimeOptionRole): MetaModelTreeNode {
  const plugins = runtimePluginOptions(role);
  return {
    id: `runtime-role:${role}`,
    label: role === "writer" ? t("web.metadata.writer") : t("web.metadata.reader"),
    kind: "runtime-role",
    domain: "RUNTIME",
    runtimeRole: role,
    children: plugins.map((pluginType) => {
      const schema = schemas.value.find((candidate) => {
        const config = parseMetaModelSchema(candidate).config;
        return config.domain === "RUNTIME"
          && config.role === role
          && normalizeRuntimePlugin(config.pluginType) === pluginType;
      });
      return buildLeafNode({
        schema,
        domain: "RUNTIME",
        runtimeRole: role,
        pluginType,
        metaModelCode: role,
        displayMode: "SINGLE",
        syncStrategy: "RUNTIME_OPTION",
        label: `${pluginType}${role}`,
      });
    }),
  };
}

function buildLeafNode(options: {
  schema?: MetadataSchemaDefinition;
  domain?: MetaModelDomain;
  datasourceType?: string;
  directoryCode?: string;
  directoryName?: string;
  metaModelCode?: string;
  runtimeRole?: RuntimeOptionRole;
  pluginType?: string;
  required?: boolean;
  displayMode?: MetaModelDisplayMode;
  syncStrategy?: string;
  label?: string;
}): MetaModelTreeNode {
  const parsed = options.schema ? parseMetaModelSchema(options.schema).config : undefined;
  const metaModelCode = options.metaModelCode || parsed?.metaModelCode || "meta-model";
  const label = options.label || options.schema?.schemaName || metaModelCode;
  return {
    id: options.schema?.id != null ? `schema:${options.schema.id}` : `placeholder:${options.domain}:${options.datasourceType || options.directoryCode || options.pluginType}:${metaModelCode}`,
    label,
    kind: "leaf",
    domain: options.domain || parsed?.domain,
    datasourceType: options.datasourceType || parsed?.datasourceType,
    directoryCode: options.directoryCode || parsed?.directoryCode,
    directoryName: options.directoryName || parsed?.directoryName,
    metaModelCode,
    runtimeRole: options.runtimeRole || parsed?.role,
    pluginType: options.pluginType || parsed?.pluginType,
    displayMode: options.displayMode || parsed?.displayMode,
    required: options.required ?? parsed?.required,
    syncStrategy: options.syncStrategy || parsed?.syncStrategy,
    schema: options.schema,
  };
}

function canCreateFromNode(node: MetaModelTreeNode) {
  return node.kind === "technical-type"
    || node.kind === "runtime-root"
    || node.kind === "runtime-role"
    || node.kind === "business-root"
    || node.kind === "business-directory"
    || (node.kind === "leaf" && !node.schema);
}

async function handleNodeClick(node: MetaModelTreeNode) {
  selectedNode.value = node;
  previewModel.value = {};
  await ensureSchemaDetail(node.schema);
}

function resetForm() {
  form.schemaId = undefined;
  form.schemaName = "";
  form.plainDescription = "";
  form.domain = "TECHNICAL";
  form.datasourceType = "";
  form.directoryCode = "";
  form.directoryName = "";
  form.metaModelCode = "";
  form.runtimeRole = "reader";
  form.pluginType = "";
  form.displayMode = "SINGLE";
  form.required = false;
  form.syncStrategy = "";
  form.fields = [];
}

function seedContextFromNode(node?: MetaModelTreeNode) {
  if (!node) {
    return;
  }
  if (node.domain) {
    form.domain = node.domain;
  }
  if (node.datasourceType) {
    form.datasourceType = node.datasourceType;
  }
  if (node.directoryCode) {
    form.directoryCode = node.directoryCode;
  }
  if (node.directoryName) {
    form.directoryName = node.directoryName;
  }
  if (node.metaModelCode) {
    form.metaModelCode = node.metaModelCode;
  }
  if (node.runtimeRole) {
    form.runtimeRole = node.runtimeRole;
    form.metaModelCode = node.runtimeRole;
  }
  if (node.pluginType) {
    form.pluginType = normalizeRuntimePlugin(node.pluginType);
  }
  if (node.displayMode) {
    form.displayMode = node.displayMode;
  }
  if (node.required != null) {
    form.required = node.required;
  }
  if (node.syncStrategy) {
    form.syncStrategy = node.syncStrategy;
  }
}

function openCreateFromNode(node: MetaModelTreeNode) {
  resetForm();
  seedContextFromNode(node);
  if (node.kind === "business-root") {
    form.domain = "BUSINESS";
  }
  if (node.kind === "technical-type") {
    form.domain = "TECHNICAL";
  }
  if (node.kind === "runtime-root" || node.kind === "runtime-role" || node.domain === "RUNTIME") {
    form.domain = "RUNTIME";
    form.metaModelCode = form.runtimeRole;
    form.displayMode = "SINGLE";
    form.required = false;
    form.syncStrategy = "RUNTIME_OPTION";
  }
  if (!form.schemaName && node.label && node.kind === "leaf") {
    form.schemaName = node.label;
  }
  appendField();
  drawerOpen.value = true;
}

async function editSchema(schema: MetadataSchemaDefinition) {
  const detail = await ensureSchemaDetail(schema);
  const copied = cloneDeep(detail ?? schema);
  const parsed = parseMetaModelSchema(copied);
  form.schemaId = copied.id;
  form.schemaName = copied.schemaName;
  form.plainDescription = parsed.plainDescription;
  form.domain = parsed.config.domain;
  form.datasourceType = parsed.config.datasourceType ?? "";
  form.directoryCode = parsed.config.directoryCode ?? "";
  form.directoryName = parsed.config.directoryName ?? "";
  form.metaModelCode = parsed.config.metaModelCode;
  form.runtimeRole = parsed.config.role ?? "reader";
  form.pluginType = normalizeRuntimePlugin(parsed.config.pluginType);
  form.displayMode = parsed.config.displayMode ?? "SINGLE";
  form.required = Boolean(parsed.config.required);
  form.syncStrategy = parsed.config.syncStrategy ?? "";
  form.fields = (cloneDeep(copied.fields ?? []) as MetadataFieldDefinition[]).map(normalizeFieldDraft);
  previewModel.value = {};
  drawerOpen.value = true;
}

function appendField() {
  form.fields.push(normalizeFieldDraft({
    fieldKey: "",
    fieldName: "",
    scope: form.domain === "BUSINESS" ? "BUSINESS" : "TECHNICAL",
    componentType: "INPUT",
    valueType: "STRING",
    required: false,
    sensitive: false,
    searchable: false,
    sortable: false,
    queryOperators: [],
    queryDefaultOperator: undefined,
    options: [],
  }));
}

function removeField(index: number) {
  form.fields.splice(index, 1);
}

function ensureCanManageMetaSchemas() {
  if (canManageMetaSchemas.value) {
    return true;
  }
  ElMessage.error("当前账号无权维护元模型");
  return false;
}

function normalizeFieldDraft(field: MetadataFieldDefinition) {
  return {
    ...field,
    options: field.options ?? [],
    queryOperators: field.queryOperators ?? [],
  };
}

async function loadPage() {
  try {
    await pageAction.run(async () => {
      const [schemaData, datasourceTypeData] = await Promise.all([
        studioApi.metaSchemas.list({ includeFields: false }),
        studioApi.catalog.datasourceTypes(),
      ]);
      schemas.value = schemaData.map(toSchemaSummary);
      schemaDetails.value = {};
      datasourceTypes.value = datasourceTypeData;
      await nextTick();
      const matchedNode = selectedNode.value ? findNodeById(treeData.value, selectedNode.value.id) : undefined;
      selectedNode.value = matchedNode ?? treeData.value[0];
      await ensureSchemaDetail(selectedNode.value?.schema);
    }, { errorMessage: t("web.metadata.loadFailed") });
  } catch {
    // useAsyncAction has already shown the message; keep page loading resilient.
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

function cacheSchemaDetail(schema?: MetadataSchemaDefinition) {
  if (!schema?.id) {
    return;
  }
  schemaDetails.value = {
    ...schemaDetails.value,
    [String(schema.id)]: schema,
  };
}

async function ensureSchemaDetail(schema?: MetadataSchemaDefinition) {
  if (!schema?.id) {
    return undefined;
  }
  const key = String(schema.id);
  const cached = schemaDetails.value[key];
  if (cached) {
    return cached;
  }
  try {
    const detail = await studioApi.metaSchemas.get(schema.id);
    cacheSchemaDetail(detail);
    return detail;
  } catch (error) {
    const message = error instanceof Error && error.message ? error.message : String(error);
    ElMessage.error(`${t("web.metadata.loadFailed")}: ${message}`);
    return undefined;
  }
}

function upsertSchema(schema: MetadataSchemaDefinition) {
  if (!schema.id) {
    return;
  }
  cacheSchemaDetail(schema);
  const summary = toSchemaSummary(schema);
  const index = schemas.value.findIndex((item) => sameEntityId(item.id, schema.id));
  if (index >= 0) {
    schemas.value = schemas.value.map((item, itemIndex) => (itemIndex === index ? summary : item)).sort(compareSchema);
    return;
  }
  schemas.value = [...schemas.value, summary].sort(compareSchema);
}

function upsertSchemas(items: MetadataSchemaDefinition[]) {
  if (!items.length) {
    return;
  }
  const nextSchemas = [...schemas.value];
  for (const item of items) {
    if (!item.id) {
      continue;
    }
    cacheSchemaDetail(item);
    const summary = toSchemaSummary(item);
    const index = nextSchemas.findIndex((schema) => sameEntityId(schema.id, item.id));
    if (index >= 0) {
      nextSchemas[index] = summary;
    } else {
      nextSchemas.push(summary);
    }
  }
  schemas.value = nextSchemas.sort(compareSchema);
}

function removeSchema(schemaId: string | number) {
  schemas.value = schemas.value.filter((schema) => !sameEntityId(schema.id, schemaId));
  const nextDetails = { ...schemaDetails.value };
  delete nextDetails[String(schemaId)];
  schemaDetails.value = nextDetails;
}

async function selectSchemaById(schemaId?: string | number) {
  if (schemaId == null) {
    return;
  }
  await nextTick();
  const matchedNode = findNodeById(treeData.value, `schema:${schemaId}`);
  if (matchedNode) {
    selectedNode.value = matchedNode;
    await ensureSchemaDetail(matchedNode.schema);
  }
}

async function selectCurrentNodeAfterPatch() {
  await nextTick();
  const matchedNode = selectedNode.value ? findNodeById(treeData.value, selectedNode.value.id) : undefined;
  selectedNode.value = matchedNode ?? treeData.value[0];
  await ensureSchemaDetail(selectedNode.value?.schema);
}

async function selectFallbackNode() {
  await nextTick();
  const matchedNode = selectedNode.value ? findNodeById(treeData.value, selectedNode.value.id) : undefined;
  selectedNode.value = matchedNode ?? treeData.value[0];
  await ensureSchemaDetail(selectedNode.value?.schema);
}

async function saveDraft() {
  if (!ensureCanManageMetaSchemas()) {
    return;
  }
  if (form.domain === "RUNTIME" && !normalizeRuntimePlugin(form.pluginType)) {
    ElMessage.error(t("web.metadata.pluginTypeRequired"));
    return;
  }
  try {
    await saveAction.run(async () => {
      const config: MetaModelConfig = {
        domain: form.domain,
        datasourceType: form.domain === "TECHNICAL" ? form.datasourceType : undefined,
        directoryCode: form.domain === "BUSINESS" ? form.directoryCode : undefined,
        directoryName: form.domain === "BUSINESS" ? (form.directoryName || form.directoryCode) : undefined,
        metaModelCode: form.domain === "RUNTIME" ? form.runtimeRole : form.metaModelCode,
        metaModelName: form.schemaName,
        displayMode: form.domain === "RUNTIME" ? "SINGLE" : form.displayMode,
        required: form.domain === "RUNTIME" ? false : form.required,
        syncStrategy: form.domain === "RUNTIME" ? "RUNTIME_OPTION" : form.syncStrategy,
        role: form.domain === "RUNTIME" ? form.runtimeRole : undefined,
        pluginType: form.domain === "RUNTIME" ? normalizeRuntimePlugin(form.pluginType) : undefined,
      };
      const saved = await studioApi.metaSchemas.saveDraft({
        schemaId: form.schemaId,
        schemaCode: derivedSchemaCode.value,
        schemaName: form.schemaName,
        objectType: derivedObjectType.value,
        typeCode: derivedTypeCode.value,
        description: encodeMetaModelDescription(config, form.plainDescription),
        fields: cloneDeep(form.fields),
      });
      upsertSchema(saved);
      drawerOpen.value = false;
      await selectSchemaById(saved.id);
    }, {
      successMessage: t("web.metadata.saveSuccess"),
      errorMessage: t("web.metadata.saveFailed"),
    });
  } catch {
    // Message handled by useAsyncAction.
  }
}

async function publishSchema(schema: MetadataSchemaDefinition) {
  if (!ensureCanManageMetaSchemas()) {
    return;
  }
  if (!schema.id) {
    return;
  }
  try {
    await schemaAction.run(async () => {
      await ElMessageBox.confirm(t("web.metadata.publishConfirmMessage", { schemaCode: schema.schemaCode }), t("common.confirm"));
      const published = await studioApi.metaSchemas.publish(schema.id!);
      upsertSchema(published);
      await selectSchemaById(published.id);
    }, {
      successMessage: t("web.metadata.publishSuccess"),
      errorMessage: t("web.metadata.publishFailed"),
      ignoreCancel: true,
    });
  } catch {
    // Message handled by useAsyncAction.
  }
}

async function deleteSchema(schema: MetadataSchemaDefinition) {
  if (!ensureCanManageMetaSchemas()) {
    return;
  }
  if (!schema.id) {
    return;
  }
  try {
    await schemaAction.run(async () => {
      await ElMessageBox.confirm(
        t("web.metadata.deleteConfirmMessage", { schemaCode: schema.schemaCode }),
        t("common.confirm"),
        { type: "warning" },
      );
      await studioApi.metaSchemas.delete(schema.id!);
      if (form.schemaId != null && sameEntityId(form.schemaId, schema.id)) {
        drawerOpen.value = false;
        resetForm();
      }
      removeSchema(schema.id!);
      await selectFallbackNode();
    }, {
      successMessage: t("web.metadata.deleteSuccess"),
      errorMessage: t("web.metadata.deleteFailed"),
      ignoreCancel: true,
    });
  } catch {
    // Message handled by useAsyncAction.
  }
}

async function syncTechnical(datasourceType?: string) {
  if (!ensureCanManageMetaSchemas()) {
    return;
  }
  if (!datasourceType) {
    return;
  }
  try {
    await schemaAction.run(async () => {
      const synced = await studioApi.metaSchemas.syncTechnicalSummary(datasourceType);
      upsertSchemas(synced);
      await selectCurrentNodeAfterPatch();
    }, {
      successMessage: t("web.metadata.syncTechnicalSuccess"),
      errorMessage: t("web.metadata.syncTechnicalFailed"),
    });
  } catch {
    // Message handled by useAsyncAction.
  }
}

async function syncAllTechnical() {
  if (!ensureCanManageMetaSchemas()) {
    return;
  }
  try {
    await schemaAction.run(async () => {
      const synced = await studioApi.metaSchemas.syncAllTechnicalSummary();
      upsertSchemas(synced);
      await selectCurrentNodeAfterPatch();
    }, {
      successMessage: t("web.metadata.syncTechnicalSuccess"),
      errorMessage: t("web.metadata.syncTechnicalFailed"),
    });
  } catch {
    // Message handled by useAsyncAction.
  }
}

function findNodeById(nodes: MetaModelTreeNode[], nodeId: string): MetaModelTreeNode | undefined {
  for (const node of nodes) {
    if (node.id === nodeId) {
      return node;
    }
    const matched = node.children ? findNodeById(node.children, nodeId) : undefined;
    if (matched) {
      return matched;
    }
  }
  return undefined;
}

onMounted(loadPage);
</script>

<style scoped>
h3 {
  margin: 0 0 6px;
}

p {
  margin: 0;
  color: var(--studio-text-soft);
}

.meta-model-layout {
  grid-template-columns: minmax(280px, 340px) minmax(0, 1fr);
}

.tree-node {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
}

.tree-node > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.meta-descriptions {
  margin-bottom: 12px;
}

.warning-hint,
.empty-hint {
  margin-top: 12px;
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 14px;
}

@media (max-width: 1080px) {
  .meta-model-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
