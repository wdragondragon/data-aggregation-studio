<template>
  <div class="workflow-canvas">
    <aside class="workflow-canvas__palette">
      <header>
        <h4>Node Palette</h4>
        <span>Drag onto the canvas</span>
      </header>

      <button
        v-for="item in palette"
        :key="item.type"
        type="button"
        class="workflow-canvas__palette-item"
        :style="{ '--palette-color': item.color }"
        draggable="true"
        @dragstart="startDrag(item)"
      >
        <strong>{{ item.label }}</strong>
        <span>{{ item.caption }}</span>
      </button>
    </aside>

    <div
      ref="containerRef"
      class="workflow-canvas__board"
      @dragover.prevent
      @drop.prevent="handleDrop"
    />
  </div>
</template>

<script setup lang="ts">
import { Graph, Shape } from "@antv/x6";
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { NodeType, WorkflowEdgeDefinition, WorkflowNodeDefinition } from "@studio/api-sdk";

interface PaletteItem {
  type: NodeType;
  label: string;
  caption: string;
  color: string;
}

const props = withDefaults(
  defineProps<{
    nodes: WorkflowNodeDefinition[];
    edges: WorkflowEdgeDefinition[];
  }>(),
  {
    nodes: () => [],
    edges: () => [],
  },
);

const emit = defineEmits<{
  "update:nodes": [value: WorkflowNodeDefinition[]];
  "update:edges": [value: WorkflowEdgeDefinition[]];
  "select-node": [nodeCode: string | null];
}>();

const palette: PaletteItem[] = [
  { type: "ETL_SINGLE", label: "Single ETL", caption: "Reader -> Transformer -> Writer", color: "#b85c38" },
  { type: "FUSION", label: "Fusion", caption: "Multi-model join and merge", color: "#ca8d2e" },
  { type: "CONSISTENCY", label: "Consistency", caption: "Cross-source compare", color: "#2f7a53" },
  { type: "HTTP", label: "HTTP", caption: "External callback or webhook", color: "#56697a" },
  { type: "SHELL", label: "Shell", caption: "Local runtime command", color: "#6f4f8d" },
];

const containerRef = ref<HTMLDivElement>();
const draggingItem = ref<PaletteItem | null>(null);
let graph: Graph | null = null;
let syncingFromProps = false;

function createGraphNode(node: WorkflowNodeDefinition, x = 80, y = 80) {
  const paletteItem = palette.find((item) => item.type === node.nodeType);
  return graph?.createNode({
    id: node.nodeCode,
    x: Number(node.config?.canvasX ?? x),
    y: Number(node.config?.canvasY ?? y),
    width: 210,
    height: 82,
    shape: "rect",
    data: node,
    attrs: {
      body: {
        fill: "#fffdf7",
        stroke: paletteItem?.color ?? "#b85c38",
        strokeWidth: 1.6,
        rx: 18,
        ry: 18,
      },
      label: {
        text: `${node.nodeName}\n${node.nodeType ?? ""}`,
        fill: "#2c2118",
        fontSize: 14,
        fontWeight: 600,
      },
    },
  });
}

function syncFromGraph() {
  if (!graph || syncingFromProps) {
    return;
  }
  const nodes: WorkflowNodeDefinition[] = graph.getNodes().map((node) => {
    const data = node.getData<WorkflowNodeDefinition>();
    const position = node.position();
    return {
      ...data,
      nodeCode: node.id,
      nodeName: data?.nodeName ?? node.id,
      nodeType: data?.nodeType,
      config: {
        ...(data?.config ?? {}),
        canvasX: position.x,
        canvasY: position.y,
      },
      fieldMappings: data?.fieldMappings ?? [],
    };
  });
  const edges: WorkflowEdgeDefinition[] = graph
    .getEdges()
    .filter((edge) => edge.getSourceCellId() && edge.getTargetCellId())
    .map((edge) => ({
      fromNodeCode: edge.getSourceCellId() as string,
      toNodeCode: edge.getTargetCellId() as string,
      condition: (edge.getData()?.condition as WorkflowEdgeDefinition["condition"]) ?? "ON_SUCCESS",
    }));

  emit("update:nodes", nodes);
  emit("update:edges", edges);
}

