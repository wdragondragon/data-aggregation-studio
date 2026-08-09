<template>
  <SectionCard :title="t('web.workflows.canvasTitle')" :description="t('web.workflows.canvasDescription')">
    <WorkflowCanvas
      :nodes="nodes"
      :edges="edges"
      :palette-types="['COLLECTION_TASK', 'QUALITY_TASK', 'FILE_TRANSFER', 'DATA_SCRIPT', 'HTTP', 'SHELL']"
      @update:nodes="emit('update:nodes', $event)"
      @update:edges="emit('update:edges', $event)"
      @select-node="emit('select-node', $event)"
    />
  </SectionCard>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import type { WorkflowSaveRequest } from "@studio/api-sdk";
import { SectionCard } from "@studio/ui";
import { WorkflowCanvas } from "@studio/workflow-designer";

defineProps<{
  nodes: WorkflowSaveRequest["nodes"];
  edges: WorkflowSaveRequest["edges"];
}>();

const emit = defineEmits<{
  "update:nodes": [value: WorkflowSaveRequest["nodes"]];
  "update:edges": [value: WorkflowSaveRequest["edges"]];
  "select-node": [value: string | null];
}>();

const { t } = useI18n();
</script>
