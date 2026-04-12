<template>
  <div ref="shellRef" class="lineage-canvas-shell">
    <div v-show="!hasData" class="soft-panel lineage-canvas-empty">
      {{ emptyText }}
    </div>
    <div v-show="hasData" ref="scrollRef" class="lineage-canvas-scroll">
      <div ref="containerRef" class="lineage-canvas-board" :style="boardStyle"></div>
      <component :is="TeleportContainer" v-if="TeleportContainer" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { Graph } from "@antv/x6";
import { getTeleport, register } from "@antv/x6-vue-shape";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type {
  DataModelLineageEdgeView,
  DataModelLineageLevel,
  DataModelLineageNodeView,
} from "@studio/api-sdk";
import ModelLineageNodeCard from "@/components/ModelLineageNodeCard.vue";
import {
  buildNodeRenderData,
  type FieldConnectionRole,
  type LineageNodeCellData,
  normalizeFieldKey,
  PADDING_X,
  PADDING_Y,
  PORT_OFFSET_X,
  resolveColumnGap,
  resolveColumnWidth,
  resolveFieldRowCenterY,
  resolveNodeSize,
  resolveStatusTone,
  ROW_GAP,
} from "@/components/modelLineageLayout";

interface PositionedNode extends DataModelLineageNodeView {
  x: number;
  y: number;
  width: number;
  height: number;
}

const props = withDefaults(defineProps<{
  nodes: DataModelLineageNodeView[];
  edges: DataModelLineageEdgeView[];
  level: DataModelLineageLevel | string;
  emptyText?: string;
}>(), {
  nodes: () => [],
  edges: () => [],
  emptyText: "",
});

const emit = defineEmits<{
  selectEdge: [edgeId: string];
}>();

const LINEAGE_NODE_SHAPE = "lineage-vue-er-node";
const INITIAL_SCALE = 0.84;
const TeleportContainer = getTeleport();

const shellRef = ref<HTMLDivElement>();
const scrollRef = ref<HTMLDivElement>();
const containerRef = ref<HTMLDivElement>();
const graphRef = ref<Graph>();
const viewportWidth = ref(0);
const viewportHeight = ref(0);
let shapeRegistered = false;
let resizeObserver: ResizeObserver | undefined;

const hasData = computed(() => props.nodes.length > 0 && props.edges.length > 0);
const focusNodeId = computed(() => props.nodes.find((item) => item.focus)?.nodeId ?? props.nodes[0]?.nodeId ?? "");

const fieldConnectionRoles = computed(() => {
  const result = new Map<string, FieldConnectionRole>();
  if (String(props.level) !== "FIELD") {
    return result;
  }
  for (const edge of props.edges) {
    if (edge.sourceField) {
      const key = `${edge.sourceNodeId}:${normalizeFieldKey(edge.sourceField)}`;
      const current = result.get(key) ?? { asSource: false, asTarget: false };
      current.asSource = true;
      result.set(key, current);
    }
    if (edge.targetField) {
      const key = `${edge.targetNodeId}:${normalizeFieldKey(edge.targetField)}`;
      const current = result.get(key) ?? { asSource: false, asTarget: false };
      current.asTarget = true;
      result.set(key, current);
    }
  }
  return result;
});

const layoutResult = computed(() => buildLayout(props.nodes, props.edges, focusNodeId.value, String(props.level)));
const positionedNodes = computed(() => layoutResult.value.nodes);
const surfaceWidth = computed(() => layoutResult.value.width);
const surfaceHeight = computed(() => layoutResult.value.height);
const effectiveViewportWidth = computed(() => Math.max(420, viewportWidth.value || 0));
const effectiveViewportHeight = computed(() => Math.max(520, viewportHeight.value || 520));
const boardStyle = computed(() => ({
  width: `${effectiveViewportWidth.value}px`,
  height: `${effectiveViewportHeight.value}px`,
}));

onMounted(() => {
  ensureShapeRegistered();
  observeViewport();
  void nextTick(() => {
    initGraph();
  });
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = undefined;
  graphRef.value?.dispose();
  graphRef.value = undefined;
});

watch(
  () => [props.level, props.nodes, props.edges, layoutResult.value],
  () => {
    void nextTick(() => {
      observeViewport();
      ensureShapeRegistered();
      initGraph();
      renderGraph();
    });
  },
  { deep: true },
);

