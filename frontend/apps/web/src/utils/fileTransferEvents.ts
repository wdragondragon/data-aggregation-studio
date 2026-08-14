import type { FileTransferQueueEventView } from "@studio/api-sdk";
import { buildStudioRequestHeaders, getStoredProjectId, getStoredTenantId, resolveStudioApiBaseUrl } from "@/api/studio";

const RECONNECT_DELAY_MS = 2_000;
const MAX_RECONNECT_DELAY_MS = 30_000;

type FileTransferEventListener = (event: FileTransferQueueEventView) => void;
type FileTransferEventErrorListener = (error: Error) => void;

interface ParsedSseEvent {
  id?: string;
  event: string;
  data: string;
}

const listeners = new Set<FileTransferEventListener>();
const errorListeners = new Set<FileTransferEventErrorListener>();
let abortController: AbortController | null = null;
let reconnectTimer: number | null = null;
let connecting = false;
let connected = false;
let reconnectAttempts = 0;
let lastEventId: string | null = null;
let lastEventScope: string | null = null;

export function subscribeFileTransferEvents(
  listener: FileTransferEventListener,
  onError?: FileTransferEventErrorListener,
) {
  listeners.add(listener);
  if (onError) errorListeners.add(onError);
  ensureConnected();
  return () => {
    listeners.delete(listener);
    if (onError) errorListeners.delete(onError);
    if (listeners.size === 0) stopConnection();
  };
}

function ensureConnected() {
  if (listeners.size === 0 || connecting || connected || reconnectTimer != null) return;
  void connect();
}

async function connect() {
  if (listeners.size === 0 || connecting || connected) return;
  connecting = true;
  abortController = new AbortController();
  const currentController = abortController;
  const currentScope = eventScope();
  if (currentScope !== lastEventScope) {
    lastEventScope = currentScope;
    lastEventId = null;
  }
  try {
    const response = await fetch(resolveStudioApiBaseUrl("/file-transfer/runs/events"), {
      method: "GET",
      headers: {
        Accept: "text/event-stream",
        ...(lastEventId ? { "Last-Event-ID": lastEventId } : {}),
        ...buildStudioRequestHeaders(),
      },
      credentials: "include",
      signal: currentController.signal,
    });
    if (!response.ok || !response.body) {
      throw new Error(`文件传输事件连接失败: HTTP ${response.status}`);
    }
    connected = true;
    connecting = false;
    reconnectAttempts = 0;
    await consumeStream(response.body, currentController.signal);
  } catch (error) {
    if (currentController.signal.aborted) return;
    connecting = false;
    connected = false;
    notifyError(error);
    scheduleReconnect();
  }
}

async function consumeStream(stream: ReadableStream<Uint8Array>, signal: AbortSignal) {
  const reader = stream.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const parsed = splitSsePayload(buffer);
      buffer = parsed.rest;
      for (const event of parsed.events) handleEvent(event);
    }
  } catch (error) {
    if (!signal.aborted) notifyError(error);
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // The browser can release the reader during abort.
    }
    connected = false;
    connecting = false;
    if (!signal.aborted) scheduleReconnect();
  }
}

function handleEvent(event: ParsedSseEvent) {
  if (event.event === "heartbeat" || !event.data) return;
  if (event.id && lastEventId && compareEventIds(event.id, lastEventId) <= 0) return;
  try {
    const payload = JSON.parse(event.data) as FileTransferQueueEventView;
    if (event.id) payload.eventId = event.id;
    for (const listener of listeners) listener(payload);
    if (event.id) lastEventId = event.id;
  } catch (error) {
    notifyError(error);
  }
}

function scheduleReconnect() {
  if (listeners.size === 0 || reconnectTimer != null) return;
  reconnectAttempts = Math.min(reconnectAttempts + 1, 31);
  const delay = Math.min(
    RECONNECT_DELAY_MS * 2 ** Math.min(reconnectAttempts - 1, 4),
    MAX_RECONNECT_DELAY_MS,
  );
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null;
    void connect();
  }, delay);
}

function stopConnection() {
  if (reconnectTimer != null) {
    window.clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  abortController?.abort();
  abortController = null;
  connecting = false;
  connected = false;
  reconnectAttempts = 0;
}

function notifyError(error: unknown) {
  const normalized = error instanceof Error ? error : new Error("文件传输事件连接已断开");
  for (const listener of errorListeners) listener(normalized);
}

function splitSsePayload(payload: string) {
  const delimiter = /\r?\n\r?\n/g;
  const events: ParsedSseEvent[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = delimiter.exec(payload)) != null) {
    const block = payload.slice(lastIndex, match.index);
    lastIndex = match.index + match[0].length;
    const parsed = parseSseEvent(block);
    if (parsed) events.push(parsed);
  }
  return { events, rest: payload.slice(lastIndex) };
}

function parseSseEvent(block: string): ParsedSseEvent | null {
  if (!block.trim()) return null;
  let eventId: string | undefined;
  let eventName = "message";
  const dataLines: string[] = [];
  for (const line of block.split(/\r?\n/)) {
    if (!line || line.startsWith(":")) continue;
    if (line.startsWith("event:")) {
      eventName = line.slice(6).trim() || "message";
    } else if (line.startsWith("id:")) {
      const value = line.slice(3).trim();
      if (value && !value.includes("\0")) eventId = value;
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trim());
    }
  }
  return { id: eventId, event: eventName, data: dataLines.join("\n") };
}

function compareEventIds(left: string, right: string) {
  const normalizedLeft = normalizeNumericEventId(left);
  const normalizedRight = normalizeNumericEventId(right);
  if (normalizedLeft && normalizedRight) {
    if (normalizedLeft.length !== normalizedRight.length) {
      return normalizedLeft.length < normalizedRight.length ? -1 : 1;
    }
    return normalizedLeft === normalizedRight ? 0 : normalizedLeft < normalizedRight ? -1 : 1;
  }
  return left === right ? 0 : left < right ? -1 : 1;
}

function normalizeNumericEventId(value: string) {
  if (!/^\d+$/.test(value)) return null;
  return value.replace(/^0+(?=\d)/, "");
}

function eventScope() {
  return `${getStoredTenantId() ?? ""}:${getStoredProjectId() ?? ""}`;
}
