import type {
  DataServiceFieldView,
  DataServiceQueryOperator,
  DataServiceRequestParam,
  DataServiceResponseParam,
  DataServiceValueType,
} from "@studio/api-sdk";

export const valueTypeOptions = [
  { label: "string", value: "STRING" },
  { label: "int", value: "INT" },
  { label: "time", value: "TIME" },
  { label: "float", value: "FLOAT" },
  { label: "timestamp", value: "TIMESTAMP" },
  { label: "list", value: "LIST" },
] as const;

export const operatorLabels: Record<DataServiceQueryOperator, string> = {
  EQ: "精确",
  LIKE: "模糊",
  NE: "不等于",
  GT: "大于",
  GE: "大于等于",
  LT: "小于",
  LE: "小于等于",
  CONTAINS: "包含",
  NOT_CONTAINS: "不包含",
};

export const wizardSteps = [
  { title: "基础信息", description: "定义服务身份" },
  { title: "服务开发", description: "选择来源与字段" },
  { title: "服务发布", description: "配置地址与映射" },
  { title: "接口调试", description: "生成模板并验证" },
];

export const debugHeaderPlaceholder = `{
  "X-Request-Id": ""
}`;

export const debugQueryPlaceholder = `{
  "pageNum": 1,
  "pageSize": 10
}`;

export const debugBodyPlaceholder = `{
  "name": ""
}`;

export function defaultFixedParams(): DataServiceRequestParam[] {
  return [
    { sortOrder: 1, paramName: "pageNum", fieldName: "pageNum", valueType: "INT", queryOperator: "EQ", required: false, description: "页码", fixedParam: true },
    { sortOrder: 2, paramName: "pageSize", fieldName: "pageSize", valueType: "INT", queryOperator: "EQ", required: false, description: "每页条数", fixedParam: true },
  ];
}

export function nextCustomRequestParamName(params: DataServiceRequestParam[]) {
  const names = new Set(params.map((item) => item.paramName));
  let index = Math.max(1, params.length - 1);
  let name = `param${index}`;
  while (names.has(name)) {
    index += 1;
    name = `param${index}`;
  }
  return name;
}

export function buildFieldsFromResponseParams(params: DataServiceResponseParam[]): DataServiceFieldView[] {
  return (params || []).map((item) => ({
    fieldName: item.fieldName,
    exampleValue: item.exampleValue,
    description: item.description,
  }));
}

export function resolveOperatorOptions(valueType?: DataServiceValueType) {
  if (valueType === "LIST") {
    return ["EQ", "CONTAINS", "NOT_CONTAINS"].map((value) => ({ label: operatorLabels[value as DataServiceQueryOperator], value: value as DataServiceQueryOperator }));
  }
  if (valueType === "STRING" || !valueType) {
    return ["EQ", "LIKE", "NE"].map((value) => ({ label: operatorLabels[value as DataServiceQueryOperator], value: value as DataServiceQueryOperator }));
  }
  return ["EQ", "GT", "GE", "LT", "LE", "NE"].map((value) => ({ label: operatorLabels[value as DataServiceQueryOperator], value: value as DataServiceQueryOperator }));
}

export function resolveValueType(fieldType?: string): DataServiceValueType {
  const normalized = String(fieldType || "").toLowerCase();
  if (normalized.includes("int") || normalized.includes("long")) {
    return "INT";
  }
  if (normalized.includes("decimal") || normalized.includes("double") || normalized.includes("float")) {
    return "FLOAT";
  }
  if (normalized.includes("timestamp") || normalized.includes("datetime")) {
    return "TIMESTAMP";
  }
  if (normalized.includes("date") || normalized.includes("time")) {
    return "TIME";
  }
  return "STRING";
}

export function resolveValueTypeLabel(value?: DataServiceValueType) {
  return valueTypeOptions.find((item) => item.value === value)?.label || value || "-";
}

export function defaultExampleValue(valueType?: DataServiceValueType) {
  if (valueType === "INT") {
    return "1";
  }
  if (valueType === "FLOAT") {
    return "1.0";
  }
  if (valueType === "TIME" || valueType === "TIMESTAMP") {
    return "2026-04-16 12:00:00";
  }
  if (valueType === "LIST") {
    return "A,B";
  }
  return "示例";
}