function ensureShapeRegistered() {
  if (shapeRegistered) {
    return;
  }
  register({
    shape: LINEAGE_NODE_SHAPE,
    component: ModelLineageNodeCard,
    inherit: "vue-shape",
  });
  shapeRegistered = true;
}

function initGraph() {
  if (graphRef.value || !containerRef.value) {
    return;
  }
  graphRef.value = new Graph({
    container: containerRef.value,
    grid: false,
    background: {
      color: "transparent",
    },
    panning: false,
    mousewheel: {
      enabled: true,
      modifiers: ["ctrl", "meta"],
      minScale: 0.5,
      maxScale: 1.4,
    },
    interacting: {
      nodeMovable: true,
      edgeMovable: false,
      vertexMovable: false,
      arrowheadMovable: false,
      magnetConnectable: false,
      edgeLabelMovable: false,
    },
  });

  graphRef.value.on("edge:click", ({ edge }) => {
    const edgeId = String(edge.getData()?.edgeId ?? edge.id ?? "");
    if (edgeId) {
      emit("selectEdge", edgeId);
    }
  });

  renderGraph();
}

function renderGraph() {
  const graph = graphRef.value;
  if (!graph) {
    return;
  }
  graph.resize(effectiveViewportWidth.value, effectiveViewportHeight.value);
  if (!hasData.value) {
    graph.resetCells([]);
    return;
  }

  const cells = [
    ...positionedNodes.value.map((node) => buildGraphNode(graph, node, String(props.level))),
    ...props.edges.map((edge) => buildGraphEdge(graph, edge, String(props.level))),
  ];
  graph.resetCells([]);
  graph.resetCells(cells);
  applyInitialViewport(graph);
}

function observeViewport() {
  const target = shellRef.value;
  if (!target) {
    return;
  }
  const syncViewport = () => {
    const rect = target.getBoundingClientRect();
    viewportWidth.value = Math.max(0, Math.floor(rect.width));
    viewportHeight.value = Math.max(520, Math.floor(rect.height || 0));
  };
  syncViewport();
  resizeObserver?.disconnect();
  resizeObserver = new ResizeObserver(() => {
    syncViewport();
    if (graphRef.value) {
      graphRef.value.resize(effectiveViewportWidth.value, effectiveViewportHeight.value);
    }
  });
  resizeObserver.observe(target);
}

function applyInitialViewport(graph: Graph) {
  graph.matrix(new DOMMatrix());
  graph.zoomToFit({
    padding: String(props.level) === "FIELD" ? 28 : 40,
    maxScale: String(props.level) === "FIELD" ? 0.72 : INITIAL_SCALE,
    minScale: 0.4,
  });
}

function buildGraphNode(graph: Graph, node: PositionedNode, level: string) {
  const renderData = buildNodeRenderData(node, level, fieldConnectionRoles.value);
  return graph.createNode({
    id: node.nodeId,
    shape: LINEAGE_NODE_SHAPE,
    x: node.x,
    y: node.y,
    width: node.width,
    height: node.height,
    zIndex: node.focus ? 3 : 2,
    data: {
      renderData,
    } satisfies LineageNodeCellData,
    attrs: {
      body: {
        fill: "transparent",
        stroke: "transparent",
      },
    },
    ports: buildNodePorts(node, renderData, level),
  });
}

function buildGraphEdge(graph: Graph, edge: DataModelLineageEdgeView, level: string) {
  const tone = resolveStatusTone(edge.displayStatus ?? edge.latestRunStatus);
  const sourcePort = level === "FIELD" && edge.sourceField ? `out:${normalizeFieldKey(edge.sourceField)}` : "out";
  const targetPort = level === "FIELD" && edge.targetField ? `in:${normalizeFieldKey(edge.targetField)}` : "in";
  const isSelfLoop = Boolean(edge.selfLoop || edge.sourceNodeId === edge.targetNodeId);
  return graph.createEdge({
    id: edge.edgeId,
    source: {
      cell: edge.sourceNodeId,
      port: sourcePort,
    },
    target: {
      cell: edge.targetNodeId,
      port: targetPort,
    },
    router: isSelfLoop
      ? {
          name: "loop",
          args: {
            width: level === "FIELD" ? 48 : 56,
            height: level === "FIELD" ? 84 : 96,
            angle: 0,
            merge: false,
          },
        }
      : {
          name: "er",
          args: {
            offset: 24,
            direction: "H",
          },
        },
    connector: {
      name: "rounded",
      args: { radius: isSelfLoop ? 16 : 12 },
    },
    attrs: {
      line: {
        stroke: tone.stroke,
        strokeWidth: tone.strokeWidth,
        strokeDasharray: tone.strokeDasharray,
        targetMarker: {
          name: "classic",
          size: 6,
        },
      },
    },
    data: {
      edgeId: edge.edgeId,
    },
  });
}

