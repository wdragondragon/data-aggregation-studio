package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.commons.util.DynamicFunctionResolver;

import java.time.LocalDateTime;

/** Compatibility facade for Flink file path pushdown. */
final class AggregationDynamicFunctionEvaluator {

    private AggregationDynamicFunctionEvaluator() {
    }

    static String replaceAll(String content) {
        return replaceAll(content, LocalDateTime.now());
    }

    static String replaceAll(String content, LocalDateTime baseTime) {
        return DynamicFunctionResolver.replaceAll(content, baseTime,
                DynamicFunctionResolver.Mode.COMPATIBLE);
    }
}