function renderGraph() {
  if (!graph) {
    return;
  }
  syncingFromProps = true;
  graph.clearCells();
  props.nodes.forEach((node, index) => {
    const x = 70 + (index % 3) * 250;
    const y = 60 + Math.floor(index / 3) * 150;
    const created = createGraphNode(node, x, y);
    if (created) {
      graph?.addNode(created);
    }
  });
  props.edges.forEach((edge) => {
    graph?.addEdge({
      source: edge.fromNodeCode,
      target: edge.toNodeCode,
      attrs: {
        line: {
          stroke: "#8e4021",
          strokeWidth: 1.3,
          targetMarker: {
            name: "classic",
            size: 8,
          },
        },
      },
      data: {
        condition: edge.condition ?? "ON_SUCCESS",
      },
    });
  });
  nextTick(() => {
    syncingFromProps = false;
  });
}

function initGraph() {
  if (!containerRef.value) {
    return;
  }
  graph = new Graph({
    container: containerRef.value,
    grid: {
      visible: true,
      type: "doubleMesh",
      args: [
        { color: "rgba(101, 80, 63, 0.08)", thickness: 1 },
        { color: "rgba(101, 80, 63, 0.03)", thickness: 1, factor: 4 },
      ],
    },
    background: {
      color: "#fffaf3",
    },
    panning: true,
    mousewheel: {
      enabled: true,
      modifiers: ["ctrl", "meta"],
      minScale: 0.6,
      maxScale: 1.8,
    },
    connecting: {
      connector: "rounded",
      router: "manhattan",
      anchor: "center",
      connectionPoint: "anchor",
      allowBlank: false,
      allowLoop: false,
      highlight: true,
      createEdge() {
        return new Shape.Edge({
          attrs: {
            line: {
              stroke: "#8e4021",
              strokeWidth: 1.4,
              targetMarker: {
                name: "classic",
                size: 8,
              },
            },
          },
          data: {
            condition: "ON_SUCCESS",
          },
        });
      },
      validateConnection({ sourceCell, targetCell }) {
        return Boolean(sourceCell && targetCell && sourceCell.id !== targetCell.id);
      },
    },
  });

  graph.on("node:click", ({ node }: { node: { id: string } }) => emit("select-node", node.id));
  graph.on("blank:click", () => emit("select-node", null));
  graph.on("node:change:position", syncFromGraph);
  graph.on("node:removed", syncFromGraph);
  graph.on("edge:connected", syncFromGraph);
  graph.on("edge:removed", syncFromGraph);
  graph.on("node:added", syncFromGraph);

  renderGraph();
}

function startDrag(item: PaletteItem) {
  draggingItem.value = item;
}

function handleDrop(event: DragEvent) {
  if (!graph || !draggingItem.value) {
    return;
  }
  const localPoint = graph.clientToLocal(event.clientX, event.clientY);
  const item = draggingItem.value;
  const created = createGraphNode(
    {
      nodeCode: `${item.type.toLowerCase()}_${Date.now()}`,
      nodeName: item.label,
      nodeType: item.type,
      config: {},
      fieldMappings: [],
    },
    localPoint.x,
    localPoint.y,
  );
  if (created) {
    graph.addNode(created);
    syncFromGraph();
  }
  draggingItem.value = null;
}

watch(
  () => [props.nodes, props.edges],
  () => {
    renderGraph();
  },
  { deep: true },
);

onMounted(initGraph);
onBeforeUnmount(() => {
  graph?.dispose();
  graph = null;
  draggingItem.value = null;
});
</script>

<style scoped>
.workflow-canvas {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 18px;
  min-height: 620px;
}

.workflow-canvas__palette {
  display: grid;
  gap: 12px;
  align-content: start;
  padding: 18px;
  border: 1px solid var(--studio-border);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.76);
}

.workflow-canvas__palette header h4 {
  margin: 0;
}

.workflow-canvas__palette header span {
  font-size: 12px;
  color: var(--studio-text-soft);
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.workflow-canvas__palette-item {
  display: grid;
  gap: 4px;
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--palette-color) 24%, transparent);
  border-radius: 18px;
  text-align: left;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.86), rgba(255, 250, 240, 0.92));
  cursor: grab;
}

.workflow-canvas__palette-item span {
  color: var(--studio-text-soft);
  font-size: 13px;
}

.workflow-canvas__board {
  min-height: 620px;
  border: 1px solid var(--studio-border);
  border-radius: 28px;
  overflow: hidden;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.5);
}

@media (max-width: 1080px) {
  .workflow-canvas {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
