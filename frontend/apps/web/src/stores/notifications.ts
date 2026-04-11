import { computed, ref } from "vue";
import { defineStore } from "pinia";
import type { NotificationSnapshotView, NotificationView } from "@studio/api-sdk";
import { studioApi } from "@/api/studio";
import { buildStudioRequestHeaders, resolveStudioApiBaseUrl } from "@/api/studio";

const RECONNECT_DELAY_MS = 3000;

interface ParsedSseEvent {
  event: string;
  data: string;
}

export const useNotificationStore = defineStore("studio-notifications", () => {
  const unreadCount = ref(0);
  const recentNotifications = ref<NotificationView[]>([]);
  const connected = ref(false);
  const connecting = ref(false);
  const lastError = ref<string | null>(null);

  let abortController: AbortController | null = null;
  let reconnectTimer: number | null = null;

  const hasUnread = computed(() => unreadCount.value > 0);

  function applySnapshot(snapshot?: NotificationSnapshotView | null) {
    unreadCount.value = Number(snapshot?.unreadCount ?? 0);
    recentNotifications.value = Array.isArray(snapshot?.recentNotifications) ? snapshot!.recentNotifications : [];
  }

  async function loadSnapshot() {
    try {
      const snapshot = await studioApi.notifications.snapshot();
      applySnapshot(snapshot);
      lastError.value = null;
      return snapshot;
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : "Failed to load notifications";
      throw error;
    }
  }

  async function markRead(id: string | number) {
    await studioApi.notifications.markRead(id);
    await loadSnapshot();
  }

  async function markAllRead() {
    await studioApi.notifications.markAllRead();
    await loadSnapshot();
  }

  function start() {
    if (connecting.value || connected.value) {
      return;
    }
    void connect();
  }

  function stop() {
    connecting.value = false;
    connected.value = false;
    clearReconnectTimer();
    if (abortController) {
      abortController.abort();
      abortController = null;
    }
  }

  async function connect() {
    if (connecting.value || connected.value) {
      return;
    }
    connecting.value = true;
    lastError.value = null;
    abortController = new AbortController();
    try {
      const response = await fetch(resolveStudioApiBaseUrl("/notifications/stream"), {
        method: "GET",
        headers: {
          Accept: "text/event-stream",
          ...buildStudioRequestHeaders(),
        },
        signal: abortController.signal,
      });
      if (!response.ok || !response.body) {
        throw new Error(`Failed to connect notification stream: ${response.status}`);
      }
      connected.value = true;
      connecting.value = false;
      await consumeStream(response.body, abortController.signal);
    } catch (error) {
      if (abortController?.signal.aborted) {
        return;
      }
      lastError.value = error instanceof Error ? error.message : "Notification stream disconnected";
      connected.value = false;
      connecting.value = false;
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
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        const parsed = splitSsePayload(buffer);
        buffer = parsed.rest;
        for (const event of parsed.events) {
          handleSseEvent(event);
        }
      }
    } finally {
      try {
        reader.releaseLock();
      } catch {
        // Ignore release errors during shutdown.
      }
      connected.value = false;
      connecting.value = false;
      if (!signal.aborted) {
        scheduleReconnect();
      }
    }
  }

  function handleSseEvent(event: ParsedSseEvent) {
    if (!event.data) {
      return;
    }
    try {
      const payload = JSON.parse(event.data) as NotificationSnapshotView;
      applySnapshot(payload);
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : "Failed to parse notification stream payload";
    }
  }

  function scheduleReconnect() {
    if (reconnectTimer != null) {
      return;
    }
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null;
      void connect();
    }, RECONNECT_DELAY_MS);
  }

  function clearReconnectTimer() {
    if (reconnectTimer != null) {
      window.clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  }

  return {
    unreadCount,
    recentNotifications,
    connected,
    connecting,
    lastError,
    hasUnread,
    loadSnapshot,
    markRead,
    markAllRead,
    start,
    stop,
  };
});

function splitSsePayload(payload: string) {
  const delimiter = /\r?\n\r?\n/g;
  const events: ParsedSseEvent[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = delimiter.exec(payload)) != null) {
    const block = payload.slice(lastIndex, match.index);
    lastIndex = match.index + match[0].length;
    const parsed = parseSseEvent(block);
    if (parsed) {
      events.push(parsed);
    }
  }
  return {
    events,
    rest: payload.slice(lastIndex),
  };
}

function parseSseEvent(block: string): ParsedSseEvent | null {
  if (!block.trim()) {
    return null;
  }
  const lines = block.split(/\r?\n/);
  let eventName = "message";
  const dataLines: string[] = [];
  for (const line of lines) {
    if (!line || line.startsWith(":")) {
      continue;
    }
    if (line.startsWith("event:")) {
      eventName = line.slice(6).trim() || "message";
      continue;
    }
    if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trim());
    }
  }
  return {
    event: eventName,
    data: dataLines.join("\n"),
  };
}
