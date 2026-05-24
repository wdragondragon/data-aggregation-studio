<template>
  <div class="studio-table-shell" :style="styleVars">
    <slot />
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";

const props = withDefaults(defineProps<{
  minWidth?: string | number;
}>(), {
  minWidth: "1120px",
});

const styleVars = computed(() => {
  const value = typeof props.minWidth === "number" ? `${props.minWidth}px` : props.minWidth;
  return {
    "--studio-table-shell-min-width": value,
  };
});
</script>

<style scoped>
.studio-table-shell {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
}

.studio-table-shell :deep(.el-table) {
  width: 100%;
  min-width: var(--studio-table-shell-min-width);
}

.studio-table-shell :deep(.el-table .cell) {
  white-space: normal;
}
</style>
