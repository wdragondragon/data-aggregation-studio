export type DebugObject = Record<string, unknown>;

export interface JsonParseResult {
  ok: boolean;
  value: DebugObject;
  error: string;
}

export interface JsonValueParseResult {
  ok: boolean;
  value: unknown;
  error: string;
}

export interface SoapFieldSpec {
  key: string;
  elementName: string;
  value: unknown;
}

export interface SoapEnvelopeBuildOptions {
  soapVersion?: string;
  namespaceUri?: string;
  requestRootName?: string;
  tokenElementName?: string;
  tokenValue?: string;
  headerFields?: SoapFieldSpec[];
  fields?: SoapFieldSpec[];
  bodyPayload?: DebugObject;
}

export interface SoapEnvelopeParseResult {
  ok: boolean;
  error: string;
  values: DebugObject;
  headerValues: DebugObject;
  tokenValue?: string;
}

export interface XmlFormatResult {
  ok: boolean;
  value: string;
  error: string;
}

export function prettyJsonValue(value: unknown) {
  return JSON.stringify(value ?? {}, null, 2);
}

export function formatXmlText(value: string, label = "XML"): XmlFormatResult {
  const text = value.trim();
  if (!text) {
    return { ok: true, value: "", error: "" };
  }
  const parser = new DOMParser();
  const document = parser.parseFromString(text, "text/xml");
  const parserError = document.getElementsByTagName("parsererror")[0];
  if (parserError) {
    return {
      ok: false,
      value,
      error: `${label} 解析失败：${parserError.textContent?.trim() || "XML 不合法"}`,
    };
  }
  const compact = text.replace(/>\s+</g, "><").trim();
  const tokens = compact.match(/<[^>]+>|[^<]+/g) ?? [compact];
  const lines: string[] = [];
  let indent = 0;
  for (const token of tokens) {
    const trimmed = token.trim();
    if (!trimmed) {
      continue;
    }
    if (trimmed.startsWith("</")) {
      indent = Math.max(indent - 1, 0);
      lines.push(`${"  ".repeat(indent)}${trimmed}`);
      continue;
    }
    lines.push(`${"  ".repeat(indent)}${trimmed}`);
    if (trimmed.startsWith("<")
      && !trimmed.startsWith("<?")
      && !trimmed.startsWith("<!")
      && !trimmed.endsWith("/>")
      && !trimmed.includes("</")) {
      indent += 1;
    }
  }
  return { ok: true, value: lines.join("\n"), error: "" };
}

export function parseJsonObjectText(value: string, label: string): JsonParseResult {
  const text = value.trim();
  if (!text) {
    return { ok: true, value: {}, error: "" };
  }
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return { ok: true, value: parsed as DebugObject, error: "" };
    }
    return { ok: false, value: {}, error: `${label} 必须是 JSON 对象` };
  } catch (error) {
    return {
      ok: false,
      value: {},
      error: `${label} 不是合法 JSON：${error instanceof Error ? error.message : "解析失败"}`,
    };
  }
}

export function parseJsonValueText(value: string, label: string): JsonValueParseResult {
  const text = value.trim();
  if (!text) {
    return { ok: true, value: {}, error: "" };
  }
  try {
    return { ok: true, value: JSON.parse(text), error: "" };
  } catch (error) {
    return {
      ok: false,
      value: {},
      error: `${label} 不是合法 JSON：${error instanceof Error ? error.message : "解析失败"}`,
    };
  }
}

export function isBlankDebugValue(value: unknown) {
  return value == null || (typeof value === "string" && !value.trim());
}

