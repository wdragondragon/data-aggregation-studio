package com.jdragon.studio.flink.execution;

public class FlinkRuntimeConnectorAccess {
    private final String runtimeRef;
    private final String runtimeEndpoint;
    private final String runtimeToken;

    private FlinkRuntimeConnectorAccess(String runtimeRef, String runtimeEndpoint, String runtimeToken) {
        this.runtimeRef = runtimeRef;
        this.runtimeEndpoint = runtimeEndpoint;
        this.runtimeToken = runtimeToken;
    }

    public static FlinkRuntimeConnectorAccess local(String runtimeRef) {
        return new FlinkRuntimeConnectorAccess(runtimeRef, null, null);
    }

    public static FlinkRuntimeConnectorAccess remote(String runtimeEndpoint, String runtimeToken) {
        return new FlinkRuntimeConnectorAccess(null, runtimeEndpoint, runtimeToken);
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

    public boolean isRemote() {
        return runtimeEndpoint != null && runtimeToken != null;
    }
}
