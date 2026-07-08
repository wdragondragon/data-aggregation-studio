package com.jdragon.studio.flink.execution;

public interface FlinkExecutionClient {
    String executionMode();

    FlinkExecutionResult execute(FlinkExecutionRequest request) throws Exception;
}
