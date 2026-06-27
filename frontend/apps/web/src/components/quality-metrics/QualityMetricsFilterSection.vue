<template>
  <SectionCard title="全局筛选" description="时间范围、数据源、模型、规则维度、校验粒度和任务状态会同步影响三个视图。">
    <div class="quality-filter-grid">
      <el-form-item class="quality-time-filter">
        <div class="time-filter-shell">
          <el-radio-group v-model="timePresetModel" size="small" @change="filterActions.changeTimePreset">
            <el-radio-button value="24h">24 小时</el-radio-button>
            <el-radio-button value="7d">7 天</el-radio-button>
            <el-radio-button value="30d">30 天</el-radio-button>
            <el-radio-button value="custom">自定义</el-radio-button>
          </el-radio-group>
          <el-date-picker
            v-model="timeRangeModel"
            type="datetimerange"
            unlink-panels
            value-format="YYYY-MM-DD HH:mm:ss"
            range-separator="~"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            @change="timePresetModel = 'custom'"
          />
        </div>
      </el-form-item>

      <el-form-item>
        <el-select v-model="filters.datasourceId" clearable filterable placeholder="全部数据源" @change="filterActions.changeDatasource">
          <el-option v-for="item in options.datasources" :key="String(item.id)" :label="item.label || item.name || String(item.id)" :value="item.id" />
        </el-select>
      </el-form-item>

      <el-form-item>
        <el-select
          v-model="filters.modelId"
          clearable
          filterable
          remote
          reserve-keyword
          :remote-method="filterActions.searchModels"
          :loading="modelOptionsLoading"
          placeholder="全部模型"
          @visible-change="filterActions.changeModelDropdownVisible"
        >
          <el-option v-for="item in modelOptions" :key="String(item.id)" :label="item.label || item.name || String(item.id)" :value="item.id" />
        </el-select>
      </el-form-item>

      <el-form-item>
        <el-select v-model="filters.ruleDimension" clearable placeholder="全部维度">
          <el-option v-for="item in dimensionOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>

      <el-form-item>
        <el-select v-model="filters.granularity" clearable placeholder="全部粒度">
          <el-option label="表级" value="TABLE" />
          <el-option label="字段级" value="COLUMN" />
        </el-select>
      </el-form-item>

      <el-form-item>
        <el-select v-model="filters.taskStatus" clearable placeholder="全部状态">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="ONLINE" />
        </el-select>
      </el-form-item>

      <template v-if="activeTab === 'issues'">
        <el-form-item>
          <el-select v-model="filters.severity" clearable placeholder="全部级别">
            <el-option v-for="item in severityOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="filters.issueStatus" clearable placeholder="全部状态">
            <el-option v-for="item in issueStatusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="filters.assigneeUserId" clearable filterable placeholder="全部负责人">
            <el-option v-for="item in assigneeOptions" :key="String(item.value)" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </template>
    </div>
    <div class="quality-filter-actions">
      <el-button type="primary" :loading="isLoading" @click="filterActions.reloadAll">查询</el-button>
      <el-button plain @click="filterActions.resetFilters">重置</el-button>
    </div>
  </SectionCard>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type {
  EntityId,
  QualityIssueSeverity,
  QualityIssueStatus,
  QualityMetricOptionsView,
} from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";

type ActiveTab = "overview" | "assets" | "issues";
type TimePreset = "24h" | "7d" | "30d" | "custom";

interface LabelOption<T = string> {
  label: string;
  value: T;
}

interface AssigneeOption {
  label: string;
  value: EntityId;
}

interface QualityFilters {
  datasourceId?: EntityId;
  modelId?: EntityId;
  ruleDimension?: string;
  granularity?: string;
  taskStatus?: string;
  severity?: QualityIssueSeverity;
  issueStatus?: QualityIssueStatus;
  assigneeUserId?: EntityId;
}

interface FilterActions {
  changeTimePreset: (value: string | number | boolean) => void;
  changeDatasource: () => void;
  changeModelDropdownVisible: (visible: boolean) => void;
  searchModels: (keyword: string) => void;
  reloadAll: () => void | Promise<void>;
  resetFilters: () => void | Promise<void>;
}

const props = defineProps<{
  activeTab: ActiveTab;
  isLoading: boolean;
  filters: QualityFilters;
  options: QualityMetricOptionsView;
  modelOptions: QualityMetricOptionsView["models"];
  modelOptionsLoading: boolean;
  timePreset: TimePreset;
  timeRange: [string, string];
  dimensionOptions: Array<LabelOption>;
  severityOptions: Array<LabelOption<QualityIssueSeverity>>;
  issueStatusOptions: Array<LabelOption<QualityIssueStatus>>;
  assigneeOptions: AssigneeOption[];
  filterActions: FilterActions;
}>();

const emit = defineEmits<{
  "update:timePreset": [value: TimePreset];
  "update:timeRange": [value: [string, string]];
}>();

const timePresetModel = computed({
  get: () => props.timePreset,
  set: (value: TimePreset) => emit("update:timePreset", value),
});

const timeRangeModel = computed({
  get: () => props.timeRange,
  set: (value: [string, string]) => emit("update:timeRange", value),
});
</script>

<style scoped>
.quality-filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px;
}

.quality-time-filter {
  grid-column: span 2;
}

.time-filter-shell {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  width: 100%;
}

.quality-filter-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 14px;
}

@media (max-width: 960px) {
  .quality-time-filter {
    grid-column: span 1;
  }
}
</style>
