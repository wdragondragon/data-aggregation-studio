package com.jdragon.studio.flink.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.datasource.queue.QueueAbstract;
import com.jdragon.aggregation.pluginloader.PluginClassLoaderCloseable;
import com.jdragon.aggregation.commons.util.Configuration;

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
                Configuration connection = runtime.getConnectionConfig() == null
                        ? Configuration.newDefault()
                        : Configuration.from(runtime.getConnectionConfig().toJSON());
                if (runtime.getPhysicalLocator() != null
                        && !runtime.getPhysicalLocator().trim().isEmpty()) {
                    connection.set("topic", runtime.getPhysicalLocator().trim());
                }
                if ("kafka".equalsIgnoreCase(runtime.getPluginName())
                        && isBlank(readLiteralOrPath(connection, "group.id"))) {
                    String datasourcePart = runtime.getDatasourceId() == null
                            ? "unknown-datasource" : String.valueOf(runtime.getDatasourceId());
                    String modelPart = runtime.getModelId() == null
                            ? "unknown-model" : String.valueOf(runtime.getModelId());
                    connection.setMapValue("", "group.id", ("studio.flink." + datasourcePart + "." + modelPart)
                            .replaceAll("[^A-Za-z0-9._-]", "_"));
                }
                queue.setPluginQueueConf(connection);
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

    private Object readLiteralOrPath(Configuration configuration, String key) {
        Object literalValue = configuration.getMapValue("", key);
        if (literalValue != null) {
            return literalValue;
        }
        try {
            return configuration.get(key);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isBlank(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }
}
