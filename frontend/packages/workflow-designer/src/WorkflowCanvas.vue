<template>
  <div class="workflow-canvas" :class="{ 'workflow-canvas--readonly': readonly }">
    <aside v-if="!hidePaletteSection" class="workflow-canvas__palette">
      <header>
        <h4>{{ t("workflowCanvas.paletteTitle") }}</h4>
        <span>{{ t("workflowCanvas.paletteHint") }}</span>
      </header>

      <button
        v-for="item in palette"
        :key="item.type"
        type="button"
        class="workflow-canvas__palette-item"
        :style="{ '--palette-color': item.color }"
        @mousedown.prevent="startDrag($event, item)"
      >
        <strong>{{ item.label }}</strong>
        <span>{{ item.caption }}</span>
      </button>
    </aside>

    <div ref="containerRef" class="workflow-canvas__board" />
  </div>
</template>

<script setup lang="ts">
import { Graph, Shape } from "@antv/x6";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
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
    paletteTypes?: NodeType[];
    nodeStatuses?: Record<string, string>;
    readonly?: boolean;
    hidePalette?: boolean;
  }>(),
  {
    nodes: () => [],
    edges: () => [],
    paletteTypes: () => ["COLLECTION_TASK", "DATA_SCRIPT", "CONSISTENCY", "HTTP", "SHELL"],
    nodeStatuses: () => ({}),
    readonly: false,
    hidePalette: false,
  },
);

const emit = defineEmits<{
  "update:nodes": [value: WorkflowNodeDefinition[]];
  "update:edges": [value: WorkflowEdgeDefinition[]];
  "select-node": [nodeCode: string | null];
}>();

const { t } = useI18n();

const paletteRegistry = computed<Record<NodeType, PaletteItem>>(() => ({
  COLLECTION_TASK: {
    type: "COLLECTION_TASK",
    label: t("workflowCanvas.nodeTypes.COLLECTION_TASK.label"),
    caption: t("workflowCanvas.nodeTypes.COLLECTION_TASK.caption"),
    color: "#175cd3",
  },
  DATA_SCRIPT: {
    type: "DATA_SCRIPT",
    label: t("workflowCanvas.nodeTypes.DATA_SCRIPT.label"),
    caption: t("workflowCanvas.nodeTypes.DATA_SCRIPT.caption"),
    color: "#0f766e",
  },
  ETL_SINGLE: {
    type: "ETL_SINGLE",
    label: t("workflowCanvas.nodeTypes.ETL_SINGLE.label"),
    caption: t("workflowCanvas.nodeTypes.ETL_SINGLE.caption"),
    color: "#b85c38",
  },
  FUSION: {
    type: "FUSION",
    label: t("workflowCanvas.nodeTypes.FUSION.label"),
    caption: t("workflowCanvas.nodeTypes.FUSION.caption"),
    color: "#ca8d2e",
  },
  CONSISTENCY: {
    type: "CONSISTENCY",
    label: t("workflowCanvas.nodeTypes.CONSISTENCY.label"),
    caption: t("workflowCanvas.nodeTypes.CONSISTENCY.caption"),
    color: "#2f7a53",
  },
  HTTP: {
    type: "HTTP",
    label: t("workflowCanvas.nodeTypes.HTTP.label"),
    caption: t("workflowCanvas.nodeTypes.HTTP.caption"),
    color: "#56697a",
  },
  SHELL: {
    type: "SHELL",
    label: t("workflowCanvas.nodeTypes.SHELL.label"),
    caption: t("workflowCanvas.nodeTypes.SHELL.caption"),
    color: "#6f4f8d",
  },
}));

const palette = computed(() => props.paletteTypes.map((type) => paletteRegistry.value[type]).filter(Boolean));
const hidePaletteSection = computed(() => props.readonly || props.hidePalette || palette.value.length === 0);

const containerRef = ref<HTMLDivElement>();
let graph: Graph | null = null;
let syncingFromProps = false;
let syncingFromGraph = false;
const draggingItem = ref<PaletteItem | null>(null);

