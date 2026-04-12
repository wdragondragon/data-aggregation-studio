import type {
  DataModelLineageLevel,
  DataModelLineageNodeFieldView,
  DataModelLineageNodeView,
} from "@studio/api-sdk";

export interface FieldConnectionRole {
  asSource: boolean;
  asTarget: boolean;
}

export interface RenderField extends DataModelLineageNodeFieldView {
  role: "none" | "source" | "target" | "both";
}

export interface RenderMetaLine {
  label: string;
  value: string;
}

export interface LineageNodeRenderData {
  level: string;
  tone: "platform" | "external";
  focus: boolean;
  title: string;
  metaLines: RenderMetaLine[];
  fields: RenderField[];
}

export interface LineageNodeCellData {
  renderData: LineageNodeRenderData;
}

export const COLUMN_WIDTH = 336;
export const COLUMN_GAP = 68;
export const ROW_GAP = 28;
export const PADDING_X = 24;
export const PADDING_Y = 28;

export const PORT_OFFSET_X = 2;
export const HEADER_HEIGHT = 42;
export const BODY_TOP_PADDING = 0;
export const BODY_BOTTOM_PADDING = 0;
export const META_ROW_HEIGHT = 34;
export const META_ROW_GAP = 0;
export const FIELD_SECTION_MARGIN_TOP = 0;
export const FIELD_ROW_HEIGHT = 34;
export const FIELD_ROW_GAP = 0;

export function resolveColumnWidth(level: string) {
  return level === "FIELD" ? 292 : COLUMN_WIDTH;
}

export function resolveColumnGap(level: string) {
  return level === "FIELD" ? 42 : COLUMN_GAP;
}

export function normalizeFieldKey(value?: string) {
  return String(value ?? "").trim().toLowerCase();
}

export function toneClass(visualType?: string) {
  return String(visualType ?? "PLATFORM_MODEL").toUpperCase() === "EXTERNAL_ACCESS" ? "external" : "platform";
}

export function resolveMetaLines(node: DataModelLineageNodeView, level: string) {
  if (level === "DATABASE") {
    return [
      { label: "数据源", value: node.datasourceName || "--" },
      { label: "类型", value: node.datasourceType || "--" },
      { label: "数据库", value: node.databaseName || "--" },
      { label: "地址", value: `${node.host || "--"}:${node.port || "--"}` },
    ];
  }
  if (level === "TABLE") {
    return [
      { label: "数据源", value: node.datasourceName || "--" },
      { label: "类型", value: node.datasourceType || "--" },
      { label: "数据库", value: node.databaseName || "--" },
      { label: "物理表", value: node.physicalLocator || "--" },
    ];
  }
  return [
    { label: "数据源", value: node.datasourceName || "--" },
    { label: "类型", value: node.datasourceType || "--" },
    { label: "数据库", value: node.databaseName || "--" },
    { label: "物理表", value: node.physicalLocator || "--" },
  ];
}

export function resolveNodeSize(node: DataModelLineageNodeView, level: string) {
  const metaCount = resolveMetaLines(node, level).length;
  const metaHeight = metaCount * META_ROW_HEIGHT + Math.max(0, metaCount - 1) * META_ROW_GAP;
  if (level !== "FIELD") {
    return {
      width: resolveColumnWidth(level),
      height: HEADER_HEIGHT + BODY_TOP_PADDING + metaHeight + BODY_BOTTOM_PADDING,
    };
  }
  const fieldCount = Math.max(1, node.fields?.length ?? 0);
  const fieldsHeight = fieldCount * FIELD_ROW_HEIGHT + Math.max(0, fieldCount - 1) * FIELD_ROW_GAP;
  return {
    width: resolveColumnWidth(level),
    height: HEADER_HEIGHT + BODY_TOP_PADDING + metaHeight + FIELD_SECTION_MARGIN_TOP + fieldsHeight + BODY_BOTTOM_PADDING,
  };
}

export function resolveFieldRowCenterY(metaCount: number, index: number) {
  return HEADER_HEIGHT
    + BODY_TOP_PADDING
    + metaCount * META_ROW_HEIGHT
    + Math.max(0, metaCount - 1) * META_ROW_GAP
    + FIELD_SECTION_MARGIN_TOP
    + index * (FIELD_ROW_HEIGHT + FIELD_ROW_GAP)
    + FIELD_ROW_HEIGHT / 2;
}

export function resolveFieldRole(
  nodeId: string,
  fieldKey: string | undefined,
  roles: Map<string, FieldConnectionRole>,
): "none" | "source" | "target" | "both" {
  const role = roles.get(`${nodeId}:${normalizeFieldKey(fieldKey)}`);
  if (!role) {
    return "none";
  }
  if (role.asSource && role.asTarget) {
    return "both";
  }
  if (role.asSource) {
    return "source";
  }
  if (role.asTarget) {
    return "target";
  }
  return "none";
}

export function buildNodeRenderData(
  node: DataModelLineageNodeView,
  level: DataModelLineageLevel | string,
  roles: Map<string, FieldConnectionRole>,
): LineageNodeRenderData {
  return {
    level: String(level),
    tone: toneClass(node.visualType),
    focus: Boolean(node.focus),
    title: node.title || "--",
    metaLines: resolveMetaLines(node, String(level)),
    fields: (node.fields ?? []).map((field) => ({
      ...field,
      role: resolveFieldRole(node.nodeId, field.fieldKey, roles),
    })),
  };
}

export function resolveStatusTone(status?: string) {
  const normalized = String(status ?? "NOT_RUN").trim().toUpperCase();
  if (normalized === "SUCCESS" || normalized === "NORMAL") {
    return { stroke: "#4f9f64", strokeWidth: 1.8 };
  }
  if (normalized === "FAILED" || normalized === "ERROR" || normalized === "EXCEPTION") {
    return { stroke: "#cf5c6c", strokeWidth: 1.8 };
  }
  if (normalized === "RUNNING" || normalized === "PENDING") {
    return { stroke: "#d0953f", strokeWidth: 1.8 };
  }
  if (normalized === "NOT_RUN" || normalized === "STOPPED") {
    return { stroke: "#8b95a7", strokeWidth: 1.7, strokeDasharray: "8 8" };
  }
  return { stroke: "#5b7db6", strokeWidth: 1.8 };
}
