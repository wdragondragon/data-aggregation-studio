<template>
  <SectionCard :title="t('web.models.previewTitle')" :description="t('web.models.previewDescription')">
    <div v-if="model" class="soft-panel preview-head">
      <div>
        <strong>{{ model.name }}</strong>
        <p>{{ model.physicalLocator }}</p>
        <p>所属项目：{{ resolveProjectLabel(model.projectId) }}</p>
      </div>
      <div class="preview-head__tags">
        <StatusPill :label="formatModelKind(t, model.modelKind)" tone="primary" />
        <StatusPill
          :label="shared ? '共享来源' : '当前项目'"
          :tone="shared ? 'warning' : 'success'"
        />
      </div>
    </div>
    <div v-else class="soft-panel empty-hint">
      {{ t("web.models.previewEmpty") }}
    </div>

    <template v-if="model">
      <div v-if="shared" class="soft-panel warning-hint">
        当前模型来自其他项目共享，支持查看与引用，但不能在此项目中编辑或删除。
      </div>
      <div class="model-section-stack">
        <div
          v-for="section in sections"
          :key="section.key"
          class="soft-panel model-meta-section"
        >
          <div class="model-meta-section__header">
            <div>
              <strong>{{ section.title }}</strong>
              <p>{{ section.description }}</p>
            </div>
            <div class="model-meta-section__tags">
              <StatusPill
                :label="section.binding === 'TECHNICAL' ? t('metaForm.technicalTitle') : t('metaForm.businessTitle')"
                :tone="section.binding === 'TECHNICAL' ? 'primary' : 'success'"
              />
              <StatusPill
                :label="section.displayMode === 'MULTIPLE' ? t('web.metadata.displayMultiple') : t('web.metadata.displaySingle')"
                tone="neutral"
              />
            </div>
          </div>

          <div v-if="section.displayMode === 'MULTIPLE'">
            <div v-if="previewSectionRows(section).length === 0" class="soft-panel empty-hint section-empty">
              {{ t("web.models.metaSectionEmpty") }}
            </div>
            <el-table v-else :data="previewSectionRows(section)" border>
              <el-table-column
                v-for="field in section.fields"
                :key="field.fieldKey"
                :prop="field.fieldKey"
                :label="field.fieldName"
                min-width="140"
                show-overflow-tooltip
              >
                <template #default="{ row }">
                  {{ formatDisplayValue(row[field.fieldKey]) }}
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div v-else class="model-field-grid">
            <div
              v-for="field in section.fields"
              :key="field.fieldKey"
              class="model-field-grid__item"
            >
              <span class="model-field-grid__label">{{ field.fieldName }}</span>
              <div class="model-field-grid__value">
                {{ formatDisplayValue(sectionValue(section, field.fieldKey, false)) }}
              </div>
            </div>
          </div>
        </div>

        <div v-if="sections.length === 0" class="soft-panel empty-hint">
          {{ t("web.models.metaSectionEmpty") }}
        </div>
      </div>

      <div class="soft-panel sample-panel">
        <div class="model-meta-section__header">
          <div>
            <strong>{{ t("web.models.sampleRowsTitle") }}</strong>
            <p>{{ t("web.models.sampleRowsDescription") }}</p>
          </div>
        </div>

        <div v-if="previewRows.length === 0" class="soft-panel empty-hint section-empty">
          {{ t("web.models.sampleRowsEmpty") }}
        </div>
        <el-table v-else :data="previewRows" border>
          <el-table-column
            v-for="column in previewColumns"
            :key="column"
            :prop="column"
            :label="column"
            min-width="140"
            show-overflow-tooltip
          />
        </el-table>
      </div>
    </template>
  </SectionCard>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import type { DataModelDefinition, EntityId } from "@studio/api-sdk";
import { SectionCard, StatusPill } from "@studio/ui";
import { formatModelKind } from "@/utils/studio";
import type { ModelMetaSection } from "./modelViewTypes";

defineProps<{
  model?: DataModelDefinition;
  shared: boolean;
  sections: ModelMetaSection[];
  previewRows: Record<string, unknown>[];
  previewColumns: string[];
  resolveProjectLabel: (projectId?: EntityId | null) => string;
  previewSectionRows: (section: ModelMetaSection) => Record<string, unknown>[];
  sectionValue: (section: ModelMetaSection, fieldKey?: string, editor?: boolean) => unknown;
  formatDisplayValue: (value: unknown) => string;
}>();

const { t } = useI18n();
</script>

<style scoped>
p {
  margin: 0;
  color: var(--studio-text-soft);
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.preview-head p {
  margin: 4px 0 0;
  display: block;
}

.preview-head__tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.model-section-stack {
  display: grid;
  gap: 10px;
}

.model-meta-section,
.sample-panel {
  display: grid;
  gap: 10px;
}

.model-meta-section__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.model-meta-section__header p {
  display: none;
}

.model-meta-section__tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.model-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.model-field-grid__item {
  border: 1px solid var(--studio-border);
  border-radius: 14px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.8);
}

.model-field-grid__label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--studio-text-soft);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.model-field-grid__value {
  color: var(--studio-text);
  word-break: break-word;
}

.empty-hint,
.warning-hint {
  margin-bottom: 12px;
}

.section-empty {
  margin-bottom: 0;
}

.warning-hint {
  border-color: rgba(36, 99, 235, 0.2);
  background: rgba(219, 234, 254, 0.7);
}

@media (max-width: 980px) {
  .model-field-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .model-meta-section__header {
    flex-direction: column;
  }

  .model-meta-section__tags,
  .preview-head__tags {
    justify-content: flex-start;
  }
}
</style>