export function stringifyDebugValue(value: unknown) {
  if (value == null) {
    return "";
  }
  if (typeof value === "object") {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

export function assignPath(target: DebugObject, path: string, value: unknown) {
  const segments = path.split(".").map((segment) => segment.trim()).filter(Boolean);
  if (!segments.length) {
    return;
  }
  let current: DebugObject = target;
  segments.forEach((segment, index) => {
    if (index === segments.length - 1) {
      current[segment] = value;
      return;
    }
    const next = current[segment];
    if (!next || typeof next !== "object" || Array.isArray(next)) {
      current[segment] = {};
    }
    current = current[segment] as DebugObject;
  });
}

export function readPath(source: unknown, path: string) {
  const segments = path.split(".").map((segment) => segment.trim()).filter(Boolean);
  let current = source;
  for (const segment of segments) {
    if (!current || typeof current !== "object") {
      return undefined;
    }
    current = (current as DebugObject)[segment];
  }
  return current;
}

export function firstArrayItemOrSelf(value: unknown) {
  return Array.isArray(value) ? value[0] : value;
}

export function buildSoapEnvelope(options: SoapEnvelopeBuildOptions) {
  const soapNs = options.soapVersion === "SOAP_12"
    ? "http://www.w3.org/2003/05/soap-envelope"
    : "http://schemas.xmlsoap.org/soap/envelope/";
  const namespaceUri = options.namespaceUri?.trim() || "http://studio.jdragon.com/open-service";
  const requestRootName = safeXmlName(options.requestRootName) || "request";
  const tokenElementName = safeXmlName(options.tokenElementName) || "token";
  const tokenValue = options.tokenValue ?? "your-token";
  const headerXml = [
    `    <tns:${tokenElementName}>${escapeXml(tokenValue)}</tns:${tokenElementName}>`,
    ...(options.headerFields ?? []).map((field) => appendXmlValue(field.elementName, field.value, 4, "tns")),
  ].filter(Boolean).join("\n");
  const fieldXml = options.bodyPayload
    ? appendObjectChildren(options.bodyPayload, 6)
    : (options.fields ?? [])
      .map((field) => appendXmlValue(field.elementName, field.value, 6))
      .filter(Boolean)
      .join("\n");
  return [
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
    `<soap:Envelope xmlns:soap="${escapeXml(soapNs)}" xmlns:tns="${escapeXml(namespaceUri)}">`,
    "  <soap:Header>",
    headerXml,
    "  </soap:Header>",
    "  <soap:Body>",
    `    <tns:${requestRootName}>`,
    fieldXml,
    `    </tns:${requestRootName}>`,
    "  </soap:Body>",
    "</soap:Envelope>",
  ].join("\n");
}

export function parseSoapEnvelope(value: string): SoapEnvelopeParseResult {
  const text = value.trim();
  if (!text) {
    return { ok: false, error: "SOAP Envelope 不能为空", values: {}, headerValues: {} };
  }
  const parser = new DOMParser();
  const document = parser.parseFromString(text, "text/xml");
  const parserError = document.getElementsByTagName("parsererror")[0];
  if (parserError) {
    return { ok: false, error: `SOAP XML 解析失败：${parserError.textContent?.trim() || "XML 不合法"}`, values: {}, headerValues: {} };
  }
  const header = findElementByLocalName(document.documentElement, "Header");
  const body = findElementByLocalName(document.documentElement, "Body");
  if (!body) {
    return { ok: false, error: "SOAP Envelope 缺少 Body 节点", values: {}, headerValues: {} };
  }
  const operation = firstElementChild(body);
  if (!operation) {
    return { ok: false, error: "SOAP Body 缺少操作节点", values: {}, headerValues: {} };
  }
  const headerValues = childrenToObject(header);
  return {
    ok: true,
    error: "",
    values: childrenToObject(operation),
    headerValues,
    tokenValue: readTokenValue(headerValues),
  };
}

function readTokenValue(headerValues?: DebugObject) {
  if (!headerValues) {
    return undefined;
  }
  const tokenNames = new Set(["token", "dataServiceToken", "dataIngestionToken"]);
  for (const [key, value] of Object.entries(headerValues)) {
    if (tokenNames.has(key)) {
      return stringifyDebugValue(value);
    }
  }
  return undefined;
}

function appendXmlValue(rawName: string, value: unknown, indentSize: number, prefix = ""): string {
  const name = safeXmlName(rawName);
  if (!name) {
    return "";
  }
  const indent = " ".repeat(indentSize);
  const tag = prefix ? `${prefix}:${name}` : name;
  if (Array.isArray(value)) {
    return value.map((item) => appendXmlValue(rawName, item, indentSize, prefix)).filter(Boolean).join("\n");
  }
  if (value && typeof value === "object") {
    const children = appendObjectChildren(value as DebugObject, indentSize + 2);
    return `${indent}<${tag}>\n${children}\n${indent}</${tag}>`;
  }
  return `${indent}<${tag}>${escapeXml(stringifyDebugValue(value))}</${tag}>`;
}

function appendObjectChildren(value: DebugObject, indentSize: number): string {
  return Object.entries(value)
    .map(([key, child]) => appendXmlValue(key, child, indentSize))
    .filter(Boolean)
    .join("\n");
}

function childrenToObject(parent?: Element) {
  const result: DebugObject = {};
  if (!parent) {
    return result;
  }
  Array.from(parent.childNodes).forEach((node) => {
    if (node.nodeType !== Node.ELEMENT_NODE) {
      return;
    }
    const element = node as Element;
    putRepeated(result, element.localName || element.nodeName, elementToValue(element));
  });
  return result;
}

function elementToValue(element: Element): unknown {
  const children = childrenToObject(element);
  if (Object.keys(children).length) {
    return children;
  }
  return element.textContent ?? "";
}

function putRepeated(target: DebugObject, key: string, value: unknown) {
  if (!(key in target)) {
    target[key] = value;
    return;
  }
  const existing = target[key];
  target[key] = Array.isArray(existing) ? [...existing, value] : [existing, value];
}

function findElementByLocalName(root: Element, localName: string): Element | undefined {
  if ((root.localName || root.nodeName) === localName) {
    return root;
  }
  for (const node of Array.from(root.childNodes)) {
    if (node.nodeType !== Node.ELEMENT_NODE) {
      continue;
    }
    const found = findElementByLocalName(node as Element, localName);
    if (found) {
      return found;
    }
  }
  return undefined;
}

function firstElementChild(root: Element): Element | undefined {
  return Array.from(root.childNodes).find((node) => node.nodeType === Node.ELEMENT_NODE) as Element | undefined;
}

function safeXmlName(value?: string) {
  const name = value?.trim() || "";
  return /^[A-Za-z_][A-Za-z0-9_.-]*$/.test(name) ? name : "";
}

function escapeXml(value: string) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}
