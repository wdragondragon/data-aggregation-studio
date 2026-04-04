<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.metadata.heading") }}</h3>
        <p>{{ t("web.metadata.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button plain @click="syncAllTechnical">{{ t("common.syncAll") }}</el-button>
        <el-button plain @click="loadPage">{{ t("common.refresh") }}</el-button>
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
                :label="slotProps?.data?.schema?.status ?? (slotProps?.data?.required ? t('common.draft') : t('common.unknown'))"
                :tone="slotProps?.data?.schema ? toneFromStatus(slotProps.data.schema.status) : 'warning'"
              />
            </div>
          </template>
        </el-tree>
      </SectionCard>

      <SectionCard :title="t('web.metadata.detailTitle')" :description="t('web.metadata.detailDescription')">
        <template #actions>
          <el-button
            v-if="selectedNode?.kind === 'technical-root'"
            plain
            @click="syncAllTechnical"
          >
            {{ t("common.syncAll") }}
          </el-button>
          <el-button
            v-if="selectedNode?.kind === 'technical-type'"
            plain
            @click="syncTechnical(selectedNode.datasourceType)"
          >
            {{ t("common.sync") }}
          </el-button>
          <el-button
            v-if="selectedNode && canCreateFromNode(selectedNode)"
            type="primary"
            @click="openCreateFromNode(selectedNode)"
          >
            {{ t("common.newMetaModel") }}
          </el-button>
          <el-button
            v-if="selectedNode?.kind === 'leaf' && selectedNode.schema"
            plain
            @click="editSchema(selectedNode.schema)"
          >
            {{ t("common.edit") }}
          </el-button>
          <el-button
            v-if="selectedNode?.kind === 'leaf' && selectedNode.schema?.id"
            plain
            @click="publishSchema(selectedNode.schema)"
          >
            {{ t("common.publish") }}
          </el-button>
          <el-button
            v-if="selectedNode?.kind === 'leaf' && selectedNode.schema?.id"
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
              {{ selectedNode.displayMode || "-" }}
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
          </el-descriptions>

          <div v-if="selectedNode.kind === 'leaf' && !selectedNode.schema" class="soft-panel warning-hint">
            {{ t("web.metadata.missingMetaModel") }}
          </div>

          <SectionCard
            v-if="selectedNode.kind === 'leaf' && selectedNode.schema"
            :title="t('web.metadata.previewTitle')"
            :description="t('web.metadata.previewDescription')"
          >
            <MetaFormRenderer :fields="selectedNode.schema.fields" :model-value="previewModel" @update:model-value="previewModel = $event" />
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
              <el-form-item :label="t('web.metadata.technicalMetaModel')">
                <el-select v-model="form.domain">
                  <el-option :label="t('web.metadata.technicalMetaModel')" value="TECHNICAL" />
                  <el-option :label="t('web.metadata.businessMetaModel')" value="BUSINESS" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.domain === 'TECHNICAL'" :label="t('web.metadata.datasourceType')">
                <el-select v-model="form.datasourceType" filterable allow-create default-first-option>
                  <el-option v-for="typeCode in sourceTypeOptions" :key="typeCode" :label="typeCode" :value="typeCode" />
                </el-select>
              </el-form-item>
              <el-form-item v-else :label="t('web.metadata.businessDirectoryCode')">
                <el-input v-model="form.directoryCode" placeholder="sales" />
              </el-form-item>
              <el-form-item v-if="form.domain === 'BUSINESS'" :label="t('web.metadata.businessDirectoryName')">
                <el-input v-model="form.directoryName" placeholder="Sales Domain" />
              </el-form-item>
              <el-form-item :label="t('web.metadata.metaModelCode')">
                <el-input v-model="form.metaModelCode" placeholder="source / table / field" />
              </el-form-item>
              <el-form-item :label="t('web.metadata.schemaName')">
                <el-input v-model="form.schemaName" :placeholder="t('web.metadata.schemaNamePlaceholder')" />
              </el-form-item>
              <el-form-item :label="t('web.metadata.displayMode')">
                <el-select v-model="form.displayMode">
                  <el-option :label="t('web.metadata.displaySingle')" value="SINGLE" />
                  <el-option :label="t('web.metadata.displayMultiple')" value="MULTIPLE" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('web.metadata.syncStrategy')">
                <el-input v-model="form.syncStrategy" placeholder="OBJECT_DISCOVERY" />
              </el-form-item>
              <el-form-item :label="t('web.metadata.requiredModel')">
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

      <SectionCard :title="t('web.metadata.fieldDefinitionsTitle')" :description="t('web.metadata.fieldDefinitionsDescription')">
        <template #actions>
          <el-button type="primary" plain @click="appendField">{{ t("common.addField") }}</el-button>
        </template>

        <el-table :data="form.fields" border>
          <el-table-column :label="t('web.metadata.fieldKey')" min-width="150">
            <template #default="{ row }">
              <el-input v-model="row.fieldKey" placeholder="host" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.fieldName')" min-width="160">
            <template #default="{ row }">
              <el-input v-model="row.fieldName" placeholder="Host" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.scope')" width="140">
            <template #default="{ row }">
              <el-select v-model="row.scope">
                <el-option :label="t('web.metadata.technical')" value="TECHNICAL" />
                <el-option :label="t('web.metadata.business')" value="BUSINESS" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.component')" width="150">
            <template #default="{ row }">
              <el-select v-model="row.componentType">
                <el-option v-for="item in componentTypes" :key="item" :label="item" :value="item" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.valueType')" width="150">
            <template #default="{ row }">
              <el-select v-model="row.valueType">
                <el-option v-for="item in valueTypes" :key="item" :label="item" :value="item" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.required')" width="100">
            <template #default="{ row }">
              <el-switch v-model="row.required" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.sensitive')" width="100">
            <template #default="{ row }">
              <el-switch v-model="row.sensitive" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.searchable')" width="110">
            <template #default="{ row }">
              <el-switch v-model="row.searchable" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.sortable')" width="110">
            <template #default="{ row }">
              <el-switch v-model="row.sortable" :disabled="!row.searchable" />
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.queryOperators')" min-width="200">
            <template #default="{ row }">
              <el-select
                v-model="row.queryOperators"
                multiple
                clearable
                filterable
                allow-create
                default-first-option
                :placeholder="t('web.metadata.queryOperatorsPlaceholder')"
                :disabled="!row.searchable"
              >
                <el-option
                  v-for="operator in queryOperatorOptions"
                  :key="operator"
                  :label="operator"
                  :value="operator"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.queryDefaultOperator')" width="160">
            <template #default="{ row }">
              <el-select
                v-model="row.queryDefaultOperator"
                clearable
                :placeholder="t('web.metadata.queryDefaultOperatorPlaceholder')"
                :disabled="!row.searchable || !(row.queryOperators?.length)"
              >
                <el-option
                  v-for="operator in row.queryOperators ?? []"
                  :key="operator"
                  :label="operator"
                  :value="operator"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.options')" min-width="180">
            <template #default="{ row }">
              <el-select
                v-model="row.options"
                multiple
                filterable
                allow-create
                default-first-option
                :placeholder="t('web.metadata.optionsPlaceholder')"
              >
                <el-option
                  v-for="option in row.options ?? []"
                  :key="option"
                  :label="option"
                  :value="option"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column :label="t('web.metadata.actions')" width="100">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeField($index)">{{ t("common.remove") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </SectionCard>

      <div class="drawer-actions">
        <el-button @click="drawerOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveDraft">{{ t("common.saveDraft") }}</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { MetadataFieldDefinition, MetadataSchemaDefinition, PluginCatalogEntry } from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";
import { encodeMetaModelDescription, hasExplicitMetaModelConfig, parseMetaModelSchema, sameEntityId, type MetaModelConfig, type MetaModelDisplayMode, type MetaModelDomain } from "@/utils/metaModel";
import { cloneDeep, toneFromStatus } from "@/utils/studio";

type TreeNodeKind = "technical-root" | "technical-type" | "business-root" | "business-directory" | "leaf";

interface MetaModelTreeNode {
  id: string;
  label: string;
  kind: TreeNodeKind;
  domain?: MetaModelDomain;
  datasourceType?: string;
  directoryCode?: string;
  directoryName?: string;
  metaModelCode?: string;
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

const schemas = ref<MetadataSchemaDefinition[]>([]);
const sourcePlugins = ref<PluginCatalogEntry[]>([]);
const selectedNode = ref<MetaModelTreeNode>();
const drawerOpen = ref(false);
const saving = ref(false);
const previewModel = ref<Record<string, unknown>>({});
const form = reactive<SchemaDraftForm>({
  schemaName: "",
  plainDescription: "",
  domain: "TECHNICAL",
  datasourceType: "",
  directoryCode: "",
  directoryName: "",
  metaModelCode: "",
  displayMode: "SINGLE",
  required: false,
  syncStrategy: "",
  fields: [],
});

const sourceTypeOptions = computed(() => {
  const options = new Set<string>();
  for (const plugin of sourcePlugins.value) {
    if (plugin.pluginName) {
      options.add(plugin.pluginName);
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
]);

const derivedSchemaCode = computed(() => {
  if (form.domain === "TECHNICAL") {
    return `technical:${form.datasourceType || "type"}:${form.metaModelCode || "meta-model"}`;
  }
  return `business:${form.directoryCode || "directory"}:${form.metaModelCode || "meta-model"}`;
});

const derivedObjectType = computed(() => {
  if (form.domain === "BUSINESS") {
    return "business";
  }
  return form.metaModelCode === "source" ? "datasource" : "model";
});

const derivedTypeCode = computed(() => {
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
    return selectedNode.value.schema.status;
  }
  if (selectedNode.value.required) {
    return t("common.draft");
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

function buildLeafNode(options: {
  schema?: MetadataSchemaDefinition;
  domain?: MetaModelDomain;
  datasourceType?: string;
  directoryCode?: string;
  directoryName?: string;
  metaModelCode?: string;
  required?: boolean;
  displayMode?: MetaModelDisplayMode;
  syncStrategy?: string;
  label?: string;
}): MetaModelTreeNode {
  const parsed = options.schema ? parseMetaModelSchema(options.schema).config : undefined;
  const metaModelCode = options.metaModelCode || parsed?.metaModelCode || "meta-model";
  const label = options.label || options.schema?.schemaName || metaModelCode;
  return {
    id: options.schema?.id != null ? `schema:${options.schema.id}` : `placeholder:${options.domain}:${options.datasourceType || options.directoryCode}:${metaModelCode}`,
    label,
    kind: "leaf",
    domain: options.domain || parsed?.domain,
    datasourceType: options.datasourceType || parsed?.datasourceType,
    directoryCode: options.directoryCode || parsed?.directoryCode,
    directoryName: options.directoryName || parsed?.directoryName,
    metaModelCode,
    displayMode: options.displayMode || parsed?.displayMode,
    required: options.required ?? parsed?.required,
    syncStrategy: options.syncStrategy || parsed?.syncStrategy,
    schema: options.schema,
  };
}

function canCreateFromNode(node: MetaModelTreeNode) {
  return node.kind === "technical-type"
    || node.kind === "business-root"
    || node.kind === "business-directory"
    || (node.kind === "leaf" && !node.schema);
}

function handleNodeClick(node: MetaModelTreeNode) {
  selectedNode.value = node;
  previewModel.value = {};
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
  if (!form.schemaName && node.label && node.kind === "leaf") {
    form.schemaName = node.label;
  }
  appendField();
  drawerOpen.value = true;
}

function editSchema(schema: MetadataSchemaDefinition) {
  const copied = cloneDeep(schema);
  const parsed = parseMetaModelSchema(copied);
  form.schemaId = copied.id;
  form.schemaName = copied.schemaName;
  form.plainDescription = parsed.plainDescription;
  form.domain = parsed.config.domain;
  form.datasourceType = parsed.config.datasourceType ?? "";
  form.directoryCode = parsed.config.directoryCode ?? "";
  form.directoryName = parsed.config.directoryName ?? "";
  form.metaModelCode = parsed.config.metaModelCode;
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

function normalizeFieldDraft(field: MetadataFieldDefinition) {
  return {
    ...field,
    options: field.options ?? [],
    queryOperators: field.queryOperators ?? [],
  };
}

async function loadPage() {
  try {
    const [schemaData, pluginData] = await Promise.all([
      studioApi.metaSchemas.list(),
      studioApi.catalog.plugins("SOURCE"),
    ]);
    schemas.value = schemaData;
    sourcePlugins.value = pluginData;
    const matchedNode = selectedNode.value ? findNodeById(treeData.value, selectedNode.value.id) : undefined;
    selectedNode.value = matchedNode ?? treeData.value[0];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.metadata.loadFailed"));
  }
}

async function saveDraft() {
  saving.value = true;
  try {
    const config: MetaModelConfig = {
      domain: form.domain,
      datasourceType: form.domain === "TECHNICAL" ? form.datasourceType : undefined,
      directoryCode: form.domain === "BUSINESS" ? form.directoryCode : undefined,
      directoryName: form.domain === "BUSINESS" ? (form.directoryName || form.directoryCode) : undefined,
      metaModelCode: form.metaModelCode,
      metaModelName: form.schemaName,
      displayMode: form.displayMode,
      required: form.required,
      syncStrategy: form.syncStrategy,
    };
    await studioApi.metaSchemas.saveDraft({
      schemaId: form.schemaId,
      schemaCode: derivedSchemaCode.value,
      schemaName: form.schemaName,
      objectType: derivedObjectType.value,
      typeCode: derivedTypeCode.value,
      description: encodeMetaModelDescription(config, form.plainDescription),
      fields: cloneDeep(form.fields),
    });
    ElMessage.success(t("web.metadata.saveSuccess"));
    drawerOpen.value = false;
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.metadata.saveFailed"));
  } finally {
    saving.value = false;
  }
}

async function publishSchema(schema: MetadataSchemaDefinition) {
  if (!schema.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(t("web.metadata.publishConfirmMessage", { schemaCode: schema.schemaCode }), t("common.confirm"));
    await studioApi.metaSchemas.publish(schema.id);
    ElMessage.success(t("web.metadata.publishSuccess"));
    await loadPage();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.metadata.publishFailed"));
    }
  }
}

async function deleteSchema(schema: MetadataSchemaDefinition) {
  if (!schema.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.metadata.deleteConfirmMessage", { schemaCode: schema.schemaCode }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.metaSchemas.delete(schema.id);
    if (form.schemaId != null && sameEntityId(form.schemaId, schema.id)) {
      drawerOpen.value = false;
      resetForm();
    }
    ElMessage.success(t("web.metadata.deleteSuccess"));
    await loadPage();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.metadata.deleteFailed"));
    }
  }
}

async function syncTechnical(datasourceType?: string) {
  if (!datasourceType) {
    return;
  }
  try {
    await studioApi.metaSchemas.syncTechnical(datasourceType);
    ElMessage.success(t("web.metadata.syncTechnicalSuccess"));
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.metadata.syncTechnicalFailed"));
  }
}

async function syncAllTechnical() {
  try {
    await studioApi.metaSchemas.syncAllTechnical();
    ElMessage.success(t("web.metadata.syncTechnicalSuccess"));
    await loadPage();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.metadata.syncTechnicalFailed"));
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
}

.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.meta-descriptions {
  margin-bottom: 16px;
}

.warning-hint,
.empty-hint {
  margin-top: 16px;
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
}

@media (max-width: 1080px) {
  .meta-model-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