function resolveNodeTone(status?: string, fallbackColor = "#175cd3") {
  const normalized = String(status ?? "NOT_RUN").toUpperCase();
  if (normalized.includes("FAIL")) {
    return {
      fill: "rgba(220, 38, 38, 0.14)",
      stroke: "#dc2626",
      labelFill: "#7f1d1d",
    };
  }
  if (normalized.includes("RUN")) {
    return {
      fill: "rgba(22, 163, 74, 0.14)",
      stroke: "#16a34a",
      labelFill: "#14532d",
    };
  }
  if (normalized.includes("SUCCESS")) {
    return {
      fill: "rgba(23, 92, 211, 0.14)",
      stroke: "#175cd3",
      labelFill: "#17324d",
    };
  }
  return {
    fill: "rgba(148, 163, 184, 0.18)",
    stroke: props.readonly ? "#94a3b8" : fallbackColor,
    labelFill: "#475569",
  };
}

function createGraphNode(node: WorkflowNodeDefinition, x = 80, y = 80) {
  const paletteItem = paletteRegistry.value[node.nodeType ?? "HTTP"];
  const nodeTone = resolveNodeTone(props.nodeStatuses[node.nodeCode], paletteItem?.color ?? "#175cd3");
  const labelText = buildNodeLabel(node, paletteItem?.label ?? node.nodeType ?? "");
  return graph?.createNode({
    id: node.nodeCode,
    x: Number(node.config?.canvasX ?? x),
    y: Number(node.config?.canvasY ?? y),
    width: 220,
    height: 88,
    shape: "rect",
    data: node,
    attrs: {
      body: {
        fill: nodeTone.fill,
        stroke: nodeTone.stroke,
        strokeWidth: 1.8,
        rx: 18,
        ry: 18,
      },
      label: {
        text: labelText,
        fill: nodeTone.labelFill,
        fontSize: 14,
        fontWeight: 600,
      },
    },
    ports: {
      groups: {
        in: {
          position: "left",
          attrs: {
            circle: {
              r: 7,
              magnet: !props.readonly,
              stroke: nodeTone.stroke,
              strokeWidth: 2,
              fill: "#ffffff",
            },
          },
        },
        out: {
          position: "right",
          attrs: {
            circle: {
              r: 7,
              magnet: !props.readonly,
              stroke: nodeTone.stroke,
              strokeWidth: 2,
              fill: nodeTone.stroke,
            },
          },
        },
      },
      items: [
        { id: "in", group: "in" },
        { id: "out", group: "out" },
      ],
    },
  });
}

function buildNodeLabel(node: WorkflowNodeDefinition, typeLabel: string) {
  const taskName = typeof node.config?.collectionTaskName === "string"
    ? node.config.collectionTaskName.trim()
    : "";
  const scriptName = typeof node.config?.scriptName === "string"
    ? node.config.scriptName.trim()
    : "";
  const nodeName = typeof node.nodeName === "string" ? node.nodeName.trim() : "";
  const primary = taskName || scriptName || nodeName || typeLabel || node.nodeCode;
  const secondary = typeLabel && typeLabel !== primary ? typeLabel : "";
  return secondary ? `${primary}\n${secondary}` : primary;
}

function syncFromGraph() {
  if (!graph || syncingFromProps) {
    return;
  }
  syncingFromGraph = true;
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
  nextTick(() => {
    syncingFromGraph = false;
  });
}