function buildNodePorts(node: PositionedNode, renderData: LineageNodeCellData["renderData"], level: string) {
  const portTone = renderData.tone === "external" ? "#2f83e5" : "#2b8ef7";
  const groups = {
    in: {
      position: {
        name: "absolute",
      },
      attrs: {
        circle: {
          r: 4,
          magnet: false,
          stroke: portTone,
          strokeWidth: 1.8,
          fill: "#ffffff",
          class: "x6-port-body",
        },
      },
    },
    out: {
      position: {
        name: "absolute",
      },
      attrs: {
        circle: {
          r: 4,
          magnet: false,
          stroke: portTone,
          strokeWidth: 1.8,
          fill: "#ffffff",
          class: "x6-port-body",
        },
      },
    },
  };

  if (level !== "FIELD") {
    const centerY = Math.round(node.height / 2);
    return {
      groups,
      items: [
        {
          id: "in",
          group: "in",
          args: { x: PORT_OFFSET_X, y: centerY },
        },
        {
          id: "out",
          group: "out",
          args: { x: node.width - PORT_OFFSET_X, y: centerY },
        },
      ],
    };
  }

  const items = renderData.fields.flatMap((field, index) => {
    const centerY = resolveFieldRowCenterY(renderData.metaLines.length, index);
    const normalizedKey = normalizeFieldKey(field.fieldKey);
    return [
      {
        id: `in:${normalizedKey}`,
        group: "in",
        args: { x: PORT_OFFSET_X, y: centerY },
      },
      {
        id: `out:${normalizedKey}`,
        group: "out",
        args: { x: node.width - PORT_OFFSET_X, y: centerY },
      },
    ];
  });

  return { groups, items };
}

function buildLayout(
  rawNodes: DataModelLineageNodeView[],
  edges: DataModelLineageEdgeView[],
  focusId: string,
  level: string,
) {
  const columnWidth = resolveColumnWidth(level);
  const columnGap = resolveColumnGap(level);
  const incoming = new Map<string, string[]>();
  const outgoing = new Map<string, string[]>();
  for (const node of rawNodes) {
    incoming.set(node.nodeId, []);
    outgoing.set(node.nodeId, []);
  }
  for (const edge of edges) {
    incoming.get(edge.targetNodeId)?.push(edge.sourceNodeId);
    outgoing.get(edge.sourceNodeId)?.push(edge.targetNodeId);
  }

  const upstreamDepths = bfsDistances(focusId, incoming);
  const downstreamDepths = bfsDistances(focusId, outgoing);
  const maxUpstreamDepth = Math.max(0, ...Array.from(upstreamDepths.values()));

  const nodes = rawNodes.map((node) => {
    const upstreamDepth = node.nodeId === focusId ? undefined : upstreamDepths.get(node.nodeId);
    const downstreamDepth = node.nodeId === focusId ? undefined : downstreamDepths.get(node.nodeId);
    let column = maxUpstreamDepth;
    if (node.nodeId === focusId) {
      column = maxUpstreamDepth;
    } else if (upstreamDepth != null && (downstreamDepth == null || upstreamDepth <= downstreamDepth)) {
      column = Math.max(0, maxUpstreamDepth - upstreamDepth);
    } else if (downstreamDepth != null) {
      column = maxUpstreamDepth + downstreamDepth;
    } else {
      column = maxUpstreamDepth + 1;
    }
    const size = resolveNodeSize(node, level);
    return {
      ...node,
      x: PADDING_X,
      y: PADDING_Y,
      width: size.width,
      height: size.height,
      __column: column,
      __titleSort: String(node.title || "").toLowerCase(),
    };
  }).sort((left, right) => {
    if (left.__column !== right.__column) {
      return left.__column - right.__column;
    }
    if (left.focus && !right.focus) {
      return -1;
    }
    if (!left.focus && right.focus) {
      return 1;
    }
    return left.__titleSort.localeCompare(right.__titleSort);
  });

  const columnGroups = new Map<number, PositionedNode[]>();
  for (const node of nodes) {
    const column = node.__column as unknown as number;
    if (!columnGroups.has(column)) {
      columnGroups.set(column, []);
    }
    columnGroups.get(column)?.push(node as PositionedNode);
  }

  let contentHeight = 0;
  const columnHeights = new Map<number, number>();
  for (const [column, group] of columnGroups.entries()) {
    let cursorY = PADDING_Y;
    for (const node of group) {
      node.x = PADDING_X + column * (columnWidth + columnGap);
      node.y = cursorY;
      cursorY += node.height + ROW_GAP;
    }
    const groupHeight = cursorY - ROW_GAP + PADDING_Y;
    columnHeights.set(column, groupHeight);
    contentHeight = Math.max(contentHeight, groupHeight);
  }

  for (const [column, group] of columnGroups.entries()) {
    const groupHeight = columnHeights.get(column) ?? contentHeight;
    const offset = Math.max(0, (contentHeight - groupHeight) / 2);
    for (const node of group) {
      node.y += offset;
    }
  }

  const maxColumn = Math.max(0, ...Array.from(columnGroups.keys()));
  return {
    nodes: nodes as PositionedNode[],
    width: PADDING_X * 2 + (maxColumn + 1) * columnWidth + maxColumn * columnGap,
    height: Math.max(460, contentHeight + PADDING_Y),
  };
}

