<template>
  <div class="overflow-action-group" :class="{ 'overflow-action-group--always': collapseMode === 'always' }">
    <div v-if="inlineItems.length" class="overflow-action-group__inline">
      <el-button
        v-for="item in inlineItems"
        :key="item.key"
        :link="item.link ?? true"
        :type="item.type"
        :plain="item.plain"
        :disabled="item.disabled"
        size="small"
        @click="handleClick(item)"
      >
        {{ item.label }}
      </el-button>
    </div>

    <el-dropdown v-if="dropdownItems.length" trigger="click" placement="bottom-end" class="overflow-action-group__dropdown">
      <el-button plain size="small">{{ dropdownLabel }}</el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            v-for="item in dropdownItems"
            :key="item.key"
            :disabled="item.disabled"
            :divided="item.divided"
            :class="{ 'overflow-action-group__item--danger': item.type === 'danger' }"
            @click="handleClick(item)"
          >
            {{ item.label }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { OverflowActionItem } from "./types";

const props = withDefaults(defineProps<{
  items: OverflowActionItem[];
  collapseMode?: "responsive" | "always";
  dropdownLabel?: string;
}>(), {
  collapseMode: "responsive",
  dropdownLabel: "",
});

const { t } = useI18n();

const visibleItems = computed(() => (props.items ?? []).filter((item) => item && item.visible !== false));
const pinnedItems = computed(() => visibleItems.value.filter((item) => isPinnedAction(item)));
const inlineItems = computed(() => {
  if (props.collapseMode === "always") {
    return [];
  }
  if (pinnedItems.value.length) {
    return pinnedItems.value;
  }
  return visibleItems.value.length <= 1 ? visibleItems.value : [];
});
const dropdownItems = computed(() => {
  if (props.collapseMode === "always") {
    return visibleItems.value;
  }
  if (pinnedItems.value.length) {
    return visibleItems.value.filter((item) => !isPinnedAction(item));
  }
  return visibleItems.value.length <= 1 ? [] : visibleItems.value;
});
const dropdownLabel = computed(() => props.dropdownLabel || t("common.more"));

function isPinnedAction(item: OverflowActionItem) {
  return item.key === "edit" || item.key === "delete";
}

function handleClick(item?: OverflowActionItem) {
  if (!item || item.disabled || !item.onClick) {
    return;
  }
  void item.onClick();
}
</script>

<style scoped>
.overflow-action-group {
  display: flex;
  align-items: center;
  gap: 4px;
  justify-content: center;
  min-width: 0;
  white-space: nowrap;
}

.overflow-action-group__inline {
  display: flex;
  flex-wrap: nowrap;
  justify-content: center;
  gap: 4px;
  min-width: 0;
}

.overflow-action-group__dropdown {
  display: inline-flex;
}

.overflow-action-group :deep(.el-button + .el-button) {
  margin-left: 0;
}

.overflow-action-group__item--danger {
  color: var(--studio-danger);
}

.overflow-action-group--always .overflow-action-group__inline {
  display: none;
}
</style>
