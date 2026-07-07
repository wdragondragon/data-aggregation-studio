package com.jdragon.studio.flink.connector;

import java.util.Map;

interface AggregationRowEmitter {
    boolean emit(Map<String, Object> row);
}
