package com.jdragon.studio.flink.connector;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AggregationFlinkRuntimeRegistry {
    public static final String CAPABILITY_TOKEN_HEADER = "X-Studio-Flink-Runtime-Token";
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<String, Entry>();

    private AggregationFlinkRuntimeRegistry() {
    }

    public static String register(AggregationFlinkTableRuntime runtime, int ttlSeconds) {
        String ref = UUID.randomUUID().toString();
        runtime.setRuntimeRef(ref);
        long expiresAt = Instant.now().toEpochMilli() + Math.max(30, ttlSeconds) * 1000L;
        ENTRIES.put(ref, new Entry(runtime, expiresAt));
        return ref;
    }

    public static AggregationFlinkTableRuntime required(String ref) {
        Entry entry = ENTRIES.get(ref);
        if (entry == null || entry.expiresAt < Instant.now().toEpochMilli()) {
            ENTRIES.remove(ref);
            throw new IllegalStateException("DataAggregation Flink runtime ref expired or missing");
        }
        return entry.runtime;
    }

    public static boolean isValid(String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            return false;
        }
        Entry entry = ENTRIES.get(ref);
        if (entry == null) {
            return false;
        }
        if (entry.expiresAt < Instant.now().toEpochMilli()) {
            ENTRIES.remove(ref, entry);
            return false;
        }
        return true;
    }

    public static AggregationFlinkTableRuntimePayload resolvePayload(String ref) {
        return AggregationFlinkTableRuntimePayload.fromRuntime(required(ref));
    }

    public static void updateAudit(String ref, AggregationFlinkTableRuntimePayload payload) {
        if (payload == null) {
            return;
        }
        payload.mergeAuditInto(required(ref));
    }

    public static void remove(String ref) {
        if (ref != null) {
            ENTRIES.remove(ref);
        }
    }

    private static class Entry {
        private final AggregationFlinkTableRuntime runtime;
        private final long expiresAt;

        private Entry(AggregationFlinkTableRuntime runtime, long expiresAt) {
            this.runtime = runtime;
            this.expiresAt = expiresAt;
        }
    }
}
