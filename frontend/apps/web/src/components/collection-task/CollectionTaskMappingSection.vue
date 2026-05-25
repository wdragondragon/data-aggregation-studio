<template>
  <SectionCard :title="t('web.collectionTasks.mappingTitle')" :description="t('web.collectionTasks.mappingDescription')">
    <div class="section-toolbar">
      <div>
        <strong>{{ t("web.collectionTasks.mappingToolbarTitle") }}</strong>
        <p>{{ t("web.collectionTasks.mappingToolbarDescription") }}</p>
      </div>
      <el-button type="primary" plain @click="mappingActions.initializeMappings">{{ t("web.collectionTasks.initializeMappings") }}</el-button>
    </div>

    <div v-if="isFusionTask" class="studio-form-grid fusion-options">
      <el-form-item :label="t('web.collectionTasks.joinKeys')">
        <el-select v-model="joinKeysModel" multiple filterable :placeholder="t('web.collectionTasks.joinKeysPlaceholder')">
          <el-option v-for="field in commonJoinKeyOptions" :key="field" :label="field" :value="field" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('web.collectionTasks.joinType')">
        <el-select v-model="joinTypeModel">
          <el-option label="LEFT" value="LEFT" />
          <el-option label="INNER" value="INNER" />
          <el-option label="RIGHT" value="RIGHT" />
        </el-select>
      </el-form-item>
    </div>

    <div v-if="isFusionTask" class="runtime-option-block runtime-option-block--fusion">
      <div class="runtime-option-header">
        <div>
          <strong>{{ t("web.collectionTasks.fusionReaderAdvancedOptions") }}</strong>
          <p>{{ mappingActions.runtimeSchemaTitleByType("reader", "fusion") }}</p>
        </div>
        <el-tag v-if="mappingActions.runtimeSchemaForType('reader', 'fusion')" :type="mappingActions.runtimeStatusTypeByType('reader', 'fusion')">
          {{ mappingActions.runtimeStatusLabelByType("reader", "fusion") }}
        </el-tag>
      </div>
      <MetaFormRenderer
        v-if="fusionReaderAdvancedFields.length"
        :fields="fusionReaderAdvancedFields"
        :model-value="fusionReaderOptions"
        @update:model-value="mappingActions.updateFusionReaderOptions"
      />
      <el-alert
        v-else-if="mappingActions.runtimeSchemaForType('reader', 'fusion') && !mappingActions.runtimeSchemaForType('reader', 'fusion')?.runtimeSupported"
        type="warning"
        :closable="false"
        show-icon
        :title="t('web.collectionTasks.runtimeUnsupported')"
      />
      <el-alert
        v-else-if="mappingActions.runtimeSchemaForType('reader', 'fusion')"
        type="info"
        :closable="false"
        show-icon
        :title="t('web.collectionTasks.runtimeSchemaMissing')"
      />
    </div>

    <CollectionTaskFieldMappingEditor
      :model-value="form.fieldMappings"
      :source-aliases="sourceAliasOptions"
      :source-field-options-by-alias="sourceFieldOptionsByAlias"
      :target-fields="targetFieldOptions"
      :rule-options="fieldMappingRules"
      :show-source-alias="isFusionTask"
      :show-expression="isFusionTask"
      @update:model-value="mappingActions.updateFieldMappings"
    />
  </SectionCard>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type {
  FieldMappingDefinition,
  FieldMappingRuleView,
  MetadataFieldDefinition,
  PluginRuntimeOptionSchemaView,
} from "@studio/api-sdk";
import { MetaFormRenderer } from "@studio/meta-form";
import { SectionCard } from "@studio/ui";
import CollectionTaskFieldMappingEditor from "@web/components/CollectionTaskFieldMappingEditor.vue";

type RuntimeOptionRole = "reader" | "writer";

interface MappingSectionForm {
  fieldMappings: FieldMappingDefinition[];
}

interface CollectionTaskMappingActions {
  initializeMappings: () => void;
  runtimeSchemaTitleByType: (role: RuntimeOptionRole, datasourceType: string) => string;
  runtimeSchemaForType: (role: RuntimeOptionRole, datasourceType: string) => PluginRuntimeOptionSchemaView | undefined;
  runtimeStatusTypeByType: (role: RuntimeOptionRole, datasourceType: string) => string;
  runtimeStatusLabelByType: (role: RuntimeOptionRole, datasourceType: string) => string;
  updateFusionReaderOptions: (value: Record<string, unknown>) => void;
  updateFieldMappings: (value: FieldMappingDefinition[]) => void;
}

const props = defineProps<{
  form: MappingSectionForm;
  isFusionTask: boolean;
  joinKeys: string[];
  joinType: string;
  commonJoinKeyOptions: string[];
  fusionReaderAdvancedFields: MetadataFieldDefinition[];
  fusionReaderOptions: Record<string, unknown>;
  sourceAliasOptions: string[];
  sourceFieldOptionsByAlias: Record<string, string[]>;
  targetFieldOptions: string[];
  fieldMappingRules: FieldMappingRuleView[];
  mappingActions: CollectionTaskMappingActions;
}>();

const emit = defineEmits<{
  "update:joinKeys": [value: string[]];
  "update:joinType": [value: string];
}>();

const { t } = useI18n();

const joinKeysModel = computed({
  get() {
    return props.joinKeys;
  },
  set(value: string[]) {
    emit("update:joinKeys", value);
  },
});

const joinTypeModel = computed({
  get() {
    return props.joinType;
  },
  set(value: string) {
    emit("update:joinType", value);
  },
});
</script>

<style scoped>
p {
  margin: 0;
  color: var(--studio-text-soft);
}

.section-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.fusion-options {
  margin-bottom: 16px;
}

.runtime-option-block {
  display: grid;
  gap: 12px;
  border: 1px solid var(--studio-border);
  border-radius: 8px;
  padding: 14px;
}

.runtime-option-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
</style>
