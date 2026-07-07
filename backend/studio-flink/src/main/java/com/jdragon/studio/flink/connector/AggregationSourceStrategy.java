package com.jdragon.studio.flink.connector;

interface AggregationSourceStrategy {
    void readRows(AggregationFlinkTableRuntime runtime, AggregationRowEmitter emitter) throws Exception;
}
