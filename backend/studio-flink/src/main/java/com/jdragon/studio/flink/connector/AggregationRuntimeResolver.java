package com.jdragon.studio.flink.connector;

final class AggregationRuntimeResolver {
    private AggregationRuntimeResolver() {
    }

    static AggregationFlinkTableRuntime resolve(AggregationRuntimeHandle handle) {
        if (handle == null) {
            throw new IllegalStateException("DataAggregation Flink runtime handle is required");
        }
        AggregationFlinkTableRuntime runtime;
        if (handle.isLocal()) {
            runtime = copyWithoutRuntimeState(AggregationFlinkRuntimeRegistry.required(handle.getRuntimeRef()));
        } else if (handle.isRemote()) {
            runtime = AggregationRemoteRuntimeClient.resolve(
                    handle.getRuntimeEndpoint(), handle.getRuntimeToken());
            clearRuntimeState(runtime);
            runtime.setPluginRuntimeEndpoint(handle.getRuntimeEndpoint());
            runtime.setPluginRuntimeToken(handle.getRuntimeToken());
        } else {
            throw new IllegalStateException("DataAggregation Flink runtime ref or endpoint/token is required");
        }
        try {
            handle.applyRuntimeState(runtime);
            return runtime;
        } catch (RuntimeException ex) {
            runtime.closeRuntimeResource();
            throw ex;
        }
    }

    static void captureRuntimeState(AggregationRuntimeHandle handle, AggregationFlinkTableRuntime runtime) {
        if (handle != null && runtime != null) {
            handle.captureRuntimeState(runtime);
        }
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

    private static AggregationFlinkTableRuntime copyWithoutRuntimeState(AggregationFlinkTableRuntime source) {
        AggregationFlinkTableRuntime runtime = AggregationFlinkTableRuntimePayload.fromRuntime(source).toRuntime();
        clearRuntimeState(runtime);
        return runtime;
    }

    private static void clearRuntimeState(AggregationFlinkTableRuntime runtime) {
        runtime.setPushedFilters(null);
        runtime.setRemainingFilters(null);
        runtime.setPathContextFilters(null);
        runtime.setHttpPushdownFilters(null);
        runtime.setHttpFilterAlwaysFalse(false);
        runtime.setResolvedSourceSql(null);
        runtime.setResolvedFilePaths(null);
    }
}
