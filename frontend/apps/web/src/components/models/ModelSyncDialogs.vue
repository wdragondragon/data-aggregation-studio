<template>
  <el-dialog
    :model-value="syncDialogOpen"
    :title="t('web.models.syncDialogTitle')"
    width="72%"
    @update:model-value="emit('update:syncDialogOpen', $event)"
  >
    <p class="dialog-description">{{ t("web.models.syncDialogDescription") }}</p>
    <div class="studio-form-grid">
      <el-form-item :label="t('web.models.syncDatasourceType')">
        <el-select
          v-model="syncForm.datasourceType"
          clearable
          :placeholder="t('web.models.syncTypePlaceholder')"
          @change="actions.handleSyncDatasourceTypeChange"
        >
          <el-option
            v-for="typeCode in databaseDatasourceTypes"
            :key="typeCode"
            :label="typeCode"
            :value="typeCode"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('web.models.syncDatasource')">
        <el-select
          v-model="syncForm.datasourceId"
          clearable
          :placeholder="t('web.models.syncDatasourcePlaceholder')"
          @change="actions.handleSyncDatasourceChange"
        >
          <el-option
            v-for="item in syncDatasourceOptions"
            :key="item.id"
            :label="`${item.name} (${item.typeCode})`"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
    </div>

    <ModelSyncTableSelector
      :model-value="selectedLocators"
      :datasource-id="syncForm.datasourceId"
      :disabled="syncing"
      @update:model-value="emit('update:selectedLocators', $event)"
    />

    <template #footer>
      <el-button @click="emit('update:syncDialogOpen', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="syncing" @click="actions.submitSync">{{ t("common.sync") }}</el-button>
    </template>
  </el-dialog>

  <el-dialog
    :model-value="syncTaskDialogOpen"
    title="新建模型同步任务"
    width="76%"
    @update:model-value="emit('update:syncTaskDialogOpen', $event)"
  >
    <p class="dialog-description">选择数据源与需要同步的表，提交后会立即在后台创建并执行同步任务。</p>
    <div class="studio-form-grid">
      <el-form-item label="数据源类型">
        <el-select
          v-model="syncTaskForm.datasourceType"
          clearable
          placeholder="选择数据源类型"
          @change="actions.handleSyncTaskFormDatasourceTypeChange"
        >
          <el-option
            v-for="typeCode in databaseDatasourceTypes"
            :key="typeCode"
            :label="typeCode"
            :value="typeCode"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="数据源">
        <el-select
          v-model="syncTaskForm.datasourceId"
          clearable
          placeholder="选择数据源"
          @change="actions.handleSyncTaskFormDatasourceChange"
        >
          <el-option
            v-for="item in syncTaskDatasourceOptions"
            :key="item.id"
            :label="`${item.name} (${item.typeCode})`"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
    </div>

    <ModelSyncTableSelector
      v-model="syncTaskForm.selectedLocators"
      :datasource-id="syncTaskForm.datasourceId"
      :disabled="creatingSyncTask"
    />

    <template #footer>
      <el-button @click="emit('update:syncTaskDialogOpen', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="creatingSyncTask" @click="actions.submitSyncTaskCreate">创建并执行</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { DataSourceOptionView } from "@studio/api-sdk";
import { useI18n } from "vue-i18n";
import ModelSyncTableSelector from "@/components/ModelSyncTableSelector.vue";
import type { ModelSyncFormState, ModelSyncTaskFormState } from "./modelViewTypes";

interface ModelSyncDialogActions {
  handleSyncDatasourceTypeChange: () => void;
  handleSyncDatasourceChange: () => void;
  handleSyncTaskFormDatasourceTypeChange: () => void;
  handleSyncTaskFormDatasourceChange: () => void;
  submitSync: () => void | Promise<void>;
  submitSyncTaskCreate: () => void | Promise<void>;
}

defineProps<{
  syncDialogOpen: boolean;
  syncTaskDialogOpen: boolean;
  syncForm: ModelSyncFormState;
  syncTaskForm: ModelSyncTaskFormState;
  selectedLocators: string[];
  syncing: boolean;
  creatingSyncTask: boolean;
  databaseDatasourceTypes: string[];
  syncDatasourceOptions: DataSourceOptionView[];
  syncTaskDatasourceOptions: DataSourceOptionView[];
  actions: ModelSyncDialogActions;
}>();

const emit = defineEmits<{
  "update:syncDialogOpen": [value: boolean];
  "update:syncTaskDialogOpen": [value: boolean];
  "update:selectedLocators": [value: string[]];
}>();

const { t } = useI18n();
</script>

<style scoped>
.dialog-description {
  margin-bottom: 12px;
}
</style>
