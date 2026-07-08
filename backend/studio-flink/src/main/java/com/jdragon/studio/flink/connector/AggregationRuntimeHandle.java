package com.jdragon.studio.flink.connector;

import java.io.Serializable;

public class AggregationRuntimeHandle implements Serializable {
    private final String runtimeRef;
    private final String runtimeEndpoint;
    private final String runtimeToken;

    public AggregationRuntimeHandle(String runtimeRef, String runtimeEndpoint, String runtimeToken) {
        this.runtimeRef = trimToNull(runtimeRef);
        this.runtimeEndpoint = trimToNull(runtimeEndpoint);
        this.runtimeToken = trimToNull(runtimeToken);
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

    public String summary() {
        return isLocal() ? runtimeRef : runtimeToken;
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
