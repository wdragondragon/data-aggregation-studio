<template>
  <div class="overflow-action-group" :class="{ 'overflow-action-group--always': collapseMode === 'always' }">
    <div v-if="showInlineAction" class="overflow-action-group__inline">
      <el-button
        :link="visibleItems[0]?.link ?? true"
        :type="visibleItems[0]?.type"
        :plain="visibleItems[0]?.plain"
        :disabled="visibleItems[0]?.disabled"
        size="small"
        @click="handleClick(visibleItems[0])"
      >
        {{ visibleItems[0]?.label }}
      </el-button>
    </div>

    <el-dropdown v-else trigger="click" placement="bottom-end" class="overflow-action-group__dropdown">
      <el-button plain size="small">{{ dropdownLabel }}</el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            v-for="item in visibleItems"
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
const showInlineAction = computed(() => props.collapseMode !== "always" && visibleItems.value.length <= 1);
const dropdownLabel = computed(() => props.dropdownLabel || t("common.more"));

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
  justify-content: flex-end;
}

.overflow-action-group__inline {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 2px;
  min-width: 0;
}

.overflow-action-group__dropdown {
  display: inline-flex;
}

.overflow-action-group__item--danger {
  color: var(--studio-danger);
}

.overflow-action-group--always .overflow-action-group__inline {
  display: none;
}
</style>
