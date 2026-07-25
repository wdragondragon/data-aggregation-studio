package com.jdragon.studio.flink.connector;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Serializable;

public class AggregationRuntimeHandle implements Serializable {
    private static final long serialVersionUID = -4997852490667400347L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String runtimeRef;
    private final String runtimeEndpoint;
    private final String runtimeToken;
    private String runtimeStateJson;

    public AggregationRuntimeHandle(String runtimeRef, String runtimeEndpoint, String runtimeToken) {
        this(runtimeRef, runtimeEndpoint, runtimeToken, null);
    }

    private AggregationRuntimeHandle(String runtimeRef,
                                     String runtimeEndpoint,
                                     String runtimeToken,
                                     String runtimeStateJson) {
        this.runtimeRef = trimToNull(runtimeRef);
        this.runtimeEndpoint = trimToNull(runtimeEndpoint);
        this.runtimeToken = trimToNull(runtimeToken);
        this.runtimeStateJson = trimToNull(runtimeStateJson);
    }

    public static AggregationRuntimeHandle local(String runtimeRef) {
        return new AggregationRuntimeHandle(runtimeRef, null, null);
    }

    public static AggregationRuntimeHandle remote(String runtimeEndpoint, String runtimeToken) {
        return new AggregationRuntimeHandle(null, runtimeEndpoint, runtimeToken);
    }

    public boolean isRemote() {
        return runtimeEndpoint != null && runtimeToken != null;
    }

    public boolean isLocal() {
        return runtimeRef != null;
    }

    public String getRuntimeRef() {
        return runtimeRef;
    }

    public String getRuntimeEndpoint() {
        return runtimeEndpoint;
    }

    public String getRuntimeToken() {
        return runtimeToken;
    }

    synchronized AggregationRuntimeHandle copy() {
        return new AggregationRuntimeHandle(runtimeRef, runtimeEndpoint, runtimeToken, runtimeStateJson);
    }

    synchronized void captureRuntimeState(AggregationFlinkTableRuntime runtime) {
        try {
            runtimeStateJson = OBJECT_MAPPER.writeValueAsString(
                    AggregationFlinkTableRuntimePayload.auditFromRuntime(runtime));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize DataAggregation Flink runtime state", ex);
        }
    }

    synchronized void applyRuntimeState(AggregationFlinkTableRuntime runtime) {
        if (runtime == null || runtimeStateJson == null) {
            return;
        }
        try {
            OBJECT_MAPPER.readValue(runtimeStateJson, AggregationFlinkTableRuntimePayload.class)
                    .mergeAuditInto(runtime);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize DataAggregation Flink runtime state", ex);
        }
    }

    public String summary() {
        String mode = isLocal() ? "local" : "remote";
        return mode + "-" + Integer.toUnsignedString(System.identityHashCode(this), 16);
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