export function parseJson(value: string, label = "调试参数") {
  if (!value.trim()) {
    return {};
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch (error) {
    throw new Error(`${label} 不是合法 JSON：${error instanceof Error ? error.message : "解析失败"}`);
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(`${label} 必须是 JSON 对象`);
  }
  return parsed as Record<string, unknown>;
}

export function ensureOpenApiHeaders(headers: Record<string, unknown>, tokenHeaderName = "X-Data-Service-Token") {
  const result: Record<string, unknown> = { ...headers };
  const hasAccept = Object.keys(result).some((key) => key.toLowerCase() === "accept");
  const normalizedTokenHeaderName = tokenHeaderName.toLowerCase();
  const hasToken = Object.keys(result).some((key) => key.toLowerCase() === normalizedTokenHeaderName);
  if (!hasAccept) {
    result.Accept = "application/json";
  }
  if (!hasToken) {
    result[tokenHeaderName] = "<订阅Token>";
  }
  return result;
}

export function buildBashCurl(
  url: string,
  method: string,
  headers: Record<string, unknown>,
  query: Record<string, unknown>,
  body: unknown,
  form?: Record<string, unknown>,
) {
  const normalizedMethod = normalizeRequestMethod(method);
  const requestBody = normalizeBodyForCurl(body, form);
  const lines = [`curl -X ${normalizedMethod} ${quoteBash(appendQueryParams(url, query))}`];
  for (const [key, value] of Object.entries(normalizeHeadersForCurl(headers, requestBody.contentType))) {
    lines.push(`  -H ${quoteBash(`${key}: ${String(value)}`)}`);
  }
  if (requestBody.text) {
    lines.push(`  --data-raw ${quoteBash(requestBody.text)}`);
  }
  return lines.join(" \\\n");
}

export function buildCmdCurl(
  url: string,
  method: string,
  headers: Record<string, unknown>,
  query: Record<string, unknown>,
  body: unknown,
  form?: Record<string, unknown>,
) {
  const normalizedMethod = normalizeRequestMethod(method);
  const requestBody = normalizeBodyForCurl(body, form);
  const lines = [`curl -X ${normalizedMethod} ${quoteCmd(appendQueryParams(url, query))}`];
  for (const [key, value] of Object.entries(normalizeHeadersForCurl(headers, requestBody.contentType))) {
    lines.push(`  -H ${quoteCmd(`${key}: ${String(value)}`)}`);
  }
  if (requestBody.text) {
    lines.push(`  --data-raw ${quoteCmd(requestBody.text)}`);
  }
  return lines.join(" ^\n");
}

export function buildBashRawBodyCurl(
  url: string,
  method: string,
  headers: Record<string, unknown>,
  bodyText: string,
  contentType: string,
) {
  const normalizedMethod = normalizeRequestMethod(method);
  const lines = [`curl -X ${normalizedMethod} ${quoteBash(normalizeCurlUrl(url))}`];
  for (const [key, value] of Object.entries(normalizeHeadersForCurl(headers, contentType))) {
    lines.push(`  -H ${quoteBash(`${key}: ${String(value)}`)}`);
  }
  if (bodyText.trim()) {
    lines.push(`  --data-raw ${quoteBash(bodyText)}`);
  }
  return lines.join(" \\\n");
}

export function buildCmdRawBodyCurl(
  url: string,
  method: string,
  headers: Record<string, unknown>,
  bodyText: string,
  contentType: string,
) {
  const normalizedMethod = normalizeRequestMethod(method);
  const lines = [`curl -X ${normalizedMethod} ${quoteCmd(normalizeCurlUrl(url))}`];
  for (const [key, value] of Object.entries(normalizeHeadersForCurl(headers, contentType))) {
    lines.push(`  -H ${quoteCmd(`${key}: ${String(value)}`)}`);
  }
  if (bodyText.trim()) {
    lines.push(`  --data-raw ${quoteCmd(bodyText)}`);
  }
  return lines.join(" ^\n");
}

export function copyTextFallback(value: string) {
  const textarea = document.createElement("textarea");
  textarea.value = value;
  textarea.setAttribute("readonly", "true");
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  document.body.appendChild(textarea);
  textarea.select();
  document.execCommand("copy");
  document.body.removeChild(textarea);
}

function normalizeRequestMethod(method?: string) {
  return String(method || "GET").toUpperCase() === "POST" ? "POST" : "GET";
}

function normalizeHeadersForCurl(headers: Record<string, unknown>, contentType: string) {
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(headers)) {
    if (!key || value == null) {
      continue;
    }
    result[key] = value;
  }
  if (contentType && !Object.keys(result).some((key) => key.toLowerCase() === "content-type")) {
    result["Content-Type"] = contentType;
  }
  return result;
}

function normalizeBodyForCurl(body: unknown, form?: Record<string, unknown>) {
  if (hasCurlBody(body)) {
    return {
      text: JSON.stringify(body),
      contentType: "application/json",
    };
  }
  const formText = normalizeFormBodyForCurl(form);
  if (formText) {
    return {
      text: formText,
      contentType: "application/x-www-form-urlencoded",
    };
  }
  return {
    text: "",
    contentType: "",
  };
}

function hasCurlBody(body: unknown) {
  if (body == null) {
    return false;
  }
  if (Array.isArray(body)) {
    return body.length > 0;
  }
  if (typeof body === "object") {
    return Object.keys(body as Record<string, unknown>).length > 0;
  }
  if (typeof body === "string") {
    return Boolean(body.trim());
  }
  return true;
}

function normalizeFormBodyForCurl(form?: Record<string, unknown>) {
  if (!form || !Object.keys(form).length) {
    return "";
  }
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(form)) {
    if (!key || value == null) {
      continue;
    }
    params.set(key, stringifyCurlValue(value));
  }
  return params.toString();
}

function appendQueryParams(url: string, query: Record<string, unknown>) {
  const parsed = new URL(url, window.location.origin);
  for (const [key, value] of Object.entries(query)) {
    if (!key || value == null) {
      continue;
    }
    if (Array.isArray(value)) {
      parsed.searchParams.set(key, value.join(","));
      continue;
    }
    parsed.searchParams.set(key, String(value));
  }
  return parsed.toString();
}

function normalizeCurlUrl(url: string) {
  try {
    return new URL(url, window.location.origin).toString();
  } catch {
    return url;
  }
}

function stringifyCurlValue(value: unknown): string {
  if (Array.isArray(value)) {
    return value.map((item) => stringifyCurlValue(item)).join(",");
  }
  if (value && typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

function quoteBash(value: string) {
  return `'${value.replace(/'/g, "'\\''")}'`;
}

function quoteCmd(value: string) {
  return `"${value.replace(/"/g, '\\"')}"`;
}
