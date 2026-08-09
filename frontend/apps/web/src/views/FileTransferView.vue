<template>
  <div class="studio-page file-transfer-page">
    <div class="studio-toolbar">
      <div>
        <h3>文件传输</h3>
      </div>
    </div>

    <el-tabs v-model="activeView" class="file-transfer-tabs" @tab-change="syncRoute">
      <el-tab-pane name="center" label="传输中心">
        <FileTransferCenterPane v-if="visitedViews.has('center')" />
      </el-tab-pane>
      <el-tab-pane name="tasks" label="预设任务" lazy>
        <FileTransferTasksPane v-if="visitedViews.has('tasks')" />
      </el-tab-pane>
      <el-tab-pane name="runs" label="运行记录" lazy>
        <FileTransferRunsPane v-if="visitedViews.has('runs')" />
      </el-tab-pane>
      <el-tab-pane name="metrics" label="传输指标" lazy>
        <FileTransferMetricsPane v-if="visitedViews.has('metrics')" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import FileTransferCenterPane from "@/components/file-transfer/FileTransferCenterPane.vue";
import FileTransferMetricsPane from "@/components/file-transfer/FileTransferMetricsPane.vue";
import FileTransferRunsPane from "@/components/file-transfer/FileTransferRunsPane.vue";
import FileTransferTasksPane from "@/components/file-transfer/FileTransferTasksPane.vue";

type FileTransferViewName = "center" | "tasks" | "runs" | "metrics";

const route = useRoute();
const router = useRouter();
const activeView = ref<FileTransferViewName>(normalizeView(route.query.view));
const visitedViews = ref(new Set<FileTransferViewName>([activeView.value]));

function normalizeView(value: unknown): FileTransferViewName {
  const normalized = String(value ?? "center").toLowerCase();
  return ["center", "tasks", "runs", "metrics"].includes(normalized)
    ? normalized as FileTransferViewName
    : "center";
}

function syncRoute(value: string | number) {
  const view = normalizeView(value);
  visitedViews.value = new Set([...visitedViews.value, view]);
  if (normalizeView(route.query.view) === view && route.query.view) return;
  void router.replace({
    path: "/file-transfer",
    query: { ...route.query, view },
  });
}

watch(() => route.query.view, (value) => {
  const view = normalizeView(value);
  activeView.value = view;
  visitedViews.value = new Set([...visitedViews.value, view]);
});
</script>

<style scoped>
.file-transfer-page {
  gap: 8px;
}

.file-transfer-tabs {
  min-width: 0;
}

.file-transfer-tabs :deep(.el-tabs__header) {
  margin: 0 0 12px;
}

.file-transfer-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--studio-border);
}

.file-transfer-tabs :deep(.el-tabs__item) {
  min-width: 96px;
  letter-spacing: 0;
}

@media (max-width: 600px) {
  .file-transfer-tabs :deep(.el-tabs__nav-scroll) {
    overflow-x: auto;
  }

  .file-transfer-tabs :deep(.el-tabs__item) {
    min-width: 84px;
    padding: 0 10px;
  }
}
</style>
