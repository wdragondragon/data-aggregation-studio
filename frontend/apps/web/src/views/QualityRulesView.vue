<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>质量规则</h3>
        <p>统一维护系统级和当前项目级的数据质量规则，支持启停、删除、参数解析和复用。</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" @click="router.push('/quality-rules/new')">新建规则</el-button>
        <el-button plain :disabled="!selectedIds.length" @click="batchDelete">批量删除</el-button>
        <el-button plain @click="loadRules">刷新</el-button>
      </div>
    </div>

    <SectionCard title="筛选条件" description="按名称、维度、作用域和启用状态筛选质量规则。">
      <div class="rule-filter-grid">
        <el-input v-model="filters.keyword" class="rule-filter-keyword" clearable placeholder="规则名称或规则编码" />
        <el-select v-model="filters.ruleDimension" class="rule-filter-select" clearable placeholder="规则维度">
          <el-option v-for="option in dimensionOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select v-model="filters.scopeType" class="rule-filter-select" clearable placeholder="规则作用域">
          <el-option label="系统级" value="SYSTEM" />
          <el-option label="项目级" value="PROJECT" />
        </el-select>
        <el-select v-model="filters.enabled" class="rule-filter-select" clearable placeholder="启用状态">
          <el-option label="启用" :value="true" />
          <el-option label="停用" :value="false" />
        </el-select>
        <div class="rule-filter-actions">
          <el-button type="primary" @click="loadRules">查询</el-button>
          <el-button plain @click="resetFilters">重置</el-button>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="规则列表" description="规则详情页支持查看 SQL 模板、输入参数和输出定义。">
      <StudioTableShell min-width="1500px">
        <el-table :data="page.items" border @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="48" />
        <el-table-column label="序号" width="78" align="center" header-align="center">
          <template #default="{ $index }">
            {{ (page.pageNo - 1) * page.pageSize + $index + 1 }}
          </template>
        </el-table-column>
        <el-table-column prop="ruleName" label="规则名称" min-width="180" />
        <el-table-column prop="ruleCode" label="规则编码" min-width="180" />
        <el-table-column label="规则维度" min-width="120">
          <template #default="{ row }">{{ resolveDimensionLabel(row.ruleDimension) }}</template>
        </el-table-column>
        <el-table-column label="粒度" width="110" align="center" header-align="center">
          <template #default="{ row }">{{ row.granularity === 'COLUMN' ? '字段级' : '表级' }}</template>
        </el-table-column>
        <el-table-column label="作用域" width="110" align="center" header-align="center">
          <template #default="{ row }">{{ row.scopeType === 'SYSTEM' ? '系统级' : '项目级' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="row.enabled ? '启用' : '停用'" :tone="row.enabled ? 'success' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column prop="createdByName" label="创建人" min-width="120" />
        <el-table-column prop="createdAt" label="创建时间" min-width="180" />
        <el-table-column prop="updatedAt" label="更新时间" min-width="180" />
        <el-table-column label="操作" width="150" align="center" header-align="center" fixed="right">
          <template #default="{ row }">
            <OverflowActionGroup :items="buildActions(row)" />
          </template>
        </el-table-column>
        </el-table>
      </StudioTableShell>

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="page.pageNo"
          v-model:page-size="page.pageSize"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="page.total"
          @current-change="loadRules"
          @size-change="handlePageSizeChange"
        />
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import type { QualityRuleListView } from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StatusPill, StudioTableShell } from "@studio/ui";
import { studioApi } from "@/api/studio";

const router = useRouter();

const dimensionOptions = [
  { label: "一致性", value: "CONSISTENCY" },
  { label: "准确性", value: "ACCURACY" },
  { label: "唯一性", value: "UNIQUENESS" },
  { label: "及时性", value: "TIMELINESS" },
  { label: "完整性", value: "COMPLETENESS" },
  { label: "有效性", value: "VALIDITY" },
];

const filters = reactive<{
  keyword: string;
  ruleDimension: string;
  scopeType: string;
  enabled?: boolean;
}>({
  keyword: "",
  ruleDimension: "",
  scopeType: "",
  enabled: undefined,
});

const page = reactive<{
  pageNo: number;
  pageSize: number;
  total: number;
  items: QualityRuleListView[];
}>({
  pageNo: 1,
  pageSize: 20,
  total: 0,
  items: [],
});

const selectedIds = ref<Array<string | number>>([]);

