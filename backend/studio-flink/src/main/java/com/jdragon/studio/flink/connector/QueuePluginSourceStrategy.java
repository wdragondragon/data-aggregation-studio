package com.jdragon.studio.flink.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.datasource.queue.QueueAbstract;
import com.jdragon.aggregation.pluginloader.PluginClassLoaderCloseable;

import java.util.LinkedHashMap;
import java.util.Map;

class QueuePluginSourceStrategy implements AggregationSourceStrategy {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void readRows(AggregationFlinkTableRuntime runtime, AggregationRowEmitter emitter) throws Exception {
        ConnectorPluginRuntimeBootstrap.runWithReady(runtime, () -> {
            try (PluginClassLoaderCloseable loader =
                         PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, runtime.getPluginName())) {
                QueueAbstract queue = loader.loadPlugin();
                queue.setPluginQueueConf(runtime.getConnectionConfig());
                queue.init();
                try {
                    queue.receiveMessage(message -> emitter.emit(toRow(message)));
                } finally {
                    queue.destroy();
                }
            }
        });
    }

    private Map<String, Object> toRow(String message) {
        if (message != null && message.trim().startsWith("{")) {
            try {
                return OBJECT_MAPPER.readValue(message, new TypeReference<LinkedHashMap<String, Object>>() {
                });
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("payload", message);
        return row;
    }
}