function renderGraph() {
  if (!graph) {
    return;
  }
  syncingFromProps = true;
  graph.clearCells();
  props.nodes.forEach((node, index) => {
    const x = 80 + (index % 3) * 260;
    const y = 70 + Math.floor(index / 3) * 150;
    const created = createGraphNode(node, x, y);
    if (created) {
      graph?.addNode(created);
    }
  });
  props.edges.forEach((edge) => {
    graph?.addEdge({
      source: { cell: edge.fromNodeCode, port: "out" },
      target: { cell: edge.toNodeCode, port: "in" },
      attrs: {
        line: {
          stroke: "#2f5fa9",
          strokeWidth: 1.6,
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
        { color: "rgba(23, 92, 211, 0.08)", thickness: 1 },
        { color: "rgba(23, 92, 211, 0.03)", thickness: 1, factor: 4 },
      ],
    },
    background: {
      color: "#f8fbff",
    },
    panning: true,
    interacting: {
      nodeMovable: !props.readonly,
      edgeMovable: !props.readonly,
      magnetConnectable: !props.readonly,
      edgeLabelMovable: false,
    },
    mousewheel: {
      enabled: true,
      modifiers: ["ctrl", "meta"],
      minScale: 0.6,
      maxScale: 1.8,
    },
    connecting: {
      connector: "rounded",
      router: "manhattan",
      allowBlank: false,
      allowLoop: false,
      allowNode: false,
      allowPort: !props.readonly,
      snap: true,
      highlight: !props.readonly,
      createEdge() {
        return new Shape.Edge({
          attrs: {
            line: {
              stroke: "#2f5fa9",
              strokeWidth: 1.6,
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
      validateConnection({ sourceCell, targetCell, sourcePort, targetPort }) {
        if (props.readonly) {
          return false;
        }
        return Boolean(sourceCell && targetCell && sourceCell.id !== targetCell.id && sourcePort === "out" && targetPort === "in");
      },
    },
  });

  graph.on("node:click", ({ node }: { node: { id: string } }) => emit("select-node", node.id));
  graph.on("blank:click", () => emit("select-node", null));
  graph.on("node:change:position", syncFromGraph);
  graph.on("node:removed", syncFromGraph);
  graph.on("edge:connected", ({ edge }) => {
    if (!edge.getData()) {
      edge.setData({ condition: "ON_SUCCESS" });
    }
    syncFromGraph();
  });
  graph.on("edge:removed", syncFromGraph);
  graph.on("node:added", ({ node }) => {
    if (!syncingFromProps) {
      emit("select-node", node.id);
      syncFromGraph();
    }
  });

  renderGraph();
}

function startDrag(event: MouseEvent, item: PaletteItem) {
  if (props.readonly) {
    return;
  }
  draggingItem.value = item;
  event.preventDefault();
}

function handleWindowMouseUp(event: MouseEvent) {
  if (props.readonly || !graph || !containerRef.value || !draggingItem.value) {
    draggingItem.value = null;
    return;
  }
  const rect = containerRef.value.getBoundingClientRect();
  const inside = event.clientX >= rect.left
    && event.clientX <= rect.right
    && event.clientY >= rect.top
    && event.clientY <= rect.bottom;
  if (!inside) {
    draggingItem.value = null;
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
    emit("select-node", created.id);
    syncFromGraph();
  }
  draggingItem.value = null;
}

watch(
  () => [props.nodes, props.edges, props.nodeStatuses, props.readonly, hidePaletteSection.value],
  () => {
    if (syncingFromGraph) {
      return;
    }
    renderGraph();
  },
  { deep: true },
);

watch(palette, () => {
  if (!syncingFromGraph) {
    renderGraph();
  }
});

onMounted(initGraph);
onBeforeUnmount(() => {
  graph?.dispose();
  graph = null;
  window.removeEventListener("mouseup", handleWindowMouseUp);
  draggingItem.value = null;
});

onMounted(() => {
  window.addEventListener("mouseup", handleWindowMouseUp);
});
</script>

<style scoped>
.workflow-canvas {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 18px;
  min-height: 620px;
}

.workflow-canvas--readonly {
  grid-template-columns: minmax(0, 1fr);
}

.workflow-canvas__palette {
  display: grid;
  gap: 12px;
  align-content: start;
  padding: 18px;
  border: 1px solid var(--studio-border);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.82);
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
  border: 1px solid color-mix(in srgb, var(--palette-color) 28%, transparent);
  border-radius: 18px;
  text-align: left;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(242, 248, 255, 0.98));
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
