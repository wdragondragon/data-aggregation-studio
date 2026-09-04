<template>
  <SectionCard :title="t('web.metadata.fieldDefinitionsTitle')" :description="t('web.metadata.fieldDefinitionsDescription')">
    <template #actions>
      <el-button type="primary" plain @click="fieldActions.appendField">{{ t("common.addField") }}</el-button>
    </template>

    <StudioTableShell min-width="2040px">
      <el-table :data="fields" border>
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
        <el-table-column :label="t('web.metadata.filePolicy')" width="200">
          <template #default="{ row }">
            <el-select
              v-if="row.componentType === 'MANAGED_FILE'"
              v-model="row.filePolicyCode"
              :placeholder="t('web.metadata.filePolicyPlaceholder')"
            >
              <el-option v-for="item in filePolicyCodes" :key="item" :label="item" :value="item" />
            </el-select>
            <span v-else class="muted-text">-</span>
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
        <el-table-column :label="t('web.metadata.actions')" width="100" fixed="right">
          <template #default="{ $index }">
            <el-button link type="danger" @click="fieldActions.removeField($index)">{{ t("common.remove") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </StudioTableShell>
  </SectionCard>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import type { MetadataFieldDefinition } from "@studio/api-sdk";
import { SectionCard, StudioTableShell } from "@studio/ui";

interface FieldSectionActions {
  appendField: () => void;
  removeField: (index: number) => void;
}

defineProps<{
  fields: MetadataFieldDefinition[];
  componentTypes: string[];
  filePolicyCodes: string[];
  valueTypes: string[];
  queryOperatorOptions: string[];
  fieldActions: FieldSectionActions;
}>();

const { t } = useI18n();
</script>