function bfsDistances(startNodeId: string, adjacency: Map<string, string[]>) {
  const distances = new Map<string, number>();
  const queue: string[] = [startNodeId];
  const depthQueue: number[] = [0];
  while (queue.length > 0) {
    const current = queue.shift() as string;
    const currentDepth = depthQueue.shift() as number;
    for (const next of adjacency.get(current) ?? []) {
      if (distances.has(next)) {
        continue;
      }
      const nextDepth = currentDepth + 1;
      distances.set(next, nextDepth);
      queue.push(next);
      depthQueue.push(nextDepth);
    }
  }
  return distances;
}
</script>

<style scoped>
.lineage-canvas-shell {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  min-height: 0;
  overflow: hidden;
  contain: layout paint style;
}

.lineage-canvas-empty {
  min-height: 360px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(100, 116, 139, 0.9);
}

.lineage-canvas-scroll {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  height: clamp(440px, 60vh, 680px);
  overflow: hidden;
  padding-bottom: 8px;
  position: relative;
}

.lineage-canvas-board {
  width: 100%;
  max-width: 100%;
  min-height: 100%;
  border-radius: 14px;
  overflow: hidden;
  border: 1px solid #e2e8f0;
  background-color: #f8fafc;
  background-image:
    radial-gradient(circle at 1px 1px, rgba(148, 163, 184, 0.28) 1px, transparent 0);
  background-size: 18px 18px;
  position: relative;
  contain: strict;
}

.lineage-canvas-shell :deep(.x6-graph) {
  width: 100% !important;
  height: 100% !important;
  border-radius: 14px;
  overflow: hidden;
}

.lineage-canvas-shell :deep(.x6-graph-svg),
.lineage-canvas-shell :deep(.x6-graph-svg-stage),
.lineage-canvas-shell :deep(.x6-graph-svg-viewport) {
  width: 100% !important;
  height: 100% !important;
}

.lineage-canvas-shell :deep(.x6-node) {
  cursor: grab;
}

.lineage-canvas-shell :deep(.x6-node.x6-node-selected),
.lineage-canvas-shell :deep(.x6-node:active) {
  cursor: grabbing;
}

.lineage-canvas-shell :deep(.x6-edge path) {
  cursor: pointer;
}

.lineage-canvas-shell :deep(.x6-port-body) {
  transition: transform 0.2s ease, opacity 0.2s ease;
  pointer-events: none;
}

.lineage-canvas-shell :deep(.x6-port:hover .x6-port-body) {
  transform: scale(1.08);
}
</style>
