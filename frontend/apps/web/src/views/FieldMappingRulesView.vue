<template>
  <div class="studio-page">
    <div class="studio-toolbar">
      <div>
        <h3>{{ t("web.fieldMappingRules.heading") }}</h3>
        <p>{{ t("web.fieldMappingRules.description") }}</p>
      </div>
      <div class="studio-toolbar-actions">
        <el-button type="primary" @click="router.push('/field-mapping-rules/new')">{{ t("web.fieldMappingRules.newRule") }}</el-button>
        <el-button plain @click="loadRules">{{ t("common.refresh") }}</el-button>
      </div>
    </div>

    <SectionCard :title="t('web.fieldMappingRules.filterTitle')" :description="t('web.fieldMappingRules.filterDescription')">
      <div class="rule-filter-grid">
        <el-input v-model="filters.keyword" clearable :placeholder="t('web.fieldMappingRules.keywordPlaceholder')" />
        <el-select v-model="filters.mappingType" clearable :placeholder="t('web.fieldMappingRules.mappingTypePlaceholder')">
          <el-option v-for="option in mappingTypeOptions" :key="option" :label="option" :value="option" />
        </el-select>
        <el-select v-model="filters.enabled" clearable :placeholder="t('web.fieldMappingRules.enabledPlaceholder')">
          <el-option :label="t('common.on')" :value="true" />
          <el-option :label="t('common.off')" :value="false" />
        </el-select>
        <div class="rule-filter-actions">
          <el-button type="primary" @click="loadRules">{{ t("common.search") }}</el-button>
          <el-button plain @click="resetFilters">{{ t("common.reset") }}</el-button>
        </div>
      </div>
    </SectionCard>

    <SectionCard :title="t('web.fieldMappingRules.listTitle')" :description="t('web.fieldMappingRules.listDescription')">
      <el-table :data="page.items" border>
        <el-table-column :label="t('common.sequence')" width="78" align="center" header-align="center">
          <template #default="{ $index }">
            {{ (page.pageNo - 1) * page.pageSize + $index + 1 }}
          </template>
        </el-table-column>
        <el-table-column prop="mappingName" :label="t('web.fieldMappingRules.mappingName')" min-width="180" />
        <el-table-column prop="mappingType" :label="t('web.fieldMappingRules.mappingType')" min-width="160" />
        <el-table-column prop="mappingCode" :label="t('web.fieldMappingRules.mappingCode')" min-width="180" />
        <el-table-column :label="t('web.fieldMappingRules.enabled')" width="110" align="center" header-align="center">
          <template #default="{ row }">
            <StatusPill :label="row.enabled ? t('common.on') : t('common.off')" :tone="row.enabled ? 'success' : 'neutral'" />
          </template>
        </el-table-column>
        <el-table-column prop="createdByName" :label="t('web.fieldMappingRules.createdBy')" min-width="140" />
        <el-table-column prop="createdAt" :label="t('web.fieldMappingRules.createdAt')" min-width="180" />
        <el-table-column :label="t('fieldMapping.actions')" width="120" align="center" header-align="center">
          <template #default="{ row }">
            <OverflowActionGroup :items="buildRuleActions(row)" />
          </template>
        </el-table-column>
      </el-table>

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
import { onMounted, reactive } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { FieldMappingRuleView } from "@studio/api-sdk";
import { OverflowActionGroup, SectionCard, StatusPill } from "@studio/ui";
import { studioApi } from "@/api/studio";

const { t } = useI18n();
const router = useRouter();

const mappingTypeOptions = [
  "过滤",
  "规整",
  "脱敏",
  "加密",
];

const filters = reactive<{
  keyword: string;
  mappingType: string;
  enabled?: boolean;
}>({
  keyword: "",
  mappingType: "",
  enabled: undefined,
});

const page = reactive<{
  pageNo: number;
  pageSize: number;
  total: number;
  items: FieldMappingRuleView[];
}>({
  pageNo: 1,
  pageSize: 20,
  total: 0,
  items: [],
});

async function loadRules() {
  try {
    const result = await studioApi.fieldMappingRules.list({
      pageNo: page.pageNo,
      pageSize: page.pageSize,
      keyword: filters.keyword.trim() || undefined,
      mappingType: filters.mappingType.trim() || undefined,
      enabled: filters.enabled,
    });
    page.pageNo = Number(result.pageNo ?? 1);
    page.pageSize = Number(result.pageSize ?? page.pageSize);
    page.total = Number(result.total ?? 0);
    page.items = result.items;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.fieldMappingRules.loadFailed"));
  }
}

function resetFilters() {
  filters.keyword = "";
  filters.mappingType = "";
  filters.enabled = undefined;
  page.pageNo = 1;
  void loadRules();
}

function handlePageSizeChange() {
  page.pageNo = 1;
  void loadRules();
}

function buildRuleActions(rule: FieldMappingRuleView) {
  return [
    { key: "edit", label: t("common.edit"), type: "primary", onClick: () => { void router.push(`/field-mapping-rules/${rule.id}/edit`); } },
    { key: "delete", label: t("common.delete"), type: "danger", onClick: () => deleteRule(rule) },
  ];
}

async function deleteRule(rule: FieldMappingRuleView) {
  if (!rule.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("web.fieldMappingRules.deleteConfirmMessage", { name: rule.mappingName }),
      t("common.confirm"),
      { type: "warning" },
    );
    await studioApi.fieldMappingRules.delete(rule.id);
    ElMessage.success(t("web.fieldMappingRules.deleteSuccess"));
    await loadRules();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(error instanceof Error ? error.message : t("web.fieldMappingRules.deleteFailed"));
    }
  }
}

onMounted(loadRules);
</script>

<style scoped>
.rule-filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  align-items: end;
}

.rule-filter-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}
</style>
