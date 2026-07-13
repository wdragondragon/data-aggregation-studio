package com.jdragon.studio.flink.connector;

final class AggregationSourceStrategyFactory {
    private AggregationSourceStrategyFactory() {
    }

    static AggregationSourceStrategy create(String pluginName) {
        AggregationPluginKind kind = AggregationPluginClassifier.classify(pluginName);
        if (kind == AggregationPluginKind.FILE) {
            return new FilePluginSourceStrategy();
        }
        if (kind == AggregationPluginKind.QUEUE) {
            return new QueuePluginSourceStrategy();
        }
        if (kind == AggregationPluginKind.HTTP) {
            return new StructuredPluginSourceStrategy();
        }
        return new StructuredPluginSourceStrategy();
    }
}
