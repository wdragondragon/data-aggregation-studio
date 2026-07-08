package com.jdragon.studio.flink.connector;

final class AggregationRuntimeResolver {
    private AggregationRuntimeResolver() {
    }

    static AggregationFlinkTableRuntime resolve(AggregationRuntimeHandle handle) {
        if (handle == null) {
            throw new IllegalStateException("DataAggregation Flink runtime handle is required");
        }
        if (handle.isLocal()) {
            return AggregationFlinkRuntimeRegistry.required(handle.getRuntimeRef());
        }
        if (handle.isRemote()) {
            return AggregationRemoteRuntimeClient.resolve(handle.getRuntimeEndpoint(), handle.getRuntimeToken());
        }
        throw new IllegalStateException("DataAggregation Flink runtime ref or endpoint/token is required");
    }

    static void updateAudit(AggregationRuntimeHandle handle, AggregationFlinkTableRuntime runtime) {
        if (handle == null || runtime == null) {
            return;
        }
        if (handle.isLocal()) {
            AggregationFlinkRuntimeRegistry.updateAudit(handle.getRuntimeRef(),
                    AggregationFlinkTableRuntimePayload.fromRuntime(runtime));
            return;
        }
        if (handle.isRemote()) {
            AggregationRemoteRuntimeClient.updateAudit(handle.getRuntimeEndpoint(), handle.getRuntimeToken(), runtime);
        }
    }
}
