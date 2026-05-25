<template>
  <SectionCard title="任务绑定" description="质量任务需要明确目标数据源、模型；字段级任务还需要绑定具体字段。">
    <div class="studio-form-grid">
      <el-form-item label="数据源">
        <el-select
          :model-value="String(form.datasourceId || '')"
          filterable
          clearable
          placeholder="请选择支持 SQL 执行的数据源"
          @update:model-value="actions.handleDatasourceChange"
        >
          <el-option v-for="datasource in datasources" :key="String(datasource.id)" :label="datasource.name" :value="String(datasource.id)" />
        </el-select>
      </el-form-item>
      <el-form-item label="模型">
        <el-select
          :model-value="String(form.modelId || '')"
          filterable
          clearable
          placeholder="请选择目标模型"
          @update:model-value="actions.handleModelChange"
        >
          <el-option
            v-for="model in models"
            :key="String(model.id)"
            :label="`${model.name}${model.physicalLocator ? ` / ${model.physicalLocator}` : ''}`"
            :value="String(model.id)"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.granularity === 'COLUMN'" label="字段">
        <el-select v-model="form.columnName" filterable clearable placeholder="请选择目标字段" @change="actions.handleColumnChange">
          <el-option v-for="column in columns" :key="column" :label="column" :value="column" />
        </el-select>
      </el-form-item>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import type { DataModelDefinition, DataSourceDefinition, QualityRuleGranularity } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";

interface BindingForm {
  datasourceId: string | number;
  modelId: string | number;
  columnName: string;
  granularity: QualityRuleGranularity;
}

interface BindingActions {
  handleDatasourceChange: (value: string) => void | Promise<void>;
  handleModelChange: (value: string) => void | Promise<void>;
  handleColumnChange: () => void;
}

defineProps<{
  form: BindingForm;
  datasources: DataSourceDefinition[];
  models: DataModelDefinition[];
  columns: string[];
  actions: BindingActions;
}>();
</script>
