<template>
  <div
    class="lineage-er-node"
    :class="[
      `lineage-er-node--${renderData.tone}`,
      `lineage-er-node--${renderData.level.toLowerCase()}`,
      { 'lineage-er-node--focus': renderData.focus },
    ]"
    :style="cssVars"
  >
    <div class="lineage-er-node__header">
      <div class="lineage-er-node__title">{{ renderData.title || "--" }}</div>
    </div>

    <div class="lineage-er-node__body">
      <div class="lineage-er-node__meta">
        <div
          v-for="(item, index) in renderData.metaLines"
          :key="`meta-${index}`"
          class="lineage-er-node__meta-line"
        >
          <span class="lineage-er-node__meta-label">{{ item.label }}</span>
          <span class="lineage-er-node__meta-value">{{ item.value }}</span>
        </div>
      </div>

      <div v-if="renderData.level === 'FIELD'" class="lineage-er-node__fields">
        <div
          v-for="field in renderData.fields"
          :key="field.fieldKey || field.fieldName"
          class="lineage-er-node__field"
          :class="`lineage-er-node__field--${field.role}`"
        >
          <span class="lineage-er-node__field-name">{{ field.fieldName || field.fieldKey || "--" }}</span>
          <span v-if="field.fieldKey && field.fieldKey !== field.fieldName" class="lineage-er-node__field-key">
            {{ field.fieldKey }}
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { Graph, Node } from "@antv/x6";
import {
  BODY_BOTTOM_PADDING,
  BODY_TOP_PADDING,
  FIELD_ROW_GAP,
  FIELD_ROW_HEIGHT,
  FIELD_SECTION_MARGIN_TOP,
  HEADER_HEIGHT,
  META_ROW_GAP,
  META_ROW_HEIGHT,
  type LineageNodeCellData,
  type LineageNodeRenderData,
} from "@/components/modelLineageLayout";

const props = defineProps<{
  node: Node;
  graph: Graph;
}>();

const renderData = computed<LineageNodeRenderData>(() => {
  return props.node.getData<LineageNodeCellData>()?.renderData ?? {
    level: "TABLE",
    tone: "platform",
    title: "--",
    metaLines: [],
    fields: [],
  };
});

const cssVars = computed(() => ({
  "--lineage-header-height": `${HEADER_HEIGHT}px`,
  "--lineage-body-top-padding": `${BODY_TOP_PADDING}px`,
  "--lineage-body-bottom-padding": `${BODY_BOTTOM_PADDING}px`,
  "--lineage-meta-gap": `${META_ROW_GAP}px`,
  "--lineage-meta-line-height": `${META_ROW_HEIGHT}px`,
  "--lineage-field-gap": `${FIELD_ROW_GAP}px`,
  "--lineage-field-height": `${FIELD_ROW_HEIGHT}px`,
  "--lineage-field-section-gap": `${FIELD_SECTION_MARGIN_TOP}px`,
}));
</script>

<style scoped>
.lineage-er-node {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  border-radius: 10px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  border: 1px solid #2f83e5;
  background: #ffffff;
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.12);
}

.lineage-er-node__header {
  height: var(--lineage-header-height);
  min-height: var(--lineage-header-height);
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-bottom: 1px solid rgba(22, 96, 191, 0.18);
}

.lineage-er-node__title {
  color: #ffffff;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.35;
  word-break: break-word;
  text-align: center;
}

.lineage-er-node__body {
  flex: 1;
  padding: var(--lineage-body-top-padding) 0 var(--lineage-body-bottom-padding);
  display: flex;
  flex-direction: column;
  background: #ffffff;
}

.lineage-er-node__meta {
  display: flex;
  flex-direction: column;
  gap: var(--lineage-meta-gap);
}

.lineage-er-node__meta-line {
  min-height: var(--lineage-meta-line-height);
  padding: 0 14px 0 16px;
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  color: #243447;
  font-size: 12px;
  font-weight: 500;
  line-height: var(--lineage-meta-line-height);
  border-bottom: 1px solid #e8edf5;
  background: #ffffff;
  box-sizing: border-box;
}

.lineage-er-node__meta-line:nth-child(even) {
  background: #fbfdff;
}

.lineage-er-node__meta-label {
  color: #5f6f82;
  font-weight: 600;
}

.lineage-er-node__meta-value {
  color: #1f2d3d;
  text-align: right;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.lineage-er-node__fields {
  margin-top: var(--lineage-field-section-gap);
  display: flex;
  flex-direction: column;
  gap: var(--lineage-field-gap);
}

.lineage-er-node__field {
  min-height: var(--lineage-field-height);
  padding: 0 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #ffffff;
  color: #1f2d3d;
  box-sizing: border-box;
  gap: 12px;
  border-bottom: 1px solid #e8edf5;
}

.lineage-er-node__field--source,
.lineage-er-node__field--target,
.lineage-er-node__field--both {
  background: #f5f9ff;
}

.lineage-er-node__field-name {
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.lineage-er-node__field-key {
  font-size: 12px;
  color: #7b8794;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 42%;
  font-family: "JetBrains Mono", "Consolas", monospace;
}

.lineage-er-node--platform .lineage-er-node__header {
  background: linear-gradient(180deg, #2b8ef7 0%, #1f76dc 100%);
}

.lineage-er-node--external .lineage-er-node__header {
  background: linear-gradient(180deg, #2f83e5 0%, #2269c8 100%);
}

.lineage-er-node--external {
  border-color: #2e74cf;
}

.lineage-er-node--focus {
  box-shadow: 0 10px 24px rgba(47, 131, 229, 0.22);
}
</style>