async function loadRules() {
  try {
    const result = await studioApi.qualityRules.list({
      pageNo: page.pageNo,
      pageSize: page.pageSize,
      keyword: filters.keyword.trim() || undefined,
      ruleDimension: filters.ruleDimension || undefined,
      scopeType: filters.scopeType || undefined,
      enabled: filters.enabled,
    });
    page.pageNo = Number(result.pageNo ?? 1);
    page.pageSize = Number(result.pageSize ?? 20);
    page.total = Number(result.total ?? 0);
    page.items = result.items ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载质量规则失败");
  }
}

function resolveDimensionLabel(value?: string) {
  return dimensionOptions.find((item) => item.value === value)?.label ?? value ?? "-";
}

function buildActions(row: QualityRuleListView) {
  return [
    { key: "edit", label: "编辑", type: "primary", disabled: !row.editable, onClick: () => { void router.push(`/quality-rules/${row.id}/edit`); } },
    { key: row.enabled ? "disable" : "enable", label: row.enabled ? "停用" : "启用", disabled: !row.editable, onClick: () => toggleRule(row) },
    { key: "delete", label: "删除", type: "danger", disabled: !row.deletable, onClick: () => deleteRule(row) },
  ];
}

function handleSelectionChange(rows: QualityRuleListView[]) {
  selectedIds.value = rows.map((item) => item.id!).filter(Boolean);
}

function handlePageSizeChange() {
  page.pageNo = 1;
  void loadRules();
}

function resetFilters() {
  filters.keyword = "";
  filters.ruleDimension = "";
  filters.scopeType = "";
  filters.enabled = undefined;
  page.pageNo = 1;
  void loadRules();
}

async function toggleRule(rule: QualityRuleListView) {
  if (!rule.id) {
    return;
  }
  try {
    const updated = rule.enabled
      ? await studioApi.qualityRules.disable(rule.id)
      : await studioApi.qualityRules.enable(rule.id);
    if (rule.enabled) {
      ElMessage.success("规则已停用");
    } else {
      ElMessage.success("规则已启用");
    }
    patchRuleRow(rule, updated);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "更新规则状态失败");
  }
}

function patchRuleRow(rule: QualityRuleListView, patch: Partial<QualityRuleListView>) {
  const target = page.items.find((item) => item.id === rule.id);
  if (!target) {
    return;
  }
  const keys: (keyof QualityRuleListView)[] = [
    "updatedAt",
    "ruleName",
    "ruleCode",
    "scopeType",
    "ruleDimension",
    "granularity",
    "enabled",
    "createdBy",
    "createdByName",
    "editable",
    "deletable",
  ];
  const next: Partial<QualityRuleListView> = {};
  for (const key of keys) {
    if (Object.prototype.hasOwnProperty.call(patch, key)) {
      (next as Record<string, unknown>)[key] = (patch as Record<string, unknown>)[key];
    }
  }
  Object.assign(target, next);
}

async function deleteRule(rule: QualityRuleListView) {
  if (!rule.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除规则“${rule.ruleName}”吗？`, "提示", { type: "warning" });
    await studioApi.qualityRules.delete(rule.id);
    ElMessage.success("规则已删除");
    removeRuleRows([rule.id]);
    if (page.items.length === 0 && page.total > 0) {
      page.pageNo = Math.max(1, page.pageNo - 1);
      await loadRules();
    }
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "删除规则失败");
    }
  }
}

async function batchDelete() {
  if (!selectedIds.value.length) {
    return;
  }
  const ids = [...selectedIds.value];
  try {
    await ElMessageBox.confirm(`确认批量删除已选中的 ${ids.length} 条规则吗？`, "提示", { type: "warning" });
    await studioApi.qualityRules.batchDelete(ids);
    ElMessage.success("批量删除成功");
    removeRuleRows(ids);
    if (page.items.length === 0 && page.total > 0) {
      page.pageNo = Math.max(1, page.pageNo - 1);
      await loadRules();
    }
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : "批量删除失败");
    }
  }
}

function removeRuleRows(ids: Array<string | number>) {
  const idSet = new Set(ids.map((id) => String(id)));
  const beforeCount = page.items.length;
  page.items = page.items.filter((item) => !idSet.has(String(item.id)));
  const removedCount = beforeCount - page.items.length;
  if (removedCount > 0) {
    page.total = Math.max(0, page.total - removedCount);
  }
  selectedIds.value = selectedIds.value.filter((id) => !idSet.has(String(id)));
}

onMounted(loadRules);
</script>

<style scoped>
.rule-filter-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.rule-filter-keyword {
  flex: 0 1 300px;
}

.rule-filter-select {
  width: 168px;
}

.rule-filter-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

@media (max-width: 760px) {
  .rule-filter-grid > * {
    width: 100%;
    flex-basis: 100%;
  }

  .rule-filter-actions {
    justify-content: flex-start;
  }
}
</style>
